package com.kevin.midnightai.link

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.Socket
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.util.Collections
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pairing listener over loopback (review H3, M1, L6): every write on the pairing worker, never
 * the caller's thread; unauthenticated connections cannot block or kill the session.
 */
@OptIn(ExperimentalEncodingApi::class)
class PairingServerTest {

    private val loopback = InetAddress.getLoopbackAddress()
    private var commits = 0
    private val sink = object : HandoverSink {
        override fun stage(handover: Handover, linkKey: ByteArray): String? = null
        override fun commit(): String? { commits++; return null }
    }

    private fun server(): Pair<PairingServer, String> {
        val session = PairingSession(
            PairingCrypto.generateKeyPair(), ByteArray(16).also { SecureRandom().nextBytes(it) }, sink, now = { System.nanoTime() / 1_000_000L },
        )
        val server = PairingServer(session, loopback)
        server.start()
        return server to session.qrText(loopback.hostAddress!!, server.port)
    }

    private class Phone(qr: String) {
        private val parts = qr.split(':')
        val unitPk: ByteArray = Base64.UrlSafe.decode(parts[2])
        val secret: ByteArray = Base64.UrlSafe.decode(parts[3])
        val port = parts[5].toInt()
        private val kp = PairingCrypto.generateKeyPair()
        val pk = PairingCrypto.encode(kp.public as ECPublicKey)
        val keys: PairingCrypto.Keys by lazy {
            PairingCrypto.derive(
                PairingCrypto.sharedSecret(kp.private, PairingCrypto.decode(unitPk, kp.public as ECPublicKey)!!),
                secret, PairingCrypto.transcript(unitPk, pk),
            )
        }

        fun hello() = JSONObject().put("t", "hello").put("v", 1).put("pk", Base64.Default.encode(pk))
            .put("mac", Base64.Default.encode(PairingCrypto.helloMac(PairingCrypto.qrKey(secret), unitPk, pk)))

        fun sealed(t: String, dir: ByteArray, aad: ByteArray, body: String) = JSONObject().put("t", t)
            .put("c", Base64.Default.encode(LinkCrypto.seal(keys.handover, LinkCrypto.nonce(dir, 1), aad, body.toByteArray())))
    }

    private fun Socket.out() = DataOutputStream(getOutputStream())
    private fun Socket.read(): JSONObject = PairingServer.readFrame(DataInputStream(getInputStream()))

    private fun waitFor(server: PairingServer, ok: (PairingSession.Stage) -> Boolean) {
        val until = System.currentTimeMillis() + 5_000
        while (!ok(server.stage.value)) {
            check(System.currentTimeMillis() < until) { "stage stuck at ${server.stage.value}" }
            Thread.sleep(20)
        }
    }

    @Test fun h3_aFullPairingWhereEveryWriteIsOnTheWorkerNotTheCaller() {
        val (server, qr) = server()
        val writers = Collections.synchronizedList(mutableListOf<Thread>())
        server.onWrite = { writers += it }
        val phone = Phone(qr)
        Socket(loopback, phone.port).use { sock ->
            sock.soTimeout = 5_000
            PairingServer.writeFrame(sock.out(), phone.hello())
            assertEquals("ack", sock.read().getString("t"))
            waitFor(server) { it is PairingSession.Stage.EnterCode }
            // The driver types the digits on the unit's pad: this test thread plays the UI thread.
            server.enterCode(phone.keys.checkCode)
            assertEquals("code_ok", sock.read().getString("t"))
            PairingServer.writeFrame(sock.out(), phone.sealed("handover", PairingSession.HAND, PairingSession.HANDOVER_AAD, """{"v":1,"phone_name":"Test phone"}"""))
            assertEquals("done", sock.read().getString("t"))
            PairingServer.writeFrame(sock.out(), phone.sealed("stored", PairingSession.STOR, PairingSession.STORED_AAD, """{"ok":true}"""))
            waitFor(server) { it is PairingSession.Stage.Paired }
        }
        assertEquals(1, commits)
        assertTrue(writers.isNotEmpty())
        assertTrue("written on ${writers.map { it.name }}", writers.none { it === Thread.currentThread() })
        assertTrue(writers.all { it.name == "pairing-worker" })
        server.close()
    }

    @Test fun m1_idleAndJunkConnectionsNeitherBlockNorKillThePairing() {
        val (server, qr) = server()
        val idle = Socket(loopback, Phone(qr).port)
        Socket(loopback, Phone(qr).port).use { junk ->
            junk.out().apply { writeInt(PairingServer.MAX_FRAME + 1); flush() }
        }
        Socket(loopback, Phone(qr).port).use { junk ->
            PairingServer.writeFrame(junk.out(), JSONObject().put("t", "abort").put("why", "x"))
        }
        Thread.sleep(300)
        assertEquals(PairingSession.Stage.WaitingForPhone, server.stage.value)
        // The real phone gets through while the idle one still holds a socket.
        val phone = Phone(qr)
        Socket(loopback, phone.port).use { sock ->
            sock.soTimeout = 5_000
            PairingServer.writeFrame(sock.out(), phone.hello())
            assertEquals("ack", sock.read().getString("t"))
        }
        idle.close()
        server.close()
    }

    @Test fun l6_aFrameHeaderSplitAcrossWritesIsReassembled() {
        val (server, qr) = server()
        val phone = Phone(qr)
        Socket(loopback, phone.port).use { sock ->
            sock.soTimeout = 5_000
            val bytes = phone.hello().toString().toByteArray()
            val out = sock.out()
            out.write(byteArrayOf(0, 0)); out.flush()
            Thread.sleep(700) // longer than the server's read poll
            out.write(byteArrayOf((bytes.size shr 8).toByte(), bytes.size.toByte())); out.write(bytes); out.flush()
            assertEquals("ack", sock.read().getString("t"))
        }
        server.close()
    }

    @Test fun h3_aLostStoredLeavesThePairingWaitingForThePhone() {
        val (server, qr) = server()
        val phone = Phone(qr)
        Socket(loopback, phone.port).use { sock ->
            sock.soTimeout = 5_000
            PairingServer.writeFrame(sock.out(), phone.hello())
            sock.read()
            waitFor(server) { it is PairingSession.Stage.EnterCode }
            server.enterCode(phone.keys.checkCode)
            sock.read()
            PairingServer.writeFrame(sock.out(), phone.sealed("handover", PairingSession.HAND, PairingSession.HANDOVER_AAD, """{"v":1}"""))
            assertEquals("done", sock.read().getString("t"))
        } // the phone vanishes before `stored`
        waitFor(server) { it is PairingSession.Stage.Paired }
        assertEquals(PairingSession.Stage.Paired(null, emptyList(), confirmed = false), server.stage.value)
        assertEquals(0, commits)
        server.close()
    }
}

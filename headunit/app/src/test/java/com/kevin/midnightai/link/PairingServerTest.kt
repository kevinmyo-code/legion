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
        override fun dropPending() {}
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

    @org.junit.After fun clearSessionFlag() { CarLink.resetForTest() }

    /** Review P5: an old server closing late (after TRY AGAIN) must not switch off the new session's N1 protection. */
    @Test fun p5_aLateCloseOfTheOldSessionLeavesTheNewOneProtected() {
        val (old, _) = server()
        val (new, _) = server()
        old.close()
        Thread.sleep(300)
        assertTrue("closing the old session switched off the new one", CarLink.pairingSessionOpen)
        new.close()
        Thread.sleep(300)
        assertEquals(false, CarLink.pairingSessionOpen)
    }

    /** Review P1: the session flag (N1 protection) ends with the session, not only with close(). */
    @Test fun p1_theSessionFlagClearsWhenTheSessionFailsEvenIfTheScreenNeverCloses() {
        val (server, qr) = server()
        assertTrue(CarLink.pairingSessionOpen)
        val phone = Phone(qr)
        Socket(loopback, phone.port).use { sock ->
            sock.soTimeout = 5_000
            PairingServer.writeFrame(sock.out(), phone.hello())
            assertEquals("ack", sock.read().getString("t"))
            waitFor(server) { it is PairingSession.Stage.EnterCode }
            val wrong = if (phone.keys.checkCode == "000000") "000001" else "000000"
            repeat(3) { server.enterCode(wrong) }
            waitFor(server) { it is PairingSession.Stage.Failed }
        }
        Thread.sleep(200)
        assertEquals("session over but still flagged open", false, CarLink.pairingSessionOpen)
        server.close()
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
        // Separate source addresses: one address gets one new connection per 250 ms.
        val idle = from(30, Phone(qr).port)
        from(31, Phone(qr).port).use { junk ->
            junk.out().apply { writeInt(PairingServer.MAX_FRAME + 1); flush() }
        }
        from(32, Phone(qr).port).use { junk ->
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

    @Test fun m1_idleConnectionsCannotHoldTheSlotsAgainstThePhone() {
        val (server, qr) = server()
        val idle = List(3) { Socket(loopback, Phone(qr).port) }
        Thread.sleep(300) // past the per-address rate limit (all of these share one address)
        val phone = Phone(qr)
        Socket(loopback, phone.port).use { sock ->
            sock.soTimeout = 5_000
            PairingServer.writeFrame(sock.out(), phone.hello())
            assertEquals("ack", sock.read().getString("t"))
        }
        idle.forEach { it.close() }
        server.close()
    }

    /** Read state of an idle client: -1 closed by the server, -2 still open (read timed out). */
    private fun Socket.state(): Int {
        soTimeout = 300
        return try {
            getInputStream().read()
        } catch (_: java.net.SocketTimeoutException) {
            -2 // still open, nothing to read
        } catch (_: java.io.IOException) {
            -1 // reset by the server: closed
        }
    }

    private fun from(last: Int, port: Int) = Socket(loopback, port, InetAddress.getByName("127.0.0.$last"), 0)

    @Test fun m1_whenAllSlotsAreTakenTheOldestIsEvictedNotTheNewcomer() {
        val (server, qr) = server()
        val port = Phone(qr).port
        // Review M1 hardening (recheck c53f1fc3): 8 pre-auth slots, not 3.
        val idle = (2..9).map { from(it, port).also { Thread.sleep(30) } }
        Thread.sleep(350) // every slot past the 300 ms minimum age
        val fourth = from(10, port)
        Thread.sleep(300)
        assertEquals("oldest not evicted", -1, idle[0].state())
        assertEquals("a newer idle one was closed", -2, idle[1].state())
        assertEquals("the newcomer was refused", -2, fourth.state())
        (idle + fourth).forEach { it.close() }
        server.close()
    }

    @Test fun m1_oneSlotPerSourceAddress() {
        val (server, qr) = server()
        val port = Phone(qr).port
        val first = from(7, port)
        Thread.sleep(300) // past the per-address rate limit
        val second = from(7, port)
        Thread.sleep(300)
        assertEquals("the older connection from the same address was not closed", -1, first.state())
        assertEquals(-2, second.state())
        first.close(); second.close()
        server.close()
    }

    /** M1 hardening: one new connection per address per 250 ms; a burst from one address is refused, not let in. */
    @Test fun m1_aBurstFromOneAddressIsRateLimited() {
        val (server, qr) = server()
        val port = Phone(qr).port
        val first = from(11, port)
        Thread.sleep(50)
        val burst = from(11, port)
        Thread.sleep(200)
        assertEquals("a burst connection was let in", -1, burst.state())
        assertEquals("the burst displaced the earlier connection", -2, first.state())
        first.close(); burst.close()
        server.close()
    }

    /** M1 hardening: when full, a connection that has sent nothing goes before one that is mid-message. */
    @Test fun m1_aSilentConnectionIsEvictedBeforeOneThatHasStartedItsHello() {
        val (server, qr) = server()
        val port = Phone(qr).port
        val idle = (20..27).map { from(it, port).also { Thread.sleep(30) } }
        idle[0].getOutputStream().apply { write(byteArrayOf(0, 0)); flush() } // half a frame header
        Thread.sleep(350) // every slot past the 300 ms minimum age
        val newcomer = from(28, port)
        Thread.sleep(200)
        assertEquals("the connection mid-hello was evicted", -2, idle[0].state())
        assertEquals("the oldest silent connection was kept", -1, idle[1].state())
        assertEquals(-2, newcomer.state())
        (idle + newcomer).forEach { it.close() }
        server.close()
    }

    /**
     * Q1 (recheck 2faecc19): a connection younger than 300 ms is never evicted, so a phone in the
     * first milliseconds of its hello cannot be the preferred (silent) victim. With every slot that
     * young, the newcomer is refused instead.
     */
    @Test fun m1_aConnectionYoungerThan300msIsNeverEvicted() {
        val (server, qr) = server()
        val port = Phone(qr).port
        val young = (40..47).map { from(it, port) }
        val newcomer = from(48, port)
        Thread.sleep(150)
        assertEquals("a connection under 300 ms old was evicted", -2, young[0].state())
        assertEquals("the newcomer should be refused while every slot is young", -1, newcomer.state())
        (young + newcomer).forEach { it.close() }
        server.close()
    }

    @Test fun m1_anIdleConnectionIsClosedWithinThreeSeconds() {
        val (server, qr) = server()
        Socket(loopback, Phone(qr).port).use { idle ->
            idle.soTimeout = 3_600
            val started = System.currentTimeMillis()
            val r = runCatching { idle.getInputStream().read() }.getOrDefault(-2)
            assertEquals("closed by the server, not a read timeout", -1, r)
            assertTrue(System.currentTimeMillis() - started < 3_500)
        }
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

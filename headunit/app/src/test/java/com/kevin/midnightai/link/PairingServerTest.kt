package com.kevin.midnightai.link

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.Socket
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import kotlin.concurrent.thread
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** HU-1 T3: the pairing listener end to end over loopback, framed exactly as the spec says. */
@OptIn(ExperimentalEncodingApi::class)
class PairingServerTest {

    @Test fun aFakePhonePairsOverTcp() {
        var storedToken: String? = null
        val session = PairingSession(
            PairingCrypto.generateKeyPair(), ByteArray(16).also { SecureRandom().nextBytes(it) },
            HandoverSink { h, _ -> storedToken = h.engineToken; null }, now = System::currentTimeMillis,
        )
        val loopback = InetAddress.getLoopbackAddress()
        val server = PairingServer(session, loopback)
        val qr = session.qrText(loopback.hostAddress!!, server.port)
        val runner = thread { server.run() }

        val parts = qr.split(':')
        val unitPk = Base64.UrlSafe.decode(parts[2])
        val secret = Base64.UrlSafe.decode(parts[3])
        val phoneKeys = PairingCrypto.generateKeyPair()
        val phonePk = PairingCrypto.encode(phoneKeys.public as ECPublicKey)

        Socket(loopback, parts[5].toInt()).use { sock ->
            sock.soTimeout = 5_000
            val out = DataOutputStream(sock.getOutputStream())
            val input = DataInputStream(sock.getInputStream())
            PairingServer.writeFrame(
                out,
                JSONObject().put("t", "hello").put("v", 1).put("pk", Base64.Default.encode(phonePk))
                    .put("mac", Base64.Default.encode(PairingCrypto.helloMac(PairingCrypto.qrKey(secret), unitPk, phonePk))),
            )
            assertEquals("ack", PairingServer.readFrame(input).getString("t"))
            val keys = PairingCrypto.derive(
                PairingCrypto.sharedSecret(phoneKeys.private, PairingCrypto.decode(unitPk, phoneKeys.public as ECPublicKey)!!),
                secret, PairingCrypto.transcript(unitPk, phonePk),
            )
            val body = JSONObject().put("v", 1).put("engine", JSONObject().put("token", "test-token"))
            val sealed = LinkCrypto.seal(keys.handover, LinkCrypto.nonce(PairingSession.HAND, 1), PairingSession.HANDOVER_AAD, body.toString().toByteArray())
            PairingServer.writeFrame(out, JSONObject().put("t", "handover").put("c", Base64.Default.encode(sealed)))
            // The driver taps "the numbers match" on the unit.
            server.confirm()
            assertEquals("done", PairingServer.readFrame(input).getString("t"))
        }
        runner.join(5_000)
        assertEquals("test-token", storedToken)
        assertTrue(server.stage.value is PairingSession.Stage.Paired)
    }

    @Test fun anOversizedFrameEndsTheSession() {
        val session = PairingSession(
            PairingCrypto.generateKeyPair(), ByteArray(16), HandoverSink { _, _ -> null }, now = System::currentTimeMillis,
        )
        val loopback = InetAddress.getLoopbackAddress()
        val server = PairingServer(session, loopback)
        val runner = thread { server.run() }
        Socket(loopback, server.port).use { sock ->
            DataOutputStream(sock.getOutputStream()).apply { writeInt(PairingServer.MAX_FRAME + 1); flush() }
            sock.soTimeout = 5_000
            assertEquals("abort", PairingServer.readFrame(DataInputStream(sock.getInputStream())).getString("t"))
        }
        runner.join(5_000)
        assertTrue(server.stage.value is PairingSession.Stage.Failed)
    }
}

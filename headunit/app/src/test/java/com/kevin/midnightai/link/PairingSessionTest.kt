package com.kevin.midnightai.link

import com.kevin.midnightai.link.PairingSession.Reason
import com.kevin.midnightai.link.PairingSession.Stage
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HU-1 T3 pairing, head unit end, against a fake phone written from the protocol spec only. Made-up
 * values throughout ("test-token", "AIza-test"): nothing real, nothing personal.
 */
@OptIn(ExperimentalEncodingApi::class)
class PairingSessionTest {

    private var clock = 1_000_000L
    private val stored = mutableListOf<Pair<Handover, ByteArray>>()
    private var storeError: String? = null
    private val sink = HandoverSink { h, key -> stored += h to key.copyOf(); storeError }

    private fun newSession(): Pair<PairingSession, String> {
        val secret = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val s = PairingSession(PairingCrypto.generateKeyPair(), secret, sink, now = { clock })
        return s to s.qrText("192.0.2.10", 40123)
    }

    /** The phone end, from the spec: parse the QR, hello, verify ack, seal the hand-over. */
    private class FakePhone(qr: String) {
        val unitPk: ByteArray
        val secret: ByteArray
        private val keyPair = PairingCrypto.generateKeyPair()
        val phonePk = PairingCrypto.encode(keyPair.public as ECPublicKey)
        lateinit var keys: PairingCrypto.Keys

        init {
            val parts = qr.split(':')
            require(parts[0] == "mnai-pair" && parts[1] == "1")
            unitPk = Base64.UrlSafe.decode(parts[2])
            secret = Base64.UrlSafe.decode(parts[3])
        }

        fun hello(macKey: ByteArray = PairingCrypto.qrKey(secret)) = JSONObject().put("t", "hello").put("v", 1)
            .put("pk", Base64.Default.encode(phonePk))
            .put("mac", Base64.Default.encode(PairingCrypto.helloMac(macKey, unitPk, phonePk)))

        fun onAck(ack: JSONObject): Boolean {
            val transcript = PairingCrypto.transcript(unitPk, phonePk)
            val ok = LinkCrypto.constantTimeEquals(
                Base64.Default.decode(ack.getString("mac")), PairingCrypto.ackMac(PairingCrypto.qrKey(secret), transcript),
            )
            val unit = PairingCrypto.decode(unitPk, keyPair.public as ECPublicKey)!!
            keys = PairingCrypto.derive(PairingCrypto.sharedSecret(keyPair.private, unit), secret, transcript)
            return ok
        }

        fun handover(): JSONObject {
            val body = JSONObject().put("v", 1)
                .put("engine", JSONObject().put("url", "https://engine.example").put("token", "test-token").put("user_id", "u-1"))
                .put("gemini_key", "AIza-test")
                .put("phone_name", "Test phone")
            val sealed = LinkCrypto.seal(
                keys.handover, LinkCrypto.nonce(PairingSession.HAND, 1), PairingSession.HANDOVER_AAD,
                body.toString().toByteArray(),
            )
            return JSONObject().put("t", "handover").put("c", Base64.Default.encode(sealed))
        }

        fun openDone(done: JSONObject): JSONObject? = LinkCrypto.open(
            keys.handover, LinkCrypto.nonce(PairingSession.DONE, 1), PairingSession.DONE_AAD,
            Base64.Default.decode(done.getString("c")),
        )?.let { JSONObject(String(it)) }
    }

    @Test fun theHappyPathStoresTheHandOverAndTheSameLinkKeyBothSidesDerived() {
        val (s, qr) = newSession()
        val phone = FakePhone(qr)
        val ack = s.onMessage(phone.hello()).single()
        assertEquals("ack", ack.getString("t"))
        assertTrue(phone.onAck(ack))
        val stage = s.stage as Stage.CompareCodes
        assertEquals(phone.keys.checkCode, stage.code)
        assertEquals(6, stage.code.length)

        assertTrue(s.onMessage(phone.handover()).isEmpty()) // waits for the driver's tap
        assertTrue(stored.isEmpty())
        val done = s.confirm().single()
        assertEquals(true, phone.openDone(done)!!.getBoolean("ok"))

        val (h, key) = stored.single()
        assertEquals("test-token", h.engineToken)
        assertEquals("AIza-test", h.geminiKey)
        assertArrayEquals(phone.keys.link, key)
        assertEquals(Stage.Paired("Test phone", listOf("engine sign-in", "Gemini key")), s.stage)
    }

    @Test fun confirmingBeforeTheHandOverArrivesAlsoWorks() {
        val (s, qr) = newSession()
        val phone = FakePhone(qr)
        phone.onAck(s.onMessage(phone.hello()).single())
        assertTrue(s.confirm().isEmpty())
        assertEquals("done", s.onMessage(phone.handover()).single().getString("t"))
        assertTrue(s.stage is Stage.Paired)
    }

    @Test fun aPhoneThatNeverSawTheQrIsRefusedAndTheQrDiesAfterThreeTries() {
        val (s, qr) = newSession()
        val intruder = FakePhone(qr)
        val wrong = ByteArray(32) { 1 }
        assertEquals("abort", s.onMessage(intruder.hello(wrong)).single().getString("t"))
        assertEquals(Stage.WaitingForPhone, s.stage)
        s.onMessage(intruder.hello(wrong))
        s.onMessage(intruder.hello(wrong))
        assertEquals(Stage.Failed(Reason.WRONG_QR), s.stage)
        // The real phone is now too late: a new QR is needed.
        assertTrue(s.onMessage(FakePhone(qr).hello()).isEmpty())
        assertTrue(stored.isEmpty())
    }

    @Test fun theNumbersDifferMeansNothingIsStored() {
        val (s, qr) = newSession()
        val phone = FakePhone(qr)
        phone.onAck(s.onMessage(phone.hello()).single())
        s.onMessage(phone.handover())
        assertEquals("abort", s.mismatch().single().getString("t"))
        assertEquals(Stage.Failed(Reason.MISMATCH), s.stage)
        assertTrue(s.confirm().isEmpty())
        assertTrue(stored.isEmpty())
    }

    @Test fun aTamperedHandOverIsRefused() {
        val (s, qr) = newSession()
        val phone = FakePhone(qr)
        phone.onAck(s.onMessage(phone.hello()).single())
        val h = phone.handover()
        val bytes = Base64.Default.decode(h.getString("c")).also { it[3] = (it[3].toInt() xor 1).toByte() }
        s.onMessage(h.put("c", Base64.Default.encode(bytes)))
        assertEquals(Stage.Failed(Reason.BAD_MESSAGE), s.stage)
        assertTrue(stored.isEmpty())
    }

    @Test fun timeoutsAndCancel() {
        val (s1, _) = newSession()
        clock += PairingSession.QR_LIFETIME_MS + 1
        assertEquals("abort", s1.tick()!!.getString("t"))
        assertEquals(Stage.Failed(Reason.QR_EXPIRED), s1.stage)

        val (s2, qr2) = newSession()
        val phone = FakePhone(qr2)
        phone.onAck(s2.onMessage(phone.hello()).single())
        clock += PairingSession.CODE_LIFETIME_MS + 1
        assertEquals(1, s2.confirm().size)
        assertEquals(Stage.Failed(Reason.CODE_TIMEOUT), s2.stage)

        val (s3, _) = newSession()
        s3.cancel()
        assertEquals(Stage.Failed(Reason.CANCELLED), s3.stage)
    }

    @Test fun aStoreFailureKeepsNothingAndSaysSo() {
        storeError = "keystore broken"
        val (s, qr) = newSession()
        val phone = FakePhone(qr)
        phone.onAck(s.onMessage(phone.hello()).single())
        s.onMessage(phone.handover())
        s.confirm()
        assertEquals(Stage.Failed(Reason.STORE_FAILED), s.stage)
    }

    @Test fun aWrongVersionIsRefused() {
        val (s, qr) = newSession()
        s.onMessage(FakePhone(qr).hello().put("v", 2))
        assertEquals(Stage.Failed(Reason.BAD_VERSION), s.stage)
    }

    @Test fun anInvalidPublicKeyIsRefused() {
        val (s, qr) = newSession()
        val phone = FakePhone(qr)
        val bogus = ByteArray(65).also { it[0] = 4; it[5] = 9 }
        val mac = PairingCrypto.helloMac(PairingCrypto.qrKey(phone.secret), phone.unitPk, bogus)
        s.onMessage(
            JSONObject().put("t", "hello").put("v", 1).put("pk", Base64.Default.encode(bogus)).put("mac", Base64.Default.encode(mac)),
        )
        assertEquals(Stage.Failed(Reason.BAD_MESSAGE), s.stage)
    }

    @Test fun nothingSecretIsEverInAToString() {
        val (s, qr) = newSession()
        val phone = FakePhone(qr)
        phone.onAck(s.onMessage(phone.hello()).single())
        s.onMessage(phone.handover())
        s.confirm()
        val texts = listOf(s.toString(), s.stage.toString(), stored.single().first.toString(), phone.keys.toString())
        texts.forEach { t ->
            assertFalse(t, t.contains("test-token") || t.contains("AIza") || t.contains("engine.example"))
        }
        assertNull(Handover.parse(JSONObject().put("v", 2)))
    }

    @Test fun bothSidesDeriveTheSameCheckCode() {
        val a = PairingCrypto.generateKeyPair()
        val b = PairingCrypto.generateKeyPair()
        val secret = ByteArray(16) { 5 }
        val pa = PairingCrypto.encode(a.public as ECPublicKey)
        val pb = PairingCrypto.encode(b.public as ECPublicKey)
        val t = PairingCrypto.transcript(pa, pb)
        val ka = PairingCrypto.derive(PairingCrypto.sharedSecret(a.private, PairingCrypto.decode(pb, a.public as ECPublicKey)!!), secret, t)
        val kb = PairingCrypto.derive(PairingCrypto.sharedSecret(b.private, PairingCrypto.decode(pa, b.public as ECPublicKey)!!), secret, t)
        assertEquals(ka.checkCode, kb.checkCode)
        assertArrayEquals(ka.link, kb.link)
    }
}

package com.kevin.midnightai.link

import com.kevin.midnightai.link.PairingSession.CodeResult
import com.kevin.midnightai.link.PairingSession.PreAuth
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
 * HU-1 T3 pairing, unit end, protocol revision 2. The fake phone below is written from the spec; it
 * is a convenience, not the proof (CarLinkVectorsTest checks the bytes against an independent
 * implementation). Made-up values only.
 */
@OptIn(ExperimentalEncodingApi::class)
class PairingSessionTest {

    private var clock = 1_000_000L
    private val staged = mutableListOf<Pair<Handover, ByteArray>>()
    private var commits = 0
    private var stageError: String? = null
    private var commitError: String? = null
    private var drops = 0
    private val sink = object : HandoverSink {
        override fun stage(handover: Handover, linkKey: ByteArray): String? {
            staged += handover to linkKey.copyOf()
            return stageError
        }
        override fun commit(): String? { commits++; return commitError }
        override fun dropPending() { drops++ }
    }

    private fun newSession(): Pair<PairingSession, String> {
        val secret = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val s = PairingSession(PairingCrypto.generateKeyPair(), secret, sink, now = { clock })
        return s to s.qrText("192.0.2.10", 40123)
    }

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

        fun hello(macKey: ByteArray = PairingCrypto.qrKey(secret), v: Int = 1) = JSONObject().put("t", "hello").put("v", v)
            .put("pk", Base64.Default.encode(phonePk))
            .put("mac", Base64.Default.encode(PairingCrypto.helloMac(macKey, unitPk, phonePk)))

        /** Verifies the unit's key confirmation and derives the same keys. */
        fun onAck(ack: JSONObject): Boolean {
            val transcript = PairingCrypto.transcript(unitPk, phonePk)
            val unit = PairingCrypto.decode(unitPk, keyPair.public as ECPublicKey)!!
            keys = PairingCrypto.derive(PairingCrypto.sharedSecret(keyPair.private, unit), secret, transcript)
            return LinkCrypto.constantTimeEquals(Base64.Default.decode(ack.getString("kc")), PairingCrypto.unitConfirm(keys.confirm, transcript))
        }

        fun opens(msg: JSONObject, dir: ByteArray, aad: ByteArray) =
            LinkCrypto.open(keys.handover, LinkCrypto.nonce(dir, 1), aad, Base64.Default.decode(msg.getString("c"))) != null

        fun handover(body: String = """{"v":1,"engine_token":"test-token","gemini_key":"AIza-test","phone_name":"Test phone"}""") =
            JSONObject().put("t", "handover").put(
                "c",
                Base64.Default.encode(LinkCrypto.seal(keys.handover, LinkCrypto.nonce(PairingSession.HAND, 1), PairingSession.HANDOVER_AAD, body.toByteArray())),
            )

        fun stored() = JSONObject().put("t", "stored").put(
            "c",
            Base64.Default.encode(LinkCrypto.seal(keys.handover, LinkCrypto.nonce(PairingSession.STOR, 1), PairingSession.STORED_AAD, """{"ok":true}""".toByteArray())),
        )
    }

    private fun claim(s: PairingSession, phone: FakePhone): JSONObject =
        (s.preAuth(phone.hello()) as PreAuth.Claimed).reply.also { assertTrue(phone.onAck(it)) }

    @Test fun theHappyPathTypedDigitsHandOverDoneStored() {
        val (s, qr) = newSession()
        val phone = FakePhone(qr)
        claim(s, phone)
        assertEquals(Stage.EnterCode(3), s.stage)
        val ok = s.enterCode(phone.keys.checkCode) as CodeResult.Correct
        assertTrue(phone.opens(ok.send, PairingSession.CDOK, PairingSession.CODE_OK_AAD))
        val done = s.onMessage(phone.handover()).single()
        assertTrue(phone.opens(done, PairingSession.DONE, PairingSession.DONE_AAD))
        val (h, key) = staged.single()
        assertEquals("test-token", h.engineToken)
        assertArrayEquals(phone.keys.link, key)
        assertEquals(0, commits)
        assertEquals(Stage.AwaitingPhone, s.stage)
        s.onMessage(phone.stored())
        assertEquals(1, commits)
        assertEquals(Stage.Paired("Test phone", listOf("engine sign-in", "Gemini key"), confirmed = true), s.stage)
    }

    @Test fun h3_aLostStoredLeavesThePairingPendingNotPairedAndNotFailed() {
        val (s, qr) = newSession()
        val phone = FakePhone(qr)
        claim(s, phone)
        s.enterCode(phone.keys.checkCode)
        s.onMessage(phone.handover())
        s.onDisconnect()
        assertEquals(0, commits)
        assertEquals(Stage.Paired("Test phone", listOf("engine sign-in", "Gemini key"), confirmed = false), s.stage)
    }

    private fun upToDone(): Pair<PairingSession, FakePhone> {
        val (s, qr) = newSession()
        val phone = FakePhone(qr)
        claim(s, phone)
        s.enterCode(phone.keys.checkCode)
        assertEquals("done", s.onMessage(phone.handover()).single().getString("t"))
        return s to phone
    }

    @Test fun n1_aStoredWithNothingPendingIsAFailureNeverPaired() {
        commitError = "nothing pending"
        val (s, phone) = upToDone()
        s.onMessage(phone.stored())
        assertTrue("stage ${s.stage}", s.stage is Stage.Failed)
    }

    @Test fun n4_aCommitThatKeepsFailingIsRetriedThenFailsVisibly() {
        commitError = "keystore broken"
        val (s, phone) = upToDone()
        s.onMessage(phone.stored())
        assertEquals(PairingSession.COMMIT_TRIES, commits)
        assertEquals(Stage.Failed(Reason.STORE_FAILED), s.stage)
    }

    @Test fun n2_cancelAfterDoneDropsThePendingPairing() {
        val (s, _) = upToDone()
        s.cancel()
        assertEquals(1, drops)
    }

    @Test fun n2_aBadStoredAfterDoneDropsThePendingPairing() {
        val (s, _) = upToDone()
        s.onMessage(JSONObject().put("t", "stored").put("c", Base64.Default.encode(ByteArray(40))))
        assertEquals(1, drops)
    }

    @Test fun m5_wrongDigitsThreeTimesEndTheSessionAndStoreNothing() {
        val (s, qr) = newSession()
        val phone = FakePhone(qr)
        claim(s, phone)
        val wrong = if (phone.keys.checkCode == "000000") "000001" else "000000"
        assertEquals(CodeResult.Wrong(2), s.enterCode(wrong))
        assertEquals(CodeResult.Wrong(1), s.enterCode(wrong))
        assertTrue(s.enterCode(wrong) is CodeResult.Dead)
        assertEquals(Stage.Failed(Reason.CODE_WRONG), s.stage)
        assertTrue(s.onMessage(phone.handover()).isEmpty())
        assertTrue(staged.isEmpty())
    }

    @Test fun aHandOverBeforeTheDigitsIsRefused() {
        val (s, qr) = newSession()
        val phone = FakePhone(qr)
        claim(s, phone)
        s.onMessage(phone.handover())
        assertEquals(Stage.Failed(Reason.BAD_MESSAGE), s.stage)
        assertTrue(staged.isEmpty())
    }

    @Test fun m1_unauthenticatedJunkNeverEndsTheSession() {
        val (s, qr) = newSession()
        assertEquals(PreAuth.Close, s.preAuth(JSONObject().put("t", "abort").put("why", "x")))
        assertEquals(PreAuth.Close, s.preAuth(JSONObject().put("t", "nonsense")))
        assertEquals(PreAuth.Close, s.preAuth(JSONObject().put("t", "hello").put("v", 1).put("pk", "!!!").put("mac", "!!!")))
        repeat(10) { assertEquals(PreAuth.Close, s.preAuth(FakePhone(qr).hello(ByteArray(32) { 1 }))) }
        assertEquals(Stage.WaitingForPhone, s.stage)
        // The real phone still pairs afterwards.
        claim(s, FakePhone(qr))
    }

    @Test fun l4_anUnreadableHandOverIsABadMessageNotABadVersion() {
        val (s, qr) = newSession()
        val phone = FakePhone(qr)
        claim(s, phone)
        s.enterCode(phone.keys.checkCode)
        s.onMessage(phone.handover("not json"))
        assertEquals(Stage.Failed(Reason.BAD_MESSAGE), s.stage)
    }

    @Test fun h4_theHandOverCarriesNoEngineAddress() {
        assertTrue(Handover::class.java.declaredFields.none { it.name.contains("url", ignoreCase = true) })
        val h = Handover.parse(JSONObject("""{"v":1,"engine":{"url":"http://evil.example","token":"t"},"engine_url":"http://evil.example"}"""))!!
        assertNull(h.engineToken)
        assertFalse(h.toString().contains("evil"))
    }

    @Test fun aSecondTryOfTheHelloAfterClaimingIsClosed() {
        val (s, qr) = newSession()
        claim(s, FakePhone(qr))
        assertEquals(PreAuth.Close, s.preAuth(FakePhone(qr).hello()))
    }

    @Test fun theNumbersAreNeverInAStageOrAToString() {
        val (s, qr) = newSession()
        val phone = FakePhone(qr)
        claim(s, phone)
        val code = phone.keys.checkCode
        listOf(s.toString(), s.stage.toString(), phone.keys.toString()).forEach { assertFalse(it, it.contains(code)) }
        s.enterCode(code)
        s.onMessage(phone.handover())
        s.onMessage(phone.stored())
        listOf(s.toString(), s.stage.toString(), staged.single().first.toString()).forEach { t ->
            assertFalse(t, t.contains("test-token") || t.contains("AIza") || t.contains(code))
        }
    }

    @Test fun timeoutsUseTheInjectedMonotonicClock() {
        val (s1, _) = newSession()
        clock += PairingSession.QR_LIFETIME_MS + 1
        assertEquals("abort", s1.tick()!!.getString("t"))
        assertEquals(Stage.Failed(Reason.TIMEOUT), s1.stage)

        val (s2, qr2) = newSession()
        claim(s2, FakePhone(qr2))
        clock += PairingSession.STAGE_LIFETIME_MS + 1
        assertTrue(s2.enterCode("123456") is CodeResult.Dead)
        assertEquals(Stage.Failed(Reason.TIMEOUT), s2.stage)
    }

    @Test fun aStoreFailureEndsWithNothingKept() {
        stageError = "keystore broken"
        val (s, qr) = newSession()
        val phone = FakePhone(qr)
        claim(s, phone)
        s.enterCode(phone.keys.checkCode)
        assertEquals("abort", s.onMessage(phone.handover()).single().getString("t"))
        assertEquals(Stage.Failed(Reason.STORE_FAILED), s.stage)
        assertEquals(0, commits)
    }

    @Test fun aValidMacWithAWrongVersionOrBadKeyEndsTheSession() {
        val (s, qr) = newSession()
        assertTrue(s.preAuth(FakePhone(qr).hello(v = 2)) is PreAuth.Fail)
        assertEquals(Stage.Failed(Reason.BAD_VERSION), s.stage)

        val (s2, qr2) = newSession()
        val phone = FakePhone(qr2)
        val bogus = ByteArray(65).also { it[0] = 4; it[5] = 9 }
        val mac = PairingCrypto.helloMac(PairingCrypto.qrKey(phone.secret), phone.unitPk, bogus)
        assertTrue(
            s2.preAuth(JSONObject().put("t", "hello").put("v", 1).put("pk", Base64.Default.encode(bogus)).put("mac", Base64.Default.encode(mac)))
                is PreAuth.Fail,
        )
        assertEquals(Stage.Failed(Reason.BAD_MESSAGE), s2.stage)
    }
}

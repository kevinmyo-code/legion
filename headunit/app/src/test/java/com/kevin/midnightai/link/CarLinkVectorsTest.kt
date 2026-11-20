package com.kevin.midnightai.link

import com.kevin.midnightai.location.PhoneFeed
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECPrivateKeySpec
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The unit against vectors.json, made by make_vectors.py (Python `cryptography`, written from the
 * protocol document, sharing no code with this app). The unit must reproduce the independent
 * implementation's bytes exactly, both what it derives and what it SENDS (ack, code_ok, done,
 * HELLO), and accept what the phone sends. This, not the fakes, is the proof.
 */
@OptIn(ExperimentalEncodingApi::class)
class CarLinkVectorsTest {

    private val v = JSONObject(javaClass.classLoader!!.getResource("carlink/vectors.json")!!.readText())
    private val p = v.getJSONObject("pairing")
    private val m = v.getJSONObject("mnai2")

    private fun hex(s: String): ByteArray = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun b(key: String) = hex(p.getString(key))

    private val template = PairingCrypto.generateKeyPair().public as ECPublicKey
    private fun privateKey(scalarHex: String): ECPrivateKey =
        KeyFactory.getInstance("EC").generatePrivate(ECPrivateKeySpec(BigInteger(scalarHex, 16), template.params)) as ECPrivateKey

    private val unitPk = b("unit_pk")
    private val phonePk = b("phone_pk")

    @Test fun theKeyScheduleMatches() {
        val z = PairingCrypto.sharedSecret(privateKey(p.getString("unit_private_scalar")), PairingCrypto.decode(phonePk, template)!!)
        assertArrayEquals(b("z"), z)
        assertArrayEquals(b("qr_key"), PairingCrypto.qrKey(b("secret")))
        assertArrayEquals(b("salt_key"), PairingCrypto.saltKey(b("secret")))
        assertArrayEquals(b("transcript"), PairingCrypto.transcript(unitPk, phonePk))
        val keys = PairingCrypto.derive(z, b("secret"), PairingCrypto.transcript(unitPk, phonePk))
        assertArrayEquals(b("link_key"), keys.link)
        assertArrayEquals(b("hand_key"), keys.handover)
        assertArrayEquals(b("confirm_key"), keys.confirm)
        assertEquals(p.getString("check"), keys.checkCode)
        assertArrayEquals(b("hello_mac"), PairingCrypto.helloMac(b("qr_key"), unitPk, phonePk))
    }

    @Test fun theUnitSendsTheDocumentedBytesThroughAWholePairing() {
        var stagedKey: ByteArray? = null
        var committed = false
        val sink = object : HandoverSink {
            override fun stage(handover: Handover, linkKey: ByteArray): String? {
                assertEquals("test-token", handover.engineToken)
                stagedKey = linkKey.copyOf()
                return null
            }
            override fun commit(): String? { committed = true; return null }
        }
        val unitKeys = KeyPair(PairingCrypto.decode(unitPk, template)!!, privateKey(p.getString("unit_private_scalar")))
        val session = PairingSession(unitKeys, b("secret").copyOf(), sink, now = { 0L })

        val hello = JSONObject().put("t", "hello").put("v", 1)
            .put("pk", Base64.Default.encode(phonePk)).put("mac", Base64.Default.encode(b("hello_mac")))
        val ack = (session.preAuth(hello) as PairingSession.PreAuth.Claimed).reply
        assertArrayEquals(b("ack_kc"), Base64.Default.decode(ack.getString("kc")))

        val codeOk = (session.enterCode(p.getString("check")) as PairingSession.CodeResult.Correct).send
        assertArrayEquals(b("code_ok"), Base64.Default.decode(codeOk.getString("c")))

        val done = session.onMessage(JSONObject().put("t", "handover").put("c", Base64.Default.encode(b("handover")))).single()
        assertArrayEquals(b("done"), Base64.Default.decode(done.getString("c")))
        assertArrayEquals(b("link_key"), stagedKey)

        session.onMessage(JSONObject().put("t", "stored").put("c", Base64.Default.encode(b("stored"))))
        assertTrue(committed)
        assertTrue(session.stage is PairingSession.Stage.Paired)
    }

    // --- MNAI2 ------------------------------------------------------------------------------

    private fun feed(): PhoneFeed {
        val session = hex(m.getString("unit_session"))
        val challenge = hex(m.getString("challenge"))
        var first = true
        return PhoneFeed(
            b("link_key"), nowNanos = { 1_000_000_000_000L },
            random = { out -> (if (first) session else challenge).copyInto(out); first = false },
        )
    }

    @Test fun theUnitsHelloMatchesAndThePhonesDatagramsAreHandledAsDocumented() {
        val f = feed()
        var hello = ByteArray(0)
        repeat(m.getJSONObject("hello").getInt("counter")) { hello = f.hello(1000) }
        assertArrayEquals(hex(m.getJSONObject("hello").getString("bytes")), hello)

        fun send(name: String): PhoneFeed.Outcome = hex(m.getJSONObject(name).getString("bytes")).let { f.receive(it, it.size) }
        val fix = send("fix")
        assertTrue(fix.toString(), fix is PhoneFeed.Outcome.Fix && fix.fix.lat == 1.5 && fix.fix.lng == -2.25 && fix.fix.ageMs == 250L)
        assertEquals(PhoneFeed.Outcome.Rejected(PhoneFeed.Refusal.STALE), send("fix_max_age"))
        assertEquals(PhoneFeed.Outcome.Zone("Etc/UTC"), send("zone"))
        assertEquals(PhoneFeed.Outcome.Revoke, send("revoke"))
    }
}

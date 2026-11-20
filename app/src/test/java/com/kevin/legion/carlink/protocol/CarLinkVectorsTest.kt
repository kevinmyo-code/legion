package com.kevin.legion.carlink.protocol

import com.kevin.legion.carlink.protocol.CarLinkVectors.hex
import com.kevin.legion.carlink.protocol.CarLinkVectors.obj
import com.kevin.legion.carlink.protocol.CarLinkVectors.str
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every value in the head unit branch's `vectors.json`: the bytes this phone produces must match, and
 * the unit's bytes must open and read as the vectors say.
 */
class CarLinkVectorsTest {
    private val p = CarLinkVectors.pairing
    private val m = CarLinkVectors.mnai2
    private val unitPk = p.hex("unit_pk")
    private val phonePk = p.hex("phone_pk")

    private fun keys(): PairingKeys {
        val z = P256.agree(CarLinkVectors.phoneKey(), P256.parsePublic(unitPk)!!)
        return PairingKeys.derive(p.hex("secret"), unitPk, phonePk, z)
    }

    @Test fun `the copy is byte for byte the head unit branch's file`() {
        // SHA-256 of feat/headunit-hu1:headunit/app/src/test/resources/carlink/vectors.json (blob f298af79).
        assertEquals(
            "63b4ffd942bba3f30bc02f83ae790ba4e6599a83fec0acbd3c1ab97a1b76382d",
            CarLinkVectors.hex(LinkCrypto.sha256(CarLinkVectors.raw)),
        )
    }

    @Test fun `ECDH from the phone's side gives the vectors' Z`() {
        val z = P256.agree(CarLinkVectors.phoneKey(), P256.parsePublic(unitPk)!!)
        assertEquals(p.str("z"), CarLinkVectors.hex(z))
    }

    @Test fun `the key schedule matches every pairing key`() {
        val k = keys()
        assertEquals(p.str("qr_key"), CarLinkVectors.hex(k.qrKey))
        assertEquals(p.str("salt_key"), CarLinkVectors.hex(k.saltKey))
        assertEquals(p.str("transcript"), CarLinkVectors.hex(k.transcript))
        assertEquals(p.str("master"), CarLinkVectors.hex(k.master))
        assertEquals(p.str("link_key"), CarLinkVectors.hex(k.linkKey))
        assertEquals(p.str("hand_key"), CarLinkVectors.hex(k.handKey))
        assertEquals(p.str("confirm_key"), CarLinkVectors.hex(k.confirmKey))
        assertEquals(p.str("check"), k.check)
    }

    @Test fun `hello MAC and the unit's kc match`() {
        val k = keys()
        assertEquals(p.str("hello_mac"), CarLinkVectors.hex(k.helloMac(unitPk, phonePk)))
        assertEquals(p.str("ack_kc"), CarLinkVectors.hex(k.expectedKc()))
    }

    @Test fun `the hand-over body serialises to the vectors' plaintext and seals to their bytes`() {
        val body = CarLinkVectors.vectorHandover().toJson()
        assertEquals(p.str("handover_plain"), body)
        val sealed = LinkCrypto.seal(
            keys().handKey, LinkCrypto.nonce("HAND", 1), LinkCrypto.ascii("MNAI-PAIR handover v1"),
            p.str("handover_plain").toByteArray(),
        )
        assertEquals(p.str("handover"), CarLinkVectors.hex(sealed))
    }

    @Test fun `the unit's code_ok and done open, and stored seals to the vectors' bytes`() {
        val hk = keys().handKey
        val ok = """{"ok":true}"""
        assertEquals(ok, String(LinkCrypto.open(hk, LinkCrypto.nonce("CDOK", 1), LinkCrypto.ascii("MNAI-PAIR code ok v1"), p.hex("code_ok"))!!))
        assertEquals(ok, String(LinkCrypto.open(hk, LinkCrypto.nonce("DONE", 1), LinkCrypto.ascii("MNAI-PAIR done v1"), p.hex("done"))!!))
        val stored = LinkCrypto.seal(hk, LinkCrypto.nonce("STOR", 1), LinkCrypto.ascii("MNAI-PAIR stored v1"), ok.toByteArray())
        assertEquals(p.str("stored"), CarLinkVectors.hex(stored))
    }

    @Test fun `the client produces the hello, handover and stored messages the vectors imply`() {
        var persisted: ByteArray? = null
        val ring = LinkKeyRing(null, null) { cur, _ -> persisted = cur; true }
        val client = PairingClient(QrPayload.parse(CarLinkVectors.qrText())!!, CarLinkVectors.phoneKey(), ring) {
            CarLinkVectors.vectorHandover()
        }
        assertEquals(
            """{"t":"hello","v":1,"pk":"${LinkCrypto.b64(phonePk)}","mac":"${LinkCrypto.b64(p.hex("hello_mac"))}"}""",
            client.hello(),
        )
        assertTrue(client.onMessage("""{"t":"ack","v":1,"kc":"${LinkCrypto.b64(p.hex("ack_kc"))}"}""").isEmpty())
        assertEquals(p.str("check"), (client.stage as PairingStage.DigitsShown).digits)
        val handover = client.onMessage(CarLinkVectors.sealedJson("code_ok", "code_ok")).single() as PairingEffect.Send
        assertEquals(CarLinkVectors.sealedJson("handover", "handover"), handover.json)
        val stored = client.onMessage(CarLinkVectors.sealedJson("done", "done")).single() as PairingEffect.Send
        assertEquals(CarLinkVectors.sealedJson("stored", "stored"), stored.json)
        assertEquals(p.str("link_key"), CarLinkVectors.hex(persisted!!))
        assertEquals(p.str("link_key"), CarLinkVectors.hex(ring.current!!))
    }

    @Test fun `MNAI2 direction keys match`() {
        val d = DirectionKeys(p.hex("link_key"))
        assertEquals(m.str("p2u_key"), CarLinkVectors.hex(d.p2u))
        assertEquals(m.str("u2p_key"), CarLinkVectors.hex(d.u2p))
    }

    @Test fun `the unit's HELLO opens and reads as the vectors say`() {
        val h = m.obj("hello")
        val d = Mnai2.openFromUnit(m.hex("u2p_key"), h.hex("bytes"))
        assertNotNull(d)
        assertEquals(Mnai2.HELLO, d!!.type)
        assertArrayEquals(m.hex("unit_session"), d.session)
        assertEquals(h["counter"]!!.jsonPrimitive.long, d.counter)
        val hello = Mnai2.readHello(d.body)!!
        assertEquals(h["interval_ms"]!!.jsonPrimitive.long, hello.intervalMs)
        assertArrayEquals(m.hex("challenge"), hello.challenge)
    }

    private fun vectorFix(age: Long) = PhoneFix(1.5, -2.25, age, 5.0f, 90.0f, 20.0f, 15.0, 2.0f, 0.5f)

    private fun phoneDatagram(type: Char, counter: Long, body: ByteArray) =
        Mnai2.seal(m.hex("p2u_key"), type, m.hex("phone_session"), counter, body)

    @Test fun `the phone's FIX datagrams match, including the largest unsigned age`() {
        val fix = m.obj("fix")
        val body = Mnai2.fixBody(m.hex("challenge"), vectorFix(fix["age_ms"]!!.jsonPrimitive.long))
        assertEquals(Mnai2.FIX_LEN, body.size)
        assertEquals(fix.str("bytes"), CarLinkVectors.hex(phoneDatagram(Mnai2.FIX, 3, body)))
        val max = m.obj("fix_max_age")
        val maxBody = Mnai2.fixBody(m.hex("challenge"), vectorFix(max["age_ms"]!!.jsonPrimitive.long))
        assertEquals(max.str("bytes"), CarLinkVectors.hex(phoneDatagram(Mnai2.FIX, 4, maxBody)))
    }

    @Test fun `the phone's ZONE and REVOKE datagrams match`() {
        val zone = m.obj("zone")
        assertEquals(
            zone.str("bytes"),
            CarLinkVectors.hex(phoneDatagram(Mnai2.ZONE, 5, Mnai2.zoneBody(m.hex("challenge"), zone.str("zone")))),
        )
        assertEquals(
            m.obj("revoke").str("bytes"),
            CarLinkVectors.hex(phoneDatagram(Mnai2.REVOKE, 6, Mnai2.revokeBody(m.hex("challenge")))),
        )
    }

    @Test fun `the phone's own FIX opens under p2u, which is how the unit reads it`() {
        val d = Mnai2.openFromPhone(m.hex("p2u_key"), m.obj("fix").hex("bytes"))!!
        assertEquals(Mnai2.FIX, d.type)
        assertEquals(3L, d.counter)
    }
}

package com.kevin.legion.carlink.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Section 2's primitives at their edges, and the QR and framing parsers' refusals. */
class LinkCryptoTest {
    private val unitPk = CarLinkVectors.unhex(CarLinkVectors.pairing["unit_pk"].toString().trim('"'))

    @Test fun `base64 is padded and strict`() {
        assertEquals("AAE=", LinkCrypto.b64(byteArrayOf(0, 1)))
        assertEquals("-_8=", LinkCrypto.b64url(byteArrayOf(-5, -1)))
        assertArrayEquals(byteArrayOf(0, 1), LinkCrypto.unb64("AAE="))
        assertNull("unpadded", LinkCrypto.unb64("AAE"))
        assertNull("url alphabet in standard", LinkCrypto.unb64("-_8="))
        assertNull("standard alphabet in url", LinkCrypto.unb64url("+/8="))
        assertNull("whitespace", LinkCrypto.unb64("AA E="))
        assertNull("non-zero pad bits", LinkCrypto.unb64("AAF="))
    }

    @Test fun `NONCE is the direction then a big-endian u64`() {
        assertEquals("43444f4b0000000000000001", CarLinkVectors.hex(LinkCrypto.nonce("CDOK", 1)))
        assertEquals("50325520ffffffffffffffff", CarLinkVectors.hex(LinkCrypto.nonce("P2U ", -1L)))
    }

    @Test fun `HKDF with an empty salt equals HKDF with 32 zero bytes`() {
        val ikm = ByteArray(32) { it.toByte() }
        val info = LinkCrypto.ascii("x")
        assertArrayEquals(LinkCrypto.hkdf(ikm, ByteArray(32), info), LinkCrypto.hkdf(ikm, ByteArray(0), info))
    }

    @Test fun `public keys off the curve, compressed, short or out of range are refused`() {
        assertNotNull(P256.parsePublic(unitPk))
        assertNull(P256.parsePublic(unitPk.copyOf(64)))
        assertNull(P256.parsePublic(unitPk.copyOf().also { it[0] = 0x02 }))
        assertNull(P256.parsePublic(unitPk.copyOf().also { it[64] = (it[64].toInt() xor 1).toByte() }))
        assertNull(P256.parsePublic(byteArrayOf(4) + ByteArray(64) { -1 }))
    }

    @Test fun `the QR parses only in its exact shape`() {
        val good = CarLinkVectors.qrText()
        assertNotNull(QrPayload.parse(good))
        val parts = good.split(':')
        fun with(i: Int, v: String) = parts.toMutableList().also { it[i] = v }.joinToString(":")
        assertNull(QrPayload.parse(with(0, "mnai-pairx")))
        assertNull(QrPayload.parse(with(1, "2")))
        assertNull(QrPayload.parse(with(3, LinkCrypto.b64url(ByteArray(15)))))
        assertNull(QrPayload.parse(with(3, parts[3].trimEnd('='))))
        assertNull(QrPayload.parse(with(4, "8.8.8.8")))
        assertNull(QrPayload.parse(with(4, "010.0.0.1")))
        assertNull(QrPayload.parse(with(5, "70000")))
        assertNull(QrPayload.parse("$good:extra"))
        assertEquals("QrPayload(redacted)", QrPayload.parse(good).toString())
    }

    @Test fun `frames reassemble a split header and refuse a bad length before allocating`() {
        val frame = Frames.encode("""{"t":"ack"}""")
        val r = Frames.FrameReader()
        assertTrue(r.feed(frame.copyOfRange(0, 2)).isEmpty())
        assertEquals(listOf("""{"t":"ack"}"""), r.feed(frame.copyOfRange(2, frame.size)))
        val big = byteArrayOf(0, 0, 0x20, 1)
        var threw = false
        try { Frames.FrameReader().feed(big) } catch (_: Frames.BadFrame) { threw = true }
        assertTrue(threw)
        threw = false
        try { Frames.FrameReader().feed(byteArrayOf(0, 0, 0, 0)) } catch (_: Frames.BadFrame) { threw = true }
        assertTrue(threw)
    }

    @Test fun `a MNAI2 counter with the top bit set is refused`() {
        val unit = FakeUnit(ByteArray(32) { 3 })
        val d = unit.hello(counterOverride = Long.MIN_VALUE)
        assertNull(Mnai2.openFromUnit(unit.keys.u2p, d))
    }
}

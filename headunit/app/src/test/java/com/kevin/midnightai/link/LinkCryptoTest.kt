package com.kevin.midnightai.link

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The car link's primitives against published vectors, so the phone end can match byte for byte. */
class LinkCryptoTest {

    private fun hex(s: String): ByteArray = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test fun hkdfMatchesRfc5869TestCase1() {
        val okm = LinkCrypto.hkdf(
            ikm = hex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b"),
            salt = hex("000102030405060708090a0b0c"),
            info = hex("f0f1f2f3f4f5f6f7f8f9"),
            length = 42,
        )
        assertArrayEquals(
            hex("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865"),
            okm,
        )
    }

    @Test fun hkdfMatchesRfc5869TestCase3WithAnEmptySalt() {
        val okm = LinkCrypto.hkdf(hex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b"), ByteArray(0), ByteArray(0), 42)
        assertArrayEquals(
            hex("8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8"),
            okm,
        )
    }

    @Test fun gcmRoundTripsAndRejectsAnyChange() {
        val key = ByteArray(32) { it.toByte() }
        val nonce = LinkCrypto.nonce("P2U ".toByteArray(), 7)
        val sealed = LinkCrypto.seal(key, nonce, "hdr".toByteArray(), "hello".toByteArray())
        assertEquals("hello", String(LinkCrypto.open(key, nonce, "hdr".toByteArray(), sealed)!!))
        assertNull(LinkCrypto.open(key, nonce, "hdX".toByteArray(), sealed))
        assertNull(LinkCrypto.open(key, LinkCrypto.nonce("P2U ".toByteArray(), 8), "hdr".toByteArray(), sealed))
        assertNull(LinkCrypto.open(ByteArray(32), nonce, "hdr".toByteArray(), sealed))
        assertNull(LinkCrypto.open(key, nonce, "hdr".toByteArray(), sealed.copyOf(3)))
    }

    @Test fun nonceIsDirectionThenBigEndianCounter() {
        assertArrayEquals(hex("50325520" + "0000000000000102"), LinkCrypto.nonce("P2U ".toByteArray(), 258))
    }

    @Test fun constantTimeEquals() {
        assertTrue(LinkCrypto.constantTimeEquals(byteArrayOf(1, 2), byteArrayOf(1, 2)))
        assertFalse(LinkCrypto.constantTimeEquals(byteArrayOf(1, 2), byteArrayOf(1, 3)))
        assertFalse(LinkCrypto.constantTimeEquals(byteArrayOf(1), byteArrayOf(1, 2)))
    }
}

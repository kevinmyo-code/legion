package com.kevin.midnightai.location

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * Rev 9's two frames, pinned BYTE FOR BYTE from fixed inputs.
 *
 * The phone holds its own hand-written copy of this format (`app/.../carlink/protocol/Mnai2.kt`),
 * and `Mnai2TokenFrameTest` there pins the SAME literals from the SAME inputs. Both suites can be
 * green while the two spellings disagree and the car silently never takes a renewed sign-in, so
 * these literals are the contract between them.
 */
class PhoneLinkWireTokenFrameTest {

    private val linkKey = ByteArray(32) { (it * 3 + 1).toByte() }
    private val session = ByteArray(16) { (0xA0 + it).toByte() }
    private val challenge = ByteArray(8) { (it + 1).toByte() }
    private val token = "hu1_rotated-token-for-the-car"
    private val vehicle = "11111111-2222-3333-4444-555555555555"
    private val p2u = PhoneLinkWire.phoneToUnitKey(linkKey)
    private val u2p = PhoneLinkWire.unitToPhoneKey(linkKey)

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    @Test fun theTokenFrameFromThePhoneIsTheseExactBytesAndOpensToWhatItSays() {
        assertEquals(TOKEN_HEX, hex(PhoneLinkWire.encodeToken(p2u, session, 7, challenge, token, vehicle)))
        val bytes = unhex(TOKEN_HEX)
        val (decoded, refusal) = PhoneLinkWire.decodePhone(p2u, bytes, bytes.size)
        assertNull(refusal)
        val credential = decoded as PhoneLinkWire.Decoded.Credential
        assertEquals(token, credential.token)
        assertEquals(vehicle, credential.vehicleId)
        assertArrayEquals(challenge, credential.challenge)
        assertEquals(7L, credential.counter)
        assertTrue(bytes.size <= PhoneLinkWire.MAX_PACKET_BYTES)
    }

    @Test fun thisUnitsAnswerIsTheseExactBytesAndNamesTheTokenItIsAbout() {
        assertEquals(FINGERPRINT_HEX, hex(PhoneLinkWire.fingerprint(token)))
        val fingerprint = unhex(FINGERPRINT_HEX)
        assertEquals(TOKEN_OK_HEX, hex(PhoneLinkWire.encodeTokenOk(u2p, session, 4, fingerprint)))
        val bytes = unhex(TOKEN_OK_HEX)
        assertArrayEquals(fingerprint, PhoneLinkWire.decodeTokenOk(u2p, bytes, bytes.size))
    }

    @Test fun aTokenWithNoVehicleIsReadWithNone() {
        val bytes = PhoneLinkWire.encodeToken(p2u, session, 1, challenge, token, null)
        val credential = PhoneLinkWire.decodePhone(p2u, bytes, bytes.size).first as PhoneLinkWire.Decoded.Credential
        assertEquals(token, credential.token)
        assertNull(credential.vehicleId)
    }

    @Test fun aTokenBodyWhoseLengthsDoNotAddUpIsRefused() {
        // Authenticated by the link key and still refused: the lengths are checked against the body.
        fun sealed(body: ByteArray) = sealBody(body)
        for (body in badBodies()) {
            val bytes = sealed(body)
            val (decoded, refusal) = PhoneLinkWire.decodePhone(p2u, bytes, bytes.size)
            assertNull(hex(body), decoded)
            assertEquals(hex(body), "BAD_FIELDS", refusal)
        }
    }

    @Test fun aTypeThisBuildDoesNotKnowIsRefusedBeforeAnythingElse() {
        // What an INSTALLED unit does with rev 9's frames, and it is why one costs it nothing: the
        // type is refused before the body is read and before any counter or challenge moves.
        val unknown = sealType('X'.code.toByte(), ByteArray(8))
        val (decoded, refusal) = PhoneLinkWire.decodePhone(p2u, unknown, unknown.size)
        assertNull(decoded)
        assertEquals("WRONG_TYPE", refusal)
        val feed = PhoneFeed(linkKey, nowNanos = { 1_000_000_000L }, random = { Random(9).nextBytes(it) })
        assertEquals(
            PhoneFeed.Outcome.Rejected(PhoneFeed.Refusal.WRONG_TYPE),
            feed.receive(unknown, unknown.size),
        )
    }

    private fun badBodies(): List<ByteArray> {
        val t = token.toByteArray(Charsets.US_ASCII)
        return listOf(
            // A token length past the end of the body.
            challenge + byteArrayOf(0x7F) + t + byteArrayOf(0),
            // A vehicle length past the end of the body.
            challenge + byteArrayOf(t.size.toByte()) + t + byteArrayOf(0x40),
            // Trailing bytes nobody claimed.
            challenge + byteArrayOf(t.size.toByte()) + t + byteArrayOf(0) + byteArrayOf(9),
            // A zero-length token.
            challenge + byteArrayOf(0) + byteArrayOf(0),
            // A token with a byte no engine token can hold.
            challenge + byteArrayOf(4) + byteArrayOf(0x68, 0x75, 0x00, 0x31) + byteArrayOf(0),
            // Nothing but the challenge.
            challenge,
        )
    }

    private fun sealBody(body: ByteArray) = sealType(PhoneLinkWire.TYPE_TOKEN, body)

    /** The phone's seal, written from the spec, so a refusal is about the BODY and not the key. */
    private fun sealType(type: Byte, body: ByteArray): ByteArray {
        val header = java.nio.ByteBuffer.allocate(PhoneLinkWire.HEADER_BYTES)
            .put("MNAI2".toByteArray(Charsets.US_ASCII)).put(PhoneLinkWire.VERSION).put(type)
            .put(session).putLong(3).array()
        val key = PhoneLinkWire.sessionKey(p2u, session)
        return header + com.kevin.midnightai.link.LinkCrypto.seal(
            key, com.kevin.midnightai.link.LinkCrypto.nonce("P2U ".toByteArray(Charsets.US_ASCII), 3), header, body,
        )
    }

    private companion object {
        /** The literals the phone's own suite repeats; see this class's doc. */
        const val TOKEN_HEX =
            "4d4e4149320154a0a1a2a3a4a5a6a7a8a9aaabacadaeaf0000000000000007d14c752e7008479a29709495a8ba" +
            "d31ca8c2b137dca1afbd4fa653fecc3cbfaa351627ade635818a73b8ea2b4faae085a68c1788ee422d2c7d7d27" +
            "1364926989ff295422b2e9a282579b4bcea0cd55dfce5f7b5182ec8da6ef9de8"
        const val TOKEN_OK_HEX =
            "4d4e4149320143a0a1a2a3a4a5a6a7a8a9aaabacadaeaf0000000000000004b29ae3e81c38387f6d1443adf71b" +
            "74a2471d38b3815a83d2"
        const val FINGERPRINT_HEX = "4a1ff982694518a8"
    }
}

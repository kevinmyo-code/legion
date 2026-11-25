package com.kevin.legion.carlink.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

/**
 * 4.3 rev 9's two frames, pinned BYTE FOR BYTE from fixed inputs.
 *
 * The head unit holds its own hand-written copy of this format
 * (`headunit/.../location/PhoneLinkWire.kt`), and `PhoneLinkWireTokenFrameTest` there pins the SAME
 * literals from the SAME inputs. Both suites can be green while the two spellings disagree and the
 * car silently never takes a renewed sign-in, so the literals are the contract: a change to either
 * end shows up as this test and that one disagreeing.
 */
class Mnai2TokenFrameTest {

    private val linkKey = ByteArray(32) { (it * 3 + 1).toByte() }
    private val session = ByteArray(16) { (0xA0 + it).toByte() }
    private val challenge = ByteArray(8) { (it + 1).toByte() }
    private val token = "hu1_rotated-token-for-the-car"
    private val vehicle = "11111111-2222-3333-4444-555555555555"
    private val unitAddr = InetAddress.getByName("10.0.0.1")

    @Test fun `a TOKEN datagram is these exact bytes`() {
        val keys = DirectionKeys(linkKey)
        val sealed = Mnai2.seal(keys.p2u, Mnai2.TOKEN, session, 7, Mnai2.tokenBody(challenge, token, vehicle))
        assertEquals(TOKEN_HEX, CarLinkVectors.hex(sealed))
        // And it is the shape the unit parses: challenge, then each value behind its own length.
        val body = Mnai2.openFromPhone(keys.p2u, sealed)!!.body
        assertArrayEquals(challenge, body.copyOfRange(0, 8))
        assertEquals(token.length, body[8].toInt())
        assertEquals(token, String(body, 9, token.length, Charsets.US_ASCII))
        assertEquals(vehicle.length, body[9 + token.length].toInt())
        assertTrue(sealed.size <= Mnai2.MAX_DATAGRAM)
    }

    @Test fun `a TOKEN_OK datagram is these exact bytes, and names the token it is about`() {
        val keys = DirectionKeys(linkKey)
        val fingerprint = Mnai2.fingerprint(token)
        assertEquals(FINGERPRINT_HEX, CarLinkVectors.hex(fingerprint))
        val sealed = Mnai2.seal(keys.u2p, Mnai2.TOKEN_OK, session, 4, Mnai2.tokenOkBody(fingerprint))
        assertEquals(TOKEN_OK_HEX, CarLinkVectors.hex(sealed))
    }

    @Test fun `a vehicle-less TOKEN still carries the token`() {
        val keys = DirectionKeys(linkKey)
        val body = Mnai2.tokenBody(challenge, token, null)
        assertEquals(0, body.last().toInt())
        val sealed = Mnai2.seal(keys.p2u, Mnai2.TOKEN, session, 1, body)
        assertEquals(Mnai2.TOKEN, Mnai2.openFromPhone(keys.p2u, sealed)!!.type)
    }

    @Test fun `a body that is not a fingerprint is not a TOKEN_OK`() {
        assertNull(Mnai2.readTokenOk(ByteArray(7)))
        assertNull(Mnai2.readTokenOk(ByteArray(9)))
        assertArrayEquals(ByteArray(8), Mnai2.readTokenOk(ByteArray(8)))
    }

    @Test fun `the feed only reports a TOKEN_OK that is authentic, new and from the unit it sends to`() {
        val stored = mutableListOf<ByteArray>()
        val ring = MemoryKeys(current = linkKey).ring()
        val feed = Mnai2Feed(
            ring = ring,
            newSession = { session },
            onLan = { it.hostAddress?.startsWith("10.") == true },
            zoneId = { null },
            onTokenStored = { stored += it },
        )
        val unit = FakeUnit(linkKey)
        val hello = unit.hello()
        feed.onDatagram(hello, hello.size, unitAddr, UNIT_PORT, 0)

        val fingerprint = Mnai2.fingerprint(token)
        val ok = unit.tokenOk(fingerprint)
        feed.onDatagram(ok, ok.size, unitAddr, UNIT_PORT, 10)
        assertEquals(1, stored.size)
        assertArrayEquals(fingerprint, stored.single())

        // Replayed: refused on the same counter ladder as a HELLO.
        feed.onDatagram(ok, ok.size, unitAddr, UNIT_PORT, 20)
        assertEquals(1, stored.size)
        // From somewhere else on the hotspot, or under a key that is not the pairing's: nothing.
        val next = unit.tokenOk(fingerprint)
        feed.onDatagram(next, next.size, InetAddress.getByName("10.0.0.9"), UNIT_PORT, 30)
        val stranger = FakeUnit(ByteArray(32) { 0x7F }).tokenOk(fingerprint)
        feed.onDatagram(stranger, stranger.size, unitAddr, UNIT_PORT, 40)
        assertEquals(1, stored.size)
    }

    @Test fun `a token goes out only while the unit is in range, echoing its newest challenge`() {
        val ring = MemoryKeys(current = linkKey).ring()
        val feed = Mnai2Feed(
            ring = ring,
            newSession = { session },
            onLan = { true },
            zoneId = { null },
        )
        assertNull("no HELLO yet", feed.handToken(0, token, vehicle))
        val unit = FakeUnit(linkKey)
        val c = unit.nextChallenge()
        val hello = unit.hello(challenge = c)
        feed.onDatagram(hello, hello.size, unitAddr, UNIT_PORT, 0)
        val out = feed.handToken(1_000, token, vehicle)!!
        val d = Mnai2.openFromPhone(DirectionKeys(linkKey).p2u, out.bytes)!!
        assertEquals(Mnai2.TOKEN, d.type)
        assertArrayEquals(c, d.body.copyOfRange(0, 8))
        // A challenge older than 10 s is not current: nothing is handed over on a dead link.
        assertNull(feed.handToken(11_000, token, vehicle))
    }

    private companion object {
        /** The literals the head unit's own suite repeats; see this class's doc. */
        const val TOKEN_HEX =
            "4d4e4149320154a0a1a2a3a4a5a6a7a8a9aaabacadaeaf0000000000000007d14c752e7008479a29709495a8ba" +
            "d31ca8c2b137dca1afbd4fa653fecc3cbfaa351627ade635818a73b8ea2b4faae085a68c1788ee422d2c7d7d27" +
            "1364926989ff295422b2e9a282579b4bcea0cd55dfce5f7b5182ec8da6ef9de8"
        const val TOKEN_OK_HEX =
            "4d4e4149320143a0a1a2a3a4a5a6a7a8a9aaabacadaeaf0000000000000004b29ae3e81c38387f6d1443adf71b" +
            "74a2471d38b3815a83d2"
        const val FINGERPRINT_HEX = "4a1ff982694518a8"
        const val UNIT_PORT = 40_001
    }
}

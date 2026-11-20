package com.kevin.legion.carlink.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

/** HELLO acceptance, fix cadence, revoke, and the reserve-key rule over MNAI2 (sections 3.7, 4.3). */
class Mnai2FeedTest {
    private val oldKey = ByteArray(32) { 0x11 }
    private val newKey = ByteArray(32) { 0x22 }
    private val unitAddr = InetAddress.getByName("10.0.0.1")
    private val otherAddr = InetAddress.getByName("10.0.0.9")
    private var sessionSeed = 0
    private val events = mutableListOf<RingEvent>()

    private fun feed(ring: LinkKeyRing, zone: String? = null) = Mnai2Feed(
        ring = ring,
        newSession = { ByteArray(16) { (sessionSeed * 16 + it).toByte() }.also { sessionSeed++ } },
        onLan = { it.hostAddress?.startsWith("10.") == true },
        zoneId = { zone },
        onRingEvent = { events += it },
    )

    private fun fix(elapsed: Long) = FixSample(elapsed, PhoneFix(1.5, -2.25, 0))

    private fun opened(out: Outgoing, key: ByteArray) = Mnai2.openFromPhone(DirectionKeys(key).p2u, out.bytes)!!

    @Test fun `fixes go only to an accepted HELLO's sender and echo its challenge`() {
        val f = feed(MemoryKeys(current = oldKey).ring())
        val unit = FakeUnit(oldKey)
        val challenge = unit.nextChallenge()
        val h = unit.hello(challenge = challenge)
        f.onDatagram(h, h.size, unitAddr, 0)
        val out = f.tick(1_000, fix(900), sendFixes = true).single()
        assertEquals(unitAddr, out.to)
        val d = opened(out, oldKey)
        assertEquals(Mnai2.FIX, d.type)
        assertEquals(1L, d.counter)
        assertArrayEquals(challenge, d.body.copyOfRange(0, 8))
    }

    @Test fun `a replayed HELLO is ignored and cannot move the destination`() {
        val f = feed(MemoryKeys(current = oldKey).ring())
        val unit = FakeUnit(oldKey)
        val first = unit.hello()
        f.onDatagram(first, first.size, unitAddr, 0)
        f.onDatagram(first, first.size, otherAddr, 100)
        assertEquals(unitAddr, f.tick(1_000, fix(900), true).single().to)
        val older = unit.hello(counterOverride = 1)
        f.onDatagram(older, older.size, otherAddr, 1_100)
        assertEquals(unitAddr, f.tick(2_100, fix(2_000), true).single().to)
    }

    @Test fun `a HELLO under a wrong key or from off the LAN is dropped`() {
        val f = feed(MemoryKeys(current = oldKey).ring())
        val stranger = FakeUnit(ByteArray(32) { 0x7F }).hello()
        f.onDatagram(stranger, stranger.size, unitAddr, 0)
        val offLan = FakeUnit(oldKey).hello()
        f.onDatagram(offLan, offLan.size, InetAddress.getByName("192.0.2.1"), 0)
        assertTrue(f.tick(1_000, fix(900), true).isEmpty())
    }

    @Test fun `no fix when the setting is off, when HELLOs stop for three intervals, or when the fix is stale`() {
        val f = feed(MemoryKeys(current = oldKey).ring())
        val unit = FakeUnit(oldKey)
        val h = unit.hello(intervalMs = 1000)
        f.onDatagram(h, h.size, unitAddr, 0)
        assertTrue(f.tick(1_000, fix(900), sendFixes = false).isEmpty())
        assertTrue(f.tick(1_000, fix(-10_000), sendFixes = true).isEmpty())
        assertEquals(1, f.tick(2_000, fix(1_900), sendFixes = true).size)
        assertTrue(f.tick(3_001, fix(3_000), sendFixes = true).isEmpty())
    }

    @Test fun `fixes keep the requested interval and never resend the same fix`() {
        val f = feed(MemoryKeys(current = oldKey).ring())
        val unit = FakeUnit(oldKey)
        val h = unit.hello(intervalMs = 1000)
        f.onDatagram(h, h.size, unitAddr, 0)
        assertEquals(1, f.tick(100, fix(50), true).size)
        assertTrue(f.tick(600, fix(550), true).isEmpty())
        assertTrue(f.tick(1_200, fix(50), true).isEmpty())
        assertEquals(1, f.tick(1_200, fix(1_150), true).size)
    }

    @Test fun `ageMs is the monotonic difference, measured at send time`() {
        val f = feed(MemoryKeys(current = oldKey).ring())
        val h = FakeUnit(oldKey).hello()
        f.onDatagram(h, h.size, unitAddr, 0)
        val body = opened(f.tick(2_000, fix(1_250), true).single(), oldKey).body
        assertEquals(750, java.nio.ByteBuffer.wrap(body, 46, 4).int)
    }

    @Test fun `revoke needs a challenge from the last 10 s`() {
        val f = feed(MemoryKeys(current = oldKey).ring())
        assertNull(f.revoke(0))
        val h = FakeUnit(oldKey).hello()
        f.onDatagram(h, h.size, unitAddr, 0)
        assertEquals(Mnai2.REVOKE, opened(f.revoke(9_000)!!, oldKey).type)
        assertNull(f.revoke(10_000))
    }

    @Test fun `zone goes once per unit session`() {
        val f = feed(MemoryKeys(current = oldKey).ring(), zone = "Etc/UTC")
        val unit = FakeUnit(oldKey)
        val a = unit.hello()
        assertEquals(Mnai2.ZONE, opened(f.onDatagram(a, a.size, unitAddr, 0).single(), oldKey).type)
        val b = unit.hello()
        assertTrue(f.onDatagram(b, b.size, unitAddr, 1_000).isEmpty())
    }

    @Test fun `lost stored - the first new-key HELLO deletes the reserve`() {
        val store = MemoryKeys(current = newKey, reserve = oldKey)
        val ring = store.ring()
        val f = feed(ring)
        val h = FakeUnit(newKey).hello()
        f.onDatagram(h, h.size, unitAddr, 0)
        assertNull(ring.reserve)
        assertNull(store.reserve)
        assertEquals(listOf(RingEvent.CONFIRMED), events)
    }

    @Test fun `lost abort - 60 s of old-key-only HELLOs with the session closed reverts to the old key`() {
        val store = MemoryKeys(current = newKey, reserve = oldKey)
        val ring = store.ring()
        val f = feed(ring)
        val unit = FakeUnit(oldKey)
        for (t in 0L..59_000L step 5_000L) {
            val h = unit.hello(intervalMs = 5000)
            f.onDatagram(h, h.size, unitAddr, t)
        }
        assertArrayEquals(newKey, ring.current)
        val last = unit.hello(intervalMs = 5000)
        f.onDatagram(last, last.size, unitAddr, 60_000)
        assertArrayEquals(oldKey, ring.current)
        assertArrayEquals(oldKey, store.current)
        assertNull(ring.reserve)
        assertEquals(listOf(RingEvent.REVERTED), events)
        // Back on the old key: the next HELLO under it is accepted and fixes flow again.
        val next = unit.hello()
        f.onDatagram(next, next.size, unitAddr, 61_000)
        assertNotNull(f.tick(62_000, fix(61_900), true).singleOrNull())
    }

    @Test fun `the 60 s must be a run of old-key HELLOs, not two far apart`() {
        val ring = MemoryKeys(current = newKey, reserve = oldKey).ring()
        val f = feed(ring)
        val unit = FakeUnit(oldKey)
        val first = unit.hello(intervalMs = 5000)
        f.onDatagram(first, first.size, unitAddr, 0)
        val late = unit.hello(intervalMs = 5000)
        f.onDatagram(late, late.size, unitAddr, 61_000)
        assertArrayEquals(newKey, ring.current)
        assertTrue(events.isEmpty())
    }

    @Test fun `old-key HELLOs never revert while the pairing session is open`() {
        val ring = MemoryKeys(current = newKey, reserve = oldKey).ring()
        ring.sessionOpen = true
        val f = feed(ring)
        val unit = FakeUnit(oldKey)
        for (t in 0L..120_000L step 5_000L) {
            val h = unit.hello()
            f.onDatagram(h, h.size, unitAddr, t)
        }
        assertArrayEquals(newKey, ring.current)
    }

    @Test fun `nothing is ever sent under the reserve key`() {
        val f = feed(MemoryKeys(current = newKey, reserve = oldKey).ring())
        val h = FakeUnit(oldKey).hello()
        assertTrue(f.onDatagram(h, h.size, unitAddr, 0).isEmpty())
        assertTrue(f.tick(1_000, fix(900), true).isEmpty())
        assertNull(f.revoke(1_000))
    }

    @Test fun `a new key starts a new session from counter 1 and waits for a fresh challenge`() {
        val store = MemoryKeys(current = oldKey)
        val ring = store.ring()
        val f = feed(ring)
        val h = FakeUnit(oldKey).hello()
        f.onDatagram(h, h.size, unitAddr, 0)
        val before = opened(f.tick(1_000, fix(900), true).single(), oldKey)
        assertTrue(ring.commit(newKey))
        assertTrue(f.tick(2_000, fix(1_900), true).isEmpty())
        val nh = FakeUnit(newKey).hello()
        f.onDatagram(nh, nh.size, unitAddr, 2_100)
        val after = opened(f.tick(3_000, fix(2_900), true).single(), newKey)
        assertEquals(1L, after.counter)
        assertTrue(!before.session.contentEquals(after.session))
    }

    @Test fun `silence between handover and done holds for fixes and revoke`() {
        val ring = MemoryKeys(current = oldKey).ring()
        val f = feed(ring)
        val h = FakeUnit(oldKey).hello()
        f.onDatagram(h, h.size, unitAddr, 0)
        ring.beginHandover()
        assertTrue(f.tick(1_000, fix(900), true).isEmpty())
        assertNull(f.revoke(1_000))
        ring.handoverFailed()
        assertEquals(1, f.tick(1_000, fix(900), true).size)
    }
}

package com.kevin.legion.dumb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class MemoryCallStore(var seen: Map<String, Long> = emptyMap()) : RecentCallStore {
    var writes = 0
    override fun read() = seen
    override fun write(seen: Map<String, Long>) {
        this.seen = seen
        writes++
    }
}

class DumbCallScreenerTest {
    private val minute = 60_000L
    private var on = true
    private val store = MemoryCallStore()
    private val recent = RecentCallers(store)
    private val chosen = setOf("15551234567")
    private val screener = DumbCallScreener(
        isOn = { on },
        isChosen = { n -> chosen.any { PhoneNumbers.same(it, n) } },
        recent = recent,
    )

    @Test
    fun `off allows everything untouched and records nothing`() {
        on = false
        assertEquals(CallAction.ALLOW, screener.decide("5550009999", incoming = true, nowMs = 0L))
        assertTrue(store.seen.isEmpty())
        assertEquals(0, store.writes)
    }

    @Test
    fun `a chosen number rings in any format`() {
        assertEquals(CallAction.ALLOW, screener.decide("+1 (555) 123-4567", true, 0L))
        assertEquals(CallAction.ALLOW, screener.decide("5551234567", true, 1L))
    }

    @Test
    fun `a stranger is silenced the first time and rings the second time within 15 minutes`() {
        assertEquals(CallAction.SILENCE, screener.decide("5550009999", true, 0L))
        assertEquals(CallAction.ALLOW, screener.decide("(555) 000-9999", true, 14 * minute))
    }

    @Test
    fun `the window is inclusive at 15 minutes and closed after`() {
        assertEquals(CallAction.SILENCE, screener.decide("5550009999", true, 0L))
        assertEquals(CallAction.ALLOW, screener.decide("5550009999", true, 15 * minute))
        // that sighting refreshed the clock, so measure the next gap from it
        assertEquals(CallAction.SILENCE, screener.decide("5550009999", true, 15 * minute + 15 * minute + 1))
    }

    @Test
    fun `a caller who keeps ringing inside the window keeps ringing`() {
        screener.decide("5550009999", true, 0L)
        assertEquals(CallAction.ALLOW, screener.decide("5550009999", true, 10 * minute))
        assertEquals(CallAction.ALLOW, screener.decide("5550009999", true, 20 * minute))
    }

    @Test
    fun `two different strangers do not vouch for each other`() {
        screener.decide("5550009999", true, 0L)
        assertEquals(CallAction.SILENCE, screener.decide("5550008888", true, minute))
    }

    @Test
    fun `a withheld number is always silenced and never tracked`() {
        assertEquals(CallAction.SILENCE, screener.decide(null, true, 0L))
        assertEquals(CallAction.SILENCE, screener.decide("", true, minute))
        assertEquals(CallAction.SILENCE, screener.decide("unknown", true, 2 * minute))
        assertTrue(store.seen.isEmpty())
    }

    @Test
    fun `an outgoing call is never touched`() {
        assertEquals(CallAction.ALLOW, screener.decide("5550009999", incoming = false, nowMs = 0L))
        assertTrue(store.seen.isEmpty())
    }

    @Test
    fun `only digits and a time are stored, and old sightings are pruned`() {
        screener.decide("+1 (555) 000-9999", true, 0L)
        assertEquals(mapOf("15550009999" to 0L), store.seen)
        screener.decide("5550001111", true, 16 * minute)
        assertEquals(mapOf("5550001111" to 16 * minute), store.seen)
    }

    @Test
    fun `sightings survive a new instance over the same store`() {
        screener.decide("5550009999", true, 0L)
        val again = DumbCallScreener({ true }, { false }, RecentCallers(store))
        assertEquals(CallAction.ALLOW, again.decide("5550009999", true, minute))
    }

    @Test
    fun `a clock that jumps backwards does not make an old sighting count`() {
        screener.decide("5550009999", true, 10 * minute)
        assertEquals(CallAction.SILENCE, screener.decide("5550009999", true, 0L))
    }

    @Test
    fun `the prefs format round trips and drops junk`() {
        val seen = mapOf("15551234567" to 5L, "5550009999" to 9L)
        assertEquals(seen, PrefsRecentCallStore.parse(PrefsRecentCallStore.format(seen)))
        assertEquals(mapOf("123" to 1L), PrefsRecentCallStore.parse("junk;123:1;abc:2;:3;45:x"))
        assertTrue(PrefsRecentCallStore.parse("").isEmpty())
    }

    @Test
    fun `seenWithin ignores a blank number`() {
        recent.record("5550009999", 0L)
        assertFalse(recent.seenWithin(null, 1L))
        assertFalse(recent.seenWithin("", 1L))
    }
}

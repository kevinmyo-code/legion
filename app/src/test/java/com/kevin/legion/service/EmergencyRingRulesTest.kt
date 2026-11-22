package com.kevin.legion.service

import com.kevin.legion.service.EmergencyRingRules.Record
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phantom alarm fix (2026-11-21): NWS gives every update of a warning a new id and lists the old
 * ones in `references`, so keying the ring memory on the id rang the same warning again on every
 * update. One ring per alert SERIES now, and a hard cap per night. Made-up ids.
 */
class EmergencyRingRulesTest {
    private val hour = 60L * 60 * 1000
    private val now = 1_800_000_000_000L

    private fun alarm(id: String, severity: Int = 1, vararg refs: String) =
        ProactiveUrgency.Alarm(id, severity, refs.toList())

    @Test
    fun `an update that references an alert that rang is not new`() {
        val records = mapOf("a1" to Record(1, now, "a1"))
        assertEquals("a1", EmergencyRingRules.seriesOf(alarm("a2", 1, "a1"), records))
        assertFalse(EmergencyRingRules.isNew(alarm("a2", 1, "a1"), records))
    }

    @Test
    fun `a chain of updates stays one series`() {
        // a2 was linked into a1's series when it arrived; a3 names only a2.
        val records = mapOf("a1" to Record(1, now, "a1"), "a2" to Record(1, now, "a1"))
        assertEquals("a1", EmergencyRingRules.seriesOf(alarm("a3", 1, "a2"), records))
        assertFalse(EmergencyRingRules.isNew(alarm("a3", 1, "a2"), records))
    }

    @Test
    fun `an alert with no remembered reference is its own series and new`() {
        val records = mapOf("a1" to Record(1, now, "a1"))
        assertTrue(EmergencyRingRules.isNew(alarm("b1"), records))
        assertTrue(EmergencyRingRules.isNew(alarm("b2", 1, "unknown-id"), records))
        assertEquals("b2", EmergencyRingRules.seriesOf(alarm("b2", 1, "unknown-id"), records))
    }

    @Test
    fun `a rise in severity within the series follows the named policy`() {
        val records = mapOf("a1" to Record(1, now, "a1"))
        val rise = EmergencyRingRules.isNew(alarm("a2", 2, "a1"), records)
        assertEquals(EmergencyRingRules.RING_AGAIN_WHEN_SEVERITY_RISES, rise)
        assertFalse(EmergencyRingRules.isNew(alarm("a1", 1), mapOf("a1" to Record(2, now, "a1"))))
    }

    @Test
    fun `at most the cap rings inside the window, older rings do not count`() {
        val max = EmergencyRingRules.MAX_RINGS_PER_NIGHT
        val inside = List(max - 1) { now - (it + 1) * hour }
        assertTrue(EmergencyRingRules.underCap(inside, now))
        assertFalse(EmergencyRingRules.underCap(inside + (now - 10L), now))
        val old = List(max) { now - EmergencyRingRules.CAP_WINDOW_MS - (it + 1) * hour }
        assertTrue(EmergencyRingRules.underCap(old, now))
    }

    @Test
    fun `an Extreme alert past the cap follows the named policy, a Severe one is always capped`() {
        val full = List(EmergencyRingRules.MAX_RINGS_PER_NIGHT) { now - (it + 1) * hour }
        val extreme = EmergencyRingRules.underCap(full, now, EmergencyRingRules.EXTREME_SEVERITY)
        assertEquals(EmergencyRingRules.EXTREME_BYPASSES_CAP, extreme)
        assertFalse(EmergencyRingRules.underCap(full, now, 1))
    }

    @Test
    fun `stored records round-trip, and a record from before series reads as its own series`() {
        val r = Record(2, now, "a1")
        assertEquals(r, EmergencyRingRules.parse("a9", EmergencyRingRules.format(r)))
        assertEquals(Record(2, 123L, "old-id"), EmergencyRingRules.parse("old-id", "2|123"))
        assertEquals(null, EmergencyRingRules.parse("x", "garbage"))
    }
}

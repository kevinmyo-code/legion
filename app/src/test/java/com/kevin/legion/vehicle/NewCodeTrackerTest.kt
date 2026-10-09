package com.kevin.legion.vehicle

import com.kevin.legion.vehicle.NewCodeTracker.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Voice audit finding 5: 47 "new trouble code" raises for codes stored since July. Replays the
 * failure shapes against the tracker. Raw replies are the real ELM327 forms: P0700 is 07 00,
 * P1282 is 12 82, "43 00" is the car saying it has none.
 */
class NewCodeTrackerTest {
    private val car = "AA:BB"
    private val twoCodes = "43 07 00 12 82"
    private val noCodes = "43 00"

    @Test
    fun `an empty failed first read does not become the baseline`() {
        val t = NewCodeTracker().also { it.seed(car, emptySet()) }
        // The 47-raise bug: "NO DATA" -> empty list -> baseline {} -> next real read = all "new".
        assertEquals(Verdict.Skip, t.observe(car, "NO DATA"))
        assertEquals(Verdict.Skip, t.observe(car, ""))
        // The first REAL read is only a baseline, never a raise.
        val first = t.observe(car, twoCodes)
        assertEquals(Verdict.Baselined(setOf("P0700", "P1282")), first)
    }

    @Test
    fun `a failed or clean read mid-session never re-arms the old codes`() {
        val t = NewCodeTracker().also { it.seed(car, emptySet()) }
        t.observe(car, twoCodes)
        assertEquals(Verdict.Skip, t.observe(car, "UNABLE TO CONNECT"))
        assertEquals(Verdict.Unchanged, t.observe(car, noCodes))
        // The old overwrite-every-scan bug raised both codes here.
        assertEquals(Verdict.Unchanged, t.observe(car, twoCodes))
    }

    @Test
    fun `a restart seeded from history does not re-announce stored codes`() {
        val history = NewCodeTracker.historyFrom(listOf("[\"P1282\",\"P0700\"]", "[\"P1282\",\"P0700\",\"P0740\"]"))
        val t = NewCodeTracker().also { it.seed(car, history) }
        assertEquals(setOf("P1282", "P0700", "P0740"), history)
        // 07 40 = P0740. A fresh process, first read, all three already in code_events.
        assertEquals(Verdict.Unchanged, t.observe(car, "43 07 00 07 40 12 82"))
    }

    @Test
    fun `a genuinely new code raises exactly once`() {
        val t = NewCodeTracker().also { it.seed(car, setOf("P0700", "P1282")) }
        val v = t.observe(car, "43 07 00 12 82 03 01") as Verdict.Fresh
        assertEquals(setOf("P0301"), v.fresh)
        assertTrue("P0700" in v.all)
        assertEquals(Verdict.Unchanged, t.observe(car, "43 07 00 12 82 03 01"))
    }

    @Test
    fun `a verified clean first read is a baseline so a later code is new`() {
        val t = NewCodeTracker().also { it.seed(car, emptySet()) }
        assertEquals(Verdict.Baselined(emptySet()), t.observe(car, noCodes))
        assertTrue(t.observe(car, twoCodes) is Verdict.Fresh)
    }

    @Test
    fun `reply validity needs a 43 frame and no failure text`() {
        assertTrue(ObdResponseParser.isValidDtcReply("43 00"))
        assertFalse(ObdResponseParser.isValidDtcReply("SEARCHING...\nNO DATA"))
        assertFalse(ObdResponseParser.isValidDtcReply("41 05 7B"))
        assertFalse(ObdResponseParser.isValidDtcReply(""))
    }

    @Test
    fun `seed is once per vehicle`() {
        val t = NewCodeTracker()
        t.seed(car, setOf("P0700"))
        t.seed(car, emptySet())
        assertEquals(Verdict.Unchanged, t.observe(car, "43 07 00"))
    }
}

package com.kevin.legion.service.turn

import com.kevin.legion.service.turn.BargeInGate.Event
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The echo gate (design 9, A25 call-mode numbers in section 10). */
class BargeInGateTest {
    private val windows = BargeInGate.SUSTAIN_WINDOWS
    private val loud = 25_000
    private val echo = 10_000

    private fun gate() = BargeInGate().apply { onReplyStart() }

    private fun feed(g: BargeInGate, n: Int, prob: Float, level: Int, playing: Boolean = true): List<Event> =
        (1..n).mapNotNull { g.onWindow(prob, level, playing) }

    @Test
    fun `sustained loud speech over the reply fires`() {
        val fire = feed(gate(), windows, 0.95f, loud).single() as Event.Fire
        assertEquals(BargeInGate.SUSTAIN_MS, fire.sustainMs)
        assertEquals(loud, fire.level)
    }

    @Test
    fun `a confident mm-hm of 192 ms followed by silence never fires`() {
        val g = gate()
        assertTrue(feed(g, 6, 1.0f, loud).isEmpty())
        assertTrue(feed(g, 60, 0.1f, 500).isEmpty())
        assertEquals(6, g.onReplyEnd()!!.bestSpanWindows)
    }

    @Test
    fun `two backchannels further apart than the span never add up`() {
        val g = gate()
        assertTrue(feed(g, 6, 1.0f, loud).isEmpty())
        assertTrue(feed(g, BargeInGate.SPAN_WINDOWS - 6, 0.1f, 500).isEmpty())
        assertTrue(feed(g, 6, 1.0f, loud).isEmpty())
        assertEquals(6, g.onReplyEnd()!!.bestSpanWindows)
    }

    @Test
    fun `confident speech at echo level never fires`() {
        assertTrue(feed(gate(), 40, 0.99f, echo).isEmpty())
    }

    @Test
    fun `the echo floor is learned from the reply`() {
        val g = gate()
        assertEquals(BargeInGate.DEFAULT_ECHO_FLOOR, g.echoFloor())
        feed(g, 20, 0.3f, 4_000)
        assertEquals(4_000, g.echoFloor())
        // 7,000 clears 1.5 x 4,000 where the default floor would have held it back.
        assertTrue(feed(g, windows, 0.95f, 7_000).single() is Event.Fire)
    }

    @Test
    fun `eight qualifying windows as 4, gap 2, 4 within the span fire`() {
        val g = gate()
        assertTrue(feed(g, 4, 0.95f, loud).isEmpty())
        assertTrue(feed(g, 2, 0.5f, 2_000).isEmpty())
        assertTrue(feed(g, 3, 0.95f, loud).isEmpty())
        val fire = feed(g, 1, 0.95f, loud).single() as Event.Fire
        assertEquals(BargeInGate.SUSTAIN_MS, fire.sustainMs)
    }

    @Test
    fun `seven qualifying windows in any span never fire`() {
        val g = gate()
        repeat(5) {
            assertTrue(feed(g, 7, 0.95f, loud).isEmpty())
            assertTrue(feed(g, BargeInGate.SPAN_WINDOWS - 7, 0.1f, 500).isEmpty())
        }
        val s = g.onReplyEnd()!!
        assertFalse(s.fired)
        assertEquals(7, s.bestSpanWindows)
    }

    /**
     * A25, 2026-10-28, run 5: "stop" into the mic at prob 1.00 and level 30,560 against a threshold
     * of 3,000; 27 qualifying windows over 394, longest consecutive run 224 ms (7), never fired.
     * Silero jitters across the word: bursts of 7, 2 misses, 2 more. The first burst fires on its
     * 8th qualifying window.
     */
    @Test
    fun `run 5 - a jittery stop fires on the first burst that reaches 8 in a span`() {
        val g = gate()
        feed(g, 20, 0.1f, 500) // call-mode echo, floor near 500
        assertEquals(BargeInGate.MIN_LEVEL, g.threshold())
        assertTrue(feed(g, 7, 1.0f, 30_560).isEmpty())
        assertTrue(feed(g, 2, 0.6f, 9_000).isEmpty())
        val fire = feed(g, 1, 1.0f, 30_560).single() as Event.Fire
        assertEquals(BargeInGate.SUSTAIN_MS, fire.sustainMs)
        assertEquals(30_560, fire.level)
        val s = g.onReplyEnd()!!
        assertTrue(s.fired)
        assertEquals(7 * BargeInGate.WINDOW_MS, s.longestRunMs)
        assertEquals(8, s.bestSpanWindows)
    }

    @Test
    fun `run 5 shape - three bursts separated by silence fire once, on the first`() {
        val g = gate()
        feed(g, 20, 0.1f, 500)
        val events = mutableListOf<Event>()
        var fireAt = -1
        var n = 0
        repeat(3) {
            listOf(7 to true, 2 to false, 2 to true, 40 to false).forEach { (count, speech) ->
                repeat(count) {
                    val prob = if (speech) 1.0f else 0.1f
                    val level = if (speech) 30_560 else 500
                    g.onWindow(prob, level, true)?.let { ev ->
                        if (ev is Event.Fire && fireAt < 0) fireAt = n
                        events += ev
                    }
                    n++
                }
            }
        }
        val fire = events.filterIsInstance<Event.Fire>().single()
        assertEquals(9, fireAt) // 0-based: 7 qualifying, 2 misses, then the 8th qualifying window
        assertEquals(30_560, fire.level)
        assertEquals(1.0f, fire.prob, 0f)
    }

    @Test
    fun `nothing fires while the reply is not playing`() {
        assertTrue(feed(gate(), 20, 0.99f, loud, playing = false).isEmpty())
    }

    @Test
    fun `no speech after a fire is a self-interrupt, two turn barge-in off`() {
        val g = gate()
        feed(g, windows, 0.95f, loud)
        val verdict = feed(g, 60, 0.05f, 300).filterIsInstance<Event.Verdict>().single()
        assertTrue(verdict.selfInterrupt)
        assertFalse(verdict.nowDisabled)

        g.onReplyStart()
        feed(g, windows, 0.95f, loud)
        val second = feed(g, 60, 0.05f, 300).filterIsInstance<Event.Verdict>().single()
        assertTrue(second.nowDisabled)
        assertTrue(g.disabled)
        g.onReplyStart()
        assertTrue(feed(g, 20, 0.99f, loud).isEmpty())
    }

    @Test
    fun `speech continuing after a fire is a real interrupt`() {
        val g = gate()
        feed(g, windows, 0.95f, loud)
        val verdict = feed(g, 60, 0.9f, 20_000).filterIsInstance<Event.Verdict>().single()
        assertFalse(verdict.selfInterrupt)
        assertEquals(0, g.selfInterrupts)
    }

    @Test
    fun `one fire per reply`() {
        val g = gate()
        feed(g, windows, 0.95f, loud)
        feed(g, 60, 0.9f, 20_000)
        assertNull(g.onWindow(0.99f, loud, true))
    }

    /**
     * A25, 2026-10-27, Kevin's second real barge-in: `fire prob=1.00 level=22914 echo_floor=0
     * threshold=3000`, then `speech_after_ms=128`. A short "stop" leaves little speech after the
     * fire, but a peak at seven times the threshold is not echo.
     */
    @Test
    fun `a short loud stop against a quiet echo floor is not a self-interrupt`() {
        val g = gate()
        feed(g, 20, 0.1f, 0) // the reply's echo, learned near zero (AEC in call mode)
        assertEquals(BargeInGate.MIN_LEVEL, g.threshold())
        assertTrue(feed(g, windows, 1.0f, 22_914).single() is Event.Fire)
        // 128 ms of speech left after the fire, then quiet.
        val after = feed(g, 4, 0.9f, 15_000) + feed(g, 60, 0.05f, 200)
        val verdict = after.filterIsInstance<Event.Verdict>().single()
        assertEquals(128, verdict.speechMsAfter)
        assertFalse(verdict.selfInterrupt)
        assertEquals(0, g.selfInterrupts)
    }

    @Test
    fun `a fire just over the threshold with nothing after is still a self-interrupt`() {
        val g = gate()
        feed(g, 20, 0.1f, 8_000) // echo floor 8,000 -> threshold 12,000
        assertTrue(feed(g, windows, 0.95f, 13_000).single() is Event.Fire)
        val verdict = feed(g, 60, 0.05f, 200).filterIsInstance<Event.Verdict>().single()
        assertTrue(verdict.selfInterrupt)
    }

    // ReplyStats: one per reply, enough to tell which of (a)-(d) kept a reply from firing.

    @Test
    fun `stats - a quiet voice is speech but never loud enough`() {
        val g = gate()
        assertTrue(feed(g, 20, 0.95f, 5_000).isEmpty())
        val s = g.onReplyEnd()!!
        assertFalse(s.fired)
        assertEquals(20, s.windows)
        assertEquals(20, s.speechWindows)
        assertEquals(0, s.loudWindows)
        assertEquals(0, s.qualifyingWindows)
        assertEquals(0.95f, s.maxProb, 1e-6f)
        assertEquals(5_000, s.maxLevel)
        assertEquals(0, s.longestRunMs)
        assertEquals(5_000, s.echoFloor)
        assertEquals(7_500, s.threshold)
    }

    @Test
    fun `stats - a loud voice Silero is unsure of never counts`() {
        val g = gate()
        feed(g, 20, 0.3f, 4_000)
        assertTrue(feed(g, 10, 0.6f, 25_000).isEmpty())
        val s = g.onReplyEnd()!!
        assertEquals(30, s.windows)
        assertEquals(0, s.speechWindows)
        assertTrue(s.loudWindows > 0)
        assertEquals(0, s.qualifyingWindows)
        assertEquals(0.6f, s.maxProb, 1e-6f)
        assertEquals(25_000, s.maxLevel)
        assertEquals(0, s.longestRunMs)
    }

    @Test
    fun `stats - choppy runs qualify but never fill a span`() {
        val g = gate()
        repeat(4) {
            assertTrue(feed(g, 3, 0.95f, loud).isEmpty())
            assertTrue(feed(g, 5, 0.1f, 500).isEmpty())
        }
        val s = g.onReplyEnd()!!
        assertFalse(s.fired)
        assertEquals(32, s.windows)
        assertEquals(12, s.qualifyingWindows)
        assertEquals(3 * BargeInGate.WINDOW_MS, s.longestRunMs)
        assertEquals(6, s.bestSpanWindows)
        assertTrue(s.bestSpanWindows < BargeInGate.SUSTAIN_WINDOWS)
    }

    @Test
    fun `stats - a reply the gate never heard has zero windows`() {
        val g = gate()
        feed(g, 20, 0.99f, loud, playing = false)
        val s = g.onReplyEnd()!!
        assertEquals(0, s.windows)
        assertEquals(BargeInGate.DEFAULT_ECHO_FLOOR, s.echoFloor)
        assertEquals((BargeInGate.DEFAULT_ECHO_FLOOR * BargeInGate.ECHO_MARGIN).toInt(), s.threshold)
    }

    @Test
    fun `stats - a fire is recorded, verdict windows are not counted, and a second end is null`() {
        val g = gate()
        feed(g, windows, 0.95f, loud)
        feed(g, 10, 0.05f, 300)
        val s = g.onReplyEnd()!!
        assertTrue(s.fired)
        assertEquals(windows, s.windows)
        assertEquals(windows, s.qualifyingWindows)
        assertEquals(BargeInGate.SUSTAIN_MS, s.longestRunMs)
        assertEquals(windows, s.bestSpanWindows)
        assertEquals(loud, s.maxLevel)
        assertNull(g.onReplyEnd())
    }

    @Test
    fun `stats - a new reply starts from zero`() {
        val g = gate()
        feed(g, windows, 0.95f, loud)
        g.onReplyStart()
        val s = g.onReplyEnd()!!
        assertFalse(s.fired)
        assertEquals(0, s.windows)
        assertEquals(0f, s.maxProb, 0f)
    }

    @Test
    fun `no reply open means no stats`() {
        assertNull(BargeInGate().onReplyEnd())
    }
}

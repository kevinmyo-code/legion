package com.kevin.legion.service.turn

import com.kevin.legion.service.turn.BargeInGate.Event
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The echo gate (design 9, A25 call-mode numbers in section 10). */
class BargeInGateTest {
    private val windows = BargeInGate.SUSTAIN_MS / BargeInGate.WINDOW_MS
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
    fun `a backchannel shorter than the sustain does not fire`() {
        val g = gate()
        assertTrue(feed(g, windows - 2, 0.95f, loud).isEmpty())
        assertTrue(feed(g, 4, 0.1f, 500).isEmpty())
        assertTrue(feed(g, windows - 2, 0.95f, loud).isEmpty())
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
    fun `one stray window inside the run is tolerated, two are not`() {
        val g = gate()
        assertTrue(feed(g, windows - 1, 0.95f, loud).isEmpty())
        assertTrue(feed(g, 1, 0.5f, 2_000).isEmpty())
        assertTrue(feed(g, 1, 0.95f, loud).single() is Event.Fire)

        val h = gate()
        feed(h, windows - 1, 0.95f, loud)
        feed(h, 2, 0.5f, 2_000)
        assertTrue(feed(h, 1, 0.95f, loud).isEmpty())
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
}

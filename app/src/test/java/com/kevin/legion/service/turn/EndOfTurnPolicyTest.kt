package com.kevin.legion.service.turn

import com.kevin.legion.service.turn.EndOfTurnPolicy.Action
import com.kevin.legion.service.turn.EndOfTurnPolicy.Reason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The extend-only end-of-turn rule (Kevin, 2026-10-27), window by window. */
class EndOfTurnPolicyTest {
    private val speech = 0.9f
    private val quiet = 0.05f
    private val windowMs = EndOfTurnPolicy.WINDOW_MS

    private fun speaking(smart: Boolean = true): EndOfTurnPolicy {
        val p = EndOfTurnPolicy(smart)
        repeat(20) { p.onWindow(speech) }
        assertTrue(p.inActivity)
        return p
    }

    /** Feeds [ms] of silence, collecting actions with the silence at which each came. */
    private fun silence(p: EndOfTurnPolicy, ms: Int): List<Pair<Int, Action>> {
        val out = mutableListOf<Pair<Int, Action>>()
        var t = 0
        while (t < ms) {
            p.onWindow(quiet).forEach { out += (p.currentSilenceMs.takeIf { it > 0 } ?: (t + windowMs)) to it }
            t += windowMs
        }
        return out
    }

    private fun actions(p: EndOfTurnPolicy, ms: Int) = silence(p, ms).map { it.second }

    @Test
    fun `speech starts after the 200 ms confirm, not before`() {
        val p = EndOfTurnPolicy(true)
        val got = (1..7).map { p.onWindow(speech) }
        assertTrue(got.take(6).all { it.isEmpty() })
        assertEquals(Action.StartActivity(7), got[6].single())
    }

    @Test
    fun `a blip shorter than the confirm never starts a turn`() {
        val p = EndOfTurnPolicy(true)
        repeat(4) { assertTrue(p.onWindow(speech).isEmpty()) }
        assertTrue(p.onWindow(quiet).isEmpty())
        assertFalse(p.inActivity)
    }

    @Test
    fun `one check starts at the 192 ms gate`() {
        val p = speaking()
        val checks = actions(p, 300).filterIsInstance<Action.RequestCheck>()
        assertEquals(1, checks.size)
        assertEquals(EndOfTurnPolicy.GATE_MS, checks.single().silenceMs)
    }

    @Test
    fun `no verdict before 700 ms ends the turn at the base`() {
        val p = speaking()
        val end = actions(p, 800).filterIsInstance<Action.EndActivity>().single()
        assertEquals(Reason.BASE, end.reason)
        assertEquals(704, end.silenceMs)
    }

    @Test
    fun `a done verdict before the base still waits for the base`() {
        val p = speaking()
        val check = actions(p, 320).filterIsInstance<Action.RequestCheck>().single()
        assertTrue(p.onCheckResult(check.checkId, 0.9f).isEmpty())
        val end = actions(p, 500).filterIsInstance<Action.EndActivity>().single()
        assertEquals(Reason.BASE, end.reason)
        assertEquals(704, end.silenceMs)
    }

    @Test
    fun `not done before the base holds, re-checks one at a time, ends on the first done`() {
        val p = speaking()
        val first = actions(p, 400).filterIsInstance<Action.RequestCheck>().single()
        val second = p.onCheckResult(first.checkId, 0.2f).single() as Action.RequestCheck
        // Past the base: no end while held, and no new check while one is in flight.
        val during = actions(p, 600)
        assertTrue(during.none { it is Action.EndActivity || it is Action.RequestCheck })
        val third = p.onCheckResult(second.checkId, 0.3f).single() as Action.RequestCheck
        actions(p, 200)
        val end = p.onCheckResult(third.checkId, 0.8f).single() as Action.EndActivity
        assertEquals(Reason.EXTENDED, end.reason)
        assertEquals(listOf(0.2f, 0.3f, 0.8f), end.scores.map { it.score })
        assertTrue(end.silenceMs > EndOfTurnPolicy.BASE_MS)
    }

    @Test
    fun `a held turn ends at the cap`() {
        val p = speaking()
        val first = actions(p, 400).filterIsInstance<Action.RequestCheck>().single()
        var id = (p.onCheckResult(first.checkId, 0.1f).single() as Action.RequestCheck).checkId
        var end: Action.EndActivity? = null
        var n = 0
        while (end == null && n < 200) {
            n++
            end = p.onWindow(quiet).filterIsInstance<Action.EndActivity>().firstOrNull()
            if (end == null && n % 10 == 0) {
                id = (p.onCheckResult(id, 0.1f).single() as Action.RequestCheck).checkId
            }
        }
        assertEquals(Reason.CAP, end!!.reason)
        assertTrue(end.silenceMs >= EndOfTurnPolicy.CAP_MS)
    }

    @Test
    fun `a first verdict after the base is too late to extend`() {
        val p = speaking()
        val got = actions(p, 800)
        val check = got.filterIsInstance<Action.RequestCheck>().single()
        assertEquals(Reason.BASE, got.filterIsInstance<Action.EndActivity>().single().reason)
        assertTrue(p.onCheckResult(check.checkId, 0.1f).isEmpty())
        assertFalse(p.inActivity)
    }

    @Test
    fun `speech resuming cancels a pending check and a hold`() {
        val p = speaking()
        val check = actions(p, 400).filterIsInstance<Action.RequestCheck>().single()
        val next = p.onCheckResult(check.checkId, 0.1f).single() as Action.RequestCheck
        assertTrue(p.onWindow(speech).single() is Action.SpeechResumed)
        assertTrue(p.onCheckResult(next.checkId, 0.9f).isEmpty())
        // A fresh silence starts over: gate check again, base end at 704.
        val got = actions(p, 800)
        assertEquals(1, got.filterIsInstance<Action.RequestCheck>().size)
        assertEquals(Reason.BASE, got.filterIsInstance<Action.EndActivity>().single().reason)
    }

    @Test
    fun `quiet but not silent windows do not resume speech`() {
        val p = speaking()
        actions(p, 300)
        assertTrue(p.onWindow(0.4f).none { it is Action.SpeechResumed })
    }

    @Test
    fun `without smart turn it is silence only, reason fallback`() {
        val p = speaking(smart = false)
        val got = actions(p, 800)
        assertTrue(got.none { it is Action.RequestCheck })
        assertEquals(Reason.FALLBACK, got.filterIsInstance<Action.EndActivity>().single().reason)
    }

    @Test
    fun `a check that throws while held ends the turn on silence`() {
        val p = speaking()
        val check = actions(p, 400).filterIsInstance<Action.RequestCheck>().single()
        val next = p.onCheckResult(check.checkId, 0.1f).single() as Action.RequestCheck
        actions(p, 500)
        val end = p.onCheckFailed(next.checkId).single() as Action.EndActivity
        assertEquals(Reason.FALLBACK, end.reason)
    }

    @Test
    fun `barge-in starts the activity at once`() {
        val p = EndOfTurnPolicy(true)
        assertEquals(Action.StartActivity(0), p.forceStart())
        assertTrue(p.inActivity)
    }

    /**
     * A25, 2026-10-27, "call the bank and ...": Smart Turn held the first pause to 1,760 ms (16 checks
     * at ~0.007), Kevin went on, and the second silence ended at the base after a late 0.578. The
     * turn WAS extended, so the end line must say so, with 704 as the final silence.
     */
    @Test
    fun `a turn held once and then ended at the base reports extended`() {
        val p = speaking()
        var id = actions(p, 192).filterIsInstance<Action.RequestCheck>().single().checkId
        var n = 0
        while (n < 49) { // to ~1,760 ms of silence, answering "not done" every 3 windows
            n++
            p.onWindow(quiet)
            if (n % 3 == 0) id = (p.onCheckResult(id, 0.007f).single() as Action.RequestCheck).checkId
        }
        assertTrue(p.onWindow(speech).single() is Action.SpeechResumed)
        val second = actions(p, 192).filterIsInstance<Action.RequestCheck>().single()
        val third = p.onCheckResult(second.checkId, 0.099f).single() as Action.RequestCheck
        assertTrue(p.onCheckResult(third.checkId, 0.578f).isEmpty())
        val end = actions(p, 600).filterIsInstance<Action.EndActivity>().single()
        assertEquals(Reason.EXTENDED, end.reason)
        assertEquals(704, end.silenceMs)
        assertTrue(end.longestSilenceMs >= 1_700)
    }

    @Test
    fun `a turn never held ends as base with its own silence as the longest`() {
        val p = speaking()
        val end = actions(p, 800).filterIsInstance<Action.EndActivity>().single()
        assertEquals(Reason.BASE, end.reason)
        assertEquals(704, end.longestSilenceMs)
    }
}

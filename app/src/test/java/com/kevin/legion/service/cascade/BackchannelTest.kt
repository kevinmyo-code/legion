@file:Suppress("MaxLineLength") // test fixtures read best on one line

package com.kevin.legion.service.cascade

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [Backchannel]'s rules as pure functions, with made-up clock values (Kevin, 2026-11-19, speed plan
 * item 3). The session's wiring is pinned in [VoiceSpeedWiringTest].
 */
class BackchannelTest {

    private fun turn(
        speechMs: Long = 2_500,
        askedBefore: Boolean = false,
        over: Boolean = false,
        quiet: Boolean = false,
        fromUser: Boolean = true,
        startedAt: Long = 100_000,
        lastAt: Long = 0,
        lastTurnHadOne: Boolean = false,
    ) = Backchannel.Turn(fromUser, speechMs, askedBefore, over, quiet, startedAt, lastAt, lastTurnHadOne)

    @Test
    fun `an ordinary turn of his gets one`() {
        assertNull(Backchannel.decide(turn()))
    }

    @Test
    fun `short speech, an answer to an ask, a barge-in, Quiet and a turn not his get none`() {
        assertEquals(Backchannel.Skip.SHORT, Backchannel.decide(turn(speechMs = Backchannel.MIN_SPEECH_MS - 1)))
        assertNull(Backchannel.decide(turn(speechMs = Backchannel.MIN_SPEECH_MS)))
        assertEquals(Backchannel.Skip.ASKED, Backchannel.decide(turn(askedBefore = true)))
        assertEquals(Backchannel.Skip.OVER_REPLY, Backchannel.decide(turn(over = true)))
        assertEquals(Backchannel.Skip.QUIET, Backchannel.decide(turn(quiet = true)))
        assertEquals(Backchannel.Skip.NOT_HIS, Backchannel.decide(turn(fromUser = false)))
    }

    @Test
    fun `one on the last turn and a quick next turn is a tic, a later turn is not`() {
        val tic = turn(startedAt = 110_000, lastAt = 110_000 - Backchannel.TIC_GAP_MS + 1, lastTurnHadOne = true)
        assertEquals(Backchannel.Skip.TIC, Backchannel.decide(tic))
        assertNull(Backchannel.decide(turn(startedAt = 200_000, lastAt = 200_000 - Backchannel.TIC_GAP_MS, lastTurnHadOne = true)))
        // The turn before had none: the one before that does not count.
        assertNull(Backchannel.decide(turn(startedAt = 110_000, lastAt = 105_000, lastTurnHadOne = false)))
    }

    @Test
    fun `never the same clip twice running, none of the last few, only from memory`() {
        val recent = RecentLines(Backchannel.ROTATION)
        val said = mutableListOf<String>()
        repeat(20) { i ->
            val line = Backchannel.pick(recent, i) { _, _ -> true }!!.first
            if (said.isNotEmpty()) assertFalse("twice running at $i", said.last() == line)
            assertFalse("within the last ${Backchannel.ROTATION} at $i", recent.contains(line))
            recent.add(line)
            said += line
        }
        assertTrue(said.toSet().size >= Backchannel.ROTATION + 1)
        assertNull(Backchannel.pick(RecentLines(Backchannel.ROTATION), 0) { _, _ -> false })
        // Only one in memory and it was just said: nothing, rather than a repeat.
        val one = RecentLines(Backchannel.ROTATION).apply { add("Mm.") }
        assertNull(Backchannel.pick(one, 0) { w, _ -> w == "Mm." })
    }

    @Test
    fun `only a clip of a backchannel's length plays`() {
        assertFalse(Backchannel.clipFits(0))
        assertFalse(Backchannel.clipFits(Backchannel.MIN_CLIP_MS - 1))
        assertTrue(Backchannel.clipFits(300))
        assertFalse(Backchannel.clipFits(Backchannel.MAX_CLIP_MS + 1))
    }

    @Test
    fun `the answer's audio first always wins, and only for its own turn`() {
        assertTrue(Backchannel.stillDue(answerStarted = false, somethingAudible = false, sameTurn = true))
        assertFalse(Backchannel.stillDue(answerStarted = true, somethingAudible = false, sameTurn = true))
        assertFalse(Backchannel.stillDue(answerStarted = false, somethingAudible = true, sameTurn = true))
        assertFalse(Backchannel.stillDue(answerStarted = false, somethingAudible = false, sameTurn = false))
    }

    @Test
    fun `the task line waits out the spacing after a backchannel, never stacks`() {
        assertEquals(0L, Backchannel.spacingWaitMs(nowMs = 5_000, backchannelEndAtMs = null))
        assertEquals(Backchannel.SPACING_MS, Backchannel.spacingWaitMs(nowMs = 5_000, backchannelEndAtMs = 5_000))
        assertEquals(150L, Backchannel.spacingWaitMs(nowMs = 5_250, backchannelEndAtMs = 5_000))
        assertEquals(0L, Backchannel.spacingWaitMs(nowMs = 9_000, backchannelEndAtMs = 5_000))
    }

    @Test
    fun `the clips are short, content-free and kept like the fillers`() {
        for (line in Backchannel.LINES) {
            // One word, or "Let's see." (2026-11-19 variety): two words, still a sound of listening.
            assertTrue(line, line.split(' ').size <= 2)
            assertTrue(line, line in ScriptedLines.KEEP)
            assertFalse("a backchannel is not a filler the model's text is checked against", line in ScriptedLines.FILLERS)
        }
    }

    @Test
    fun `resumed speech during a backchannel still merges, and never cancels a write`() {
        // A backchannel never sets the session's speaking flag, so the newSpeech rule sees no reply.
        assertEquals(
            CascadeRules.NewSpeech.MERGE,
            CascadeRules.newSpeech(turnRunning = true, replyAudible = false, pendingFromUser = true, toolsCalled = false, writeCalled = false, bargeIn = false),
        )
        // A write already called is never merged away (half-duplex: left to finish).
        assertEquals(
            CascadeRules.NewSpeech.NONE,
            CascadeRules.newSpeech(turnRunning = true, replyAudible = false, pendingFromUser = true, toolsCalled = true, writeCalled = true, bargeIn = false),
        )
    }

    @Test
    fun `recent lines keep only the window, newest last`() {
        val r = RecentLines(2, listOf("a", "b", "c"))
        assertEquals(listOf("b", "c"), r.toList())
        r.add("d")
        assertEquals(listOf("c", "d"), r.toList())
        assertTrue(r.contains("C"))
        assertEquals("d", r.last())
    }

    @Test
    fun `a slow tool's line plays though its call has not reached the executor, a late one only while one runs`() {
        // The slow line's check can run before the executor counts the call (same loop): never asked.
        assertTrue(ToolFillers.stillDue(slow = true, sameTurn = true, toolsRunning = false, answerStarted = false, played = false))
        assertFalse(ToolFillers.stillDue(slow = false, sameTurn = true, toolsRunning = false, answerStarted = false, played = false))
        assertTrue(ToolFillers.stillDue(slow = false, sameTurn = true, toolsRunning = true, answerStarted = false, played = false))
        for ((same, answered, played) in listOf(Triple(false, false, false), Triple(true, true, false), Triple(true, false, true))) {
            assertFalse(ToolFillers.stillDue(slow = true, sameTurn = same, toolsRunning = true, answerStarted = answered, played = played))
        }
    }

    @Test
    fun `kept clips are looked up by the spoken form, which is the line itself`() {
        // inHand and speak get SpokenText.forSpeech(line); KEEP holds the line. They must be equal.
        for (line in Backchannel.LINES + ScriptedLines.FIXED_DONE_LINES + ToolFillers.GENERIC) {
            assertEquals(line, SpokenText.forSpeech(line))
        }
    }

    @Test
    fun `half-duplex gets none unless its setting allows it (the mic is deaf during a clip)`() {
        val half = turn().copy(halfDuplex = true)
        assertEquals(Backchannel.Skip.HALF_DUPLEX, Backchannel.decide(half))
        assertNull(Backchannel.decide(half.copy(halfDuplexAllowed = true)))
        assertNull(Backchannel.decide(turn().copy(halfDuplex = false)))
    }
}

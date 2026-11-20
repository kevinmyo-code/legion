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
        over: Boolean = false,
        quiet: Boolean = false,
        fromUser: Boolean = true,
    ) = Backchannel.Turn(fromUser, over, quiet)

    @Test
    fun `an ordinary turn of his gets one`() {
        assertNull(Backchannel.decide(turn()))
    }

    // Kevin, 2026-11-20: "the scripted fillers should fire every time, no restrictions". Short speech,
    // an answer to an ask and a quick next turn were taste, not necessity: each gets one now.
    // The Turn no longer carries his speech length, the last turn's ask fields or the last clip's
    // time: no rule can read them, so none of the three can skip a turn again.
    @Test
    fun `short speech, an answer to an ask and a quick next turn each get one`() {
        assertNull(Backchannel.decide(turn()))
        assertEquals(
            listOf("NOT_HIS", "ON_CALL", "OVER_REPLY", "QUIET", "HALF_DUPLEX", "ANSWER_TEXT"),
            Backchannel.Skip.entries.map { it.name },
        )
    }

    @Test
    fun `a barge-in, Quiet and a turn not his still get none`() {
        assertEquals(Backchannel.Skip.OVER_REPLY, Backchannel.decide(turn(over = true)))
        assertEquals(Backchannel.Skip.QUIET, Backchannel.decide(turn(quiet = true)))
        assertEquals(Backchannel.Skip.NOT_HIS, Backchannel.decide(turn(fromUser = false)))
    }

    @Test
    fun `five turns in a row of mixed lengths get five clips, never the same twice running`() {
        val recent = RecentLines(Backchannel.ROTATION)
        val said = mutableListOf<String>()
        // "Yes." (0.4 s), a question (3 s), "No." (0.25 s), a follow-up (1.1 s), a long one (6 s): the
        // length is no input any more, so each is the same ordinary turn of his.
        for (i in 0 until 5) {
            assertNull("turn $i", Backchannel.decide(turn()))
            val line = Backchannel.pick(recent, i) { _, _ -> true }!!.first
            assertFalse("repeat at $i", line in said)
            recent.add(line)
            said += line
        }
        assertEquals(5, said.size)
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
    fun `a clip that fails the length gate is swapped for another word, silent only if none fits`() = kotlinx.coroutines.runBlocking {
        val recent = RecentLines(Backchannel.ROTATION)
        val first = Backchannel.pick(recent, 0) { _, _ -> true }!!.first
        // The first word's clip renders as a sigh (too long): the next word plays instead.
        val chosen = Backchannel.choose(recent, 0, { _, _ -> true }) { w, _ -> if (w == first) 1_500L else 300L }!!
        assertFalse(chosen.word == first)
        assertEquals(300L, chosen.ms)
        assertEquals(1, chosen.rejected)
        // Every clip fails: nothing, rather than a clip outside the gate.
        assertNull(Backchannel.choose(recent, 0, { _, _ -> true }) { _, _ -> 0L })
        // Nothing in memory: nothing.
        assertNull(Backchannel.choose(recent, 0, { _, _ -> false }) { _, _ -> 300L })
    }

    @Test
    fun `the open's chime is not a reply, and a clip queues after it while it can start in time`() {
        assertFalse(Backchannel.overReply(replyAudible = true, turnInFlight = true, openingCue = true))
        assertTrue(Backchannel.overReply(replyAudible = true, turnInFlight = false, openingCue = false))
        assertTrue(Backchannel.overReply(replyAudible = false, turnInFlight = true, openingCue = false))
        // His speech ended at 10,000; the clip is due at 10,600.
        assertEquals(0L, Backchannel.cueWaitMs(nowMs = 10_600, eotAtMs = 10_000, cueEndsAtMs = null))
        assertEquals(0L, Backchannel.cueWaitMs(nowMs = 10_600, eotAtMs = 10_000, cueEndsAtMs = 10_400))
        // The chime ends at 10,800: the clip waits 200 ms and starts 800 ms after his last word.
        assertEquals(200L, Backchannel.cueWaitMs(nowMs = 10_600, eotAtMs = 10_000, cueEndsAtMs = 10_800))
        assertEquals(300L, Backchannel.cueWaitMs(nowMs = 10_600, eotAtMs = 10_000, cueEndsAtMs = 10_000 + Backchannel.MAX_START_AFTER_EOT_MS))
        // It would start later than that: skipped.
        assertNull(Backchannel.cueWaitMs(nowMs = 10_600, eotAtMs = 10_000, cueEndsAtMs = 10_000 + Backchannel.MAX_START_AFTER_EOT_MS + 1))
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
            assertTrue(line, line.split(' ').size == 1)
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
    fun `a phone call gets none, even where half-duplex clips are allowed`() {
        assertEquals(Backchannel.Skip.ON_CALL, Backchannel.decide(turn().copy(onCall = true)))
        assertEquals(Backchannel.Skip.ON_CALL, Backchannel.decide(turn().copy(onCall = true, halfDuplex = true, halfDuplexAllowed = true)))
        assertTrue("the half-duplex skip stays until Kevin rules on it", Backchannel.SKIP_ON_HALF_DUPLEX)
    }

    @Test
    fun `half-duplex gets none unless its setting allows it (the mic is deaf during a clip)`() {
        val half = turn().copy(halfDuplex = true)
        assertEquals(Backchannel.Skip.HALF_DUPLEX, Backchannel.decide(half))
        assertNull(Backchannel.decide(half.copy(halfDuplexAllowed = true)))
        assertNull(Backchannel.decide(turn().copy(halfDuplex = false)))
    }
}

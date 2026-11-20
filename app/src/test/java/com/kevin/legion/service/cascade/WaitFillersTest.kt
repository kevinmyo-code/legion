package com.kevin.legion.service.cascade

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [WaitFillers] (Kevin, 2026-11-20: chained fillers on a long wait). Made-up clock values: his
 * speech ends at 0, the backchannel plays at 600 ms and ends at 1,000 ms, a wait line lasts 800 ms.
 */
class WaitFillersTest {
    private val backchannelEnd = 1_000L
    private val clip = 800L

    @Test
    fun `a 10 s wait gets the backchannel and two wait lines at their offsets, and no more`() {
        val starts = WaitFillers.timeline(backchannelEnd, fillersBefore = 1, answerAtMs = 10_000, clipMs = clip)
        // 1,000 + 3,500 = 4,500; it ends at 5,300; 5,300 + 4,000 = 9,300. Then the cap (3) is reached.
        assertEquals(listOf(4_500L, 9_300L), starts)
        val forever = WaitFillers.timeline(backchannelEnd, fillersBefore = 1, answerAtMs = 60_000, clipMs = clip)
        assertEquals("the cap holds however long the wait", 2, forever.size)
    }

    @Test
    fun `an answer during the first wait line cancels the second, a 2 s wait gets none`() {
        // The answer is ready at 5,000, while the first line (4,500 to 5,300) plays: no second line;
        // the answer waits for the line to end (the session's play gate).
        assertEquals(listOf(4_500L), WaitFillers.timeline(backchannelEnd, 1, answerAtMs = 5_000, clipMs = clip))
        assertTrue(WaitFillers.timeline(backchannelEnd, 1, answerAtMs = 2_000, clipMs = clip).isEmpty())
    }

    @Test
    fun `the cap counts every kind of filler`() {
        // Backchannel and a task line already played: one wait line at most.
        assertEquals(1, WaitFillers.timeline(3_000, fillersBefore = 2, answerAtMs = 60_000, clipMs = clip).size)
        assertNull(WaitFillers.nextAtMs(3_000, waitSaid = 0, fillersSaid = WaitFillers.MAX_PER_TURN))
        // No backchannel (a skip): the first wait line counts from his end of speech.
        assertEquals(3_500L, WaitFillers.nextAtMs(0, waitSaid = 0, fillersSaid = 0))
    }

    @Test
    fun `no line twice in a turn, none of the last five, and a failed clip falls back`() = runBlocking {
        val recent = RecentLines(WaitFillers.ROTATION)
        val said = mutableSetOf<String>()
        repeat(2) { i ->
            val c = WaitFillers.choose(recent, said, i, { _, _ -> true }) { _, _ -> 700L }!!
            assertFalse("twice in one turn", c.line in said)
            said += c.line
            recent.add(c.line)
        }
        // The task line said "Working on it." this turn: never again as a wait line.
        val task = setOf("Working on it.")
        val afterTask = WaitFillers.choose(RecentLines(5), task, 4, { _, _ -> true }) { _, _ -> 700L }!!
        assertFalse(afterTask.line == "Working on it.")
        // Five turns of two lines: no line within the last five wait lines.
        val window = RecentLines(WaitFillers.ROTATION)
        val all = mutableListOf<String>()
        repeat(5) { i ->
            val line = WaitFillers.choose(window, emptySet(), i, { _, _ -> true }) { _, _ -> 700L }!!.line
            assertFalse("within the last five at $i", window.contains(line))
            window.add(line)
            all += line
        }
        assertEquals(5, all.toSet().size)
        // The first choice's clip renders too long: another line plays.
        val first = WaitFillers.choose(RecentLines(5), emptySet(), 0, { _, _ -> true }) { _, _ -> 700L }!!.line
        val swapped = WaitFillers.choose(RecentLines(5), emptySet(), 0, { _, _ -> true }) { l, _ ->
            if (l == first) 2_000L else 700L
        }!!
        assertFalse(swapped.line == first)
        assertEquals(1, swapped.rejected)
        assertNull(WaitFillers.choose(RecentLines(5), emptySet(), 0, { _, _ -> true }) { _, _ -> 2_000L })
    }

    @Test
    fun `the lines are short, neutral and kept, and the rules match the backchannel's`() {
        for (line in WaitFillers.LINES) {
            assertTrue(line, line.split(' ').size <= 3)
            assertTrue(line, line in ScriptedLines.KEEP)
        }
        // Quiet, a call, over a reply, half-duplex: the backchannel's own decision, which the session
        // applies to the wait lines too.
        val base = Backchannel.Turn(fromUser = true, overReplyOrTurn = false, quiet = false)
        assertEquals(Backchannel.Skip.QUIET, Backchannel.decide(base.copy(quiet = true)))
        assertEquals(Backchannel.Skip.ON_CALL, Backchannel.decide(base.copy(onCall = true)))
    }
}

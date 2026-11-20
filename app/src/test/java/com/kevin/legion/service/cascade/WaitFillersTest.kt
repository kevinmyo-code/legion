@file:Suppress("MaxLineLength") // test fixtures read best on one line

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
    fun `a 10 s wait gets the backchannel and three wait lines at their offsets, and no more`() {
        val starts = WaitFillers.timeline(backchannelEnd, fillersBefore = 1, answerAtMs = 10_000, clipMs = clip)
        // Kevin's ruling, 2026-11-20 (his phone log: 3 to 4 s of silence after the backchannel on most turns):
        // 1,000 + 1,800 = 2,800; it ends at 3,600; 3,600 + 2,500 = 6,100; it ends at 6,900; 6,900 + 2,500 = 9,400.
        assertEquals(listOf(2_800L, 6_100L, 9_400L), starts)
        val forever = WaitFillers.timeline(backchannelEnd, fillersBefore = 1, answerAtMs = 60_000, clipMs = clip)
        // Every wait line once at most in a turn, and never more than the hard cap of fillers.
        assertEquals(minOf(WaitFillers.LINES.size, WaitFillers.MAX_PER_TURN - 1), forever.size)
        // No silence longer than the gap rule while lines are left.
        val gaps = listOf(forever.first() - backchannelEnd) + forever.zipWithNext { a, b -> b - (a + clip) }
        assertTrue(gaps.toString(), gaps.all { it <= WaitFillers.NEXT_GAP_MS })
    }

    @Test
    fun `an answer during the first wait line cancels the second, a 2 s wait gets none`() {
        // The answer is ready at 3,000, while the first line (2,800 to 3,600) plays: no second line;
        // the answer waits for the line to end (the session's play gate).
        assertEquals(listOf(2_800L), WaitFillers.timeline(backchannelEnd, 1, answerAtMs = 3_000, clipMs = clip))
        assertTrue(WaitFillers.timeline(backchannelEnd, 1, answerAtMs = 2_000, clipMs = clip).isEmpty())
    }

    // Review of 546cce67 (F5): a wait line due while a task line was queued played right after it.
    // Under the play lock it is due only FIRST_GAP_MS after the END of the last filler of any kind.
    @Test
    fun `a wait line is due only after the gap from the last filler's end, never during a task line`() {
        // A task line ended at 4,000: not before 5,800.
        assertFalse(WaitFillers.dueNow(nowMs = 4_100, lastFillerEndAtMs = 4_000, waitSaid = 0, fillersSaid = 2, fillerPlaying = false))
        assertTrue(WaitFillers.dueNow(nowMs = 5_800, lastFillerEndAtMs = 4_000, waitSaid = 0, fillersSaid = 2, fillerPlaying = false))
        // A task line still playing: never.
        assertFalse(WaitFillers.dueNow(nowMs = 9_000, lastFillerEndAtMs = 1_000, waitSaid = 0, fillersSaid = 2, fillerPlaying = true))
        // The cap: never.
        assertFalse(WaitFillers.dueNow(nowMs = 60_000, lastFillerEndAtMs = 0, waitSaid = 0, fillersSaid = WaitFillers.MAX_PER_TURN, fillerPlaying = false))
    }

    @Test
    fun `the cap counts every kind of filler`() {
        // Backchannel and a task line already played: up to the hard cap of 6 fillers in all.
        assertEquals(4, WaitFillers.timeline(3_000, fillersBefore = 2, answerAtMs = 60_000, clipMs = clip).size)
        assertNull(WaitFillers.nextAtMs(3_000, waitSaid = 0, fillersSaid = WaitFillers.MAX_PER_TURN))
        // Never a wait line twice in a turn: once every line was said, none more.
        assertNull(WaitFillers.nextAtMs(3_000, waitSaid = WaitFillers.LINES.size, fillersSaid = 1))
        // No backchannel (a skip): the first wait line counts from his end of speech.
        assertEquals(1_800L, WaitFillers.nextAtMs(0, waitSaid = 0, fillersSaid = 0))
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

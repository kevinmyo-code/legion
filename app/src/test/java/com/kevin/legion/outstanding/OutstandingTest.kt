package com.kevin.legion.outstanding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ranking, which is the whole design of `Outstanding.kt`.
 *
 * `OutstandingController` only fetches; every judgement lives in the pure functions here, which is
 * what lets this run without Room and without a device. The fixtures use the real shapes from
 * 2026-09-12: a coursework deadline that passed unticked (WK02: Bio Video Presentation, MKTG 3303),
 * nine more due the next night, and bio-plan lines that reset nightly.
 */
class OutstandingTest {

    private val now = 1_789_000_000_000L
    private val hour = 3_600_000L

    private fun deadline(id: String, title: String, dueOffsetHours: Long, done: Boolean = false) =
        OutstandingItem(
            id = id,
            title = title,
            kind = OutstandingKind.DEADLINE,
            dueAtMs = now + dueOffsetHours * hour,
            overdue = dueOffsetHours < 0 && !done,
            done = done,
            source = title.substringBefore('·').trim().takeIf { it.isNotBlank() },
        )

    private fun reminder(id: String, title: String) =
        OutstandingItem(id, title, OutstandingKind.REMINDER, null, false, false, null)

    private fun line(id: String, title: String, done: Boolean) =
        OutstandingItem(id, title, OutstandingKind.CHECKLIST_LINE, null, false, done, "bio")

    @Test
    fun `a deadline tonight outranks an errand with no date, which outranks a ticked line`() {
        // The ticket's own sentence, pinned. This is the ordering rule and everything else is detail.
        val ranked = rankOutstanding(
            listOf(
                line("l1", "3 sets goblet squats", done = true),
                reminder("r1", "return the library book"),
                deadline("d1", "MKTG 3303 · Quiz 3", dueOffsetHours = 9),
            ),
        )
        assertEquals(listOf("d1", "r1", "l1"), ranked.map { it.id })
    }

    @Test
    fun `overdue comes first, and the most overdue leads it`() {
        // The thing ignored longest is the one most likely forgotten rather than deferred.
        val ranked = rankOutstanding(
            listOf(
                deadline("recent", "COSC 4320 · Assignment", dueOffsetHours = -2),
                deadline("soon", "MATH 3391 · Quiz", dueOffsetHours = 3),
                deadline("ancient", "MKTG 3303 · WK02 Bio Video Presentation", dueOffsetHours = -48),
            ),
        )
        assertEquals(listOf("ancient", "recent", "soon"), ranked.map { it.id })
    }

    @Test
    fun `within dated, sooner first`() {
        val ranked = rankOutstanding(
            listOf(
                deadline("later", "A · later", dueOffsetHours = 30),
                deadline("sooner", "B · sooner", dueOffsetHours = 4),
            ),
        )
        assertEquals(listOf("sooner", "later"), ranked.map { it.id })
    }

    @Test
    fun `a ticked line is listed, not dropped - a finished day must read as finished`() {
        // Dropping it would make a completed bio plan indistinguishable from an empty one, which is
        // CLAUDE.md section 1's empty-versus-unreadable distinction pointed at a day's work.
        val ranked = rankOutstanding(listOf(line("l1", "walk", done = true)))
        assertEquals(1, ranked.size)
        assertTrue(ranked.single().done)
    }

    @Test
    fun `a checklist line is never overdue - it resets tonight, it cannot be late`() {
        // Conflating the two would put a bio line it is still 9am to do in the same bucket as a
        // coursework deadline that passed.
        val l = line("l1", "wall sits", done = false)
        assertTrue(!l.overdue)
        val ranked = rankOutstanding(listOf(l, deadline("d1", "X · late", dueOffsetHours = -5)))
        assertEquals(listOf("d1", "l1"), ranked.map { it.id })
    }

    @Test
    fun `equal items keep the order their store handed them in`() {
        // No hidden tiebreak. Two undated errands have nothing to choose between them, and inventing
        // one would be a judgement the sort has no basis for.
        val ranked = rankOutstanding(listOf(reminder("a", "first"), reminder("b", "second")))
        assertEquals(listOf("a", "b"), ranked.map { it.id })
    }

    @Test
    fun `the sentence states the count and names how many are late`() {
        val items = listOf(
            deadline("d1", "A · late", dueOffsetHours = -3),
            deadline("d2", "B · soon", dueOffsetHours = 6),
            line("l1", "walk", done = true),
        )
        // The ticked line is excluded from the count: it is not outstanding.
        assertEquals("2 outstanding, 1 past its date.", outstandingSentence(items))
    }

    @Test
    fun `nothing outstanding is a sentence, never a zero`() {
        // "0 outstanding" invites the reader to wonder what it was counting.
        assertEquals("Nothing outstanding.", outstandingSentence(emptyList()))
        assertEquals("Nothing outstanding.", outstandingSentence(listOf(line("l1", "walk", true))))
    }

    @Test
    fun `no late ones means the sentence does not mention lateness at all`() {
        val items = listOf(deadline("d1", "A · soon", dueOffsetHours = 6))
        assertEquals("1 outstanding.", outstandingSentence(items))
    }

    @Test
    fun `all three stores rank together rather than in blocks`() {
        // The failure this whole module exists to prevent: three lists stapled end to end, so an
        // errand with no date sits above a deadline that passed purely because of which table it
        // came from.
        val ranked = rankOutstanding(
            listOf(
                reminder("r1", "call the dealer"),
                line("l1", "10 min stretch", done = false),
                deadline("d1", "MKTG 3303 · WK02", dueOffsetHours = -20),
                deadline("d2", "MATH 3391 · Chapter 2 Quiz", dueOffsetHours = 10),
            ),
        )
        assertEquals(listOf("d1", "d2", "r1", "l1"), ranked.map { it.id })
    }
}

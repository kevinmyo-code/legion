package com.kevin.legion.checklists

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure, no Robolectric - [matchTickHistory]/[normalizeForTickMatch] take rows someone else fetched
 * (`TickHistoryControllerTest` covers the fetch, through Room). Ticket 04 of web-calendar-and-lists;
 * the matching rule itself is ticket 03's, pinned here so a later "improvement" has to argue with a
 * red test.
 */
class TickHistoryTest {

    private fun match(itemText: String, checklist: String = "Groceries", tickedAt: Long = 0L, itemId: Long = 1L) =
        TickMatch(itemId = itemId, itemText = itemText, checklistName = checklist, tickedAt = tickedAt)

    // ------------------------------------------------------------------ case and whitespace variants match

    @Test
    fun `case is ignored`() {
        val result = matchTickHistory("toothpaste", listOf(match("TOOTHPASTE")))
        assertEquals(1, result.size)
    }

    @Test
    fun `surrounding whitespace is ignored`() {
        val result = matchTickHistory("toothpaste", listOf(match("  toothpaste  ")))
        assertEquals(1, result.size)
    }

    @Test
    fun `internal whitespace collapses`() {
        val result = matchTickHistory("paper towels", listOf(match("paper   towels")))
        assertEquals(1, result.size)
    }

    @Test
    fun `the query itself is normalised the same way`() {
        val result = matchTickHistory("  Toothpaste  ", listOf(match("toothpaste")))
        assertEquals(1, result.size)
    }

    // ------------------------------------------------------------------ 03's deliberate narrowness

    @Test
    fun `a longer line does not match a shorter query`() {
        // Ticket 03's own worked example, verbatim: "Colgate toothpaste" is not "toothpaste". A
        // near-match that is WRONG is worse than a miss.
        val result = matchTickHistory("toothpaste", listOf(match("Colgate toothpaste")))
        assertTrue(result.isEmpty())
    }

    @Test
    fun `a shorter query does not match a longer line the other direction either`() {
        val result = matchTickHistory("Colgate toothpaste", listOf(match("toothpaste")))
        assertTrue(result.isEmpty())
    }

    @Test
    fun `no stemming - plural does not match singular`() {
        val result = matchTickHistory("towel", listOf(match("towels")))
        assertTrue(result.isEmpty())
    }

    // ------------------------------------------------------------------ a tombstoned checklist's tick is returned

    @Test
    fun `a match from a soft-deleted checklist is still returned`() {
        // matchTickHistory itself has no notion of "deleted" - TickHistoryController is what reads
        // through tombstones by including them in the candidate list at all. This pins that once a
        // row IS a candidate, nothing here filters it back out.
        val result = matchTickHistory("toothpaste", listOf(match("toothpaste", checklist = "Groceries (deleted)")))
        assertEquals(1, result.size)
        assertEquals("Groceries (deleted)", result.first().checklistName)
    }

    // ------------------------------------------------------------------ newest first

    @Test
    fun `results come back newest tick first`() {
        val old = match("toothpaste", tickedAt = 1_000L, itemId = 1L)
        val mid = match("toothpaste", tickedAt = 5_000L, itemId = 2L)
        val new = match("toothpaste", tickedAt = 9_000L, itemId = 3L)

        val result = matchTickHistory("toothpaste", listOf(mid, old, new))

        assertEquals(listOf(new, mid, old), result)
    }

    @Test
    fun `a non-matching item is excluded even when it ticked more recently`() {
        val toothpaste = match("toothpaste", tickedAt = 1_000L)
        val shampoo = match("shampoo", tickedAt = 9_000L)

        val result = matchTickHistory("toothpaste", listOf(shampoo, toothpaste))

        assertEquals(listOf(toothpaste), result)
    }

    @Test
    fun `no candidates at all is an empty list, not an error`() {
        assertTrue(matchTickHistory("toothpaste", emptyList()).isEmpty())
    }
}

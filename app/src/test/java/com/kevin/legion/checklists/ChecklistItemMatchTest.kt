package com.kevin.legion.checklists

import com.kevin.legion.data.local.ChecklistItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure test of [matchChecklistItem]'s three tiers - no Context, no Room, same posture as
 * `notes/NotesLogicTest`/`grocery/GroceryLogicTest` beside their own matchers. */
class ChecklistItemMatchTest {

    private fun item(id: Long, text: String) = ChecklistItem(id = id, checklistId = 1, text = text)

    @Test
    fun `exact case-insensitive match resolves`() {
        val items = listOf(item(1, "Walk"), item(2, "Squats"))
        val match = matchChecklistItem("walk", items) as ChecklistItemMatch.Resolved
        assertEquals(1L, match.item.id)
    }

    @Test
    fun `substring match resolves either direction`() {
        val items = listOf(item(1, "3 sets goblet squats"))
        val match = matchChecklistItem("squats", items) as ChecklistItemMatch.Resolved
        assertEquals(1L, match.item.id)
    }

    @Test
    fun `word overlap tie is ambiguous, never guessed`() {
        val items = listOf(item(1, "morning squats"), item(2, "morning lunges"))
        val match = matchChecklistItem("morning", items) as ChecklistItemMatch.Ambiguous
        assertEquals(2, match.candidates.size)
    }

    @Test
    fun `no overlap is NoMatch`() {
        val items = listOf(item(1, "walk"))
        assertTrue(matchChecklistItem("bananas", items) is ChecklistItemMatch.NoMatch)
    }

    @Test
    fun `blank query is NoMatch`() {
        val items = listOf(item(1, "walk"))
        assertTrue(matchChecklistItem("  ", items) is ChecklistItemMatch.NoMatch)
    }

    @Test
    fun `empty item list is NoMatch`() {
        assertTrue(matchChecklistItem("walk", emptyList()) is ChecklistItemMatch.NoMatch)
    }
}

package com.kevin.legion.checklists

import com.kevin.legion.data.local.Checklist
import com.kevin.legion.data.local.ChecklistItem
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Plain JUnit against [checklistSectionLabel]/[historyGroupedByDayDescending] - neither needs
 * Android/Room, matching [com.kevin.legion.goals.GoalProgressTest]'s own "no Robolectric" posture
 * for a pure function. The heavier controller-level behaviour (trap 1/trap 2, the tick/untick
 * round trip) is [ChecklistControllerTest]'s job; this file only exercises the two UI-facing
 * helpers `ui/CalendarScreen.kt` and `ui/checklists/ChecklistsScreen.kt` call into.
 */
class ChecklistDayViewLogicTest {

    private fun item(id: Long, text: String, sortOrder: Int = 0) =
        ChecklistItem(id = id, checklistId = 1, text = text, sortOrder = sortOrder)

    // ---- checklistSectionLabel ------------------------------------------------------------------

    @Test
    fun `section label reports done over total, never a percentage or streak`() {
        val checklist = Checklist(id = 1, name = "bio")
        val items = listOf(
            ChecklistController.ItemState(item(1, "squats"), ticked = true, tickedAt = 1L),
            ChecklistController.ItemState(item(2, "sleep 8h"), ticked = false, tickedAt = null),
            ChecklistController.ItemState(item(3, "protein"), ticked = true, tickedAt = 2L),
        )
        assertEquals("bio (2/3)", checklistSectionLabel(checklist, items))
    }

    @Test
    fun `an empty items list still produces an honest zero-over-zero label`() {
        val checklist = Checklist(id = 1, name = "bio")
        assertEquals("bio (0/0)", checklistSectionLabel(checklist, emptyList()))
    }

    @Test
    fun `nothing ticked yet reads zero done, never as a failure state`() {
        val checklist = Checklist(id = 1, name = "morning routine")
        val items = listOf(ChecklistController.ItemState(item(1, "stretch"), ticked = false, tickedAt = null))
        assertEquals("morning routine (0/1)", checklistSectionLabel(checklist, items))
    }

    // ---- historyGroupedByDayDescending -----------------------------------------------------------

    @Test
    fun `history groups lines by day, most recent day first`() {
        val squats = item(1, "squats")
        val sleep = item(2, "sleep 8h")
        val lines = listOf(
            ChecklistController.ChecklistHistoryLine(day = 100, item = squats, ticked = true, tickedAt = 1L),
            ChecklistController.ChecklistHistoryLine(day = 102, item = squats, ticked = true, tickedAt = 3L),
            ChecklistController.ChecklistHistoryLine(day = 101, item = sleep, ticked = true, tickedAt = 2L),
        )

        val grouped = historyGroupedByDayDescending(lines)

        assertEquals(listOf(102, 101, 100), grouped.map { it.first })
        assertEquals(listOf(squats), grouped.first { it.first == 102 }.second.map { it.item })
    }

    @Test
    fun `a soft-deleted item's line still groups into its own day, resolved text and all`() {
        // Mirrors trap 2 (ChecklistController's own doc comment): the ChecklistItem this line
        // carries may already be soft-deleted by the time history is read; this function has no
        // opinion on that at all and must not filter on it - it only groups by day.
        val droppedItem = item(9, "an item since removed from the checklist")
        val lines = listOf(ChecklistController.ChecklistHistoryLine(day = 50, item = droppedItem, ticked = true, tickedAt = 5L))

        val grouped = historyGroupedByDayDescending(lines)

        assertEquals(1, grouped.size)
        assertEquals("an item since removed from the checklist", grouped.single().second.single().item.text)
    }

    @Test
    fun `an empty line list groups to an empty result, not a crash`() {
        assertEquals(emptyList<Pair<Int, List<ChecklistController.ChecklistHistoryLine>>>(), historyGroupedByDayDescending(emptyList()))
    }
}

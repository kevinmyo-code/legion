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

    // ---- checklistSectionLabel, loadFailed --------------------------------------------------------

    @Test
    fun `a failed read reports itself in words, never a zero-over-zero count`() {
        val checklist = Checklist(id = 1, name = "bio")
        assertEquals(
            "bio - couldn't load today's items",
            checklistSectionLabel(checklist, emptyList(), loadFailed = true),
        )
    }

    // ---- measureValueDisplay -----------------------------------------------------------------------

    private fun measuredItem(unit: String?, target: Double?, direction: String?) =
        ChecklistItem(id = 1, checklistId = 1, text = "walk 10k steps", measureUnit = unit, measureTarget = target, measureDirection = direction)

    @Test
    fun `a measured value with a target shows both numbers and the unit, comma-grouped`() {
        val item = measuredItem("steps", 10000.0, "AT_LEAST")
        assertEquals("8,400 / 10,000 steps", measureValueDisplay(item, 8400.0))
    }

    @Test
    fun `a measured value with no target just records the number and unit`() {
        val item = measuredItem("kg", null, null)
        assertEquals("22.5 kg", measureValueDisplay(item, 22.5))
    }

    @Test
    fun `a whole number never carries a trailing decimal point`() {
        val item = measuredItem("min", 30.0, "AT_MOST")
        assertEquals("30 / 30 min", measureValueDisplay(item, 30.0))
    }

    // ---- measurePromptLabel --------------------------------------------------------------------------

    @Test
    fun `the prompt names the direction and target when both are set`() {
        val item = measuredItem("steps", 10000.0, "AT_LEAST")
        assertEquals("target: at least 10,000 steps", measurePromptLabel(item))
    }

    @Test
    fun `at-most reads as at most, not at least`() {
        val item = measuredItem("min", 20.0, "AT_MOST")
        assertEquals("target: at most 20 min", measurePromptLabel(item))
    }

    @Test
    fun `a unit with no target just names the unit`() {
        val item = measuredItem("kg", null, null)
        assertEquals("in kg", measurePromptLabel(item))
    }

    @Test
    fun `a plain binary item has no prompt at all`() {
        val item = ChecklistItem(id = 1, checklistId = 1, text = "squats")
        assertEquals(null, measurePromptLabel(item))
    }

    // ---- measureTargetResult -----------------------------------------------------------------------

    @Test
    fun `at-least meets the target at or above it`() {
        val item = measuredItem("steps", 10000.0, "AT_LEAST")
        assertEquals(MeasureTargetResult.MET, measureTargetResult(item, 10000.0))
        assertEquals(MeasureTargetResult.MET, measureTargetResult(item, 12000.0))
        assertEquals(MeasureTargetResult.MISSED, measureTargetResult(item, 8400.0))
    }

    @Test
    fun `at-most meets the target at or below it`() {
        val item = measuredItem("min", 20.0, "AT_MOST")
        assertEquals(MeasureTargetResult.MET, measureTargetResult(item, 20.0))
        assertEquals(MeasureTargetResult.MET, measureTargetResult(item, 15.0))
        assertEquals(MeasureTargetResult.MISSED, measureTargetResult(item, 25.0))
    }

    @Test
    fun `no target means no result to report`() {
        val item = measuredItem("kg", null, null)
        assertEquals(null, measureTargetResult(item, 22.5))
    }

    // ---- checklistScheduleLabel --------------------------------------------------------------------

    @Test
    fun `no schedule at all reads as no schedule`() {
        assertEquals("No schedule", checklistScheduleLabel(Checklist(id = 1, name = "todo", scheduleKind = null)))
    }

    @Test
    fun `daily every one day reads as plain Daily`() {
        val checklist = Checklist(id = 1, name = "bio", scheduleKind = "DAILY", scheduleEvery = 1)
        assertEquals("Daily", checklistScheduleLabel(checklist))
    }

    @Test
    fun `daily every N days names the interval`() {
        val checklist = Checklist(id = 1, name = "bio", scheduleKind = "DAILY", scheduleEvery = 3)
        assertEquals("Every 3 days", checklistScheduleLabel(checklist))
    }

    @Test
    fun `weekly on chosen days lists them Monday-first, abbreviated`() {
        val checklist = Checklist(
            id = 1,
            name = "gym",
            scheduleKind = "WEEKLY",
            scheduleEvery = 1,
            scheduleDaysOfWeek = "FRIDAY,MONDAY,WEDNESDAY",
        )
        assertEquals("Mon Wed Fri", checklistScheduleLabel(checklist))
    }

    @Test
    fun `weekly every N weeks appends the interval after the days`() {
        val checklist = Checklist(
            id = 1,
            name = "gym",
            scheduleKind = "WEEKLY",
            scheduleEvery = 2,
            scheduleDaysOfWeek = "MONDAY",
        )
        assertEquals("Mon, every 2 weeks", checklistScheduleLabel(checklist))
    }

    @Test
    fun `a malformed weekly schedule with no parseable days reads as no schedule, not a crash`() {
        val checklist = Checklist(id = 1, name = "gym", scheduleKind = "WEEKLY", scheduleEvery = 1, scheduleDaysOfWeek = "")
        assertEquals("No schedule", checklistScheduleLabel(checklist))
    }
}

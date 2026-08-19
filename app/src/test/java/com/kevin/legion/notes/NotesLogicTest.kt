package com.kevin.legion.notes

import com.kevin.legion.data.local.ItemList
import com.kevin.legion.data.local.ListItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek

/**
 * Pure logic behind `manage_list`/`manage_item`/`read_list` (`service/LiveToolbox.kt`) and
 * `notes/NotesController.kt` - no `Context`, no Room, same posture as `ledger/LedgerPendingLogTest`.
 */
class NotesLogicTest {

    private fun item(id: Long, text: String, repeatKind: String? = null) =
        ListItem(id = id, listId = 1, text = text, repeatKind = repeatKind)

    // --------------------------------------------------------------------------------- matchItem

    @Test
    fun `exact case-insensitive match resolves`() {
        val items = listOf(item(1, "Tent"), item(2, "Batteries"))
        val result = matchItem("tent", items)
        assertEquals(ItemMatch.Resolved(items[0]), result)
    }

    @Test
    fun `substring match resolves when the item text contains the query`() {
        val items = listOf(item(1, "AA batteries for the headlamp"), item(2, "Tent"))
        val result = matchItem("batteries", items)
        assertEquals(ItemMatch.Resolved(items[0]), result)
    }

    @Test
    fun `substring match resolves when the query contains the item text`() {
        val items = listOf(item(1, "tent"), item(2, "stove"))
        val result = matchItem("scratch the tent off", items)
        assertEquals(ItemMatch.Resolved(items[0]), result)
    }

    @Test
    fun `word overlap is the last resort and picks the best-scoring item`() {
        val items = listOf(item(1, "propane canister"), item(2, "coffee filter"))
        val result = matchItem("propane for the stove", items)
        assertEquals(ItemMatch.Resolved(items[0]), result)
    }

    @Test
    fun `two items matching equally is ambiguous, never a silent guess`() {
        val items = listOf(item(1, "AA batteries"), item(2, "AAA batteries"))
        val result = matchItem("batteries", items)
        assertTrue(result is ItemMatch.Ambiguous)
        assertEquals(2, (result as ItemMatch.Ambiguous).candidates.size)
    }

    @Test
    fun `no match at all refuses rather than guessing`() {
        val items = listOf(item(1, "Tent"))
        assertEquals(ItemMatch.NoMatch, matchItem("sleeping bag", items))
    }

    @Test
    fun `an empty item list is always NoMatch`() {
        assertEquals(ItemMatch.NoMatch, matchItem("anything", emptyList()))
    }

    @Test
    fun `a blank query is always NoMatch`() {
        assertEquals(ItemMatch.NoMatch, matchItem("   ", listOf(item(1, "Tent"))))
    }

    @Test
    fun `matching never depends on position - a later item can match a query the first item doesn't`() {
        val items = listOf(item(1, "Tent"), item(2, "Propane"), item(3, "Stove"))
        val result = matchItem("stove", items)
        assertEquals(ItemMatch.Resolved(items[2]), result)
    }

    // --------------------------------------------------------------------------------- matchList

    private fun list(id: Long, name: String, archived: Boolean = false) = ItemList(id = id, name = name, archived = archived)

    // --------------------------------------------------------------------------------- copiedItem

    // -------------------------------------------------------------------------- repeat columns

    @Test
    fun `Daily rule round-trips through storage columns`() {
        val cols = repeatColumnsFor(RepeatRule.Daily(3), RepeatEnd.Never)
        val stored = ListItem(id = 1, listId = 1, text = "x", repeatKind = cols.repeatKind, repeatEvery = cols.repeatEvery, repeatEndKind = cols.repeatEndKind)
        assertEquals(RepeatRule.Daily(3), ruleFromItem(stored))
        assertEquals(RepeatEnd.Never, endFromItem(stored))
    }

    @Test
    fun `Weekly rule round-trips including the day set`() {
        val rule = RepeatRule.Weekly(2, setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY))
        val cols = repeatColumnsFor(rule, RepeatEnd.AfterCount(10))
        val stored = ListItem(
            id = 1, listId = 1, text = "x", repeatKind = cols.repeatKind, repeatEvery = cols.repeatEvery,
            repeatDaysOfWeek = cols.repeatDaysOfWeek, repeatEndKind = cols.repeatEndKind, repeatEndCount = cols.repeatEndCount,
        )
        assertEquals(rule, ruleFromItem(stored))
        assertEquals(RepeatEnd.AfterCount(10), endFromItem(stored))
    }

    @Test
    fun `Yearly rule round-trips`() {
        val rule = RepeatRule.Yearly(2, 29)
        val cols = repeatColumnsFor(rule, RepeatEnd.OnDate(555L))
        val stored = ListItem(
            id = 1, listId = 1, text = "x", repeatKind = cols.repeatKind, repeatMonth = cols.repeatMonth,
            repeatDay = cols.repeatDay, repeatEndKind = cols.repeatEndKind, repeatEndDate = cols.repeatEndDate,
        )
        assertEquals(rule, ruleFromItem(stored))
        assertEquals(RepeatEnd.OnDate(555L), endFromItem(stored))
    }

    @Test
    fun `a null rule clears every repeat column`() {
        val cols = repeatColumnsFor(null, RepeatEnd.Never)
        assertNull(cols.repeatKind)
        assertNull(cols.repeatEvery)
        assertNull(cols.repeatDaysOfWeek)
        assertNull(cols.repeatDay)
        assertNull(cols.repeatMonth)
    }

    @Test
    fun `a non-recurring item (null repeatKind) reads back as no rule and Never end`() {
        val stored = ListItem(id = 1, listId = 1, text = "x")
        assertNull(ruleFromItem(stored))
        assertEquals(RepeatEnd.Never, endFromItem(stored))
    }

    // -------------------------------------------------------------------------- weekday parsing

    @Test
    fun `parseWeekdays accepts abbreviations`() {
        assertEquals(setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY), parseWeekdays("MON,WED,FRI"))
    }

    @Test
    fun `parseWeekdays accepts full names case-insensitively`() {
        assertEquals(setOf(DayOfWeek.MONDAY), parseWeekdays("monday"))
    }

    @Test
    fun `parseWeekdays rejects an unrecognized token`() {
        assertNull(parseWeekdays("MON,FROOBAR"))
    }

    @Test
    fun `parseWeekdays of a blank string is an empty set, not null`() {
        assertEquals(emptySet<DayOfWeek>(), parseWeekdays(""))
    }

    @Test
    fun `formatWeekdays is the inverse of parseWeekdays, always full names in ISO order`() {
        val days = setOf(DayOfWeek.FRIDAY, DayOfWeek.MONDAY)
        assertEquals("MONDAY,FRIDAY", formatWeekdays(days))
        assertEquals(days, parseWeekdays(formatWeekdays(days)))
    }
}

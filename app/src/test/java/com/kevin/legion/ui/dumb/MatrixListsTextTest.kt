package com.kevin.legion.ui.dumb

import com.kevin.legion.checklists.ChecklistController
import com.kevin.legion.data.local.Checklist
import com.kevin.legion.data.local.ChecklistItem
import com.kevin.legion.ui.checklists.ListCardUi
import com.kevin.legion.ui.checklists.ListDetailState
import com.kevin.legion.ui.checklists.ListItemUi
import com.kevin.legion.ui.checklists.ListProgress
import com.kevin.legion.ui.checklists.ListsPageState
import com.kevin.legion.ui.checklists.listVisual
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The words on the Matrix lists screens: loading, empty, unreadable (apart from empty), write error. */
class MatrixListsTextTest {
    private fun card(name: String, progress: ListProgress, id: Long = 1, routine: Boolean = false) = ListCardUi(
        Checklist(id = id, name = name, scheduleKind = if (routine) "DAILY" else null),
        listVisual(name, id),
        progress,
    )

    private fun item(text: String, ticked: Boolean, queued: Boolean = false, id: Long = 1) =
        ListItemUi(ChecklistItem(id = id, checklistId = 1, text = text), ticked, null, if (ticked) 5 else null, queued)

    private val checklist = Checklist(id = 1, name = "Chores")

    @Test
    fun `loading says it is reading, not that there are no lists`() {
        assertEquals(MatrixListsText.LOADING, MatrixListsText.pageNote(ListsPageState(loading = true)))
    }

    @Test
    fun `an empty page says so and offers the new-list field, a page with lists says nothing`() {
        assertEquals(MatrixListsText.NO_LISTS, MatrixListsText.pageNote(ListsPageState(loading = false)))
        val withList = ListsPageState(loading = false, plainLists = listOf(card("A", ListProgress(0.5f, "1/2"))))
        assertNull(MatrixListsText.pageNote(withList))
    }

    @Test
    fun `a list whose items could not be read is alert toned and says Couldn't load, never Empty`() {
        val row = MatrixListsText.cardRow(card("Broken", ListProgress(null, "Couldn't load")))
        assertEquals(MatrixTone.ALERT, row.tone)
        assertEquals("Couldn't load", row.detail)
        val empty = MatrixListsText.cardRow(card("Blank", ListProgress(0f, "Empty")))
        assertEquals(MatrixTone.HEADING, empty.tone)
        assertEquals("Empty", empty.detail)
    }

    @Test
    fun `routines and plain lists both appear and archived only when shown`() {
        val page = ListsPageState(
            loading = false,
            routines = listOf(card("Morning", ListProgress(0f, "0/3 today"), id = 1, routine = true)),
            plainLists = listOf(card("Groceries", ListProgress(0f, "Empty"), id = 2)),
            archivedLists = listOf(card("Old", ListProgress(1f, "2/2"), id = 3)),
        )
        assertEquals(listOf("Morning", "Groceries"), MatrixListsText.pageRows(page).map { it.text })
        assertTrue(MatrixListsText.archivedRows(page).isEmpty())
        assertEquals("Show archived lists (1)", MatrixListsText.archivedToggle(page))
        val shown = page.copy(showArchived = true)
        assertEquals(listOf("Old"), MatrixListsText.archivedRows(shown).map { it.text })
        assertEquals("Hide archived lists (1)", MatrixListsText.archivedToggle(shown))
        assertNull(MatrixListsText.archivedToggle(ListsPageState(loading = false)))
    }

    @Test
    fun `the household Groceries list not yet synced is said, not left absent`() {
        val page = ListsPageState(loading = false, groceriesPending = true)
        assertEquals(ChecklistController.GROCERIES_NOT_SYNCED, MatrixListsText.groceriesNote(page))
        assertNull(MatrixListsText.pageNote(page))
    }

    @Test
    fun `the detail separates loading, unreadable and empty`() {
        assertEquals(MatrixListsText.DETAIL_LOADING, MatrixListsText.detailNote(ListDetailState()))
        val failed = ListDetailState(checklist = checklist, loading = false, loadFailed = true)
        assertEquals(MatrixListsText.DETAIL_UNREADABLE, MatrixListsText.detailNote(failed))
        val empty = ListDetailState(checklist = checklist, loading = false)
        assertEquals(MatrixListsText.DETAIL_EMPTY, MatrixListsText.detailNote(empty))
        val some = empty.copy(unticked = listOf(item("Milk", false)))
        assertNull(MatrixListsText.detailNote(some))
    }

    @Test
    fun `open and ticked rows are bracketed in text and an unsynced tick says so in words`() {
        assertEquals("[ ] Milk", MatrixListsText.openRow(item("Milk", false)).text)
        val ticked = MatrixListsText.tickedRow(item("Eggs", true, queued = true))
        assertEquals("[x] Eggs", ticked.text)
        assertEquals("Not synced yet", ticked.detail)
        assertNull(MatrixListsText.tickedRow(item("Eggs", true)).detail)
    }

    @Test
    fun `a routine says it is not scheduled today, a plain list has no schedule line`() {
        val routine = Checklist(id = 2, name = "Gym", scheduleKind = "DAILY", scheduleEvery = 1)
        val off = ListDetailState(checklist = routine, loading = false, appliesToday = false)
        assertEquals("Not scheduled today.", MatrixListsText.scheduleNote(off))
        val on = off.copy(appliesToday = true, scheduleLabel = "Daily")
        assertEquals("Daily. Ticks count for today.", MatrixListsText.scheduleNote(on))
        assertNull(MatrixListsText.scheduleNote(ListDetailState(checklist = checklist, loading = false)))
    }
}

package com.kevin.legion.ui.checklists

import com.kevin.legion.checklists.ChecklistController
import com.kevin.legion.data.local.ChecklistItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Home-launcher ticket 04's own card cases - plain JVM, no Robolectric: [listProgress] takes no
 * [android.content.Context]. */
class ListProgressTest {

    private fun itemState(ticked: Boolean) =
        ChecklistController.ItemState(item = ChecklistItem(checklistId = 1, text = "x"), ticked = ticked, tickedAt = null)

    @Test
    fun `a routine applying today shows done over total, with the word today`() {
        val items = listOf(itemState(true), itemState(true), itemState(false))
        val result = listProgress(isRoutine = true, appliesToday = true, items = items, loadFailed = false)
        assertEquals(2f / 3f, result.fraction!!, 0.0001f)
        assertEquals("2/3 today", result.label)
    }

    @Test
    fun `a routine not applying today shows Not today with an empty ring`() {
        val result = listProgress(isRoutine = true, appliesToday = false, items = emptyList(), loadFailed = false)
        assertEquals(0f, result.fraction)
        assertEquals("Not today", result.label)
    }

    @Test
    fun `a plain list shows done over total, no today wording`() {
        val items = listOf(itemState(true), itemState(false))
        val result = listProgress(isRoutine = false, appliesToday = true, items = items, loadFailed = false)
        assertEquals(0.5f, result.fraction)
        assertEquals("1/2", result.label)
    }

    @Test
    fun `no items shows Empty with an empty ring`() {
        val result = listProgress(isRoutine = false, appliesToday = true, items = emptyList(), loadFailed = false)
        assertEquals(0f, result.fraction)
        assertEquals("Empty", result.label)
    }

    @Test
    fun `a failed read shows Couldn't load with no ring at all, never a quiet 0-0`() {
        val result = listProgress(isRoutine = false, appliesToday = true, items = emptyList(), loadFailed = true)
        assertNull(result.fraction)
        assertEquals("Couldn't load", result.label)
    }

    @Test
    fun `loadFailed wins even over a not-today routine`() {
        val result = listProgress(isRoutine = true, appliesToday = false, items = emptyList(), loadFailed = true)
        assertNull(result.fraction)
        assertEquals("Couldn't load", result.label)
    }
}

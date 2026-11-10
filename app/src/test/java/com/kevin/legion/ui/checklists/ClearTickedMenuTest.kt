package com.kevin.legion.ui.checklists

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.kevin.legion.data.local.Checklist
import com.kevin.legion.data.local.ChecklistItem
import com.kevin.legion.ui.theme.soft.SoftTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The open list's "Clear ticked items" overflow entry and its Undo snackbar (Kevin, 2026-11-10:
 * a list with dozens of ticked lines and no way to drop them but one at a time). Made-up lines.
 */
@RunWith(RobolectricTestRunner::class)
class ClearTickedMenuTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val plain = Checklist(id = 3, name = "Picnic")
    private val routine = Checklist(id = 4, name = "Evenings", scheduleKind = "DAILY", scheduleEvery = 1)

    private fun row(id: Long, text: String, ticked: Boolean) =
        ListItemUi(
            ChecklistItem(id = id, checklistId = 3, text = text, sortOrder = id.toInt()),
            ticked, null, if (ticked) 20_000 else null, false,
        )

    @Test
    fun `the entry is hidden on a routine, disabled with nothing ticked, enabled otherwise`() {
        assertEquals(ClearTickedMenu.HIDDEN, clearTickedMenu(routine, tickedCount = 5))
        assertEquals(ClearTickedMenu.HIDDEN, clearTickedMenu(null, tickedCount = 5))
        assertEquals(ClearTickedMenu.DISABLED, clearTickedMenu(plain, tickedCount = 0))
        assertEquals(ClearTickedMenu.ENABLED, clearTickedMenu(plain, tickedCount = 2))
    }

    @Test
    fun `the snackbar line counts the lines, and pending lines are hidden`() {
        assertEquals("Cleared 1 ticked item", clearedTickedMessage(1))
        assertEquals("Cleared 74 ticked items", clearedTickedMessage(74))
        val rows = listOf(row(1, "blanket", true), row(2, "lemonade", false))
        assertEquals(listOf(2L), withoutPendingClear(rows, listOf(1L)).map { it.item.id })
        assertEquals(rows, withoutPendingClear(rows, emptyList()))
    }

    @Test
    fun `tapping the enabled entry asks the screen to clear`() {
        var cleared = 0
        val state = ListDetailState(
            checklist = plain,
            loading = false,
            unticked = listOf(row(2, "lemonade", false)),
            ticked = listOf(row(1, "blanket", true)),
        )
        val callbacks = ListContentPreviewCallbacks.detail().copy(onClearTicked = { cleared++ })
        compose.setContent { SoftTheme { ListDetailContent(state, callbacks) } }
        compose.onNodeWithContentDescription("List options").performClick()
        compose.onNodeWithText("Clear ticked items").assertIsEnabled().performClick()
        compose.waitForIdle()
        assertEquals(1, cleared)
    }

    @Test
    fun `with nothing ticked the entry is there but disabled`() {
        val state = ListDetailState(checklist = plain, loading = false, unticked = listOf(row(2, "lemonade", false)))
        compose.setContent { SoftTheme { ListDetailContent(state, ListContentPreviewCallbacks.detail()) } }
        compose.onNodeWithContentDescription("List options").performClick()
        compose.onNodeWithText("Clear ticked items").assertIsNotEnabled()
    }

    @Test
    fun `a pending clear shows the Undo snackbar and Undo calls back`() {
        var undone = 0
        val state = ListDetailState(
            checklist = plain,
            loading = false,
            unticked = listOf(row(2, "lemonade", false)),
            pendingClear = listOf(1L, 5L),
        )
        val callbacks = ListContentPreviewCallbacks.detail().copy(onUndoClearTicked = { undone++ })
        compose.setContent { SoftTheme { ListDetailContent(state, callbacks) } }
        compose.waitForIdle()
        compose.onNodeWithText("Cleared 2 ticked items").assertExists()
        compose.onNodeWithText("Undo").performClick()
        compose.waitForIdle()
        assertEquals(1, undone)
    }
}

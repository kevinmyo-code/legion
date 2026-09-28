package com.kevin.legion.screenshot

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.kevin.legion.data.local.Checklist
import com.kevin.legion.data.local.ChecklistItem
import com.kevin.legion.data.local.MeasureDirection
import com.kevin.legion.ui.checklists.ListCardUi
import com.kevin.legion.ui.checklists.ListContentPreviewCallbacks
import com.kevin.legion.ui.checklists.ListDetailContent
import com.kevin.legion.ui.checklists.ListDetailState
import com.kevin.legion.ui.checklists.ListItemUi
import com.kevin.legion.ui.checklists.ListProgress
import com.kevin.legion.ui.checklists.ListsContent
import com.kevin.legion.ui.checklists.ListsPageState
import com.kevin.legion.ui.checklists.listVisual
import com.kevin.legion.ui.theme.soft.SoftTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Home-launcher ticket 04's own verification step 3: "the page (routines + lists + new card; a
 * Failed card; archived shown); a plain list with a ticked group and a not-synced row; a routine
 * with a measured item showing a refusal; the all-ticked delete offer; an empty list."
 *
 * **`w384dp-h636dp`, not [ScreenshotDeviceConfig.QUALIFIERS]** - the ticket names this exact size
 * ("Roborazzi at 384 x 636dp"), narrower than that constant's own full-device `h832dp` (every
 * other screenshot test in this module renders the full A25 canvas; this ticket asks for a
 * shorter one, so this file keeps its own qualifier rather than repointing the shared constant
 * every other test still depends on).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w384dp-h636dp")
class ListsScreenshotTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private fun checklist(id: Long, name: String, scheduleKind: String? = null) =
        Checklist(id = id, name = name, scheduleKind = scheduleKind, scheduleEvery = if (scheduleKind != null) 1 else null)

    private fun card(id: Long, name: String, scheduleKind: String? = null, fraction: Float?, label: String): ListCardUi =
        ListCardUi(
            checklist = checklist(id, name, scheduleKind),
            visual = listVisual(name, id),
            progress = ListProgress(fraction, label),
        )

    @Test
    fun `the page - routines, lists, a Failed card, the new card, archived shown`() {
        val routines = listOf(
            card(1, "Bio", scheduleKind = "DAILY", fraction = 2f / 6f, label = "2/6 today"),
            card(2, "Morning", scheduleKind = "DAILY", fraction = 0f, label = "Not today"),
        )
        val plain = listOf(
            card(3, "Groceries", fraction = 6f / 8f, label = "6/8"),
            card(4, "Packing", fraction = null, label = "Couldn't load"),
            card(5, "Empty list", fraction = 0f, label = "Empty"),
        )
        val archived = listOf(card(6, "Old todo", fraction = 1f, label = "3/3"))
        capturePage(
            "lists-page-full.png",
            ListsPageState(loading = false, routines = routines, plainLists = plain, archivedLists = archived, showArchived = true),
        )
    }

    @Test
    fun `a plain list with a ticked group and a not-synced row`() {
        val checklist = checklist(10, "Groceries")
        val unticked = listOf(
            ListItemUi(ChecklistItem(id = 1, checklistId = 10, text = "Toothpaste", sortOrder = 0), ticked = false, value = null, tickDay = null, queued = false),
            ListItemUi(ChecklistItem(id = 2, checklistId = 10, text = "Eggs, dozen", sortOrder = 1), ticked = false, value = null, tickDay = null, queued = false),
        )
        val ticked = listOf(
            ListItemUi(ChecklistItem(id = 3, checklistId = 10, text = "Bread", sortOrder = 2), ticked = true, value = null, tickDay = 19_900, queued = false),
            ListItemUi(ChecklistItem(id = 4, checklistId = 10, text = "Coffee beans", sortOrder = 3), ticked = true, value = null, tickDay = 19_900, queued = true),
        )
        capture(
            "lists-detail-plain-ticked-group.png",
            ListDetailState(checklist = checklist, loading = false, unticked = unticked, ticked = ticked, showTicked = true),
        )
    }

    @Test
    fun `a routine with a measured item showing a refusal`() {
        val checklist = checklist(11, "Bio", scheduleKind = "DAILY")
        val measured = ChecklistItem(
            id = 5, checklistId = 11, text = "Water", sortOrder = 0,
            measureUnit = "L", measureTarget = 3.0, measureDirection = MeasureDirection.AT_LEAST.name,
        )
        val unticked = listOf(ListItemUi(measured, ticked = false, value = null, tickDay = null, queued = false))
        capture(
            "lists-detail-measured-refusal.png",
            ListDetailState(
                checklist = checklist,
                loading = false,
                unticked = unticked,
                appliesToday = true,
                scheduleLabel = "Daily",
                refusals = mapOf(5L to "\"Water\" is measured in L - give a number to tick it, nothing was recorded."),
            ),
        )
    }

    @Test
    fun `the all-ticked delete offer`() {
        val checklist = checklist(12, "Todo")
        val ticked = listOf(
            ListItemUi(ChecklistItem(id = 6, checklistId = 12, text = "Book dentist cleaning", sortOrder = 0), ticked = true, value = null, tickDay = 19_900, queued = false),
        )
        capture(
            "lists-detail-all-ticked.png",
            ListDetailState(checklist = checklist, loading = false, unticked = emptyList(), ticked = ticked, showTicked = true),
        )
    }

    @Test
    fun `an empty list`() {
        val checklist = checklist(13, "New list")
        capture(
            "lists-detail-empty.png",
            ListDetailState(checklist = checklist, loading = false, unticked = emptyList(), ticked = emptyList()),
        )
    }

    private fun capturePage(fileName: String, state: ListsPageState) {
        composeTestRule.setContent {
            SoftTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    ListsContent(state = state, callbacks = ListContentPreviewCallbacks.page())
                }
            }
        }
        composeTestRule.onRoot().captureRoboImage(fileName)
    }

    private fun capture(fileName: String, state: ListDetailState) {
        composeTestRule.setContent {
            SoftTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    ListDetailContent(state = state, callbacks = ListContentPreviewCallbacks.detail())
                }
            }
        }
        composeTestRule.onRoot().captureRoboImage(fileName)
    }
}

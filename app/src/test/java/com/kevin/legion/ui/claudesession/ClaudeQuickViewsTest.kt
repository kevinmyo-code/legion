package com.kevin.legion.ui.claudesession

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.kevin.legion.claude.ClaudeModalHolder
import com.kevin.legion.claude.ClaudeOutcome
import com.kevin.legion.claude.ClaudeOverview
import com.kevin.legion.claude.ClaudeSessionController
import com.kevin.legion.claude.ClaudeStatus
import com.kevin.legion.claude.ClaudeStep
import com.kevin.legion.claude.ClaudeTask
import com.kevin.legion.claude.ClaudeWording
import com.kevin.legion.claude.FakeClaudeBackend
import com.kevin.legion.claude.FakeClaudeBackend.Companion.NOW
import com.kevin.legion.claude.FakeClaudeBackend.Companion.task
import com.kevin.legion.claude.InMemoryClaudeModalMemory
import com.kevin.legion.ui.theme.soft.SoftTheme
import java.time.Duration
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * The Claude task dialog and the header's Claude sheet (Kevin 2026-11-04): the dialog follows one
 * task live, draws its steps, and stays after the task finishes until Close; the sheet says when
 * there is nothing.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ClaudeQuickViewsTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val wording = ClaudeWording(now = { NOW }, zone = { ZoneOffset.UTC })

    private val steps = listOf(
        ClaudeStep(NOW.minusSeconds(60), "reading Gmail"),
        ClaudeStep(NOW.minusSeconds(30), "opening Amazon's order page"),
    )

    @Test
    fun `the dialog follows the task, keeps its steps, and stays after it is done until Close`() {
        val backend = FakeClaudeBackend()
        backend.taskById[41] = task(41, ClaudeTask.RUNNING, progress = "opening Amazon's order page")
            .copy(steps = steps, request = "check whether my Amazon order shipped yet please and tell me")
        val holder = ClaudeModalHolder(InMemoryClaudeModalMemory()) { NOW }
        holder.show(41)
        rule.setContent {
            SoftTheme {
                ClaudeTaskModalHost(
                    onOpenAll = {},
                    holderOverride = holder,
                    controllerOverride = ClaudeSessionController(backend, wording),
                    pollEvery = Duration.ofMillis(200),
                )
            }
        }
        rule.waitUntil(5_000) { rule.onAllNodesWithTag(DIALOG_STEP_TAG).fetchSemanticsNodes().size == 2 }
        rule.onNodeWithText("Running").assertExists()
        rule.onNodeWithText("Now: opening Amazon's order page").assertExists()
        rule.onNodeWithText("On the Alienware").assertExists()
        rule.onNodeWithText("check whether my Amazon order shipped yet please and tell...").assertExists()

        backend.taskById[41] = task(41, ClaudeTask.DONE, summary = "It shipped; arrives Friday.").copy(steps = steps)
        rule.waitUntil(5_000) { rule.onAllNodesWithText("Done").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Summary: It shipped; arrives Friday.").assertExists()
        rule.onAllNodesWithTag(DIALOG_STEP_TAG).assertCountEquals(2)
        // Finished, and still up: nothing but Close takes it down.
        rule.mainClock.advanceTimeBy(2_000)
        rule.onNodeWithTag(DIALOG_TAG).assertExists()
        assertEquals(41, holder.pending.value?.taskId)

        rule.onNodeWithText("Close").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(DIALOG_TAG).assertDoesNotExist()
        assertNull(holder.pending.value)
    }

    @Test
    fun `a question is drawn in words with what happens next`() {
        val ui = ClaudeQuickRows.modal(
            task(7, ClaudeTask.NEEDS_CONFIRMATION, question = "Delete the old exports?"),
            wording,
        )
        rule.setContent { SoftTheme { ClaudeTaskDialogContent(ui, 7, onClose = {}, onOpenAll = {}) } }
        rule.onNodeWithText("Waiting for your yes").assertExists()
        rule.onNodeWithText("Question: Delete the old exports?").assertExists()
        rule.onNodeWithText("See all").assertExists()
    }

    @Test
    fun `the sheet says when there is nothing, and still links to the full screen`() {
        var openedAll = 0
        rule.setContent {
            SoftTheme {
                ClaudeSessionsSheetBody(ClaudeSheetUi(loading = false), onOpenTask = {}, onOpenAll = { openedAll++ })
            }
        }
        rule.onNodeWithText(EMPTY_SHEET).assertExists()
        rule.onNodeWithText("Open Claude sessions").performClick()
        assertEquals(1, openedAll)
    }

    @Test
    fun `sheet rows give the machine, what it is doing, the status in words and the age, and open the task`() {
        val overview = ClaudeOverview(
            ClaudeStatus(emptyList(), emptyList()),
            listOf(
                task(41, ClaudeTask.RUNNING, progress = "reading Gmail"),
                task(40, ClaudeTask.DONE, request = "find the AW4 wiring PDF"),
            ),
        )
        val rows = ClaudeQuickRows.sheet(overview, wording)
        var opened: Int? = null
        rule.setContent {
            SoftTheme { ClaudeSessionsSheetBody(ClaudeSheetUi(loading = false, rows = rows), { opened = it }, {}) }
        }
        rule.onNodeWithText("reading Gmail").assertExists()
        rule.onNodeWithText("Running, Alienware, just now").assertExists()
        rule.onNodeWithText("Done, Alienware, just now").performClick()
        assertEquals(40, opened)
    }

    @Test
    fun `the sheet's read failure is said in words, never an empty list`() {
        val sentence = ClaudeWording.failed(ClaudeOutcome.Unreachable("down"), "read the Claude sessions")
        rule.setContent {
            SoftTheme { ClaudeSessionsSheetBody(ClaudeSheetUi(loading = false, unavailable = sentence), {}, {}) }
        }
        rule.onNodeWithText(sentence).assertExists()
        rule.onNodeWithText(EMPTY_SHEET).assertDoesNotExist()
    }
}

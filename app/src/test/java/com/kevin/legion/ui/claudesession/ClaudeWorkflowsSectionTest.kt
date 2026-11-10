package com.kevin.legion.ui.claudesession

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.kevin.legion.claude.ClaudeOutcome
import com.kevin.legion.claude.ClaudeSessionController
import com.kevin.legion.claude.ClaudeTask
import com.kevin.legion.claude.ClaudeTaskDraft
import com.kevin.legion.claude.ClaudeWorkflow
import com.kevin.legion.claude.ClaudeWorkflowEvery
import com.kevin.legion.claude.FakeClaudeBackend
import com.kevin.legion.claude.FakeClaudeBackend.Companion.machine
import com.kevin.legion.claude.FakeClaudeBackend.Companion.task
import com.kevin.legion.claude.FakeWatch
import com.kevin.legion.ui.theme.soft.SoftTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode

/**
 * Worker-workflows ticket 06, the hands half (ADR 0035): the Workflows section shows each workflow's
 * name, one line, last run, where it stops for his yes, its standing mail and schedule, and Run; Run
 * reaches the same controller call the voice action makes; a scheduled task is labelled. Invented
 * workflows only.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ClaudeWorkflowsSectionTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val digest = ClaudeWorkflow(
        machine = "Alienware", machineState = "idle", name = "weekly-digest", spoken = "weekly digest",
        says = "Writes the week's digest of finished tasks.", params = emptyList(),
        asks = listOf("Send the digest to your email?"), deliver = ClaudeTaskDraft.DELIVER_EMAIL_SELF,
        every = ClaudeWorkflowEvery(168, 10, 240), lastRun = null, waiting = false, scheduleRefused = null,
        due = false,
    )

    private fun viewModel(backend: FakeClaudeBackend) =
        ClaudeSessionViewModel(RuntimeEnvironment.getApplication(), ClaudeSessionController(backend), FakeWatch())

    private fun at(text: String) =
        rule.onNodeWithTag(LIST_TAG).performScrollToNode(hasText(text)).let { rule.onNodeWithText(text) }

    @Test
    fun `a workflow card says what it does, where it stops, its mail and schedule, and Run calls back`() {
        val calls = mutableListOf<Pair<String, String>>()
        val state = ClaudeSessionUiState(loading = false, workflows = ClaudeWorkflowRows.rows(listOf(digest)))
        rule.setContent {
            SoftTheme {
                ClaudeSessionContent(state, ClaudeSessionCallbacks(onRunWorkflow = { m, n -> calls += m to n }))
            }
        }
        at("Weekly digest on the Alienware").assertExists()
        at("Writes the week's digest of finished tasks.").assertExists()
        at("Never run.").assertExists()
        at("It stops for your yes first: Send the digest to your email?").assertExists()
        at("Every run emails its result to you.").assertExists()
        at("Runs on its own about every week when the Alienware is idle, and stops at every question.").assertExists()
        at("Run").performClick()
        assertEquals(listOf("Alienware" to "weekly-digest"), calls)
    }

    @Test
    fun `a scheduled task is labelled Scheduled with its workflow`() {
        val backend = FakeClaudeBackend()
        backend.machines(machine("Alienware"))
        backend.tasksOut = ClaudeOutcome.Ok(
            listOf(
                task(
                    70, ClaudeTask.NEEDS_CONFIRMATION, origin = ClaudeTaskDraft.ORIGIN_SCHEDULE,
                    kind = ClaudeTask.KIND_WORKFLOW, workflow = "weekly-digest", request = "Run weekly digest.",
                ),
            ),
        )
        val rows = ClaudeSessionRows.tasks(
            com.kevin.legion.claude.ClaudeOverview(
                (backend.statusOut as ClaudeOutcome.Ok).value,
                (backend.tasksOut as ClaudeOutcome.Ok).value,
            ),
        )
        assertTrue(rows.single().scheduled)
        val state = ClaudeSessionUiState(loading = false, tasks = rows)
        rule.setContent { SoftTheme { ClaudeSessionContent(state, ClaudeSessionCallbacks()) } }
        at("Scheduled, Weekly digest").assertExists()
    }

    @Test
    fun `the screen's Run goes through the controller the voice action uses`() {
        val backend = FakeClaudeBackend()
        backend.machines(machine("Alienware"))
        backend.workflowsOut = ClaudeOutcome.Ok(listOf(digest))
        val vm = viewModel(backend)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, vm.state.value.workflows.size)
        vm.setWorkflowParams("Alienware/weekly-digest", "only the Alienware's tasks")
        vm.runWorkflow("Alienware", "weekly-digest")
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(
            listOf(Triple<String?, String, String?>("Alienware", "weekly-digest", "only the Alienware's tasks")),
            backend.runs,
        )
        assertTrue(vm.state.value.message!!.startsWith("Sent to the Alienware: weekly digest."))
    }

    @Test
    fun `unreadable workflows are said in words, never drawn as none`() {
        val backend = FakeClaudeBackend()
        backend.machines(machine("Alienware"))
        backend.workflowsOut = ClaudeOutcome.Unreachable("down")
        val vm = viewModel(backend)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(vm.state.value.workflowsNote!!.contains("read the workflows"))
    }
}

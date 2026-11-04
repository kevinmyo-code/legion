package com.kevin.legion.ui.claudesession

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.assert
import com.kevin.legion.ui.theme.soft.SoftTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * The Claude session screen's stateless content (claude-session ticket 05: "Compose tests for each
 * machine state, a question, a found file"), plus the work laptop's summaries-only file row, the
 * waiting-on-you session first, and a running task's live step. No ViewModel and no network: the
 * content is drawn from a [ClaudeSessionUiState] built by hand.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ClaudeSessionContentTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun show(state: ClaudeSessionUiState, callbacks: ClaudeSessionCallbacks = ClaudeSessionCallbacks()) {
        rule.setContent { SoftTheme { ClaudeSessionContent(state, callbacks) } }
    }

    /** Scrolls the lazy list to [text] so a row below the fold is composed. */
    private fun at(text: String) = rule.onNodeWithTag(LIST_TAG).performScrollToNode(hasText(text)).let {
        rule.onNodeWithText(text)
    }

    private val machines = listOf(
        MachineRowUi("Alienware", "on, idle", online = true, summariesOnly = false),
        MachineRowUi("Spare", "on, busy", online = true, summariesOnly = false),
        MachineRowUi("work laptop", "off since 3:40 PM", online = false, summariesOnly = true),
    )

    private fun task(
        id: Int,
        status: String,
        machine: String = "Alienware",
        question: String? = null,
        files: List<FileRowUi> = emptyList(),
        filesMayCome: Boolean = true,
        progress: String? = null,
    ) = TaskRowUi(
        id = id, request = "find the AW4 wiring PDF", machine = machine, statusText = status,
        summary = "Found it in Downloads.", question = question, progress = progress, files = files,
        filesMayCome = filesMayCome, needsConfirmation = question != null, needsMachine = false,
    )

    @Test
    fun `each machine state is drawn in words, off with its time`() {
        show(ClaudeSessionUiState(loading = false, machines = machines))
        at("on, idle").assertExists()
        at("on, busy").assertExists()
        at("off since 3:40 PM").assertExists()
        at("Summaries only, never files.").assertExists()
    }

    @Test
    fun `an opened task lists its kept steps newest first`() {
        val steps = listOf(StepRowUi("7:52 PM", "found it"), StepRowUi("7:40 PM", "reading Downloads"))
        val done = task(40, "Done").copy(steps = steps)
        show(ClaudeSessionUiState(loading = false, machines = machines, tasks = listOf(done), expandedTask = 40))
        at("found it").assertExists()
        at("7:40 PM").assertExists()
        val drawn = rule.onAllNodesWithTag(STEP_TAG, useUnmergedTree = true).fetchSemanticsNodes()
        assertEquals(2, drawn.size)
        assertTrue(drawn[0].positionInRoot.y < drawn[1].positionInRoot.y)
        rule.onAllNodesWithTag(STEP_TAG, useUnmergedTree = true)[0]
            .assert(hasAnyDescendant(hasText("found it")))
    }

    @Test
    fun `a closed task does not list its steps`() {
        val done = task(40, "Done").copy(steps = listOf(StepRowUi("7:40 PM", "reading Downloads")))
        show(ClaudeSessionUiState(loading = false, machines = machines, tasks = listOf(done)))
        at("Task 40 on the Alienware: Done").assertExists()
        rule.onNodeWithText("reading Downloads").assertDoesNotExist()
    }

    @Test
    fun `an unreachable engine is a sentence, never an empty machine list`() {
        show(ClaudeSessionUiState(loading = false, unavailable = "I can't reach the engine right now."))
        at("I can't reach the engine right now.").assertExists()
        rule.onNodeWithText("No machine has ever connected.").assertDoesNotExist()
    }

    @Test
    fun `a question shows the yes button, which sends a yes`() {
        var answered: Pair<Int, Boolean>? = null
        show(
            ClaudeSessionUiState(
                loading = false, machines = machines, expandedTask = 42,
                tasks = listOf(task(42, "Waiting for your yes", question = "Email the sheet to Dad?")),
            ),
            ClaudeSessionCallbacks(onAnswer = { id, yes -> answered = id to yes }),
        )
        at("Question: Email the sheet to Dad?").assertExists()
        at("Yes, go ahead").performClick()
        assertEquals(42 to true, answered)
    }

    @Test
    fun `a found file has Send to phone, and the work laptop's does not`() {
        var asked: Pair<Int, Int>? = null
        show(
            ClaudeSessionUiState(
                loading = false, machines = machines, expandedTask = 41,
                tasks = listOf(
                    task(41, "Done", files = listOf(FileRowUi(9, "AW4 wiring rev C.pdf", "2.0 MB", false, false))),
                ),
            ),
            ClaudeSessionCallbacks(onSendFile = { t, f -> asked = t to f }),
        )
        at("AW4 wiring rev C.pdf (2.0 MB)").assertExists()
        at("Send to phone").performClick()
        assertEquals(41 to 9, asked)
    }

    @Test
    fun `a summaries-only machine's file says so and offers nothing`() {
        show(
            ClaudeSessionUiState(
                loading = false, machines = machines, expandedTask = 7,
                tasks = listOf(
                    task(7, "Done", machine = "work laptop", filesMayCome = false,
                        files = listOf(FileRowUi(3, "notes.md", "10 bytes", false, false))),
                ),
            ),
        )
        at("Summary only").assertExists()
        rule.onNodeWithText("Send to phone").assertDoesNotExist()
    }

    @Test
    fun `a running task shows its live step`() {
        val running = task(41, "Running", progress = "searching Downloads")
        show(ClaudeSessionUiState(loading = false, machines = machines, tasks = listOf(running)))
        at("Now: searching Downloads").assertExists()
    }

    @Test
    fun `a session waiting on you is drawn first`() {
        val sessions = ClaudeSessionRows.sessions(
            listOf(
                com.kevin.legion.claude.ClaudeCodeSession("Alienware", "smg-aw4", "running", "tune shift map", null),
                com.kevin.legion.claude.ClaudeCodeSession("Alienware", "legion", "waiting", "fix the tests", null),
            ),
        )
        assertEquals("waiting on you", sessions.first().stateText)
        assertEquals("legion", sessions.first().project)
        show(ClaudeSessionUiState(loading = false, sessions = sessions))
        at("waiting on you").assertExists()
    }
}

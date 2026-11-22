package com.kevin.legion.ui.controlroom

import android.app.Application
import com.kevin.legion.board.BoardOutcome
import com.kevin.legion.claude.ClaudeCodeSession
import com.kevin.legion.claude.ClaudeMachine
import com.kevin.legion.claude.ClaudeOutcome
import com.kevin.legion.claude.ClaudeSessionController
import com.kevin.legion.claude.ClaudeStatus
import com.kevin.legion.claude.ClaudeTask
import com.kevin.legion.claude.FakeClaudeBackend
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The control room's ViewModel (control room C1-C4) over fakes: sessions and message cards through
 * the Claude controller, the board half through its own backend, and an Allow that reaches the
 * engine only after the confirm said yes. Made-up data only.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class ControlRoomViewModelTest {
    private val now = Instant.parse("2031-04-02T10:00:00Z")
    private val claude = FakeClaudeBackend()
    private val board = FakeControlRoomBackend()

    @Before
    fun main() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun reset() = Dispatchers.resetMain()

    private fun viewModel() = ControlRoomViewModel(
        RuntimeEnvironment.getApplication() as Application,
        ClaudeSessionController(claude),
        board,
        now = { now },
    )

    private fun machines(vararg m: ClaudeMachine) = m.toList()

    private fun status(sessions: List<ClaudeCodeSession>) {
        claude.statusOut = ClaudeOutcome.Ok(
            ClaudeStatus(
                machines(
                    ClaudeMachine("Alienware", "full", "idle", now, emptyList(), emptyMap()),
                    ClaudeMachine("work laptop", "listed", "idle", now, emptyList(), emptyMap()),
                ),
                sessions,
            ),
        )
    }

    private fun session(id: String, machine: String = "Alienware", state: String = "waiting") =
        ClaudeCodeSession(machine, "garden", state, null, now, sessionId = id, name = "planner",
            waitingSince = now.minusSeconds(12 * 60))

    private fun messageCard(id: Int) = ClaudeTask(
        id = id, request = "Message my planner session", machine = "Alienware", status = ClaudeTask.NEEDS_CONFIRMATION,
        summary = null, question = ClaudeTask.SESSION_SEND_PREFIX + "planner: \"water the beans\"",
        createdAt = now, finishedAt = now, files = emptyList(), parent = null, origin = "named", deliver = null,
        confirms = false, progress = null, progressAt = null,
    )

    @Test
    fun `sessions show name, folder, machine, state and how long it has waited`() {
        status(listOf(session("s-1"), session("s-2", machine = "work laptop", state = "busy")))
        val s = viewModel().state.value
        assertEquals(listOf("planner", "planner"), s.sessions.map { it.name })
        assertEquals("waiting 12 min", s.sessions[0].waited)
        assertTrue(s.sessions[0].canSummarise)
        // The work laptop is summaries-only: no transcript read is ever asked of it.
        assertFalse(s.sessions[1].canSummarise)
        assertEquals(1, s.count(ControlTab.SESSIONS))
    }

    @Test
    fun `summary sends a code-built task naming the session id, to its machine`() {
        status(listOf(session("s-1")))
        val vm = viewModel()
        vm.summarise(vm.state.value.sessions.single())
        val sent = claude.created.single()
        assertEquals(ControlRoomViewModel.summaryRequest("s-1"), sent.request)
        assertEquals("Alienware", sent.machine)
        assertTrue("400 characters" in sent.request)
    }

    @Test
    fun `a message card is one tap, and after its yes it has no Yes`() {
        status(emptyList())
        val card = messageCard(7)
        claude.tasksOut = ClaudeOutcome.Ok(listOf(card))
        claude.taskById[7] = card
        val vm = viewModel()
        assertEquals(listOf(7), vm.state.value.tasks.map { it.id })
        vm.yes(7)
        val yes = claude.created.single()
        assertTrue(yes.confirms)
        assertEquals(7, yes.parent)
        claude.tasksOut = ClaudeOutcome.Ok(listOf(card, card.copy(id = 8, parent = 7, confirms = true, status = "queued")))
        vm.refresh()
        assertEquals(emptyList<Int>(), vm.state.value.tasks.map { it.id })
    }

    @Test
    fun `allow reaches the engine only after the confirm said yes`() {
        status(emptyList())
        val vm = viewModel()
        val card = vm.state.value.actions.single()
        val no = FakeConfirmer(yes = false)
        vm.allow(card, no)
        assertEquals(listOf(card.headline), no.shown)
        assertTrue(board.calls.none { it.startsWith("allow") })
        assertEquals(BiometricActionConfirmer.CANCELLED, vm.state.value.message)
        vm.allow(card, FakeConfirmer(yes = true))
        assertEquals(listOf("allow:rec-1:" + "b".repeat(64)), board.calls)
    }

    @Test
    fun `decline needs no confirm and a decided record cannot be tapped`() {
        status(emptyList())
        val vm = viewModel()
        vm.decline(vm.state.value.actions.single())
        assertEquals(listOf("decline:rec-1:" + "b".repeat(64)), board.calls)
        board.calls.clear()
        val decided = vm.state.value.actions.single().copy(open = false)
        vm.allow(decided, FakeConfirmer(yes = true))
        assertTrue(board.calls.isEmpty())
    }

    @Test
    fun `a decision sends the hash it showed, and a deploy question is chat only`() {
        status(emptyList())
        val vm = viewModel()
        val (teapot, kettle) = vm.state.value.decisions
        assertFalse(teapot.chatOnly)
        assertTrue(kettle.chatOnly)
        vm.answer(teapot, "green")
        vm.answer(kettle, "yes")
        assertEquals(listOf("answer:teapot:" + "a".repeat(64) + ":green:"), board.calls)
        vm.type("teapot", "  green, the big one ")
        vm.answer(teapot, null)
        assertEquals("answer:teapot:" + "a".repeat(64) + "::green, the big one", board.calls.last())
    }

    @Test
    fun `the engine's 409 words are shown as they are`() {
        status(emptyList())
        board.answerOut = BoardOutcome.Failed("Nothing was sent. The question changed after you saw it.")
        val vm = viewModel()
        vm.answer(vm.state.value.decisions.first(), "blue")
        assertEquals("Nothing was sent. The question changed after you saw it.", vm.state.value.message)
    }

    @Test
    fun `the whole backlog shows with his vote and says how many more`() {
        status(emptyList())
        val b = viewModel().state.value.board!!
        assertEquals(listOf("A quieter kettle alarm", "Tea timer on the watch"), b.backlog.map { it.title })
        assertEquals("p1", b.backlog[0].vote)
        assertEquals("RICE 12.0, YELLOW", b.backlog[0].meta)
        assertEquals("Showing 2 of 5 ideas.", b.backlogMore)
    }

    @Test
    fun `a vote is sent as tapped`() {
        status(emptyList())
        val vm = viewModel()
        vm.vote("idea-000000000b", "p2")
        assertEquals(listOf("vote:idea-000000000b:p2"), board.calls)
    }

    @Test
    fun `the board switched off says so and the Claude half still works`() {
        status(listOf(session("s-1")))
        board.boardOut = BoardOutcome.Failed("The control room is switched off on the engine.")
        board.actionsOut = BoardOutcome.Failed("off")
        val s = viewModel().state.value
        assertEquals("The control room is switched off on the engine.", s.boardNote)
        assertEquals(1, s.sessions.size)
        assertTrue(s.actions.isEmpty())
    }
}

package com.kevin.legion.service

import com.kevin.legion.claude.ClaudeCreated
import com.kevin.legion.claude.ClaudeModalHolder
import com.kevin.legion.claude.InMemoryClaudeModalMemory
import com.kevin.legion.claude.ClaudeOutcome
import com.kevin.legion.claude.ClaudeSessionController
import com.kevin.legion.claude.ClaudeTask
import com.kevin.legion.claude.ClaudeTaskFile
import com.kevin.legion.claude.ClaudeWatchStore
import com.kevin.legion.claude.FakeClaudeBackend
import com.kevin.legion.claude.FakeClaudeBackend.Companion.machine
import com.kevin.legion.claude.FakeClaudeBackend.Companion.task
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
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
 * The `claude_session` voice tool (claude-session ticket 05): one declaration, every action on the
 * same [ClaudeSessionController] the screen calls (ADR 0035), success only from a 2xx, and no
 * result that calls a sent task done (CLAUDE.md section 7).
 */
@RunWith(RobolectricTestRunner::class)
class ClaudeSessionToolboxTest {
    private val context = RuntimeEnvironment.getApplication()
    private lateinit var backend: FakeClaudeBackend
    private lateinit var modal: ClaudeModalHolder

    @Before
    fun setUp() {
        backend = FakeClaudeBackend()
        modal = ClaudeModalHolder(InMemoryClaudeModalMemory())
        ClaudeSessionToolbox.controllerFactory = { ClaudeSessionController(backend) }
        ClaudeSessionToolbox.modalFactory = { modal }
    }

    @After
    fun tearDown() {
        ClaudeSessionToolbox.controllerFactory = ClaudeSessionController::forContext
        ClaudeSessionToolbox.modalFactory = ClaudeModalHolder::get
    }

    @Test
    fun `a send the engine accepted opens the task dialog on that task`() {
        backend.machines(machine("Alienware"))
        call("action" to "send", "request" to "check my Amazon orders")
        assertEquals(41, modal.pending.value?.taskId)
        assertFalse(modal.pending.value!!.seen)
    }

    @Test
    fun `a send that was not sent opens nothing, and a check opens nothing`() {
        backend.statusOut = ClaudeOutcome.Unreachable("down")
        call("action" to "send", "request" to "check my Amazon orders")
        call("action" to "check")
        assertEquals(null, modal.pending.value)
    }

    @Test
    fun `a task waiting for a machine opens the dialog too`() {
        backend.machines(machine("Alienware"), machine("Spare"))
        backend.createOut = { ClaudeOutcome.Ok(ClaudeCreated(52, ClaudeTask.NEEDS_MACHINE, null)) }
        call("action" to "send", "request" to "list my repos")
        assertEquals(52, modal.pending.value?.taskId)
    }

    private fun call(vararg args: Pair<String, Any>): JSONObject = runBlocking {
        val json = JSONObject().apply { args.forEach { (k, v) -> put(k, v) } }
        ClaudeSessionToolbox.dispatch(context, "claude_session", json)!!
    }

    @Test
    fun `one declaration, named claude_session, and another name falls through`() = runBlocking {
        val decls = ClaudeSessionToolbox.declarations()
        assertEquals(1, decls.length())
        assertEquals("claude_session", decls.getJSONObject(0).getString("name"))
        assertEquals(null, ClaudeSessionToolbox.dispatch(context, "get_balance", JSONObject()))
        assertTrue(LiveToolbox.isMutatingTool("claude_session"))
        assertFalse("claude_session" in LiveToolbox.NON_BLOCKING_READ_TOOLS)
    }

    @Test
    fun `a send that came back ok is sent, never done, and carries the task id`() {
        backend.machines(machine("Alienware"))
        val r = call("action" to "send", "request" to "find the AW4 wiring PDF", "offered" to true)
        assertTrue(r.getBoolean("success"))
        assertEquals(41, r.getInt("task_id"))
        assertFalse(r.getString("message").contains(" is done"))
        assertEquals("offered", backend.created.single().origin)
    }

    @Test
    fun `which machine comes back unsuccessful with the task id for assign`() {
        backend.machines(machine("Alienware"), machine("work laptop", scope = "listed"))
        backend.createOut = { ClaudeOutcome.Ok(ClaudeCreated(52, ClaudeTask.NEEDS_MACHINE, null)) }
        val r = call("action" to "send", "request" to "list my repos")
        assertFalse(r.getBoolean("success"))
        assertTrue(r.getBoolean("needs_machine"))
        assertEquals(52, r.getInt("task_id"))
        val a = call("action" to "assign", "task_id" to 52, "machine" to "work laptop")
        assertTrue(a.getBoolean("success"))
    }

    @Test
    fun `an unreachable engine is unsuccessful and says nothing was sent`() {
        backend.statusOut = ClaudeOutcome.Unreachable("offline")
        val r = call("action" to "send", "request" to "x")
        assertFalse(r.getBoolean("success"))
        assertTrue(backend.created.isEmpty())
    }

    @Test
    fun `check reads machines, the live step, a found file's id and the waiting session first`() {
        backend.statusOut = ClaudeOutcome.Ok(
            com.kevin.legion.claude.ClaudeStatus(
                listOf(
                    machine(
                        "Alienware", state = "busy", running = listOf(41),
                        progress = mapOf(41 to "searching Downloads"),
                    ),
                ),
                listOf(
                    com.kevin.legion.claude.ClaudeCodeSession("Alienware", "legion", "waiting", "fix the tests", null),
                ),
            ),
        )
        backend.tasksOut = ClaudeOutcome.Ok(
            listOf(
                task(41, ClaudeTask.RUNNING, progress = "searching Downloads"),
                task(40, ClaudeTask.DONE, files = listOf(ClaudeTaskFile(9, "AW4.pdf", 2048, false, false))),
            ),
        )
        val m = call("action" to "check").getString("message")
        assertTrue(m, m.contains("Alienware: on, busy; running task 41 (searching Downloads)"))
        assertTrue(m, m.contains("[file_id 9: AW4.pdf, 2 KB]"))
        assertTrue(m, m.contains("waiting on you"))
    }

    @Test
    fun `check names one step per running task, never the kept history`() {
        backend.machines(machine("Alienware", state = "busy", running = listOf(41)))
        val history = listOf(
            com.kevin.legion.claude.ClaudeStep(FakeClaudeBackend.NOW, "opening wiring.pdf"),
            com.kevin.legion.claude.ClaudeStep(FakeClaudeBackend.NOW.minusSeconds(60), "reading Downloads"),
        )
        backend.tasksOut = ClaudeOutcome.Ok(
            listOf(
                task(41, ClaudeTask.RUNNING, progress = "writing the summary").copy(steps = history),
                task(42, ClaudeTask.RUNNING).copy(steps = history),
                task(40, ClaudeTask.DONE, summary = "Found it.").copy(steps = history),
            ),
        )
        val m = call("action" to "check").getString("message")
        assertTrue(m, m.contains("is running: writing the summary. Not done yet."))
        // No current line: the newest kept step stands in, one line only.
        assertTrue(m, m.contains("in my downloads\") is running: opening wiring.pdf. Not done yet."))
        assertFalse(m, m.contains("reading Downloads"))
        assertEquals(1, Regex("opening wiring.pdf").findAll(m).count())
    }

    @Test
    fun `a yes needs a task id`() {
        val r = call("action" to "answer", "yes" to true)
        assertFalse(r.getBoolean("success"))
    }

    @Test
    fun `quiet turns the announcements off and unquiet turns them back on`() = runBlocking {
        val watch = com.kevin.legion.claude.FakeWatch()
        ClaudeSessionToolbox.watchFactory = { watch }
        try {
            assertTrue(call("action" to "quiet").getBoolean("success"))
            assertTrue(watch.silenced())
            assertTrue(call("action" to "unquiet").getBoolean("success"))
            assertFalse(watch.silenced())
        } finally {
            ClaudeSessionToolbox.watchFactory = ::ClaudeWatchStore
        }
    }
}

package com.kevin.legion.service

import com.kevin.legion.claude.ClaudeCreated
import com.kevin.legion.claude.ClaudeMachineRef
import com.kevin.legion.claude.ClaudeModalHolder
import com.kevin.legion.claude.ClaudeOutcome
import com.kevin.legion.claude.ClaudeSessionController
import com.kevin.legion.claude.ClaudeTask
import com.kevin.legion.claude.ClaudeTaskDraft
import com.kevin.legion.claude.ClaudeWorkflowName
import com.kevin.legion.claude.FakeClaudeBackend
import com.kevin.legion.claude.FakeClaudeBackend.Companion.NOW
import com.kevin.legion.claude.FakeClaudeBackend.Companion.machine
import com.kevin.legion.claude.InMemoryClaudeModalMemory
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Worker-workflows ticket 06, the voice half: `action: "workflow"` sends Kevin's phrase for the
 * engine to resolve, reads the engine's miss back in words, `check` lists each online machine's
 * workflows, and a read-through turn can never start one (driven through [LiveToolbox.dispatch], so
 * the flag's wiring is under test, not only the toolbox's guard). Invented workflows only.
 */
@RunWith(RobolectricTestRunner::class)
class ClaudeWorkflowToolboxTest {
    private val context = RuntimeEnvironment.getApplication()
    private lateinit var backend: FakeClaudeBackend

    private val digest = ClaudeWorkflowName("weekly-digest", "Writes the week's digest of finished tasks.")
    private val tidy = ClaudeWorkflowName("tidy-downloads", "Moves old downloads into dated folders.")

    @Before
    fun setUp() {
        backend = FakeClaudeBackend()
        ClaudeSessionToolbox.controllerFactory = { ClaudeSessionController(backend) }
        ClaudeSessionToolbox.modalFactory = { ClaudeModalHolder(InMemoryClaudeModalMemory()) }
    }

    @After
    fun tearDown() {
        ClaudeSessionToolbox.controllerFactory = ClaudeSessionController::forContext
        ClaudeSessionToolbox.modalFactory = ClaudeModalHolder::get
    }

    private fun call(readThrough: Boolean = false, vararg pairs: Pair<String, Any>): JSONObject = runBlocking {
        val args = JSONObject()
        pairs.forEach { (k, v) -> args.put(k, v) }
        LiveToolbox.dispatch(context, "claude_session", args, touchedReadThroughToolThisTurn = readThrough)!!
    }

    @Test
    fun `workflow sends his phrase for the engine to resolve and says where it stops`() {
        backend.machines(machine("Alienware", workflows = listOf(tidy)))
        backend.createOut = { d ->
            ClaudeOutcome.Ok(
                ClaudeCreated(
                    51, ClaudeTask.QUEUED, ClaudeMachineRef("Alienware", "idle", NOW), "tidy-downloads",
                    listOf("Move the files into dated folders?"),
                ),
            )
        }
        val out = call(false, "action" to "workflow", "request" to "tidy my downloads from last week")
        val sent = backend.created.single()
        assertEquals("tidy my downloads from last week", sent.workflow)
        assertEquals("tidy my downloads from last week", sent.request)
        assertEquals(ClaudeTaskDraft.ORIGIN_NAMED, sent.origin)
        assertNull("no machine unless he named one", sent.machine)
        assertNull("the engine takes the workflow's own delivery", sent.deliver)
        assertTrue(out.getBoolean("success"))
        assertEquals(51, out.getInt("task_id"))
        val said = out.getString("message")
        assertTrue(said, said.startsWith("Sent to the Alienware: tidy downloads."))
        assertTrue(said, said.contains("It stops for your yes first: Move the files into dated folders?"))
        assertFalse("never done", said.contains("done.", ignoreCase = true))
    }

    @Test
    fun `an unknown name reads the engine's sentence, which names the workflows there are`() {
        backend.machines(machine("Alienware", workflows = listOf(tidy, digest)))
        val detail = "Nothing was sent. No machine has no workflow called that. There are: tidy downloads, weekly digest."
        backend.createOut = { ClaudeOutcome.Refused(detail, 400) }
        val out = call(false, "action" to "workflow", "request" to "audit my car")
        assertFalse(out.getBoolean("success"))
        assertEquals(detail, out.getString("message"))
    }

    @Test
    fun `a run already going is a refusal in words`() {
        backend.machines(machine("Alienware", workflows = listOf(tidy)))
        val detail = "Nothing was sent. A tidy downloads run is already queued, running or waiting on your yes."
        backend.createOut = { ClaudeOutcome.Refused(detail, 409) }
        val out = call(false, "action" to "workflow", "request" to "tidy my downloads")
        assertFalse(out.getBoolean("success"))
        assertEquals(detail, out.getString("message"))
    }

    @Test
    fun `a named machine is resolved and sent`() {
        backend.machines(machine("Alienware", workflows = listOf(tidy)), machine("work laptop", scope = "listed"))
        call(false, "action" to "workflow", "request" to "tidy my downloads", "machine" to "my Alienware")
        assertEquals("Alienware", backend.created.single().machine)
    }

    @Test
    fun `a read-through turn cannot start a workflow, and the next plain turn can`() {
        backend.machines(machine("Alienware", workflows = listOf(tidy)))
        val refused = call(true, "action" to "workflow", "request" to "tidy my downloads")
        assertFalse(refused.getBoolean("success"))
        assertEquals(ClaudeSessionToolbox.READ_THROUGH_REFUSAL, refused.getString("message"))
        assertTrue("nothing reached the engine", backend.created.isEmpty())
        assertTrue(backend.runs.isEmpty())

        val allowed = call(false, "action" to "workflow", "request" to "tidy my downloads")
        assertTrue(allowed.getBoolean("success"))
        assertEquals(1, backend.created.size)
    }

    @Test
    fun `a read-through turn may still check, which writes nothing`() {
        backend.machines(machine("Alienware", workflows = listOf(tidy)))
        val out = call(true, "action" to "check")
        assertTrue(out.getBoolean("success"))
        assertTrue(backend.created.isEmpty())
    }

    @Test
    fun `check lists each online machine's workflows by spoken name`() {
        backend.machines(
            machine("Alienware", workflows = listOf(tidy, digest)),
            machine("Spare", state = "offline", workflows = listOf(digest)),
        )
        val said = call(false, "action" to "check").getString("message")
        assertTrue(said, said.contains("Workflows on the Alienware: tidy downloads, weekly digest."))
        assertFalse("an offline machine's list is not read out", said.contains("Workflows on the Spare"))
    }

    @Test
    fun `check answers what a workflow does from the same list`() {
        backend.machines(machine("Alienware", workflows = listOf(tidy, digest)))
        val said = call(false, "action" to "check", "request" to "what does weekly digest do?").getString("message")
        assertTrue(said, said.contains("Weekly digest: Writes the week's digest of finished tasks."))
        assertFalse(said, said.contains("Tidy downloads: "))
    }

    @Test
    fun `the declaration carries the workflow action`() {
        val decl = ClaudeSessionToolbox.declarations().getJSONObject(0)
        val actions = decl.getJSONObject("parameters").getJSONObject("properties").getJSONObject("action")
            .getJSONArray("enum")
        assertTrue((0 until actions.length()).any { actions.getString(it) == "workflow" })
    }
}

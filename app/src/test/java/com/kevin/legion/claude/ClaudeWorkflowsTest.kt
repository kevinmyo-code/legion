package com.kevin.legion.claude

import com.kevin.legion.claude.FakeClaudeBackend.Companion.NOW
import com.kevin.legion.claude.FakeClaudeBackend.Companion.machine
import com.kevin.legion.claude.FakeClaudeBackend.Companion.task
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** An in-memory [ScheduledQuestionSeen]. */
class FakeSeen : ScheduledQuestionSeen {
    val ids = mutableSetOf<Int>()
    override fun seen() = ids.toSet()
    override fun mark(id: Int) { ids += id }
    override fun keepOnly(ids: Set<Int>) { this.ids.retainAll(ids) }
}

/**
 * Worker-workflows ticket 06 over a fake backend, no Android: the controller's two doors onto one
 * workflow call (ADR 0035), and the scheduled-question raise (ruling R3) once per id. Invented
 * workflows only.
 */
class ClaudeWorkflowsTest {
    private val backend = FakeClaudeBackend()
    private val watched = mutableListOf<Int>()
    private val controller = ClaudeSessionController(backend, onSent = { watched += it })

    // ------------------------------------------------------------------ the controller

    @Test
    fun `the screen's Run goes to workflows run by exact name, with the typed params`() = runBlocking {
        backend.machines(machine("Alienware"))
        val out = controller.runWorkflow("weekly-digest", "Alienware", params = " last week ", byName = true)
        assertEquals(listOf(Triple<String?, String, String?>("Alienware", "weekly-digest", "last week")), backend.runs)
        assertTrue("nothing went through tasks/", backend.created.isEmpty())
        assertTrue(out is ClaudeSendResult.Sent)
        assertTrue(out.sentence, out.sentence.startsWith("Sent to the Alienware: weekly digest."))
        assertEquals("the result is watched like any task he started", listOf(42), watched)
    }

    @Test
    fun `voice sends no machine when he named none, even with one on`() = runBlocking {
        backend.machines(machine("Alienware"), machine("Spare", state = "offline"))
        controller.runWorkflow("run the weekly digest", machine = null)
        assertNull(backend.created.single().machine)
        assertEquals("run the weekly digest", backend.created.single().workflow)
    }

    @Test
    fun `a workflow waiting for a machine asks which`() = runBlocking {
        backend.machines(machine("Alienware"), machine("Spare"))
        backend.createOut = { ClaudeOutcome.Ok(ClaudeCreated(60, ClaudeTask.NEEDS_MACHINE, null, "weekly-digest")) }
        val out = controller.runWorkflow("run the weekly digest", machine = null)
        assertTrue(out is ClaudeSendResult.NeedsMachine)
        assertEquals(60, (out as ClaudeSendResult.NeedsMachine).taskId)
    }

    @Test
    fun `an unreachable engine sends nothing and says so`() = runBlocking {
        backend.statusOut = ClaudeOutcome.Unreachable("down")
        val out = controller.runWorkflow("run the weekly digest", machine = null)
        assertTrue(out is ClaudeSendResult.NotSent)
        assertTrue(backend.created.isEmpty())
    }

    @Test
    fun `the schedule and the standing mail are said in words`() {
        val w = ClaudeWorkflow(
            machine = "Alienware", machineState = "idle", name = "weekly-digest", spoken = "weekly digest",
            says = "Writes the week's digest.", params = emptyList(), asks = listOf("Send the digest?"),
            deliver = ClaudeTaskDraft.DELIVER_EMAIL_SELF, every = ClaudeWorkflowEvery(168, 10, 240),
            lastRun = ClaudeWorkflowRun(12, "done", ClaudeTaskDraft.ORIGIN_SCHEDULE, NOW, NOW),
            waiting = false, scheduleRefused = null, due = false,
        )
        assertEquals(
            "Runs on its own about every week when the Alienware is idle, and stops at every question.",
            ClaudeWorkflowWording.schedule(w),
        )
        assertEquals("Every run emails its result to you.", ClaudeWorkflowWording.deliverLine(w))
        assertTrue(ClaudeWorkflowWording.lastRun(w, ClaudeWording()).startsWith("Last run (scheduled): done"))
        val refused = w.copy(
            machine = "work laptop",
            scheduleRefused = "Scheduled runs only happen on a full-access machine.",
        )
        assertEquals("Scheduled runs only happen on a full-access machine.", ClaudeWorkflowWording.schedule(refused))
    }

    // ------------------------------------------------------------------ scheduled questions

    private val raised = mutableListOf<Triple<Int, String, String>>()
    private var delivery = ClaudeDelivery.DELIVERED
    private var clock: Instant = NOW
    private val seen = FakeSeen()
    private val watch = FakeWatch()

    private fun poller() = ScheduledQuestionPoller(
        controller = { controller },
        seen = seen,
        watch = watch,
        announce = { id, text, hint -> raised += Triple(id, text, hint); delivery },
        now = { clock },
    )

    private fun question(id: Int, finished: Instant = NOW.minusSeconds(60)) = task(
        id, ClaudeTask.NEEDS_CONFIRMATION, question = "Send this week's digest to your email?",
        origin = ClaudeTaskDraft.ORIGIN_SCHEDULE, kind = ClaudeTask.KIND_WORKFLOW, workflow = "weekly-digest",
        finishedAt = finished,
    )

    @Test
    fun `a scheduled run that stopped for his yes is raised once, with the answer call`() = runBlocking {
        backend.whereOut = ClaudeOutcome.Ok(listOf(question(70)))
        val p = poller()
        assertEquals(1, p.pollNow())
        assertEquals("the second poll raises nothing", 0, p.pollNow())
        val (id, text, hint) = raised.single()
        assertEquals(70, id)
        assertTrue(text, text.startsWith("Scheduled weekly digest on the Alienware (task 70) stopped to ask:"))
        assertEquals("On a yes: claude_session answer, task_id 70, yes true.", hint)
        assertEquals(
            listOf(ClaudeTaskDraft.ORIGIN_SCHEDULE to ClaudeTask.NEEDS_CONFIRMATION),
            backend.whereAsked.distinct(),
        )
    }

    @Test
    fun `a scheduled question is never framed as a task he sent`() {
        val text = "Scheduled weekly digest on the Alienware (task 70) stopped to ask: Send it?"
        val note = ClaudeResultPolling.note(text, "", scheduled = true)
        val prompt = ClaudeResultPolling.prompt(text, "", scheduled = true)
        assertTrue(note, note.contains("they did not send it"))
        assertTrue(prompt, prompt.contains("they did not send it"))
        assertTrue(!note.contains("from a task they sent") && !prompt.contains("A task the user sent"))
        assertTrue(ClaudeResultPolling.note(text, "").contains("from a task they sent"))
    }

    @Test
    fun `an answered question is never replayed`() = runBlocking {
        backend.whereOut = ClaudeOutcome.Ok(listOf(question(70)))
        backend.tasksOut = ClaudeOutcome.Ok(listOf(task(71, ClaudeTask.RUNNING, parent = 70)))
        assertEquals(0, poller().pollNow())
        assertTrue(raised.isEmpty())
        assertTrue(70 in seen.ids)
    }

    @Test
    fun `a stale question is not raised`() = runBlocking {
        backend.whereOut = ClaudeOutcome.Ok(listOf(question(70, finished = NOW.minus(Duration.ofDays(4)))))
        assertEquals(0, poller().pollNow())
        assertTrue(raised.isEmpty())
    }

    @Test
    fun `quiet silences it, and it does not come back when announcements return`() = runBlocking {
        backend.whereOut = ClaudeOutcome.Ok(listOf(question(70)))
        watch.quiet = true
        val p = poller()
        assertEquals(0, p.pollNow())
        watch.quiet = false
        assertEquals(0, p.pollNow())
        assertTrue(raised.isEmpty())
    }

    @Test
    fun `not now is retried on the next poll`() = runBlocking {
        backend.whereOut = ClaudeOutcome.Ok(listOf(question(70)))
        delivery = ClaudeDelivery.RETRY
        val p = poller()
        p.pollNow()
        delivery = ClaudeDelivery.DELIVERED
        assertEquals(1, p.pollNow())
        assertEquals(2, raised.size)
    }

    @Test
    fun `the poll keeps its own cadence on the watcher's loop`() = runBlocking {
        backend.whereOut = ClaudeOutcome.Ok(emptyList())
        val p = poller()
        p.maybePoll()
        clock = clock.plusSeconds(30)
        p.maybePoll()
        assertEquals(1, backend.whereAsked.size)
        clock = clock.plus(ScheduledQuestionPoller.EVERY)
        p.maybePoll()
        assertEquals(2, backend.whereAsked.size)
    }

    @Test
    fun `an unreadable engine raises nothing and marks nothing`() = runBlocking {
        backend.whereOut = ClaudeOutcome.Unreachable("down")
        assertEquals(0, poller().pollNow())
        assertTrue(seen.ids.isEmpty())
    }
}

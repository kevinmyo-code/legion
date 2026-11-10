package com.kevin.legion.claude

import com.kevin.legion.claude.FakeClaudeBackend.Companion.NOW
import com.kevin.legion.claude.FakeClaudeBackend.Companion.machine
import com.kevin.legion.claude.FakeClaudeBackend.Companion.task
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ClaudeSessionController]'s rules (claude-session ticket 05; Kevin 2026-11-01): machine choice,
 * `deliver: email_self`, `confirms`, the work laptop's summaries-only rule, and that no sentence
 * says "done" for a task that was only sent.
 */
class ClaudeSessionControllerTest {
    private val backend = FakeClaudeBackend()
    private val sent = mutableListOf<Int>()
    private val controller = ClaudeSessionController(
        backend,
        ClaudeWording(now = { NOW }, zone = { ZoneOffset.UTC }),
        onSent = { sent += it },
    )

    private fun send(machine: String? = null, emailMe: Boolean = false) = runBlocking {
        controller.send("find the AW4 wiring PDF", machine, ClaudeTaskDraft.ORIGIN_NAMED, emailMe)
    }

    @Test
    fun `the only machine online is chosen without asking`() {
        backend.machines(machine("Alienware"), machine("work laptop", state = "offline", scope = "listed"))
        val result = send()
        assertTrue(result is ClaudeSendResult.Sent)
        assertEquals("Alienware", backend.created.single().machine)
        assertTrue(result.sentence.contains("sent to the Alienware, which is on"))
        assertFalse(result.sentence.contains(" is done"))
        assertEquals(listOf(41), sent)
    }

    @Test
    fun `more than one online leaves it to the engine and asks which, then assign queues it`() {
        backend.machines(machine("Alienware"), machine("work laptop", scope = "listed"))
        backend.createOut = { ClaudeOutcome.Ok(ClaudeCreated(52, ClaudeTask.NEEDS_MACHINE, null)) }
        val result = send()
        assertTrue(result is ClaudeSendResult.NeedsMachine)
        result as ClaudeSendResult.NeedsMachine
        assertEquals(52, result.taskId)
        assertNull(backend.created.single().machine)
        assertTrue(result.sentence.startsWith("Not sent yet"))
        val assigned = runBlocking { controller.assign(52, "the work laptop") }
        assertTrue(assigned is ClaudeSendResult.Sent)
        assertEquals(52 to "work laptop", backend.assigned.single())
    }

    @Test
    fun `a named machine that is off is queued and says off since when`() {
        backend.machines(machine("Alienware", state = "offline", lastSeen = NOW.minusSeconds(4 * 3600 + 20 * 60)))
        backend.createOut = { d ->
            val seen = NOW.minusSeconds(15600)
            ClaudeOutcome.Ok(ClaudeCreated(41, ClaudeTask.QUEUED, ClaudeMachineRef(d.machine!!, "offline", seen)))
        }
        val result = send(machine = "my Alienware")
        assertEquals("Alienware", backend.created.single().machine)
        assertTrue(result.sentence, result.sentence.contains("off since 3:40 PM"))
        assertTrue(result.sentence.contains("queued"))
        assertTrue(result.sentence.contains("Nothing has run yet"))
    }

    @Test
    fun `none online and several known asks which without creating anything`() {
        backend.machines(machine("Alienware", state = "offline"), machine("work laptop", state = "offline"))
        val result = send()
        assertTrue(result is ClaudeSendResult.NeedsMachine)
        assertNull((result as ClaudeSendResult.NeedsMachine).taskId)
        assertTrue(backend.created.isEmpty())
    }

    @Test
    fun `no machine ever connected sends nothing`() {
        val result = send()
        assertTrue(result is ClaudeSendResult.NotSent)
        assertTrue(result.sentence.contains("nothing was sent"))
        assertTrue(backend.created.isEmpty())
    }

    @Test
    fun `an unknown machine name sends nothing and names the known ones`() {
        backend.machines(machine("Alienware"))
        val result = send(machine = "desktop")
        assertTrue(result is ClaudeSendResult.NotSent)
        assertTrue(result.sentence.contains("Known: Alienware"))
        assertTrue(backend.created.isEmpty())
    }

    @Test
    fun `email me sets deliver email_self and nothing else does`() {
        backend.machines(machine("Alienware"))
        send(emailMe = true)
        send(emailMe = false)
        assertEquals(listOf("email_self", null), backend.created.map { it.deliver })
    }

    @Test
    fun `a yes to a needs_confirmation task is a follow-up with confirms true`() {
        backend.taskById[41] = task(41, ClaudeTask.NEEDS_CONFIRMATION, question = "Send the sheet to Dad?")
        val result = runBlocking { controller.answer(41, "", yes = true, emailMe = false) }
        assertTrue(result is ClaudeSendResult.Sent)
        val draft = backend.created.single()
        assertEquals(41, draft.parent)
        assertTrue(draft.confirms)
        assertNull(draft.machine)
    }

    /**
     * Phone audit 2026-11-10: a yes to a task that stopped to ask "send the email?" came back as
     * "The follow-up was sent to the Alienware", and the model told the user the email was on its
     * way. It was never sent. The yes's result names no outcome verb and says nothing happened yet.
     */
    @Test
    fun `a yes to a stopped task says nothing it asked about has happened`() {
        backend.machines(machine("Alienware"))
        backend.createOut = { _ ->
            ClaudeOutcome.Ok(ClaudeCreated(42, ClaudeTask.QUEUED, ClaudeMachineRef("Alienware", "idle", null)))
        }
        backend.taskById[41] =
            task(41, ClaudeTask.NEEDS_CONFIRMATION, question = "Send the note to the club secretary?")
        val result = runBlocking { controller.answer(41, "", yes = true, emailMe = false) }
        assertTrue(result is ClaudeSendResult.Sent)
        val s = result.sentence
        assertTrue(s, s.contains("task 41"))
        assertTrue(s, s.contains("has not happened yet"))
        assertFalse(s, s.contains("sent", ignoreCase = true))
        assertFalse(s, s.contains("on its way"))
        assertFalse(s, s.contains(" done"))
    }

    @Test
    fun `any follow-up says it has not run yet and never that it was sent`() {
        backend.machines(machine("Alienware"))
        backend.createOut = { _ ->
            ClaudeOutcome.Ok(ClaudeCreated(42, ClaudeTask.QUEUED, ClaudeMachineRef("Alienware", "idle", null)))
        }
        backend.taskById[41] = task(41, ClaudeTask.DONE)
        val result = runBlocking { controller.answer(41, "now email it to me", yes = false, emailMe = true) }
        val s = result.sentence
        assertTrue(s, s.contains("has not run yet"))
        assertFalse(s, s.contains("sent", ignoreCase = true))
    }

    @Test
    fun `any other follow-up carries no confirms`() {
        backend.taskById[41] = task(41, ClaudeTask.DONE)
        runBlocking { controller.answer(41, "now email it to me", yes = true, emailMe = true) }
        val draft = backend.created.single()
        assertFalse(draft.confirms)
        assertEquals("email_self", draft.deliver)
    }

    @Test
    fun `a file from the work laptop is never requested`() {
        backend.machines(machine("work laptop", scope = "listed"))
        val notes = ClaudeTaskFile(3, "notes.md", 10, false, false)
        backend.taskById[7] = task(7, ClaudeTask.DONE, machine = "work laptop", files = listOf(notes))
        val result = runBlocking { controller.sendFile(7, 3) }
        assertTrue(result is ClaudeSendResult.NotSent)
        assertTrue(result.sentence.contains("summaries only"))
        assertTrue(backend.fileRequests.isEmpty())
    }

    @Test
    fun `a file from the Alienware is requested and watched`() {
        val watched = mutableListOf<Triple<Int, Int, String>>()
        val c = ClaudeSessionController(backend, onFileRequested = { t, f, n -> watched += Triple(t, f, n) })
        backend.machines(machine("Alienware"))
        val pdf = ClaudeTaskFile(9, "AW4 wiring rev C.pdf", 2_100_000, false, false)
        backend.taskById[41] = task(41, ClaudeTask.DONE, files = listOf(pdf))
        val result = runBlocking { c.sendFile(41, 9) }
        assertTrue(result is ClaudeSendResult.Sent)
        assertEquals(41 to 9, backend.fileRequests.single())
        assertEquals(Triple(41, 9, "AW4 wiring rev C.pdf"), watched.single())
        assertTrue(result.sentence.contains("not on the phone yet"))
    }

    @Test
    fun `an unreachable engine sends nothing and says so`() {
        backend.statusOut = ClaudeOutcome.Unreachable("offline")
        val result = send()
        assertTrue(result is ClaudeSendResult.NotSent)
        assertTrue(result.sentence.contains("can't reach the engine"))
        assertTrue(backend.created.isEmpty())
    }

    @Test
    fun `spoken machine names resolve to the engine's rows`() {
        val known = listOf("Alienware", "work laptop")
        assertEquals("Alienware", ClaudeMachines.resolve("my Alienware", known))
        assertEquals("work laptop", ClaudeMachines.resolve("the work computer", known))
        assertNull(ClaudeMachines.resolve("desktop", known))
    }
}

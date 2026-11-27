package com.kevin.legion.claude

import com.kevin.legion.claude.FakeClaudeBackend.Companion.NOW
import com.kevin.legion.claude.FakeClaudeBackend.Companion.machine
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The problem report door (alfred-builds ticket 01): filed only from a 2xx, never the reply's text. */
class ProblemReportTest {
    private val backend = FakeClaudeBackend()
    private val controller = ClaudeSessionController(
        backend,
        ClaudeWording(now = { NOW }, zone = { ZoneOffset.UTC }),
    )

    private fun report(
        note: String = "it said Tuesday, it is Wednesday",
        source: String = "voice",
        reply: Int? = null,
    ) = runBlocking { controller.reportProblem(note, source, reply, NOW) }

    @Test
    fun `a report goes to the full machine with his words verbatim and the header the worker keys on`() {
        backend.machines(machine("work laptop", scope = "listed"), machine("Alienware"))
        val result = report()
        assertTrue(result is ClaudeSendResult.Sent)
        val draft = backend.created.single()
        assertEquals("Alienware", draft.machine)
        assertEquals(ClaudeTaskDraft.ORIGIN_NAMED, draft.origin)
        assertEquals(null, draft.deliver)
        assertTrue(draft.request.startsWith("Problem report (kind: fix). Reported by voice at $NOW"))
        assertTrue(draft.request.endsWith("Kevin's words: it said Tuesday, it is Wednesday"))
    }

    @Test
    fun `filed is said, fixed never`() {
        backend.machines(machine("Alienware"))
        val sentence = report().sentence
        assertTrue(sentence.startsWith("Report filed."))
        assertTrue(sentence.contains("not fixed yet"))
        assertFalse(sentence.contains("is fixed"))
    }

    @Test
    fun `the button carries the reply position and no reply text`() {
        backend.machines(machine("Alienware"))
        report(note = "wrong amount", source = "button", reply = 2)
        val request = backend.created.single().request
        assertTrue(request.contains("Reported by button"))
        assertTrue(request.contains("reply number 3 of that conversation"))
    }

    @Test
    fun `a blank note files nothing`() {
        backend.machines(machine("Alienware"))
        val result = report(note = "   ")
        assertEquals(ProblemReport.EMPTY_NOTE, result.sentence)
        assertTrue(backend.created.isEmpty())
    }

    @Test
    fun `the button may go without a note, voice may not`() {
        backend.machines(machine("Alienware"))
        assertTrue(report(note = "", source = "button", reply = 0) is ClaudeSendResult.Sent)
        assertTrue(backend.created.single().request.endsWith(ProblemReport.BUTTON_NO_NOTE))
    }

    @Test
    fun `an unreachable engine says nothing was reported and creates nothing`() {
        backend.statusOut = ClaudeOutcome.Unreachable("down")
        val result = report()
        assertTrue(result is ClaudeSendResult.NotSent)
        assertFalse(result.sentence.startsWith("Report filed"))
        assertTrue(backend.created.isEmpty())
    }

    @Test
    fun `only the summaries-only laptop means nothing is reported`() {
        backend.machines(machine("work laptop", scope = "listed"))
        val result = report()
        assertEquals(ProblemReport.NO_FIX_MACHINE, result.sentence)
        assertTrue(backend.created.isEmpty())
    }

    @Test
    fun `a refused create is not filed`() {
        backend.machines(machine("Alienware"))
        backend.createOut = { ClaudeOutcome.Refused("no", 400) }
        val result = report()
        assertTrue(result is ClaudeSendResult.NotSent)
        assertFalse(result.sentence.startsWith("Report filed"))
    }

    @Test
    fun `an offline fix machine still queues the report and says it has not run`() {
        backend.machines(machine("Alienware", state = "offline"))
        backend.createOut = {
            ClaudeOutcome.Ok(ClaudeCreated(41, ClaudeTask.QUEUED, ClaudeMachineRef("Alienware", "offline", NOW)))
        }
        val result = report()
        assertTrue(result is ClaudeSendResult.Sent)
        assertTrue(result.sentence.contains("Nothing has run yet"))
    }
}

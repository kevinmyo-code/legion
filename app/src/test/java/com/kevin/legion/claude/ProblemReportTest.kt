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
        reply: ProblemReport.ReplyRef? = null,
    ) = runBlocking { controller.reportProblem(note, source, reply, NOW) }

    @Test
    fun `a report goes to the full machine with his words verbatim and a free-text header`() {
        backend.machines(machine("work laptop", scope = "listed"), machine("Alienware"))
        val result = report()
        assertTrue(result is ClaudeSendResult.Sent)
        val draft = backend.created.single()
        assertEquals("Alienware", draft.machine)
        assertEquals(ClaudeTaskDraft.ORIGIN_NAMED, draft.origin)
        assertEquals(null, draft.deliver)
        val header = "Problem report (a fix request, free text; the engine kind is ask)."
        assertTrue(draft.request.startsWith("$header Reported by voice at $NOW"))
        assertTrue(draft.request.endsWith("Kevin's words: it said Tuesday, it is Wednesday"))
    }

    @Test
    fun `the header says free text and a spoken report says it has no anchor`() {
        backend.machines(machine("Alienware"))
        report()
        val request = backend.created.single().request
        assertTrue(request.contains("free text; the engine kind is ask"))
        assertTrue(request.contains("No reply anchor (spoken report)"))
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
    fun `the button carries the reply time and hash and no reply text`() {
        backend.machines(machine("Alienware"))
        val text = "You owe the landlord 1200 dollars."
        val ref = ProblemReport.ReplyRef(1_700_000_000_000L, ProblemReport.hashOf(text))
        report(note = "wrong amount", source = "button", reply = ref)
        val request = backend.created.single().request
        assertTrue(request.contains("Reported by button"))
        assertTrue(request.contains("added 2023-11-14T22:13:20Z, text hash ${ref.hash}"))
        assertTrue(request.contains("do not pick the newest record"))
        assertFalse(request.contains("landlord"))
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
        assertTrue(report(note = "", source = "button", reply = null) is ClaudeSendResult.Sent)
        assertTrue(backend.created.single().request.endsWith(ProblemReport.BUTTON_NO_NOTE))
    }

    @Test
    fun `an unreachable engine says nothing was reported and creates nothing`() {
        backend.statusOut = ClaudeOutcome.Unreachable("down")
        val result = report()
        assertEquals(ClaudeWording.failed(backend.statusOut, "report it"), result.sentence)
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
        assertEquals(ClaudeWording.failed(ClaudeOutcome.Refused("no", 400), "send it"), result.sentence)
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

package com.kevin.legion.backend.engine

import com.kevin.legion.backend.engine.EngineTestSupport.json
import com.kevin.legion.claude.ClaudeOutcome
import com.kevin.legion.claude.ClaudeSendResult
import com.kevin.legion.claude.ClaudeSessionController
import com.kevin.legion.claude.ClaudeTask
import com.kevin.legion.claude.ClaudeTaskDraft
import com.kevin.legion.claude.ClaudeWording
import com.kevin.legion.claude.ClaudeWorkflowWording
import com.kevin.legion.claude.FakeClaudeBackend
import com.kevin.legion.claude.FakeClaudeBackend.Companion.NOW
import com.kevin.legion.claude.FakeClaudeBackend.Companion.machine
import com.kevin.legion.claude.FakeClaudeBackend.Companion.task
import com.kevin.legion.claude.FakeSeen
import com.kevin.legion.claude.FakeWatch
import com.kevin.legion.claude.ScheduledQuestionPoller
import com.kevin.legion.memory.MemoryOutcome
import com.kevin.legion.openloops.LoopsView
import com.kevin.legion.openloops.OpenLoopsBrief
import com.kevin.legion.openloops.OpenLoopsController
import com.kevin.legion.service.OpenLoopsToolbox
import com.kevin.legion.watches.MapSaidFirings
import com.kevin.legion.watches.WatchDelivery
import com.kevin.legion.watches.WatchFiringPoller
import com.kevin.legion.watches.WatchOutcome
import com.kevin.legion.watches.WatchWording
import com.kevin.legion.watches.WatchesController
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Integrate 2026-11-10, check 4: this phone build installed BEFORE the engine is deployed, against
 * the round 4 engine, which has no `/api/watches/`, `/api/open-loops/` or `claude/workflows/`
 * route, ignores `origin=schedule` on `tasks/`, and sends no `workflows` field in `status/`.
 *
 * Each missing route answers what Django answers with DEBUG off: 404 with its own HTML page. Each
 * path must fail quietly (nothing spoken, nothing raised, no crash, at most one call per poll
 * interval) and every screen or tool result must say "not available yet" in words, never the HTML
 * and never "none". The real Django backends run over [EngineTestSupport.RecordingEngine].
 */
@RunWith(RobolectricTestRunner::class)
class NewPhoneOldEngineTest {

    private val context = RuntimeEnvironment.getApplication()

    /** The round 4 engine: every new route is Django's 404 page; `status/` has no workflows key. */
    private val oldEngine = EngineTestSupport.RecordingEngine { request ->
        val path = request.url.encodedPath
        when {
            path.endsWith("/api/claude/status/") -> json(OLD_STATUS)
            path.endsWith("/api/claude/tasks/") -> json(OLD_TASKS)
            else -> respond(DJANGO_404, HttpStatusCode.NotFound, headersOf(HttpHeaders.ContentType, "text/html"))
        }
    }

    private fun http() = EngineHttp(EngineTestSupport.signedInConfig(context), oldEngine.client())

    @After
    fun tearDown() {
        OpenLoopsToolbox.controllerFactory = OpenLoopsController::forContext
        OpenLoopsController.Hint.pending = null
    }

    private fun assertNoHtml(text: String?) {
        assertFalse("raw HTML reached words: $text", text.orEmpty().contains("<"))
    }

    // ------------------------------------------------------------------ watches

    @Test
    fun `the watch firings poll says nothing, acks nothing, and asks once per interval`() = runBlocking {
        var now = 5_000_000L
        val announced = mutableListOf<Int>()
        val poller = WatchFiringPoller(
            controller = { WatchesController(DjangoWatchesBackend(http())) },
            announce = { firings, _ -> announced += firings.size; WatchDelivery.SPOKEN },
            said = MapSaidFirings(),
            clockMs = { now },
        )
        assertEquals(0, poller.maybePoll())
        assertEquals(0, poller.maybePoll())
        now += WatchFiringPoller.POLL_INTERVAL_MS - 1
        assertEquals(0, poller.maybePoll())
        assertTrue("nothing spoken or raised", announced.isEmpty())
        assertEquals("one GET, no ack, no retry storm", 1, oldEngine.requests.size)
        assertEquals("GET", oldEngine.requests.single().method.value)
        now += 1
        poller.maybePoll()
        assertEquals("the next interval asks again, once", 2, oldEngine.requests.size)
    }

    @Test
    fun `the Watches screen and the voice list say watches are not available yet`() = runBlocking {
        val out = WatchesController(DjangoWatchesBackend(http())).list()
        assertTrue(out is WatchOutcome.Refused)
        // What WatchesViewModel draws as Unavailable, and what the voice `watch` list returns.
        val words = WatchWording.failed(out, "read")
        assertEquals(WatchWording.NOT_ON_ENGINE, words)
        assertNoHtml(words)
        assertFalse(words.contains("No watches"))
    }

    @Test
    fun `a 404 for a missing watch row is still the engine's own sentence`() = runBlocking {
        val engine = EngineTestSupport.RecordingEngine {
            json("""{"detail": "There is no such watch."}""", HttpStatusCode.NotFound)
        }
        val out = DjangoWatchesBackend(EngineHttp(EngineTestSupport.signedInConfig(context), engine.client()))
            .update("w-9", kotlinx.serialization.json.JsonObject(emptyMap()))
        assertEquals("There is no such watch.", (out as WatchOutcome.Refused).sentence)
    }

    // ------------------------------------------------------------------ workflows

    @Test
    fun `the Workflows section says not available yet, never none and never HTML`() = runBlocking {
        val out = DjangoClaudeSessionBackend(http()).workflows()
        assertTrue(out is ClaudeOutcome.Refused)
        assertEquals(ClaudeWording.NOT_ON_ENGINE, (out as ClaudeOutcome.Refused).sentence)
        val note = ClaudeWording.failed(out, "read the workflows")
        assertEquals(
            "Not available yet: the engine has not been updated for this, so I couldn't read the workflows.",
            note,
        )
        assertNoHtml(note)
    }

    @Test
    fun `old status has no workflows field, so a voice workflow is refused before anything is queued`() =
        runBlocking {
            val backend = DjangoClaudeSessionBackend(http())
            val status = backend.status()
            assertTrue(status is ClaudeOutcome.Ok)
            assertFalse((status as ClaudeOutcome.Ok).value.workflowsKnown)
            val controller = ClaudeSessionController(backend, onSent = {})
            val out = controller.runWorkflow("run the weekly digest", machine = null)
            assertTrue(out is ClaudeSendResult.NotSent)
            assertEquals(ClaudeWorkflowWording.NOT_ON_ENGINE, out.sentence)
            assertEquals(
                "only status/ was asked (once by the test, once by runWorkflow); no task was posted",
                listOf("/api/claude/status/", "/api/claude/status/"),
                oldEngine.requests.map { it.url.encodedPath },
            )
        }

    @Test
    fun `a new engine's status with an empty workflows list still counts as knowing workflows`() = runBlocking {
        val engine = EngineTestSupport.RecordingEngine { json(NEW_STATUS_NO_WORKFLOWS) }
        val status = DjangoClaudeSessionBackend(EngineHttp(EngineTestSupport.signedInConfig(context), engine.client()))
            .status()
        assertTrue((status as ClaudeOutcome.Ok).value.workflowsKnown)
    }

    @Test
    fun `the scheduled-question poller never raises his own questions from an old engine's list`() = runBlocking {
        // The old engine ignores origin=schedule: it lists every needs_confirmation task.
        val fake = FakeClaudeBackend()
        fake.machines(machine("Alienware"))
        fake.whereOut = ClaudeOutcome.Ok(
            listOf(
                task(9, ClaudeTask.NEEDS_CONFIRMATION, question = "Delete the old exports?", origin = "named"),
                task(10, ClaudeTask.NEEDS_CONFIRMATION, question = "Move the photos?"),
            ),
        )
        val raised = mutableListOf<Int>()
        val poller = ScheduledQuestionPoller(
            controller = { ClaudeSessionController(fake, onSent = {}) },
            seen = FakeSeen(),
            watch = FakeWatch(),
            announce = { id, _, _ -> raised += id; com.kevin.legion.claude.ClaudeDelivery.DELIVERED },
            now = { NOW },
        )
        assertEquals(0, poller.pollNow())
        assertTrue("nothing raised", raised.isEmpty())
    }

    @Test
    fun `an old engine's task rows decode, and its ignored origin filter raises nothing`() = runBlocking {
        val backend = DjangoClaudeSessionBackend(http())
        val c = ClaudeSessionController(backend, onSent = {})
        val listed = c.scheduledQuestions()
        assertTrue("old rows (no workflow, no params) still decode: $listed", listed is ClaudeOutcome.Ok)
        val row = (listed as ClaudeOutcome.Ok).value.single()
        assertNull(row.workflow)
        assertFalse(row.isScheduled)
        val raised = mutableListOf<Int>()
        val poller = ScheduledQuestionPoller(
            controller = { c },
            seen = FakeSeen(),
            watch = FakeWatch(),
            announce = { id, _, _ -> raised += id; com.kevin.legion.claude.ClaudeDelivery.DELIVERED },
            now = { NOW },
        )
        assertEquals(0, poller.pollNow())
        assertTrue(raised.isEmpty())
        // The Claude tasks screen's own list reads the same rows.
        assertTrue(backend.tasks(20) is ClaudeOutcome.Ok)
    }

    @Test
    fun `a scheduled question with no workflow name is not said as Scheduled A scheduled task`() {
        val q = task(
            11, ClaudeTask.NEEDS_CONFIRMATION, question = "Send it?", origin = ClaudeTaskDraft.ORIGIN_SCHEDULE,
        )
        assertEquals(
            "A scheduled run on the Alienware (task 11) stopped to ask: Send it?",
            ClaudeWorkflowWording.scheduledQuestion(q),
        )
    }

    // ------------------------------------------------------------------ open loops and the brief line

    private fun loops() = OpenLoopsController(backend = { DjangoOpenLoopsBackend(http()) })

    @Test
    fun `the brief line is silent, and the Brief modal says not available yet`() = runBlocking {
        val c = loops()
        assertNull("nothing spoken in the wake brief or get_sitrep", c.voiceLine())
        assertEquals(OpenLoopsBrief.NOT_ON_ENGINE, c.screenLine())
        val view = c.view()
        assertTrue(view is LoopsView.Unavailable && view.notYet)
        assertEquals(OpenLoopsBrief.NOT_ON_ENGINE, (view as LoopsView.Unavailable).sentence)
    }

    @Test
    fun `an ordinary add stays an ordinary add, and later adds stop asking the old engine`() = runBlocking {
        OpenLoopsToolbox.controllerFactory = { loops() }
        val add = JSONObject("""{"action":"add","item":"buy lightbulbs","list":"Todo"}""")
        assertNull(OpenLoopsToolbox.dispatch(context, "manage_item", add, false))
        val asked = oldEngine.requests.size
        assertEquals(1, asked)
        assertNull(OpenLoopsToolbox.dispatch(context, "manage_item", add, false))
        assertEquals("no second GET per add", asked, oldEngine.requests.size)
    }

    @Test
    fun `turning proposals off against the old engine says not available and reports nothing done`() =
        runBlocking {
            val answer = loops().setProposing(false)
            assertFalse(answer.ok)
            assertEquals(OpenLoopsBrief.NOT_ON_ENGINE, answer.words)
            assertTrue(
                (DjangoOpenLoopsBackend(http()).list() as MemoryOutcome.Refused).sentence ==
                    OpenLoopsBrief.NOT_ON_ENGINE,
            )
        }

    private companion object {
        /** Django's default 404 page with DEBUG off (the shape; exact markup does not matter). */
        const val DJANGO_404 = """<!doctype html>
<html lang="en">
<head>
  <title>Not Found</title>
</head>
<body>
  <h1>Not Found</h1><p>The requested resource was not found on this server.</p>
</body>
</html>"""

        const val OLD_STATUS = """{"machines": [{"name": "Alienware", "scope": "full", "state": "idle",
            "last_seen_at": "2026-11-10T12:00:00Z", "running": [], "progress": []}], "sessions": []}"""

        /** `legion-deploy` (round 4) `claude_session/views.py` `_task_row`: no workflow, no params. */
        const val OLD_TASKS = """[{"id": 9, "request": "tidy the downloads folder", "machine": "Alienware",
            "status": "needs_confirmation", "summary": null, "question": "Delete the old exports?",
            "created_at": "2026-11-10T11:00:00Z", "finished_at": "2026-11-10T11:05:00Z", "files": [],
            "artifacts": [], "parent": null, "origin": "named", "kind": "ask", "deliver": null,
            "confirms": false, "progress": null, "progress_at": null, "steps": []}]"""

        const val NEW_STATUS_NO_WORKFLOWS = """{"machines": [{"name": "Alienware", "scope": "full",
            "state": "idle", "last_seen_at": "2026-11-10T12:00:00Z", "running": [], "progress": [],
            "workflows": []}], "sessions": []}"""
    }
}

package com.kevin.legion.claude

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.kevin.legion.browser.LoadOutcome
import com.kevin.legion.claude.FakeClaudeBackend.Companion.NOW
import com.kevin.legion.claude.FakeClaudeBackend.Companion.machine
import com.kevin.legion.claude.FakeClaudeBackend.Companion.task
import com.kevin.legion.service.ClaudeSessionToolbox
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * HTML delivery (claude-session ticket 09, Kevin 2026-11-03): a `page` task is sent as
 * `deliver: phone` and never to the work laptop; its result waits briefly for the page, then says
 * it is ready and asks before opening it; a yes (`open_page`) opens the task's FRESH link in
 * LEGION's browser and says "opened" only when the page loaded.
 */
@RunWith(RobolectricTestRunner::class)
class ClaudePageDeliveryTest {
    private val backend = FakeClaudeBackend()
    private val watch = FakeWatch()
    private val said = mutableListOf<String>()
    private val hints = mutableListOf<String>()
    private var clock: Instant = NOW
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val opened = mutableListOf<String>()
    private val realOpen = ClaudePageOpener.browserOpen

    private val watcher = ClaudeResultWatcher(
        controller = { ClaudeSessionController(backend) },
        watch = watch,
        announce = { _, text, hint -> said += text; hints += hint; ClaudeDelivery.DELIVERED },
        saveFile = { _, _ -> true },
        now = { clock },
    )

    private val page =
        ClaudePage(1410, "atlanta.html", "Atlanta day trip", 9_000, "https://engine.test/api/claude/pages/1410/?t=a")
    private val pageFile = ClaudeTaskFile(1410, "atlanta.html", 9_000, uploaded = false, requested = true)

    private fun pageTask(pages: List<ClaudePage>, uploaded: Boolean = pages.isNotEmpty()) =
        task(56, ClaudeTask.DONE, summary = "Your Atlanta day trip is ready.",
            files = listOf(pageFile.copy(uploaded = uploaded)))
            .copy(deliver = ClaudeTaskDraft.DELIVER_PHONE, finishedAt = NOW, pages = pages)

    private fun openPage(id: Int) = JSONObject().put("action", "open_page").put("task_id", id)

    @After
    fun tearDown() {
        ClaudeSessionToolbox.controllerFactory = ClaudeSessionController::forContext
        ClaudePageOpener.browserOpen = realOpen
    }

    @Test
    fun `a page request is sent as deliver phone`() {
        backend.machines(machine("Alienware"))
        val out = runBlocking {
            ClaudeSessionController(backend)
                .send("an Atlanta day trip itinerary", null, "offered", emailMe = false, page = true)
        }
        assertTrue(out is ClaudeSendResult.Sent)
        assertEquals(ClaudeTaskDraft.DELIVER_PHONE, backend.created.single().deliver)
    }

    @Test
    fun `the work laptop never gets a page request`() {
        backend.machines(machine("work laptop", scope = "listed"))
        val out = runBlocking {
            ClaudeSessionController(backend)
                .send("an itinerary", "work laptop", "named", emailMe = false, page = true)
        }
        assertTrue(out is ClaudeSendResult.NotSent)
        assertTrue((out as ClaudeSendResult.NotSent).sentence.contains("summaries only"))
        assertTrue(backend.created.isEmpty())
    }

    @Test
    fun `a ready page is announced with an ask, and the yes is open_page`() {
        backend.machines(machine("Alienware"))
        watch.tasks += 56
        backend.tasksOut = ClaudeOutcome.Ok(listOf(pageTask(listOf(page))))
        runBlocking { watcher.pollOnce() }
        val text = said.single()
        assertTrue(text, text.contains("The page \"Atlanta day trip\" is ready. Want me to open it?"))
        assertFalse("the page is not offered as a file too: $text", text.contains("Want it on your phone?"))
        assertEquals("On a yes: claude_session open_page, task_id 56.", hints.single())
    }

    @Test
    fun `a page still on its way holds the result, then says it did not come`() {
        backend.machines(machine("Alienware"))
        watch.tasks += 56
        backend.tasksOut = ClaudeOutcome.Ok(listOf(pageTask(emptyList(), uploaded = false)))
        clock = NOW.plusSeconds(30)
        runBlocking { watcher.pollOnce() }
        assertTrue(said.isEmpty())
        assertEquals(setOf(56), watch.tasks)
        clock = NOW.plus(ClaudeResultWatcher.PAGE_WAIT).plus(Duration.ofSeconds(1))
        runBlocking { watcher.pollOnce() }
        assertTrue(said.single(), said.single().contains("The page has not reached your phone yet"))
        assertEquals("", hints.single())
    }

    @Test
    fun `open_page opens the fresh link and says opened only when it loaded`() {
        backend.taskById[56] = pageTask(listOf(page))
        ClaudeSessionToolbox.controllerFactory = { ClaudeSessionController(backend) }
        ClaudePageOpener.browserOpen = { _, url -> opened += url; LoadOutcome.Loaded(url, "Atlanta day trip") }
        val ok = runBlocking {
            ClaudeSessionToolbox.dispatch(context, "claude_session", openPage(56))
        }!!
        assertTrue(ok.toString(), ok.getBoolean("success"))
        assertEquals(listOf(page.url), opened)
        assertTrue(ok.getString("message").startsWith("Opened"))

        ClaudePageOpener.browserOpen = { _, _ -> LoadOutcome.TimedOut }
        val slow = runBlocking {
            ClaudeSessionToolbox.dispatch(context, "claude_session", openPage(56))
        }!!
        assertFalse(slow.getBoolean("success"))
        assertFalse(slow.getString("message").startsWith("Opened"))
    }

    @Test
    fun `open_page on a task with no page opens nothing`() {
        backend.taskById[57] = task(57, ClaudeTask.DONE, summary = "done")
        ClaudeSessionToolbox.controllerFactory = { ClaudeSessionController(backend) }
        ClaudePageOpener.browserOpen = { _, url -> opened += url; LoadOutcome.Loaded(url, "x") }
        val out = runBlocking {
            ClaudeSessionToolbox.dispatch(context, "claude_session", openPage(57))
        }!!
        assertFalse(out.getBoolean("success"))
        assertTrue(opened.isEmpty())
    }

    @Test
    fun `the page tool flag reaches the controller`() {
        backend.machines(machine("Alienware"))
        ClaudeSessionToolbox.controllerFactory = { ClaudeSessionController(backend) }
        runBlocking {
            ClaudeSessionToolbox.dispatch(
                context,
                "claude_session",
                JSONObject().put("action", "send").put("request", "Atlanta day trip")
                    .put("offered", true).put("page", true),
            )
        }
        assertEquals(ClaudeTaskDraft.DELIVER_PHONE, backend.created.single().deliver)
        assertEquals(ClaudeTaskDraft.ORIGIN_OFFERED, backend.created.single().origin)
    }
}

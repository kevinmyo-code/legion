package com.kevin.legion.claude

import com.kevin.legion.claude.FakeClaudeBackend.Companion.machine
import com.kevin.legion.claude.FakeClaudeBackend.Companion.task
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ClaudeResultWatcher]: each finished task this phone sent is said once, a "not now" waits for
 * the next poll, the silence switch stops every line, and a requested file is fetched only once
 * the laptop has uploaded it.
 */
class ClaudeResultWatcherTest {
    private val backend = FakeClaudeBackend()
    private val watch = FakeWatch()
    private val said = mutableListOf<Pair<Int, String>>()
    private val hints = mutableListOf<String>()
    private var delivery = ClaudeDelivery.DELIVERED
    private val saved = mutableListOf<String>()
    private val watcher = ClaudeResultWatcher(
        controller = { ClaudeSessionController(backend) },
        watch = watch,
        announce = { id, text, hint -> said += id to text; hints += hint; delivery },
        saveFile = { name, _ -> saved += name; true },
    )

    private fun tasks(vararg t: ClaudeTask) {
        backend.tasksOut = ClaudeOutcome.Ok(t.toList())
    }

    @Test
    fun `nothing watched asks the engine nothing`() {
        backend.statusOut = ClaudeOutcome.Unreachable("would fail if asked")
        assertEquals(0, runBlocking { watcher.pollOnce() })
        assertTrue(said.isEmpty())
    }

    @Test
    fun `a finished task is said once with its found file offered, then forgotten`() {
        backend.machines(machine("Alienware"))
        watch.tasks += 41
        tasks(task(41, ClaudeTask.DONE, summary = "Found it, AW4 wiring rev C, in Downloads.",
            files = listOf(ClaudeTaskFile(9, "AW4 wiring rev C.pdf", 2_100_000, false, false))))
        runBlocking { watcher.pollOnce(); watcher.pollOnce() }
        assertEquals(1, said.size)
        val text = said.single().second
        assertTrue(text, text.contains("is done. Found it, AW4 wiring rev C"))
        assertTrue(text, text.contains("AW4 wiring rev C.pdf (2.0 MB)"))
        assertTrue(text, text.endsWith("Want it on your phone?"))
        assertTrue(watch.tasks.isEmpty())
        // The yes needs ids the user never hears: they ride in the hint, for the model only.
        assertEquals(
            "On a yes: claude_session send_file, task_id 41, file_id 9 (AW4 wiring rev C.pdf).",
            hints.single(),
        )
        assertTrue(!text.contains("file_id"))
    }

    /** A check read the result out (and forgot the task) while this poll waited on the engine. */
    @Test
    fun `a task forgotten while the poll was reading is not announced`() {
        backend.machines(machine("Alienware"))
        watch.tasks += 41
        tasks(task(41, ClaudeTask.DONE, summary = "Done."))
        val racing = ClaudeResultWatcher(
            controller = { ClaudeSessionController(backend) },
            watch = watch,
            announce = { id, text, hint -> said += id to text; hints += hint; delivery },
            saveFile = { _, _ -> true },
            onOverview = { watch.forgetTask(41) },
        )
        assertEquals(0, runBlocking { racing.pollOnce() })
        assertTrue(said.isEmpty())
    }

    @Test
    fun `a running task is not announced`() {
        backend.machines(machine("Alienware"))
        watch.tasks += 41
        tasks(task(41, ClaudeTask.RUNNING, progress = "reading Downloads"))
        runBlocking { watcher.pollOnce() }
        assertTrue(said.isEmpty())
        assertEquals(setOf(41), watch.tasks)
    }

    @Test
    fun `not now keeps it for the next poll`() {
        backend.machines(machine("Alienware"))
        watch.tasks += 41
        tasks(task(41, ClaudeTask.FAILED, summary = "Stopped: it ran past the 30 minute limit."))
        delivery = ClaudeDelivery.RETRY
        runBlocking { watcher.pollOnce() }
        assertEquals(setOf(41), watch.tasks)
        delivery = ClaudeDelivery.DELIVERED
        runBlocking { watcher.pollOnce() }
        assertTrue(watch.tasks.isEmpty())
        assertEquals(2, said.size)
    }

    @Test
    fun `silenced says nothing and stops watching`() {
        backend.machines(machine("Alienware"))
        watch.tasks += 41
        watch.quiet = true
        tasks(task(41, ClaudeTask.DONE, summary = "done"))
        runBlocking { watcher.pollOnce() }
        assertTrue(said.isEmpty())
        assertTrue(watch.tasks.isEmpty())
    }

    @Test
    fun `the work laptop's result offers no file`() {
        backend.machines(machine("work laptop", scope = "listed"))
        watch.tasks += 7
        tasks(task(7, ClaudeTask.DONE, machine = "work laptop", summary = "Three repos use the old API.",
            files = listOf(ClaudeTaskFile(3, "notes.md", 10, false, false))))
        runBlocking { watcher.pollOnce() }
        val text = said.single().second
        assertTrue(text, !text.contains("phone"))
        assertEquals("a summaries-only result asks nothing, so the mic stays closed", "", hints.single())
    }

    @Test
    fun `a requested file is saved only after it is uploaded`() {
        backend.machines(machine("Alienware"))
        watch.files += WatchedFile(41, 9, "AW4 wiring rev C.pdf")
        tasks(task(41, ClaudeTask.DONE, files = listOf(ClaudeTaskFile(9, "AW4 wiring rev C.pdf", 3, false, true))))
        runBlocking { watcher.pollOnce() }
        assertTrue(backend.downloads.isEmpty())
        tasks(task(41, ClaudeTask.DONE, files = listOf(ClaudeTaskFile(9, "AW4 wiring rev C.pdf", 3, true, true))))
        runBlocking { watcher.pollOnce() }
        assertEquals(listOf(41 to 9), backend.downloads)
        assertEquals(listOf("AW4 wiring rev C.pdf"), saved)
        assertEquals("AW4 wiring rev C.pdf is on your phone, in Downloads.", said.single().second)
        assertTrue(watch.files.isEmpty())
    }

    @Test
    fun `a question's hint names the yes, and a plain result has none`() {
        val asks = task(42, ClaudeTask.NEEDS_CONFIRMATION, question = "Email the sheet to Dad?")
        assertEquals("On a yes: claude_session answer, task_id 42, yes true.", ClaudeResultWatcher.hint(asks, false))
        assertEquals("", ClaudeResultWatcher.hint(task(43, ClaudeTask.FAILED), false))
    }
}

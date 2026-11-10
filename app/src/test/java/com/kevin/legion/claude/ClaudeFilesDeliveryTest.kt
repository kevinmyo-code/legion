package com.kevin.legion.claude

import com.kevin.legion.claude.FakeClaudeBackend.Companion.NOW
import com.kevin.legion.claude.FakeClaudeBackend.Companion.machine
import com.kevin.legion.claude.FakeClaudeBackend.Companion.task
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Files to phone (ADR 0080): a `files` send is `deliver: files` and never to the work laptop; the
 * requested files are fetched once each is up, kept, and the engine told it may delete its copy;
 * once nothing more is coming they are opened (or notified) and the result says which landed. No
 * question, no Downloads. Made-up file names only.
 */
class ClaudeFilesDeliveryTest {
    private val backend = FakeClaudeBackend()
    private val watch = FakeWatch()
    private val said = mutableListOf<String>()
    private val opens = mutableListOf<Int>()
    private val savedToDownloads = mutableListOf<String>()
    private var inFront = true
    private var delivery = ClaudeDelivery.DELIVERED
    private var clock: Instant = NOW
    private val shelf = FakeShelf()

    private val watcher = ClaudeResultWatcher(
        controller = { ClaudeSessionController(backend) },
        watch = watch,
        announce = { _, text, _ -> said += "announce: $text"; ClaudeDelivery.DELIVERED },
        saveFile = { name, _ -> savedToDownloads += name; true },
        now = { clock },
        shelf = shelf,
        openFiles = { id -> opens += id; inFront },
        filesReady = { _, text -> said += text; delivery },
    )

    private val scan = ClaudeTaskFile(91, "form-scan.pdf", 1_200_000, uploaded = true, requested = true, contentType = "application/pdf")
    private val photo = ClaudeTaskFile(92, "photo-1.jpg", 300_000, uploaded = true, requested = true, contentType = "image/jpeg")

    private fun filesTask(vararg files: ClaudeTaskFile) =
        task(58, ClaudeTask.DONE, summary = "Found the form and the photo.", files = files.toList(),
            deliver = ClaudeTaskDraft.DELIVER_FILES, finishedAt = NOW,
            request = "find the scanned form and the photo and send them to my phone")

    private fun serve(t: ClaudeTask) {
        backend.machines(machine("Alienware"))
        backend.tasksOut = ClaudeOutcome.Ok(listOf(t))
    }

    @Test
    fun `a files request is sent as deliver files`() {
        backend.machines(machine("Alienware"))
        val out = runBlocking {
            ClaudeSessionController(backend).send("send me the form scan", null, "named", emailMe = false, files = true)
        }
        assertTrue(out is ClaudeSendResult.Sent)
        assertEquals(ClaudeTaskDraft.DELIVER_FILES, backend.created.single().deliver)
    }

    @Test
    fun `the work laptop never gets a files request`() {
        backend.machines(machine("work laptop", scope = "listed"))
        val out = runBlocking {
            ClaudeSessionController(backend).send("send me a file", "work laptop", "named", emailMe = false, files = true)
        }
        assertTrue(out is ClaudeSendResult.NotSent)
        assertTrue((out as ClaudeSendResult.NotSent).sentence.contains("can't send files"))
        assertTrue(backend.created.isEmpty())
    }

    @Test
    fun `the files land privately, the engine copy goes, they open, and the result says so once`() {
        watch.tasks += 58
        serve(filesTask(scan, photo))
        runBlocking { watcher.pollOnce(); watcher.pollOnce() }
        assertEquals(listOf(58 to 91, 58 to 92), backend.downloads)
        assertEquals(setOf(91, 92), shelf.kept.keys)
        assertEquals(listOf(58 to 91, 58 to 92), backend.receipts)
        assertEquals(listOf(58), opens)
        val text = said.single()
        assertTrue(text, text.contains("is done. Found the form and the photo."))
        assertTrue(text, text.contains("On your phone: form-scan.pdf (1.1 MB), photo-1.jpg (292 KB)."))
        assertTrue(text, text.contains("They are open on the screen now."))
        assertFalse("no question: naming them was the yes ($text)", text.contains("?"))
        assertTrue("never Downloads", savedToDownloads.isEmpty())
        assertTrue(watch.tasks.isEmpty())
    }

    @Test
    fun `out of the app the result points at the notification`() {
        inFront = false
        watch.tasks += 58
        serve(filesTask(scan))
        runBlocking { watcher.pollOnce() }
        assertEquals(listOf(58), opens)
        assertTrue(said.single(), said.single().contains("Tap the notification to open it."))
    }

    @Test
    fun `a file still on its way holds the result, the first is fetched meanwhile`() {
        watch.tasks += 58
        serve(filesTask(scan, photo.copy(uploaded = false)))
        clock = NOW.plusSeconds(30)
        runBlocking { watcher.pollOnce() }
        assertTrue(said.isEmpty())
        assertEquals(setOf(91), shelf.kept.keys)
        assertEquals(listOf(58 to 91), backend.receipts)
        assertTrue(opens.isEmpty())

        clock = NOW.plus(ClaudeResultWatcher.FILES_WAIT).plusSeconds(1)
        runBlocking { watcher.pollOnce() }
        val text = said.single()
        assertTrue(text, text.contains("On your phone: form-scan.pdf"))
        assertTrue(text, text.contains("Did not reach the phone: photo-1.jpg. Ask again to retry."))
    }

    @Test
    fun `a download that fails is retried on the next poll and nothing is received`() {
        watch.tasks += 58
        serve(filesTask(scan))
        backend.downloadOut = ClaudeOutcome.Unreachable("no signal")
        runBlocking { watcher.pollOnce() }
        assertTrue(backend.receipts.isEmpty())
        assertTrue(said.isEmpty())
        backend.downloadOut = ClaudeOutcome.Ok(byteArrayOf(7))
        runBlocking { watcher.pollOnce() }
        assertEquals(listOf(58 to 91), backend.receipts)
        assertEquals(1, said.size)
    }

    @Test
    fun `a kept file whose receipt failed is receipted again, never downloaded again`() {
        watch.tasks += 58
        shelf.kept[91] = byteArrayOf(1)
        delivery = ClaudeDelivery.RETRY
        serve(filesTask(scan))
        runBlocking { watcher.pollOnce(); watcher.pollOnce() }
        assertTrue(backend.downloads.isEmpty())
        assertEquals(listOf(58 to 91, 58 to 91), backend.receipts)
        // "Not now" keeps it watched, and the viewer is not opened a second time.
        assertEquals(listOf(58), opens)
        assertEquals(setOf(58), watch.tasks)
    }

    @Test
    fun `silenced still brings and opens the files, and says nothing`() {
        watch.quiet = true
        watch.tasks += 58
        serve(filesTask(scan))
        runBlocking { watcher.pollOnce() }
        assertEquals(setOf(91), shelf.kept.keys)
        assertEquals(listOf(58), opens)
        assertTrue(said.isEmpty())
        assertTrue(watch.tasks.isEmpty())
    }

    @Test
    fun `a file the engine already deleted is reported, not waited for`() {
        watch.tasks += 58
        serve(filesTask(scan.copy(gone = true)))
        runBlocking { watcher.pollOnce() }
        assertTrue(backend.downloads.isEmpty())
        assertTrue(opens.isEmpty())
        assertTrue(said.single(), said.single().contains("Did not reach the phone: form-scan.pdf"))
    }

    @Test
    fun `a files task that asked first is a question like any other, the yes is answer`() {
        watch.tasks += 58
        serve(
            filesTask(scan.copy(requested = false, uploaded = false)).copy(
                status = ClaudeTask.NEEDS_CONFIRMATION,
                question = "I found 21 files, 4 MB in all. Send them all to your phone?",
            ),
        )
        runBlocking { watcher.pollOnce() }
        assertTrue(said.single(), said.single().startsWith("announce: "))
        assertTrue(said.single().contains("Send them all to your phone?"))
        assertEquals(
            "On a yes: claude_session answer, task_id 58, yes true.",
            ClaudeResultWatcher.hint(backend.tasksOutValue().single(), summariesOnly = false),
        )
    }

    @Test
    fun `requested files are never offered for send_file again`() {
        val t = filesTask(scan.copy(uploaded = false), ClaudeTaskFile(93, "other.txt", 10, uploaded = false, requested = false))
        val hint = ClaudeResultWatcher.hint(t, summariesOnly = false)
        assertTrue(hint, hint.contains("file_id 93"))
        assertFalse(hint, hint.contains("file_id 91"))
        val result = ClaudeWording().result(t, summariesOnly = false)
        assertTrue(result, result.contains("Coming to your phone: form-scan.pdf"))
        assertFalse(result, result.contains("form-scan.pdf (1.1 MB). Want it"))
    }

    private fun FakeClaudeBackend.tasksOutValue(): List<ClaudeTask> = (tasksOut as ClaudeOutcome.Ok).value
}

/** An in-memory [ClaudeFileShelf]. */
class FakeShelf : ClaudeFileShelf {
    val kept = mutableMapOf<Int, ByteArray>()
    var refuse = false
    override fun has(taskId: Int, fileId: Int) = fileId in kept
    override suspend fun keep(taskId: Int, fileId: Int, name: String, bytes: ByteArray): Boolean {
        if (refuse) return false
        kept[fileId] = bytes
        return true
    }
}

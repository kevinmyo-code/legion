package com.kevin.legion.service.phonelog

import com.kevin.legion.service.conversationlog.Backoff
import com.kevin.legion.service.conversationlog.ConversationSpool
import com.kevin.legion.service.conversationlog.ConversationTransport
import com.kevin.legion.service.conversationlog.ConversationUploader
import com.kevin.legion.service.conversationlog.DailyBudget
import com.kevin.legion.service.conversationlog.PostResult
import com.kevin.legion.service.conversationlog.SendOutcome
import com.kevin.legion.service.conversationlog.UploadMeta
import java.io.ByteArrayInputStream
import java.util.TimeZone
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The pieces `PhoneLog` wires together (2026-11-09): the line helpers, the run state, the daily
 * cap, the spool's bulk append, and the shared uploader sending `logcat` with a run id.
 */
class PhoneLogPartsTest {
    @get:Rule val tmp = TemporaryFolder()

    private val l1 = "11-09 14:00:00.000  4242  4300 I View    : setRequestedFrameRate frameRate=0.0"
    private val l2 = "11-09 14:00:00.001  4242  4300 I LIVE_AUDIT: turn_complete"

    // ---- LogcatLines

    @Test fun `line helpers read pid stamp and tag`() {
        assertEquals("4242", LogcatLines.pidOf(l1))
        assertEquals("11-09 14:00:00.000", LogcatLines.stampOf(l1))
        assertEquals("View", LogcatLines.tagOf(l1))
        assertNull(LogcatLines.pidOf("--------- beginning of main"))
    }

    @Test fun `ours keeps a set of pids and newSince skips through the anchor's last occurrence`() {
        val other = "11-09 14:00:00.002  1  1 I X: y"
        assertEquals(listOf(l1, l2), LogcatLines.ours(listOf(l1, other, l2), setOf("4242")))
        assertEquals(listOf(l2), LogcatLines.newSince(listOf(l1, l2), l1))
        assertEquals(listOf(l1, l2), LogcatLines.newSince(listOf(l1, l2), "gone"))
        assertEquals(listOf(l1, l2), LogcatLines.newSince(listOf(l1, l2), null))
    }

    @Test fun `nothing is noise by default and a noise entry drops by tag and prefix`() {
        assertFalse(LogcatLines.isNoise(l1))
        val noise = listOf("View" to "setRequestedFrameRate")
        assertTrue(LogcatLines.isNoise(l1, noise))
        assertFalse(LogcatLines.isNoise(l2, noise))
    }

    // ---- RunState

    @Test fun `run state survives a restart and a new run clears the anchor`() {
        val file = tmp.newFile("run.properties")
        RunState(file).apply {
            startRun(1111, "run-a")
            captured(l2, 5_000)
            exitSeen(4_000)
        }
        val read = RunState(file).read()
        assertEquals("1111", read.pid)
        assertEquals("run-a", read.runId)
        assertEquals(l2, read.lastLine)
        assertEquals(5_000L, read.lastLineAtMs)
        RunState(file).startRun(2222, "run-b")
        val next = RunState(file).read()
        assertEquals("2222", next.pid)
        assertNull(next.lastLine)
        assertEquals(4_000L, next.lastExitSeenMs)
    }

    @Test fun `a missing state file reads as nothing known`() {
        val read = RunState(java.io.File(tmp.root, "absent")).read()
        assertNull(read.pid)
        assertEquals(0L, read.lastExitSeenMs)
    }

    // ---- DailyBudget

    @Test fun `the daily cap allows up to its size, then waits for the next local day`() {
        val utc = TimeZone.getTimeZone("UTC")
        val budget = DailyBudget(tmp.newFile("budget.txt"), capBytes = 100, zone = { utc })
        val day = 1_794_254_400_000L
        assertTrue(budget.allows(60, day))
        budget.spent(60, day)
        assertTrue(budget.allows(40, day))
        assertFalse(budget.allows(41, day))
        assertEquals(60L, budget.spentToday(day))
        assertTrue(budget.allows(41, day + 86_400_000L))
    }

    @Test fun `the first batch of a day goes however big`() {
        val budget = DailyBudget(tmp.newFile("b.txt"), capBytes = 10, zone = { TimeZone.getTimeZone("UTC") })
        assertTrue(budget.allows(1_000, 0))
    }

    @Test fun `the day rolls over on local midnight`() {
        val houston = TimeZone.getTimeZone("America/Chicago")
        val budget = DailyBudget(tmp.newFile("c.txt"), capBytes = 10, zone = { houston })
        val beforeLocalMidnight = 1_794_290_340_000L // 2026-11-09 23:59 CST
        budget.spent(10, beforeLocalMidnight)
        assertFalse(budget.allows(5, beforeLocalMidnight))
        assertTrue(budget.allows(5, beforeLocalMidnight + 120_000L))
    }

    // ---- ConversationSpool.appendAll / clear

    @Test fun `appendAll writes a burst at once and clear empties the spool`() {
        val spool = ConversationSpool(tmp.newFolder(), maxTotalBytes = 10_000, sealAtBytes = 5_000)
        spool.appendAll(listOf(l1, "with\nnewline", l2), 1_000)
        val batch = spool.seal()!!
        assertEquals("$l1\nwith newline\n$l2\n", batch.file.readText())
        assertEquals(1_000L, batch.startMs)
        spool.appendAll(listOf(l1), 2_000)
        spool.clear()
        assertFalse(spool.hasContent())
    }

    @Test fun `appendAll seals at the size and drops the oldest past the cap`() {
        val spool = ConversationSpool(tmp.newFolder(), maxTotalBytes = 400, sealAtBytes = 150)
        repeat(10) { spool.appendAll(listOf("x".repeat(100)), it.toLong()) }
        assertTrue(spool.totalBytes() <= 400)
        assertTrue(spool.pending().first().startMs > 0)
    }

    // ---- the shared uploader sending logcat

    private class Fake(val answers: ArrayDeque<PostResult> = ArrayDeque()) : ConversationTransport {
        val fields = mutableListOf<Map<String, String>>()
        val bodies = mutableListOf<ByteArray>()
        override fun isUsable() = true
        override suspend fun post(fields: Map<String, String>, fileName: String, gzipBody: ByteArray): PostResult {
            this.fields += fields
            bodies += gzipBody
            return answers.removeFirstOrNull() ?: PostResult.Ok(201)
        }
    }

    private fun gunzip(bytes: ByteArray) = String(GZIPInputStream(ByteArrayInputStream(bytes)).readBytes())

    @Test fun `logcat batches carry their reason and run id and are redacted again on the way out`() = runBlocking {
        val spool = ConversationSpool(tmp.newFolder())
        spool.appendAll(listOf("11-09 14:00:00.000  1  1 I OkHttp: Authorization: Token abcdefabcdef12345678"), 1_000)
        val transport = Fake()
        val up = ConversationUploader(
            spool, transport, UploadMeta("samsung SM-A256E", "1.0", 7),
            reason = PhoneLog.REASON_LOGCAT, runId = "run-1",
            prepare = { String(it).lines().joinToString("\n") { l -> LogcatRedactor.redactLine(l) }.toByteArray() },
        )
        assertEquals(SendOutcome.Sent(1, 1), up.upload(manual = false))
        val f = transport.fields.single()
        assertEquals("logcat", f["reason"])
        assertEquals("run-1", f["run_id"])
        assertEquals("11-09 14:00:00.000  1  1 I OkHttp: Authorization: [redacted]\n", gunzip(transport.bodies.single()))
    }

    @Test fun `over the daily cap the rest waits and stays on the phone`() = runBlocking {
        val spool = ConversationSpool(tmp.newFolder(), sealAtBytes = 50)
        repeat(3) { spool.appendAll(listOf("line $it " + "z".repeat(60)), it.toLong()) }
        assertEquals(3, spool.pending().size)
        val transport = Fake()
        val budget = DailyBudget(tmp.newFile("cap.txt"), capBytes = 1, zone = { TimeZone.getTimeZone("UTC") })
        val up = ConversationUploader(
            spool, transport, UploadMeta("x", "1", 1), reason = "logcat", budget = budget,
            now = { 1_794_254_400_000L }, backoff = Backoff(),
        )
        val outcome = up.upload(manual = true)
        assertEquals(SendOutcome.Capped(1), outcome)
        assertTrue(outcome.sentence.contains("allowance"))
        assertEquals(1, transport.fields.size)
        assertEquals(2, spool.pending().size)
    }

    @Test fun `the conversation uploader's fields are unchanged without a run id`() {
        val spool = ConversationSpool(tmp.newFolder())
        spool.appendAll(listOf("a"), 1)
        val up = ConversationUploader(spool, Fake(), UploadMeta("x", "1", 1))
        val fields = up.fieldsFor(spool.seal()!!)
        assertEquals("conversation", fields["reason"])
        assertFalse(fields.containsKey("run_id"))
    }
}

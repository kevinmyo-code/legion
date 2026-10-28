package com.kevin.legion.service.conversationlog

import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The upload half of conversation-records ticket 03 against a fake transport: the request fields,
 * the gzip body, and the retry table (delete only on a 2xx, keep on everything else).
 */
class ConversationUploaderTest {
    @get:Rule val tmp = TemporaryFolder()

    private class Sent(val fields: Map<String, String>, val fileName: String, val body: ByteArray)

    private class FakeTransport(var usable: Boolean = true, val answers: ArrayDeque<PostResult> = ArrayDeque()) :
        ConversationTransport {
        val sent = mutableListOf<Sent>()
        override fun isUsable() = usable
        override suspend fun post(fields: Map<String, String>, fileName: String, gzipBody: ByteArray): PostResult {
            sent += Sent(fields, fileName, gzipBody)
            return answers.removeFirstOrNull() ?: PostResult.Ok(201)
        }
    }

    private val meta = UploadMeta("samsung SM-A256E", "1.0", 1)
    private var clock = 1_000_000L
    private val said = mutableListOf<String>()

    private fun uploader(spool: ConversationSpool, transport: FakeTransport, backoff: Backoff = Backoff()) =
        ConversationUploader(spool, transport, meta, say = { said += it }, now = { clock }, backoff = backoff)

    private fun spoolWith(vararg lines: Pair<String, Long>) = ConversationSpool(tmp.newFolder()).apply {
        lines.forEach { (line, at) -> append(line, at) }
    }

    private fun gunzip(bytes: ByteArray) = GZIPInputStream(ByteArrayInputStream(bytes)).readBytes()

    @Test fun `the request carries the head unit's fields with reason conversation and the window`() = runBlocking {
        val spool = spoolWith(
            "10-28 16:20:00.000 1 2 I LIVE_AUDIT: turn_complete" to 1_700_000_000_000,
            "x" to 1_700_000_065_432,
        )
        val transport = FakeTransport()
        val outcome = uploader(spool, transport).upload(manual = false)

        assertEquals(SendOutcome.Sent(lines = 2, batches = 1), outcome)
        assertEquals("Sent 2 lines.", outcome.sentence)
        val request = transport.sent.single()
        assertEquals(
            listOf(
                "batch_id", "device_label", "app_version_name", "app_version_code", "reason",
                "window_start_ms", "window_end_ms",
            ),
            request.fields.keys.toList(),
        )
        assertEquals("conversation", request.fields["reason"])
        assertEquals("samsung SM-A256E", request.fields["device_label"])
        assertEquals("1.0", request.fields["app_version_name"])
        assertEquals("1", request.fields["app_version_code"])
        assertEquals("1700000000000", request.fields["window_start_ms"])
        assertEquals("1700000065432", request.fields["window_end_ms"])
        assertEquals("${request.fields["batch_id"]}.log.gz", request.fileName)
        assertTrue(java.util.UUID.fromString(request.fields["batch_id"]) != null)
    }

    @Test fun `the gzip body round-trips to exactly the spooled bytes`() = runBlocking {
        val lines = listOf("10-28 16:20:00.000 1 2 I LIVE_AUDIT: user final=true text=\"café\"", "second")
        val spool = spoolWith(lines[0] to 1, lines[1] to 2)
        val transport = FakeTransport()
        uploader(spool, transport).upload(manual = false)
        val expected = lines.joinToString("\n", postfix = "\n").toByteArray(Charsets.UTF_8)
        assertArrayEquals(expected, gunzip(transport.sent.single().body))
    }

    @Test fun `a 2xx deletes the batch, both 200 and 201`() = runBlocking {
        val spool = spoolWith("a" to 1)
        spool.seal()
        spool.append("b", 2)
        val transport = FakeTransport(answers = ArrayDeque(listOf(PostResult.Ok(201), PostResult.Ok(200))))
        val outcome = uploader(spool, transport).upload(manual = false)
        assertEquals(SendOutcome.Sent(2, 2), outcome)
        assertTrue(spool.pending().isEmpty())
        assertFalse(spool.hasContent())
    }

    @Test fun `the retry table keeps the batch on every non-2xx`() {
        val table = listOf(
            PostResult.Unreachable("No route to host") to FailureKind.OFFLINE,
            PostResult.Http(401, "bad token") to FailureKind.SIGNED_OUT,
            PostResult.Http(403, "read scope") to FailureKind.SIGNED_OUT,
            PostResult.Http(400, OLD_ENGINE_BODY) to FailureKind.OLD_ENGINE,
            PostResult.Http(400, "Nothing was stored. The file is not valid gzip data.") to FailureKind.REFUSED,
            PostResult.Http(413, "capped at 5 MB") to FailureKind.TOO_LARGE,
            PostResult.Http(404, "") to FailureKind.REFUSED,
            PostResult.Http(500, "boom") to FailureKind.SERVER_ERROR,
            PostResult.Http(503, "") to FailureKind.SERVER_ERROR,
        )
        for ((result, kind) in table) {
            assertEquals(result.toString(), kind, RetryRules.classify(result))
            val spool = spoolWith("line" to 1)
            val transport = FakeTransport(answers = ArrayDeque(listOf(result)))
            val outcome = runBlocking { uploader(spool, transport).upload(manual = true) }
            assertTrue(result.toString(), outcome is SendOutcome.Failed && outcome.kind == kind)
            assertEquals(result.toString(), 1, spool.pending().size)
            val sentence = outcome.sentence
            assertTrue(sentence, sentence.startsWith("Failed: ") && sentence.endsWith("tried again."))
        }
        assertNull(RetryRules.classify(PostResult.Ok(201)))
        assertNull(RetryRules.classify(PostResult.Http(204, "")))
    }

    @Test fun `the first failure stops the run so order is kept`() = runBlocking {
        val spool = spoolWith("a" to 1)
        spool.seal()
        spool.append("b", 2)
        spool.seal()
        spool.append("c", 3)
        val transport = FakeTransport(answers = ArrayDeque(listOf(PostResult.Ok(201), PostResult.Http(500, "boom"))))
        val outcome = uploader(spool, transport).upload(manual = false) as SendOutcome.Failed
        assertEquals(2, transport.sent.size)
        assertEquals(1, outcome.sentLines)
        assertEquals(
            "Sent 1 line, then failed: the engine answered HTTP 500: boom. Kept on this phone; it will be tried again.",
            outcome.sentence,
        )
        assertEquals(2, spool.pending().size)
        assertEquals(listOf(2L, 3L), spool.pending().map { it.startMs })
    }

    @Test fun `no engine sends nothing, keeps everything, and says so once`() = runBlocking {
        val spool = spoolWith("a" to 1)
        val transport = FakeTransport(usable = false)
        val up = uploader(spool, transport)
        assertEquals(SendOutcome.NoEngine, up.upload(manual = false))
        assertEquals(SendOutcome.NoEngine, up.upload(manual = true))
        assertTrue(transport.sent.isEmpty())
        assertTrue(spool.hasContent())
        assertEquals(1, said.size)
    }

    @Test fun `an old engine is said once, kept, and waits the longest backoff`() = runBlocking {
        val spool = spoolWith("a" to 1)
        val answers = ArrayDeque<PostResult>(List(3) { PostResult.Http(400, OLD_ENGINE_BODY) })
        val up = uploader(spool, FakeTransport(answers = answers))
        up.upload(manual = true)
        up.upload(manual = true)
        assertEquals(1, said.size)
        assertEquals(1, spool.pending().size)
        clock += Backoff.MAX_MS - 1
        assertEquals(SendOutcome.Waiting, up.upload(manual = false))
        clock += 1
        assertTrue(up.upload(manual = false) is SendOutcome.Failed)
    }

    @Test fun `nothing spooled is nothing to send`() = runBlocking {
        val transport = FakeTransport()
        val empty = ConversationSpool(tmp.newFolder())
        assertEquals(SendOutcome.NothingToSend, uploader(empty, transport).upload(manual = false))
        assertEquals("Nothing to send.", SendOutcome.NothingToSend.sentence)
        assertTrue(transport.sent.isEmpty())
    }

    @Test fun `backoff doubles to a cap, send now ignores it, success resets it`() = runBlocking {
        val backoff = Backoff(baseMs = 1_000, maxMs = 3_000)
        backoff.failed(0, FailureKind.OFFLINE)
        assertFalse(backoff.ready(999))
        assertTrue(backoff.ready(1_000))
        backoff.failed(0, FailureKind.OFFLINE)
        assertFalse(backoff.ready(1_999))
        assertTrue(backoff.ready(2_000))
        backoff.failed(0, FailureKind.SERVER_ERROR)
        assertTrue(backoff.ready(3_000)) // capped, not 4_000
        backoff.succeeded()
        assertTrue(backoff.ready(0))

        val spool = spoolWith("a" to 1)
        val up = uploader(spool, FakeTransport(answers = ArrayDeque(listOf(PostResult.Unreachable("offline")))))
        assertTrue(up.upload(manual = false) is SendOutcome.Failed)
        assertEquals(SendOutcome.Waiting, up.upload(manual = false))
        assertEquals(SendOutcome.Sent(1, 1), up.upload(manual = true))
    }

    private companion object {
        // What `devicelogs/views.py` answers an engine without `conversation` in REASONS.
        const val OLD_ENGINE_BODY = "{\"detail\":\"Nothing was stored. " +
            "{'reason': [ErrorDetail(string='\\\"conversation\\\" " +
            "is not a valid choice.', code='invalid_choice')]}\"}"
    }
}

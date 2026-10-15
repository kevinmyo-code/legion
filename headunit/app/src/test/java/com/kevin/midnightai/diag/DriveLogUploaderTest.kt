package com.kevin.midnightai.diag

import com.kevin.midnightai.backend.engine.EngineConfig
import com.kevin.midnightai.backend.engine.EngineHttp
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The drive-log upload against a real [MockWebServer]: a batch is deleted ONLY on a 2xx, a
 * failure keeps it, no token means nothing is sent, and what leaves the unit is gzip with the
 * secrets already gone.
 */
@RunWith(RobolectricTestRunner::class)
class DriveLogUploaderTest {
    @get:Rule val tmp = TemporaryFolder()
    private val server = MockWebServer()

    private class Sink : UploadStatusSink {
        var last: Triple<Long, Boolean, String>? = null
        override fun record(atMs: Long, ok: Boolean, sentence: String) {
            last = Triple(atMs, ok, sentence)
        }
    }

    private val sink = Sink()

    @Before fun start() = server.start()

    @After fun stop() {
        runCatching { server.shutdown() }
    }

    private fun config(signedIn: Boolean): EngineConfig {
        val config = EngineConfig(
            context = RuntimeEnvironment.getApplication(),
            encrypt = { plain -> "ENC($plain)" },
            decrypt = { blob -> blob.removePrefix("ENC(").removeSuffix(")") },
        )
        config.saveBaseUrl(server.url("/").toString().trimEnd('/'))
        if (signedIn) config.saveSession("test-device-token", "11111111-1111-1111-1111-111111111111")
        else config.clearSession()
        return config
    }

    private fun uploader(store: DriveLogStore, signedIn: Boolean = true) = DriveLogUploader(
        store = store,
        http = EngineHttp(config(signedIn), OkHttpClient()),
        meta = UploadMeta("Test unit", "1.0", 7),
        status = sink,
    )

    private fun newStore() = DriveLogStore(File(tmp.root, "log"))

    @Test
    fun `a 2xx deletes the batch and sends gzip with the secrets already redacted`() = runTest {
        val store = newStore()
        store.append(listOf("10-15 08:00:00.000 1 1 I T: Authorization: Token abcdefabcdefabcdefabcdefabcdefabcdef", "plain line"))
        server.enqueue(MockResponse().setResponseCode(201).setBody("{}"))

        val outcome = uploader(store).upload(UploadReason.DRIVE_END)

        assertEquals(UploadOutcome.Sent(1), outcome)
        assertTrue(store.pending().isEmpty())
        assertTrue(sink.last!!.second)

        val request = server.takeRequest()
        assertEquals("/api/device-logs/", request.path)
        assertEquals("Token test-device-token", request.getHeader("Authorization"))
        val bytes = request.body.readByteArray()
        val text = String(bytes, Charsets.ISO_8859_1)
        assertTrue(text.contains("name=\"reason\"") && text.contains("drive_end"))
        assertTrue(text.contains("name=\"batch_id\""))
        assertTrue(text.contains("name=\"app_version_code\"") && text.contains("\r\n7\r\n"))
        val gzAt = text.indexOf("\u001f\u008b")
        val inflated = GZIPInputStream(ByteArrayInputStream(bytes, gzAt, bytes.size - gzAt))
            .readBytes().toString(Charsets.UTF_8)
        assertTrue(inflated.contains("plain line"))
        assertFalse(inflated.contains("abcdefabcdef"))
    }

    @Test
    fun `a 500 keeps the batch and says so`() = runTest {
        val store = newStore()
        store.append(listOf("keep me"))
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))

        val outcome = uploader(store).upload(UploadReason.MANUAL)

        assertTrue(outcome is UploadOutcome.Failed)
        assertEquals(1, store.pending().size)
        assertFalse(sink.last!!.second)
        assertTrue(sink.last!!.third.contains("kept here"))
    }

    @Test
    fun `an unreachable engine keeps the batch`() = runTest {
        val store = newStore()
        store.append(listOf("keep me"))
        server.shutdown()

        val outcome = uploader(store).upload(UploadReason.STARTUP)

        assertTrue(outcome is UploadOutcome.Failed)
        assertEquals(1, store.pending().size)
    }

    @Test
    fun `no token on file sends nothing and loses nothing`() = runTest {
        val store = newStore()
        store.append(listOf("still here"))

        val outcome = uploader(store, signedIn = false).upload(UploadReason.MANUAL)

        assertEquals(UploadOutcome.NoEngine, outcome)
        assertEquals(0, server.requestCount)
        // Not even sealed: the live file is untouched.
        assertTrue(store.hasContent())
        assertTrue(store.pending().isEmpty())
    }

    @Test
    fun `a failure stops the run so batches go oldest first and the rest wait`() = runTest {
        val store = newStore()
        store.append(listOf("first"))
        store.seal()
        store.append(listOf("second"))
        store.seal()
        server.enqueue(MockResponse().setResponseCode(201).setBody("{}"))
        server.enqueue(MockResponse().setResponseCode(503))

        val outcome = uploader(store).upload(UploadReason.DRIVE_END)

        assertEquals(UploadOutcome.Failed::class, outcome::class)
        assertEquals(1, (outcome as UploadOutcome.Failed).sent)
        assertEquals(listOf("second"), store.pending().flatMap { it.file.readLines() })
    }

    @Test
    fun `nothing waiting is reported as such`() = runTest {
        assertEquals(UploadOutcome.NothingToSend, uploader(newStore()).upload(UploadReason.STARTUP))
        assertEquals(0, server.requestCount)
    }
}

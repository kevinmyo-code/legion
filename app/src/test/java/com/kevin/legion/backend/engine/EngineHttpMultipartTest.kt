package com.kevin.legion.backend.engine

import com.kevin.legion.backend.engine.EngineTestSupport.json
import com.kevin.legion.service.conversationlog.ConversationUploader
import com.kevin.legion.service.conversationlog.EngineConversationTransport
import com.kevin.legion.service.conversationlog.PostResult
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The phone's `POST /api/device-logs/` on the wire (conversation-records ticket 03), through
 * [EngineHttp.postMultipart] and [EngineConversationTransport] against a [io.ktor.client.engine.mock.MockEngine]:
 * the device token, a multipart body with `filename=` on the file part, and a gzip part that
 * inflates to the spooled text. The server side is `server/devicelogs/views.py`; nothing here was
 * recorded from a live engine.
 */
@RunWith(RobolectricTestRunner::class)
class EngineHttpMultipartTest {
    private val context = RuntimeEnvironment.getApplication()
    private val spooled = "10-28 16:20:00.000 1 2 I LIVE_AUDIT: session_start model=m async_set=none\n"
    private val fields = linkedMapOf(
        "batch_id" to "0b6f3c1e-7d1a-4c55-9b0e-3a1f2c4d5e6f",
        "device_label" to "samsung SM-A256E",
        "app_version_name" to "1.0",
        "app_version_code" to "1",
        "reason" to "conversation",
        "window_start_ms" to "1761668400000",
        "window_end_ms" to "1761668460000",
    )

    private fun transport(engine: EngineTestSupport.RecordingEngine) =
        EngineConversationTransport(EngineHttp(EngineTestSupport.signedInConfig(context), engine.client()))

    @Test fun `the request is an authorised multipart POST with every field and a gzip file part`() = runBlocking {
        val engine = EngineTestSupport.RecordingEngine { json("{\"detail\":\"Stored.\"}", HttpStatusCode.Created) }
        val gz = ConversationUploader.gzip(spooled.toByteArray())
        val result = transport(engine).post(fields, "${fields["batch_id"]}.log.gz", gz)

        assertEquals(PostResult.Ok(201), result)
        val request = engine.requests.single()
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("${EngineTestSupport.BASE_URL}/api/device-logs/", request.url.toString())
        assertEquals("Token test-device-token", request.headers[HttpHeaders.Authorization])
        val contentType = request.body.contentType.toString()
        assertTrue(contentType, contentType.startsWith("multipart/form-data; boundary="))

        val body = request.body.toByteArray()
        val text = String(body, Charsets.ISO_8859_1)
        for ((name, value) in fields) {
            assertTrue(name, text.contains("form-data; name=$name") || text.contains("name=\"$name\""))
            assertTrue(value, text.contains("\r\n\r\n$value\r\n"))
        }
        assertTrue(text, text.contains("filename=\"${fields["batch_id"]}.log.gz\""))
        assertTrue(text, text.contains("Content-Type: application/gzip"))

        // The gzip member starts at its magic bytes after the file part's headers.
        val start = (0 until body.size - 1).first { body[it] == 0x1f.toByte() && body[it + 1] == 0x8b.toByte() }
        val inflated = GZIPInputStream(ByteArrayInputStream(body, start, body.size - start)).readBytes()
        assertArrayEquals(spooled.toByteArray(), inflated)
    }

    @Test fun `statuses map onto the retry table's inputs`() = runBlocking {
        val cases = listOf(
            HttpStatusCode.OK to PostResult.Ok(200),
            HttpStatusCode.BadRequest to PostResult.Http(400, "{\"detail\":\"no\"}"),
            HttpStatusCode.PayloadTooLarge to PostResult.Http(413, "{\"detail\":\"no\"}"),
            HttpStatusCode.InternalServerError to PostResult.Http(500, "{\"detail\":\"no\"}"),
        )
        for ((status, expected) in cases) {
            val engine = EngineTestSupport.RecordingEngine { json("{\"detail\":\"no\"}", status) }
            assertEquals(status.toString(), expected, transport(engine).post(fields, "x.log.gz", byteArrayOf(1)))
        }
        val unauthorized = EngineTestSupport.RecordingEngine {
            json("{\"detail\":\"bad\"}", HttpStatusCode.Unauthorized)
        }
        assertEquals(401, (transport(unauthorized).post(fields, "x.log.gz", byteArrayOf(1)) as PostResult.Http).status)
        val offline = EngineConversationTransport(
            EngineHttp(EngineTestSupport.signedInConfig(context), EngineTestSupport.unreachableClient()),
        )
        assertTrue(offline.post(fields, "x.log.gz", byteArrayOf(1)) is PostResult.Unreachable)
    }
}

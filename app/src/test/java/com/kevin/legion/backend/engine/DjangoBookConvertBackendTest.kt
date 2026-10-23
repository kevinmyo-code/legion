package com.kevin.legion.backend.engine

import com.kevin.legion.backend.engine.EngineTestSupport.json
import com.kevin.legion.books.ConversionStatus
import com.kevin.legion.books.ConvertReply
import com.kevin.legion.books.DjangoBookConvertBackend
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * [DjangoBookConvertBackend] against `POST /api/books/convert`'s contract in
 * `.scratch/books/issues/04-build-pdf-convert-server.md`. The server is not deployed when this is
 * written, so the shapes here are the ticket's, not a recording of a live reply.
 */
@RunWith(RobolectricTestRunner::class)
class DjangoBookConvertBackendTest {
    private val context = RuntimeEnvironment.getApplication()
    private val pdf = "%PDF-1.4 fake".toByteArray()

    private fun http(engine: EngineTestSupport.RecordingEngine) =
        EngineHttp(EngineTestSupport.signedInConfig(context), engine.client())

    private fun convert(engine: EngineTestSupport.RecordingEngine) =
        runBlocking { DjangoBookConvertBackend(http(engine)).convert(pdf) }

    @Test fun `a 200 hands back the EPUB bytes untouched and the page count header`() {
        val epub = byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x00, 0xFF.toByte(), 0x80.toByte())
        val headers = headersOf(
            HttpHeaders.ContentType to listOf("application/epub+zip"),
            "X-Legion-Pages" to listOf("212"),
        )
        val engine = EngineTestSupport.RecordingEngine { respond(epub, HttpStatusCode.OK, headers) }
        val reply = convert(engine) as ConvertReply.Converted
        assertArrayEquals("binary body survives, no text decoding", epub, reply.epub)
        assertEquals(212, reply.pages)
    }

    @Test fun `the request is the raw PDF body at the books endpoint with the device token`() {
        val engine = EngineTestSupport.RecordingEngine { respond(ByteArray(1), HttpStatusCode.OK) }
        convert(engine)
        val request = engine.requests.single()
        assertEquals("${EngineTestSupport.BASE_URL}/api/books/convert", request.url.toString())
        assertEquals("application/pdf", request.body.contentType.toString())
        assertEquals("Token test-device-token", request.headers["Authorization"])
    }

    private fun refusal(code: String, status: HttpStatusCode, message: String) =
        convert(EngineTestSupport.RecordingEngine { json("""{"code": "$code", "message": "$message"}""", status) })

    @Test fun `422 scanned keeps the server's sentence and the SCANNED status`() {
        val sentence = "This PDF is scanned pages with no text, so its text cannot be refit."
        val reply = refusal("scanned", HttpStatusCode.UnprocessableEntity, sentence)
        assertEquals(ConvertReply.Refused(ConversionStatus.SCANNED, sentence), reply)
    }

    @Test fun `422 unreadable, 413 too big and 502 failed map to their statuses`() {
        val unreadable = refusal("unreadable", HttpStatusCode.UnprocessableEntity, "encrypted")
        val tooBig = refusal("too_big", HttpStatusCode.PayloadTooLarge, "31 MB")
        val failed = refusal("failed", HttpStatusCode.BadGateway, "timed out")
        assertEquals(ConversionStatus.UNREADABLE, (unreadable as ConvertReply.Refused).status)
        assertEquals(ConversionStatus.TOO_BIG, (tooBig as ConvertReply.Refused).status)
        assertEquals(ConversionStatus.FAILED, (failed as ConvertReply.Refused).status)
    }

    @Test fun `a refusal with a body that is not the contract's JSON is FAILED with a sentence`() {
        val engine = EngineTestSupport.RecordingEngine {
            respond("<html>boom</html>", HttpStatusCode.InternalServerError)
        }
        val refused = convert(engine) as ConvertReply.Refused
        assertEquals(ConversionStatus.FAILED, refused.status)
        assertTrue(refused.message, refused.message.isNotBlank())
    }

    @Test fun `a 401 is Unavailable, not a retry and not a failed attempt`() {
        val engine = EngineTestSupport.RecordingEngine {
            json("""{"detail": "bad token"}""", HttpStatusCode.Unauthorized)
        }
        assertTrue(convert(engine) is ConvertReply.Unavailable)
    }

    @Test fun `an unreachable engine is Unreachable so WorkManager retries`() {
        val http = EngineHttp(EngineTestSupport.signedInConfig(context), EngineTestSupport.unreachableClient())
        assertTrue(runBlocking { DjangoBookConvertBackend(http).convert(pdf) } is ConvertReply.Unreachable)
    }
}

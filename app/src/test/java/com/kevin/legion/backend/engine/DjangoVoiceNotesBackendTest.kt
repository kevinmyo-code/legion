package com.kevin.legion.backend.engine

import com.kevin.legion.backend.VoiceNoteFields
import com.kevin.legion.backend.engine.EngineTestSupport.json
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * [DjangoVoiceNotesBackend] against `server/api/voice_notes.py`'s real shapes.
 *
 * **The fixture is the live engine's own reply**, captured 2026-09-07 from
 * `GET https://legion-757959564788.us-south1.run.app/api/voice_notes/` - all three rows the
 * household has, one of which is genuinely tombstoned (`"deleted_at": "2026-09-04T20:56:21.889000Z"`).
 * That tombstone is not a fabricated case: it is the row this aspect's since-feed has to keep and
 * its active feed has to drop, and both halves were confirmed against that engine
 * (`?active=1` answered two rows, both with `deleted_at: null`).
 */
@RunWith(RobolectricTestRunner::class)
class DjangoVoiceNotesBackendTest {

    private val context = RuntimeEnvironment.getApplication()

    private fun backend(engine: EngineTestSupport.RecordingEngine): DjangoVoiceNotesBackend =
        DjangoVoiceNotesBackend(EngineHttp(EngineTestSupport.signedInConfig(context), engine.client()))

    @Test
    fun `the since-feed parses every field and keeps the tombstone`() = runBlocking {
        val engine =
            EngineTestSupport.RecordingEngine { json(EngineTestSupport.fixture("voice_notes_page.json")) }

        val notes = backend(engine).fetchChangedSince(0L).getOrThrow()

        assertEquals(3, notes.size)
        // The tombstone survives the pull. Filtering it here is the bug EventsBackend's own
        // fetchChangedSince doc comment traces at length: a merge's tombstone branch never fires
        // if the row it is waiting for is dropped by the transport.
        val removed = notes.single { it.serverId == "07efc8d8-8ae0-4bd8-9dd2-03528758f8d9" }
        assertTrue(removed.deleted)

        val cooking = notes.single { it.serverId == "ed4f9548-5825-45b5-877b-8f57bd72923a" }
        assertEquals("SOLO", cooking.kind)
        assertEquals("LLM_DERIVED", cooking.provenance)
        assertFalse(cooking.interrupted)
        assertEquals(1788562225899L, cooking.startedAtMs)
        assertEquals(1788562276528L, cooking.updatedAtMs)
        // Non-ASCII round-trips: the real transcript is German and the fixture is read as UTF-8.
        assertTrue(cooking.transcript!!.contains("Äpfel"))
        assertTrue(cooking.summary!!.startsWith("The speaker outlines steps"))

        val url = engine.requests.single().url
        assertEquals("/api/voice_notes/", url.encodedPath)
        assertEquals("1970-01-01T00:00:00Z", url.parameters["since"])
        // Tombstones are wanted here, so the active narrowing is deliberately NOT sent.
        assertNull(url.parameters["active"])
    }

    @Test
    fun `fetchActive asks the engine to narrow rather than filtering here`() = runBlocking {
        val engine =
            EngineTestSupport.RecordingEngine { json(EngineTestSupport.fixture("voice_notes_page.json")) }

        backend(engine).fetchActive().getOrThrow()

        val url = engine.requests.single().url
        assertEquals("1", url.parameters["active"])
        assertNull(url.parameters["since"])
    }

    @Test
    fun `a create POSTs and an update PUTs to the server's own id`() = runBlocking {
        val row = """
            {"id": "142f4cea-9673-4dc8-b31b-10a026026730", "started_at": "2026-09-04T22:54:35.354000Z",
             "ended_at": null, "title": "Transcription Check Seven", "summary": null,
             "transcript": null, "kind": "SOLO", "provenance": "LLM_DERIVED", "interrupted": false,
             "created_at": "2026-09-04T22:59:35.497680Z", "updated_at": "2026-09-04T22:59:35.497680Z",
             "deleted_at": null}
        """.trimIndent()
        val fields = VoiceNoteFields(startedAtMs = 1788562475354L, kind = "SOLO")

        val creating = EngineTestSupport.RecordingEngine { json(row, HttpStatusCode.Created) }
        backend(creating).upsert(serverId = null, fields = fields).getOrThrow()
        assertEquals("POST", creating.requests.single().method.value)
        assertEquals("/api/voice_notes/", creating.requests.single().url.encodedPath)

        val updating = EngineTestSupport.RecordingEngine { json(row) }
        backend(updating).upsert("142f4cea-9673-4dc8-b31b-10a026026730", fields).getOrThrow()
        assertEquals("PUT", updating.requests.single().method.value)
        assertEquals(
            "/api/voice_notes/142f4cea-9673-4dc8-b31b-10a026026730/",
            updating.requests.single().url.encodedPath,
        )
    }

    @Test
    fun `no audio path is ever serialised, on a create or on an update`() = runBlocking {
        // ADR 0041 makes a recording Kevin starts first-party, and the voice-notes map still draws
        // the line at the FILE: the server holds text, the file stays on the phone. This asserts
        // the guarantee at the wire, where it is observable - and note what makes it strong: there
        // is no audio column on `public.voice_notes` and no audio field on VoiceNoteFields, so
        // nothing here had to remember to exclude one. This test is what would notice if that
        // stopped being true.
        val row = """
            {"id": "142f4cea-9673-4dc8-b31b-10a026026730", "started_at": "2026-09-04T22:54:35.354000Z",
             "ended_at": null, "title": null, "summary": null, "transcript": null, "kind": "SOLO",
             "provenance": "LLM_DERIVED", "interrupted": false,
             "created_at": "2026-09-04T22:59:35.497680Z", "updated_at": "2026-09-04T22:59:35.497680Z",
             "deleted_at": null}
        """.trimIndent()
        val fields = VoiceNoteFields(
            startedAtMs = 1788562475354L,
            endedAtMs = 1788562768794L,
            title = "Transcription Check Seven",
            summary = "A check recording.",
            transcript = "The quick brown fox jumps over the lazy dog.",
            kind = "SOLO",
            interrupted = false,
        )

        for (serverId in listOf(null, "142f4cea-9673-4dc8-b31b-10a026026730")) {
            val engine = EngineTestSupport.RecordingEngine { json(row, HttpStatusCode.Created) }
            backend(engine).upsert(serverId, fields).getOrThrow()
            val sent = (engine.requests.single().body as TextContent).text
            assertFalse("no audio field may reach the wire", sent.contains("audio"))
            assertFalse(sent.contains(".m4a"))
            assertFalse(sent.contains("/sdcard"))
            // The text half DOES cross, so this is not passing merely because nothing was sent.
            assertTrue(sent.contains("\"transcript\":\"The quick brown fox jumps over the lazy dog.\""))
            assertTrue(sent.contains("\"ended_at\":\"2026-09-04T22:59:28.794Z\""))
        }
    }

    @Test
    fun `a summary with no transcript is refused in the engine's own words`() = runBlocking {
        // Verbatim from the live engine's serializer (`VoiceNoteSerializer.validate`, mirroring the
        // `voice_notes_summary_needs_transcript` CHECK). The transcript is what anchors the summary
        // under ADR 0041, so this refusal is a rule the phone must NOT reimplement - it must show.
        val sentence = "Nothing was written. A voice note cannot carry a summary with no transcript " +
            "to have summarized: the transcript is what anchors the summary (ADR 0041). " +
            "Store the transcript first."
        val engine = EngineTestSupport.RecordingEngine {
            json("""{"non_field_errors": ["$sentence"]}""", HttpStatusCode.BadRequest)
        }

        val result = backend(engine).upsert(
            serverId = null,
            fields = VoiceNoteFields(startedAtMs = 1L, summary = "a summary", kind = "SOLO"),
        )

        assertTrue(result.isFailure)
        val failure = (result.exceptionOrNull() as EngineHttpException).failure
        assertTrue(failure is EngineFailure.Refused)
        assertEquals(400, (failure as EngineFailure.Refused).status)
        // Verbatim - nothing prefixed, nothing paraphrased. See prefixEngineFailure's own doc.
        assertTrue(failure.body.contains(sentence))
    }

    @Test
    fun `softDelete is true on a 204 and false on a 404`() = runBlocking {
        val gone = EngineTestSupport.RecordingEngine { json("", HttpStatusCode.NoContent) }
        assertTrue(backend(gone).softDelete("142f4cea-9673-4dc8-b31b-10a026026730").getOrThrow())

        val missing = EngineTestSupport.RecordingEngine {
            json("""{"detail": "no such row"}""", HttpStatusCode.NotFound)
        }
        assertFalse(backend(missing).softDelete("142f4cea-9673-4dc8-b31b-10a026026730").getOrThrow())
    }

    @Test
    fun `an unreachable engine fails in words rather than reporting a save`() = runBlocking {
        val http = EngineHttp(EngineTestSupport.signedInConfig(context), EngineTestSupport.unreachableClient())

        val result = DjangoVoiceNotesBackend(http)
            .upsert(null, VoiceNoteFields(startedAtMs = 1L, kind = "SOLO"))

        assertTrue(result.isFailure)
        val failure = (result.exceptionOrNull() as EngineHttpException).failure
        assertTrue(failure is EngineFailure.Unreachable)
        assertTrue(failure.sentence.startsWith("Couldn't save that recording."))
    }
}

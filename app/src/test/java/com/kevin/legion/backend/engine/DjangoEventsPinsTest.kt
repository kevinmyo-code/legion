package com.kevin.legion.backend.engine

import com.kevin.legion.backend.EventFields
import com.kevin.legion.backend.EventKind
import com.kevin.legion.backend.engine.EngineTestSupport.json
import io.ktor.http.HttpMethod
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
 * Suggestion pins on the wire (Kevin, 2026-10-09): `pinned_by` is read into
 * [com.kevin.legion.backend.RemoteEvent.pinnedByJson], is NEVER sent back on an event write, and
 * the pin / unpin calls hit the endpoints the server contract names.
 */
@RunWith(RobolectricTestRunner::class)
class DjangoEventsPinsTest {
    private val context = RuntimeEnvironment.getApplication()

    private fun backend(engine: EngineTestSupport.RecordingEngine) =
        DjangoEventsBackend(EngineHttp(EngineTestSupport.signedInConfig(context), engine.client()))

    private fun row(pinnedBy: String?) = """{"id": "e1", "title": "Jazz night", "source": "engine",
        "kind": "suggestion", "created_at": "2026-10-09T00:00:00Z", "updated_at": "2026-10-09T00:00:00Z"
        ${if (pinnedBy == null) "" else ", \"pinned_by\": $pinnedBy"}}"""

    private val twoPins = """[{"user_id": "u1", "display_name": "Mia"}, {"user_id": "u2", "display_name": "Kevin"}]"""

    @Test
    fun `a feed row carries pinned_by verbatim into the remote event`() = runBlocking {
        val engine = EngineTestSupport.RecordingEngine { json("""{"results": [${row(twoPins)}], "next": null}""") }

        val event = backend(engine).fetchChangedSince(0L).getOrThrow().single()

        assertEquals(
            """[{"user_id":"u1","display_name":"Mia"},{"user_id":"u2","display_name":"Kevin"}]""",
            event.pinnedByJson,
        )
    }

    @Test
    fun `an empty pinned_by is stored as an empty array, an absent key as null`() = runBlocking {
        val engine = EngineTestSupport.RecordingEngine {
            json("""{"results": [${row("[]")}, ${row(null).replace("\"e1\"", "\"e2\"")}], "next": null}""")
        }

        val events = backend(engine).fetchChangedSince(0L).getOrThrow().associateBy { it.serverId }

        assertEquals("[]", events.getValue("e1").pinnedByJson)
        assertNull("a redacted tombstone / older engine states none", events.getValue("e2").pinnedByJson)
    }

    @Test
    fun `an event write never carries pinned_by`() = runBlocking {
        val engine = EngineTestSupport.RecordingEngine { json(row(twoPins)) }
        val fields = EventFields(title = "Jazz night", startsAtMs = 0L, kind = EventKind.SUGGESTION)

        val created = backend(engine).upsert(null, fields).getOrThrow()
        backend(engine).upsert("e1", fields).getOrThrow()

        // The response is read (pins arrive), but nothing in either request body names pins.
        assertTrue(created.pinnedByJson!!.contains("Mia"))
        assertEquals(2, engine.requests.size)
        engine.requests.forEach { request ->
            val body = (request.body as TextContent).text
            assertFalse(body, body.contains("pinned_by"))
        }
    }

    @Test
    fun `pin posts an empty body to the pins path and returns the event with its pins`() = runBlocking {
        val engine = EngineTestSupport.RecordingEngine { json(row(twoPins), HttpStatusCode.Created) }

        val event = backend(engine).pin("e1").getOrThrow()

        val request = engine.requests.single()
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("/api/events/e1/pins", request.url.encodedPath)
        assertEquals("", (request.body as TextContent).text)
        assertTrue(event.pinnedByJson!!.contains("Kevin"))
    }

    @Test
    fun `unpin deletes the callers own pin and returns the event`() = runBlocking {
        val engine = EngineTestSupport.RecordingEngine { json(row("[]")) }

        val event = backend(engine).unpin("e1").getOrThrow()

        val request = engine.requests.single()
        assertEquals(HttpMethod.Delete, request.method)
        assertEquals("/api/events/e1/pins/mine", request.url.encodedPath)
        assertEquals("[]", event.pinnedByJson)
    }

    @Test
    fun `a 400 for a non-suggestion is a Refused failure a caller can read as terminal`() = runBlocking {
        val engine = EngineTestSupport.RecordingEngine {
            json("""{"detail": "Only a suggestion can be pinned."}""", HttpStatusCode.BadRequest)
        }

        val failure = (backend(engine).pin("e1").exceptionOrNull() as EngineHttpException).failure

        assertTrue(failure is EngineFailure.Refused)
        assertEquals(400, (failure as EngineFailure.Refused).status)
    }
}

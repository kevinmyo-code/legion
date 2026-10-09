package com.kevin.legion.backend.engine

import com.kevin.legion.backend.engine.EngineTestSupport.json
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * `places.address` and `POST /api/places/<label>/rename/` on the wire (2026-10-09), against the
 * shapes `server/api/places.py` renders - `PlaceSerializer` and `PlaceRenameResultSerializer`,
 * whose server tests (`server/tests/test_places_api.py`) assert the same fields from the other end.
 */
@RunWith(RobolectricTestRunner::class)
class DjangoPlacesAddressTest {
    private val context = RuntimeEnvironment.getApplication()

    private fun backend(engine: EngineTestSupport.RecordingEngine) =
        DjangoPlacesBackend(EngineHttp(EngineTestSupport.signedInConfig(context), engine.client()))

    private fun row(label: String, address: String?) =
        """{"id": "b021c3f8-cf90-4a79-afd8-efc717a18f74", "label": "$label",
            "latitude": 29.78, "longitude": -95.82, "provenance": "USER",
            "created_at": "2026-10-09T12:00:00Z", "updated_at": "2026-10-09T12:00:00Z",
            "deleted_at": null, "address": ${address?.let { "\"$it\"" } ?: "null"}}"""

    @Test
    fun `an address goes out on the PUT and comes back on the row`() = runBlocking {
        val engine = EngineTestSupport.RecordingEngine { json(row("katie house", "123 Main St, Katy, TX 77494")) }

        val saved = backend(engine).upsert("katie house", 29.78, -95.82, "123 Main St, Katy, TX 77494").getOrThrow()

        assertEquals("123 Main St, Katy, TX 77494", saved.address)
        val sent = (engine.requests.single().body as TextContent).text
        assertTrue(sent, sent.contains("\"address\":\"123 Main St, Katy, TX 77494\""))
    }

    @Test
    fun `no address is sent as an explicit null so a re-pin cannot keep the old spot's address`() = runBlocking {
        val engine = EngineTestSupport.RecordingEngine { json(row("home", null)) }

        val saved = backend(engine).upsert("home", 29.78, -95.82, address = null).getOrThrow()

        assertNull(saved.address)
        val sent = (engine.requests.single().body as TextContent).text
        assertTrue(sent, sent.contains("\"address\":null"))
    }

    @Test
    fun `a row from an engine that predates the column still decodes, with no address`() = runBlocking {
        val engine = EngineTestSupport.RecordingEngine {
            json(
                """{"id": "b021c3f8-cf90-4a79-afd8-efc717a18f74", "label": "home", "latitude": 1.0,
                    "longitude": 2.0, "provenance": "USER", "created_at": "2026-10-09T12:00:00Z",
                    "updated_at": "2026-10-09T12:00:00Z", "deleted_at": null}""",
            )
        }

        assertNull(backend(engine).upsert("home", 1.0, 2.0, address = null).getOrThrow().address)
    }

    @Test
    fun `rename POSTs to the percent-encoded old label and decodes the moved reminders`() = runBlocking {
        val engine = EngineTestSupport.RecordingEngine {
            json("""{"place": ${row("katie house", "123 Main St")}, "reminders_moved": 2, "detail": "Renamed."}""")
        }

        val renamed = backend(engine).rename("fancy walmart", "katie house").getOrThrow()

        val request = engine.requests.single()
        assertEquals("POST", request.method.value)
        assertEquals("/api/places/fancy%20walmart/rename/", request.url.encodedPath)
        assertEquals("""{"to":"katie house"}""", (request.body as TextContent).text)
        assertEquals("katie house", renamed.place.label)
        assertEquals("123 Main St", renamed.place.address)
        assertEquals(2, renamed.remindersMoved)
    }

    @Test
    fun `a 409 rename is a failure carrying the engine's sentence`() = runBlocking {
        val engine = EngineTestSupport.RecordingEngine {
            json(
                """{"detail": "Nothing was renamed. There is already a saved place called 'work'."}""",
                HttpStatusCode.Conflict,
            )
        }

        val failure = backend(engine).rename("home", "work").exceptionOrNull() as EngineHttpException

        val refused = failure.failure as EngineFailure.Refused
        assertEquals(409, refused.status)
        assertTrue(engineRefusalSentence(refused.body).contains("already a saved place called 'work'"))
    }
}

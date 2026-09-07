package com.kevin.legion.backend.engine

import com.kevin.legion.backend.engine.EngineTestSupport.json
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.location.LocationController
import com.kevin.legion.location.PlaceController
import com.kevin.legion.testutil.RoomTestReset
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * [DjangoPlacesBackend] against `server/api/places.py`'s real shapes.
 *
 * **The fixture is the live engine's own reply**, captured 2026-09-07 from
 * `GET https://legion-757959564788.us-south1.run.app/api/places/` and trimmed only by dropping
 * nothing at all (it holds all three rows the household has). `fancy walmart` is a real saved
 * place and it is the reason the percent-encoding test below exists rather than being hypothetical.
 *
 * Two behaviours were confirmed against that same live engine rather than inferred from the Python:
 * `?active=1` really does drop a tombstoned row server-side (measured on `voice_notes`, which has
 * one), and `GET /api/places/fancy%20walmart/` resolves to the detail route (405 Method Not
 * Allowed, which only a MATCHED route can answer - an unmatched path is a 404).
 */
@RunWith(RobolectricTestRunner::class)
class DjangoPlacesBackendTest {

    private val context = RuntimeEnvironment.getApplication()

    private fun backend(engine: EngineTestSupport.RecordingEngine): DjangoPlacesBackend =
        DjangoPlacesBackend(EngineHttp(EngineTestSupport.signedInConfig(context), engine.client()))

    @After
    fun clearControllerState() {
        RoomTestReset.drainArchDiskIoPool()
        PlaceController.backendOverride = null
        setFix(null, null)
    }

    @Test
    fun `fetchActive parses the live engine's page and asks for live rows only`() = runBlocking {
        val engine = EngineTestSupport.RecordingEngine { json(EngineTestSupport.fixture("places_page.json")) }

        val places = backend(engine).fetchActive().getOrThrow()

        assertEquals(3, places.size)
        assertEquals(setOf("fancy walmart", "home", "work"), places.map { it.label }.toSet())
        val home = places.single { it.label == "home" }
        assertEquals(29.858552, home.latitude, 0.000001)
        assertEquals(-95.780431, home.longitude, 0.000001)
        assertFalse(home.deleted)
        // "2026-08-26T21:11:36.657888Z" - a Z-suffixed microsecond timestamp, which
        // OffsetDateTime.parse reads without help. java.time.Instant.parse would too, but the
        // Supabase side hands back "+00:00" and OffsetDateTime is what reads BOTH.
        assertEquals(1787778696657L, home.updatedAtMs)

        val url = engine.requests.single().url
        assertEquals("/api/places/", url.encodedPath)
        assertEquals("1", url.parameters["active"])
        // No watermark: the interface asks for "every active row", not "everything since".
        assertEquals(null, url.parameters["since"])
    }

    @Test
    fun `a tombstoned row is reported deleted rather than filtered away`() = runBlocking {
        // `?active=1` means the live engine never sends one of these to fetchActive - it filters
        // server-side, which is why this class has no client-side tombstone filter at all. The
        // property under test is that when a tombstone DOES arrive (a future since-feed, a server
        // that changed its mind) it is decoded as deleted rather than silently read as live.
        val body = """
            {"results": [{
              "id": "8869bfb7-a911-443d-82ac-541c86962338", "label": "work",
              "latitude": 29.782164, "longitude": -95.532689, "provenance": "USER",
              "created_at": "2026-08-26T21:11:36.903447Z",
              "updated_at": "2026-09-07T10:00:00Z",
              "deleted_at": "2026-09-07T10:00:00Z"
            }], "next": null}
        """.trimIndent()
        val engine = EngineTestSupport.RecordingEngine { json(body) }

        assertTrue(backend(engine).fetchActive().getOrThrow().single().deleted)
    }

    @Test
    fun `upsert PUTs to the percent-encoded label and sends only the writable columns`() = runBlocking {
        val engine = EngineTestSupport.RecordingEngine {
            json(
                """{"id": "b021c3f8-cf90-4a79-afd8-efc717a18f74", "label": "fancy walmart",
                    "latitude": 29.9, "longitude": -95.7, "provenance": "USER",
                    "created_at": "2026-08-26T21:11:36.342899Z",
                    "updated_at": "2026-09-07T12:00:00Z", "deleted_at": null}""",
            )
        }

        val saved = backend(engine).upsert("fancy walmart", 29.9, -95.7).getOrThrow()

        assertEquals("fancy walmart", saved.label)
        val request = engine.requests.single()
        assertEquals("PUT", request.method.value)
        // %20, not `+`: a `+` in a PATH segment is a literal plus and would address a place nobody
        // ever tagged. See EngineSyncedTable.detailPath's own doc comment.
        assertEquals("/api/places/fancy%20walmart/", request.url.encodedPath)

        val sent = (request.body as TextContent).text
        assertTrue(sent.contains("\"label\":\"fancy walmart\""))
        assertTrue(sent.contains("\"latitude\":29.9"))
        // Read-only server-side, and SyncedSerializer answers a 400 naming an unknown field rather
        // than dropping it - so this body has to be exactly the writable set, not a superset.
        assertFalse(sent.contains("provenance"))
        assertFalse(sent.contains("deleted_at"))
        assertFalse(sent.contains("updated_at"))
    }

    @Test
    fun `softDelete is true on a 204 and false on a 404, and issues exactly one request`() = runBlocking {
        val gone = EngineTestSupport.RecordingEngine { json("", HttpStatusCode.NoContent) }
        assertTrue(backend(gone).softDelete("work").getOrThrow())
        assertEquals("DELETE", gone.requests.single().method.value)
        assertEquals("/api/places/work/", gone.requests.single().url.encodedPath)

        val missing = EngineTestSupport.RecordingEngine {
            json("""{"detail": "Nothing was changed. No places row has label 'nowhere'."}""", HttpStatusCode.NotFound)
        }
        // Not a failure: "there was no such row" is an outcome the caller asked about, and
        // PlaceController turns it into "I don't have a saved place called ..." rather than into
        // an error. See PlacesBackend.softDelete's own contract.
        assertFalse(backend(missing).softDelete("nowhere").getOrThrow())
    }

    @Test
    fun `an unreachable engine fails in words and sends nothing`() = runBlocking {
        val http = EngineHttp(EngineTestSupport.signedInConfig(context), EngineTestSupport.unreachableClient())

        val result = DjangoPlacesBackend(http).upsert("home", 29.0, -95.0)

        assertTrue(result.isFailure)
        val failure = (result.exceptionOrNull() as EngineHttpException).failure
        assertTrue("an offline phone must be Unreachable, never Refused", failure is EngineFailure.Unreachable)
        // Unreachable is the ONE branch a caller may queue behind, because it is the only one where
        // the engine cannot have applied the write. Places does not queue - see the next test.
        assertTrue(failure.sentence.startsWith("Couldn't save that place."))
    }

    @Test
    fun `PlaceController over an unreachable engine says it did not save and queues nothing`() = runBlocking {
        // The aspect-level property this ticket's brief asks for: places is pure write-through and
        // this transport must not quietly give it durability the Supabase one does not have. Driven
        // through the real controller and a real DjangoPlacesBackend, so the assertion covers the
        // whole path rather than the backend alone.
        RoomTestReset.resetCarDatabaseSingleton()
        setFix(29.7604, -95.3698)
        PlaceController.backendOverride = DjangoPlacesBackend(
            EngineHttp(EngineTestSupport.signedInConfig(context), EngineTestSupport.unreachableClient()),
        )

        val spoken = PlaceController.tagPlace(context, "work")

        assertTrue(
            "a failed write must say in words that it did not save",
            spoken.contains("didn't save") || spoken.contains("went wrong"),
        )
        val db = CarDatabase.getDatabase(context)
        assertTrue("Room must never be written ahead of a server ACK", db.placeDao().getAll().isEmpty())
        assertTrue("places has no outbox and must not grow one", db.outboxDao().getAll().isEmpty())
    }

    private fun setFix(lat: Double?, lon: Double?) {
        val field = LocationController::class.java.getDeclaredField("_state")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val flow = field.get(LocationController) as MutableStateFlow<android.location.Location?>
        flow.value = if (lat == null || lon == null) {
            null
        } else {
            android.location.Location("test").apply { latitude = lat; longitude = lon }
        }
    }
}

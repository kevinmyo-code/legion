package com.kevin.legion.backend.engine

import com.kevin.legion.backend.MaintenanceScheduleUpload
import com.kevin.legion.backend.ObdSampleUpload
import com.kevin.legion.backend.ServiceHistoryUpload
import com.kevin.legion.backend.VehicleUpload
import com.kevin.legion.backend.engine.EngineTestSupport.json
import io.ktor.http.HttpMethod
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
 * [DjangoFleetBackend] - the aspect with six identity shapes, so most of these cases are about
 * WHICH URL went out rather than about what came back.
 *
 * Every shape and route is read off `server/openapi.yaml`: `/api/fleet/vehicles/{identity}/` is
 * keyed on `origin_guid`, the six shape-2 tables on `sync_id`, `chassis_quirks` on `quirk_id`,
 * `vehicle_specs` on `vehicle_id`, `maintenance_schedules` on the PAIR, and `obd_samples` on
 * nothing at all - it has no detail route.
 */
@RunWith(RobolectricTestRunner::class)
class DjangoFleetBackendTest {

    private val context = RuntimeEnvironment.getApplication()
    private val vehicleId = "11111111-2222-4333-8444-555555555555"

    private fun backend(engine: EngineTestSupport.RecordingEngine) =
        DjangoFleetBackend(EngineHttp(EngineTestSupport.signedInConfig(context), engine.client()))

    // --- obd_samples: the shape that is like nothing else ---------------------------------------

    @Test
    fun `the windowed OBD feed always sends vehicle, which the route makes mandatory`() = runBlocking {
        val page = """
            {"results": [{
              "vehicle_id": "$vehicleId", "pid": "0105", "value": 88.0, "unit": "C",
              "recorded_at": "2026-09-02T14:03:02.500000Z", "lat": null, "lng": null,
              "created_at": "2026-09-02T14:03:03Z"
            }], "next": null}
        """.trimIndent()
        val engine = EngineTestSupport.RecordingEngine { json(page) }

        val samples = backend(engine).fetchObdSamplesSince(vehicleId, 1_788_357_600_000L).getOrThrow()

        assertEquals(1, samples.size)
        assertEquals("0105", samples.single().pid)
        assertEquals(vehicleId, samples.single().vehicleServerId)

        val url = engine.requests.single().url
        assertEquals("/api/fleet/obd_samples/", url.encodedPath)
        // REQUIRED. `api/fleet.py`'s OBD_VEHICLE_PARAMETER: absent is a 400 naming this parameter,
        // because 20,796 rows unfiltered "is a download, not a feed".
        assertEquals(vehicleId, url.parameters["vehicle"])
        // `since` filters `recorded_at` - when the sample was read from the CAR, not when it
        // reached the server. Z-suffixed by Instant.toString().
        assertEquals("2026-09-02T14:00:00Z", url.parameters["since"])
        // No tombstone column on this table, so no ?active=1 to send.
        assertNull(url.parameters["active"])
    }

    @Test
    fun `a re-posted OBD batch is a normal 200 with nothing inserted, never an error`() = runBlocking {
        // Idempotent on the natural key `(vehicle_id, pid, recorded_at)` by construction - the
        // insert names the unique index as its `on conflict` target - and ObdSampleReconcile
        // resumes from a cursor it may have failed to advance, so a re-post is the NORMAL case.
        val engine = EngineTestSupport.RecordingEngine {
            json("""{"received":2,"inserted":0,"already_present":2,"duplicates_in_batch":0}""")
        }

        val result = backend(engine).uploadObdSampleBatch(listOf(sample("0105"), sample("010C")))

        assertTrue("nothing was written and that is a success: $result", result.isSuccess)
        val request = engine.requests.single()
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("/api/fleet/obd_samples/batch/", request.url.encodedPath)
        // A JSON ARRAY body, not an object wrapping one.
        assertTrue((request.body as TextContent).text.trimStart().startsWith("["))
    }

    @Test
    fun `a batch over the engine's cap of 1000 is refused before anything is sent`() = runBlocking {
        val engine = EngineTestSupport.RecordingEngine { json("{}") }

        val result = backend(engine).uploadObdSampleBatch(List(1001) { sample("PID$it") })

        assertTrue(result.isFailure)
        assertTrue("nothing left the phone: ${engine.requests}", engine.requests.isEmpty())
        val sentence = (result.exceptionOrNull() as EngineHttpException).failure.sentence
        assertTrue("the cap is named: $sentence", sentence.contains("1000"))
        // A truncated batch would report success for rows it never wrote - api/fleet.py's own
        // reasoning for refusing rather than trimming, mirrored here.
        assertTrue("and so is the count that was dropped: $sentence", sentence.contains("1001"))
    }

    @Test
    fun `exactly 1000 is inside the cap and is sent`() = runBlocking {
        val engine = EngineTestSupport.RecordingEngine {
            json("""{"received":1000,"inserted":1000,"already_present":0,"duplicates_in_batch":0}""")
        }

        val result = backend(engine).uploadObdSampleBatch(List(1000) { sample("PID$it") })

        assertTrue(result.isSuccess)
        assertEquals(1, engine.requests.size)
    }

    @Test
    fun `an empty batch sends nothing at all`() = runBlocking {
        val engine = EngineTestSupport.RecordingEngine { json("{}") }

        assertTrue(backend(engine).uploadObdSampleBatch(emptyList()).isSuccess)
        assertTrue(engine.requests.isEmpty())
    }

    @Test
    fun `the sample count is one integer off its own route, with no vehicle filter`() = runBlocking {
        val engine = EngineTestSupport.RecordingEngine { json("""{"count":20796}""") }

        assertEquals(20796L, backend(engine).countObdSamples().getOrThrow())

        val url = engine.requests.single().url
        assertEquals("/api/fleet/obd_samples/count/", url.encodedPath)
        // Optional here, unlike on the feed: an aggregate is one row whatever the table's size,
        // and the interface asks for the whole table.
        assertNull(url.parameters["vehicle"])
    }

    // --- maintenance_schedules: the pair --------------------------------------------------------

    @Test
    fun `a maintenance upsert addresses BOTH halves of the key, each encoded on its own`() = runBlocking {
        val row = """
            {"id":"99999999-2222-4333-8444-555555555555","vehicle_id":"$vehicleId",
             "service_name":"oil change","interval_miles":5000,"interval_months":6,
             "interval_source":"USER","never_done":false,"provenance":"USER",
             "created_at":"2026-09-01T00:00:00Z","updated_at":"2026-09-01T00:00:00Z","deleted_at":null}
        """.trimIndent()
        val engine = EngineTestSupport.RecordingEngine { json(row) }

        val saved = backend(engine).upsertMaintenanceSchedule(
            MaintenanceScheduleUpload(
                vehicleServerId = vehicleId,
                serviceName = "oil change",
                intervalMiles = 5000,
                intervalMonths = 6,
                intervalSource = "USER",
                neverDone = false,
                provenance = "USER",
            ),
        ).getOrThrow()

        assertEquals("oil change", saved.serviceName)
        assertEquals(vehicleId, saved.vehicleServerId)

        val request = engine.requests.single()
        assertEquals(HttpMethod.Put, request.method)
        // Two path segments, and the SPACE in "oil change" is %20 rather than + or %2F. A single
        // encoded identity would render the separator as %2F and address a row that cannot exist.
        assertEquals("/api/fleet/maintenance_schedules/$vehicleId/oil%20change/", request.url.encodedPath)
        // Neither half is repeated in the body - the URL is the authority on both.
        val body = (request.body as TextContent).text
        assertTrue("no vehicle_id in the body: $body", !body.contains("vehicle_id"))
        assertTrue("no service_name in the body: $body", !body.contains("service_name"))
        assertTrue(body.contains("\"interval_miles\":5000"))
    }

    @Test
    fun `the maintenance path helper encodes each key half separately`() {
        // Pinned directly as well as through the request above, because this is the one identity
        // in the API that cannot go through EngineSyncedTable's own single-segment encoder.
        assertEquals(
            "/api/fleet/maintenance_schedules/$vehicleId/brake%20fluid/",
            DjangoFleetBackend.maintenancePath(vehicleId, "brake fluid"),
        )
    }

    // --- the other identity shapes --------------------------------------------------------------

    @Test
    fun `a drive upserts on its sync_id and the id never rides in the body`() = runBlocking {
        val row = """
            {"id":"77777777-2222-4333-8444-555555555555","sync_id":"drive-42","vehicle_id":"$vehicleId",
             "started_at":"2026-09-02T14:00:00Z","ended_at":"2026-09-02T14:30:00Z","miles":12.4,
             "gallons":null,"end_reason":"IGNITION_OFF","provenance":"DETERMINISTIC",
             "created_at":"2026-09-02T14:31:00Z","updated_at":"2026-09-02T14:31:00Z","deleted_at":null}
        """.trimIndent()
        val engine = EngineTestSupport.RecordingEngine { json(row) }

        val saved = backend(engine).upsertDrive(
            com.kevin.legion.backend.DriveUpload(
                syncId = "drive-42",
                vehicleServerId = vehicleId,
                startedAtMs = 1_788_357_600_000L,
                endedAtMs = 1_788_359_400_000L,
                miles = 12.4,
                gallons = null,
                endReason = "IGNITION_OFF",
            ),
        ).getOrThrow()

        assertEquals("drive-42", saved.syncId)
        val request = engine.requests.single()
        assertEquals("/api/fleet/drives/drive-42/", request.url.encodedPath)
        val body = (request.body as TextContent).text
        assertTrue("sync_id comes from the URL only: $body", !body.contains("sync_id"))
        // explicitNulls: a PUT replaces the whole row, so a cleared gallons must be SENT as null
        // rather than omitted, or the stored value would survive.
        assertTrue("nulls are explicit: $body", body.contains("\"gallons\":null"))
    }

    @Test
    fun `chassis quirks and vehicle specs read a since-less feed, never active=1`() = runBlocking {
        val engine = EngineTestSupport.RecordingEngine { json("""{"results": [], "next": null}""") }
        val fleet = backend(engine)

        fleet.fetchChassisQuirks().getOrThrow()
        fleet.fetchVehicleSpecs().getOrThrow()

        // Neither table has a `deleted_at` column, so both set has_tombstones = False and neither
        // view advertises `active`. The unnarrowed feed already IS the live set.
        assertEquals(
            listOf("/api/fleet/chassis_quirks/", "/api/fleet/vehicle_specs/"),
            engine.requests.map { it.url.encodedPath },
        )
        assertTrue(engine.requests.all { it.url.parameters["active"] == null })
        assertTrue(engine.requests.all { it.url.parameters["since"] == null })
    }

    @Test
    fun `a table WITH tombstones does narrow its active fetch`() = runBlocking {
        val engine = EngineTestSupport.RecordingEngine { json("""{"results": [], "next": null}""") }

        backend(engine).fetchActiveDrives().getOrThrow()

        assertEquals("1", engine.requests.single().url.parameters["active"])
    }

    // --- the gap ---------------------------------------------------------------------------------

    @Test
    fun `a live vehicle upsert refuses in words rather than guessing at an origin_guid`() = runBlocking {
        val engine = EngineTestSupport.RecordingEngine { json("{}") }

        val result = backend(engine).upsertVehicle(
            VehicleUpload(
                serverId = null,
                name = "the wagon",
                make = "Subaru",
                model = "Outback",
                year = 2016,
                trim = null,
                engine = null,
                confirmed = true,
                odometerBaseline = null,
                odometerBaselineAtMs = null,
                archived = false,
                lastObdMac = null,
            ),
        )

        assertTrue(result.isFailure)
        assertTrue("nothing was sent: ${engine.requests}", engine.requests.isEmpty())
        val sentence = (result.exceptionOrNull() as EngineHttpException).failure.sentence
        // The refusal says WHY and says nothing landed - DjangoFleetBackend's class doc, "THE ONE
        // GAP". Minting an origin_guid for a live-created car would decide what that column means.
        assertTrue("names the identity mismatch: $sentence", sentence.contains("origin_guid"))
        assertTrue("and the absence: $sentence", sentence.contains("nothing was sent"))
    }

    @Test
    fun `a live service-history upsert refuses the same way`() = runBlocking {
        val engine = EngineTestSupport.RecordingEngine { json("{}") }

        val result = backend(engine).upsertServiceHistory(
            ServiceHistoryUpload(
                serverId = null,
                vehicleServerId = vehicleId,
                serviceName = "oil change",
                mileage = 91000,
                serviceDateEpochMs = null,
                costCents = 6500,
                kind = "ASSERTED",
            ),
        )

        assertTrue(result.isFailure)
        assertTrue(engine.requests.isEmpty())
    }

    private fun sample(pid: String) = ObdSampleUpload(
        vehicleServerId = vehicleId,
        pid = pid,
        value = 88.0,
        unit = "C",
        recordedAtMs = 1_788_357_600_000L,
        lat = null,
        lng = null,
    )
}

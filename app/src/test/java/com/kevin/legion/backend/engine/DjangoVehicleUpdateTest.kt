package com.kevin.legion.backend.engine

import com.kevin.legion.backend.FakePullFleetBackend
import com.kevin.legion.backend.FleetBackend
import com.kevin.legion.backend.FleetSync
import com.kevin.legion.backend.RemoteVehicle
import com.kevin.legion.backend.VehicleUpload
import com.kevin.legion.backend.engine.EngineTestSupport.json
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.testutil.RoomTestReset
import com.kevin.legion.vehicle.ActiveVehicle
import com.kevin.legion.vehicle.FleetEngineStore
import com.kevin.legion.vehicle.VehicleController
import io.ktor.http.content.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * [DjangoFleetBackend.upsertVehicle] updates a car the engine already holds through that row's own
 * `origin_guid` (2026-11-05). It used to refuse every call, and since a synced car is READ from
 * the replica only this write refreshes, an odometer reading on the Django transport landed on the
 * phone's own tables while every read kept the old number. Made-up car, made-up mac.
 */
@RunWith(RobolectricTestRunner::class)
class DjangoVehicleUpdateTest {

    private val context = RuntimeEnvironment.getApplication()
    private val serverId = "11111111-2222-4333-8444-555555555555"
    private val dongle = "AA:BB:CC:11:22:33"

    @Before
    fun clearState() {
        RoomTestReset.resetCarDatabaseSingleton()
        context.getSharedPreferences("active_vehicle", android.content.Context.MODE_PRIVATE).edit().clear().apply()
    }

    @After
    fun drain() {
        RoomTestReset.drainArchDiskIoPool()
        FleetEngineStore.backendOverride = null
    }

    private fun backend(engine: EngineTestSupport.RecordingEngine) =
        DjangoFleetBackend(EngineHttp(EngineTestSupport.signedInConfig(context), engine.client()))

    private fun row(originGuid: String?, odometer: Int = 228_000, updatedAt: String = "2026-11-05T20:00:00Z"): String {
        val guid = originGuid?.let { "\"$it\"" } ?: "null"
        return """{"id": "$serverId", "name": "Cherokee", "make": "Jeep", "model": "Cherokee", "year": 1998,
            "trim": null, "engine": null, "confirmed": true, "odometer_baseline": $odometer,
            "odometer_baseline_at": "2026-10-01T12:00:00Z", "provenance": "USER",
            "created_at": "2026-01-01T00:00:00Z", "updated_at": "$updatedAt", "deleted_at": null,
            "origin_guid": $guid, "archived": false, "last_obd_mac": "$dongle", "plate": "TEST 1"}"""
    }

    private fun page(vararg rows: String) = """{"results": [${rows.joinToString(",")}], "next": null}"""

    private fun upload(miles: Int, id: String? = serverId) = VehicleUpload(
        serverId = id, name = "Cherokee", make = "Jeep", model = "Cherokee", year = 1998, trim = null,
        engine = null, confirmed = true, odometerBaseline = miles, odometerBaselineAtMs = 1_762_400_000_000L,
        archived = false, lastObdMac = dongle,
    )

    private fun sentBody(engine: EngineTestSupport.RecordingEngine) =
        Json.parseToJsonElement((engine.requests.last().body as TextContent).text).jsonObject

    @Test
    fun `an update PUTs to the row's own origin_guid and leaves the plate alone`() = runBlocking {
        val engine = EngineTestSupport.RecordingEngine { request ->
            if (request.method.value == "GET") json(page(row("guid-jeep"))) else json(row("guid-jeep", 229_746))
        }

        val result = backend(engine).upsertVehicle(upload(229_746))

        assertTrue(result.exceptionOrNull()?.message ?: "", result.isSuccess)
        assertEquals(229_746, result.getOrThrow().odometerBaseline)
        assertEquals(listOf("GET", "PUT"), engine.requests.map { it.method.value })
        assertEquals("/api/fleet/vehicles/guid-jeep/", engine.requests[1].url.encodedPath)
        val sent = sentBody(engine)
        assertEquals(229_746, sent["odometer_baseline"]!!.jsonPrimitive.int)
        assertEquals(dongle, sent["last_obd_mac"]!!.jsonPrimitive.content)
        assertFalse("an omitted plate keeps the engine's stored one", sent.containsKey("plate"))
    }

    @Test
    fun `an insert is still refused and nothing is sent`() = runBlocking {
        val engine = EngineTestSupport.RecordingEngine { json("{}") }

        val result = backend(engine).upsertVehicle(upload(229_746, id = null))

        assertTrue(result.isFailure)
        assertTrue(engine.requests.isEmpty())
    }

    @Test
    fun `a car the engine does not list is refused and nothing is written`() = runBlocking {
        val engine = EngineTestSupport.RecordingEngine { json(page()) }

        val result = backend(engine).upsertVehicle(upload(229_746))

        assertTrue(result.isFailure)
        assertEquals("only the read went out", listOf("GET"), engine.requests.map { it.method.value })
    }

    @Test
    fun `a car with no origin_guid is refused in the plate's words and nothing is written`() = runBlocking {
        val engine = EngineTestSupport.RecordingEngine { json(page(row(null))) }

        val result = backend(engine).upsertVehicle(upload(229_746))

        val sentence = (result.exceptionOrNull() as EngineHttpException).failure.sentence
        assertEquals(DjangoVehiclePlateBackend.NO_GUID, sentence)
        assertEquals(listOf("GET"), engine.requests.map { it.method.value })
    }

    /** Kevin's sequence on the Django transport, through the real FleetEngineStore read path. */
    @Test
    fun `on the engine transport a pulled car's new reading is what the phone reads back`() = runBlocking {
        val jeep = RemoteVehicle(
            serverId = serverId, name = "Cherokee", make = "Jeep", model = "Cherokee", year = 1998,
            trim = null, engine = null, confirmed = true, odometerBaseline = 228_000,
            odometerBaselineAtMs = 1_000L, updatedAtMs = 2_000L, deleted = false,
            originGuid = "guid-jeep", archived = false, lastObdMac = dongle,
        )
        FleetSync.pull(context, object : FleetBackend by FakePullFleetBackend() {
            override suspend fun fetchChangedVehiclesSince(sinceMs: Long) = Result.success(listOf(jeep))
        })
        ActiveVehicle.select(context, dongle)

        var stored = 228_000
        val engine = EngineTestSupport.RecordingEngine { request ->
            if (request.method.value == "PUT") {
                val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                stored = body["odometer_baseline"]!!.jsonPrimitive.int
                json(row("guid-jeep", stored, updatedAt = "2026-11-05T20:20:00Z"))
            } else {
                json(page(row("guid-jeep", stored)))
            }
        }
        FleetEngineStore.backendOverride = backend(engine)

        val first = VehicleController.setOdometer(context, miles = 229_746, vehicleId = dongle)
        assertTrue(first.message, first.success)
        assertEquals("the engine holds the reading", 229_746, stored)
        val readBack = FleetEngineStore.getByMac(context, dongle)!!.odometerBaseline
        assertEquals("and the phone reads it back", 229_746, readBack)

        val register = VehicleController.registerDirect(context, year = 1998, make = "Jeep", model = "Cherokee")
        assertTrue(register.message, register.success)
        assertEquals(1, CarDatabase.getDatabase(context).vehicleDao().getAllIncludingArchived().size)
        assertEquals(
            "the replica still has the plate the engine holds",
            "TEST 1",
            CarDatabase.getDatabase(context).vehicleReplicaDao().getByServerId(serverId)!!.plate,
        )
    }
}

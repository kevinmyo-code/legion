package com.kevin.legion.vehicle

import com.kevin.legion.backend.FakePullFleetBackend
import com.kevin.legion.backend.FleetBackend
import com.kevin.legion.backend.FleetSync
import com.kevin.legion.backend.RemoteVehicle
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.VehicleReplica
import com.kevin.legion.data.local.VehicleSidecar
import com.kevin.legion.engine.fleet.FleetRecordBridge
import com.kevin.legion.testutil.RoomTestReset
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Kevin's conversation of 2026-11-05 ~20:18 CST, build 263092342, the Jeep's dongle connected:
 *
 *  1. `set_odometer({"miles":229746})` -> "I don't have this car on file yet..."
 *  2. `register_vehicle({make:"Jeep", model:"Cherokee", year:1998})` -> "Got it, this is the 1998
 *     Jeep Cherokee now."
 *  3. `set_odometer` again -> the same refusal.
 *
 * The car came onto the phone through [FleetSync.pull] (fleet is on the engine since f96821ef), and
 * the pull writes a legacy row, sidecar and replica but no engine `Vehicle` record. Every identity
 * writer in [FleetEngineStore] looked that record up and returned 0 without it, and
 * [VehicleController.registerDirect] threw the 0 away. These tests rebuild the car the way the pull
 * does and replay the sequence.
 */
@RunWith(RobolectricTestRunner::class)
class PulledCarOdometerTest {
    private val context = RuntimeEnvironment.getApplication()
    private val db get() = CarDatabase.getDatabase(context)

    private val dongle = "AA:BB:CC:11:22:33"

    private val jeep = RemoteVehicle(
        serverId = "srv-jeep",
        name = "Cherokee",
        make = "Jeep",
        model = "Cherokee",
        year = 1998,
        trim = null,
        engine = null,
        confirmed = true,
        odometerBaseline = 228_000,
        odometerBaselineAtMs = 1_000L,
        updatedAtMs = 2_000L,
        deleted = false,
        originGuid = "guid-jeep",
        archived = false,
        lastObdMac = dongle,
    )

    /** Serves the one Jeep to the pull; everything else is [FakePullFleetBackend]'s. */
    private class OneCarPull(private val car: RemoteVehicle) : FleetBackend by FakePullFleetBackend() {
        override suspend fun fetchChangedVehiclesSince(sinceMs: Long) = Result.success(listOf(car))
    }

    @Before
    fun clearState() {
        RoomTestReset.resetCarDatabaseSingleton()
        context.getSharedPreferences("active_vehicle", android.content.Context.MODE_PRIVATE).edit().clear().apply()
        FleetEngineStore.backendOverride = null
    }

    @After
    fun drain() {
        RoomTestReset.drainArchDiskIoPool()
        FleetEngineStore.backendOverride = null
    }

    private suspend fun pullTheJeep() {
        FleetSync.pull(context, OneCarPull(jeep))
        ActiveVehicle.select(context, dongle)
    }

    private suspend fun engineRecordExists() =
        db.engineRecordDao().getByGuid(FleetRecordBridge.vehicleGuid(dongle)) != null

    @Test
    fun `a pulled car is on file but has no engine record - the precondition`() = runBlocking {
        pullTheJeep()

        assertNotNull("the pull rebuilt the car under the dongle's mac", db.vehicleDao().getByMac(dongle))
        assertFalse("the pull writes no engine Vehicle record", engineRecordExists())
    }

    @Test
    fun `the exact sequence - set_odometer, register_vehicle, set_odometer - takes the reading`() = runBlocking {
        pullTheJeep()

        val first = VehicleController.setOdometer(context, miles = 229_746, vehicleId = dongle)
        assertTrue("the first set_odometer must take the reading: ${first.message}", first.success)
        assertFalse(first.message.contains("don't have this car on file"))
        assertEquals(229_746, db.vehicleDao().getByMac(dongle)!!.odometerBaseline)
        assertTrue("the missing engine record is backfilled from the legacy row", engineRecordExists())

        val register = VehicleController.registerDirect(context, year = 1998, make = "Jeep", model = "Cherokee")
        assertTrue(register.message, register.success)

        val again = VehicleController.setOdometer(context, miles = 229_750, vehicleId = dongle)
        assertTrue(again.message, again.success)
        assertEquals(229_750, db.vehicleDao().getByMac(dongle)!!.odometerBaseline)

        assertEquals("register_vehicle must not fork a phone-local duplicate", 1, db.vehicleDao().getAllIncludingArchived().size)
    }

    @Test
    fun `register_vehicle on a pulled car writes the identity it says it wrote`() = runBlocking {
        pullTheJeep()

        val outcome = VehicleController.registerDirect(context, year = 1997, make = "Jeep", model = "Cherokee")

        assertTrue(outcome.message, outcome.success)
        assertEquals(1997, db.vehicleDao().getByMac(dongle)!!.year)
        assertEquals(1, db.vehicleDao().getAllIncludingArchived().size)
    }

    /**
     * A car the configured read can see (sidecar + replica) with no legacy row behind it: the
     * identity write has nothing to write to. It used to answer "Got it" over that; it must say so.
     */
    @Test
    fun `register_vehicle says nothing changed when the identity write lands nowhere`() = runBlocking {
        FleetEngineStore.backendOverride = OneCarPull(jeep)
        db.vehicleSidecarDao().upsert(VehicleSidecar(serverId = "srv-jeep", obdMac = dongle))
        db.vehicleReplicaDao().insert(
            VehicleReplica(
                serverId = "srv-jeep", name = "Cherokee", make = "Jeep", model = "Cherokee", year = 1998,
                trim = null, engine = null, confirmed = true, odometerBaseline = 228_000,
                odometerBaselineAtMs = 1_000L, updatedAtMs = 2_000L, deleted = false,
                originGuid = "guid-jeep", archived = false,
            ),
        )
        ActiveVehicle.select(context, dongle)
        assertNotNull("the configured read sees the car", FleetEngineStore.getByMac(context, dongle))
        assertNull(db.vehicleDao().getByMac(dongle))

        val outcome = VehicleController.registerDirect(context, year = 1998, make = "Jeep", model = "Cherokee")

        assertFalse("success must follow the write, not the attempt", outcome.success)
        assertFalse(outcome.message, outcome.message.startsWith("Got it"))
        assertTrue(outcome.message, outcome.message.contains("nothing changed"))
    }
}

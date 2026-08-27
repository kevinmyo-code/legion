package com.kevin.legion.service

import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.MaintenanceItem
import com.kevin.legion.data.local.Vehicle
import com.kevin.legion.testutil.RoomTestReset
import com.kevin.legion.vehicle.ActiveVehicle
import com.kevin.legion.vehicle.FleetEngineStore
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * End-to-end regression for `get_next_service`'s spoken guess caveat (mission-control ticket 16,
 * `.scratch/fleet-maintenance/issues/16-ticket-06-audited-a-dead-surface-and-missed-a-live-one.md`
 * - "the caveat carry ALOUD because a tag cannot be heard"). [LiveToolboxVehicleScopingTest] already
 * exercises `get_next_service` through the real [LiveToolbox.dispatch] path with Robolectric-backed
 * Room; this file reuses that same shape to check the actual SENTENCE the model would speak, not
 * just [com.kevin.legion.vehicle.VehicleController.ServiceCandidate.isGuess] in isolation (that
 * narrower regression lives in [com.kevin.legion.vehicle.VehicleControllerServiceCandidateIsGuessTest]).
 */
@RunWith(RobolectricTestRunner::class)
class LiveToolboxNextServiceGuessCaveatTest {
    private val context = RuntimeEnvironment.getApplication()

    @Before
    fun clearState() {
        RoomTestReset.resetCarDatabaseSingleton()
        context.getSharedPreferences("active_vehicle", android.content.Context.MODE_PRIVATE).edit().clear().apply()
    }

    private suspend fun seedVehicle(mac: String, name: String) {
        // Cutover 4 (docs/architecture/cutover4-2026-08-24.md): a real ENGINE Vehicle record.
        FleetEngineStore.createVehicle(
            context,
            Vehicle(obdMac = mac, name = name, make = "Test", model = "Car", year = 2020, personaPrompt = "", odometerBaseline = 2000, odometerBaselineAt = 1L),
        )
    }

    @After
    fun drainRoomInvalidationTracker() {
        // A DAO write anywhere in this test can schedule a Room InvalidationTracker refresh
        // on ArchTaskExecutor's disk-IO pool. If that refresh is still queued or running when
        // this test method returns, it races Robolectric's own per-@Test-METHOD native SQLite
        // reset and throws "Illegal connection pointer" on a background thread - uncaught, and
        // blamed by kotlinx-coroutines-test on whatever runTest starts next, not on this class.
        // Draining here, before Robolectric ever gets a chance to reset, is the fix - see
        // RoomTestReset's own class doc comment
        // (.scratch/hardening/issues/13-the-suite-is-green-by-luck.md) for the full account.
        RoomTestReset.drainArchDiskIoPool()
    }


    @Test
    fun `a SEEDED interval's spoken next-service line carries the guess caveat aloud`() = runBlocking {
        seedVehicle("AA:BB", "Outlander")
        FleetEngineStore.upsertNewItem(
            context,
            MaintenanceItem(vehicleId = "AA:BB", serviceName = "Oil Change", intervalSource = "SEEDED", intervalMiles = 5000, lastDoneMileage = 1000),
        )
        ActiveVehicle.select(context, "AA:BB")

        val result = LiveToolbox.dispatch(context, "get_next_service", JSONObject())!!

        assertTrue(result.getBoolean("success"))
        assertTrue(
            "expected the spoken guess caveat, got: ${result.getString("message")}",
            result.getString("message").contains("though that interval is LEGION's guess, not one you've confirmed"),
        )
    }

    @Test
    fun `a CONFIRMED interval's spoken next-service line carries no caveat at all`() = runBlocking {
        seedVehicle("CC:DD", "Miata")
        FleetEngineStore.upsertNewItem(
            context,
            MaintenanceItem(vehicleId = "CC:DD", serviceName = "Oil Change", intervalSource = "CONFIRMED", intervalMiles = 5000, lastDoneMileage = 1000),
        )
        ActiveVehicle.select(context, "CC:DD")

        val result = LiveToolbox.dispatch(context, "get_next_service", JSONObject())!!

        assertTrue(result.getBoolean("success"))
        assertFalse(result.getString("message").contains("guess"))
    }
}

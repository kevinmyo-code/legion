package com.kevin.legion.service

import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.MaintenanceItem
import com.kevin.legion.data.local.Vehicle
import com.kevin.legion.testutil.RoomTestReset
import com.kevin.legion.vehicle.ActiveVehicle
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
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
        CarDatabase.getDatabase(context).vehicleDao().upsert(
            Vehicle(obdMac = mac, name = name, make = "Test", model = "Car", year = 2020, personaPrompt = "", odometerBaseline = 2000, odometerBaselineAt = 1L),
        )
    }

    @Test
    fun `a SEEDED interval's spoken next-service line carries the guess caveat aloud`() = runBlocking {
        seedVehicle("AA:BB", "Outlander")
        CarDatabase.getDatabase(context).maintenanceItemDao().upsert(
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
        CarDatabase.getDatabase(context).maintenanceItemDao().upsert(
            MaintenanceItem(vehicleId = "CC:DD", serviceName = "Oil Change", intervalSource = "CONFIRMED", intervalMiles = 5000, lastDoneMileage = 1000),
        )
        ActiveVehicle.select(context, "CC:DD")

        val result = LiveToolbox.dispatch(context, "get_next_service", JSONObject())!!

        assertTrue(result.getBoolean("success"))
        assertFalse(result.getString("message").contains("guess"))
    }
}

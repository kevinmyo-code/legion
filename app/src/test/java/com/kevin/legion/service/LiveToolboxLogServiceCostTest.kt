package com.kevin.legion.service

import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.Vehicle
import com.kevin.legion.testutil.RoomTestReset
import com.kevin.legion.vehicle.ActiveVehicle
import com.kevin.legion.vehicle.FleetEngineStore
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * `log_service`'s new `cost` argument (ticket 11 §2,
 * `.scratch/fleet-maintenance/issues/11-service-history-cost-and-fleet-spend.md`) - the
 * dollars-to-cents conversion at the voice edge (CLAUDE.md §4 rule 3: [com.kevin.legion.vehicle.VehicleController.logServiceDirect]
 * only ever sees `Long` cents). Robolectric, same shape as [LiveToolboxVehicleScopingTest].
 */
@RunWith(RobolectricTestRunner::class)
class LiveToolboxLogServiceCostTest {
    private val context = RuntimeEnvironment.getApplication()

    @Before
    fun clearState() = runBlocking {
        RoomTestReset.resetCarDatabaseSingleton()
        // Cutover 4 (docs/architecture/cutover4-2026-08-24.md): a real ENGINE Vehicle record.
        FleetEngineStore.createVehicle(
            context,
            Vehicle(
                obdMac = "V1", name = "Cherokee", make = "Jeep", model = "Cherokee", year = 1998,
                personaPrompt = "", odometerBaseline = 227_000, confirmed = true,
            )
        )
        ActiveVehicle.select(context, "V1")
    }

    @Test
    fun `log_service with a cost argument converts dollars to cents exactly`() = runBlocking {
        val args = JSONObject().put("service", "oil change").put("cost", 45.99)

        val result = LiveToolbox.dispatch(context, "log_service", args)

        assertTrue(result?.optBoolean("success") == true)
        val record = FleetEngineStore.getRecentForVehicle(context, "V1", 1).single()
        assertEquals(4599L, record.costCents)
    }

    @Test
    fun `log_service with no cost argument leaves costCents null`() = runBlocking {
        val args = JSONObject().put("service", "tire rotation")

        LiveToolbox.dispatch(context, "log_service", args)

        val record = FleetEngineStore.getRecentForVehicle(context, "V1", 1).single()
        assertNull(record.costCents)
    }

    @Test
    fun `log_service with a non-positive cost is treated as no cost given, never a negative charge`() = runBlocking {
        val args = JSONObject().put("service", "brake fluid").put("cost", -5.0)

        LiveToolbox.dispatch(context, "log_service", args)

        val record = FleetEngineStore.getRecentForVehicle(context, "V1", 1).single()
        assertNull("a misheard/negative cost must never write a negative charge", record.costCents)
    }
}

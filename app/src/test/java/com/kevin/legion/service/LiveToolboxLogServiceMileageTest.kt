package com.kevin.legion.service

import com.kevin.legion.data.local.Vehicle
import com.kevin.legion.testutil.RoomTestReset
import com.kevin.legion.vehicle.ActiveVehicle
import com.kevin.legion.vehicle.FleetEngineStore
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * `log_service`'s `mileage` argument - the car plan's slice 0
 * (`C:\Users\kevin\.legion\reports\car-maintenance-plan-1124.md`, "the one-line version of why he
 * does not use it"). "I got my oil changed today at 42,300" had nowhere to put 42,300: the tool
 * took `service` and `cost` only, and
 * [com.kevin.legion.vehicle.VehicleController.logServiceDirect] filled the record's mileage from
 * `currentMileage` - the odometer baseline plus integrated trip miles, an ESTIMATE - and filed it on
 * an `OBSERVED` row. So the driver stated a real reading, it was discarded, and the app's own guess
 * was stored as an observation.
 *
 * These go through [LiveToolbox.dispatch] with the raw JSON a model sends, the same shape
 * [LiveToolboxLogServiceCostTest] uses for the `cost` argument, so the voice edge's own parsing is
 * under test and not just the controller it calls.
 */
@RunWith(RobolectricTestRunner::class)
class LiveToolboxLogServiceMileageTest {
    private val context = RuntimeEnvironment.getApplication()

    @Before
    fun clearState() = runBlocking {
        RoomTestReset.resetCarDatabaseSingleton()
        // Baseline 40,000 with no trip miles accrued, so `currentMileage`'s estimate is 40,000 and
        // every assertion below distinguishes the stated figure from the estimate rather than
        // passing on a coincidence.
        FleetEngineStore.createVehicle(
            context,
            Vehicle(
                obdMac = "V1", name = "Cherokee", make = "Jeep", model = "Cherokee", year = 1998,
                personaPrompt = "", odometerBaseline = 40_000, confirmed = true,
            )
        )
        ActiveVehicle.select(context, "V1")
    }

    @After
    fun drainRoomInvalidationTracker() {
        // See LiveToolboxLogServiceCostTest's own note: a queued Room InvalidationTracker refresh
        // races Robolectric's per-method SQLite reset and is blamed on the next test to run.
        RoomTestReset.drainArchDiskIoPool()
    }

    @Test
    fun `log_service records the mileage he stated, never the estimate`() = runBlocking {
        // "I got my oil changed today at 42,300."
        val args = JSONObject().put("service", "oil change").put("mileage", 42_300)

        val result = LiveToolbox.dispatch(context, "log_service", args)

        assertTrue("Was: $result", result?.optBoolean("success") == true)
        val record = FleetEngineStore.getRecentForVehicle(context, "V1", 1).single()
        assertEquals("the stated reading is the fact; 40,000 is the app's own guess", 42_300, record.mileage)
    }

    @Test
    fun `log_service with a stated mileage says that figure back`() = runBlocking {
        val args = JSONObject().put("service", "oil change").put("mileage", 42_300)

        val result = LiveToolbox.dispatch(context, "log_service", args)

        val message = result?.optString("message").orEmpty()
        assertTrue("the reply must state what landed. Was: $message", message.contains("42300") || message.contains("42,300"))
    }

    @Test
    fun `log_service with no stated mileage files no mileage, never the estimate as an observation`() = runBlocking {
        val args = JSONObject().put("service", "tire rotation")

        LiveToolbox.dispatch(context, "log_service", args)

        val record = FleetEngineStore.getRecentForVehicle(context, "V1", 1).single()
        assertNull(
            "an unstated mileage is unknown, and an OBSERVED row may not carry a guess as an observation",
            record.mileage,
        )
    }

    @Test
    fun `log_service with a stated mileage moves the odometer to it`() = runBlocking {
        val args = JSONObject().put("service", "oil change").put("mileage", 42_300)

        LiveToolbox.dispatch(context, "log_service", args)

        // Without this the anchor sits 2,300 miles above the odometer the due math reads, so the
        // next oil change would come due 2,300 miles late. He stated today's reading; it is one.
        val vehicle = com.kevin.legion.vehicle.VehicleController.vehicleFor(context, "V1")
        assertEquals(42_300, vehicle.odometerBaseline)
        assertEquals(0.0, vehicle.tripMilesSinceBaseline, 0.001)
    }

    @Test
    fun `log_service with a misheard mileage refuses and writes nothing`() = runBlocking {
        val args = JSONObject().put("service", "oil change").put("mileage", 7)

        val result = LiveToolbox.dispatch(context, "log_service", args)

        assertTrue("a reading out of range must be questioned, not filed. Was: $result", result?.optBoolean("success") == false)
        assertTrue(FleetEngineStore.getRecentForVehicle(context, "V1", 10).isEmpty())
    }
}

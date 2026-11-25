package com.kevin.legion.vehicle

import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.MaintenanceItem
import com.kevin.legion.data.local.Vehicle
import com.kevin.legion.testutil.RoomTestReset
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Blocker B of round 2 of the RED review on PR #5: the question every due surface asks must be
 * answerable by the thing it asks for.
 *
 * A miles-only item logged with no reading is named on the spoken answer, on `CarCards`, in both
 * digests and in Matrix, each asking for an odometer reading
 * ([needsMileageReading]). The only tool that takes one is `set_odometer`, and it wrote the
 * vehicle's baseline and nothing else - so the item still needed a reading the instant after the
 * driver gave one, the identical question came back the next time he asked what was due, and
 * Matrix's "Say your mileage and it will" was a promise the code could not keep.
 *
 * The one route is [pinStatedReadingToWaitingItems], through `setOdometer`. These cases run the
 * whole way: log with no reading, state a reading, and read the state the surfaces read.
 *
 * Robolectric through the real [CarDatabase.getDatabase] path, same shape as
 * [VehicleControllerServiceWritesTest].
 */
@RunWith(RobolectricTestRunner::class)
class OdometerAnswersTheMileageAskTest {
    private val context = RuntimeEnvironment.getApplication()

    @Before
    fun clearState() = runBlocking {
        RoomTestReset.resetCarDatabaseSingleton()
        FleetEngineStore.createVehicle(
            context,
            Vehicle(
                obdMac = "V1", name = "Cherokee", make = "Jeep", model = "Cherokee", year = 1998,
                personaPrompt = "", odometerBaseline = 227_000, confirmed = true,
            ),
        )
    }

    @After
    fun drainRoomInvalidationTracker() = RoomTestReset.drainArchDiskIoPool()

    /** The state the ask comes from: a miles-only item, logged today with no reading. */
    private suspend fun aMilesOnlyItemLoggedWithNoReading() {
        FleetEngineStore.upsertNewItem(
            context,
            MaintenanceItem(vehicleId = "V1", serviceName = "Oil Change", intervalMiles = 5_000),
        )
        VehicleController.logServiceDirect(context, "oil change", vehicleId = "V1")
    }

    /** What every due surface reads, off the state these writes left behind. */
    private suspend fun next(): VehicleController.NextService {
        val vehicle = VehicleController.vehicleFor(context, "V1")
        return VehicleController.computeNextService(
            items = FleetEngineStore.getForVehicle(context, "V1"),
            currentMileage = VehicleController.currentMileage(vehicle),
            now = System.currentTimeMillis(),
            odometerBaseline = vehicle.odometerBaseline,
        )!!
    }

    @Test
    fun `the ask exists before the reading is given`() = runBlocking {
        aMilesOnlyItemLoggedWithNoReading()

        assertEquals(listOf("Oil Change"), next().needsMileage)
    }

    @Test
    fun `a stated odometer reading becomes the anchor of the item that was waiting for one`() = runBlocking {
        aMilesOnlyItemLoggedWithNoReading()
        val dateBefore = FleetEngineStore.get(context, "V1", "Oil Change")!!.lastDoneDate

        val outcome = VehicleController.setOdometer(context, 235_000, vehicleId = "V1")

        assertTrue("Was: ${outcome.message}", outcome.success)
        val item = FleetEngineStore.get(context, "V1", "Oil Change")!!
        assertEquals("the reading the driver stated is the anchor", 235_000, item.lastDoneMileage)
        assertNotNull("and the day the service happened is not dropped to make room for it", item.lastDoneDate)
        assertEquals(dateBefore, item.lastDoneDate)
        assertEquals(
            "so the surfaces stop asking - which is the whole point of the write",
            emptyList<String>(), next().needsMileage,
        )
    }

    @Test
    fun `the reply names the item that took the reading, so a write he did not ask for is not silent`() = runBlocking {
        aMilesOnlyItemLoggedWithNoReading()

        val outcome = VehicleController.setOdometer(context, 235_000, vehicleId = "V1")

        assertTrue("Was: ${outcome.message}", outcome.message.contains("Oil Change"))
        assertTrue("and the figure it now holds. Was: ${outcome.message}", outcome.message.contains("235000"))
    }

    @Test
    fun `a reading says nothing about items that were not waiting for one`() = runBlocking {
        FleetEngineStore.upsertNewItem(
            context,
            MaintenanceItem(
                vehicleId = "V1", serviceName = "Tire Rotation", intervalMiles = 10_000,
                lastDoneMileage = 230_000, lastDoneDate = System.currentTimeMillis(),
            ),
        )

        val outcome = VehicleController.setOdometer(context, 235_000, vehicleId = "V1")

        assertTrue("Was: ${outcome.message}", outcome.success)
        assertEquals(
            "an anchored item keeps the anchor it had",
            230_000, FleetEngineStore.get(context, "V1", "Tire Rotation")!!.lastDoneMileage,
        )
        assertTrue(
            "and nothing is claimed about it. Was: ${outcome.message}",
            !outcome.message.contains("Tire Rotation"),
        )
    }
}

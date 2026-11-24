package com.kevin.legion.vehicle

import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.Vehicle
import com.kevin.legion.testutil.RoomTestReset
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The write half of blocker 2 in the RED review on PR #5: the edit form may not invent a reading.
 *
 * The dialog's reason for existing is adding a cost to an existing record retroactively (ticket 11
 * section 2). Its mileage box was pre-filled with `record.mileage ?: 0` and SAVE wrote whatever was
 * in it unconditionally, so opening a voice-logged, mileage-less record to add a cost turned "he
 * gave no reading" into a STATED reading of zero - which projects onto the item's mileage anchor and
 * reads the service as overdue from then on. The form's own parse rule is pinned in
 * [com.kevin.legion.vehicle.MileagelessRecordReadersTest]; this is the controller and the row.
 *
 * Robolectric through the real [CarDatabase.getDatabase] path, same shape as
 * [VehicleControllerServiceWritesTest].
 */
@RunWith(RobolectricTestRunner::class)
class ServiceRecordNullMileageEditTest {
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
    fun drainRoomInvalidationTracker() {
        // See VehicleControllerServiceWritesTest's own note: a queued Room InvalidationTracker
        // refresh races Robolectric's per-method SQLite reset and is blamed on the next test.
        RoomTestReset.drainArchDiskIoPool()
    }

    @Test
    fun `a cost can be added to a mileage-less record without a reading being written`() = runBlocking {
        VehicleController.logServiceDirect(context, "oil change", vehicleId = "V1")
        val id = FleetEngineStore.getRecentForVehicle(context, "V1", 1).single().id

        val outcome = VehicleController.editServiceRecordDirect(context, id, mileageMiles = null, costCents = 4599)

        assertTrue("Was: $outcome", outcome.success)
        val after = FleetEngineStore.getServiceRecordById(context, id)!!
        assertNull("a blank box keeps the row with no reading", after.mileage)
        assertEquals(4599L, after.costCents)
        assertFalse("and the read-back states no figure. Was: ${outcome.message}", outcome.message.contains("0 miles"))
    }

    @Test
    fun `a real reading typed into the form still lands, and is read back`() = runBlocking {
        VehicleController.logServiceDirect(context, "oil change", vehicleId = "V1")
        val id = FleetEngineStore.getRecentForVehicle(context, "V1", 1).single().id

        val outcome = VehicleController.editServiceRecordDirect(context, id, mileageMiles = 227_500, costCents = null)

        assertTrue("Was: $outcome", outcome.success)
        assertEquals(227_500, FleetEngineStore.getServiceRecordById(context, id)!!.mileage)
        assertTrue("Was: ${outcome.message}", outcome.message.contains("227500"))
    }

    @Test
    fun `clearing a reading that was there is a write the read-back states`() = runBlocking {
        // Correcting a mistyped mileage down to "I don't actually know" is a legitimate edit, and
        // the reply must not then name a figure the row no longer holds.
        VehicleController.logServiceDirect(context, "oil change", vehicleId = "V1", statedMileage = 227_500)
        val id = FleetEngineStore.getRecentForVehicle(context, "V1", 1).single().id

        val outcome = VehicleController.editServiceRecordDirect(context, id, mileageMiles = null, costCents = null)

        assertTrue("Was: $outcome", outcome.success)
        assertNull(FleetEngineStore.getServiceRecordById(context, id)!!.mileage)
        assertFalse("Was: ${outcome.message}", outcome.message.contains("227500"))
        assertTrue("Was: ${outcome.message}", outcome.message.contains("no mileage logged"))
    }
}

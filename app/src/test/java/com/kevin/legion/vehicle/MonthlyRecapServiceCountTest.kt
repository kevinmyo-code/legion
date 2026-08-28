package com.kevin.legion.vehicle

import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.Vehicle
import com.kevin.legion.testutil.RoomTestReset
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Regression for the under-count [MonthlyRecapController.generate] named in its own comment
 * ("a service logged after this branch lands... will under-count here until MonthlyRecap's own
 * follow-up wave repoints this read") - engine retirement step 3 (ticket 16) closes it by
 * repointing [FleetEngineStore.insertObserved] back onto `service_records`, the exact table
 * [MonthlyRecapController.generate] reads via `db.serviceRecordDao().countInRange`.
 *
 * This does not exercise [MonthlyRecapController.generate] itself - that function also calls
 * [com.kevin.legion.ai.SubAgent] for the narrative, a real network dependency out of scope for a
 * unit test - it instead proves the DAO read `generate` performs directly reflects a service logged
 * through TODAY's real write path, which is the entire mechanism the under-count comment describes.
 */
@RunWith(RobolectricTestRunner::class)
class MonthlyRecapServiceCountTest {
    private val context = RuntimeEnvironment.getApplication()
    private val db get() = CarDatabase.getDatabase(context)
    private val mac = "V1"

    @Before
    fun clearState() = runBlocking {
        RoomTestReset.resetCarDatabaseSingleton()
        FleetEngineStore.createVehicle(
            context,
            Vehicle(obdMac = mac, name = "Cherokee", make = "Jeep", model = "Cherokee", year = 1998, personaPrompt = "", confirmed = true),
        )
    }

    @After
    fun drainRoomInvalidationTracker() {
        RoomTestReset.drainArchDiskIoPool()
    }

    @Test
    fun `a service logged through the current write path is counted by the same query MonthlyRecap uses`() = runBlocking {
        val fromMs = 1_700_000_000_000L
        val toMs = 1_710_000_000_000L
        val loggedInsideWindow = fromMs + 1_000L

        // The real, current write path - not a direct DAO insert - proving the whole chain
        // (FleetEngineStore.insertObserved -> service_records) is what MonthlyRecap now reads.
        val result = FleetEngineStore.insertObserved(context, mac, "Oil Change", mileage = 100_000, date = loggedInsideWindow, costCents = null)
        assertEquals(true, result is FleetEngineStore.InsertObservedResult.Success)

        val count = db.serviceRecordDao().countInRange(mac, fromMs, toMs)

        assertEquals("the post-repoint write is visible to MonthlyRecap's own query - the gap is closed", 1, count)
    }

    @Test
    fun `an ASSERTED anchor inside the window is not counted as a service performed`() = runBlocking {
        val fromMs = 1_700_000_000_000L
        val toMs = 1_710_000_000_000L

        FleetEngineStore.upsertNewItem(context, com.kevin.legion.data.local.MaintenanceItem(vehicleId = mac, serviceName = "Brake Pads"))
        FleetEngineStore.setAnchor(context, mac, "Brake Pads", mileage = 90_000, date = fromMs + 500L, now = fromMs + 500L)

        val count = db.serviceRecordDao().countInRange(mac, fromMs, toMs)

        assertEquals("a driver-stated anchor is not a logged service", 0, count)
    }
}

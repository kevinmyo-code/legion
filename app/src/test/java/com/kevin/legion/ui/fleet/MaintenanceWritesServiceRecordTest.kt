package com.kevin.legion.ui.fleet

import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.MaintenanceItem
import com.kevin.legion.data.local.Vehicle
import com.kevin.legion.testutil.RoomTestReset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Ticket 07 (command-center, `.scratch/command-center/issues/07-build-sheet-screen.md`), point 3:
 * "the maintenance done-flow gets an optional cost+notes step that writes a real `service_records`
 * row through the same path `log_service` uses, not just the anchor." Pins [writeSetAnchor]'s new
 * `costCents` addendum - see that function's own doc comment for the full trace of why the
 * omission was real (not a documented decision this addendum overrides) and why this is a direct
 * [com.kevin.legion.data.local.ServiceRecordDao.insert] rather than a call to
 * [com.kevin.legion.vehicle.VehicleController.logServiceDirect] itself.
 *
 * Robolectric through the real [CarDatabase.getDatabase] path, same shape as
 * [com.kevin.legion.vehicle.VehicleControllerServiceWritesTest].
 */
@RunWith(RobolectricTestRunner::class)
class MaintenanceWritesServiceRecordTest {
    private val context = RuntimeEnvironment.getApplication()
    private val db get() = CarDatabase.getDatabase(context)

    @Before
    fun clearState() = runBlocking {
        RoomTestReset.resetCarDatabaseSingleton()
        db.vehicleDao().upsert(
            Vehicle(
                obdMac = "V1", name = "Cherokee", make = "Jeep", model = "Cherokee", year = 1998,
                personaPrompt = "", odometerBaseline = 227_000, confirmed = true,
            ),
        )
        db.maintenanceItemDao().upsertStamped(
            MaintenanceItem(vehicleId = "V1", serviceName = "Oil Change", intervalMiles = 5_000),
        )
    }

    @Test
    fun `DONE_AT with a cost moves the anchor AND writes a service_records row`() = runBlocking {
        val outcome = writeSetAnchor(
            context, "V1", "Oil Change", AnchorMode.DONE_AT, mileage = 231_500, date = 1_700_000_000_000L, costCents = 4_599L,
        )

        assertTrue("A landed write must report success. Was: $outcome", outcome.success)

        val item = db.maintenanceItemDao().get("V1", "Oil Change")!!
        assertEquals(231_500, item.lastDoneMileage)
        assertEquals(1_700_000_000_000L, item.lastDoneDate)

        val records = db.serviceRecordDao().getRecentForVehicle("V1", 10)
        assertEquals(1, records.size)
        val record = records.single()
        assertEquals("Oil Change", record.serviceName)
        assertEquals(231_500, record.mileage)
        assertEquals(1_700_000_000_000L, record.date)
        assertEquals(4_599L, record.costCents)
    }

    @Test
    fun `DONE_AT without a cost still moves the anchor and writes no service_records row`() = runBlocking {
        val outcome = writeSetAnchor(
            context, "V1", "Oil Change", AnchorMode.DONE_AT, mileage = 231_500, date = 1_700_000_000_000L, costCents = null,
        )

        assertTrue("Skipping cost stays legal. Was: $outcome", outcome.success)

        val item = db.maintenanceItemDao().get("V1", "Oil Change")!!
        assertEquals(231_500, item.lastDoneMileage)

        assertTrue(db.serviceRecordDao().getRecentForVehicle("V1", 10).isEmpty())
    }

    @Test
    fun `NEVER_DONE with a cost ignores the cost - the anchor mode gates the record, not the field alone`() = runBlocking {
        val outcome = writeSetAnchor(context, "V1", "Oil Change", AnchorMode.NEVER_DONE, mileage = null, date = null, costCents = 4_599L)

        assertTrue(outcome.success)
        val item = db.maintenanceItemDao().get("V1", "Oil Change")!!
        assertTrue(item.neverDone)
        assertTrue(db.serviceRecordDao().getRecentForVehicle("V1", 10).isEmpty())
    }

    @Test
    fun `DONE_AT with a cost but no typed mileage falls back to the vehicle's current live mileage`() = runBlocking {
        // odometerBaseline = 227_000, no trip miles accrued - currentMileage reads exactly that.
        val outcome = writeSetAnchor(context, "V1", "Oil Change", AnchorMode.DONE_AT, mileage = null, date = null, costCents = 5_000L)

        assertTrue(outcome.success)
        val record = db.serviceRecordDao().getRecentForVehicle("V1", 10).single()
        assertEquals(227_000, record.mileage)
        assertNull(db.maintenanceItemDao().get("V1", "Oil Change")!!.lastDoneMileage)
    }
}

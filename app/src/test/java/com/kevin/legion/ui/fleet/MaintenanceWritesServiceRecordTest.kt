package com.kevin.legion.ui.fleet

import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.MaintenanceItem
import com.kevin.legion.data.local.Vehicle
import com.kevin.legion.testutil.RoomTestReset
import com.kevin.legion.vehicle.FleetEngineStore
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
        // Cutover 4 (docs/architecture/cutover4-2026-08-24.md): a real ENGINE Vehicle record, not
        // just the legacy mirror - every FleetEngineStore write resolves the vehicle by its
        // deterministic engine guid.
        FleetEngineStore.createVehicle(
            context,
            Vehicle(
                obdMac = "V1", name = "Cherokee", make = "Jeep", model = "Cherokee", year = 1998,
                personaPrompt = "", odometerBaseline = 227_000, confirmed = true,
            ),
        )
        FleetEngineStore.upsertNewItem(context, MaintenanceItem(vehicleId = "V1", serviceName = "Oil Change", intervalMiles = 5_000))
    }

    @Test
    fun `DONE_AT with a cost moves the anchor AND writes a service_records row`() = runBlocking {
        val outcome = writeSetAnchor(
            context, "V1", "Oil Change", AnchorMode.DONE_AT, mileage = 231_500, date = 1_700_000_000_000L, costCents = 4_599L,
        )

        assertTrue("A landed write must report success. Was: $outcome", outcome.success)

        val item = FleetEngineStore.get(context, "V1", "Oil Change")!!
        assertEquals(231_500, item.lastDoneMileage)
        assertEquals(1_700_000_000_000L, item.lastDoneDate)

        val records = FleetEngineStore.getRecentForVehicle(context, "V1", 10)
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

        val item = FleetEngineStore.get(context, "V1", "Oil Change")!!
        assertEquals(231_500, item.lastDoneMileage)

        assertTrue(FleetEngineStore.getRecentForVehicle(context, "V1", 10).isEmpty())
    }

    @Test
    fun `NEVER_DONE with a cost ignores the cost - the anchor mode gates the record, not the field alone`() = runBlocking {
        val outcome = writeSetAnchor(context, "V1", "Oil Change", AnchorMode.NEVER_DONE, mileage = null, date = null, costCents = 4_599L)

        assertTrue(outcome.success)
        val item = FleetEngineStore.get(context, "V1", "Oil Change")!!
        assertTrue(item.neverDone)
        assertTrue(FleetEngineStore.getRecentForVehicle(context, "V1", 10).isEmpty())
    }

    // Cutover 4 (docs/architecture/cutover4-2026-08-24.md): the anchor's mileage is no longer a
    // second, independently-writable column - it is DERIVED from ServiceHistory
    // (FleetRecordBridge.projectAnchor), so an OBSERVED row this branch writes (with the fallback
    // live mileage) now legitimately becomes the visible anchor too. Before cutover, writeSetAnchor
    // wrote NULL to the anchor's mileage (mileage was never typed) while separately inserting a
    // ServiceRecord carrying the fallback figure - exactly the two-different-views-of-one-fact split
    // ticket 29 exists to delete. The anchor now correctly agrees with the event that was just
    // logged, which is the whole point of the unification, not a regression in this test.
    @Test
    fun `DONE_AT with a cost but no typed mileage falls back to the vehicle's current live mileage`() = runBlocking {
        // odometerBaseline = 227_000, no trip miles accrued - currentMileage reads exactly that.
        val outcome = writeSetAnchor(context, "V1", "Oil Change", AnchorMode.DONE_AT, mileage = null, date = null, costCents = 5_000L)

        assertTrue(outcome.success)
        val record = FleetEngineStore.getRecentForVehicle(context, "V1", 10).single()
        assertEquals(227_000, record.mileage)
        assertEquals(
            "the anchor now derives from the SAME OBSERVED row just written - one source, not two",
            227_000, FleetEngineStore.get(context, "V1", "Oil Change")!!.lastDoneMileage,
        )
    }
}

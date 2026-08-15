package com.kevin.legion.advisor

import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.testutil.RoomTestReset
import com.kevin.legion.vehicle.VehicleController
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
 * Review defect 1: three controllers behind the allowlist ([com.kevin.legion.sleep.SleepController
 * .setTarget], [com.kevin.legion.workouts.WorkoutController.generatePlan],
 * [com.kevin.legion.location.ReminderController.add]) signal "nothing written" by RETURNING a
 * spoken failure sentence rather than throwing. [AdvisorProposalExecutor] must catch that by
 * reading its own write back through the DAO, never by matching the sentence, and report
 * [AdvisorProposalExecutor.ExecuteResult.WriteFailed] rather than a false [AdvisorProposalExecutor
 * .ExecuteResult.Ok]. Also covers `set_maintenance_item`, the one allowlisted op that had no direct
 * test at all.
 */
@RunWith(RobolectricTestRunner::class)
class AdvisorProposalExecutorTest {
    private val context = RuntimeEnvironment.getApplication()

    @Before
    fun clearState() {
        RoomTestReset.resetCarDatabaseSingleton()
    }

    // --- WriteFailed: verified by read-back, not by matching a sentence -------------------------

    @Test
    fun `an invalid sleep target is caught by read-back, not left reading as Ok`() = runBlocking {
        // SleepController.setTarget returns a spoken failure sentence (never throws) for a target
        // that is not a real duration - reachable here via optDouble on a malformed/missing field,
        // which is exactly what "NaN" parses to.
        val brief = AdvisorBriefs.forAspect(AdvisorAspect.BIO)
        val proposal = """{"op":"set_sleep_target","targetHours":"not a number"}"""

        val outcome = AdvisorProposalExecutor.execute(context, brief, proposal)

        assertTrue(
            "a target that never landed must report WriteFailed, never Ok",
            outcome is AdvisorProposalExecutor.ExecuteResult.WriteFailed,
        )
        assertNull(
            "nothing should actually be written to sleep_targets",
            CarDatabase.getDatabase(context).sleepTargetDao().currentTarget(System.currentTimeMillis()),
        )
    }

    @Test
    fun `an out-of-range sleep target (26h) is caught by read-back`() = runBlocking {
        val brief = AdvisorBriefs.forAspect(AdvisorAspect.BIO)
        val proposal = """{"op":"set_sleep_target","targetHours":26.0}"""

        val outcome = AdvisorProposalExecutor.execute(context, brief, proposal)

        assertTrue(outcome is AdvisorProposalExecutor.ExecuteResult.WriteFailed)
        assertNull(CarDatabase.getDatabase(context).sleepTargetDao().currentTarget(System.currentTimeMillis()))
    }

    @Test
    fun `a valid sleep target is verified and reports Ok`() = runBlocking {
        val brief = AdvisorBriefs.forAspect(AdvisorAspect.BIO)
        val proposal = """{"op":"set_sleep_target","targetHours":7.5}"""

        val outcome = AdvisorProposalExecutor.execute(context, brief, proposal)

        assertTrue(outcome is AdvisorProposalExecutor.ExecuteResult.Ok)
        val landed = CarDatabase.getDatabase(context).sleepTargetDao().currentTarget(System.currentTimeMillis())
        assertEquals(450, landed!!.targetMinutes) // 7.5h = 450 minutes
    }

    @Test
    fun `a reminder whose place normalises to blank is caught by read-back, not left reading as Ok`() = runBlocking {
        // ReminderController.add's private normalizeLabel strips filler words like "location" down
        // to blank, at which point add returns a spoken failure sentence rather than throwing.
        val brief = AdvisorBriefs.forAspect(AdvisorAspect.LOG)
        val proposal = """{"op":"set_reminder","place":"location","text":"pick up the package"}"""

        val outcome = AdvisorProposalExecutor.execute(context, brief, proposal)

        assertTrue(
            "a place that normalises to blank must report WriteFailed, never Ok",
            outcome is AdvisorProposalExecutor.ExecuteResult.WriteFailed,
        )
        assertTrue(
            "no reminder should have been written under any label",
            CarDatabase.getDatabase(context).listItemDao().openWithAnyPlaceTrigger().isEmpty(),
        )
    }

    @Test
    fun `a valid reminder is verified and reports Ok`() = runBlocking {
        val brief = AdvisorBriefs.forAspect(AdvisorAspect.LOG)
        val proposal = """{"op":"set_reminder","place":"work","text":"pick up the package"}"""

        val outcome = AdvisorProposalExecutor.execute(context, brief, proposal)

        assertTrue(outcome is AdvisorProposalExecutor.ExecuteResult.Ok)
        val landed = CarDatabase.getDatabase(context).listItemDao().openWithAnyPlaceTrigger()
        assertEquals(1, landed.size)
        assertEquals("pick up the package", landed.first().text)
    }

    // --- set_maintenance_item: writes ONLY the interval, never an actual -------------------------

    @Test
    fun `set_maintenance_item writes only intervalMiles and intervalMonths, leaving lastDone fields untouched`() = runBlocking {
        val vehicle = VehicleController.currentVehicle(context)
        val db = CarDatabase.getDatabase(context)
        db.maintenanceItemDao().upsert(
            com.kevin.legion.data.local.MaintenanceItem(
                vehicleId = vehicle.obdMac,
                serviceName = "Oil Change",
                intervalMiles = 3000,
                lastDoneMileage = 12000,
                lastDoneDate = 1_600_000_000_000L,
                neverDone = false,
            ),
        )

        val brief = AdvisorBriefs.forAspect(AdvisorAspect.FLEET)
        val proposal = """{"op":"set_maintenance_item","serviceName":"Oil Change","intervalMiles":5000,"intervalMonths":6}"""
        val outcome = AdvisorProposalExecutor.execute(context, brief, proposal)

        assertTrue(outcome is AdvisorProposalExecutor.ExecuteResult.Ok)
        val landed = db.maintenanceItemDao().get(vehicle.obdMac, "Oil Change")!!
        assertEquals("interval must be updated to the proposed value", 5000, landed.intervalMiles)
        assertEquals(6, landed.intervalMonths)
        assertEquals(
            "an interval proposal is an INTENTION, never a claim about work performed - the actual" +
                " last-done mileage must be untouched",
            12000, landed.lastDoneMileage,
        )
        assertEquals(1_600_000_000_000L, landed.lastDoneDate)
        assertEquals("neverDone is an ACTUAL fact too and must be untouched", false, landed.neverDone)
    }

    @Test
    fun `set_maintenance_item on a brand new service creates it with the interval only`() = runBlocking {
        val vehicle = VehicleController.currentVehicle(context)
        val db = CarDatabase.getDatabase(context)

        val brief = AdvisorBriefs.forAspect(AdvisorAspect.FLEET)
        val proposal = """{"op":"set_maintenance_item","serviceName":"Tire Rotation","intervalMiles":6000}"""
        val outcome = AdvisorProposalExecutor.execute(context, brief, proposal)

        assertTrue(outcome is AdvisorProposalExecutor.ExecuteResult.Ok)
        val landed = db.maintenanceItemDao().get(vehicle.obdMac, "Tire Rotation")!!
        assertEquals(6000, landed.intervalMiles)
        assertNull(landed.intervalMonths)
        assertNull("a freshly created item has no actual work logged against it", landed.lastDoneMileage)
        assertNull(landed.lastDoneDate)
        assertEquals(false, landed.neverDone)
    }
}

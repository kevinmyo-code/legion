package com.kevin.legion.vehicle

import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.MaintenanceItem
import com.kevin.legion.data.local.Vehicle
import com.kevin.legion.testutil.RoomTestReset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Controller-level regressions for tickets 05 and 08's targeted-write rewrite of
 * [VehicleController.logServiceDirect] / [VehicleController.logPastServiceDirect] /
 * [VehicleController.setMaintenanceInterval] - `.scratch/fleet-maintenance/issues/05-*` and
 * `08-matching-a-logged-service-to-an-item.md`.
 *
 * [MaintenanceItemDaoTargetedWritesTest] pins the DAO-level column isolation and row-count
 * contract; this file pins the CONTROLLER-level behaviour that sits on top of it - matching a
 * spoken name to the schedule, announcing an unmatched name rather than silently creating it, the
 * backfill-vs-precise-record conflict, and the interval-edit read-back.
 *
 * Robolectric through the real [CarDatabase.getDatabase] path, same shape as
 * [VehicleControllerIdentityWritesTest].
 */
@RunWith(RobolectricTestRunner::class)
class VehicleControllerServiceWritesTest {
    private val context = RuntimeEnvironment.getApplication()
    private val db get() = CarDatabase.getDatabase(context)

    @Before
    fun clearState() = runBlocking {
        RoomTestReset.resetCarDatabaseSingleton()
        db.vehicleDao().upsert(
            Vehicle(
                obdMac = "V1", name = "Cherokee", make = "Jeep", model = "Cherokee", year = 1998,
                personaPrompt = "", odometerBaseline = 227_000, confirmed = true,
            )
        )
    }

    // --- logServiceDirect: matching (ticket 08) --------------------------------------------

    @Test
    fun `logServiceDirect resets the anchor of a matching existing item via a targeted write`() = runBlocking {
        db.maintenanceItemDao().upsertStamped(
            MaintenanceItem(vehicleId = "V1", serviceName = "Oil Change", intervalMiles = 5_000, lastDoneMileage = 220_000)
        )

        // Spoken name is loose phrasing, not the exact stored name - the
        // canonicalised comparator must still find it.
        val outcome = VehicleController.logServiceDirect(context, "I just changed the oil", vehicleId = "V1")

        assertTrue("A landed write must report success. Was: $outcome", outcome.success)
        val after = db.maintenanceItemDao().get("V1", "Oil Change")!!
        assertEquals(227_000, after.lastDoneMileage)
        // Interval must ride along untouched - this is the read-modify-write
        // race ticket 05 removed by switching to a targeted setAnchor write.
        assertEquals(5_000, after.intervalMiles)
        // Exactly one item - matching must not have created a duplicate.
        assertEquals(1, db.maintenanceItemDao().getForVehicle("V1").size)
    }

    // --- logServiceDirect: cost capture (ticket 11 §2) --------------------------------------

    @Test
    fun `logServiceDirect with a cost writes costCents onto the ServiceRecord`() = runBlocking {
        val outcome = VehicleController.logServiceDirect(context, "oil change", vehicleId = "V1", costCents = 4599)

        assertTrue(outcome.success)
        val record = db.serviceRecordDao().getRecentForVehicle("V1", 1).single()
        assertEquals(4599L, record.costCents)
    }

    @Test
    fun `logServiceDirect with no cost given leaves costCents null, never zero`() = runBlocking {
        val outcome = VehicleController.logServiceDirect(context, "oil change", vehicleId = "V1")

        assertTrue(outcome.success)
        val record = db.serviceRecordDao().getRecentForVehicle("V1", 1).single()
        assertNull("an omitted cost must stay null - CLAUDE.md §4 rule 6, never a silent $0.00", record.costCents)
    }

    // --- editServiceRecordDirect / deleteServiceRecordDirect (ticket 11 §2) -----------------

    @Test
    fun `editServiceRecordDirect touches only mileage and costCents, and reads the value back`() = runBlocking {
        VehicleController.logServiceDirect(context, "oil change", vehicleId = "V1")
        val id = db.serviceRecordDao().getRecentForVehicle("V1", 1).single().id

        val outcome = VehicleController.editServiceRecordDirect(context, id, mileageMiles = 227_500, costCents = 4599)

        assertTrue("Was: $outcome", outcome.success)
        assertTrue("Read-back must state the new mileage. Was: ${outcome.message}", outcome.message.contains("227500") || outcome.message.contains("227,500"))
        assertTrue("Read-back must state the new cost. Was: ${outcome.message}", outcome.message.contains("45.99"))
        val after = db.serviceRecordDao().getById(id)!!
        assertEquals(227_500, after.mileage)
        assertEquals(4599L, after.costCents)
    }

    @Test
    fun `editServiceRecordDirect against a nonexistent id reports failure rather than pretending it worked`() = runBlocking {
        val outcome = VehicleController.editServiceRecordDirect(context, id = 999L, mileageMiles = 1, costCents = null)
        assertFalse(outcome.success)
    }

    @Test
    fun `deleteServiceRecordDirect soft-deletes and its message says the delete is local only`() = runBlocking {
        VehicleController.logServiceDirect(context, "oil change", vehicleId = "V1")
        val id = db.serviceRecordDao().getRecentForVehicle("V1", 1).single().id

        val outcome = VehicleController.deleteServiceRecordDirect(context, id)

        assertTrue(outcome.success)
        assertTrue(
            "the delete's local-only nature must be stated in words (CLAUDE.md §2's tombstone caveat), not implied",
            outcome.message.contains("this phone", ignoreCase = true),
        )
        assertTrue(db.serviceRecordDao().getRecentForVehicle("V1", 10).isEmpty())
    }

    @Test
    fun `deleteServiceRecordDirect against a nonexistent id reports failure`() = runBlocking {
        val outcome = VehicleController.deleteServiceRecordDirect(context, id = 999L)
        assertFalse(outcome.success)
    }

    @Test
    fun `logServiceDirect always writes a ServiceRecord, even for a brand new item`() = runBlocking {
        // "wheel bearing service" deliberately matches none of the 10
        // SERVICE_KEYWORDS entries, so canonicalizeServiceName falls through to
        // the titlecase path rather than folding onto an existing keyword.
        val outcome = VehicleController.logServiceDirect(context, "wheel bearing service", vehicleId = "V1")

        assertTrue(outcome.success)
        // Ticket 08 decision: the ServiceRecord is ALWAYS written - work done
        // is a fact regardless of what the schedule knew about it.
        val records = db.serviceRecordDao().getRecentForVehicle("V1", 10)
        assertEquals(1, records.size)
        assertEquals(227_000, records.single().mileage)
    }

    @Test
    fun `logServiceDirect with no matching item creates one and ANNOUNCES it, never silently`() = runBlocking {
        val outcome = VehicleController.logServiceDirect(context, "wheel bearing service", vehicleId = "V1")

        assertTrue(outcome.success)
        assertTrue(
            "An unmatched log must say a new item was added, not silently create one (ticket 08). Was: ${outcome.message}",
            outcome.message.contains("Nothing on your schedule matched", ignoreCase = true),
        )
        val created = db.maintenanceItemDao().get("V1", "Wheel Bearing Service")
        assertEquals("Wheel Bearing Service", created?.serviceName)
        assertEquals(227_000, created?.lastDoneMileage)
    }

    @Test
    fun `logServiceDirect clears neverDone on the matched item`() = runBlocking {
        db.maintenanceItemDao().upsertStamped(MaintenanceItem(vehicleId = "V1", serviceName = "Tire Rotation", neverDone = true))

        VehicleController.logServiceDirect(context, "rotated the tires", vehicleId = "V1")

        val after = db.maintenanceItemDao().get("V1", "Tire Rotation")!!
        assertFalse("A just-completed service must not still read as permanently overdue", after.neverDone)
    }

    // --- logPastServiceDirect: the backfill-vs-precise-record conflict (ticket 08) ----------

    @Test
    fun `logPastServiceDirect refuses to overwrite an anchor a later ServiceRecord supports`() = runBlocking {
        // Real damage on Kevin's device, reproduced: log_service writes a
        // precise record and its anchor...
        val logged = VehicleController.logServiceDirect(context, "oil change", vehicleId = "V1")
        assertTrue(logged.success)
        val afterLog = db.maintenanceItemDao().get("V1", "Oil Change")!!
        assertEquals(227_000, afterLog.lastDoneMileage)

        // ...then, moments later, a memory-based backfill tries to overwrite
        // it with a different, less precise figure. It must be refused.
        val backfilled = VehicleController.logPastServiceDirect(context, "oil change", mileage = 226_800, vehicleId = "V1")

        assertFalse("A backfill conflicting with a precise record must report failure. Was: $backfilled", backfilled.success)
        val afterBackfill = db.maintenanceItemDao().get("V1", "Oil Change")!!
        assertEquals("The precise anchor must survive the refused backfill", 227_000, afterBackfill.lastDoneMileage)
        assertNotNull(afterBackfill.lastDoneDate)
    }

    @Test
    fun `logPastServiceDirect against an item with no conflicting record succeeds`() = runBlocking {
        val outcome = VehicleController.logPastServiceDirect(context, "coolant flush", milesAgo = 5_000, vehicleId = "V1")

        assertTrue(outcome.success)
        val after = db.maintenanceItemDao().get("V1", "Coolant Flush")!!
        assertEquals(222_000, after.lastDoneMileage)
        // logPastServiceDirect never writes a ServiceRecord - a remembered
        // approximation does not belong in the precise ledger.
        assertTrue(db.serviceRecordDao().getRecentForVehicle("V1", 10).isEmpty())
    }

    @Test
    fun `logPastServiceDirect neverDone replaces any prior anchor via the targeted write`() = runBlocking {
        db.maintenanceItemDao().upsertStamped(
            MaintenanceItem(vehicleId = "V1", serviceName = "Battery", lastDoneMileage = 200_000, lastDoneDate = 5_000L)
        )

        val outcome = VehicleController.logPastServiceDirect(context, "battery", neverDone = true, vehicleId = "V1")

        assertTrue(outcome.success)
        val after = db.maintenanceItemDao().get("V1", "Battery")!!
        assertTrue(after.neverDone)
        assertNull(after.lastDoneMileage)
        assertNull(after.lastDoneDate)
    }

    @Test
    fun `logPastServiceDirect with nothing usable refuses`() = runBlocking {
        val outcome = VehicleController.logPastServiceDirect(context, "oil change", vehicleId = "V1")

        assertFalse(outcome.success)
    }

    // --- setMaintenanceInterval: the voice tool's read-back (ticket 05) --------------------

    @Test
    fun `setMaintenanceInterval on an existing item writes CONFIRMED and reads the value back`() = runBlocking {
        db.maintenanceItemDao().upsertStamped(
            MaintenanceItem(vehicleId = "V1", serviceName = "Oil Change", intervalMiles = 3_000, intervalSource = "SEEDED", lastDoneMileage = 118_483)
        )

        val outcome = VehicleController.setMaintenanceInterval(context, "oil change", intervalMiles = 7_500, vehicleId = "V1")

        assertTrue(outcome.success)
        // The read-back is a re-read, not the caller's own input echoed - it
        // must state the value actually now on the row.
        assertTrue("Read-back must state the new interval. Was: ${outcome.message}", outcome.message.contains("7500") || outcome.message.contains("7,500"))
        assertTrue("Read-back must state the last-done anchor. Was: ${outcome.message}", outcome.message.contains("118483") || outcome.message.contains("118,483"))
        val after = db.maintenanceItemDao().get("V1", "Oil Change")!!
        assertEquals(7_500, after.intervalMiles)
        // A driver-confirmed edit is CONFIRMED, never left SEEDED - ticket 05's
        // whole point is that a future factory populate must skip this row.
        assertEquals("CONFIRMED", after.intervalSource)
    }

    @Test
    fun `setMaintenanceInterval on an unknown service creates it as CONFIRMED`() = runBlocking {
        val outcome = VehicleController.setMaintenanceInterval(context, "differential fluid", intervalMiles = 30_000, vehicleId = "V1")

        assertTrue(outcome.success)
        val after = db.maintenanceItemDao().get("V1", "Differential Fluid")!!
        assertEquals(30_000, after.intervalMiles)
        assertEquals("CONFIRMED", after.intervalSource)
    }

    @Test
    fun `setMaintenanceInterval with no interval given refuses rather than writing an empty one`() = runBlocking {
        val outcome = VehicleController.setMaintenanceInterval(context, "oil change", vehicleId = "V1")

        assertFalse(outcome.success)
        assertNull(db.maintenanceItemDao().get("V1", "Oil Change"))
    }

    /**
     * BLOCKING review finding, 2026-08-15, caught before this reached the device.
     *
     * `setIntervals` is an unconditional `SET intervalMiles = :miles, intervalMonths = :months`,
     * so a caller passing null for the axis the driver never mentioned writes SQL NULL and
     * destroys it. Not theoretical: 34 of the 54 rows on Kevin's phone carry both axes, including
     * `Oil Change 3,000 mi / 3 mo` on the Jeep - the exact row "change the oil interval to 7,500"
     * would hit first.
     *
     * The read-back could not have caught this. It reports what is actually stored, so it would
     * have confirmed the damaged row as correct.
     */
    @Test
    fun `setMaintenanceInterval editing one axis leaves the other one alone`() = runBlocking {
        db.maintenanceItemDao().upsert(
            MaintenanceItem(
                vehicleId = "V1", serviceName = "Oil Change",
                intervalMiles = 3_000, intervalMonths = 3,
                lastDoneMileage = 118_483,
            )
        )

        val outcome = VehicleController.setMaintenanceInterval(context, "oil change", intervalMiles = 7_500, vehicleId = "V1")

        assertTrue("Was: ${outcome.message}", outcome.success)
        val after = db.maintenanceItemDao().get("V1", "Oil Change")!!
        assertEquals("the edited axis takes the new value", 7_500, after.intervalMiles)
        assertEquals("the UNTOUCHED axis must survive", 3, after.intervalMonths)
        assertEquals("the anchor is not this write's business", 118_483, after.lastDoneMileage)
        assertEquals("CONFIRMED", after.intervalSource)
    }

    /** The mirror: editing only the time axis must not wipe the mileage one. */
    @Test
    fun `setMaintenanceInterval editing months leaves miles alone`() = runBlocking {
        db.maintenanceItemDao().upsert(
            MaintenanceItem(vehicleId = "V1", serviceName = "Tire Rotation", intervalMiles = 6_000, intervalMonths = 6)
        )

        val outcome = VehicleController.setMaintenanceInterval(context, "tire rotation", intervalMonths = 12, vehicleId = "V1")

        assertTrue(outcome.success)
        val after = db.maintenanceItemDao().get("V1", "Tire Rotation")!!
        assertEquals(6_000, after.intervalMiles)
        assertEquals(12, after.intervalMonths)
    }

    /**
     * SHOULD-FIX from the same review: the backfill conflict rule was extended to `neverDone`
     * (a logged record contradicts "I've never done this") but nothing pinned that combination,
     * and the shared refusal message spoke of moving a schedule backward, which is not what
     * happens when an anchor is being cleared.
     */
    @Test
    fun `marking never-done is refused when a logged record proves otherwise, and says why`() = runBlocking {
        db.maintenanceItemDao().upsert(MaintenanceItem(vehicleId = "V1", serviceName = "Oil Change", intervalMiles = 7_500))
        db.serviceRecordDao().insert(
            com.kevin.legion.data.local.ServiceRecord(
                vehicleId = "V1", serviceName = "Oil Change", mileage = 118_374, date = 1_786_567_802_968L,
            )
        )

        val outcome = VehicleController.logPastServiceDirect(context, "oil change", neverDone = true, vehicleId = "V1")

        assertFalse("A record on file contradicts never-done. Was: ${outcome.message}", outcome.success)
        assertTrue(
            "The refusal must fit the never-done case, not talk about moving a schedule backward. Was: ${outcome.message}",
            outcome.message.contains("never done", ignoreCase = true),
        )
        val after = db.maintenanceItemDao().get("V1", "Oil Change")!!
        assertFalse("neverDone must NOT have been set", after.neverDone)
    }
}

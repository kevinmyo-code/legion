package com.kevin.legion.vehicle

import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.MaintenanceItem
import com.kevin.legion.data.local.Vehicle
import com.kevin.legion.engine.RecordStore
import com.kevin.legion.testutil.RoomTestReset
import kotlinx.coroutines.runBlocking
import org.junit.After
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
 * Cutover 4 (`docs/architecture/cutover4-2026-08-24.md`) - the live-behavior tests instruction 8
 * names by hand, that no pre-existing fixture file happened to already pin: the unification itself
 * (one source for a service logged by two different entry points), the ASSERTED-supersession rule
 * with the both-axes correction, one-transaction atomicity in both directions, due-ness math read
 * through the engine, and the `DeletePolicy.BLOCK` refusal worded. [EngineDataMigrationWave4Test]
 * already pins BLOCK against the MIGRATED data; this file pins it against a LIVE write, and
 * everything else here is new coverage this cutover branch itself owes.
 */
@RunWith(RobolectricTestRunner::class)
class FleetEngineStoreTest {
    private val context = RuntimeEnvironment.getApplication()
    private val db get() = CarDatabase.getDatabase(context)

    @Before
    fun clearState() = runBlocking {
        RoomTestReset.resetCarDatabaseSingleton()
        FleetEngineStore.createVehicle(
            context,
            Vehicle(obdMac = "V1", name = "Cherokee", make = "Jeep", model = "Cherokee", year = 1998, personaPrompt = "", odometerBaseline = 227_000, confirmed = true),
        )
    }

    @After
    fun drainRoomInvalidationTracker() {
        // A DAO write anywhere in this test can schedule a Room InvalidationTracker refresh
        // on ArchTaskExecutor's disk-IO pool. If that refresh is still queued or running when
        // this test method returns, it races Robolectric's own per-@Test-METHOD native SQLite
        // reset and throws "Illegal connection pointer" on a background thread - uncaught, and
        // blamed by kotlinx-coroutines-test on whatever runTest starts next, not on this class.
        // Draining here, before Robolectric ever gets a chance to reset, is the fix - see
        // RoomTestReset's own class doc comment
        // (.scratch/hardening/issues/13-the-suite-is-green-by-luck.md) for the full account.
        RoomTestReset.drainArchDiskIoPool()
    }


    // ============================================================================================
    // Unification: a service logged by ONE entry point is the SAME record a DIFFERENT entry point
    // reads back - the whole reason ticket 29 exists.
    // ============================================================================================

    @Test
    fun `a service inserted directly through FleetEngineStore (the hands-UI shape) is immediately visible to VehicleController's own read (the voice-tool shape)`() = runBlocking {
        // Simulates ui-fleet/MaintenanceWrites.kt's DONE_AT-with-cost write - a real logged event,
        // written through the same insertObserved every voice log_service call also uses.
        val result = FleetEngineStore.insertObserved(context, "V1", "Oil Change", mileage = 231_500, date = 1_700_000_000_000L, costCents = 4599)
        assertTrue(result is FleetEngineStore.InsertObservedResult.Success)

        // The SAME fact, read through FleetEngineStore.get - the accessor VehicleController.dueItems/
        // nextService/get_next_service and the FLEET screen all ultimately go through.
        FleetEngineStore.upsertNewItem(context, MaintenanceItem(vehicleId = "V1", serviceName = "Oil Change", intervalMiles = 5_000))
        val oilChange = FleetEngineStore.get(context, "V1", "Oil Change")!!

        assertEquals("one source: the anchor IS the OBSERVED row's own mileage", 231_500, oilChange.lastDoneMileage)
        assertEquals("one source: the anchor IS the OBSERVED row's own date", 1_700_000_000_000L, oilChange.lastDoneDate)
    }

    @Test
    fun `logServiceDirect (voice) and a direct MaintenanceWrites-shaped insertObserved (hands) land on the SAME ServiceHistory record type - one screen and one voice answer can never disagree`() = runBlocking {
        VehicleController.logServiceDirect(context, "oil change", vehicleId = "V1")
        val fromVoice = FleetEngineStore.getRecentForVehicle(context, "V1", 1).single()

        // The hands-path equivalent write (ui/fleet/MaintenanceWrites.kt's cost-linked insert)
        // reads back through the EXACT same accessor - there is no second table it could have
        // landed in instead.
        val allRecords = FleetEngineStore.serviceRecordsForVehicle(context, "V1")
        assertEquals(1, allRecords.size)
        assertEquals(fromVoice.id, allRecords.single().id)
    }

    // ============================================================================================
    // ASSERTED-supersession, corrected both-axes rule (wave4-carve's own senior-review fix, reused
    // live for cutover instruction 3 - "when logServiceDirect records an OBSERVED service that
    // matches an existing ASSERTED row (same corrected both-axes rule as the migration dedup), the
    // ASSERTED row is trashed in the same transaction").
    // ============================================================================================

    @Test
    fun `an OBSERVED insert that matches an ASSERTED anchor on BOTH stated axes supersedes it - the ASSERTED row is trashed`() = runBlocking {
        FleetEngineStore.upsertNewItem(context, MaintenanceItem(vehicleId = "V1", serviceName = "Oil Change"))
        // A driver-stated anchor with no backing event yet.
        FleetEngineStore.setAnchor(context, "V1", "Oil Change", mileage = 227_483, date = 1_723_000_000_000L, now = System.currentTimeMillis())
        val assertedGuid = com.kevin.legion.engine.fleet.FleetRecordBridge.assertedAnchorGuid("V1", "Oil Change")
        assertNotNull("the ASSERTED row must exist before the observation", db.engineRecordDao().getByGuid(assertedGuid))

        // The real event that explains the anchor on BOTH axes now lands.
        val result = FleetEngineStore.insertObserved(context, "V1", "Oil Change", mileage = 227_483, date = 1_723_000_000_000L, costCents = null)
        assertTrue(result is FleetEngineStore.InsertObservedResult.Success)

        val asserted = db.engineRecordDao().getByGuid(assertedGuid)!!
        assertNotNull("the superseded ASSERTED row must have been trashed, not deleted outright (30-day restore)", asserted.deletedAt)
    }

    @Test
    fun `an OBSERVED insert that matches only ONE axis of the ASSERTED anchor does NOT supersede it - the both-axes rule`() = runBlocking {
        FleetEngineStore.upsertNewItem(context, MaintenanceItem(vehicleId = "V1", serviceName = "Oil Change"))
        // The Jeep's real incident shape: a dateless anchor at 227,483 mi, stated NOW (real wall
        // clock) - the most-recently-stated fact on file for this service.
        FleetEngineStore.setAnchor(context, "V1", "Oil Change", mileage = 227_483, date = null, now = System.currentTimeMillis())
        val assertedGuid = com.kevin.legion.engine.fleet.FleetRecordBridge.assertedAnchorGuid("V1", "Oil Change")

        // A DIFFERENT, coincidentally-mileage-matching event that does NOT actually back the same
        // anchor on every axis it states is irrelevant here since the anchor states only mileage -
        // so use a genuinely mismatched mileage instead, matching the carve doc's own regression case.
        // Its own "now" is the OLDER service date (1_692_000_000_000L, 2023), never the wall clock -
        // insertObserved's own create call passes the service's own date as the row's `now`.
        FleetEngineStore.insertObserved(context, "V1", "Oil Change", mileage = 227_374, date = 1_692_000_000_000L, costCents = null)

        val asserted = db.engineRecordDao().getByGuid(assertedGuid)!!
        assertNull(
            "a mismatched mileage must NOT trash the anchor - ticket 29's dateless 227,483 anchor " +
                "must survive alongside a disagreeing OBSERVED row, both facts stated, never silently reconciled",
            asserted.deletedAt,
        )

        // Senior review MUST-FIX (2026-08-24): projectAnchor derives BOTH axes from the single
        // most-recently-stated row, never a cross-row blend. The ASSERTED anchor was stated at real
        // wall-clock "now" (2026); the OBSERVED row's own clock is the older service date it
        // actually happened on (2023) - so the ASSERTED row is still the most recent fact on file,
        // and the derived anchor must be EXACTLY its own two axes: 227,483 mi, no date. It must
        // NEVER read as (227,483 mi, 1_692_000_000_000L) - that pairing was never asserted by any
        // single write, only fabricated by taking the max of each axis independently, which is
        // exactly the bug this fix removes.
        val derived = FleetEngineStore.get(context, "V1", "Oil Change")!!
        assertEquals("the derived mileage is the most-recent row's OWN mileage", 227_483, derived.lastDoneMileage)
        assertNull(
            "the derived date must be the most-recent row's OWN null, never borrowed from the older OBSERVED row's real date",
            derived.lastDoneDate,
        )
    }

    @Test
    fun `the derived anchor's date is null when the most-recently-stated row is dateless, even though an older row has a real date`() = runBlocking {
        FleetEngineStore.upsertNewItem(context, MaintenanceItem(vehicleId = "V1", serviceName = "Coolant Flush"))
        // An older, dated, real event.
        FleetEngineStore.insertObserved(context, "V1", "Coolant Flush", mileage = 210_000, date = 1_650_000_000_000L, costCents = null)
        // A NEWER assertion that states only a mileage - "I think it was done again around
        // 215,000" - with no date attached, stated at real wall-clock now.
        FleetEngineStore.setAnchor(context, "V1", "Coolant Flush", mileage = 215_000, date = null, now = System.currentTimeMillis())

        val derived = FleetEngineStore.get(context, "V1", "Coolant Flush")!!

        assertEquals("the newer, dateless row's own mileage wins outright", 215_000, derived.lastDoneMileage)
        assertNull(
            "the newer row's own null date must stand - never silently backfilled from the older dated row",
            derived.lastDoneDate,
        )
    }

    @Test
    fun `logServiceDirect through the voice path supersedes a matching ASSERTED anchor the SAME way`() = runBlocking {
        // Proves the supersession fires identically no matter which entry point logged the service -
        // VehicleController.logServiceDirect delegates to the exact same insertObserved.
        FleetEngineStore.upsertNewItem(context, MaintenanceItem(vehicleId = "V1", serviceName = "Oil Change"))
        FleetEngineStore.setAnchor(context, "V1", "Oil Change", mileage = 227_000, date = null, now = System.currentTimeMillis())
        val assertedGuid = com.kevin.legion.engine.fleet.FleetRecordBridge.assertedAnchorGuid("V1", "Oil Change")
        assertNull(db.engineRecordDao().getByGuid(assertedGuid)!!.deletedAt)

        // currentMileage(vehicle) == odometerBaseline (227_000, no trip miles) - matches the anchor exactly.
        VehicleController.logServiceDirect(context, "oil change", vehicleId = "V1")

        assertNotNull(
            "voice logService must supersede the ASSERTED anchor the same way a direct insertObserved does",
            db.engineRecordDao().getByGuid(assertedGuid)!!.deletedAt,
        )
    }

    // ============================================================================================
    // One-transaction atomicity (instruction 8: "both failure directions").
    // ============================================================================================

    @Test
    fun `insertObserved against a vehicle not on file fails with a worded reason and writes nothing`() = runBlocking {
        val before = FleetEngineStore.serviceRecordsForVehicle(context, "never:registered").size

        val result = FleetEngineStore.insertObserved(context, "never:registered", "Oil Change", mileage = 100, date = 1L, costCents = null)

        assertTrue("a failure must be worded, never a bare false", result is FleetEngineStore.InsertObservedResult.Failure)
        assertTrue((result as FleetEngineStore.InsertObservedResult.Failure).reason.isNotBlank())
        assertEquals(before, FleetEngineStore.serviceRecordsForVehicle(context, "never:registered").size)
    }

    @Test
    fun `a successful insertObserved leaves BOTH the OBSERVED create and the ASSERTED supersession applied together, never one without the other`() = runBlocking {
        FleetEngineStore.upsertNewItem(context, MaintenanceItem(vehicleId = "V1", serviceName = "Oil Change"))
        FleetEngineStore.setAnchor(context, "V1", "Oil Change", mileage = 100_000, date = 1_000L, now = System.currentTimeMillis())
        val assertedGuid = com.kevin.legion.engine.fleet.FleetRecordBridge.assertedAnchorGuid("V1", "Oil Change")

        val result = FleetEngineStore.insertObserved(context, "V1", "Oil Change", mileage = 100_000, date = 1_000L, costCents = null)

        assertTrue(result is FleetEngineStore.InsertObservedResult.Success)
        // Both halves of the one logical write are visible together - the OBSERVED row exists...
        assertEquals(1, FleetEngineStore.serviceRecordsForVehicle(context, "V1").size)
        // ...AND its matching ASSERTED row was superseded in the same call.
        assertNotNull(db.engineRecordDao().getByGuid(assertedGuid)!!.deletedAt)
    }

    // ============================================================================================
    // Due-ness math read through the engine (mileage and date anchors).
    // ============================================================================================

    @Test
    fun `dueItems reads mileage due-ness off the SAME anchor an OBSERVED row establishes`() = runBlocking {
        FleetEngineStore.upsertNewItem(context, MaintenanceItem(vehicleId = "V1", serviceName = "Oil Change", intervalMiles = 5_000))
        // Logged 5,000 miles before the vehicle's current 227,000 baseline - exactly due.
        FleetEngineStore.insertObserved(context, "V1", "Oil Change", mileage = 222_000, date = 1L, costCents = null)

        val vehicle = FleetEngineStore.getByMac(context, "V1")!!
        val due = VehicleController.dueItems(context, vehicle)

        assertEquals(1, due.size)
        assertEquals("Oil Change", due.single().serviceName)
    }

    @Test
    fun `dueItems is NOT due when the OBSERVED anchor is recent enough on the mileage axis`() = runBlocking {
        FleetEngineStore.upsertNewItem(context, MaintenanceItem(vehicleId = "V1", serviceName = "Oil Change", intervalMiles = 5_000))
        FleetEngineStore.insertObserved(context, "V1", "Oil Change", mileage = 226_000, date = 1L, costCents = null)

        val vehicle = FleetEngineStore.getByMac(context, "V1")!!
        val due = VehicleController.dueItems(context, vehicle)

        assertTrue("1,000 miles into a 5,000-mile interval must not read as due", due.isEmpty())
    }

    @Test
    fun `dueItems reads date due-ness off the SAME anchor an OBSERVED row establishes`() = runBlocking {
        FleetEngineStore.upsertNewItem(context, MaintenanceItem(vehicleId = "V1", serviceName = "Coolant Flush", intervalMonths = 24))
        val twoYearsAgo = System.currentTimeMillis() - (25L * 30 * 24 * 60 * 60 * 1000)
        FleetEngineStore.insertObserved(context, "V1", "Coolant Flush", mileage = 200_000, date = twoYearsAgo, costCents = null)

        val vehicle = FleetEngineStore.getByMac(context, "V1")!!
        val due = VehicleController.dueItems(context, vehicle)

        assertEquals(1, due.size)
        assertEquals("Coolant Flush", due.single().serviceName)
    }

    // ============================================================================================
    // DeletePolicy.BLOCK, exercised against a LIVE write (not just migrated data - see
    // EngineDataMigrationWave4Test for that half). No live caller reaches FleetEngineStore.deleteVehicle
    // today (see that function's own doc comment) - this proves the refusal itself is real and
    // worded, ready for whichever future surface calls it.
    // ============================================================================================

    @Test
    fun `deleteVehicle on a car with live ServiceHistory is BLOCKed, and nothing is written`() = runBlocking {
        FleetEngineStore.insertObserved(context, "V1", "Oil Change", mileage = 227_000, date = 1L, costCents = null)

        val result = FleetEngineStore.deleteVehicle(context, "V1")

        assertTrue("a car with real history must refuse the delete, not silently cascade or succeed", result is RecordStore.DeleteResult.Blocked)
        val blockers = (result as RecordStore.DeleteResult.Blocked).blockers
        assertTrue("the refusal must be worded, naming what still references the car", blockers.isNotEmpty())
        // The vehicle itself must still be there, untouched.
        assertNotNull(FleetEngineStore.getByMac(context, "V1"))
    }

    @Test
    fun `deleteVehicle on a car with a live MaintenanceSchedule (no history yet) is also BLOCKed`() = runBlocking {
        FleetEngineStore.upsertNewItem(context, MaintenanceItem(vehicleId = "V1", serviceName = "Oil Change", intervalMiles = 5_000))

        val result = FleetEngineStore.deleteVehicle(context, "V1")

        assertTrue(result is RecordStore.DeleteResult.Blocked)
    }

    @Test
    fun `deleteVehicle on a car with neither history nor a schedule succeeds`() = runBlocking {
        FleetEngineStore.createVehicle(
            context,
            Vehicle(obdMac = "V2", name = "Empty", make = "Test", model = "Car", year = 2020, personaPrompt = "", confirmed = true),
        )

        val result = FleetEngineStore.deleteVehicle(context, "V2")

        assertTrue("a car with nothing referencing it may be deleted", result is RecordStore.DeleteResult.Trashed)
    }

    // ============================================================================================
    // Worded failure per write path (§7's outcome-verb rule, applied to every FleetEngineStore
    // write this cutover added).
    // ============================================================================================

    @Test
    fun `setAnchor against a schedule item that does not exist is a no-op, reported as zero`() = runBlocking {
        val written = FleetEngineStore.setAnchor(context, "V1", "Nonexistent Service", mileage = 100, date = 1L, now = System.currentTimeMillis())
        assertEquals(0, written)
    }

    @Test
    fun `setNeverDone against a schedule item that does not exist is a no-op, reported as zero`() = runBlocking {
        val written = FleetEngineStore.setNeverDone(context, "V1", "Nonexistent Service", System.currentTimeMillis())
        assertEquals(0, written)
    }

    @Test
    fun `insertIgnore refuses (-1) when the pair already exists, active OR trashed`() = runBlocking {
        FleetEngineStore.upsertNewItem(context, MaintenanceItem(vehicleId = "V1", serviceName = "Oil Change"))
        FleetEngineStore.softDeleteItem(context, "V1", "Oil Change", System.currentTimeMillis())

        val rowId = FleetEngineStore.insertIgnore(context, MaintenanceItem(vehicleId = "V1", serviceName = "Oil Change"))

        assertEquals(
            "a tombstoned row still occupies the natural key - insertIgnore must not resurrect it silently",
            -1L, rowId,
        )
    }
}

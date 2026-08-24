package com.kevin.legion.ui.fleet

import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.MaintenanceItem
import com.kevin.legion.data.local.Vehicle
import com.kevin.legion.testutil.RoomTestReset
import com.kevin.legion.vehicle.FleetEngineStore
import com.kevin.legion.vehicle.PopulateChangeRow
import com.kevin.legion.vehicle.PopulatePossibleMatchRow
import com.kevin.legion.vehicle.PopulateRestoreRow
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
 * Regression for `ui/fleet/PopulateWrites.kt` (ticket 14 review, SHOULD-FIX 3,
 * `.scratch/fleet-maintenance/issues/14-populate-from-the-factory-schedule.md`) - the exact same
 * shape gap [MaintenanceWritesTest] closed for `ui/fleet/MaintenanceWrites.kt`: a brand-new
 * write-dispatch file, shipped with zero tests, in a codebase that has repeatedly hit "reported
 * success and did nothing." Robolectric through the real [CarDatabase.getDatabase] path, same
 * posture as [MaintenanceWritesTest] and [com.kevin.legion.vehicle.VehicleControllerServiceWritesTest].
 */
@RunWith(RobolectricTestRunner::class)
class PopulateWritesTest {
    private val context = RuntimeEnvironment.getApplication()
    private val db get() = CarDatabase.getDatabase(context)

    @Before
    fun clearState() = runBlocking {
        RoomTestReset.resetCarDatabaseSingleton()
        // Cutover 4 (docs/architecture/cutover4-2026-08-24.md): every MaintenanceSchedule write
        // resolves its vehicle by a real ENGINE record now.
        FleetEngineStore.createVehicle(
            context,
            Vehicle(obdMac = "V1", name = "Test Car", make = "Jeep", model = "Cherokee", year = 1998, personaPrompt = "", confirmed = true),
        )
    }

    // ------------------------------------------------------------ writePopulateAdd

    @Test
    fun `writePopulateAdd inserts a genuine new row as LOOKUP`() = runBlocking {
        val candidate = MaintenanceItem(vehicleId = "ignored", serviceName = "Coolant Flush", intervalMonths = 24, intervalSource = "SEEDED")

        val outcome = writePopulateAdd(context, "V1", candidate)

        assertTrue("Was: ${outcome.message}", outcome.success)
        val created = FleetEngineStore.get(context, "V1", "Coolant Flush")
        assertNotNull(created)
        assertEquals("V1", created?.vehicleId)
        assertEquals(24, created?.intervalMonths)
        // LOOKUP on accept, never CONFIRMED (ticket 18, superseding ticket 06 decision b) - the
        // driver reviewed a factory-lookup candidate, they did not state this figure themselves, and
        // ticket 18 found that lookup disagrees with itself roughly every other run.
        assertEquals("LOOKUP", created?.intervalSource)
    }

    /**
     * BLOCKING 2 (ticket 14 review): a `wouldAdd` candidate is computed at diff-load time and can
     * sit un-accepted while the driver reviews the rest of the diff. If a row under the SAME
     * `(vehicleId, serviceName)` appears in that window, tapping ADD must never silently REPLACE it
     * - anchor and provenance must survive untouched, and the caller must be told in words rather
     * than seeing a false "Added" success.
     */
    @Test
    fun `writePopulateAdd never overwrites a row that appeared under the same name during the review window`() = runBlocking {
        // A row with an anchor and CONFIRMED provenance lands - e.g. a sync merge, a voice
        // log_service orphan, or a near-miss the matching gap missed - AFTER the diff was loaded
        // but BEFORE the driver taps ADD on the stale wouldAdd candidate.
        val concurrent = MaintenanceItem(
            vehicleId = "V1", serviceName = "Coolant Flush", intervalMonths = 24,
            intervalSource = "CONFIRMED", lastDoneMileage = 132_400, lastDoneDate = 1_700_000_000_000L,
        )
        FleetEngineStore.upsertNewItem(context, concurrent)

        val staleCandidate = MaintenanceItem(vehicleId = "ignored", serviceName = "Coolant Flush", intervalMonths = 36, intervalSource = "SEEDED")
        val outcome = writePopulateAdd(context, "V1", staleCandidate)

        assertFalse("must refuse rather than silently replace", outcome.success)
        assertTrue("must say why, in words. Was: ${outcome.message}", outcome.message.contains("already on file", ignoreCase = true))

        // The concurrent row's anchor and provenance must survive UNTOUCHED - REPLACE would have
        // clobbered both back to the stale candidate's values (36 months, no anchor, SEEDED).
        val after = FleetEngineStore.get(context, "V1", "Coolant Flush")!!
        assertEquals(24, after.intervalMonths)
        assertEquals("CONFIRMED", after.intervalSource)
        assertEquals(132_400, after.lastDoneMileage)
        assertEquals(1_700_000_000_000L, after.lastDoneDate)
    }

    // ------------------------------------------------------------ writePopulateChange

    @Test
    fun `writePopulateChange writes the proposed interval and stamps LOOKUP`() = runBlocking {
        FleetEngineStore.upsertNewItem(context, 
            MaintenanceItem(vehicleId = "V1", serviceName = "Oil Change", intervalMiles = 3_000, intervalSource = "SEEDED"),
        )
        val row = PopulateChangeRow(
            serviceName = "Oil Change", currentMiles = 3_000, currentMonths = null, currentSource = "SEEDED",
            proposedMiles = 7_500, proposedMonths = 6,
        )

        val outcome = writePopulateChange(context, "V1", row)

        assertTrue("Was: ${outcome.message}", outcome.success)
        val after = FleetEngineStore.get(context, "V1", "Oil Change")!!
        assertEquals(7_500, after.intervalMiles)
        assertEquals(6, after.intervalMonths)
        // LOOKUP, not CONFIRMED (ticket 18) - the proposed value is still the factory lookup's own
        // figure, only reviewed and accepted, never typed by the driver.
        assertEquals("LOOKUP", after.intervalSource)
    }

    @Test
    fun `writePopulateChange against a vanished item returns success false with a message`() = runBlocking {
        val row = PopulateChangeRow(
            serviceName = "Nonexistent Item", currentMiles = 3_000, currentMonths = null, currentSource = "SEEDED",
            proposedMiles = 7_500, proposedMonths = 6,
        )

        val outcome = writePopulateChange(context, "V1", row)

        assertFalse(outcome.success)
        assertTrue("A failed write must say why, in words. Was: ${outcome.message}", outcome.message.isNotBlank())
    }

    // ------------------------------------------------------------ writePopulateDelete

    @Test
    fun `writePopulateDelete tombstones an existing row`() = runBlocking {
        FleetEngineStore.upsertNewItem(context, MaintenanceItem(vehicleId = "V1", serviceName = "Brake Fluid Flush", intervalMonths = 24, intervalSource = "SEEDED"))

        val outcome = writePopulateDelete(context, "V1", "Brake Fluid Flush")

        assertTrue("Was: ${outcome.message}", outcome.success)
        assertNull(FleetEngineStore.get(context, "V1", "Brake Fluid Flush"))
    }

    // ------------------------------------------------------------ writePopulateRestore

    @Test
    fun `writePopulateRestore un-tombstones a row and writes the proposed interval`() = runBlocking {
        // Cutover 4: a tombstone is now the engine record's OWN trashed state
        // (RecordStore.delete), not a payload field - MaintenanceItem.deleted plays no part in
        // FleetEngineStore.upsertNewItem's field map, so the fixture must genuinely trash the
        // record via softDeleteItem rather than construct a value object claiming deleted = true.
        FleetEngineStore.upsertNewItem(context, MaintenanceItem(vehicleId = "V1", serviceName = "Tire Rotation", intervalMiles = 6_000))
        FleetEngineStore.softDeleteItem(context, "V1", "Tire Rotation", System.currentTimeMillis())
        val row = PopulateRestoreRow(serviceName = "Tire Rotation", proposedMiles = 7_500, proposedMonths = null)

        val outcome = writePopulateRestore(context, "V1", row)

        assertTrue("Was: ${outcome.message}", outcome.success)
        val after = FleetEngineStore.get(context, "V1", "Tire Rotation")!!
        assertFalse(after.deleted)
        assertEquals(7_500, after.intervalMiles)
        // LOOKUP, not CONFIRMED (ticket 18) - the restored interval is the factory lookup's own
        // proposed figure, reviewed and accepted, not stated by the driver.
        assertEquals("LOOKUP", after.intervalSource)
    }

    @Test
    fun `writePopulateRestore against a row that was never tombstoned returns success false`() = runBlocking {
        val row = PopulateRestoreRow(serviceName = "Nonexistent Item", proposedMiles = 7_500, proposedMonths = null)

        val outcome = writePopulateRestore(context, "V1", row)

        assertFalse(outcome.success)
        assertTrue("Was: ${outcome.message}", outcome.message.isNotBlank())
    }

    // ------------------------------------------------------------ writePopulateMergeMatch (BLOCKING 1b)

    @Test
    fun `writePopulateMergeMatch writes onto the EXISTING name, never the factory name`() = runBlocking {
        FleetEngineStore.upsertNewItem(context, 
            MaintenanceItem(vehicleId = "V1", serviceName = "Check The Wheel Alignment", intervalMiles = 15_000, intervalSource = "SEEDED"),
        )
        val row = PopulatePossibleMatchRow(
            factoryName = "Wheel Alignment Check", existingName = "Check The Wheel Alignment", existingSource = "SEEDED",
            currentMiles = 15_000, currentMonths = null, proposedMiles = 20_000, proposedMonths = null,
        )

        val outcome = writePopulateMergeMatch(context, "V1", row)

        assertTrue("Was: ${outcome.message}", outcome.success)
        // The EXISTING name is what got the write - the factory's own wording never became a row.
        val after = FleetEngineStore.get(context, "V1", "Check The Wheel Alignment")!!
        assertEquals(20_000, after.intervalMiles)
        // LOOKUP, not CONFIRMED (ticket 18) - reached via writePopulateChange under the hood, same
        // reasoning as that function's own test.
        assertEquals("LOOKUP", after.intervalSource)
        assertNull(FleetEngineStore.get(context, "V1", "Wheel Alignment Check"))
    }

    // ------------------------------------------------------------ writePopulateAddAsNew (BLOCKING 1b)

    @Test
    fun `writePopulateAddAsNew inserts the factory name verbatim as its own row`() = runBlocking {
        // Some OTHER item is on file - the near-miss guess the driver is overriding.
        FleetEngineStore.upsertNewItem(context, 
            MaintenanceItem(vehicleId = "V1", serviceName = "Check The Wheel Alignment", intervalMiles = 15_000, intervalSource = "SEEDED"),
        )
        val row = PopulatePossibleMatchRow(
            factoryName = "Wheel Alignment Check", existingName = "Check The Wheel Alignment", existingSource = "SEEDED",
            currentMiles = 15_000, currentMonths = null, proposedMiles = 20_000, proposedMonths = null,
        )

        val outcome = writePopulateAddAsNew(context, "V1", row)

        assertTrue("Was: ${outcome.message}", outcome.success)
        val newRow = FleetEngineStore.get(context, "V1", "Wheel Alignment Check")!!
        assertEquals(20_000, newRow.intervalMiles)
        // LOOKUP, not CONFIRMED (ticket 18) - reached via writePopulateAdd under the hood, same
        // reasoning as that function's own test.
        assertEquals("LOOKUP", newRow.intervalSource)
        // The item the driver said was NOT a match must survive completely untouched.
        val untouched = FleetEngineStore.get(context, "V1", "Check The Wheel Alignment")!!
        assertEquals(15_000, untouched.intervalMiles)
        assertEquals("SEEDED", untouched.intervalSource)
    }

    @Test
    fun `writePopulateAddAsNew refuses to overwrite a row that already exists under the factory name`() = runBlocking {
        FleetEngineStore.upsertNewItem(context, 
            MaintenanceItem(
                vehicleId = "V1", serviceName = "Wheel Alignment Check", intervalMiles = 12_000,
                intervalSource = "CONFIRMED", lastDoneMileage = 90_000, lastDoneDate = 1_600_000_000_000L,
            ),
        )
        val row = PopulatePossibleMatchRow(
            factoryName = "Wheel Alignment Check", existingName = "Something Else", existingSource = "SEEDED",
            currentMiles = null, currentMonths = null, proposedMiles = 20_000, proposedMonths = null,
        )

        val outcome = writePopulateAddAsNew(context, "V1", row)

        assertFalse("must refuse rather than silently replace - BLOCKING 2 applies here too", outcome.success)
        val after = FleetEngineStore.get(context, "V1", "Wheel Alignment Check")!!
        assertEquals(12_000, after.intervalMiles)
        assertEquals(90_000, after.lastDoneMileage)
    }
}

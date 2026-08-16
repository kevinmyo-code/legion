package com.kevin.legion.data

import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.DriveReassignment
import com.kevin.legion.testutil.RoomTestReset
import com.kevin.legion.vehicle.DriveReassigner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Stage 2 of `.scratch/import-sync-duplication/issues/01-the-import-rekey-and-union-sync-duplicate-a-car-every-launch.md`.
 *
 * The import used to repair its sentinel re-key with a plain local `UPDATE`. `obd_samples` syncs
 * UNION on an identity that INCLUDES `vehicleId`, so that correction was invisible to Drive: the
 * sentinel-keyed originals stayed in the shared file, came back on the next pull, and the next
 * re-key made them look new again. One extra copy per launch, 36,694 rows over 5,242 distinct
 * identities by the time it was caught.
 *
 * A `DriveReassignment` rule is the mechanism that already existed for exactly this
 * ([com.kevin.legion.vehicle.VehicleController.reassignDrive], 2026-07-16), because
 * [com.kevin.legion.sync.SyncEngine] applies it after the merge and BEFORE uploading the converged
 * snapshot - so the rows Drive receives already carry the corrected id.
 */
@RunWith(RobolectricTestRunner::class)
class MidnightImportReassignmentRuleTest {
    private val context = RuntimeEnvironment.getApplication()
    private val db get() = CarDatabase.getDatabase(context).openHelper.writableDatabase

    private val sentinel = MidnightImport.SENTINEL_VEHICLE_ID
    private val synthetic = "imported-mitsubishi-outlander-2020"

    @Before
    fun clearState() {
        RoomTestReset.resetCarDatabaseSingleton()
    }

    private fun rules(): List<DriveReassignment> {
        val out = mutableListOf<DriveReassignment>()
        db.query("SELECT syncId, vehicleId, fromMs, toMs, newVehicleId, updatedAt FROM drive_reassignments").use { c ->
            while (c.moveToNext()) {
                out += DriveReassignment(
                    syncId = c.getString(0),
                    vehicleId = c.getString(1),
                    fromMs = c.getLong(2),
                    toMs = c.getLong(3),
                    newVehicleId = c.getString(4),
                    updatedAt = c.getLong(5),
                )
            }
        }
        return out
    }

    @Test
    fun `the sentinel re-key is recorded as a synced rule covering the bundle's whole past`() {
        val before = System.currentTimeMillis()
        MidnightImport.recordSentinelReassignments(db, mapOf(sentinel to synthetic))

        val rule = rules().single()
        assertEquals(sentinel, rule.vehicleId)
        assertEquals(synthetic, rule.newVehicleId)
        // Unbounded backwards - the whole of this car's history is on the wrong id.
        assertEquals(0L, rule.fromMs)
        // But bounded at NOW going forwards, and that bound is the point of this assertion.
        // `default` is ALSO this device's live placeholder for an unpaired car, so an unbounded
        // rule would sweep every future sample recorded under it onto the imported car, silently,
        // forever, on every sync pass. Everything the bundle carries is already in the past.
        assertTrue("must not be unbounded forwards", rule.toMs < Long.MAX_VALUE)
        assertTrue("but must still cover everything the bundle carried", rule.toMs >= before)
    }

    /**
     * The import re-runs on every launch until its gate latches, and on Kevin's phone it did that
     * for roughly six passes. A fresh UUID per run would have left six rules all saying the same
     * thing, which `DriveReassigner.plan` would then replay one after another on every single sync.
     */
    @Test
    fun `re-running the import overwrites its own rule rather than stacking another`() {
        repeat(5) { MidnightImport.recordSentinelReassignments(db, mapOf(sentinel to synthetic)) }

        assertEquals("five passes, one rule", 1, rules().size)
        assertEquals(1, DriveReassigner.plan(rules()).size)
    }

    /** Two imported cars would each need their own rule; the map is not assumed to hold one entry. */
    @Test
    fun `each remapped vehicle gets its own rule`() {
        MidnightImport.recordSentinelReassignments(
            db,
            mapOf(sentinel to synthetic, "legacy-sentinel" to "imported-ford-f150-2017"),
        )

        assertEquals(2, rules().size)
        assertEquals(
            setOf(synthetic, "imported-ford-f150-2017"),
            rules().map { it.newVehicleId }.toSet(),
        )
    }

    /**
     * `DriveReassigner.plan` drops self-moves because "a self-move would be an infinite no-op on
     * every sync pass forever" - so one must never be written in the first place.
     */
    @Test
    fun `a vehicle remapped onto itself is never written as a rule`() {
        MidnightImport.recordSentinelReassignments(db, mapOf(sentinel to sentinel))

        assertTrue("a self-move rule is worse than no rule", rules().isEmpty())
    }

    /** The rule has to survive the planner it exists to feed, not just sit in the table. */
    @Test
    fun `the recorded rule plans into exactly the move the import intended`() {
        MidnightImport.recordSentinelReassignments(db, mapOf(sentinel to synthetic))

        val move = DriveReassigner.plan(rules()).single()
        assertEquals(sentinel, move.fromVehicleId)
        assertEquals(synthetic, move.toVehicleId)
        assertEquals(0L, move.fromMs)
        assertTrue("the planner must carry the forward bound through, not widen it", move.toMs < Long.MAX_VALUE)
    }
}

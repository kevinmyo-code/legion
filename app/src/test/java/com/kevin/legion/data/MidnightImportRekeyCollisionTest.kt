package com.kevin.legion.data

import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.testutil.RoomTestReset
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Regression for stage 1 of
 * `.scratch/import-sync-duplication/issues/01-the-import-rekey-and-union-sync-duplicate-a-car-every-launch.md`.
 *
 * [MidnightImport.rekeyExistingRows] moves rows a v1 pass wrote under the sentinel vehicle id onto a
 * synthetic one. Its first version assumed the destination key was always free. On Kevin's phone it
 * was not, and the assumption broke in two different ways depending on whether SQLite happened to be
 * enforcing a constraint over the identity columns:
 *
 *  - `vehicle_specs` (PK `vehicleId`) threw `UNIQUE constraint failed`, which rolled the table back,
 *    marked the pass failed, and left the completion flag unlatched - so the import re-ran on every
 *    single launch, forever.
 *  - `obd_samples` (autoincrement `id`, nothing unique over `vehicleId, pid, timestamp`) silently
 *    moved the row next to the copy already sitting there. Repeated across launches that reached
 *    31,452 rows over 5,242 distinct identities.
 *
 * **A first fix for this (per-row occupancy check, still one `UPDATE`/`DELETE` per bundle row) HUNG
 * on-device** - four-plus minutes with no progress against a ~21s baseline, thread state `D`
 * (uninterruptible I/O), CPU frozen. It survives only as `git stash` ("stage-1 rekey fix, HANGS on
 * device - needs rework"). The leading explanation, reasoned from `OdbSample` carrying no `@Index`
 * over `(vehicleId, pid, timestamp)`: every one of those per-row statements was a full table scan,
 * and the table it scanned was the one this exact bug keeps inflating - up to 11,511 scans of a
 * table that had already grown past 36,000 rows. **This was not directly observed on a device**
 * (no device is available to this session in the state that would exercise it - Kevin's own import
 * already latched complete). The version under test here replaces the per-row statements with a
 * bounded, set-based `DELETE`/`UPDATE` per table per destination - see [MidnightImport.rekeyExistingRows]'s
 * own doc for the full design.
 *
 * **Nothing tested this because the function needs a real database**, and every other test in
 * `MidnightImportTest` is pure (`parseManifest`, `importOrder`, `syntheticVehicleId`). These tests
 * run against real tables through Robolectric, the same shape as `MidnightImportReassignmentRuleTest`.
 */
@RunWith(RobolectricTestRunner::class)
class MidnightImportRekeyCollisionTest {
    private val context = RuntimeEnvironment.getApplication()
    private val db get() = CarDatabase.getDatabase(context).openHelper.writableDatabase

    private val sentinel = MidnightImport.SENTINEL_VEHICLE_ID
    private val synthetic = "imported-mitsubishi-outlander-2020"

    @Before
    fun clearState() {
        RoomTestReset.resetCarDatabaseSingleton()
    }

    /** A shard row as `importTable` hands it over: already re-keyed to the synthetic id. */
    private fun shardSample(pid: String, ts: Long) = JSONObject()
        .put("vehicleId", synthetic)
        .put("pid", pid)
        .put("timestamp", ts)
        .put("value", 1.0)

    /** `unit` is NOT NULL in the real schema, so it is supplied here rather than defaulted. */
    private fun insertSample(vehicleId: String, pid: String, ts: Long, value: Double = 1.0) {
        db.execSQL(
            "INSERT INTO `obd_samples` (`vehicleId`,`pid`,`timestamp`,`value`,`unit`) VALUES (?,?,?,?,?)",
            arrayOf<Any?>(vehicleId, pid, ts, value, "rpm"),
        )
    }

    /** `vehicle_specs` declares fourteen NOT NULL TEXT columns with no SQL default, so a minimal
     * insert is not possible - every one is supplied rather than the test asserting against a shape
     * the real schema would reject. */
    private fun insertSpec(vehicleId: String) {
        val cols = listOf(
            "engineConfig", "fuelType", "transmissionStyle", "transmissionSpeeds", "driveType",
            "bodyClass", "series", "vehicleType", "manufacturer", "plantCity", "plantCountry",
            "paintColor", "paintCode", "buildNotes",
        )
        db.execSQL(
            "INSERT INTO `vehicle_specs` (`vehicleId`,`vin`,`decodedAt`,`updatedAt`," +
                cols.joinToString(",") { "`$it`" } + ") VALUES (?,?,?,?," + cols.joinToString(",") { "?" } + ")",
            (listOf<Any?>(vehicleId, "VIN1", 1L, 1L) + cols.map { "" }).toTypedArray(),
        )
    }

    private fun countSamples(vehicleId: String): Int =
        db.query("SELECT COUNT(*) FROM `obd_samples` WHERE `vehicleId`=?", arrayOf<Any?>(vehicleId))
            .use { if (it.moveToFirst()) it.getInt(0) else 0 }

    private val obdSpec get() = MidnightImport.SPECS.first { it.table == "obd_samples" }
    private val specsSpec get() = MidnightImport.SPECS.first { it.table == "vehicle_specs" }

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


    /**
     * The duplication half. A free destination must still MOVE, or the repair does nothing at all -
     * this is the case the original code got right and the fix must not regress.
     */
    @Test
    fun `a free destination still moves the row`() {
        insertSample(sentinel, "0104", 1_000L)

        val result = MidnightImport.rekeyExistingRows(
            db, obdSpec, listOf(shardSample("0104", 1_000L)), mapOf(sentinel to synthetic),
        )

        assertEquals("moved, not discarded", MidnightImport.RekeyResult(moved = 1, discarded = 0), result)
        assertEquals(0, countSamples(sentinel))
        assertEquals(1, countSamples(synthetic))
    }

    /**
     * The duplication itself. Before the fix this left TWO rows under the synthetic id, and reported
     * `rekeyed=1` as though that were success - which is how six passes read as six clean ones.
     */
    @Test
    fun `an occupied destination discards the sentinel row instead of stacking a duplicate`() {
        insertSample(synthetic, "0104", 1_000L)
        insertSample(sentinel, "0104", 1_000L)

        val result = MidnightImport.rekeyExistingRows(
            db, obdSpec, listOf(shardSample("0104", 1_000L)), mapOf(sentinel to synthetic),
        )

        assertEquals("discarded, and NOT counted as a move", MidnightImport.RekeyResult(moved = 0, discarded = 1), result)
        assertEquals("the sentinel copy is gone", 0, countSamples(sentinel))
        assertEquals("and exactly one survives - not two", 1, countSamples(synthetic))
    }

    /**
     * The loop, run twice. This is the property that actually matters: repeating the pass must not
     * keep adding copies. Before the fix each pass added one, uniformly, which is what produced a
     * 6.0x factor across two unrelated tables.
     */
    @Test
    fun `repeating the pass never grows the row count`() {
        insertSample(synthetic, "0104", 1_000L)
        val rows = listOf(shardSample("0104", 1_000L))

        repeat(4) {
            // Each round, sync re-inserts the sentinel-keyed row from Drive. That part is not fixed
            // here (it needs the reassignment work, stage 2) - what is fixed is that absorbing it
            // costs nothing.
            insertSample(sentinel, "0104", 1_000L)
            MidnightImport.rekeyExistingRows(db, obdSpec, rows, mapOf(sentinel to synthetic))
        }

        assertEquals("bounded across repeats, not 5", 1, countSamples(synthetic))
        assertEquals(0, countSamples(sentinel))
    }

    /**
     * A table with SEVERAL colliding identities in one pass, not just one - the shape a real
     * `obd_samples` shard has (thousands of rows, not a single sample). Exercises the batched
     * temp-table path with more than one row in both the move group and the discard group.
     */
    @Test
    fun `a batch with a mix of free and occupied destinations resolves each independently`() {
        // 0104@1000 and 0106@3000 already exist at the destination; 0105@2000 does not.
        insertSample(synthetic, "0104", 1_000L)
        insertSample(synthetic, "0106", 3_000L)
        insertSample(sentinel, "0104", 1_000L)
        insertSample(sentinel, "0105", 2_000L)
        insertSample(sentinel, "0106", 3_000L)

        val result = MidnightImport.rekeyExistingRows(
            db,
            obdSpec,
            listOf(shardSample("0104", 1_000L), shardSample("0105", 2_000L), shardSample("0106", 3_000L)),
            mapOf(sentinel to synthetic),
        )

        assertEquals(MidnightImport.RekeyResult(moved = 1, discarded = 2), result)
        assertEquals("nothing left under the sentinel", 0, countSamples(sentinel))
        assertEquals("one surviving copy per identity, not two", 3, countSamples(synthetic))
    }

    /**
     * The other failure direction: a table whose identity IS its primary key threw rather than
     * duplicating, and the throw is what kept the completion flag unlatched. It must now complete
     * normally, because the gate latching is the difference between running once and running forever.
     */
    @Test
    fun `a real primary key collision no longer throws`() {
        insertSpec(synthetic)
        insertSpec(sentinel)

        val result = MidnightImport.rekeyExistingRows(
            db,
            specsSpec,
            listOf(JSONObject().put("vehicleId", synthetic).put("vin", "VIN1").put("updatedAt", 1L)),
            mapOf(sentinel to synthetic),
        )

        assertEquals(MidnightImport.RekeyResult(moved = 0, discarded = 1), result)
        val remaining = db.query("SELECT COUNT(*) FROM `vehicle_specs`").use { if (it.moveToFirst()) it.getInt(0) else -1 }
        assertEquals("one spec row survives, and no exception was thrown getting here", 1, remaining)
    }

    /** The natural-PK table's free-destination case, mirrored from the `obd_samples` one above so
     * the `nonVehicleCols.isEmpty()` branch is covered on both sides, not just the collision side. */
    @Test
    fun `a real primary key table still moves when the destination is free`() {
        insertSpec(sentinel)

        val result = MidnightImport.rekeyExistingRows(
            db,
            specsSpec,
            listOf(JSONObject().put("vehicleId", synthetic).put("vin", "VIN1").put("updatedAt", 1L)),
            mapOf(sentinel to synthetic),
        )

        assertEquals(MidnightImport.RekeyResult(moved = 1, discarded = 0), result)
        val remaining = db.query("SELECT COUNT(*) FROM `vehicle_specs` WHERE `vehicleId`=?", arrayOf<Any?>(synthetic))
            .use { if (it.moveToFirst()) it.getInt(0) else -1 }
        assertEquals(1, remaining)
    }

    /**
     * `places` has no `vehicleId` column at all - its identity is `label`. The `row.has(VEHICLE_COL)`
     * guard has always kept such tables out of this function entirely, and the first stage-1 attempt
     * broke that by loading the destination-identity set BEFORE the guard, which threw
     * `no such column: vehicleId` and failed the table on the A25. Pinned here because the guard is
     * invisible unless you know why it exists.
     */
    @Test
    fun `a table with no vehicle column is left entirely alone`() {
        db.execSQL(
            "INSERT INTO `places` (`label`,`latitude`,`longitude`,`timestamp`) VALUES (?,?,?,?)",
            arrayOf<Any?>("home", 1.0, 2.0, 1L),
        )
        val placesSpec = MidnightImport.SPECS.first { it.table == "places" }

        // A places shard row carries no vehicleId, exactly as the real bundle's does not.
        val result = MidnightImport.rekeyExistingRows(
            db,
            placesSpec,
            listOf(JSONObject().put("label", "home").put("latitude", 1.0).put("longitude", 2.0).put("timestamp", 1L)),
            mapOf(sentinel to synthetic),
        )

        assertEquals("nothing moved, nothing discarded, and no exception", MidnightImport.RekeyResult(0, 0), result)
        val kept = db.query("SELECT COUNT(*) FROM `places`").use { if (it.moveToFirst()) it.getInt(0) else -1 }
        assertEquals(1, kept)
    }

    /**
     * The precision guarantee this function has always carried, re-pinned because the fix still ends
     * in a DELETE and a DELETE is the one operation that would make losing it expensive: a row the
     * DRIVER recorded locally under `default` shares the sentinel id but matches no bundle row's
     * identity, so it must be neither moved nor deleted.
     */
    @Test
    fun `a locally-recorded sentinel row the bundle does not know about is left alone`() {
        insertSample(sentinel, "0105", 9_999L)
        insertSample(synthetic, "0104", 1_000L)
        insertSample(sentinel, "0104", 1_000L)

        MidnightImport.rekeyExistingRows(
            db, obdSpec, listOf(shardSample("0104", 1_000L)), mapOf(sentinel to synthetic),
        )

        val survivor = db.query(
            "SELECT COUNT(*) FROM `obd_samples` WHERE `vehicleId`=? AND `pid`=? AND `timestamp`=?",
            arrayOf<Any?>(sentinel, "0105", 9_999L),
        ).use { if (it.moveToFirst()) it.getInt(0) else 0 }
        assertEquals("the driver's own row is untouched by the discard", 1, survivor)
    }

    /** An empty bundle (no candidate rows carry a remapped vehicle id) must be a true no-op, not a
     * zero-row batch that still opens a transaction and touches the table. */
    @Test
    fun `a bundle with nothing to rekey does no work and returns zero for both counts`() {
        insertSample(sentinel, "0104", 1_000L)

        val result = MidnightImport.rekeyExistingRows(
            db, obdSpec, emptyList(), mapOf(sentinel to synthetic),
        )

        assertEquals(MidnightImport.RekeyResult(0, 0), result)
        assertEquals("untouched - nothing in the bundle named it", 1, countSamples(sentinel))
    }
}

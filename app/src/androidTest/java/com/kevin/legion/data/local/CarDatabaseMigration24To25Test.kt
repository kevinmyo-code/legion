package com.kevin.legion.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented migration test for v24 -> v25 - the `obd_samples(vehicleId, pid, timestamp)`
 * composite index (Kevin's device, 2026-08-16). See [MIGRATION_24_25]'s own doc comment and
 * [OdbSample]'s own doc comment for the full story: `obd_samples` had 18,694 rows and zero
 * indexes, and the FAULTS drilldown's `getRange` calls were a full `SCAN` plus a temp-b-tree sort,
 * twice per code event. `createSql` confirmed against the generated
 * `app/schemas/com.kevin.legion.data.local.CarDatabase/25.json` after a kapt run rather than
 * assumed - byte for byte the same string this migration's own `execSQL` call uses.
 *
 * **NOT RUN this session** - `connectedAndroidTest` uninstalls the app and would take Kevin's real
 * data (CLAUDE.md L11, `memory/MEMORY.md`). Compiled only. The plan-change claim this index exists
 * to prove is instead demonstrated separately, off-device, against a throwaway SQLite database with
 * the same table shape (18,694 synthetic rows): `EXPLAIN QUERY PLAN` on [OdbSampleDao.getRange]'s
 * exact SQL went from `SCAN obd_samples` + `USE TEMP B-TREE FOR ORDER BY` to `SEARCH obd_samples
 * USING INDEX index_obd_samples_vehicleId_pid_timestamp (vehicleId=? AND pid=? AND timestamp>? AND
 * timestamp<?)` after creating this exact index. On-device confirmation (the database actually
 * opening after install, and a real `EXPLAIN QUERY PLAN` against Kevin's live 18,694-row table) is
 * still a follow-up.
 */
@RunWith(AndroidJUnit4::class)
class CarDatabaseMigration24To25Test {
    private val dbName = "migration-test-24-25"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        CarDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrate24To25_isPurelyAdditive_everyExistingRowSurvivesWithIdenticalValues() {
        // A couple of real-shaped rows (two PIDs, one with GPS filled in, one without) - nothing
        // about adding an index should touch a single stored value.
        helper.createDatabase(dbName, 24).apply {
            execSQL(
                "INSERT INTO obd_samples (vehicleId, pid, value, unit, timestamp, lat, lng) " +
                    "VALUES ('AA:BB', '010C', 2200.0, 'rpm', 1000, NULL, NULL)"
            )
            execSQL(
                "INSERT INTO obd_samples (vehicleId, pid, value, unit, timestamp, lat, lng) " +
                    "VALUES ('AA:BB', '010D', 62.0, 'mph', 1000, 47.6, -122.3)"
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(dbName, 25, true, MIGRATION_24_25)

        val count = db.query("SELECT COUNT(*) FROM obd_samples")
        assertTrue(count.moveToFirst())
        assertEquals(2, count.getInt(0))
        count.close()

        val row = db.query(
            "SELECT value, unit, lat, lng FROM obd_samples WHERE pid = '010D' AND timestamp = 1000"
        )
        assertTrue(row.moveToFirst())
        assertEquals(62.0, row.getDouble(0), 0.0001)
        assertEquals("mph", row.getString(1))
        assertEquals(47.6, row.getDouble(2), 0.0001)
        assertEquals(-122.3, row.getDouble(3), 0.0001)
        row.close()
    }

    @Test
    fun migrate24To25_indexExistsAndCoversTheGetRangeShape() {
        // Proves the index is real SQLite DDL post-migration (not just schema-JSON metadata) and
        // that SQLite's own planner picks it up for getRange's exact WHERE + ORDER BY shape -
        // `sqlite_master` + `EXPLAIN QUERY PLAN` are queryable through the same SupportSQLiteDatabase
        // MigrationTestHelper hands back, no separate connection needed.
        helper.createDatabase(dbName, 24).apply { close() }
        val db = helper.runMigrationsAndValidate(dbName, 25, true, MIGRATION_24_25)

        val indexRow = db.query(
            "SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'obd_samples' " +
                "AND name = 'index_obd_samples_vehicleId_pid_timestamp'"
        )
        assertTrue(indexRow.moveToFirst())
        assertEquals("index_obd_samples_vehicleId_pid_timestamp", indexRow.getString(0))
        indexRow.close()

        val plan = db.query(
            "EXPLAIN QUERY PLAN SELECT * FROM obd_samples WHERE vehicleId = 'AA:BB' AND pid = '010C' " +
                "AND timestamp >= 0 AND timestamp <= 9999999999 ORDER BY timestamp ASC"
        )
        val detailColumn = plan.getColumnIndex("detail")
        var usedTheIndex = false
        while (plan.moveToNext()) {
            val detail = plan.getString(detailColumn)
            if (detail.contains("USING INDEX index_obd_samples_vehicleId_pid_timestamp")) {
                usedTheIndex = true
            }
        }
        plan.close()
        assertTrue("expected getRange's shape to use the new index", usedTheIndex)
    }
}

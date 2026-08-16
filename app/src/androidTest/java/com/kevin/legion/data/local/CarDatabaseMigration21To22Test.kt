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
 * Instrumented migration test for v21 -> v22 - `code_clear_events`, fleet's first WRITE to the car
 * (`.scratch/hands-and-senses/issues/01-clear-dtc.md`, resolved 2026-08-16). See
 * [MIGRATION_21_22]'s own doc comment for the full story: one additive `CREATE TABLE`, `createSql`
 * confirmed against the generated `app/schemas/com.kevin.legion.data.local.CarDatabase/22.json`
 * after a kapt run rather than assumed - byte for byte the same string this migration's own
 * `execSQL` call uses.
 *
 * **NOT RUN this session** - `connectedAndroidTest` uninstalls the app and would take Kevin's real
 * data (CLAUDE.md L11, `memory/MEMORY.md`). Compiled only. On-device evidence is the database
 * opening after install, per the ticket's own verification section.
 */
@RunWith(AndroidJUnit4::class)
class CarDatabaseMigration21To22Test {
    private val dbName = "migration-test-21-22"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        CarDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun `migrate21To22_createsAnEmptyCodeClearEventsTable`() {
        helper.createDatabase(dbName, 21).apply { close() }

        val db = helper.runMigrationsAndValidate(dbName, 22, true, MIGRATION_21_22)

        val cursor = db.query("SELECT COUNT(*) FROM code_clear_events")
        assertTrue(cursor.moveToFirst())
        assertEquals(0, cursor.getInt(0))
        cursor.close()
    }

    @Test
    fun `migrate21To22_freshInsertRoundTripsEveryColumn`() {
        helper.createDatabase(dbName, 21).apply { close() }
        val db = helper.runMigrationsAndValidate(dbName, 22, true, MIGRATION_21_22)

        db.execSQL(
            "INSERT INTO code_clear_events " +
                "(vehicleId, timestamp, mileage, codesBeforeJson, freezeFrameJson, codesAfterJson, outcome, ackRaw, syncId) " +
                "VALUES ('AA:BB', 1000, 227374, '[\"P0420\"]', '', '[]', 'CLEARED', '44', 's-1')"
        )
        val cursor = db.query("SELECT vehicleId, mileage, outcome, syncId FROM code_clear_events WHERE timestamp = 1000")
        assertTrue(cursor.moveToFirst())
        assertEquals("AA:BB", cursor.getString(0))
        assertEquals(227374, cursor.getInt(1))
        assertEquals("CLEARED", cursor.getString(2))
        assertEquals("s-1", cursor.getString(3))
        cursor.close()
    }

    @Test
    fun `migrate21To22_syncIdDefaultsToEmptyStringWhenOmitted`() {
        // Mirrors CodeEvent's own @ColumnInfo(defaultValue = "''") convention - confirms the ADD
        // COLUMN-equivalent CREATE TABLE's DEFAULT '' is real at the SQLite level, matching
        // MIGRATION_20_21's own "fresh insert after migration" precedent.
        helper.createDatabase(dbName, 21).apply { close() }
        val db = helper.runMigrationsAndValidate(dbName, 22, true, MIGRATION_21_22)

        db.execSQL(
            "INSERT INTO code_clear_events " +
                "(vehicleId, timestamp, codesBeforeJson, freezeFrameJson, codesAfterJson, outcome, ackRaw) " +
                "VALUES ('AA:BB', 2000, '[\"P0420\"]', '', '', 'REFUSED', '')"
        )
        val cursor = db.query("SELECT syncId FROM code_clear_events WHERE timestamp = 2000")
        assertTrue(cursor.moveToFirst())
        assertEquals("", cursor.getString(0))
        cursor.close()
    }
}

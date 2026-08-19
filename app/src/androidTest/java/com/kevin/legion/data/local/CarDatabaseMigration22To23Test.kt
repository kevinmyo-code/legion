package com.kevin.legion.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented migration test for v22 -> v23 - `drives`, the drive-boundary object
 * (`.scratch/drive-ui/issues/05-trip-content.md` Q14, `09-mpg-scale-bug.md`'s "bigger finding").
 * See [MIGRATION_22_23]'s own doc comment for the full story: one additive `CREATE TABLE`,
 * `createSql` confirmed against the generated
 * `app/schemas/com.kevin.legion.data.local.CarDatabase/23.json` after a kapt run rather than
 * assumed - byte for byte the same string this migration's own `execSQL` call uses.
 *
 * **NOT RUN this session** - `connectedAndroidTest` uninstalls the app and would take Kevin's real
 * data (CLAUDE.md L11, `memory/MEMORY.md`). Compiled only. On-device evidence is the database
 * opening after install, per this ticket's own verification instructions.
 */
@RunWith(AndroidJUnit4::class)
class CarDatabaseMigration22To23Test {
    private val dbName = "migration-test-22-23"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        CarDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun `migrate22To23_createsAnEmptyDrivesTable`() {
        helper.createDatabase(dbName, 22).apply { close() }

        val db = helper.runMigrationsAndValidate(dbName, 23, true, MIGRATION_22_23)

        val cursor = db.query("SELECT COUNT(*) FROM drives")
        assertTrue(cursor.moveToFirst())
        assertEquals(0, cursor.getInt(0))
        cursor.close()
    }

    @Test
    fun `migrate22To23_freshInsertRoundTripsEveryColumn`() {
        helper.createDatabase(dbName, 22).apply { close() }
        val db = helper.runMigrationsAndValidate(dbName, 23, true, MIGRATION_22_23)

        db.execSQL(
            "INSERT INTO drives " +
                "(vehicleId, startedAt, endedAt, miles, gallons, endReason, syncId) " +
                "VALUES ('AA:BB', 1000, 2000, 20.7, 0.723, 'ENGINE_OFF', 's-1')"
        )
        val cursor = db.query("SELECT vehicleId, miles, gallons, endReason, syncId FROM drives WHERE startedAt = 1000")
        assertTrue(cursor.moveToFirst())
        assertEquals("AA:BB", cursor.getString(0))
        assertEquals(20.7, cursor.getDouble(1), 0.0001)
        assertEquals(0.723, cursor.getDouble(2), 0.0001)
        assertEquals("ENGINE_OFF", cursor.getString(3))
        assertEquals("s-1", cursor.getString(4))
        cursor.close()
    }

    @Test
    fun `migrate22To23_gallonsIsNullableForAMilesOnlyDrive`() {
        // The whole point of Drive.gallons being nullable (never 0-0) when MAF never answered -
        // see Drive's own doc comment. Confirms the column itself accepts and round-trips NULL,
        // not just that the Kotlin type says it should.
        helper.createDatabase(dbName, 22).apply { close() }
        val db = helper.runMigrationsAndValidate(dbName, 23, true, MIGRATION_22_23)

        db.execSQL(
            "INSERT INTO drives " +
                "(vehicleId, startedAt, endedAt, miles, gallons, endReason, syncId) " +
                "VALUES ('AA:BB', 3000, 4000, 5.2, NULL, 'LINK_LOST', 's-2')"
        )
        val cursor = db.query("SELECT gallons, endReason FROM drives WHERE startedAt = 3000")
        assertTrue(cursor.moveToFirst())
        assertNull(cursor.getString(0))
        assertEquals("LINK_LOST", cursor.getString(1))
        cursor.close()
    }

    @Test
    fun `migrate22To23_syncIdDefaultsToEmptyStringWhenOmitted`() {
        // Mirrors CodeClearEvent's own @ColumnInfo(defaultValue = "''") convention - confirms the
        // ADD COLUMN-equivalent CREATE TABLE's DEFAULT '' is real at the SQLite level, matching
        // MIGRATION_21_22's own "fresh insert after migration" precedent.
        helper.createDatabase(dbName, 22).apply { close() }
        val db = helper.runMigrationsAndValidate(dbName, 23, true, MIGRATION_22_23)

        db.execSQL(
            "INSERT INTO drives (vehicleId, startedAt, endedAt, miles, endReason) " +
                "VALUES ('AA:BB', 5000, 6000, 1.5, 'ENGINE_OFF')"
        )
        val cursor = db.query("SELECT syncId FROM drives WHERE startedAt = 5000")
        assertTrue(cursor.moveToFirst())
        assertEquals("", cursor.getString(0))
        cursor.close()
    }
}

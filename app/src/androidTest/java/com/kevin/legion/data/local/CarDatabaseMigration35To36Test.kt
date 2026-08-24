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
 * Instrumented migration test for v35 -> v36 - `muted_reminders` (aspect-engine ticket 19, the
 * Dates aspect build), a permanent per-record reminder mute deliberately outside
 * [com.kevin.legion.engine.RecordStore]'s write door - see [MutedReminder]'s own doc comment for
 * why. One `CREATE TABLE IF NOT EXISTS`, nothing else touched. `createSql` in [MIGRATION_35_36]
 * is PASTED VERBATIM from the kapt-generated `app/schemas/com.kevin.legion.data.local.CarDatabase/36.json`
 * after a real `compileDebugKotlin -Pnokey` run - confirmed to differ from a hand-written first
 * draft only in how the primary key is expressed (`PRIMARY KEY(\`recordId\`)` as a trailing table
 * constraint, which is what Room's own generator emits for a single-column `@PrimaryKey` with no
 * `autoGenerate`, rather than an inline `INTEGER PRIMARY KEY` column modifier), matching
 * [CarDatabaseMigration34To35Test]'s own "confirm against the generated schema after a real
 * compile" discipline.
 *
 * **NOT RUN this session** - `connectedAndroidTest` uninstalls the app and would take Kevin's
 * real data (CLAUDE.md L11, `memory/MEMORY.md`). Compiled only, same posture as
 * [CarDatabaseMigration34To35Test]/[CarDatabaseMigration33To34Test]. On-device evidence (a
 * `muted_reminders` row written at v36 reading back, and every other table's existing data
 * untouched by the upgrade) is deferred to on-device QA.
 */
@RunWith(AndroidJUnit4::class)
class CarDatabaseMigration35To36Test {
    private val dbName = "migration-test-35-36"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        CarDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun `migrate35To36_isPurelyAdditive_existingRowUntouched`() {
        helper.createDatabase(dbName, 35).apply {
            execSQL(
                "INSERT INTO widget_instances (deviceId, aspectId, recordTypeId, widgetType, config, position, gridRow, gridCol, rowSpan, colSpan, createdAt, updatedAt) " +
                    "VALUES ('device-1', NULL, NULL, 'STAT_TILE', '{}', 0, 0, 0, 1, 1, 1000, 1000)"
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(dbName, 36, true, MIGRATION_35_36)

        val cursor = db.query("SELECT deviceId, widgetType FROM widget_instances WHERE deviceId = 'device-1'")
        assertTrue(cursor.moveToFirst())
        assertEquals("device-1", cursor.getString(0))
        assertEquals("STAT_TILE", cursor.getString(1))
        cursor.close()
    }

    @Test
    fun `migrate35To36_mutedRemindersTableExistsAndIsWritable`() {
        helper.createDatabase(dbName, 35).apply { close() }

        val db = helper.runMigrationsAndValidate(dbName, 36, true, MIGRATION_35_36)

        db.execSQL("INSERT INTO muted_reminders (recordId, mutedAt) VALUES (42, 5000)")
        val cursor = db.query("SELECT recordId, mutedAt FROM muted_reminders WHERE recordId = 42")
        assertTrue(cursor.moveToFirst())
        assertEquals(42, cursor.getInt(0))
        assertEquals(5000, cursor.getInt(1))
        cursor.close()
    }

    @Test
    fun `migrate35To36_recordIdIsThePrimaryKey_aSecondInsertForTheSameRecordFails`() {
        helper.createDatabase(dbName, 35).apply { close() }

        val db = helper.runMigrationsAndValidate(dbName, 36, true, MIGRATION_35_36)

        db.execSQL("INSERT INTO muted_reminders (recordId, mutedAt) VALUES (7, 1000)")
        var threw = false
        try {
            db.execSQL("INSERT INTO muted_reminders (recordId, mutedAt) VALUES (7, 2000)")
        } catch (e: Exception) {
            threw = true
        }
        assertTrue("recordId must be the primary key - a duplicate insert must fail, not silently duplicate", threw)
    }
}

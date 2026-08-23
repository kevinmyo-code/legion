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
 * Instrumented migration test for v33 -> v34 - the aspect engine core
 * (`.scratch/aspect-engine/issues/16-build-engine-core.md`). Five additive `CREATE TABLE`s,
 * nothing existing touched. `createSql` confirmed against the generated
 * `app/schemas/com.kevin.legion.data.local.CarDatabase/34.json` after a real `compileDebugKotlin
 * -Pnokey` run, not hand-derived - same discipline [MIGRATION_32_33]'s own test documents.
 *
 * **NOT RUN this session** - `connectedAndroidTest` uninstalls the app and would take Kevin's real
 * data (CLAUDE.md L11, `memory/MEMORY.md`). Compiled only. On-device evidence (the database opening
 * after install with the five new tables present and every existing table untouched) is deferred
 * to on-device QA.
 */
@RunWith(AndroidJUnit4::class)
class CarDatabaseMigration33To34Test {
    private val dbName = "migration-test-33-34"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        CarDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun `migrate33To34_isPurelyAdditive_existingTableUntouched`() {
        helper.createDatabase(dbName, 33).apply {
            execSQL(
                "INSERT INTO workout_set_logs (exercise, sets, loggedAt, trustTier) " +
                    "VALUES ('Kettlebell swing', 3, 1000, 'REPORTED')"
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(dbName, 34, true, MIGRATION_33_34)

        val cursor = db.query("SELECT exercise, sets FROM workout_set_logs WHERE exercise = 'Kettlebell swing'")
        assertTrue(cursor.moveToFirst())
        assertEquals("Kettlebell swing", cursor.getString(0))
        assertEquals(3, cursor.getInt(1))
        cursor.close()
    }

    @Test
    fun `migrate33To34_createsAllFiveEngineTablesWritable`() {
        helper.createDatabase(dbName, 33).apply { close() }
        val db = helper.runMigrationsAndValidate(dbName, 34, true, MIGRATION_33_34)

        val now = 1000L
        db.execSQL(
            "INSERT INTO aspects (name, icon, color, position, archived, archivedAt, createdAt, updatedAt) " +
                "VALUES ('Fleet', '', '', 0, 0, NULL, ?, ?)",
            arrayOf<Any>(now, now),
        )
        val aspectCursor = db.query("SELECT id, name FROM aspects WHERE name = 'Fleet'")
        assertTrue(aspectCursor.moveToFirst())
        val aspectId = aspectCursor.getLong(0)
        aspectCursor.close()

        db.execSQL(
            "INSERT INTO record_types (aspectId, name, primaryAmountFieldId, primaryDueDateFieldId, createdAt, updatedAt) " +
                "VALUES (?, 'Vehicle', NULL, NULL, ?, ?)",
            arrayOf<Any>(aspectId, now, now),
        )
        val recordTypeCursor = db.query("SELECT id FROM record_types WHERE name = 'Vehicle'")
        assertTrue(recordTypeCursor.moveToFirst())
        val recordTypeId = recordTypeCursor.getLong(0)
        recordTypeCursor.close()

        db.execSQL(
            "INSERT INTO field_defs (recordTypeId, name, type, required, position, config, ownerPluginId, locked, createdAt, updatedAt) " +
                "VALUES (?, 'name', 'TEXT', 0, 0, NULL, NULL, 0, ?, ?)",
            arrayOf<Any>(recordTypeId, now, now),
        )

        db.execSQL(
            "INSERT INTO records (recordTypeId, createdAt, updatedAt, dueAt, amountCents, searchText, provenance, payload, deletedAt) " +
                "VALUES (?, ?, ?, NULL, NULL, '', 'USER', '{}', NULL)",
            arrayOf<Any>(recordTypeId, now, now),
        )
        val recordCursor = db.query("SELECT COUNT(*) FROM records WHERE recordTypeId = ?", arrayOf<Any>(recordTypeId))
        assertTrue(recordCursor.moveToFirst())
        assertEquals(1, recordCursor.getInt(0))
        recordCursor.close()

        db.execSQL(
            "INSERT INTO widget_instances (deviceId, aspectId, recordTypeId, widgetType, config, position, createdAt, updatedAt) " +
                "VALUES ('device-1', ?, ?, 'LIST', '{}', 0, ?, ?)",
            arrayOf<Any>(aspectId, recordTypeId, now, now),
        )
        val widgetCursor = db.query("SELECT deviceId FROM widget_instances WHERE deviceId = 'device-1'")
        assertTrue(widgetCursor.moveToFirst())
        assertEquals("device-1", widgetCursor.getString(0))
        widgetCursor.close()
    }

    @Test
    fun `migrate33To34_recordsDeletedAtDefaultsNull`() {
        helper.createDatabase(dbName, 33).apply { close() }
        val db = helper.runMigrationsAndValidate(dbName, 34, true, MIGRATION_33_34)

        db.execSQL(
            "INSERT INTO record_types (aspectId, name, createdAt, updatedAt) VALUES (1, 'Note', 1000, 1000)"
        )
        db.execSQL(
            "INSERT INTO records (recordTypeId, createdAt, updatedAt, searchText, provenance, payload) " +
                "VALUES (1, 1000, 1000, '', 'USER', '{}')"
        )
        val cursor = db.query("SELECT deletedAt FROM records WHERE recordTypeId = 1")
        assertTrue(cursor.moveToFirst())
        assertNull(cursor.getString(0))
        cursor.close()
    }
}

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
 * Instrumented migration test for v42 -> v43 - `events_replica` gains `kind`
 * (`.scratch/backend-erp/issues/11-notes-write-path-rewire.md`'s 2026-08-27 ruling #1). One
 * additive `ALTER TABLE ADD COLUMN`, same shape as [CarDatabaseMigration40To41Test] - see
 * [MIGRATION_42_43]'s own doc comment for why the replica genuinely needed this column (not a
 * cosmetic addition: it is what lets `NotesController` stop reading Dates appointments as
 * reminders it owns).
 *
 * **NOT RUN this session** - `connectedAndroidTest` uninstalls the app and would take Kevin's real
 * data (CLAUDE.md L11, `memory/MEMORY.md`). Compiled only, confirmed via
 * `compileDebugAndroidTestKotlin -Pnokey`. On-device evidence is deferred to on-device QA, same
 * posture as [CarDatabaseMigration41To42Test].
 */
@RunWith(AndroidJUnit4::class)
class CarDatabaseMigration42To43Test {
    private val dbName = "migration-test-42-43"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        CarDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun `migrate42To43_existingRowsBackfillToReminder`() {
        helper.createDatabase(dbName, 42).apply {
            execSQL(
                "INSERT INTO events_replica (id, serverId, title, startsAt, allDay, source, done, exact, exactDowngraded, updatedAtMs, deleted, createdAt) " +
                    "VALUES (1, 'server-1', 'Dentist', 50000, 0, 'legion', 0, 0, 0, 100, 0, 12345)"
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(dbName, 43, true, MIGRATION_42_43)

        val cursor = db.query("SELECT kind FROM events_replica WHERE id = 1")
        assertTrue(cursor.moveToFirst())
        assertEquals("reminder", cursor.getString(0))
        cursor.close()
    }

    @Test
    fun `migrate42To43_canInsertAnAppointmentRow`() {
        helper.createDatabase(dbName, 42).apply { close() }

        val db = helper.runMigrationsAndValidate(dbName, 43, true, MIGRATION_42_43)

        db.execSQL(
            "INSERT INTO events_replica (id, serverId, title, startsAt, allDay, source, kind, done, exact, exactDowngraded, updatedAtMs, deleted, createdAt) " +
                "VALUES (2, 'server-2', 'Standup', 60000, 0, 'legion', 'appointment', 0, 0, 0, 200, 0, 22345)"
        )
        val cursor = db.query("SELECT kind FROM events_replica WHERE id = 2")
        assertTrue(cursor.moveToFirst())
        assertEquals("appointment", cursor.getString(0))
        cursor.close()
    }

    @Test
    fun `migrate42To43_isOtherwiseAdditive_everyOtherColumnUnchanged`() {
        helper.createDatabase(dbName, 42).apply {
            execSQL(
                "INSERT INTO events_replica (id, serverId, title, startsAt, allDay, source, done, exact, exactDowngraded, updatedAtMs, deleted, createdAt) " +
                    "VALUES (1, 'server-1', 'Dentist', 50000, 0, 'legion', 0, 0, 0, 100, 0, 12345)"
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(dbName, 43, true, MIGRATION_42_43)

        val cursor = db.query("SELECT title, startsAt, createdAt FROM events_replica WHERE id = 1")
        assertTrue(cursor.moveToFirst())
        assertEquals("Dentist", cursor.getString(0))
        assertEquals(50000L, cursor.getLong(1))
        assertEquals(12345L, cursor.getLong(2))
        cursor.close()
    }
}

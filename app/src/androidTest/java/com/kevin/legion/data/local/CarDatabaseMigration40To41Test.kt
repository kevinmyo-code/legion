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
 * Instrumented migration test for v40 -> v41 - `events_replica` gains `createdAt`
 * (`INTEGER NOT NULL DEFAULT 0`), a plain additive `ALTER TABLE ADD COLUMN`
 * (`.scratch/backend-erp/issues/11-notes-write-path-rewire.md`'s own follow-up - see
 * [MIGRATION_40_41]'s own doc comment for why this column is not cosmetic).
 *
 * **NOT RUN this session** - `connectedAndroidTest` uninstalls the app and would take Kevin's real
 * data (CLAUDE.md L11, `memory/MEMORY.md`; a prior run in this exact session already destroyed
 * real on-device data). Compiled only, confirmed via `compileDebugAndroidTestKotlin -Pnokey`.
 * On-device evidence is deferred to on-device QA. Same posture as [CarDatabaseMigration39To40Test].
 */
@RunWith(AndroidJUnit4::class)
class CarDatabaseMigration40To41Test {
    private val dbName = "migration-test-40-41"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        CarDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun `migrate40To41_existingRow_getsTheDefaultZeroCreatedAt`() {
        helper.createDatabase(dbName, 40).apply {
            execSQL(
                "INSERT INTO events_replica (id, serverId, title, startsAt, allDay, source, done, exact, exactDowngraded, updatedAtMs, deleted) " +
                    "VALUES (1, 'server-1', 'Dentist', 50000, 0, 'legion', 0, 0, 0, 100, 0)"
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(dbName, 41, true, MIGRATION_40_41)

        val cursor = db.query("SELECT createdAt FROM events_replica WHERE id = 1")
        assertTrue(cursor.moveToFirst())
        assertEquals(
            "a pre-existing row predates the column entirely - 0 is a schema-validity placeholder, never a real creation time",
            0L,
            cursor.getLong(0),
        )
        cursor.close()
    }

    @Test
    fun `migrate40To41_canInsertARealCreatedAt`() {
        helper.createDatabase(dbName, 40).apply { close() }

        val db = helper.runMigrationsAndValidate(dbName, 41, true, MIGRATION_40_41)

        db.execSQL(
            "INSERT INTO events_replica (id, serverId, title, startsAt, allDay, source, done, exact, exactDowngraded, updatedAtMs, deleted, createdAt) " +
                "VALUES (2, 'server-2', 'Call the vet', NULL, 1, 'legion', 0, 0, 0, 200, 0, 12345)"
        )

        val cursor = db.query("SELECT createdAt FROM events_replica WHERE id = 2")
        assertTrue(cursor.moveToFirst())
        assertEquals(12345L, cursor.getLong(0))
        cursor.close()
    }

    @Test
    fun `migrate40To41_isOtherwiseAdditive_everyOtherColumnUnchanged`() {
        helper.createDatabase(dbName, 40).apply {
            execSQL(
                "INSERT INTO events_replica (id, serverId, title, startsAt, allDay, source, done, exact, exactDowngraded, updatedAtMs, deleted) " +
                    "VALUES (1, 'server-1', 'Dentist', 50000, 0, 'legion', 0, 0, 0, 100, 0)"
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(dbName, 41, true, MIGRATION_40_41)

        val cursor = db.query("SELECT title, startsAt, updatedAtMs FROM events_replica WHERE id = 1")
        assertTrue(cursor.moveToFirst())
        assertEquals("Dentist", cursor.getString(0))
        assertEquals(50000L, cursor.getLong(1))
        assertEquals(100L, cursor.getLong(2))
        cursor.close()
    }
}

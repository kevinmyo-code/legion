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
 * Instrumented migration test for v47 -> v48 - `events` gains `structuredMeta` (backend-erp ticket
 * 17's repoint, "RULED 2026-08-28": Dates now writes the local `events` table directly, with no
 * server round-trip, so a Room column is the only surviving place for a Google `LEGION::v1` block).
 * A plain additive `ALTER TABLE ADD COLUMN`, same shape [MIGRATION_39_40]/[MIGRATION_40_41] already
 * used for this exact table - see [MIGRATION_47_48]'s own doc comment for why the column exists now
 * when [com.kevin.legion.backend.EventsReconcile] once deliberately declined to add it.
 *
 * **NOT RUN this session** - `connectedAndroidTest` uninstalls the app and would take Kevin's real
 * data (CLAUDE.md L11, `memory/MEMORY.md`). Compiled only, confirmed via
 * `compileDebugAndroidTestKotlin -Pnokey`. On-device evidence is deferred to on-device QA, same
 * posture as [CarDatabaseMigration46To47Test].
 */
@RunWith(AndroidJUnit4::class)
class CarDatabaseMigration47To48Test {
    private val dbName = "migration-test-47-48"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        CarDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun `migrate47To48_existingRowSurvivesWithStructuredMetaNull`() {
        helper.createDatabase(dbName, 47).apply {
            execSQL(
                "INSERT INTO events (id, serverId, title, startsAt, allDay, source, kind, done, exact, exactDowngraded, updatedAtMs, deleted, createdAt) " +
                    "VALUES (1, 'server-1', 'Dentist', 50000, 0, 'legion', 'reminder', 0, 0, 0, 100, 0, 12345)"
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(dbName, 48, true, MIGRATION_47_48)

        val cursor = db.query("SELECT title, structuredMeta FROM events WHERE id = 1")
        assertTrue(cursor.moveToFirst())
        assertEquals("Dentist", cursor.getString(0))
        assertTrue("a pre-migration row has no structured block - must read null, never a placeholder", cursor.isNull(1))
        cursor.close()
    }

    @Test
    fun `migrate47To48_canInsertARowCarryingAStructuredBlock`() {
        helper.createDatabase(dbName, 47).apply { close() }

        val db = helper.runMigrationsAndValidate(dbName, 48, true, MIGRATION_47_48)

        db.execSQL(
            "INSERT INTO events (id, serverId, title, startsAt, allDay, source, kind, done, exact, exactDowngraded, updatedAtMs, deleted, createdAt, structuredMeta) " +
                "VALUES (2, 'server-2', 'Midterm', 60000, 0, 'google', 'appointment', 0, 0, 0, 200, 0, 22345, '{\"course\":\"COSC4320\"}')"
        )
        val cursor = db.query("SELECT structuredMeta FROM events WHERE id = 2")
        assertTrue(cursor.moveToFirst())
        assertEquals("{\"course\":\"COSC4320\"}", cursor.getString(0))
        cursor.close()
    }

    @Test
    fun `migrate47To48_isOtherwiseAdditive_everyOtherColumnUnchanged`() {
        helper.createDatabase(dbName, 47).apply {
            execSQL(
                "INSERT INTO events (id, serverId, title, startsAt, allDay, source, kind, done, exact, exactDowngraded, updatedAtMs, deleted, createdAt) " +
                    "VALUES (1, 'server-1', 'Tire check', 70000, 0, 'legion', 'appointment', 0, 0, 0, 300, 0, 32345)"
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(dbName, 48, true, MIGRATION_47_48)

        val cursor = db.query("SELECT serverId, source, kind, deleted FROM events WHERE id = 1")
        assertTrue(cursor.moveToFirst())
        assertEquals("server-1", cursor.getString(0))
        assertEquals("legion", cursor.getString(1))
        assertEquals("appointment", cursor.getString(2))
        assertEquals(0, cursor.getInt(3))
        cursor.close()
    }
}

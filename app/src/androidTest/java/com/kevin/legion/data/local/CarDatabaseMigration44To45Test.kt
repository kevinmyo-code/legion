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
 * Instrumented migration test for v44 -> v45 - `events_replica`/`event_skips_replica` RENAMED to
 * `events`/`event_skips` (engine retirement step 4,
 * `.scratch/backend-erp/issues/15-engine-retirement-sequence.md`, "RULED 2026-08-27: notes gets ONE
 * local table"). A real `ALTER TABLE ... RENAME TO`, not a drop/create, so every existing row -
 * including a genuine configured install's live data - survives untouched; only the name and the
 * `serverId` index name change. See [MIGRATION_44_45]'s own doc comment for why the index needs an
 * explicit drop/recreate where the bare table rename does not touch it.
 *
 * **NOT RUN this session** - `connectedAndroidTest` uninstalls the app and would take Kevin's real
 * data (CLAUDE.md L11, `memory/MEMORY.md`). Compiled only, confirmed via
 * `compileDebugAndroidTestKotlin -Pnokey`. On-device evidence is deferred to on-device QA, same
 * posture as [CarDatabaseMigration43To44Test].
 */
@RunWith(AndroidJUnit4::class)
class CarDatabaseMigration44To45Test {
    private val dbName = "migration-test-44-45"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        CarDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun `migrate44To45_existingRowSurvivesTheRenameUnchanged`() {
        helper.createDatabase(dbName, 44).apply {
            execSQL(
                "INSERT INTO events_replica (id, serverId, title, startsAt, allDay, source, kind, done, exact, exactDowngraded, updatedAtMs, deleted, createdAt) " +
                    "VALUES (1, 'server-1', 'Dentist', 50000, 0, 'legion', 'reminder', 0, 0, 0, 100, 0, 12345)"
            )
            execSQL(
                "INSERT INTO event_skips_replica (eventServerId, skipDateEpochMs) VALUES ('server-1', 999)"
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(dbName, 45, true, MIGRATION_44_45)

        val cursor = db.query("SELECT id, title, kind, createdAt FROM events WHERE id = 1")
        assertTrue(cursor.moveToFirst())
        assertEquals(1L, cursor.getLong(0))
        assertEquals("Dentist", cursor.getString(1))
        assertEquals("reminder", cursor.getString(2))
        assertEquals(12345L, cursor.getLong(3))
        cursor.close()

        val skipCursor = db.query("SELECT eventServerId, skipDateEpochMs FROM event_skips WHERE eventServerId = 'server-1'")
        assertTrue(skipCursor.moveToFirst())
        assertEquals(999L, skipCursor.getLong(1))
        skipCursor.close()
    }

    @Test
    fun `migrate44To45_theUniqueServerIdIndexStillRejectsADuplicate`() {
        helper.createDatabase(dbName, 44).apply {
            execSQL(
                "INSERT INTO events_replica (id, serverId, title, startsAt, allDay, source, kind, done, exact, exactDowngraded, updatedAtMs, deleted, createdAt) " +
                    "VALUES (1, 'server-1', 'Dentist', 50000, 0, 'legion', 'reminder', 0, 0, 0, 100, 0, 12345)"
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(dbName, 45, true, MIGRATION_44_45)

        var threw = false
        try {
            db.execSQL(
                "INSERT INTO events (id, serverId, title, startsAt, allDay, source, kind, done, exact, exactDowngraded, updatedAtMs, deleted, createdAt) " +
                    "VALUES (2, 'server-1', 'Duplicate', 60000, 0, 'legion', 'reminder', 0, 0, 0, 200, 0, 22345)"
            )
        } catch (e: Exception) {
            threw = true
        }
        // The unique index survives the rename under its new Room-generated name
        // (index_events_serverId) - a second row claiming the same serverId must still fail, the
        // exact constraint EventDao.upsert's own id-preservation logic depends on.
        assertTrue(threw)
    }

    @Test
    fun `migrate44To45_oldTableNamesAreGoneAfterTheRename`() {
        helper.createDatabase(dbName, 44).apply { close() }

        val db = helper.runMigrationsAndValidate(dbName, 45, true, MIGRATION_44_45)

        val cursor = db.query(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name IN ('events_replica', 'event_skips_replica')"
        )
        // A real RENAME, not a copy - the old names must not linger alongside the new ones.
        assertEquals(0, cursor.count)
        cursor.close()
    }
}

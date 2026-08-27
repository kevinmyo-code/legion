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
 * Instrumented migration test for v39 -> v40 - `events_replica.startsAt` widens from `INTEGER
 * NOT NULL` to nullable `INTEGER` (backend-erp ticket 07, "RULED 2026-08-26: option 1" -
 * `.scratch/backend-erp/issues/07-undated-notes-have-no-server-shape.md`; see [EventReplica]'s and
 * [MIGRATION_39_40]'s own doc comments). `createSql` confirmed against the generated
 * `app/schemas/com.kevin.legion.data.local.CarDatabase/40.json` after a real
 * `compileDebugKotlin -Pnokey` run, byte for byte - not hand-derived, same discipline
 * [CarDatabaseMigration38To39Test] documents.
 *
 * **NOT RUN this session** - `connectedAndroidTest` uninstalls the app and would take Kevin's real
 * data (CLAUDE.md L11, `memory/MEMORY.md`; a prior run in this exact session already destroyed
 * real on-device data). Compiled only, confirmed via `compileDebugAndroidTestKotlin -Pnokey`.
 * On-device evidence is deferred to on-device QA.
 */
@RunWith(AndroidJUnit4::class)
class CarDatabaseMigration39To40Test {
    private val dbName = "migration-test-39-40"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        CarDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun `migrate39To40_existingDatedRow_carriesItsStartsAtUnchanged`() {
        helper.createDatabase(dbName, 39).apply {
            execSQL(
                "INSERT INTO events_replica (id, serverId, title, startsAt, allDay, source, done, exact, exactDowngraded, updatedAtMs, deleted) " +
                    "VALUES (1, 'server-1', 'Dentist', 50000, 0, 'legion', 0, 0, 0, 100, 0)"
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(dbName, 40, true, MIGRATION_39_40)

        val cursor = db.query("SELECT startsAt FROM events_replica WHERE id = 1")
        assertTrue(cursor.moveToFirst())
        assertEquals(50000L, cursor.getLong(0))
        cursor.close()
    }

    @Test
    fun `migrate39To40_canInsertAGenuinelyUndatedRow_whereV39WouldHaveRejectedIt`() {
        helper.createDatabase(dbName, 39).apply { close() }

        val db = helper.runMigrationsAndValidate(dbName, 40, true, MIGRATION_39_40)

        db.execSQL(
            "INSERT INTO events_replica (id, serverId, title, startsAt, allDay, source, done, exact, exactDowngraded, updatedAtMs, deleted) " +
                "VALUES (2, 'server-2', 'Call the vet', NULL, 1, 'legion', 0, 0, 0, 200, 0)"
        )

        val cursor = db.query("SELECT startsAt FROM events_replica WHERE id = 2")
        assertTrue(cursor.moveToFirst())
        assertTrue("a dateless item must be storable as NULL now, never a guessed date", cursor.isNull(0))
        cursor.close()
    }

    @Test
    fun `migrate39To40_uniqueServerIdIndexSurvivesTheRebuild`() {
        helper.createDatabase(dbName, 39).apply {
            execSQL(
                "INSERT INTO events_replica (id, serverId, title, startsAt, allDay, source, done, exact, exactDowngraded, updatedAtMs, deleted) " +
                    "VALUES (1, 'server-1', 'Dentist', 50000, 0, 'legion', 0, 0, 0, 100, 0)"
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(dbName, 40, true, MIGRATION_39_40)

        // A second row sharing the already-used serverId must be rejected - the unique index
        // must have survived the create/copy/drop/rename dance, not been silently dropped with
        // the old table.
        var threw = false
        try {
            db.execSQL(
                "INSERT INTO events_replica (id, serverId, title, startsAt, allDay, source, done, exact, exactDowngraded, updatedAtMs, deleted) " +
                    "VALUES (3, 'server-1', 'Duplicate', 60000, 0, 'legion', 0, 0, 0, 300, 0)"
            )
        } catch (e: Exception) {
            threw = true
        }
        assertTrue("the unique index on serverId must survive the table rebuild", threw)
    }

    @Test
    fun `migrate39To40_isOtherwiseAdditive_eventSkipsReplicaUntouched`() {
        helper.createDatabase(dbName, 39).apply {
            execSQL(
                "INSERT INTO events_replica (id, serverId, title, startsAt, allDay, source, done, exact, exactDowngraded, updatedAtMs, deleted) " +
                    "VALUES (1, 'server-1', 'Dentist', 50000, 0, 'legion', 0, 0, 0, 100, 0)"
            )
            execSQL(
                "INSERT INTO event_skips_replica (eventServerId, skipDateEpochMs) VALUES ('server-1', 20000)"
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(dbName, 40, true, MIGRATION_39_40)

        val cursor = db.query("SELECT skipDateEpochMs FROM event_skips_replica WHERE eventServerId = 'server-1'")
        assertTrue(cursor.moveToFirst())
        assertEquals(20000L, cursor.getLong(0))
        cursor.close()
    }
}

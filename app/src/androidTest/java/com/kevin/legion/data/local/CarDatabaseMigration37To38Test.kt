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
 * Instrumented migration test for v37 -> v38 - `events_replica`/`event_skips_replica`, the
 * backend-erp Phase 4 Notes+Dates merge's Room replica (see [EventReplica]'s and
 * [MIGRATION_37_38]'s own doc comments). `createSql`/index text confirmed against the generated
 * `app/schemas/com.kevin.legion.data.local.CarDatabase/38.json` after a real
 * `compileDebugKotlin -Pnokey` run, byte for byte - not hand-derived, same discipline
 * [CarDatabaseMigration36To37Test] documents.
 *
 * **NOT RUN this session** - `connectedAndroidTest` uninstalls the app and would take Kevin's real
 * data (CLAUDE.md L11, `memory/MEMORY.md`). Compiled only, confirmed via
 * `compileDebugAndroidTestKotlin -Pnokey`. On-device evidence is deferred to on-device QA.
 */
@RunWith(AndroidJUnit4::class)
class CarDatabaseMigration37To38Test {
    private val dbName = "migration-test-37-38"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        CarDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun `migrate37To38_isPurelyAdditive_noExistingTableTouched`() {
        helper.createDatabase(dbName, 37).apply {
            execSQL(
                "INSERT INTO records (recordTypeId, createdAt, updatedAt, searchText, provenance, payload, guid) " +
                    "VALUES (1, 1000, 1000, 'untouched', 'USER', '{}', 'some-guid')"
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(dbName, 38, true, MIGRATION_37_38)

        val cursor = db.query("SELECT searchText FROM records WHERE guid = 'some-guid'")
        assertTrue(cursor.moveToFirst())
        assertEquals("untouched", cursor.getString(0))
        cursor.close()
    }

    @Test
    fun `migrate37To38_createsEventsReplica_insertAndReadBack`() {
        helper.createDatabase(dbName, 37).apply { close() }

        val db = helper.runMigrationsAndValidate(dbName, 38, true, MIGRATION_37_38)

        db.execSQL(
            "INSERT INTO events_replica (serverId, title, startsAt, allDay, source, done, exact, exactDowngraded, updatedAtMs, deleted) " +
                "VALUES ('server-uuid-1', 'Take out trash', 5000, 0, 'legion', 0, 0, 0, 5000, 0)"
        )

        val cursor = db.query("SELECT title, startsAt, source FROM events_replica WHERE serverId = 'server-uuid-1'")
        assertTrue(cursor.moveToFirst())
        assertEquals("Take out trash", cursor.getString(0))
        assertEquals(5000L, cursor.getLong(1))
        assertEquals("legion", cursor.getString(2))
        cursor.close()
    }

    @Test
    fun `migrate37To38_eventsReplicaServerId_isUnique`() {
        helper.createDatabase(dbName, 37).apply { close() }

        val db = helper.runMigrationsAndValidate(dbName, 38, true, MIGRATION_37_38)

        db.execSQL(
            "INSERT INTO events_replica (serverId, title, startsAt, allDay, source, done, exact, exactDowngraded, updatedAtMs, deleted) " +
                "VALUES ('dup', 'first', 1000, 0, 'legion', 0, 0, 0, 1000, 0)"
        )
        var threw = false
        try {
            db.execSQL(
                "INSERT INTO events_replica (serverId, title, startsAt, allDay, source, done, exact, exactDowngraded, updatedAtMs, deleted) " +
                    "VALUES ('dup', 'second', 2000, 0, 'legion', 0, 0, 0, 2000, 0)"
            )
        } catch (e: Exception) {
            threw = true
        }
        assertTrue("serverId must be UNIQUE on events_replica - a duplicate insert must fail", threw)
    }

    @Test
    fun `migrate37To38_createsEventSkipsReplica_uniqueOnEventAndDate`() {
        helper.createDatabase(dbName, 37).apply { close() }

        val db = helper.runMigrationsAndValidate(dbName, 38, true, MIGRATION_37_38)

        db.execSQL("INSERT INTO event_skips_replica (eventServerId, skipDateEpochMs) VALUES ('server-uuid-1', 1000)")
        var threw = false
        try {
            db.execSQL("INSERT INTO event_skips_replica (eventServerId, skipDateEpochMs) VALUES ('server-uuid-1', 1000)")
        } catch (e: Exception) {
            threw = true
        }
        assertTrue("(eventServerId, skipDateEpochMs) must be the primary key - a duplicate insert must fail", threw)
    }
}

package com.kevin.legion.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented migration test for v48 -> v49 - `events` gains `guid` (coordinator follow-up on
 * backend-erp ticket 17, 2026-08-28: [Event.serverId] cannot serve as the identity
 * [com.kevin.legion.backend.EventsReconcile]'s Dates branch needs, because
 * [com.kevin.legion.backend.EventsReconcile]'s own wholesale refill overwrites it with the server's
 * real uuid). Same create/backfill recipe [MIGRATION_36_37] already used for `records.guid`, minus
 * the unique index that column has - see [MIGRATION_48_49]'s own doc comment for why a unique
 * index on THIS column broke the real build (every `kind = reminder` row shares the blank default).
 *
 * **NOT RUN this session** - `connectedAndroidTest` uninstalls the app and would take Kevin's real
 * data (CLAUDE.md L11, `memory/MEMORY.md`). Compiled only, confirmed via
 * `compileDebugAndroidTestKotlin -Pnokey`. On-device evidence is deferred to on-device QA, same
 * posture as [CarDatabaseMigration47To48Test].
 */
@RunWith(AndroidJUnit4::class)
class CarDatabaseMigration48To49Test {
    private val dbName = "migration-test-48-49"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        CarDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun `migrate48To49_existingRowsEachGetTheirOwnRealGuid`() {
        helper.createDatabase(dbName, 48).apply {
            execSQL(
                "INSERT INTO events (id, serverId, title, startsAt, allDay, source, kind, done, exact, exactDowngraded, updatedAtMs, deleted, createdAt) " +
                    "VALUES (1, 'server-1', 'Dentist', 50000, 0, 'legion', 'reminder', 0, 0, 0, 100, 0, 12345)"
            )
            execSQL(
                "INSERT INTO events (id, serverId, title, startsAt, allDay, source, kind, done, exact, exactDowngraded, updatedAtMs, deleted, createdAt) " +
                    "VALUES (2, 'server-2', 'Midterm', 60000, 0, 'google', 'appointment', 0, 0, 0, 200, 0, 22345)"
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(dbName, 49, true, MIGRATION_48_49)

        val cursor = db.query("SELECT id, guid FROM events ORDER BY id ASC")
        assertTrue(cursor.moveToFirst())
        val guid1 = cursor.getString(1)
        assertTrue("a pre-migration row must be backfilled a real, non-blank guid", guid1.isNotBlank())
        assertTrue(cursor.moveToNext())
        val guid2 = cursor.getString(1)
        assertTrue("the second row must also be backfilled a real, non-blank guid", guid2.isNotBlank())
        assertNotEquals("two different rows must never collide on the same backfilled guid", guid1, guid2)
        cursor.close()
    }

    /**
     * **DELIBERATELY proves there is NO unique constraint, the opposite of
     * [CarDatabaseMigration36To37Test]'s equivalent case for `records.guid`.** A first version of
     * this migration DID add a unique index and it broke the real build immediately: every
     * `kind = reminder` row (Notes) leaves [Event.guid] at its Kotlin default (blank) by design, so
     * a second Notes item ever created on an unconfigured install would violate the constraint. See
     * [MIGRATION_48_49]'s own doc comment for the full account.
     */
    @Test
    fun `migrate48To49_hasNoUniqueConstraintOnGuid_twoBlankRowsCoexist`() {
        helper.createDatabase(dbName, 48).apply { close() }

        val db = helper.runMigrationsAndValidate(dbName, 49, true, MIGRATION_48_49)

        db.execSQL(
            "INSERT INTO events (id, serverId, title, startsAt, allDay, source, kind, done, exact, exactDowngraded, updatedAtMs, deleted, createdAt, guid) " +
                "VALUES (1, 'server-1', 'First reminder', NULL, 1, 'legion', 'reminder', 0, 0, 0, 100, 0, 12345, '')"
        )
        db.execSQL(
            "INSERT INTO events (id, serverId, title, startsAt, allDay, source, kind, done, exact, exactDowngraded, updatedAtMs, deleted, createdAt, guid) " +
                "VALUES (2, 'server-2', 'Second reminder', NULL, 1, 'legion', 'reminder', 0, 0, 0, 200, 0, 22345, '')"
        )
        val cursor = db.query("SELECT COUNT(*) FROM events WHERE guid = ''")
        assertTrue(cursor.moveToFirst())
        assertEquals("two rows sharing the blank default guid must both survive, never a constraint failure", 2, cursor.getInt(0))
        cursor.close()
    }

    @Test
    fun `migrate48To49_isOtherwiseAdditive_everyOtherColumnUnchanged`() {
        helper.createDatabase(dbName, 48).apply {
            execSQL(
                "INSERT INTO events (id, serverId, title, startsAt, allDay, source, kind, done, exact, exactDowngraded, updatedAtMs, deleted, createdAt, structuredMeta) " +
                    "VALUES (1, 'server-1', 'Team offsite', 70000, 1, 'google', 'appointment', 0, 0, 0, 300, 0, 32345, '{\"course\":\"COSC4320\"}')"
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(dbName, 49, true, MIGRATION_48_49)

        val cursor = db.query("SELECT serverId, kind, structuredMeta FROM events WHERE id = 1")
        assertTrue(cursor.moveToFirst())
        assertEquals("server-1", cursor.getString(0))
        assertEquals("appointment", cursor.getString(1))
        assertEquals("{\"course\":\"COSC4320\"}", cursor.getString(2))
        cursor.close()
    }
}

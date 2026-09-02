package com.kevin.legion.data.local

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * [MIGRATION_58_59] - the events-outbox ticket's schema half. **Exercised against a HAND-BUILT
 * v58-shaped database, unlike [Migration57To58Test]'s own approach of running against
 * [CarDatabase.getDatabase]'s live (current-version) table** - that trick only works for a
 * data-only migration where the "current" schema and the migration's OWN starting shape are
 * identical; this migration rebuilds `events` (`serverId` `NOT NULL` -> nullable), so a database
 * already opened at the CURRENT (v59) version would already have the nullable column before
 * [MIGRATION_58_59.migrate] ever ran, proving nothing about the rebuild itself. This codebase has
 * no `androidx.room.testing.MigrationTestHelper` wired into unit tests (only
 * `androidTestImplementation`, per `app/build.gradle.kts` - see [Migration57To58Test]'s own doc
 * comment), so the v58 shape is built by hand here with the real `SupportSQLiteOpenHelper`
 * machinery Room itself sits on, copied verbatim from `58.json`'s own `createSql`.
 */
@RunWith(RobolectricTestRunner::class)
class Migration58To59Test {
    private val context = RuntimeEnvironment.getApplication()

    private fun openV58ShapedDatabase(name: String): SupportSQLiteDatabase {
        val configuration = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(name)
            .callback(object : SupportSQLiteOpenHelper.Callback(58) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    // Copied verbatim from app/schemas/.../58.json's own `events` createSql.
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `events` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`serverId` TEXT NOT NULL, `title` TEXT NOT NULL, `startsAt` INTEGER, " +
                            "`endsAt` INTEGER, `allDay` INTEGER NOT NULL, `location` TEXT, `notes` TEXT, " +
                            "`source` TEXT NOT NULL, `googleEventId` TEXT, `done` INTEGER NOT NULL, " +
                            "`doneAt` INTEGER, `sortOrder` INTEGER, `triggerPlaceLabel` TEXT, " +
                            "`repeatKind` TEXT, `repeatEvery` INTEGER, `repeatDaysOfWeek` TEXT, " +
                            "`repeatDay` INTEGER, `repeatMonth` INTEGER, `repeatEndKind` TEXT, " +
                            "`repeatEndDate` INTEGER, `repeatEndCount` INTEGER, `exact` INTEGER NOT NULL, " +
                            "`exactDowngraded` INTEGER NOT NULL, `missedAt` INTEGER, " +
                            "`missedDismissedAt` INTEGER, `loggedAt` INTEGER, `updatedAtMs` INTEGER NOT NULL, " +
                            "`deleted` INTEGER NOT NULL DEFAULT 0, `createdAt` INTEGER NOT NULL DEFAULT 0, " +
                            "`kind` TEXT NOT NULL DEFAULT 'reminder', `structuredMeta` TEXT, " +
                            "`guid` TEXT NOT NULL DEFAULT '')",
                    )
                    db.execSQL(
                        "CREATE UNIQUE INDEX IF NOT EXISTS `index_events_serverId` ON `events` (`serverId`)",
                    )
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            })
            .build()
        return FrameworkSQLiteOpenHelperFactory().create(configuration).writableDatabase
    }

    @Test
    fun `rebuilds events with a nullable serverId, preserving every existing row byte for byte`() {
        val db = openV58ShapedDatabase("migration_58_59_events_test.db")
        db.execSQL(
            "INSERT INTO events (id, serverId, title, allDay, source, done, exact, " +
                "exactDowngraded, updatedAtMs, kind, guid) VALUES " +
                "(1, 'server-uuid-1', 'Dentist', 0, 'legion', 0, 0, 0, 1000, 'event', 'guid-1')",
        )

        MIGRATION_58_59.migrate(db)

        db.query("SELECT serverId, title, guid FROM events WHERE id = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("server-uuid-1", cursor.getString(0))
            assertEquals("Dentist", cursor.getString(1))
            assertEquals("guid-1", cursor.getString(2))
        }

        // The whole point of the migration: the column now genuinely accepts NULL.
        db.execSQL(
            "INSERT INTO events (id, serverId, title, allDay, source, done, exact, " +
                "exactDowngraded, updatedAtMs, kind, guid) VALUES " +
                "(2, NULL, 'New appt', 0, 'legion', 0, 0, 0, 2000, 'event', 'guid-2')",
        )
        db.query("SELECT serverId FROM events WHERE id = 2").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertTrue(cursor.isNull(0))
        }

        // Multiple NULLs must coexist under the unique index - SQLite never treats NULL = NULL.
        db.execSQL(
            "INSERT INTO events (id, serverId, title, allDay, source, done, exact, " +
                "exactDowngraded, updatedAtMs, kind, guid) VALUES " +
                "(3, NULL, 'Another new appt', 0, 'legion', 0, 0, 0, 3000, 'event', 'guid-3')",
        )
        db.query("SELECT COUNT(*) FROM events").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(3, cursor.getInt(0))
        }
    }

    @Test
    fun `creates sync_outbox, ready for inserts`() {
        val db = openV58ShapedDatabase("migration_58_59_outbox_test.db")

        MIGRATION_58_59.migrate(db)

        db.execSQL(
            "INSERT INTO sync_outbox (targetTable, operation, localId, payload, createdAt) " +
                "VALUES ('events', 'upsert', 1, '{}', 1000)",
        )
        db.query("SELECT targetTable, operation, attempts, lastError FROM sync_outbox").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("events", cursor.getString(0))
            assertEquals("upsert", cursor.getString(1))
            assertEquals(0, cursor.getInt(2))
            assertTrue(cursor.isNull(3))
        }
    }
}

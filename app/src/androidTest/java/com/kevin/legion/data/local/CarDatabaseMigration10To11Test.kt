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
 * Instrumented migration test for v10 -> v11 (four additive columns on `list_items` behind local
 * alarms and fired-reminder state - `.scratch/notes-lists-calendar/issues/03-*`/`12-*`). Same shape
 * as [CarDatabaseMigration9To10Test] - see its doc comment for why this needs `androidTest`.
 *
 * **NOT RUN this session** - `connectedAndroidTest` wipes app data and Kevin's real database is on
 * the connected device (CLAUDE.md L11 / this ticket's verification gates). Compiled only.
 */
@RunWith(AndroidJUnit4::class)
class CarDatabaseMigration10To11Test {
    private val dbName = "migration-test-10-11"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        CarDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrate10To11_addsAlarmAndMissedColumnsWithSaneDefaults() {
        // Create a v10 database with one pre-existing row, representative of everything already
        // migrated by v9->v10 (a plain checklist item, no trigger) - confirms the ADD COLUMNs
        // don't disturb it and every new column reads back as the documented "not set yet" value.
        helper.createDatabase(dbName, 10).apply {
            execSQL(
                "INSERT INTO item_lists (id, name, tickable, sortOrder, lastUsedAt, archived, " +
                    "createdAt, updatedAt, syncId, deleted) VALUES " +
                    "(1, 'Camping', 1, 0, 1733356800000, 0, 1733356800000, 1733356800000, 'list-sync-1', 0)"
            )
            execSQL(
                "INSERT INTO list_items (id, listId, text, done, doneAt, sortOrder, createdAt, " +
                    "updatedAt, syncId, deleted, startsAt, endsAt, allDay, triggerPlaceLabel, " +
                    "repeatKind, repeatEvery, repeatDaysOfWeek, repeatDay, repeatMonth, repeatEndKind, " +
                    "repeatEndDate, repeatEndCount) VALUES " +
                    "(1, 1, 'Tent', 0, NULL, 0, 1733356800000, 1733356800000, 'item-sync-1', 0, " +
                    "NULL, NULL, 1, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL)"
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(dbName, 11, true, MIGRATION_10_11)

        // The pre-existing row survives, and every new column defaults exactly as documented:
        // exact/exactDowngraded false (0), missedAt/missedDismissedAt NULL.
        val row = db.query(
            "SELECT exact, exactDowngraded, missedAt, missedDismissedAt FROM list_items WHERE id = 1"
        )
        assertTrue(row.moveToFirst())
        assertEquals(0, row.getInt(0))
        assertEquals(0, row.getInt(1))
        assertTrue("expected missedAt to default to NULL", row.isNull(2))
        assertTrue("expected missedDismissedAt to default to NULL", row.isNull(3))
        row.close()

        // A brand-new row can write and read back every new column through the migrated schema.
        db.execSQL(
            "INSERT INTO list_items (id, listId, text, done, doneAt, sortOrder, createdAt, " +
                "updatedAt, syncId, deleted, startsAt, endsAt, allDay, triggerPlaceLabel, " +
                "repeatKind, repeatEvery, repeatDaysOfWeek, repeatDay, repeatMonth, repeatEndKind, " +
                "repeatEndDate, repeatEndCount, exact, exactDowngraded, missedAt, missedDismissedAt) VALUES " +
                "(2, 1, 'Wake up call', 0, NULL, 1, 1733356800000, 1733356800000, 'item-sync-2', 0, " +
                "1733356800000, NULL, 0, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, " +
                "1, 1, 1733400000000, NULL)"
        )
        val fresh = db.query(
            "SELECT exact, exactDowngraded, missedAt, missedDismissedAt FROM list_items WHERE syncId = 'item-sync-2'"
        )
        assertTrue(fresh.moveToFirst())
        assertEquals(1, fresh.getInt(0))
        assertEquals(1, fresh.getInt(1))
        assertEquals(1733400000000L, fresh.getLong(2))
        assertTrue(fresh.isNull(3))
        fresh.close()
    }
}

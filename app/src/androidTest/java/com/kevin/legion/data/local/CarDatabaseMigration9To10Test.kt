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
 * Instrumented migration test for v9 -> v10 (`item_lists` + `list_items` + `list_item_skips`, the
 * notes/lists/calendar domain phase 1 - `.scratch/notes-lists-calendar/issues/01-*`/`04-*`). Same
 * shape as [CarDatabaseMigration8To9Test] - see its doc comment for why this needs `androidTest`.
 *
 * **NOT RUN this session** - `connectedAndroidTest` wipes app data and Kevin's real database is on
 * the connected device (CLAUDE.md L11 / this ticket's verification gates). Compiled only.
 */
@RunWith(AndroidJUnit4::class)
class CarDatabaseMigration9To10Test {
    private val dbName = "migration-test-9-10"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        CarDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrate9To10_createsNoteTablesAndCopiesCarTasksAndPlaceReminders() {
        // Create the v9 database with one representative row in EACH source table the migration
        // copies from, to confirm the copy preserves the sync-critical columns verbatim and
        // leaves the source tables themselves untouched (ticket 01: do NOT drop them here).
        helper.createDatabase(dbName, 9).apply {
            execSQL(
                "INSERT INTO car_tasks (id, text, category, done, createdAt, doneAt, updatedAt, syncId, deleted) VALUES " +
                    "(1, 'Replace front bushings', 'maintenance', 0, 1733356800000, NULL, 1733356800000, 'car-sync-1', 0)"
            )
            execSQL(
                "INSERT INTO car_tasks (id, text, category, done, createdAt, doneAt, updatedAt, syncId, deleted) VALUES " +
                    "(2, 'New coilovers', 'wishlist', 1, 1733356800000, 1733400000000, 1733400000000, 'car-sync-2', 0)"
            )
            execSQL(
                "INSERT INTO place_reminders (id, placeLabel, text, createdAt, done, updatedAt, syncId) VALUES " +
                    "(1, 'gym', 'grab my gym bag', 1733356800000, 0, 1733356800000, 'reminder-sync-1')"
            )
            close()
        }

        // Run the real migration under test.
        val db = helper.runMigrationsAndValidate(dbName, 10, true, MIGRATION_9_10)

        // Both source tables survive, untouched - ticket 01 is explicit this is not optional.
        val carTasksStillThere = db.query("SELECT COUNT(*) FROM car_tasks")
        assertTrue(carTasksStillThere.moveToFirst())
        assertEquals(2, carTasksStillThere.getInt(0))
        carTasksStillThere.close()

        val placeRemindersStillThere = db.query("SELECT COUNT(*) FROM place_reminders")
        assertTrue(placeRemindersStillThere.moveToFirst())
        assertEquals(1, placeRemindersStillThere.getInt(0))
        placeRemindersStillThere.close()

        // Exactly two lists were created: "Car" and "Reminders".
        val lists = db.query("SELECT name FROM item_lists ORDER BY name ASC")
        val names = mutableListOf<String>()
        while (lists.moveToNext()) names.add(lists.getString(0))
        lists.close()
        assertEquals(listOf("Car", "Reminders"), names)

        // The open car_tasks row landed in "Car" with every sync-critical column preserved
        // verbatim: syncId, deleted, updatedAt, createdAt, done, doneAt.
        val openTask = db.query(
            "SELECT syncId, deleted, updatedAt, createdAt, done, doneAt FROM list_items " +
                "WHERE listId = (SELECT id FROM item_lists WHERE name = 'Car') AND text = 'Replace front bushings'"
        )
        assertTrue(openTask.moveToFirst())
        assertEquals("car-sync-1", openTask.getString(0))
        assertEquals(0, openTask.getInt(1)) // deleted
        assertEquals(1733356800000L, openTask.getLong(2)) // updatedAt
        assertEquals(1733356800000L, openTask.getLong(3)) // createdAt
        assertEquals(0, openTask.getInt(4)) // done
        assertTrue("expected doneAt to stay NULL for an open task", openTask.isNull(5))
        openTask.close()

        // The done car_tasks row's done/doneAt/syncId carried over too - not just the open one.
        val doneTask = db.query(
            "SELECT syncId, done, doneAt FROM list_items " +
                "WHERE listId = (SELECT id FROM item_lists WHERE name = 'Car') AND text = 'New coilovers'"
        )
        assertTrue(doneTask.moveToFirst())
        assertEquals("car-sync-2", doneTask.getString(0))
        assertEquals(1, doneTask.getInt(1))
        assertEquals(1733400000000L, doneTask.getLong(2))
        doneTask.close()

        // The place_reminders row landed in "Reminders", carrying placeLabel forward as
        // triggerPlaceLabel, with deleted/doneAt defaulting to the same values a brand-new row
        // would get (place_reminders never had those columns to carry).
        val reminder = db.query(
            "SELECT syncId, deleted, doneAt, triggerPlaceLabel FROM list_items " +
                "WHERE listId = (SELECT id FROM item_lists WHERE name = 'Reminders') AND text = 'grab my gym bag'"
        )
        assertTrue(reminder.moveToFirst())
        assertEquals("reminder-sync-1", reminder.getString(0))
        assertEquals(0, reminder.getInt(1))
        assertTrue(reminder.isNull(2))
        assertEquals("gym", reminder.getString(3))
        reminder.close()

        // list_item_skips exists, is queryable, and starts empty - nothing pre-migration wrote to it.
        val skips = db.query("SELECT COUNT(*) FROM list_item_skips")
        assertTrue(skips.moveToFirst())
        assertEquals(0, skips.getInt(0))
        skips.close()

        // A brand-new list and item can actually be written and read back through the new schema,
        // including the recurrence columns.
        db.execSQL(
            "INSERT INTO item_lists (name, tickable, sortOrder, lastUsedAt, archived, createdAt, updatedAt, syncId, deleted) " +
                "VALUES ('Camping', 1, 0, 1733356800000, 0, 1733356800000, 1733356800000, 'list-sync-3', 0)"
        )
        db.execSQL(
            "INSERT INTO list_items (listId, text, done, doneAt, sortOrder, createdAt, updatedAt, syncId, deleted, " +
                "startsAt, endsAt, allDay, triggerPlaceLabel, repeatKind, repeatEvery, repeatDaysOfWeek, repeatDay, " +
                "repeatMonth, repeatEndKind, repeatEndDate, repeatEndCount) VALUES " +
                "((SELECT id FROM item_lists WHERE name = 'Camping'), 'Take out the trash', 0, NULL, 0, " +
                "1733356800000, 1733356800000, 'item-sync-4', 0, 1733356800000, NULL, 1, NULL, " +
                "'WEEKLY', 1, 'MONDAY', NULL, NULL, 'NEVER', NULL, NULL)"
        )
        val fresh = db.query("SELECT text, repeatKind, repeatDaysOfWeek FROM list_items WHERE syncId = 'item-sync-4'")
        assertTrue(fresh.moveToFirst())
        assertEquals("Take out the trash", fresh.getString(0))
        assertEquals("WEEKLY", fresh.getString(1))
        assertEquals("MONDAY", fresh.getString(2))
        fresh.close()

        db.execSQL(
            "INSERT INTO list_item_skips (itemId, skippedDate, createdAt, updatedAt, syncId, deleted) " +
                "SELECT id, 1733960000000, 1733356800000, 1733356800000, 'skip-sync-1', 0 FROM list_items WHERE syncId = 'item-sync-4'"
        )
        val skipRow = db.query("SELECT skippedDate FROM list_item_skips WHERE syncId = 'skip-sync-1'")
        assertTrue(skipRow.moveToFirst())
        assertEquals(1733960000000L, skipRow.getLong(0))
        skipRow.close()
    }
}

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
 * Instrumented migration test for v13 -> v14 - the grocery aspect's two tables
 * ([MIGRATION_13_14]). Purely additive, so the important assertion is the negative one: the notes
 * data that v12->v13 just finished consolidating is still exactly where it was left.
 *
 * **NOT RUN this session** - `connectedAndroidTest` wipes app data and Kevin's real database is on
 * the connected device (CLAUDE.md L11). Compiled only.
 */
@RunWith(AndroidJUnit4::class)
class CarDatabaseMigration13To14Test {
    private val dbName = "migration-test-13-14"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        CarDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrate13To14_createsBothGroceryTablesEmpty() {
        helper.createDatabase(dbName, 13).apply { close() }

        val db = helper.runMigrationsAndValidate(dbName, 14, true, MIGRATION_13_14)

        // A brand-new install has no trip in progress and no staples learned - "trip in progress"
        // is defined as grocery_items being non-empty, so a stray seeded row would read as a
        // shopping trip the driver never started.
        val items = db.query("SELECT COUNT(*) FROM grocery_items")
        assertTrue(items.moveToFirst())
        assertEquals(0, items.getInt(0))
        items.close()

        val staples = db.query("SELECT COUNT(*) FROM grocery_staples")
        assertTrue(staples.moveToFirst())
        assertEquals(0, staples.getInt(0))
        staples.close()
    }

    @Test
    fun migrate13To14_acceptsARowInEachNewTable() {
        helper.createDatabase(dbName, 13).apply { close() }
        val db = helper.runMigrationsAndValidate(dbName, 14, true, MIGRATION_13_14)

        // Proves the hand-written CREATE TABLE really matches what the DAOs write, including the
        // defaulted columns the migration declares.
        db.execSQL(
            "INSERT INTO grocery_items (text, done, sortOrder, createdAt, updatedAt, syncId) " +
                "VALUES ('Milk', 0, 0, 1, 1, 'g-1')"
        )
        db.execSQL(
            "INSERT INTO grocery_staples (name, displayName, timesBought, lastBoughtAt, syncId) " +
                "VALUES ('milk', 'Milk', 4, 99, 's-1')"
        )

        val item = db.query("SELECT text, done, doneAt FROM grocery_items WHERE syncId = 'g-1'")
        assertTrue(item.moveToFirst())
        assertEquals("Milk", item.getString(0))
        assertEquals(0, item.getInt(1))
        assertTrue("doneAt is nullable and starts unset", item.isNull(2))
        item.close()

        val staple = db.query("SELECT displayName, timesBought FROM grocery_staples WHERE name = 'milk'")
        assertTrue(staple.moveToFirst())
        assertEquals("Milk", staple.getString(0))
        assertEquals(4, staple.getInt(1))
        staple.close()
    }

    @Test
    fun migrate13To14_leavesTheConsolidatedNotesDataAlone() {
        // The regression that matters: v12->v13 folded every list into one, and this migration must
        // not disturb a single row of that. Additive means additive.
        helper.createDatabase(dbName, 13).apply {
            execSQL(
                "INSERT INTO item_lists (name, tickable, sortOrder, lastUsedAt, archived, createdAt, updatedAt, syncId, deleted) " +
                    "VALUES ('List', 1, 0, 1, 0, 1, 1, 'sync-list', 0)"
            )
            execSQL(
                "INSERT INTO list_items (listId, text, done, sortOrder, createdAt, updatedAt, syncId, deleted, " +
                    "startsAt, allDay, exact, exactDowngraded) " +
                    "VALUES (1, 'F150 recall appointment', 0, 0, 1, 1, 'item-recall', 0, 555000, 1, 0, 0)"
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(dbName, 14, true, MIGRATION_13_14)

        val cursor = db.query("SELECT listId, text, startsAt FROM list_items WHERE syncId = 'item-recall'")
        assertTrue(cursor.moveToFirst())
        assertEquals(1L, cursor.getLong(0))
        assertEquals("F150 recall appointment", cursor.getString(1))
        assertEquals(555_000L, cursor.getLong(2))
        cursor.close()

        val lists = db.query("SELECT COUNT(*) FROM item_lists WHERE deleted = 0")
        assertTrue(lists.moveToFirst())
        assertEquals(1, lists.getInt(0))
        lists.close()
    }
}

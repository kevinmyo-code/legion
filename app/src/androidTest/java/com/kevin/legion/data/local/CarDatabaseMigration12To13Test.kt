package com.kevin.legion.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented migration test for v12 -> v13 - dissolving every named list into one (Kevin,
 * 2026-08-11: "dissolve the car list. merge everything into one list model"). See
 * [MIGRATION_12_13]'s doc comment for the reasoning.
 *
 * Like [CarDatabaseMigration11To12Test] this migration is DATA-ONLY, so there is no schema diff to
 * assert - `runMigrationsAndValidate` checking the result against `13.json` is itself the proof that
 * no structure changed, and `13.json`'s identity hash is `12.json`'s.
 *
 * What these tests are really guarding is the claim that made the migration acceptable at all:
 * **nothing is lost.** Every item keeps its text, its due date, its repeat rule, its place trigger
 * and its `syncId`; only `listId` moves. A migration that quietly dropped the items on the lists it
 * dissolved would be the exact failure this repo keeps shipping - a silent one that reads as success.
 *
 * **NOT RUN this session** - `connectedAndroidTest` wipes app data and Kevin's real database is on
 * the connected device (CLAUDE.md L11). Compiled only.
 */
@RunWith(AndroidJUnit4::class)
class CarDatabaseMigration12To13Test {
    private val dbName = "migration-test-12-13"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        CarDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    private fun insertList(
        db: androidx.sqlite.db.SupportSQLiteDatabase,
        name: String,
        createdAt: Long,
        archived: Int = 0,
        tickable: Int = 1,
    ) = db.execSQL(
        "INSERT INTO item_lists (name, tickable, sortOrder, lastUsedAt, archived, createdAt, updatedAt, syncId, deleted) " +
            "VALUES ('$name', $tickable, 0, $createdAt, $archived, $createdAt, $createdAt, 'sync-$name', 0)"
    )

    @Test
    fun migrate12To13_movesEveryItemOntoOneListWithoutLosingAnything() {
        // Kevin's real shape: a "Car" list (MIGRATION_9_10 made it), a "Reminders" list (same), and
        // a hand-made one - with the F150 recall appointment stranded on "Car", which is the bug
        // that prompted this whole change.
        helper.createDatabase(dbName, 12).apply {
            insertList(this, "Car", 1_000L)
            insertList(this, "Reminders", 2_000L)
            insertList(this, "Camping", 3_000L)
            execSQL(
                "INSERT INTO list_items (listId, text, done, sortOrder, createdAt, updatedAt, syncId, deleted, " +
                    "startsAt, allDay, repeatKind, exact, exactDowngraded) " +
                    "VALUES (1, 'F150 recall appointment', 0, 0, 1, 1, 'item-recall', 0, 555000, 1, NULL, 0, 0)"
            )
            execSQL(
                "INSERT INTO list_items (listId, text, done, sortOrder, createdAt, updatedAt, syncId, deleted, " +
                    "allDay, triggerPlaceLabel, exact, exactDowngraded) " +
                    "VALUES (2, 'Buy rope', 0, 0, 2, 2, 'item-rope', 0, 1, 'Camping Store', 0, 0)"
            )
            execSQL(
                "INSERT INTO list_items (listId, text, done, sortOrder, createdAt, updatedAt, syncId, deleted, " +
                    "allDay, repeatKind, repeatEvery, exact, exactDowngraded) " +
                    "VALUES (3, 'Water the plants', 0, 0, 3, 3, 'item-plants', 0, 1, 'DAILY', 1, 0, 0)"
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(dbName, 13, true, MIGRATION_12_13)

        // Exactly one live list, and it is the oldest ("Car", id 1), renamed - not a brand-new row,
        // so its syncId survives for any device that already knew it.
        val lists = db.query("SELECT id, name, archived, tickable, syncId FROM item_lists WHERE deleted = 0")
        assertTrue(lists.moveToFirst())
        val survivorId = lists.getLong(0)
        assertEquals(1L, survivorId)
        assertEquals("List", lists.getString(1))
        assertEquals(0, lists.getInt(2))
        assertEquals(1, lists.getInt(3))
        assertEquals("sync-Car", lists.getString(4))
        assertTrue("expected exactly one surviving list", !lists.moveToNext())
        lists.close()

        // The dissolved lists are TOMBSTONED, not gone - a hard delete would let the next sync
        // resurrect them from a peer that never saw them disappear.
        val tombstones = db.query("SELECT COUNT(*) FROM item_lists WHERE deleted = 1")
        assertTrue(tombstones.moveToFirst())
        assertEquals(2, tombstones.getInt(0))
        tombstones.close()

        // All three items are on the survivor, and none was dropped.
        val items = db.query("SELECT COUNT(*) FROM list_items WHERE listId = $survivorId")
        assertTrue(items.moveToFirst())
        assertEquals(3, items.getInt(0))
        items.close()

        // The item that started this keeps its due date, to the millisecond.
        val recall = db.query("SELECT startsAt, allDay, syncId FROM list_items WHERE text = 'F150 recall appointment'")
        assertTrue(recall.moveToFirst())
        assertEquals(555_000L, recall.getLong(0))
        assertEquals(1, recall.getInt(1))
        assertEquals("item-recall", recall.getString(2))
        recall.close()

        // A place trigger and a repeat rule both survive the move untouched.
        val rope = db.query("SELECT triggerPlaceLabel FROM list_items WHERE text = 'Buy rope'")
        assertTrue(rope.moveToFirst())
        assertEquals("Camping Store", rope.getString(0))
        rope.close()

        val plants = db.query("SELECT repeatKind, repeatEvery FROM list_items WHERE text = 'Water the plants'")
        assertTrue(plants.moveToFirst())
        assertEquals("DAILY", plants.getString(0))
        assertEquals(1, plants.getInt(1))
        plants.close()
    }

    @Test
    fun migrate12To13_adoptsAnExistingListNamedListRatherThanRenamingAnOlderOne() {
        // Rule 1 of the target-selection order: a list already called "List" wins even when an older
        // list exists, so a re-run (or an install where the driver already made one by hand) does
        // not shuffle which row is the survivor.
        helper.createDatabase(dbName, 12).apply {
            insertList(this, "Car", 1_000L)
            insertList(this, "List", 9_000L)
            close()
        }

        val db = helper.runMigrationsAndValidate(dbName, 13, true, MIGRATION_12_13)

        val cursor = db.query("SELECT id, syncId FROM item_lists WHERE deleted = 0")
        assertTrue(cursor.moveToFirst())
        assertEquals(2L, cursor.getLong(0))
        assertEquals("sync-List", cursor.getString(1))
        assertTrue("expected exactly one surviving list", !cursor.moveToNext())
        cursor.close()
    }

    @Test
    fun migrate12To13_unarchivesAndUnNotesTheSurvivor() {
        // The one list cannot be archived (nothing shows archived lists any more) and cannot be
        // un-tickable (the checklist-vs-note split is gone) - either would leave the driver with a
        // list they cannot use and no affordance to fix it.
        helper.createDatabase(dbName, 12).apply {
            insertList(this, "Trip ideas", 1_000L, archived = 1, tickable = 0)
            close()
        }

        val db = helper.runMigrationsAndValidate(dbName, 13, true, MIGRATION_12_13)

        val cursor = db.query("SELECT name, archived, tickable FROM item_lists WHERE deleted = 0")
        assertTrue(cursor.moveToFirst())
        assertEquals("List", cursor.getString(0))
        assertEquals(0, cursor.getInt(1))
        assertEquals(1, cursor.getInt(2))
        cursor.close()
    }

    @Test
    fun migrate12To13_createsTheOneListWhenTheInstallHasNoneAtAll() {
        // Rule 3: a v12 install that never made a list still comes out of this with exactly one, so
        // the app never faces "no list exists" as a state it has to handle at runtime.
        helper.createDatabase(dbName, 12).apply { close() }

        val db = helper.runMigrationsAndValidate(dbName, 13, true, MIGRATION_12_13)

        val cursor = db.query("SELECT name, syncId FROM item_lists WHERE deleted = 0")
        assertTrue(cursor.moveToFirst())
        assertEquals("List", cursor.getString(0))
        assertNotNull(cursor.getString(1))
        assertTrue("a generated syncId must not be blank", cursor.getString(1).isNotBlank())
        assertTrue("expected exactly one list", !cursor.moveToNext())
        cursor.close()
    }

    @Test
    fun migrate12To13_carriesSoftDeletedItemsAcrossToo() {
        // A tombstoned item left behind on a tombstoned list is how a deleted row comes back: the
        // peer still has it, and nothing local outranks it any more. It moves with everything else.
        helper.createDatabase(dbName, 12).apply {
            insertList(this, "Car", 1_000L)
            insertList(this, "Old trip", 2_000L)
            execSQL(
                "INSERT INTO list_items (listId, text, done, sortOrder, createdAt, updatedAt, syncId, deleted, allDay, exact, exactDowngraded) " +
                    "VALUES (2, 'Deleted thing', 0, 0, 1, 1, 'item-gone', 1, 1, 0, 0)"
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(dbName, 13, true, MIGRATION_12_13)

        val cursor = db.query("SELECT listId, deleted FROM list_items WHERE syncId = 'item-gone'")
        assertTrue(cursor.moveToFirst())
        assertEquals(1L, cursor.getLong(0))
        assertEquals("the tombstone itself must survive the move", 1, cursor.getInt(1))
        cursor.close()
    }
}

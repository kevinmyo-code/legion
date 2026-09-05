package com.kevin.legion.grocery

import com.kevin.legion.checklists.ChecklistController
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.GroceryItem
import com.kevin.legion.data.local.GroceryStaple
import com.kevin.legion.testutil.RoomTestReset
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Ticket 10 slice B: [GroceryChecklistMigration] carries an open `grocery_items` trip onto a
 * non-recurring "Groceries" checklist exactly once. Robolectric through the real
 * [CarDatabase.getDatabase] path, same shape as
 * [com.kevin.legion.engine.migration.EngineDataMigrationWave1Test].
 */
@RunWith(RobolectricTestRunner::class)
class GroceryChecklistMigrationTest {
    private val context = RuntimeEnvironment.getApplication()
    private val db get() = CarDatabase.getDatabase(context)

    @Before
    fun clearState() {
        RoomTestReset.resetCarDatabaseSingleton()
        // The completion flag lives under its own prefs file - clear it explicitly, same reasoning
        // EngineDataMigrationWave1Test's own @Before gives for why Robolectric SharedPreferences
        // otherwise survive across tests in this package.
        context.getSharedPreferences("grocery_checklist_migration", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @After
    fun drainRoomInvalidationTracker() {
        RoomTestReset.drainArchDiskIoPool()
    }

    @Test
    fun `open items land on a new Groceries checklist, ticked items ticked today`() = runBlocking {
        val now = System.currentTimeMillis()
        db.groceryItemDao().insert(GroceryItem(text = "Milk", done = false, sortOrder = 0, createdAt = now, updatedAt = now))
        db.groceryItemDao().insert(GroceryItem(text = "Eggs", done = true, sortOrder = 1, createdAt = now, updatedAt = now))

        val result = GroceryChecklistMigration.migrateIfNeeded(context)

        assertEquals(2, result.migrated)
        assertFalse(result.alreadyDone)

        val lists = ChecklistController.allChecklists(context)
        assertEquals(1, lists.size)
        val checklist = lists.first()
        assertEquals("Groceries", checklist.name)
        assertEquals(null, checklist.scheduleKind) // non-recurring

        val loaded = ChecklistController.itemsWithTickState(context, checklist.id)
                as ChecklistController.ChecklistItemsResult.Loaded
        assertEquals(2, loaded.items.size)
        val milk = loaded.items.first { it.item.text == "Milk" }
        val eggs = loaded.items.first { it.item.text == "Eggs" }
        assertFalse(milk.ticked)
        assertTrue(eggs.ticked)
    }

    @Test
    fun `a second run is a no-op`() = runBlocking {
        val now = System.currentTimeMillis()
        db.groceryItemDao().insert(GroceryItem(text = "Milk", done = false, sortOrder = 0, createdAt = now, updatedAt = now))

        val first = GroceryChecklistMigration.migrateIfNeeded(context)
        assertEquals(1, first.migrated)

        // Reopen a fresh trip after the first sweep to prove the SECOND call does not touch it -
        // the flag alone must be what stops it, not "grocery_items happens to be empty".
        db.groceryItemDao().insert(GroceryItem(text = "Bread", done = false, sortOrder = 0, createdAt = now, updatedAt = now))

        val second = GroceryChecklistMigration.migrateIfNeeded(context)
        assertTrue(second.alreadyDone)
        assertEquals(0, second.migrated)

        // The checklist still has only the first run's item - "Bread" was never migrated.
        val checklist = ChecklistController.allChecklists(context).first()
        val loaded = ChecklistController.itemsWithTickState(context, checklist.id)
                as ChecklistController.ChecklistItemsResult.Loaded
        assertEquals(1, loaded.items.size)
        assertEquals("Milk", loaded.items.first().item.text)
    }

    @Test
    fun `grocery_items is empty afterwards`() = runBlocking {
        val now = System.currentTimeMillis()
        db.groceryItemDao().insert(GroceryItem(text = "Milk", done = false, createdAt = now, updatedAt = now))

        GroceryChecklistMigration.migrateIfNeeded(context)

        assertEquals(0, db.groceryItemDao().count())
    }

    @Test
    fun `grocery_staples is untouched by the migration`() = runBlocking {
        val now = System.currentTimeMillis()
        db.groceryItemDao().insert(GroceryItem(text = "Milk", done = true, createdAt = now, updatedAt = now))
        db.groceryStapleDao().upsert(
            GroceryStaple(name = "eggs", displayName = "Eggs", timesBought = 3, lastBoughtAt = now, updatedAtMs = now),
        )

        GroceryChecklistMigration.migrateIfNeeded(context)

        // Only the pre-existing staple survives, unchanged - "Milk" (ticked on the migrated trip)
        // must NOT have been folded in, since that fold is GroceryController.completeTrip's job and
        // this migration deliberately does not call it (see the migration's own class doc).
        val staples = db.groceryStapleDao().getAllIncludingDeleted()
        assertEquals(1, staples.size)
        assertEquals("eggs", staples.first().name)
        assertEquals(3, staples.first().timesBought)
    }

    @Test
    fun `no open trip still marks the sweep done`() = runBlocking {
        val result = GroceryChecklistMigration.migrateIfNeeded(context)
        assertEquals(0, result.migrated)
        assertFalse(result.alreadyDone)
        assertTrue(ChecklistController.allChecklists(context).isEmpty())

        val second = GroceryChecklistMigration.migrateIfNeeded(context)
        assertTrue(second.alreadyDone)
    }
}

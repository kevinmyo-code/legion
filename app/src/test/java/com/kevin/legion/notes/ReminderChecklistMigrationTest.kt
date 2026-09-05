package com.kevin.legion.notes

import com.kevin.legion.advisor.GoalChecklistSync
import com.kevin.legion.checklists.ChecklistController
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.testutil.RoomTestReset
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Ticket 10 slice C: [ReminderChecklistMigration] carries every DATELESS open reminder (no time, no
 * place trigger, no repeat) onto a non-recurring "Todo" checklist exactly once, and leaves a
 * dated/place-triggered/repeating reminder - and a goal's own materialized "Plan: " line -
 * untouched. Robolectric through the real [CarDatabase.getDatabase] path, same shape as
 * [com.kevin.legion.grocery.GroceryChecklistMigrationTest].
 */
@RunWith(RobolectricTestRunner::class)
class ReminderChecklistMigrationTest {
    private val context = RuntimeEnvironment.getApplication()

    @Before
    fun clearState() {
        RoomTestReset.resetCarDatabaseSingleton()
        context.getSharedPreferences("reminder_checklist_migration", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @After
    fun drainRoomInvalidationTracker() {
        RoomTestReset.drainArchDiskIoPool()
    }

    @Test
    fun `dateless open reminders land on a new Todo checklist, and are removed from the source`() = runBlocking {
        val list = NotesController.theList(context)
        val milk = NotesController.addItem(context, list.id, "buy milk")
        val bread = NotesController.addItem(context, list.id, "buy bread")

        val result = ReminderChecklistMigration.migrateIfNeeded(context)

        assertEquals(2, result.migrated)
        assertFalse(result.alreadyDone)

        val lists = ChecklistController.allChecklists(context)
        assertEquals(1, lists.size)
        val checklist = lists.first()
        assertEquals("Todo", checklist.name)
        assertNull(checklist.scheduleKind) // non-recurring

        val loaded = ChecklistController.itemsWithTickState(context, checklist.id)
                as ChecklistController.ChecklistItemsResult.Loaded
        assertEquals(setOf("buy milk", "buy bread"), loaded.items.map { it.item.text }.toSet())

        // The source reminders are gone - soft-deleted through NotesController's own funnel.
        assertNull(NotesController.itemById(context, milk.id))
        assertNull(NotesController.itemById(context, bread.id))
    }

    @Test
    fun `a dated reminder is untouched`() = runBlocking {
        val list = NotesController.theList(context)
        val now = System.currentTimeMillis()
        val dated = NotesController.addItemDue(context, list.id, "renew insurance", now + 86_400_000L)

        val result = ReminderChecklistMigration.migrateIfNeeded(context)

        assertEquals(0, result.migrated)
        assertTrue(ChecklistController.allChecklists(context).isEmpty())
        assertEquals("renew insurance", NotesController.itemById(context, dated.id)?.text)
    }

    @Test
    fun `a place-triggered reminder is untouched`() = runBlocking {
        val list = NotesController.theList(context)
        val item = NotesController.addItem(context, list.id, "grab gym bag")
        NotesController.setPlaceTrigger(context, item, "Gym")

        val result = ReminderChecklistMigration.migrateIfNeeded(context)

        assertEquals(0, result.migrated)
        assertTrue(ChecklistController.allChecklists(context).isEmpty())
        assertEquals("Gym", NotesController.itemById(context, item.id)?.triggerPlaceLabel)
    }

    @Test
    fun `a repeating reminder is untouched`() = runBlocking {
        val list = NotesController.theList(context)
        val now = System.currentTimeMillis()
        val dated = NotesController.addItemDue(context, list.id, "water the plants", now + 3_600_000L, allDay = false)
        NotesController.setRepeat(context, dated, RepeatRule.Daily(1), RepeatEnd.Never)

        val result = ReminderChecklistMigration.migrateIfNeeded(context)

        assertEquals(0, result.migrated)
        assertTrue(ChecklistController.allChecklists(context).isEmpty())
        assertEquals("DAILY", NotesController.itemById(context, dated.id)?.repeatKind)
    }

    @Test
    fun `a goal's own Plan line is untouched`() = runBlocking {
        val list = NotesController.theList(context)
        val plan = NotesController.addItem(context, list.id, GoalChecklistSync.ITEM_PREFIX + "run 2 miles")

        val result = ReminderChecklistMigration.migrateIfNeeded(context)

        assertEquals(0, result.migrated)
        assertTrue(ChecklistController.allChecklists(context).isEmpty())
        assertEquals(GoalChecklistSync.ITEM_PREFIX + "run 2 miles", NotesController.itemById(context, plan.id)?.text)
    }

    @Test
    fun `a second run is a no-op`() = runBlocking {
        val list = NotesController.theList(context)
        NotesController.addItem(context, list.id, "buy milk")

        val first = ReminderChecklistMigration.migrateIfNeeded(context)
        assertEquals(1, first.migrated)

        // A dateless reminder added AFTER the first sweep proves the SECOND call does not touch it -
        // the flag alone must be what stops it, not "there happened to be nothing left to migrate".
        NotesController.addItem(context, list.id, "buy bread")

        val second = ReminderChecklistMigration.migrateIfNeeded(context)
        assertTrue(second.alreadyDone)
        assertEquals(0, second.migrated)

        val checklist = ChecklistController.allChecklists(context).first()
        val loaded = ChecklistController.itemsWithTickState(context, checklist.id)
                as ChecklistController.ChecklistItemsResult.Loaded
        assertEquals(1, loaded.items.size)
        assertEquals("buy milk", loaded.items.first().item.text)
    }

    @Test
    fun `no dateless reminders still marks the sweep done`() = runBlocking {
        val result = ReminderChecklistMigration.migrateIfNeeded(context)
        assertEquals(0, result.migrated)
        assertFalse(result.alreadyDone)
        assertTrue(ChecklistController.allChecklists(context).isEmpty())

        val second = ReminderChecklistMigration.migrateIfNeeded(context)
        assertTrue(second.alreadyDone)
    }
}

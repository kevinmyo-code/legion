package com.kevin.legion.checklists

import com.kevin.legion.testutil.RoomTestReset
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Exercises [TickHistoryController.lastTicked] through the real [ChecklistController] write path
 * and Room, same Robolectric shape [ChecklistControllerTest] already uses. `TickHistoryTest`
 * exercises the pure matcher; this file exercises the FETCH - specifically ticket 03's "read
 * through tombstones on both the item and the checklist", which only a real Room read can prove.
 */
@RunWith(RobolectricTestRunner::class)
class TickHistoryControllerTest {
    private val context = RuntimeEnvironment.getApplication()

    @Before
    fun clearState() {
        RoomTestReset.resetCarDatabaseSingleton()
    }

    @After
    fun drainRoomInvalidationTracker() {
        RoomTestReset.drainArchDiskIoPool()
    }

    @Test
    fun `a tick survives its checklist being deleted`() = runBlocking {
        val checklist = ChecklistController.createChecklist(context, "Groceries")
        val item = ChecklistController.addItem(context, checklist.id, "toothpaste")
        ChecklistController.tick(context, item.id, day = ChecklistController.today())

        ChecklistController.deleteChecklist(context, checklist.id)

        val matches = TickHistoryController.lastTicked(context, "toothpaste")
        assertEquals(1, matches.size)
        assertEquals("Groceries", matches.first().checklistName)
    }

    @Test
    fun `a tick survives its own item being deleted`() = runBlocking {
        val checklist = ChecklistController.createChecklist(context, "Groceries")
        val item = ChecklistController.addItem(context, checklist.id, "toothpaste")
        ChecklistController.tick(context, item.id, day = ChecklistController.today())

        ChecklistController.deleteItem(context, item.id)

        val matches = TickHistoryController.lastTicked(context, "toothpaste")
        assertEquals(1, matches.size)
    }

    @Test
    fun `matches across every checklist, not just one named Groceries`() = runBlocking {
        val bio = ChecklistController.createChecklist(context, "bio")
        val item = ChecklistController.addItem(context, bio.id, "toothpaste")
        ChecklistController.tick(context, item.id, day = ChecklistController.today())

        val matches = TickHistoryController.lastTicked(context, "toothpaste")
        assertEquals(1, matches.size)
        assertEquals("bio", matches.first().checklistName)
    }

    @Test
    fun `newest tick wins across two different item rows with the same retyped text`() = runBlocking {
        // The exact scenario ticket 03 exists for: delete a list in March, start a new one in
        // September, retype "toothpaste" - a different ChecklistItem row, no relationship to the
        // old one, matched only by text.
        val marchList = ChecklistController.createChecklist(context, "Groceries")
        val marchItem = ChecklistController.addItem(context, marchList.id, "toothpaste")
        ChecklistController.tick(context, marchItem.id, day = ChecklistController.today() - 100, at = 1_000L)
        ChecklistController.deleteChecklist(context, marchList.id)

        val septemberList = ChecklistController.createChecklist(context, "Groceries")
        val septemberItem = ChecklistController.addItem(context, septemberList.id, "toothpaste")
        ChecklistController.tick(context, septemberItem.id, day = ChecklistController.today(), at = 9_000L)

        val matches = TickHistoryController.lastTicked(context, "toothpaste")
        assertEquals(2, matches.size)
        assertEquals(9_000L, matches.first().tickedAt)
        assertEquals(1_000L, matches.last().tickedAt)
    }

    @Test
    fun `no record returns an empty list, not an error`() = runBlocking {
        ChecklistController.createChecklist(context, "Groceries")
        val matches = TickHistoryController.lastTicked(context, "toothpaste")
        assertTrue(matches.isEmpty())
    }

    @Test
    fun `an unticked line is not returned`() = runBlocking {
        val checklist = ChecklistController.createChecklist(context, "Groceries")
        ChecklistController.addItem(context, checklist.id, "toothpaste")

        val matches = TickHistoryController.lastTicked(context, "toothpaste")
        assertTrue(matches.isEmpty())
    }

    @Test
    fun `an untick removes the record`() = runBlocking {
        val checklist = ChecklistController.createChecklist(context, "Groceries")
        val item = ChecklistController.addItem(context, checklist.id, "toothpaste")
        val day = ChecklistController.today()
        ChecklistController.tick(context, item.id, day = day)
        ChecklistController.untick(context, item.id, day = day)

        val matches = TickHistoryController.lastTicked(context, "toothpaste")
        assertTrue(matches.isEmpty())
    }
}

package com.kevin.legion.notes

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
 * **Cutover 1** (`docs/architecture/cutover1-2026-08-24.md`) originally made this the engine-backed
 * suite for [NotesController]'s UNCONFIGURED path. **Ticket 15 step 4**
 * (`.scratch/backend-erp/issues/15-engine-retirement-sequence.md`) repointed that path onto the
 * local `events` table, so this file now covers CRUD against `events` via the unconfigured branch;
 * [EngineNotesRetirementCopy]'s own suite (`engine/migration/EngineNotesRetirementCopyTest.kt`)
 * covers the one-time reconcile that keeps an item created directly through the engine (before this
 * repoint, or on an install still mid-soak) from being silently dropped the moment the read flips.
 * [NotesControllerBackendTest] covers the CONFIGURED path and is untouched by this ticket. Every
 * assertion reads back through [NotesController] itself, never `ListItemDao` - `list_items` gains
 * no reader or writer from either path, both before and after this repoint.
 */
@RunWith(RobolectricTestRunner::class)
class NotesControllerTest {
    private val context = RuntimeEnvironment.getApplication()

    @Before
    fun clearState() {
        RoomTestReset.resetCarDatabaseSingleton()
    }

    @After
    fun drainRoomInvalidationTracker() {
        // A DAO write anywhere in this test can schedule a Room InvalidationTracker refresh
        // on ArchTaskExecutor's disk-IO pool. If that refresh is still queued or running when
        // this test method returns, it races Robolectric's own per-@Test-METHOD native SQLite
        // reset and throws "Illegal connection pointer" on a background thread - uncaught, and
        // blamed by kotlinx-coroutines-test on whatever runTest starts next, not on this class.
        // Draining here, before Robolectric ever gets a chance to reset, is the fix - see
        // RoomTestReset's own class doc comment
        // (.scratch/hardening/issues/13-the-suite-is-green-by-luck.md) for the full account.
        RoomTestReset.drainArchDiskIoPool()
    }


    @Test
    fun `addItem writes an engine record and itemById reads it back`() = runBlocking {
        val list = NotesController.theList(context)
        val item = NotesController.addItem(context, list.id, " buy milk ")

        assertEquals("buy milk", item.text)
        assertFalse(item.done)
        val reread = NotesController.itemById(context, item.id)
        assertEquals("buy milk", reread?.text)

        // list_items gains no reader here - the legacy table must still be empty.
        assertTrue(CarDatabase.getDatabase(context).listItemDao().allActive().isEmpty())
    }

    @Test
    fun `tick refuses a recurring item and accepts a one-off`() = runBlocking {
        val list = NotesController.theList(context)
        val oneOff = NotesController.addItem(context, list.id, "one-off")
        assertTrue(NotesController.tick(context, oneOff))
        assertTrue(NotesController.itemById(context, oneOff.id)!!.done)

        val recurring = NotesController.addItem(context, list.id, "recurring")
        val withRepeat = NotesController.setRepeat(context, recurring, RepeatRule.Daily(1), RepeatEnd.Never)!!
        assertFalse(NotesController.tick(context, withRepeat))
        assertFalse(NotesController.itemById(context, recurring.id)!!.done)
    }

    @Test
    fun `setTime then setPlaceTrigger enforces at most one trigger`() = runBlocking {
        val list = NotesController.theList(context)
        val item = NotesController.addItem(context, list.id, "errand")
        val timed = NotesController.setTime(context, item, System.currentTimeMillis() + 3_600_000, null, true)!!
        assertTrue(timed.startsAt != null)

        val placed = NotesController.setPlaceTrigger(context, timed, "work")!!
        assertNull("setting a place trigger must clear any time trigger", placed.startsAt)
        assertEquals("work", placed.triggerPlaceLabel)

        val backToTime = NotesController.setTime(context, placed, System.currentTimeMillis() + 7_200_000, null, true)!!
        assertNull("setting a time trigger must clear any place trigger", backToTime.triggerPlaceLabel)
    }

    // -------------------------------------------------------------- outcome-verb honesty (should-fix 3)

    @Test
    fun `setTime returns null rather than a stale item when the record does not exist`() = runBlocking {
        val list = NotesController.theList(context)
        val item = NotesController.addItem(context, list.id, "will be deleted")
        assertTrue(NotesController.removeItem(context, item))

        // The record is now trashed - RecordStore.update refuses a write against a trashed record
        // (RecordStore.WriteResult.Failure), so this must surface as null, never a silently-unchanged
        // ListItem that looks like the write succeeded.
        val result = NotesController.setTime(context, item, System.currentTimeMillis() + 3_600_000, null, true)
        assertNull("a write against a trashed record must fail, not silently succeed", result)
    }

    @Test
    fun `removeItem returns false for an already-removed item, never a false success`() = runBlocking {
        val list = NotesController.theList(context)
        val item = NotesController.addItem(context, list.id, "one-time delete")
        assertTrue("the first delete must genuinely trash the record", NotesController.removeItem(context, item))
        assertFalse("a second delete of an already-trashed record must report failure, not success", NotesController.removeItem(context, item))
    }

    @Test
    fun `tick returns false for an already-removed item, never a false success`() = runBlocking {
        val list = NotesController.theList(context)
        val item = NotesController.addItem(context, list.id, "gone before ticking")
        NotesController.removeItem(context, item)
        assertFalse("ticking a trashed record must fail honestly", NotesController.tick(context, item))
    }

    @Test
    fun `untick clears done and doneAt`() = runBlocking {
        val list = NotesController.theList(context)
        val item = NotesController.addItem(context, list.id, "chore")
        NotesController.tick(context, item)
        val ticked = NotesController.itemById(context, item.id)!!
        NotesController.untick(context, ticked)
        val unticked = NotesController.itemById(context, item.id)!!
        assertFalse(unticked.done)
        assertNull(unticked.doneAt)
    }

    @Test
    fun `removeItem trashes the record so it no longer appears in allItems`() = runBlocking {
        val list = NotesController.theList(context)
        val item = NotesController.addItem(context, list.id, "gone soon")
        NotesController.removeItem(context, item)
        assertTrue(NotesController.allItems(context).none { it.id == item.id })
    }

    @Test
    fun `missedItems and dismissMissed round-trip through the engine`() = runBlocking {
        val list = NotesController.theList(context)
        val item = NotesController.addItem(context, list.id, "expired reminder")
        NotesController.markMissed(context, item.id)
        assertTrue(NotesController.missedItems(context).any { it.id == item.id })

        val missed = NotesController.itemById(context, item.id)!!
        NotesController.dismissMissed(context, missed)
        assertTrue(NotesController.missedItems(context).none { it.id == item.id })
    }

    @Test
    fun `unconfigured start-up sweep still marks a genuinely overdue item missed`() = runBlocking {
        // Unconfigured/engine path guard, unchanged by ticket 11's 2026-08-27 fix (that fix
        // corrected what the CONFIGURED sweep reads - see AlarmSchedulerTest for the configured
        // regression test, both the marks-a-reminder and never-touches-an-appointment halves).
        // NotesController.backend resolves to null here (no backendOverride, no Supabase project
        // configured in the test environment), so this exercises the same path production runs on
        // for the majority of installs, which carry no Supabase configuration at all.
        val list = NotesController.theList(context)
        val item = NotesController.addItem(context, list.id, "genuinely overdue")
        NotesController.setTime(context, item, startsAt = 1_000L, endsAt = null, allDay = false)

        AlarmScheduler.rescheduleAll(context)

        val reread = NotesController.itemById(context, item.id)
        assertTrue("an overdue one-off item must still be marked missed", reread?.missedAt != null)
    }

    @Test
    fun `markLogged is readable back off the engine record - the new loggedAt field`() = runBlocking {
        val list = NotesController.theList(context)
        val item = NotesController.addItem(context, list.id, "Plan: swept line")
        assertNull(item.loggedAt)
        val at = System.currentTimeMillis()
        NotesController.markLogged(context, item.id, at)
        assertEquals(at, NotesController.itemById(context, item.id)!!.loggedAt)
    }

    @Test
    fun `openWithPlaceTrigger and openWithAnyPlaceTrigger read engine records, never the legacy DAO`() = runBlocking {
        val list = NotesController.theList(context)
        val item = NotesController.addItem(context, list.id, "grab bag")
        NotesController.setPlaceTrigger(context, item, "work")

        assertEquals(1, NotesController.openWithPlaceTrigger(context, "work").size)
        assertEquals(0, NotesController.openWithPlaceTrigger(context, "home").size)
        assertEquals(1, NotesController.openWithAnyPlaceTrigger(context).size)
        assertTrue(CarDatabase.getDatabase(context).listItemDao().allActive().isEmpty())
    }

    // ------------------------------------------------------------------------- recurrence skip rekey

    @Test
    fun `skipOccurrence keys ListItemSkip by the engine record id`() = runBlocking {
        val list = NotesController.theList(context)
        val item = NotesController.addItem(context, list.id, "weekly trash day")
        val withRepeat = NotesController.setRepeat(context, item, RepeatRule.Weekly(1, setOf(java.time.DayOfWeek.MONDAY)), RepeatEnd.Never)!!
        val skipDate = System.currentTimeMillis()

        NotesController.skipOccurrence(context, withRepeat, skipDate)

        val skips = CarDatabase.getDatabase(context).listItemSkipDao().skippedDatesForItem(item.id)
        assertEquals(listOf(skipDate), skips)
        assertEquals(setOf(skipDate), NotesController.skippedDates(context, withRepeat))
    }

    // ----------------------------------------------------------------- ticket 15 step 4: events repoint

    @Test
    fun `unconfigured writes land in the local events table, never list_items`() = runBlocking {
        val list = NotesController.theList(context)
        NotesController.addItem(context, list.id, "buy milk")

        val db = CarDatabase.getDatabase(context)
        assertEquals(1, db.eventDao().getAllActive().size)
        assertEquals("buy milk", db.eventDao().getAllActive().single().title)
        assertTrue("list_items must gain no writer from this repoint either", db.listItemDao().allActive().isEmpty())
    }

    @Test
    fun `unconfigured reads are NULLS-LAST ordered, matching the configured path's own policy`() = runBlocking {
        val list = NotesController.theList(context)
        val undated = NotesController.addItem(context, list.id, "undated todo")
        val dated = NotesController.addItem(context, list.id, "dated reminder")
        NotesController.setTime(context, dated, startsAt = 500_000L, endsAt = null, allDay = false)

        // allItems does not itself sort by startsAt (that is the agenda's job), so read the DAO
        // directly - this is the same query allNotesItems delegates to on the unconfigured branch.
        val ordered = CarDatabase.getDatabase(context).eventDao().getActiveByKind(
            com.kevin.legion.backend.EventKind.REMINDER,
        )
        assertEquals("dated reminder", ordered.first().title)
        assertEquals("undated todo", ordered.last().title)
        assertTrue(undated.id != dated.id)
    }

    @Test
    fun `unconfigured start-up sweep marks a genuinely overdue reminder missed and never touches an appointment sitting next to it`() = runBlocking {
        // The unconfigured-path twin of AlarmSchedulerTest's configured regression test - the same
        // incident shape (a Dates appointment merged into the same table as Notes reminders) can
        // recur on EITHER path now that both read/write the same local `events` table, so both get
        // an explicit test rather than assuming the configured one covers it.
        val list = NotesController.theList(context)
        val reminder = NotesController.addItem(context, list.id, "genuinely overdue reminder")
        NotesController.setTime(context, reminder, startsAt = 1_000L, endsAt = null, allDay = false)

        val db = CarDatabase.getDatabase(context)
        db.eventDao().insert(
            com.kevin.legion.data.local.Event(
                id = 0,
                serverId = java.util.UUID.randomUUID().toString(),
                title = "Dentist",
                startsAt = 2_000L,
                source = "legion",
                kind = com.kevin.legion.backend.EventKind.APPOINTMENT,
                updatedAtMs = 1L,
                createdAt = 1L,
            ),
        )

        AlarmScheduler.rescheduleAll(context)

        val rereadReminder = NotesController.itemById(context, reminder.id)
        assertTrue(
            "the genuinely overdue REMINDER must be marked missed",
            rereadReminder?.missedAt != null,
        )
        val appointmentRow = db.eventDao().getAll().single { it.title == "Dentist" }
        assertEquals(
            "the APPOINTMENT must never be touched by this sweep - it is not something NotesController owns",
            null,
            appointmentRow.missedAt,
        )
    }
}

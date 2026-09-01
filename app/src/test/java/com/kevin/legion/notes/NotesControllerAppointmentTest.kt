package com.kevin.legion.notes

import com.kevin.legion.backend.EventKind
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.Event
import com.kevin.legion.data.local.nextAppointmentId
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
import java.util.UUID

/**
 * One-today ticket 02, "ticking an appointment", **NARROWED by ticket 08, "events are not todos"
 * (2026-09-01, `.scratch/one-today/issues/08-events-are-not-todos.md`).** Kevin, verbatim: "i dont
 * mark an event done, it just passes whether or not i do it, like classes". This file used to be
 * titled "an appointment can be ticked" - that premise is now half wrong: [EventKind.EVENT] (every
 * row that used to read `kind = appointment`) can never be ticked at all, and only [EventKind.TASK]
 * (nothing writes one yet - Canvas is its own ticket) still goes through
 * [NotesController.tickAppointment]/[NotesController.untickAppointment]. The other four functions
 * in this section - [NotesController.appointmentById]/[NotesController.updateAppointment]/
 * [NotesController.removeAppointment] - still cover BOTH [EventKind.EVENT] and [EventKind.TASK]
 * (renaming or deleting a calendar entry stays legitimate even though ticking one off is not); only
 * [NotesController.findAppointment]/[NotesController.openAppointments] narrowed to
 * [EventKind.TASK] alone (see those functions' own doc comments for why an event must never
 * surface as a voice tick-fallback candidate).
 */
@RunWith(RobolectricTestRunner::class)
class NotesControllerAppointmentTest {
    private val context = RuntimeEnvironment.getApplication()

    @Before
    fun clearState() {
        RoomTestReset.resetCarDatabaseSingleton()
    }

    @After
    fun drainRoomInvalidationTracker() {
        // See NotesControllerTest's identical @After for why this is load-bearing, not tidying.
        RoomTestReset.drainArchDiskIoPool()
    }

    /** Inserts a bare calendar-table row directly - the shape the retired Google import and
     * `service/LiveToolbox.kt`'s `addAppointment` both produce, allocated from the SAME disjoint
     * range ([com.kevin.legion.data.local.nextAppointmentId]) so a test id can never collide with a
     * reminder id created in the same test. [kind] defaults to [EventKind.EVENT] - the shape every
     * real row on the device is (a historical Google import or a voice-created calendar entry) -
     * with [EventKind.TASK] passed explicitly by the tests that need a completable one. */
    private suspend fun insertAppointment(title: String, done: Boolean = false, kind: String = EventKind.EVENT): Event {
        val db = CarDatabase.getDatabase(context)
        val now = System.currentTimeMillis()
        val row = Event(
            id = db.eventDao().nextAppointmentId(),
            serverId = UUID.randomUUID().toString(),
            guid = UUID.randomUUID().toString(),
            title = title,
            startsAt = now + 3_600_000,
            endsAt = now + 7_200_000,
            allDay = false,
            source = "legion",
            kind = kind,
            done = done,
            updatedAtMs = now,
            createdAt = now,
        )
        val id = db.eventDao().insert(row)
        return row.copy(id = id)
    }

    @Test
    fun `a task can be ticked and unticked`() = runBlocking {
        val task = insertAppointment("Submit essay", kind = EventKind.TASK)
        assertFalse(task.done)

        assertTrue(NotesController.tickAppointment(context, task))
        val ticked = NotesController.appointmentById(context, task.id)!!
        assertTrue(ticked.done)
        assertTrue(ticked.doneAt != null)

        assertTrue(NotesController.untickAppointment(context, task))
        val unticked = NotesController.appointmentById(context, task.id)!!
        assertFalse(unticked.done)
        assertNull(unticked.doneAt)
    }

    @Test
    fun `an event can never be ticked - it just passes, like Kevin's classes`() = runBlocking {
        // The core reversal ticket 08 makes: half of what ticket 02 made tickable were never
        // appointments, they were assignments a title heuristic could not tell apart from a class.
        val event = insertAppointment("COSC 3334 Intro to Cybersecurity")

        assertFalse("an EventKind.EVENT row must never be tickable", NotesController.tickAppointment(context, event))
        val reread = NotesController.appointmentById(context, event.id)!!
        assertFalse(reread.done)
        assertNull(reread.doneAt)

        // Nor can a stale `done = true` (the real on-device COSC 3334 row, ticked during testing on
        // 2026-09-01) be cleared through untickAppointment - it must simply refuse the row outright,
        // matching MIGRATION_56_57's own clearing of that exact case at the data layer instead.
        val staleDoneEvent = insertAppointment("Stale event", done = true, kind = EventKind.EVENT)
        assertFalse(NotesController.untickAppointment(context, staleDoneEvent))
    }

    @Test
    fun `AlarmScheduler owns exactly what it owned before - ticking a task never arms or cancels an alarm`() = runBlocking {
        // The regression this ticket exists to prevent (2026-08-26): AlarmScheduler's sweep must
        // never treat a calendar-table row as something it owns. tickAppointment/untickAppointment
        // call neither AlarmScheduler.schedule nor AlarmScheduler.cancel at all (traced: neither
        // function references AlarmScheduler in its body) - proven here by asserting a REMINDER's
        // own scheduling state (allWithTimeTrigger, the exact scan AlarmScheduler.rescheduleAll
        // walks) is completely unaffected by ticking an unrelated task.
        val list = NotesController.theList(context)
        val reminder = NotesController.addItemDue(context, list.id, "oil change", System.currentTimeMillis() + 3_600_000, allDay = false)
        val beforeTriggers = NotesController.allWithTimeTrigger(context).map { it.id to it.startsAt }

        val task = insertAppointment("Submit essay", kind = EventKind.TASK)
        assertTrue(NotesController.tickAppointment(context, task))
        assertTrue(NotesController.untickAppointment(context, task))

        val afterTriggers = NotesController.allWithTimeTrigger(context).map { it.id to it.startsAt }
        assertEquals(
            "ticking/unticking a task must not perturb any reminder's own alarm-trigger state",
            beforeTriggers,
            afterTriggers,
        )
        // And the reminder itself is untouched (not ticked, not retimed).
        val rereadReminder = NotesController.itemById(context, reminder.id)!!
        assertFalse(rereadReminder.done)
        assertEquals(reminder.startsAt, rereadReminder.startsAt)
    }

    @Test
    fun `tickAppointment refuses a reminder-kind row - the kind boundary holds both directions`() = runBlocking {
        val list = NotesController.theList(context)
        val reminder = NotesController.addItem(context, list.id, "a reminder")
        val db = CarDatabase.getDatabase(context)
        val reminderRow = db.eventDao().getById(reminder.id)!!

        assertFalse("a reminder-kind row must never be tickable through the appointment funnel", NotesController.tickAppointment(context, reminderRow))
        assertFalse(NotesController.itemById(context, reminder.id)!!.done)
    }

    @Test
    fun `appointmentById resolves both an event and a task, never a reminder - itemById never resolves either`() = runBlocking {
        val list = NotesController.theList(context)
        val reminder = NotesController.addItem(context, list.id, "a reminder")
        val event = insertAppointment("Dentist")
        val task = insertAppointment("Submit essay", kind = EventKind.TASK)

        assertNull(NotesController.appointmentById(context, reminder.id))
        assertNull(NotesController.itemById(context, event.id))
        assertNull(NotesController.itemById(context, task.id))
        assertEquals("Dentist", NotesController.appointmentById(context, event.id)?.text)
        assertEquals("Submit essay", NotesController.appointmentById(context, task.id)?.text)
        assertEquals("a reminder", NotesController.itemById(context, reminder.id)?.text)
    }

    @Test
    fun `an event never appears in the reminder-only stream allItems reads`() = runBlocking {
        val list = NotesController.theList(context)
        NotesController.addItem(context, list.id, "a reminder")
        insertAppointment("Dentist")

        val allReminderItems = NotesController.allItems(context)
        assertEquals(1, allReminderItems.size)
        assertEquals("a reminder", allReminderItems.single().text)
    }

    @Test
    fun `findAppointment matches an open TASK by fuzzy title, same shape as findItem`() = runBlocking {
        insertAppointment("Submit essay", kind = EventKind.TASK)

        val match = NotesController.findAppointment(context, "essay")
        assertTrue(match is ItemMatch.Resolved)
        assertEquals("Submit essay", (match as ItemMatch.Resolved).item.text)

        assertTrue(NotesController.findAppointment(context, "nothing like this") is ItemMatch.NoMatch)
    }

    @Test
    fun `findAppointment never matches an EVENT, even by an exact title - a class can't be a tick target`() = runBlocking {
        // The bug ticket 08 exists to close: before this narrowing, a voice "mark COSC 3334 done"
        // would have matched the class by title and ticked it. Now there is nothing to match.
        insertAppointment("COSC 3334 Intro to Cybersecurity")

        assertTrue(NotesController.findAppointment(context, "COSC 3334") is ItemMatch.NoMatch)
    }

    @Test
    fun `updateAppointment edits title and time on either an event or a task, removeAppointment hard-deletes either`() = runBlocking {
        val event = insertAppointment("Dentist")
        val newStart = event.startsAt!! + 86_400_000
        val newEnd = newStart + 3_600_000

        assertTrue(NotesController.updateAppointment(context, event.id, "Dentist (rescheduled)", newStart, newEnd, false))
        val updated = NotesController.appointmentById(context, event.id)!!
        assertEquals("Dentist (rescheduled)", updated.text)
        assertEquals(newStart, updated.startsAt)

        assertTrue(NotesController.removeAppointment(context, event.id))
        assertNull(NotesController.appointmentById(context, event.id))
        // Not a soft delete - the row is genuinely gone, matching removeItem's own unconfigured
        // hard-delete convention for this table (Event's own doc comment).
        assertNull(CarDatabase.getDatabase(context).eventDao().getById(event.id))

        val task = insertAppointment("Submit essay", kind = EventKind.TASK)
        assertTrue(NotesController.updateAppointment(context, task.id, "Submit essay (final)", task.startsAt!!, task.endsAt!!, false))
        assertTrue(NotesController.removeAppointment(context, task.id))
        assertNull(NotesController.appointmentById(context, task.id))
    }
}

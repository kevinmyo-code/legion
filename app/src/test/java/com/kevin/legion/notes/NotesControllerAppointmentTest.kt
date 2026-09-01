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
 * One-today ticket 02, "ticking an appointment" - the pairing the ticket's own "done means"
 * demands: **an appointment can be ticked, and [notes.AlarmScheduler] still owns exactly what it
 * owned before.** [NotesControllerTest] covers the REMINDER-only funnel this file deliberately
 * never touches; this file covers the separate, narrower appointment funnel added alongside it
 * ([NotesController.tickAppointment]/[NotesController.untickAppointment]/
 * [NotesController.appointmentById]/[NotesController.findAppointment]/
 * [NotesController.updateAppointment]/[NotesController.removeAppointment]).
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

    /** Inserts a bare appointment row directly - the shape the retired Google import and
     * `service/LiveToolbox.kt`'s `addAppointment` both produce, allocated from the SAME disjoint
     * range ([com.kevin.legion.data.local.nextAppointmentId]) so a test id can never collide with a
     * reminder id created in the same test. */
    private suspend fun insertAppointment(title: String, done: Boolean = false): Event {
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
            kind = EventKind.APPOINTMENT,
            done = done,
            updatedAtMs = now,
            createdAt = now,
        )
        val id = db.eventDao().insert(row)
        return row.copy(id = id)
    }

    @Test
    fun `an appointment can be ticked and unticked`() = runBlocking {
        val appointment = insertAppointment("Dentist")
        assertFalse(appointment.done)

        assertTrue(NotesController.tickAppointment(context, appointment))
        val ticked = NotesController.appointmentById(context, appointment.id)!!
        assertTrue(ticked.done)
        assertTrue(ticked.doneAt != null)

        assertTrue(NotesController.untickAppointment(context, appointment))
        val unticked = NotesController.appointmentById(context, appointment.id)!!
        assertFalse(unticked.done)
        assertNull(unticked.doneAt)
    }

    @Test
    fun `AlarmScheduler owns exactly what it owned before - ticking an appointment never arms or cancels an alarm`() = runBlocking {
        // The regression this ticket exists to prevent (2026-08-26): AlarmScheduler's sweep must
        // never treat an appointment as something it owns. tickAppointment/untickAppointment call
        // neither AlarmScheduler.schedule nor AlarmScheduler.cancel at all (traced: neither function
        // references AlarmScheduler in its body) - proven here by asserting a REMINDER's own
        // scheduling state (allWithTimeTrigger, the exact scan AlarmScheduler.rescheduleAll walks)
        // is completely unaffected by ticking an unrelated appointment.
        val list = NotesController.theList(context)
        val reminder = NotesController.addItemDue(context, list.id, "oil change", System.currentTimeMillis() + 3_600_000, allDay = false)
        val beforeTriggers = NotesController.allWithTimeTrigger(context).map { it.id to it.startsAt }

        val appointment = insertAppointment("Dentist")
        assertTrue(NotesController.tickAppointment(context, appointment))
        assertTrue(NotesController.untickAppointment(context, appointment))

        val afterTriggers = NotesController.allWithTimeTrigger(context).map { it.id to it.startsAt }
        assertEquals(
            "ticking/unticking an appointment must not perturb any reminder's own alarm-trigger state",
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
    fun `appointmentById never resolves a reminder, and itemById never resolves an appointment`() = runBlocking {
        val list = NotesController.theList(context)
        val reminder = NotesController.addItem(context, list.id, "a reminder")
        val appointment = insertAppointment("Dentist")

        assertNull(NotesController.appointmentById(context, reminder.id))
        assertNull(NotesController.itemById(context, appointment.id))
        assertEquals("Dentist", NotesController.appointmentById(context, appointment.id)?.text)
        assertEquals("a reminder", NotesController.itemById(context, reminder.id)?.text)
    }

    @Test
    fun `an appointment never appears in the reminder-only stream allItems reads`() = runBlocking {
        val list = NotesController.theList(context)
        NotesController.addItem(context, list.id, "a reminder")
        insertAppointment("Dentist")

        val allReminderItems = NotesController.allItems(context)
        assertEquals(1, allReminderItems.size)
        assertEquals("a reminder", allReminderItems.single().text)
    }

    @Test
    fun `findAppointment matches an open appointment by fuzzy title, same shape as findItem`() = runBlocking {
        insertAppointment("Dentist appointment")

        val match = NotesController.findAppointment(context, "dentist")
        assertTrue(match is ItemMatch.Resolved)
        assertEquals("Dentist appointment", (match as ItemMatch.Resolved).item.text)

        assertTrue(NotesController.findAppointment(context, "nothing like this") is ItemMatch.NoMatch)
    }

    @Test
    fun `updateAppointment edits title and time, removeAppointment hard-deletes`() = runBlocking {
        val appointment = insertAppointment("Dentist")
        val newStart = appointment.startsAt!! + 86_400_000
        val newEnd = newStart + 3_600_000

        assertTrue(NotesController.updateAppointment(context, appointment.id, "Dentist (rescheduled)", newStart, newEnd, false))
        val updated = NotesController.appointmentById(context, appointment.id)!!
        assertEquals("Dentist (rescheduled)", updated.text)
        assertEquals(newStart, updated.startsAt)

        assertTrue(NotesController.removeAppointment(context, appointment.id))
        assertNull(NotesController.appointmentById(context, appointment.id))
        // Not a soft delete - the row is genuinely gone, matching removeItem's own unconfigured
        // hard-delete convention for this table (Event's own doc comment).
        assertNull(CarDatabase.getDatabase(context).eventDao().getById(appointment.id))
    }
}

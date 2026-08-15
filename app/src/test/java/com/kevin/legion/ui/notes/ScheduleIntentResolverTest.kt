package com.kevin.legion.ui.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises the appointment-versus-reminder routing branch in `ui/notes/ScheduleIntentResolver.kt`
 * hard - ticket 14: "that is the piece with a wrong answer that looks right." Plain JUnit, no
 * Compose/Room/`Context`/`CalendarContract`, same posture as [CalendarAgendaResolverTest].
 */
class ScheduleIntentResolverTest {

    // ------------------------------------------------------------------------------------- resolve

    @Test
    fun `explicit appointment resolves to Appointment`() {
        assertTrue(ScheduleIntentResolver.resolve("appointment") is ScheduleIntentResolver.Kind.Appointment)
    }

    @Test
    fun `explicit appointment is case-insensitive and tolerates surrounding whitespace`() {
        assertTrue(ScheduleIntentResolver.resolve("Appointment") is ScheduleIntentResolver.Kind.Appointment)
        assertTrue(ScheduleIntentResolver.resolve("APPOINTMENT") is ScheduleIntentResolver.Kind.Appointment)
        assertTrue(ScheduleIntentResolver.resolve("  appointment  ") is ScheduleIntentResolver.Kind.Appointment)
    }

    @Test
    fun `explicit reminder resolves to Reminder`() {
        assertTrue(ScheduleIntentResolver.resolve("reminder") is ScheduleIntentResolver.Kind.Reminder)
    }

    @Test
    fun `null defaults to Reminder`() {
        assertTrue(ScheduleIntentResolver.resolve(null) is ScheduleIntentResolver.Kind.Reminder)
    }

    @Test
    fun `blank string defaults to Reminder`() {
        assertTrue(ScheduleIntentResolver.resolve("") is ScheduleIntentResolver.Kind.Reminder)
        assertTrue(ScheduleIntentResolver.resolve("   ") is ScheduleIntentResolver.Kind.Reminder)
    }

    @Test
    fun `unrecognized value defaults to Reminder, never throws, never guesses appointment`() {
        // The ticket's own named risk: a wrong answer that looks right. Anything that is not
        // exactly "appointment" must fall to the safe, undoable side, including near-misses.
        assertTrue(ScheduleIntentResolver.resolve("appointments") is ScheduleIntentResolver.Kind.Reminder)
        assertTrue(ScheduleIntentResolver.resolve("event") is ScheduleIntentResolver.Kind.Reminder)
        assertTrue(ScheduleIntentResolver.resolve("calendar") is ScheduleIntentResolver.Kind.Reminder)
        assertTrue(ScheduleIntentResolver.resolve("gibberish") is ScheduleIntentResolver.Kind.Reminder)
    }

    // --------------------------------------------------------------------------- confirmationPhrase

    @Test
    fun `appointment phrase says calendar and states the when`() {
        val phrase = ScheduleIntentResolver.confirmationPhrase(
            ScheduleIntentResolver.Kind.Appointment, "dentist", "Tuesday at 3:00 PM",
        )
        assertEquals("Put \"dentist\" on your calendar for Tuesday at 3:00 PM.", phrase)
    }

    @Test(expected = IllegalStateException::class)
    fun `appointment phrase refuses a null when - an appointment always has a date`() {
        ScheduleIntentResolver.confirmationPhrase(ScheduleIntentResolver.Kind.Appointment, "dentist", null)
    }

    @Test
    fun `reminder phrase with a when says remind, never calendar`() {
        val phrase = ScheduleIntentResolver.confirmationPhrase(
            ScheduleIntentResolver.Kind.Reminder, "change the oil", "Tuesday",
        )
        assertEquals("I'll remind you about \"change the oil\" Tuesday.", phrase)
        assertTrue("must never claim the calendar store for a reminder", !phrase.contains("calendar"))
    }

    @Test
    fun `reminder phrase with no when falls back to a plain add confirmation`() {
        val phrase = ScheduleIntentResolver.confirmationPhrase(
            ScheduleIntentResolver.Kind.Reminder, "buy dog food", null,
        )
        assertEquals("Added \"buy dog food\" to your list.", phrase)
    }

    @Test
    fun `appointment and reminder phrases for the same item and when never collide`() {
        val appointment = ScheduleIntentResolver.confirmationPhrase(
            ScheduleIntentResolver.Kind.Appointment, "checkup", "Friday at 9:00 AM",
        )
        val reminder = ScheduleIntentResolver.confirmationPhrase(
            ScheduleIntentResolver.Kind.Reminder, "checkup", "Friday at 9:00 AM",
        )
        // The whole point of ticket 14's "Alfred always says which he did" - the two sentences must
        // be distinguishable so a driver can tell which store an item landed in from the reply alone.
        org.junit.Assert.assertNotEquals(appointment, reminder)
    }
}

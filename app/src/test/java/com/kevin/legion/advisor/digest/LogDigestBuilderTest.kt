package com.kevin.legion.advisor.digest

import com.kevin.legion.calendar.CalendarProvider.GoogleCalendarEvent
import com.kevin.legion.data.local.ListItem
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic coverage for [LogDigestBuilder.buildDigestText], exercised through its `internal` seam
 * - no Room, no `Context`, no `CalendarContract` (matches [com.kevin.legion.ui.notes
 * .CalendarAgendaResolverTest]'s posture of never touching the platform calendar provider directly
 * in a unit test).
 */
class LogDigestBuilderTest {

    private val now = 1_700_000_000_000L
    private val day = 24L * 60 * 60 * 1000
    private val listId = 1L

    private fun task(text: String, createdAt: Long, updatedAt: Long = createdAt, done: Boolean = false, startsAt: Long? = null, repeatKind: String? = null) =
        ListItem(listId = listId, text = text, done = done, createdAt = createdAt, updatedAt = updatedAt, startsAt = startsAt, repeatKind = repeatKind)

    // ------------------------------------------------------------------------------ empty domain

    @Test
    fun `no tasks at all reads not logged, never zero`() {
        val text = LogDigestBuilder.buildDigestText(
            allActive = emptyList(), tickableListIds = setOf(listId), missed = emptyList(),
            placeReminderCount = 0, calendarEvents = emptyList(), now = now,
        )
        assertTrue(text.contains("TASKS not logged"))
    }

    // --------------------------------------------------------------------------------- age bands

    @Test
    fun `open tasks bucket into fresh, aging and stale age bands`() {
        val items = listOf(
            task("Buy milk", createdAt = now - 1 * day), // fresh
            task("Book dentist", createdAt = now - 15 * day), // aging
            task("Renew passport", createdAt = now - 40 * day), // stale
        )
        val text = LogDigestBuilder.buildDigestText(
            allActive = items, tickableListIds = setOf(listId), missed = emptyList(),
            placeReminderCount = 0, calendarEvents = emptyList(), now = now,
        )
        assertTrue(text.contains("fresh(<7d) 1"))
        assertTrue(text.contains("aging(7-30d) 1"))
        assertTrue(text.contains("stale(30d+) 1"))
        assertTrue(text.contains("Renew passport"))
    }

    @Test
    fun `a note-list item never counts as an open task`() {
        val noteListId = 2L
        val items = listOf(task("Reference note", createdAt = now - day).copy(listId = noteListId))
        val text = LogDigestBuilder.buildDigestText(
            allActive = items, tickableListIds = setOf(listId), missed = emptyList(),
            placeReminderCount = 0, calendarEvents = emptyList(), now = now,
        )
        assertTrue(text.contains("TASKS not logged"))
    }

    @Test
    fun `a timed calendar-shaped item never counts as an open task`() {
        val items = listOf(task("Dentist appointment", createdAt = now - day, startsAt = now + day))
        val text = LogDigestBuilder.buildDigestText(
            allActive = items, tickableListIds = setOf(listId), missed = emptyList(),
            placeReminderCount = 0, calendarEvents = emptyList(), now = now,
        )
        assertTrue(text.contains("TASKS not logged"))
    }

    @Test
    fun `a recurring item never counts as an open task, it re-arms rather than going stale`() {
        val items = listOf(task("Take vitamins", createdAt = now - 60 * day, repeatKind = "Daily"))
        val text = LogDigestBuilder.buildDigestText(
            allActive = items, tickableListIds = setOf(listId), missed = emptyList(),
            placeReminderCount = 0, calendarEvents = emptyList(), now = now,
        )
        assertTrue(text.contains("TASKS not logged"))
    }

    @Test
    fun `a done item never counts as open`() {
        val items = listOf(task("Already done", createdAt = now - 40 * day, done = true))
        val text = LogDigestBuilder.buildDigestText(
            allActive = items, tickableListIds = setOf(listId), missed = emptyList(),
            placeReminderCount = 0, calendarEvents = emptyList(), now = now,
        )
        assertTrue(text.contains("TASKS not logged"))
    }

    // ------------------------------------------------------------------------- overdue reminders

    @Test
    fun `overdue reminders name the missed items`() {
        val missed = listOf(task("Call the vet", createdAt = now - 10 * day, startsAt = now - 2 * day))
        val text = LogDigestBuilder.buildDigestText(
            allActive = emptyList(), tickableListIds = setOf(listId), missed = missed,
            placeReminderCount = 0, calendarEvents = emptyList(), now = now,
        )
        assertTrue(text.contains("OVERDUE REMINDERS 1: Call the vet"))
    }

    @Test
    fun `no missed reminders reads none, a real computed fact`() {
        val text = LogDigestBuilder.buildDigestText(
            allActive = emptyList(), tickableListIds = setOf(listId), missed = emptyList(),
            placeReminderCount = 0, calendarEvents = emptyList(), now = now,
        )
        assertTrue(text.contains("OVERDUE REMINDERS none"))
    }

    // ------------------------------------------------------------------------------- calendar

    @Test
    fun `null calendar events reads permission not granted, distinct from an honest empty list`() {
        val text = LogDigestBuilder.buildDigestText(
            allActive = emptyList(), tickableListIds = setOf(listId), missed = emptyList(),
            placeReminderCount = 0, calendarEvents = null, now = now,
        )
        assertTrue(text.contains("calendar permission not granted"))
    }

    @Test
    fun `an empty calendar window reads nothing scheduled, never not logged`() {
        val text = LogDigestBuilder.buildDigestText(
            allActive = emptyList(), tickableListIds = setOf(listId), missed = emptyList(),
            placeReminderCount = 0, calendarEvents = emptyList(), now = now,
        )
        assertTrue(text.contains("nothing scheduled"))
        assertFalse(text.contains("CALENDAR next 7d not logged"))
    }

    @Test
    fun `calendar events in window are named`() {
        val events = listOf(GoogleCalendarEvent(eventId = 1, calendarId = 1, title = "Dentist", startMs = now + day, endMs = now + day + 1000, allDay = false))
        val text = LogDigestBuilder.buildDigestText(
            allActive = emptyList(), tickableListIds = setOf(listId), missed = emptyList(),
            placeReminderCount = 0, calendarEvents = events, now = now,
        )
        assertTrue(text.contains("1 event(s)"))
        assertTrue(text.contains("Dentist"))
    }

    // --------------------------------------------------------------------------- deferral flags

    @Test
    fun `a stale item touched since creation flags as a repeated-deferral candidate`() {
        val items = listOf(
            task("Renew passport", createdAt = now - 20 * day, updatedAt = now - 5 * day), // touched, still open, old enough
        )
        val text = LogDigestBuilder.buildDigestText(
            allActive = items, tickableListIds = setOf(listId), missed = emptyList(),
            placeReminderCount = 0, calendarEvents = emptyList(), now = now,
        )
        assertTrue(text.contains("REPEATED-DEFERRAL CANDIDATES 1: Renew passport"))
    }

    @Test
    fun `an old but never-touched item does not flag as a deferral candidate`() {
        val items = listOf(task("Renew passport", createdAt = now - 20 * day, updatedAt = now - 20 * day))
        val text = LogDigestBuilder.buildDigestText(
            allActive = items, tickableListIds = setOf(listId), missed = emptyList(),
            placeReminderCount = 0, calendarEvents = emptyList(), now = now,
        )
        assertTrue(text.contains("REPEATED-DEFERRAL CANDIDATES none flagged"))
    }

    @Test
    fun `a recently touched item that is not yet old enough does not flag`() {
        val items = listOf(task("Fresh but edited", createdAt = now - 2 * day, updatedAt = now - 1 * day))
        val text = LogDigestBuilder.buildDigestText(
            allActive = items, tickableListIds = setOf(listId), missed = emptyList(),
            placeReminderCount = 0, calendarEvents = emptyList(), now = now,
        )
        assertTrue(text.contains("REPEATED-DEFERRAL CANDIDATES none flagged"))
    }

    // ----------------------------------------------------------------------------- place reminders

    @Test
    fun `active place-triggered reminders are named when present`() {
        val text = LogDigestBuilder.buildDigestText(
            allActive = emptyList(), tickableListIds = setOf(listId), missed = emptyList(),
            placeReminderCount = 2, calendarEvents = emptyList(), now = now,
        )
        assertTrue(text.contains("PLACE-TRIGGERED reminders active 2"))
    }

    @Test
    fun `zero active place reminders omits the line entirely`() {
        val text = LogDigestBuilder.buildDigestText(
            allActive = emptyList(), tickableListIds = setOf(listId), missed = emptyList(),
            placeReminderCount = 0, calendarEvents = emptyList(), now = now,
        )
        assertFalse(text.contains("PLACE-TRIGGERED"))
    }
}

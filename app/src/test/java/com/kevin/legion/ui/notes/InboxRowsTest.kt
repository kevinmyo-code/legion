package com.kevin.legion.ui.notes

import com.kevin.legion.backend.EventKind
import com.kevin.legion.data.local.ListItem
import com.kevin.legion.ui.AgendaSource
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises [buildInboxRows] - the one-stream inbox's ordering and labelling (Kevin, 2026-08-11:
 * "1 list, many items appended, all with their own due dates"). Plain JUnit, no Compose/Room/
 * `Context`, same posture as [NotesResolversTest]. All fixtures invented.
 *
 * `now` is passed to [buildInboxRows] rather than read from the clock, so the overdue cases below
 * are deterministic and do not rot as the date moves.
 */
class InboxRowsTest {

    private fun epochOf(date: String, time: LocalTime = LocalTime.MIDNIGHT): Long =
        LocalDate.parse(date).atTime(time).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private val now = epochOf("2026-08-11", LocalTime.NOON)

    @Test
    fun `dated items sort soonest-first ahead of every undated item`() {
        val items = listOf(
            ListItem(id = 1, listId = 1, text = "Buy milk", sortOrder = 0),
            ListItem(id = 2, listId = 1, text = "Renew rego", sortOrder = 1, startsAt = epochOf("2026-09-01")),
            ListItem(id = 3, listId = 1, text = "Oil change", sortOrder = 2, startsAt = epochOf("2026-08-14")),
            ListItem(id = 4, listId = 1, text = "Wash the car", sortOrder = 3),
        )
        // Dated first by due date, then the undated pair in their appended order - an undated item
        // never outranks a dated one even with a lower sortOrder.
        assertEquals(listOf(3L, 2L, 1L, 4L), buildInboxRows(items, now).map { it.id })
    }

    @Test
    fun `items from different lists land in one stream ordered by date alone`() {
        val items = listOf(
            ListItem(id = 1, listId = 7, text = "Pack tent", sortOrder = 0, startsAt = epochOf("2026-08-20")),
            ListItem(id = 2, listId = 99, text = "Pay card", sortOrder = 0, startsAt = epochOf("2026-08-12")),
        )
        assertEquals(listOf(2L, 1L), buildInboxRows(items, now).map { it.id })
    }

    @Test
    fun `an all-day date renders date-only and a timed one renders the time too`() {
        val items = listOf(
            ListItem(id = 1, listId = 1, text = "Oil change", startsAt = epochOf("2026-08-14"), allDay = true),
            ListItem(id = 2, listId = 1, text = "Dentist", startsAt = epochOf("2026-08-15", LocalTime.of(9, 30)), allDay = false),
        )
        val rows = buildInboxRows(items, now)
        assertEquals("Aug 14", rows[0].dateLabel)
        assertEquals("Aug 15, 9:30 AM", rows[1].dateLabel)
    }

    @Test
    fun `an undated item has a null dateLabel and is never overdue`() {
        val rows = buildInboxRows(listOf(ListItem(id = 1, listId = 1, text = "Buy milk")), now)
        assertNull(rows.single().dateLabel)
        assertFalse(rows.single().overdue)
    }

    @Test
    fun `a past-due open item is overdue`() {
        val rows = buildInboxRows(
            listOf(ListItem(id = 1, listId = 1, text = "Oil change", startsAt = epochOf("2026-08-01"))),
            now,
        )
        assertTrue(rows.single().overdue)
        assertNotNull(rows.single().dateLabel)
    }

    @Test
    fun `a past-due item that is done is not overdue`() {
        val rows = buildInboxRows(
            listOf(ListItem(id = 1, listId = 1, text = "Oil change", startsAt = epochOf("2026-08-01"), done = true)),
            now,
        )
        assertFalse(rows.single().overdue)
    }

    @Test
    fun `a recurring item is never overdue and is never tickable`() {
        // Ticket 04: a recurring item cannot be ticked, and it re-arms forward rather than going
        // late - marking one OVERDUE would report a failure that did not happen.
        val rows = buildInboxRows(
            listOf(
                ListItem(
                    id = 1, listId = 1, text = "Water the plants",
                    startsAt = epochOf("2026-08-01"), repeatKind = "DAILY", repeatEvery = 1,
                ),
            ),
            now,
        )
        assertFalse(rows.single().overdue)
        assertFalse(rows.single().tickable)
        assertTrue(rows.single().recurring)
        assertEquals("Repeats - next Aug 1", rows.single().dateLabel)
    }

    @Test
    fun `every non-recurring item is tickable regardless of which list it came from`() {
        // The checklist-vs-note split is gone from this surface: tickability is a per-item fact
        // (recurring or not), never a property inherited from the item's list.
        val items = listOf(
            ListItem(id = 1, listId = 1, text = "From a checklist"),
            ListItem(id = 2, listId = 2, text = "From a note list"),
        )
        assertTrue(buildInboxRows(items, now).all { it.tickable })
    }

    @Test
    fun `a place trigger renders in its own slot and never as a date`() {
        val rows = buildInboxRows(
            listOf(ListItem(id = 1, listId = 1, text = "Buy rope", triggerPlaceLabel = "Camping Store")),
            now,
        )
        assertNull(rows.single().dateLabel)
        assertEquals("At Camping Store", rows.single().placeLabel)
    }

    @Test
    fun `done items stay in the stream rather than vanishing`() {
        val items = listOf(
            ListItem(id = 1, listId = 1, text = "Oil change", startsAt = epochOf("2026-08-14")),
            ListItem(id = 2, listId = 1, text = "Call the bank", startsAt = epochOf("2026-08-12"), done = true),
        )
        val rows = buildInboxRows(items, now)
        assertEquals(listOf(2L, 1L), rows.map { it.id })
        assertTrue(rows.first().done)
    }

    @Test
    fun `an empty input yields no rows`() {
        assertEquals(emptyList<InboxRowView>(), buildInboxRows(emptyList(), now))
    }

    // ---------------------------------------------------- Google Calendar merge (ticket 13 follow-up)

    private fun googleEvent(
        id: Long,
        title: String,
        startMs: Long,
        allDay: Boolean = false,
        done: Boolean = false,
        kind: String = EventKind.EVENT,
    ) = AppointmentEvent(
        eventId = id, title = title, startMs = startMs, endMs = startMs + 1_000L,
        allDay = allDay, done = done, kind = kind,
    )

    @Test
    fun `google events interleave into the dated section by real start time`() {
        val items = listOf(
            ListItem(id = 1, listId = 1, text = "Renew rego", startsAt = epochOf("2026-09-01")),
            ListItem(id = 2, listId = 1, text = "Oil change", startsAt = epochOf("2026-08-14")),
        )
        val google = listOf(googleEvent(10, "Dentist", startMs = epochOf("2026-08-20")))

        val rows = buildInboxRows(items, now, google)

        assertEquals(listOf("Oil change", "Dentist", "Renew rego"), rows.map { it.text })
    }

    @Test
    fun `google events never land in the undated section, even with no local dated items`() {
        val items = listOf(ListItem(id = 1, listId = 1, text = "Buy milk"))
        val google = listOf(googleEvent(10, "Dentist", startMs = epochOf("2026-08-20")))

        val rows = buildInboxRows(items, now, google)

        assertEquals(listOf("Dentist", "Buy milk"), rows.map { it.text })
    }

    @Test
    fun `a google row is tagged GOOGLE, is NOT tickable when it is an event, and never recurring`() {
        // One-today ticket 08, "events are not todos" - REVERSES ticket 02's "an appointment row is
        // tickable now". Kevin: "i dont mark an event done, it just passes whether or not i do it,
        // like classes." A kind = EVENT row (the default here, and every row that used to read
        // `appointment`) must render with no checkbox at all.
        val rows = buildInboxRows(emptyList(), now, listOf(googleEvent(10, "Dentist", startMs = epochOf("2026-08-20"))))

        val row = rows.single()
        assertEquals(AgendaSource.GOOGLE, row.source)
        assertFalse(row.tickable)
        assertFalse(row.recurring)
        assertFalse(row.overdue)
        assertNull(row.placeLabel)
    }

    @Test
    fun `a google row IS tickable when its kind is TASK, not EVENT`() {
        // Ticket 08's other half: the axis is completable-or-not, not "every calendar-table row
        // alike". Nothing writes a TASK row yet (Canvas is its own ticket), but the resolver itself
        // must already treat one as tickable the moment one exists.
        val rows = buildInboxRows(
            emptyList(), now,
            listOf(googleEvent(10, "Submit essay", startMs = epochOf("2026-08-20"), kind = EventKind.TASK)),
        )
        assertTrue(rows.single().tickable)
    }

    @Test
    fun `a reminder row is still tickable - ticket 08 narrows only the calendar-table rows`() {
        val rows = buildInboxRows(listOf(ListItem(id = 1, listId = 1, text = "Call the bank")), now)
        assertTrue(rows.single().tickable)
    }

    @Test
    fun `a local row defaults to LOCAL source`() {
        val rows = buildInboxRows(listOf(ListItem(id = 1, listId = 1, text = "Buy milk")), now)
        assertEquals(AgendaSource.LOCAL, rows.single().source)
    }

    @Test
    fun `a google row carries its own real event id, disjoint from a reminder id by construction`() {
        // One-today ticket 01 retired the old synthetic-negative-id trick along with the live
        // CalendarContract read it protected against - an appointment's Event.id is disjoint from
        // a reminder's BY CONSTRUCTION (Event.APPOINTMENT_ID_BASE), so [id] is now the real id.
        val rows = buildInboxRows(
            listOf(ListItem(id = 1, listId = 1, text = "Oil change", startsAt = epochOf("2026-08-14"))),
            now,
            listOf(googleEvent(100_000_001L, "Dentist", startMs = epochOf("2026-08-20"))),
        )
        assertEquals(setOf(1L, 100_000_001L), rows.map { it.id }.toSet())
    }

    @Test
    fun `an all-day google event renders date-only, a timed one renders the time too`() {
        val rows = buildInboxRows(
            emptyList(), now,
            listOf(
                googleEvent(1, "Kevin's birthday", startMs = epochOf("2026-08-20"), allDay = true),
                googleEvent(2, "Dentist", startMs = epochOf("2026-08-21", LocalTime.of(9, 30)), allDay = false),
            ),
        )
        assertEquals("Aug 20", rows.first { it.text == "Kevin's birthday" }.dateLabel)
        assertEquals("Aug 21, 9:30 AM", rows.first { it.text == "Dentist" }.dateLabel)
    }

    @Test
    fun `no google events is the same result as the two-argument call`() {
        val items = listOf(ListItem(id = 1, listId = 1, text = "Buy milk", startsAt = epochOf("2026-08-14")))
        assertEquals(buildInboxRows(items, now), buildInboxRows(items, now, emptyList()))
    }

    // -------------------------------------------------------------- ticket 22: edit-carrying fields

    @Test
    fun `a google row carries the real eventId, occurrence times and allDay`() {
        val start = epochOf("2026-08-20")
        val rows = buildInboxRows(
            emptyList(), now,
            listOf(googleEvent(42, "Dentist", startMs = start, allDay = true)),
        )
        val row = rows.single()
        assertEquals(42L, row.calendarEventId)
        assertEquals(start, row.calendarOccurrenceStartMs)
        assertEquals(start + 1_000L, row.calendarOccurrenceEndMs)
        assertEquals(true, row.calendarAllDay)
    }

    @Test
    fun `a google row is never recurring - one-today ticket 01 retired the live RRULE fact`() {
        val rows = buildInboxRows(
            emptyList(), now,
            listOf(googleEvent(1, "Mara's bday", startMs = epochOf("2026-08-20"))),
        )
        assertFalse(rows.single().recurring)
    }

    @Test
    fun `a google row's done state carries through from the stored Event`() {
        val rows = buildInboxRows(
            emptyList(), now,
            listOf(googleEvent(1, "Dentist", startMs = epochOf("2026-08-20"), done = true)),
        )
        assertTrue(rows.single().done)
    }

    @Test
    fun `a local row carries no calendar edit fields at all`() {
        val rows = buildInboxRows(listOf(ListItem(id = 1, listId = 1, text = "Buy milk")), now)
        val row = rows.single()
        assertNull(row.calendarEventId)
        assertNull(row.calendarOccurrenceStartMs)
        assertNull(row.calendarOccurrenceEndMs)
        assertNull(row.calendarAllDay)
    }
}

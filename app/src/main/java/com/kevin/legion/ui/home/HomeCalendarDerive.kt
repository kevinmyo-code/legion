@file:Suppress("MatchingDeclarationName") // the derive functions are the file's point; MonthRead is their input
package com.kevin.legion.ui.home

import com.kevin.legion.backend.EventKind
import com.kevin.legion.calendar.EventSuggestions
import com.kevin.legion.data.local.Event
import com.kevin.legion.data.local.ListItem
import com.kevin.legion.ui.AgendaSource
import com.kevin.legion.ui.common.dailyBuckets
import com.kevin.legion.ui.notes.InboxRowView
import com.kevin.legion.ui.notes.buildInboxRows
import com.kevin.legion.ui.notes.buildMonthOpenTodoCounts
import com.kevin.legion.ui.notes.toAppointmentEvent
import com.kevin.legion.util.clockTime
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/**
 * What ONE read of a month returned (2026-10-09, HOME speed pass): the window's events, tasks and
 * suggestions together in [rows] (mixed kinds, one query), plus the reminders. Everything HOME shows
 * for the month AND for any day inside it is derived from this by the pure functions below, off the
 * main thread, with no further query. [unreadable] names the kinds whose read failed, as in
 * [MonthMarkers].
 */
data class MonthRead(
    val month: YearMonth,
    val rows: List<Event>,
    val reminders: List<ListItem>,
    val unreadable: List<String> = emptyList(),
)

// The three splits below filter by kind EXPLICITLY. One query returns all three kinds, so a position
// or a "the rest" split would let a suggestion be counted as a plan (EventReadsAreKindFilteredTest).
internal fun MonthRead.events(): List<Event> = rows.filter { it.kind == EventKind.EVENT }

internal fun MonthRead.tasks(): List<Event> = rows.filter { it.kind == EventKind.TASK }

internal fun MonthRead.suggestions(): List<Event> = rows.filter { it.kind == EventKind.SUGGESTION }

/** The reminders plus the month's tasks as the day view's own merged rows (`buildInboxRows`), so HOME
 * and the calendar can never disagree about what is a to-do. */
internal fun inboxOf(read: MonthRead, now: Long): List<InboxRowView> =
    buildInboxRows(read.reminders, now, read.tasks().map(Event::toAppointmentEvent))

internal fun deriveMonthMarkers(read: MonthRead, inbox: List<InboxRowView>, zone: ZoneId): MonthMarkers {
    val dayStarts = dailyBuckets(
        read.month.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli(),
        read.month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1,
        zone,
    )
    // A blue dot always has an Event row behind it; a reminder is a to-do here and marked as one.
    val events = read.events().mapNotNull { EventSuggestions.localDayStart(it, zone) }
        .groupingBy { it }.eachCount()
    val todos = dayStarts.zip(buildMonthOpenTodoCounts(inbox, dayStarts, zone)).toMap()
    val suggestions = EventSuggestions.countsByDay(read.suggestions(), dayStarts, zone)
    val byDay = dayStarts.associate { dayStart ->
        val date = Instant.ofEpochMilli(dayStart).atZone(zone).toLocalDate()
        date to DayMarkers(events[dayStart] ?: 0, todos[dayStart] ?: 0, suggestions[dayStart] ?: 0)
    }
    return MonthMarkers(byDay, read.unreadable)
}

/** One day's agenda from the month already read: no query. [day] must lie inside [MonthRead.month]. */
internal fun deriveDay(read: MonthRead, inbox: List<InboxRowView>, day: LocalDate, zone: ZoneId): DayRead {
    val from = day.atStartOfDay(zone).toInstant().toEpochMilli()
    val to = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
    val rows = mutableListOf<Pair<AgendaRowUi, Long?>>()

    read.events().filter { EventSuggestions.localDayStart(it, zone) == from }.forEach { event ->
        rows += AgendaRowUi(
            key = "event-${event.id}",
            kind = AgendaKind.EVENT,
            whenLabel = whenLabel(event),
            title = event.title,
            typeLabel = "Event",
        ) to event.startsAt?.takeUnless { event.allDay }
    }
    inbox.filter { it.instantMs != null && it.instantMs >= from && it.instantMs <= to }.forEach { row ->
        rows += AgendaRowUi(
            key = "todo-${row.source}-${row.id}",
            kind = AgendaKind.TODO,
            whenLabel = dueLabel(row, from),
            title = row.text,
            typeLabel = "To-do",
            reminderId = row.id.takeIf { row.source == AgendaSource.LOCAL },
            done = row.done,
        ) to row.instantMs
    }
    read.suggestions().filter { EventSuggestions.localDayStart(it, zone) == from }
        .sortedBy { it.startsAt ?: Long.MAX_VALUE }
        .forEach { s ->
            rows += AgendaRowUi(
                key = "suggestion-${s.id}",
                kind = AgendaKind.SUGGESTION,
                whenLabel = whenLabel(s),
                title = s.title,
                typeLabel = "Suggestion · not a plan",
            ) to s.startsAt?.takeUnless { s.allDay }
        }
    return DayRead(orderAgenda(rows), read.unreadable)
}

private fun whenLabel(event: Event): String {
    val at = event.startsAt
    return if (event.allDay || at == null) "All day" else clockTime(at)
}

/** "Due" for a date-only reminder or task, "Due 9:00 AM" when it has a time. */
private fun dueLabel(row: InboxRowView, dayStartMs: Long): String {
    val at = row.instantMs
    return if (at == null || at == dayStartMs || row.calendarAllDay == true) "Due" else "Due ${clockTime(at)}"
}

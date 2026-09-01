package com.kevin.legion.ui.agenda

import android.content.Context
import com.kevin.legion.backend.EventKind
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.notes.NotesController
import com.kevin.legion.notes.Recurrence
import com.kevin.legion.notes.endFromItem
import com.kevin.legion.notes.ruleFromItem
import com.kevin.legion.ui.AgendaEntry
import com.kevin.legion.ui.notes.mergeAgenda
import com.kevin.legion.ui.notes.toAppointmentEvent
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/**
 * The one merge previously restated verbatim in three places - `ui/TodayScreen.kt`'s AGENDA pane
 * and `ui/NotesScreen.kt`'s "today" and "month" builds, all three doing the same
 * [NotesController.timedItemsInWindow] + [NotesController.allRecurringItems]/
 * [Recurrence.occurrencesInWindow] + `db.eventDao().activeByKindInWindow` triple, windowed
 * differently, then folded together with [mergeAgenda]. NotesScreen's own "today" build said, in
 * its own comment, that it was "restated here... rather than shared" because Kotlin top-level
 * `private` is file-scoped, not package-scoped (see that file's `TodayPane` doc comment on the same
 * gap for `AgendaRow`) - this file is the shared home that comment was waiting for. Extracted as a
 * pure ticket: no window, filter, or ordering behaviour changed from any of the three sites it
 * replaces (confirmed by reading all three before writing this).
 *
 * [EventDao.activeByKindInWindow] is already generic over any `[fromMs, toMs]` window, so no new
 * query was added here - only the caller-side assembly around it moved.
 */
suspend fun buildAgendaInWindow(context: Context, fromMs: Long, toMs: Long, zone: ZoneId): List<AgendaEntry> {
    val oneOff = NotesController.timedItemsInWindow(context, fromMs, toMs)
        .filter { !it.done }
        .mapNotNull { item -> item.startsAt?.let { AgendaEntry(item.text, it, item.allDay) } }
    val recurring = NotesController.allRecurringItems(context).flatMap { item ->
        val startsAt = item.startsAt
        val rule = startsAt?.let { ruleFromItem(item) }
        if (startsAt == null || rule == null) {
            emptyList()
        } else {
            val skips = NotesController.skippedDates(context, item)
            Recurrence.occurrencesInWindow(startsAt, rule, endFromItem(item), skips, fromMs, toMs)
                .map { occMs -> AgendaEntry(item.text, occMs, item.allDay) }
        }
    }
    val appointments = CarDatabase.getDatabase(context).eventDao()
        .activeByKindInWindow(EventKind.APPOINTMENT, fromMs, toMs)
        .map { it.toAppointmentEvent() }
    return mergeAgenda(oneOff + recurring, appointments)
}

/** [buildAgendaInWindow] over exactly [date], device-zone midnight to the last millisecond before
 * the next midnight - the window `ui/TodayScreen.kt`'s AGENDA pane and NotesScreen's "today" build
 * both used inline. */
suspend fun buildDayAgenda(context: Context, date: LocalDate, zone: ZoneId): List<AgendaEntry> {
    val dayStart = date.atStartOfDay(zone).toInstant().toEpochMilli()
    val dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
    return buildAgendaInWindow(context, dayStart, dayEnd, zone)
}

/** [buildAgendaInWindow] over the whole of [month] - the window NotesScreen's "month" build used
 * inline for its calendar grid's dot counts. */
suspend fun buildMonthAgenda(context: Context, month: YearMonth, zone: ZoneId): List<AgendaEntry> {
    val monthStart = month.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val monthEnd = month.atEndOfMonth().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
    return buildAgendaInWindow(context, monthStart, monthEnd, zone)
}

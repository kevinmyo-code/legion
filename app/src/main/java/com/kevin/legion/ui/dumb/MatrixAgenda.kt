package com.kevin.legion.ui.dumb

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/** What a calendar row is. An event is never ticked ("it just passes"); a task or reminder is. */
internal enum class MatrixAgendaKind { EVENT, TASK, REMINDER }

/**
 * One row of the Matrix calendar, already read out of Room and flattened so the text layer needs no
 * Android types. [allDay] means different things by kind, exactly as in the Soft calendar: an event's or
 * task's all-day [startMs] is UTC midnight of its date, a reminder's is local midnight ([day] applies it).
 */
internal data class MatrixAgendaRow(
    val id: Long,
    val kind: MatrixAgendaKind,
    val title: String,
    val startMs: Long?,
    val endMs: Long?,
    val allDay: Boolean,
    val done: Boolean = false,
    val tickable: Boolean = false,
    val overdue: Boolean = false,
    val repeats: Boolean = false,
) {
    /** The calendar date this row sits on, or null when it states no time. */
    fun day(zone: ZoneId): LocalDate? {
        val at = startMs ?: return null
        val utcDate = allDay && kind != MatrixAgendaKind.REMINDER
        return Instant.ofEpochMilli(at).atZone(if (utcDate) ZoneOffset.UTC else zone).toLocalDate()
    }
}

/** The start, end and all-day flag a form parsed into: what a save writes. */
internal data class MatrixEventTimes(val startMs: Long, val endMs: Long, val allDay: Boolean)

/** The data seam under the Matrix calendar. The real one is over Room and the controllers; tests fake it. */
internal interface MatrixCalendarSource {
    /** Rows on `[from, toExclusive)` in [zone]. Throws when Room cannot be read; never returns a guess. */
    suspend fun agenda(from: LocalDate, toExclusive: LocalDate, zone: ZoneId): List<MatrixAgendaRow>

    /** Adds an event ([id] null) or edits one. True only when the write was accepted. */
    suspend fun saveEvent(id: Long?, title: String, times: MatrixEventTimes): Boolean

    suspend fun deleteEvent(id: Long): Boolean

    /** Ticks or unticks a task or reminder through the same funnels the Soft calendar uses. */
    suspend fun setDone(row: MatrixAgendaRow, done: Boolean): Boolean
}

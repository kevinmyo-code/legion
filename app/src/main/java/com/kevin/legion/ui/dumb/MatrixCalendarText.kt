package com.kevin.legion.ui.dumb

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One day of the Matrix calendar: its heading and the rows that fall on it. */
internal data class MatrixAgendaDay(val date: LocalDate, val heading: String, val rows: List<MatrixAgendaRow>)

/**
 * The words on the Matrix calendar (matrix-open ticket 04), pure so every state is tested: loading,
 * unreadable (said apart from a clear week), and an honestly empty day. A day says "Nothing on this
 * day." only when the read SUCCEEDED; a failed read replaces the whole list with [UNREADABLE].
 */
internal object MatrixCalendarText {
    const val LOADING = "Reading your calendar..."
    const val UNREADABLE = "Couldn't read the calendar. This is not a clear week: nothing could be read."
    const val NOTHING = "Nothing on this day."
    const val DAYS_SHOWN = 8

    private val HEADING_DATE = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)

    /** Day order, then all-day before timed, then by start. Undated rows (none are read) would sort last. */
    fun ordered(rows: List<MatrixAgendaRow>, zone: ZoneId): List<MatrixAgendaRow> =
        rows.sortedWith(compareBy<MatrixAgendaRow> { it.day(zone) }.thenBy { !it.allDay }.thenBy { it.startMs })

    /** The sentence above the list, or null when the days speak for themselves. */
    fun note(state: MatrixCalendarUiState): String? = when {
        state.loading -> LOADING
        state.readFailed -> UNREADABLE
        else -> null
    }

    /** Today and the next seven days. Empty when the read failed or has not landed, never eight blank days. */
    fun days(state: MatrixCalendarUiState, zone: ZoneId): List<MatrixAgendaDay> {
        if (state.loading || state.readFailed) return emptyList()
        return (0 until DAYS_SHOWN).map { offset ->
            val date = state.today.plusDays(offset.toLong())
            MatrixAgendaDay(date, heading(date, state.today), state.rows.filter { it.day(zone) == date })
        }
    }

    fun heading(date: LocalDate, today: LocalDate): String {
        val label = date.format(HEADING_DATE)
        return when (date) {
            today -> "Today - $label"
            today.plusDays(1) -> "Tomorrow - $label"
            else -> label
        }
    }

    private fun clock(row: MatrixAgendaRow, zone: ZoneId): String {
        val at = row.startMs
        return if (row.allDay || at == null) {
            "all day"
        } else {
            Instant.ofEpochMilli(at).atZone(zone).toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm"))
        }
    }

    /** The row's whole first line: events as "09:00  Title", tickables with a [ ] or [x] in front. */
    fun rowLabel(row: MatrixAgendaRow, zone: ZoneId): String {
        val box = when {
            row.kind == MatrixAgendaKind.EVENT -> ""
            row.done -> "[x] "
            else -> "[ ] "
        }
        return "$box${clock(row, zone)}  ${row.title}"
    }

    /** The second line: what kind of row it is, and the states said in words (overdue, repeats). */
    fun rowDetail(row: MatrixAgendaRow): String? {
        val parts = listOfNotNull(
            when (row.kind) {
                MatrixAgendaKind.EVENT -> "tap to edit"
                MatrixAgendaKind.TASK -> "task"
                MatrixAgendaKind.REMINDER -> "reminder"
            },
            if (row.overdue) "Overdue" else null,
            if (row.repeats) "Repeats, so it can't be ticked off here" else null,
        )
        return parts.joinToString(" - ").ifEmpty { null }
    }

    fun rowTone(row: MatrixAgendaRow): MatrixTone = when {
        row.overdue -> MatrixTone.WARNING
        row.done -> MatrixTone.DIM
        else -> MatrixTone.HEADING
    }
}

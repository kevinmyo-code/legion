package com.kevin.legion.ui.dumb

import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * The add/edit-event form (matrix-open ticket 04) as plain strings, because the Matrix screen has no
 * pickers: a date typed as 2026-10-24 and a time as 14:30, or no time for an all-day event. [parse]
 * turns it into what a save writes, or a sentence saying what is wrong and that nothing was saved.
 * [id] null is a new event. [durationMs] keeps an edited event's own length; a new timed event is an hour.
 */
internal data class MatrixEventForm(
    val id: Long?,
    val title: String,
    val date: String,
    val time: String,
    val durationMs: Long = DEFAULT_DURATION_MS,
    val error: String? = null,
    val confirmingDelete: Boolean = false,
) {
    sealed interface Parsed {
        data class Ok(val title: String, val times: MatrixEventTimes) : Parsed

        data class Bad(val sentence: String) : Parsed
    }

    fun parse(zone: ZoneId): Parsed {
        val cleanTitle = title.trim()
        val day = runCatching { LocalDate.parse(date.trim()) }.getOrNull()
        val clock = time.trim()
        val at = if (clock.isEmpty()) null else runCatching { LocalTime.parse(clock) }.getOrNull()
        return when {
            cleanTitle.isEmpty() -> Parsed.Bad("Give the event a title. Nothing was saved.")
            day == null -> Parsed.Bad("The date must look like 2026-10-24. Nothing was saved.")
            clock.isNotEmpty() && at == null ->
                Parsed.Bad("The time must look like 14:30, or be left empty for an all-day event. Nothing was saved.")
            at == null -> {
                val start = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
                Parsed.Ok(cleanTitle, MatrixEventTimes(start, start + Duration.ofDays(1).toMillis(), allDay = true))
            }
            else -> {
                val start = LocalDateTime.of(day, at).atZone(zone).toInstant().toEpochMilli()
                val length = if (durationMs > 0) durationMs else DEFAULT_DURATION_MS
                Parsed.Ok(cleanTitle, MatrixEventTimes(start, start + length, allDay = false))
            }
        }
    }

    companion object {
        const val DEFAULT_DURATION_MS = 60L * 60L * 1000L

        fun blank(day: LocalDate) = MatrixEventForm(id = null, title = "", date = day.toString(), time = "")

        /** The form for an existing event row, in the same conventions [parse] writes back. */
        fun of(row: MatrixAgendaRow, zone: ZoneId): MatrixEventForm {
            val day = row.day(zone) ?: LocalDate.now(zone)
            val clock = if (row.allDay || row.startMs == null) {
                ""
            } else {
                java.time.Instant.ofEpochMilli(row.startMs).atZone(zone).toLocalTime().withSecond(0).withNano(0)
                    .toString()
            }
            val length = if (row.startMs != null && row.endMs != null) row.endMs - row.startMs else 0L
            return MatrixEventForm(row.id, row.title, day.toString(), clock, length)
        }
    }
}

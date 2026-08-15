package com.kevin.legion.calendar

import java.time.LocalDate
import java.time.ZoneId

/**
 * Pure logic behind `read_calendar` (ticket 19,
 * `.scratch/google-account-integration/issues/19-calendar-read-tool.md`). No Android types -
 * `service/LiveToolbox.kt`'s dispatch calls [parseWindow] to turn the model's `from`/`to` args
 * into a millis window, then calls [CalendarProvider.eventsInWindow] itself (ticket 19 point 2:
 * reuse that query, never write a second one). Matches the "pure builder, thin dispatch" split
 * ticket 05 already set for Gmail ([com.kevin.legion.gmail.GmailToolLogic]).
 */
object CalendarReadToolLogic {

    /**
     * The exact line Alfred says when `READ_CALENDAR` is refused or was never granted - ticket 19
     * point 3. A foreground service has no Activity to raise a permission dialog from, so this
     * sentence is the whole answer, and it must NEVER be paired with an empty event list: an empty
     * window there would read as "you have nothing on", the same failure shape
     * `memory/MEMORY.md`'s L15 note is about and ticket 13/17 already guard against on screen.
     */
    const val PERMISSION_MISSING_MESSAGE =
        "I don't have permission to read your calendar yet. Grant calendar access from the " +
            "Today screen to let me see it."

    /** Said when `from`/`to` do not parse or `to` is before `from` - a malformed window is a
     * spoken failure, never a silent guess at one. */
    const val INVALID_WINDOW_MESSAGE =
        "I need a valid date range to read your calendar - from and to as yyyy-MM-dd, with to on " +
            "or after from."

    /**
     * Parses `from`/`to` (yyyy-MM-dd, the same date shape `manage_item`'s own `date` param already
     * uses) into a `[startMs, endMs)` window in [zone]: midnight of `from` through the START of the
     * day AFTER `to`, matching `ui/TodayScreen.kt`'s own `dayStart`/`dayEnd` convention for
     * [CalendarProvider.eventsInWindow] exactly - a single-day request (`from == to`) still covers
     * that whole day rather than collapsing to a zero-width window. Null on a malformed date or
     * `to` before `from`.
     */
    fun parseWindow(from: String, to: String, zone: ZoneId): Pair<Long, Long>? {
        val fromDate = runCatching { LocalDate.parse(from) }.getOrNull() ?: return null
        val toDate = runCatching { LocalDate.parse(to) }.getOrNull() ?: return null
        if (toDate.isBefore(fromDate)) return null
        val startMs = fromDate.atStartOfDay(zone).toInstant().toEpochMilli()
        val endMs = toDate.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
        return startMs to endMs
    }
}

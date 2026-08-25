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

    /** The first line of a machine-readable description block, and the line that closes it. An
     * event whose `DESCRIPTION` opens with [META_SENTINEL] is carrying structured fields for
     * Alfred; everything from [META_TERMINATOR] onward is prose written for Kevin's eyes and is
     * deliberately NOT sent to the model - `read_calendar` returns the whole window in one payload,
     * so shipping every event's paragraphs would spend context on text no tool call needs. */
    const val META_SENTINEL = "LEGION::v1"
    private const val META_TERMINATOR = "---"

    /**
     * The `key: value` lines between [META_SENTINEL] and [META_TERMINATOR], or null when [description]
     * carries no block - an ordinary hand-typed calendar entry returns null and costs nothing.
     *
     * Values stay verbatim strings: this parser never coerces types, because a mis-parsed `due` is
     * worse than an unparsed one, and the model reads these as text regardless. A `source: inferred`
     * field is the whole point of the block - it is how an event says its own date was never stated
     * by any authority, so Alfred can label it an estimate instead of asserting it. Keys repeat at
     * most once; a duplicate key keeps the FIRST occurrence rather than silently overwriting.
     */
    fun structuredBlock(description: String): Map<String, String>? {
        val lines = description.lineSequence().map { it.trim() }.toList()
        if (lines.firstOrNull() != META_SENTINEL) return null
        val out = LinkedHashMap<String, String>()
        for (line in lines.drop(1)) {
            if (line == META_TERMINATOR) break
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            val key = line.substring(0, idx).trim()
            val value = line.substring(idx + 1).trim()
            if (key.isNotEmpty() && value.isNotEmpty()) out.putIfAbsent(key, value)
        }
        return out.takeIf { it.isNotEmpty() }
    }

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

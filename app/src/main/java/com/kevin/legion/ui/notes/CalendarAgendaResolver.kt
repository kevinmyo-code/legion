package com.kevin.legion.ui.notes

import com.kevin.legion.calendar.CalendarProvider.GoogleCalendarEvent
import com.kevin.legion.meals.dayStartEpoch
import com.kevin.legion.ui.AgendaEntry
import com.kevin.legion.ui.AgendaSource
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.TextStyle
import java.util.Locale

/**
 * Pure merge/sort/empty-state logic behind the deck home's AGENDA pane once a Google Calendar
 * source joins the local one (ticket 13, `.scratch/google-account-integration/issues/13-*`). No
 * Android types - [com.kevin.legion.calendar.CalendarProvider] is the Android-bound half that
 * actually queries `CalendarContract`; `ui/TodayScreen.kt`'s `LaunchedEffect` calls that, then
 * hands the plain [GoogleCalendarEvent] list here to combine with the local
 * [AgendaEntry] list it already built. Every branch below is a plain JUnit test
 * (`CalendarAgendaResolverTest`), matching this domain's existing "pure builder, thin Composable
 * wrapper" split ([com.kevin.legion.ui.notes.buildInboxRows]/[com.kevin.legion.ui.notes.buildMissedRows]).
 *
 * **No recurrence math lives here, or anywhere else in LEGION, for the Google side.** Ticket 02's
 * answer: `Instances` already expands a series into one row per occurrence in the queried window,
 * so [mergeAgenda] only ever combines and sorts what it is handed - re-deriving an occurrence from
 * an `RRULE` string would be exactly the translation layer ticket 04's answer rules out.
 */

/**
 * Local [AgendaEntry] rows plus a Google-sourced entry per [GoogleCalendarEvent], all in one
 * ascending-by-start list. [local] is assumed already windowed and skip-subtracted by the caller
 * (`ui/TodayScreen.kt`'s existing `timedItemsInWindow`/`allRecurringItems` +
 * `Recurrence.occurrencesInWindow` pair, unchanged by this ticket) - [googleEvents] must be queried
 * over the SAME window for the merge to mean anything, but this function does not itself check
 * that; it only combines what it is given.
 */
fun mergeAgenda(local: List<AgendaEntry>, googleEvents: List<GoogleCalendarEvent>): List<AgendaEntry> =
    mergeByTime(local.map { it.timeMs to it }, googleEvents) { event ->
        AgendaEntry(label = event.title, timeMs = event.startMs, allDay = event.allDay, source = AgendaSource.GOOGLE)
    }

/**
 * The chronological interleave [mergeAgenda] performs, generalized over the caller's own row type so
 * `ui/notes/InboxScreen.kt`'s [InboxRowView] stream can share the identical merge/sort instead of
 * forking a second one (ticket 13 follow-up, `.scratch/google-account-integration/issues/
 * 13-calendar-read.md`, Kevin 2026-08-13: "InboxScreen carries Google events too"). [local] pairs
 * each already-built row with the real instant it sorts on - [InboxRowView] only keeps a FORMATTED
 * [InboxRowView.dateLabel] string (see that type's own doc comment on why a date needs its own
 * slot), which cannot be sorted correctly, so the raw millis travel alongside the row through this
 * function rather than living on the row type itself. [fromGoogle] builds one caller-shaped row per
 * [GoogleCalendarEvent], the same job [mergeAgenda] does inline for [AgendaEntry].
 */
fun <T> mergeByTime(
    local: List<Pair<Long, T>>,
    googleEvents: List<GoogleCalendarEvent>,
    fromGoogle: (GoogleCalendarEvent) -> T,
): List<T> {
    val converted = googleEvents.map { it.startMs to fromGoogle(it) }
    return (local + converted).sortedBy { it.first }.map { it.second }
}

/**
 * What the AGENDA pane says about its own Google Calendar coverage, kept separate from whether the
 * row LIST itself is empty - ticket 13 point 7's "must never render an empty day that reads as
 * 'you have nothing on'" (the same failure shape as `memory/MEMORY.md`'s L15 note, applied here): a
 * denied `READ_CALENDAR` permission means the day might not be empty at all, merely UNREAD, and
 * that is a different fact from a day that was fully read and is genuinely empty. Both facts can be
 * true at once (permission refused AND zero local entries), so this is worded independently of
 * [AgendaEntry] count rather than folded into one empty-state string.
 */
data class AgendaCalendarNotice(
    /** Null when there is nothing to say - permission is granted, so the row list already speaks
     * for itself. Non-null is shown as its own line/prompt regardless of whether [AgendaEntry]s
     * are present, because a driver with three local reminders showing still cannot tell from that
     * alone whether a fourth, Google-owned appointment is silently missing. */
    val message: String?,
    /** True only when [message] is null (permission granted) AND the merged list is empty - the
     * one case where "NOTHING SCHEDULED" is an honest claim rather than a guess about a source this
     * screen was never allowed to read. */
    val showNothingScheduled: Boolean,
)

private const val CALENDAR_NOT_LINKED_MESSAGE =
    "Calendar not linked - grant access to see Google events here too."

/**
 * Resolves [AgendaCalendarNotice] from the one fact that decides it: whether `READ_CALENDAR` is
 * currently granted. [entryCount] only matters when it is - see that field's own doc comment for
 * why permission state and emptiness are never collapsed into a single check.
 */
fun buildAgendaCalendarNotice(calendarPermissionGranted: Boolean, entryCount: Int): AgendaCalendarNotice =
    if (!calendarPermissionGranted) {
        AgendaCalendarNotice(message = CALENDAR_NOT_LINKED_MESSAGE, showNothingScheduled = false)
    } else {
        AgendaCalendarNotice(message = null, showNothingScheduled = entryCount == 0)
    }

// ------------------------------------------------------------ quant-viz ticket 13: WEEK AHEAD

/**
 * Quant-viz ticket 13's Notes-tab WEEK AHEAD strip: one count per local day, [dayStarts] (index-
 * aligned with the returned list) already built by the caller with
 * [com.kevin.legion.ui.common.dailyBuckets] over today's local date through six days out - this
 * function only groups [entries] into those buckets, it does not decide the window.
 *
 * **Zero is a genuine zero here (ticket 13 point 2), never a gap.** Unlike a health metric where
 * an unlogged day is a fact this app cannot claim to know, a day with no agenda item is a fact the
 * app DOES know - [entries] is the SAME merged local+Google agenda stream
 * [mergeAgenda]/[buildAgendaCalendarNotice] already produce for [com.kevin.legion.ui.TodayScreen]'s
 * single-day AGENDA pane and `InboxScreen`'s stream, just windowed over seven days instead of one -
 * so an empty bucket here means "nothing scheduled that day", not "coverage unknown". This is why
 * the return type is `List<Int>`, never `List<Int?>`: the gap-vs-zero distinction the chart kit
 * enforces elsewhere has nothing to represent on this particular screen, because this screen's one
 * source of truth (the local DB plus, when linked, Google) is either fully queried for the whole
 * window or not queried at all - see this file's other doc comment on why a denied `READ_CALENDAR`
 * is a caller-level "do not call this function, show [CalendarNotLinkedRow] instead" decision
 * rather than a per-day null this function could produce.
 *
 * A [AgendaEntry] whose [AgendaEntry.timeMs] falls inside a local day but outside every entry in
 * [dayStarts] (i.e. a recurrence math bug or a Google event outside the queried window) is silently
 * excluded from every bucket rather than fabricating an eighth slot - the caller is responsible for
 * having queried exactly `[dayStarts.first(), dayStarts.last() + 1 day)`, same contract
 * [com.kevin.legion.ui.common.bucketDailySumCents] already holds its callers to.
 */
/**
 * The local day an [AgendaEntry] belongs in, as a device-zone day-start key index-aligned with the
 * `dayStarts` [buildWeekAheadDayCounts] is handed and the `dayStart` [entriesForDay] filters on.
 *
 * **The two halves of this merged stream do not anchor an all-day entry the same way, and treating
 * them alike put every Google all-day appointment one day early on the month grid for anyone west
 * of UTC.** Found 2026-08-18 on-device: two appointments spoken for 18 August, correct in the row
 * list and in Google Calendar itself, rendered on 17 August in the grid.
 *
 * - A [AgendaSource.GOOGLE] all-day event's `timeMs` is **UTC midnight** of its calendar date -
 *   Android's `CalendarContract` all-day convention, written that way deliberately by
 *   [com.kevin.legion.calendar.CalendarProvider.insertEvent] and read back unmodified. Reading it
 *   through the device zone at UTC-5 yields 19:00 the PREVIOUS day, hence the off-by-one. The date
 *   is recovered through [ZoneOffset.UTC] and only THEN re-anchored to the local day-start the
 *   caller's buckets use. `ui/notes/InboxScreen.kt`'s `epochToZonedLocalDate` is the same rule.
 * - Everything else - every [AgendaSource.LOCAL] row, all-day or timed - is a genuine device-zone
 *   instant (`LiveToolbox.addAppointment`'s `fallbackStartsAt`, `NotesController.addItemDue`), so
 *   it keeps [dayStartEpoch] unchanged. Reading a LOCAL all-day row through UTC would break it in
 *   the opposite direction, which is why this branches on the SOURCE and not on [AgendaEntry.allDay]
 *   alone.
 */
fun agendaDayStart(entry: AgendaEntry, zone: ZoneId = ZoneId.systemDefault()): Long =
    if (entry.allDay && entry.source == AgendaSource.GOOGLE) {
        Instant.ofEpochMilli(entry.timeMs).atZone(ZoneOffset.UTC).toLocalDate()
            .atStartOfDay(zone).toInstant().toEpochMilli()
    } else {
        dayStartEpoch(entry.timeMs, zone)
    }

fun buildWeekAheadDayCounts(entries: List<AgendaEntry>, dayStarts: List<Long>, zone: ZoneId = ZoneId.systemDefault()): List<Int> {
    val grouped = entries.groupBy { agendaDayStart(it, zone) }
    return dayStarts.map { day -> grouped[day]?.size ?: 0 }
}

/**
 * The single-letter day-of-week stamp under each WEEK AHEAD bar (ticket 13 point 3: "day letters in
 * a plain stamp Row... no ui/common change" - this is that plain text, computed once as a pure
 * function so the Composable stamp row is a bare `Text` loop with no date math of its own).
 * [java.time.DayOfWeek.getDisplayName] with [TextStyle.NARROW] already returns exactly one
 * character per ICU (e.g. "M", "T", "W" - and both Tuesday and Thursday narrow to "T", which is
 * intentional: a driver reading a stamp row cross-references it against "today's" highlighted bar,
 * not against the letter in isolation).
 */
fun dayOfWeekLetter(dayStartMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    Instant.ofEpochMilli(dayStartMs).atZone(zone).dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.ENGLISH)

// -------------------------------------------------------------------- quant-viz ticket 14: MONTH

/**
 * One cell of the LOG tab's month calendar grid (ticket 14, replacing the WEEK AHEAD strip -
 * Kevin, 2026-08-14: "lets make it a calendar with events on it"). [dayStart] is null for a
 * leading/trailing slot that belongs to the previous or next month - [buildMonthCells] renders
 * those as empty rather than showing a neighbouring month's day number, so a driver glancing at
 * the grid is never misled into thinking a stray "30" belongs to the displayed month.
 */
data class MonthCell(val dayStart: Long?, val dayOfMonth: Int?, val eventCount: Int)

/**
 * The displayed [month]'s cells, always a multiple of seven (leading blanks + the month's own days
 * + trailing blanks, padded out to whole weeks). [counts] is keyed by the SAME local day-start
 * epoch [buildWeekAheadDayCounts] already buckets into - the caller builds it by zipping that
 * function's `dayStarts`/`List<Int>` pair into a map, so this function does no counting of its own
 * (ticket 14: "do not write a second counting rule").
 *
 * Column order follows [java.time.temporal.WeekFields.of]'s locale-derived
 * [java.time.DayOfWeek] rather than a hardcoded Monday-or-Sunday start, matching whatever order a
 * caller's own weekday-letter header uses for the same locale.
 */
fun buildMonthCells(month: YearMonth, counts: Map<Long, Int>, zone: ZoneId = ZoneId.systemDefault()): List<MonthCell> {
    val firstDayOfWeek = java.time.temporal.WeekFields.of(Locale.getDefault()).firstDayOfWeek
    val firstOfMonth = month.atDay(1)
    // How many blank slots precede day 1 - the gap between the grid's own first column
    // ([firstDayOfWeek]) and the weekday day 1 actually falls on, wrapped into [0, 6].
    val leading = (firstOfMonth.dayOfWeek.value - firstDayOfWeek.value + 7) % 7
    val daysInMonth = month.lengthOfMonth()
    val trailing = (7 - (leading + daysInMonth) % 7) % 7

    val cells = ArrayList<MonthCell>(leading + daysInMonth + trailing)
    repeat(leading) { cells.add(MonthCell(dayStart = null, dayOfMonth = null, eventCount = 0)) }
    for (day in 1..daysInMonth) {
        val dayStart = month.atDay(day).atStartOfDay(zone).toInstant().toEpochMilli()
        cells.add(MonthCell(dayStart = dayStart, dayOfMonth = day, eventCount = counts[dayStart] ?: 0))
    }
    repeat(trailing) { cells.add(MonthCell(dayStart = null, dayOfMonth = null, eventCount = 0)) }
    return cells
}

/**
 * Density-only dots for a cell's [eventCount] (ticket 14): 0 events draws no dot, 1-2 draws one,
 * 3-4 draws two, 5+ draws three. **Never source or importance** - the words for those live in the
 * list below the grid, which is what keeps this satisfying CLAUDE.md §4 rule 5 (nothing meaningful
 * is carried by a glyph alone, because the same information is stated in words elsewhere).
 */
fun eventDotCount(eventCount: Int): Int = when {
    eventCount <= 0 -> 0
    eventCount <= 2 -> 1
    eventCount <= 4 -> 2
    else -> 3
}

// --------------------------------------------------------------- quant-viz ticket 16: DAY POPUP

/**
 * [entries] falling on the local day starting at [dayStart] (ticket 16: tapping a dotted day pops
 * up what is on it). **[entries] must be the SAME `merged` list [buildWeekAheadDayCounts] bucketed
 * to produce the dots the driver just tapped** - this function buckets by the identical
 * [dayStartEpoch] rule that one already uses, so a day's dot count and this function's returned
 * size can never disagree; that agreement is the whole point (ticket 16's own stated failure mode:
 * "dots promised events a differently-windowed list denied").
 *
 * Ordering is display order, not chronological: all-day entries first (an all-day entry's
 * [AgendaEntry.timeMs] is a midnight stamp - device-zone for a LOCAL row, UTC for a GOOGLE one,
 * see [agendaDayStart] - with no meaningful clock reading, so sorting it among timed entries by
 * that stamp would misplace it), then timed entries ascending by
 * [AgendaEntry.timeMs]. [sortedWith] is a stable sort, so two entries with equal sort keys (two
 * all-day entries, or a genuine timestamp tie) keep their incoming relative order rather than being
 * reshuffled on every rebuild.
 */
fun entriesForDay(entries: List<AgendaEntry>, dayStart: Long, zone: ZoneId = ZoneId.systemDefault()): List<AgendaEntry> =
    entries.filter { agendaDayStart(it, zone) == dayStart }
        .sortedWith(compareBy({ if (it.allDay) 0L else 1L }, { it.timeMs }))

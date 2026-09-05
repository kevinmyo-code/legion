package com.kevin.legion.ui.notes

import com.kevin.legion.backend.EventKind
import com.kevin.legion.data.local.Event
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
 * Pure merge/sort/empty-state logic behind the deck home's AGENDA pane once an appointment source
 * joins the local reminder one (ticket 13, `.scratch/google-account-integration/issues/13-*`;
 * **REPOINTED off live Google Calendar onto the local `events` table by one-today ticket 01, "cut
 * Google entirely" - 2026-08-30/09-01**). No Android types - the caller queries
 * [com.kevin.legion.data.local.EventDao.activeByKindInWindow] directly now (`kind = 'appointment'`,
 * the SAME table Google's own one-way import used to write and a voice-created appointment writes
 * to today - see `service/LiveToolbox.kt`'s `addAppointment`), maps each row to [AppointmentEvent],
 * and hands the plain list here to combine with the local [AgendaEntry] list it already built.
 * Every branch below is a plain JUnit test (`CalendarAgendaResolverTest`), matching this domain's
 * existing "pure builder, thin Composable wrapper" split
 * ([com.kevin.legion.ui.notes.buildInboxRows] - `buildMissedRows`, this pattern's other original
 * example, is deleted, one-today ticket 10 slice C; see [buildInboxRows]'s own doc comment).
 *
 * **This file's own merge/sort logic is UNCHANGED by that repoint** - only where its input comes
 * from moved. **No recurrence math lives here, or anywhere else in LEGION, for an appointment.**
 * Ticket 02's answer, still true post-repoint: a recurring Google series was already expanded into
 * one row per occurrence at IMPORT time (the retired `CalendarImportController`'s own composite-key
 * scheme), so every appointment [Event] row this file is ever handed is already a single occurrence
 * - [mergeAgenda] only ever combines and sorts what it is given.
 */

/**
 * One calendar-table row (`kind = `[EventKind.EVENT] or [EventKind.TASK]), shaped for this file's
 * merge/sort logic - carried over verbatim from the retired `calendar/CalendarProvider.kt`'s
 * `GoogleCalendarEvent` (one-today ticket 01) so [mergeAgenda]/[mergeByTime] below needed no change
 * beyond their input's origin. [eventId] is the real, positive [Event.id] now (the old negative-id
 * Room-safety trick in `ui/notes/NotesResolvers.kt` is gone with it - see that file's own doc
 * comment: this row lives in the SAME id space as a reminder, just disjoint by construction,
 * [Event.APPOINTMENT_ID_BASE]'s own doc comment). [recurring] is always false: nothing local tracks
 * an `RRULE`, every stored row is already one occurrence (this object's own class doc).
 *
 * **[kind] carries [Event.kind] through (ticket 08, "events are not todos")** - the whole reason
 * this type is not just "an appointment" any more. [done] used to be described as meaning "I
 * attended" for every row this type could hold; that was only ever true for a kind that has since
 * been renamed [EventKind.EVENT] and made permanently `false` (an event is never completable - see
 * [EventKind]'s own class doc). [ui.notes.NotesResolvers.InboxRowView.tickable] is what actually
 * reads [kind] to decide whether a checkbox may exist at all; [done] itself is not the gate.
 */
data class AppointmentEvent(
    val eventId: Long,
    val title: String,
    val startMs: Long,
    val endMs: Long,
    val allDay: Boolean,
    val recurring: Boolean = false,
    val location: String = "",
    val done: Boolean = false,
    val kind: String = EventKind.EVENT,
)

/** [Event] -> [AppointmentEvent] - the one mapper every calendar-table-reading screen/builder
 * funnels through, so a future column on either side has exactly one place to add it.
 * [Event.startsAt] is assumed non-null by every caller of this function (every real row of this
 * shape states one - see [AppointmentEvent]'s own doc comment); a null one maps to `0L` rather than
 * crashing, since a malformed row is still worth showing rather than silently dropping (CLAUDE.md's
 * "a line the parser does not recognize is a hard failure, never a skip" - applied here as "render
 * it, oddly, rather than lose it"). */
fun Event.toAppointmentEvent(): AppointmentEvent = AppointmentEvent(
    eventId = id,
    title = title,
    startMs = startsAt ?: 0L,
    endMs = endsAt ?: (startsAt ?: 0L),
    allDay = allDay,
    recurring = false,
    location = location.orEmpty(),
    done = done,
    kind = kind,
)

/**
 * Local [AgendaEntry] rows plus an appointment-sourced entry per [AppointmentEvent], all in one
 * ascending-by-start list. [local] is assumed already windowed and skip-subtracted by the caller
 * (`ui/TodayScreen.kt`'s existing `timedItemsInWindow`/`allRecurringItems` +
 * `Recurrence.occurrencesInWindow` pair, unchanged by this ticket) - [appointments] must be queried
 * over the SAME window for the merge to mean anything, but this function does not itself check
 * that; it only combines what it is given.
 */
fun mergeAgenda(local: List<AgendaEntry>, appointments: List<AppointmentEvent>): List<AgendaEntry> =
    mergeByTime(local.map { it.timeMs to it }, appointments) { event ->
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
 * function rather than living on the row type itself. [fromAppointment] builds one caller-shaped row
 * per [AppointmentEvent], the same job [mergeAgenda] does inline for [AgendaEntry].
 */
fun <T> mergeByTime(
    local: List<Pair<Long, T>>,
    appointments: List<AppointmentEvent>,
    fromAppointment: (AppointmentEvent) -> T,
): List<T> {
    val converted = appointments.map { it.startMs to fromAppointment(it) }
    return (local + converted).sortedBy { it.first }.map { it.second }
}

// AgendaCalendarNotice/buildAgendaCalendarNotice (what the AGENDA pane said about its own Google
// Calendar coverage, kept separate from whether the row list itself was empty - ticket 13 point 7)
// deleted one-today ticket 10 slice C, 2026-09-05: `ui/notes/InboxScreen.kt` was the only caller
// (grep-confirmed before deletion) and is itself deleted with this slice. One-today ticket 01 had
// already cut the live `CalendarContract` read this notice existed to word ("cut Google entirely" -
// the local `events` table is always readable, so the permission-refused branch had not fired in
// production since that ticket), so nothing on `ui/CalendarScreen.kt`'s day view lost real coverage
// by this removal.

// ------------------------------------------------------------ quant-viz ticket 13: WEEK AHEAD

/**
 * Quant-viz ticket 13's Notes-tab WEEK AHEAD strip: one count per local day, [dayStarts] (index-
 * aligned with the returned list) already built by the caller with
 * [com.kevin.legion.ui.common.dailyBuckets] over today's local date through six days out - this
 * function only groups [entries] into those buckets, it does not decide the window.
 *
 * **Zero is a genuine zero here (ticket 13 point 2), never a gap.** Unlike a health metric where
 * an unlogged day is a fact this app cannot claim to know, a day with no agenda item is a fact the
 * app DOES know - [entries] is the SAME merged local+Google agenda stream [mergeAgenda] produces for
 * `ui/CalendarScreen.kt`'s own day view, just windowed over seven days instead of one (the original
 * callers this comment named, `ui/TodayScreen.kt`'s single-day AGENDA pane and `ui/notes/
 * InboxScreen.kt`'s own stream, are both deleted - one-today ticket 07 and ticket 10 slice C
 * respectively) - so an empty bucket here means "nothing scheduled that day", not "coverage
 * unknown". This is why
 * the return type is `List<Int>`, never `List<Int?>`: the gap-vs-zero distinction the chart kit
 * enforces elsewhere has nothing to represent on this particular screen, because this screen's one
 * source of truth (the local DB plus, when linked, Google) is either fully queried for the whole
 * window or not queried at all - a denied `READ_CALENDAR` used to be a caller-level "do not call
 * this function, show [CalendarNotLinkedRow] instead" decision rather than a per-day null this
 * function could produce (moot since one-today ticket 01 cut the live `CalendarContract` read
 * entirely - the local `events` table this screen reads now is always readable).
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
 *
 * [openTodoCount], added 2026-09-05 (Kevin: "calendar has dots for events but not for todos, a bit
 * misleading, i look at next wednesday and think theres nothing due, but there is") - the count of
 * NOT-done tasks and reminders due this day, deliberately a SEPARATE field from [eventCount] rather
 * than folded into it: an event and an open todo answer different questions ("what's scheduled" vs
 * "what's still owed"), and collapsing them back into one number would recreate exactly the
 * "nothing due" misread this field exists to fix. See [buildMonthOpenTodoCounts]'s own doc comment
 * for what counts and what deliberately does not (checklist lines, done rows, events).
 */
data class MonthCell(val dayStart: Long?, val dayOfMonth: Int?, val eventCount: Int, val openTodoCount: Int = 0)

/**
 * The displayed [month]'s cells, always a multiple of seven (leading blanks + the month's own days
 * + trailing blanks, padded out to whole weeks). [counts] is keyed by the SAME local day-start
 * epoch [buildWeekAheadDayCounts] already buckets into - the caller builds it by zipping that
 * function's `dayStarts`/`List<Int>` pair into a map, so this function does no counting of its own
 * (ticket 14: "do not write a second counting rule"). [todoCounts] is the same shape, keyed the
 * same way, built by [buildMonthOpenTodoCounts] - defaults to empty so every existing call site
 * that has not been taught about todos yet still compiles and simply renders zero everywhere.
 *
 * Column order follows [java.time.temporal.WeekFields.of]'s locale-derived
 * [java.time.DayOfWeek] rather than a hardcoded Monday-or-Sunday start, matching whatever order a
 * caller's own weekday-letter header uses for the same locale.
 */
fun buildMonthCells(
    month: YearMonth,
    counts: Map<Long, Int>,
    zone: ZoneId = ZoneId.systemDefault(),
    todoCounts: Map<Long, Int> = emptyMap(),
): List<MonthCell> {
    val firstDayOfWeek = java.time.temporal.WeekFields.of(Locale.getDefault()).firstDayOfWeek
    val firstOfMonth = month.atDay(1)
    // How many blank slots precede day 1 - the gap between the grid's own first column
    // ([firstDayOfWeek]) and the weekday day 1 actually falls on, wrapped into [0, 6].
    val leading = (firstOfMonth.dayOfWeek.value - firstDayOfWeek.value + 7) % 7
    val daysInMonth = month.lengthOfMonth()
    val trailing = (7 - (leading + daysInMonth) % 7) % 7

    val cells = ArrayList<MonthCell>(leading + daysInMonth + trailing)
    repeat(leading) { cells.add(MonthCell(dayStart = null, dayOfMonth = null, eventCount = 0, openTodoCount = 0)) }
    for (day in 1..daysInMonth) {
        val dayStart = month.atDay(day).atStartOfDay(zone).toInstant().toEpochMilli()
        cells.add(
            MonthCell(
                dayStart = dayStart,
                dayOfMonth = day,
                eventCount = counts[dayStart] ?: 0,
                openTodoCount = todoCounts[dayStart] ?: 0,
            ),
        )
    }
    repeat(trailing) { cells.add(MonthCell(dayStart = null, dayOfMonth = null, eventCount = 0, openTodoCount = 0)) }
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

// -------------------------------------------------------- 2026-09-05: open-todo month indicator

/**
 * The local day an [InboxRowView] with a due date belongs in, mirroring [agendaDayStart]'s own
 * split for the same reason: a [AgendaSource.GOOGLE] task row's [InboxRowView.instantMs] is UTC
 * midnight of its date when [InboxRowView.calendarAllDay] is true (`LiveToolbox.addAppointment`'s
 * own convention, [toInboxRowView]'s doc comment on the GOOGLE branch), while a
 * [AgendaSource.LOCAL] reminder's `startsAt` (all-day or timed) is already a genuine device-zone
 * instant. Reading a GOOGLE all-day row through the device zone directly would put it one day
 * early west of UTC - the exact bug [agendaDayStart] was written to fix in the AGENDA pane, and
 * this is the same fix applied to [InboxRowView] rather than [com.kevin.legion.ui.AgendaEntry].
 */
private fun inboxRowDayStart(row: InboxRowView, zone: ZoneId): Long {
    val instant = row.instantMs ?: return 0L
    return if (row.source == AgendaSource.GOOGLE && row.calendarAllDay == true) {
        Instant.ofEpochMilli(instant).atZone(ZoneOffset.UTC).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
    } else {
        dayStartEpoch(instant, zone)
    }
}

/**
 * One open-todo count per local day, [dayStarts] the SAME index-aligned list
 * [buildWeekAheadDayCounts] takes (built by the caller with [com.kevin.legion.ui.common.dailyBuckets]
 * over the queried window). [rows] is the SAME [InboxRowView] stream the day view's own YET TO DO
 * section reads - built by [buildInboxRows] over [com.kevin.legion.notes.NotesController.allItems]
 * (which already excludes a checklist line - `GoalChecklistSync.ITEM_PREFIX` - so a "bio" routine's
 * six lines never inflate this count, ticket 09's own rule restated here for a second table) plus
 * `EventKind.TASK` rows queried with [com.kevin.legion.data.local.activeByKindInLocalWindow] - the
 * one window helper this counts through, never a second date query of its own.
 *
 * **Done rows are excluded, on purpose** (Kevin: the indicator answers "what's still owed", not
 * "what happened this day") - [InboxRowView.done] is checked here, the caller does not pre-filter.
 * **An undated row ([InboxRowView.instantMs] null) cannot belong to any one day and is silently
 * excluded**, the same rule [InboxRowView.instantMs]'s own doc comment states for the month day
 * filter. **[EventKind.EVENT] rows never reach this function at all** - the day view never puts
 * them in [dayRows], only in its own SCHEDULE section, so [rows] must never include them; that is
 * the caller's contract, restated here rather than re-checked, matching [buildWeekAheadDayCounts]'s
 * own "the caller is responsible for the window" contract.
 */
fun buildMonthOpenTodoCounts(rows: List<InboxRowView>, dayStarts: List<Long>, zone: ZoneId = ZoneId.systemDefault()): List<Int> {
    val open = rows.filter { !it.done && it.instantMs != null }
    val grouped = open.groupBy { inboxRowDayStart(it, zone) }
    return dayStarts.map { day -> grouped[day]?.size ?: 0 }
}

/**
 * How many square marks a cell draws for its [MonthCell.openTodoCount] - same density bands as
 * [eventDotCount] (0 draws none, 1-2 draws one, 3-4 draws two, 5+ draws three), deliberately reusing
 * that function's own thresholds so a driver who has learned "one dot means a couple, three means a
 * lot" does not have to learn a second scale for the second indicator. Kept as its own named
 * function rather than a direct call to [eventDotCount] so the two marks can diverge independently
 * later without one's tuning silently dragging the other's.
 */
fun openTodoMarkCount(openTodoCount: Int): Int = eventDotCount(openTodoCount)

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

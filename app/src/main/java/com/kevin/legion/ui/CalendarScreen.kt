package com.kevin.legion.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kevin.legion.backend.EventKind
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.Event
import com.kevin.legion.data.local.ListItem
import com.kevin.legion.notes.NotesController
import com.kevin.legion.ui.agenda.MonthCalendar
import com.kevin.legion.ui.common.DeckPane
import com.kevin.legion.ui.common.DeckSectionRule
import com.kevin.legion.ui.common.dailyBuckets
import com.kevin.legion.ui.notes.DAY_FILTER_WINDOW_MS
import com.kevin.legion.ui.notes.InboxRowView
import com.kevin.legion.ui.notes.MonthCell
import com.kevin.legion.ui.notes.buildInboxRows
import com.kevin.legion.ui.notes.buildMonthCells
import com.kevin.legion.ui.notes.buildWeekAheadDayCounts
import com.kevin.legion.ui.notes.formatDateOnly
import com.kevin.legion.ui.notes.formatDateTime
import com.kevin.legion.ui.notes.toAppointmentEvent
import com.kevin.legion.ui.theme.LegionType
import com.kevin.legion.ui.theme.LocalLegionSemantics
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import kotlinx.coroutines.launch

/**
 * The three-destination cutover's new start destination (Kevin, verbatim: "month grid primary.
 * tapping a day on the month opens up view B. C as another tab. retire the bottom headers like
 * cred fleet etc. those we tap through from view C the meters."). Supersedes the six-tab bottom bar
 * - see [LegionRoute]'s own doc comment for the shape this replaced. FLEET/MONEY/BODY/NOTES stay
 * registered [LegionRoute] destinations, reachable as drill-downs (from [MetersScreen], "view C")
 * and by deep link; they just stop being tabs a driver lands on directly.
 *
 * **View A/View B, both in this one screen, as internal Compose state** ([selectedDayStart]) rather
 * than a nav-graph argument - [LegionRoute]'s own doc comment establishes that convention ("nothing
 * here takes a navigation argument") and [NotesScreen]'s day-filter drill-down already follows it
 * for the exact same month-grid/day shape this screen reuses.
 *
 * **No new write path.** Ticking a row calls the SAME [NotesController.tick]/[NotesController.untick]
 * (one-off reminders) and [NotesController.tickAppointment]/[NotesController.untickAppointment]
 * (appointments) funnels `ui/notes/InboxScreen.kt`'s own day-filtered mode already calls, over the
 * SAME [com.kevin.legion.ui.notes.buildInboxRows] pure merge - restated here (not imported as a
 * screen) only because that file's own add-item bar/edit-dialog/CAL-tag furniture is more than this
 * sparse view wants, per Kevin's "sparse UI + voice modals" instruction. A recurring reminder's
 * occurrence stays `tickable = false` for the same reason it already is on that screen -
 * `.scratch/one-today/issues/02-ticking-an-appointment.md` point 2 leaves "tick one occurrence of a
 * recurring series" an open design question, not something this ticket may invent an answer to. No
 * agenda source in this screen's day view is left with a checkbox and no write path: every row here
 * is a real [ListItem] or a real [Event], and both already have one.
 *
 * **View B also carries the day's plan checklist when, and only when, the selected day is TODAY**
 * (rehomed from the deleted `ui/TodayScreen.kt`'s HERO pane, one-today ticket 07, 2026-09-01) -
 * [com.kevin.legion.ui.goals.GoalChecklistPanel], `compact = true`, unchanged from its own build.
 * A checklist of "today's items" sitting under a day view for a date in March would be nonsense, so
 * the pane is omitted entirely on any other day rather than rendered empty.
 */
@Composable
fun CalendarScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val zone = remember { ZoneId.systemDefault() }

    var displayedMonth by remember { mutableStateOf(YearMonth.now()) }
    var selectedDayStart by remember { mutableStateOf<Long?>(null) }
    var calendarCollapsed by remember { mutableStateOf(false) }
    var monthLoading by remember { mutableStateOf(true) }
    var monthCells by remember { mutableStateOf(emptyList<MonthCell>()) }
    // The same list [buildWeekAheadDayCounts] bucketed to draw the grid's dots, kept for the NEXT
    // pane below - not a second fetch, so the dots and the pane can never disagree (the same
    // reasoning `ui/NotesScreen.kt`'s own [entriesForDay] popup already relies on).
    var monthEntries by remember { mutableStateOf(emptyList<AgendaEntry>()) }

    // The day view's own row state - a real [ListItem]/[Event] behind every tickable row, same
    // shape `ui/notes/InboxScreen.kt`'s [rawItems]/[rawAppointments] pair already holds for its own
    // day-filtered mode, kept here rather than imported because this screen owns a different
    // [reloadNonce] cadence (this screen has no add-item bar to also trigger a reload).
    var rawItems by remember { mutableStateOf(emptyList<ListItem>()) }
    var rawAppointments by remember { mutableStateOf(emptyList<Event>()) }
    var dayRows by remember { mutableStateOf(emptyList<InboxRowView>()) }
    var reloadNonce by remember { mutableStateOf(0) }

    LaunchedEffect(displayedMonth, reloadNonce) {
        monthLoading = true
        val monthStart = displayedMonth.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val monthEnd = displayedMonth.atEndOfMonth().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
        val dayStarts = dailyBuckets(monthStart, monthEnd, zone)
        val merged = com.kevin.legion.ui.agenda.buildMonthAgenda(context, displayedMonth, zone)
        val counts = buildWeekAheadDayCounts(merged, dayStarts, zone)
        val countsByDayStart = dayStarts.zip(counts).toMap()
        monthCells = buildMonthCells(displayedMonth, countsByDayStart, zone)
        monthEntries = merged
        monthLoading = false
    }

    // Re-fetched on [selectedDayStart] AND [reloadNonce] (a tick just written) - same two keys
    // `ui/notes/InboxScreen.kt`'s own effect re-fetches on. [NotesController.allItems] is
    // unwindowed (see that screen's own comment on why), so only the appointment half needs a
    // day-scoped query.
    LaunchedEffect(selectedDayStart, reloadNonce) {
        val day = selectedDayStart
        if (day == null) {
            dayRows = emptyList()
            return@LaunchedEffect
        }
        val items = NotesController.allItems(context)
        rawItems = items
        val dayEndExclusive = day + DAY_FILTER_WINDOW_MS
        val db = CarDatabase.getDatabase(context)
        val appointments = db.eventDao().activeByKindInWindow(EventKind.APPOINTMENT, day, dayEndExclusive - 1)
        rawAppointments = appointments
        val now = System.currentTimeMillis()
        val rows = buildInboxRows(items, now, appointments.map { it.toAppointmentEvent() })
        dayRows = rows.filter { row -> row.instantMs != null && row.instantMs >= day && row.instantMs < dayEndExclusive }
    }

    fun toggle(id: Long) {
        val target = rawItems.firstOrNull { it.id == id }
        if (target != null) {
            scope.launch {
                if (target.done) NotesController.untick(context, target) else NotesController.tick(context, target)
                reloadNonce++
            }
            return
        }
        val appointment = rawAppointments.firstOrNull { it.id == id } ?: return
        scope.launch {
            if (appointment.done) {
                NotesController.untickAppointment(context, appointment)
            } else {
                NotesController.tickAppointment(context, appointment)
            }
            reloadNonce++
        }
    }

    val sem = LocalLegionSemantics.current

    // Fixed on-device 2026-09-01: dropped the redundant "CALENDAR" H1 - the tab immediately above
    // this screen already reads CALENDAR (`LegionTabRow`), and repeating it as a heading was pure
    // duplication, not orientation. Same fix applied to `MetersScreen.kt`'s own "METERS" H1.
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 10.dp, bottom = 12.dp)) {
        if (!monthLoading) {
            MonthCalendar(
                // One-today ticket 01 cut the live Google read this flag used to gate on - the
                // local `events` table is always readable, matching `ui/NotesScreen.kt`'s own
                // post-cut `monthCalendarLinked = true`.
                calendarLinked = true,
                month = displayedMonth,
                cells = monthCells,
                // Forced collapsed the instant a day is selected (Kevin: "tapping a day on the
                // month opens up view B", and view B's own agenda list needs the room the grid
                // would otherwise take) - [calendarCollapsed] still lets the HIDE/MONTH toggle
                // collapse the grid manually while browsing with no day selected.
                collapsed = selectedDayStart != null || calendarCollapsed,
                selectedDayStart = selectedDayStart,
                onToggleCollapsed = { calendarCollapsed = !calendarCollapsed },
                onPrevMonth = { selectedDayStart = null; displayedMonth = displayedMonth.minusMonths(1) },
                onNextMonth = { selectedDayStart = null; displayedMonth = displayedMonth.plusMonths(1) },
                // Kevin's ruling: "tapping a day on the month opens up view B" - directly, no
                // intermediate popup (unlike `ui/NotesScreen.kt`'s own ticket-16 day-tap dialog,
                // which this screen's own "view B is the day view itself" shape has no need of).
                onSelectDay = { day -> selectedDayStart = day },
                onGrantCalendar = {},
            )
        }

        val day = selectedDayStart
        if (day == null) {
            // View A: no day selected - the month grid above, plus a short NEXT pane. Only the
            // current month has a meaningful "next" - a browsed-away month has no relationship to
            // "now" worth showing here (`.scratch` has no ruling either way; this keeps the pane
            // honest rather than showing stale "next" entries for a month that is not this one).
            if (displayedMonth == YearMonth.now()) {
                val now = System.currentTimeMillis()
                val next = monthEntries.filter { it.allDay || it.timeMs >= now }.take(NEXT_PANE_LIMIT)
                DeckPane(header = "NEXT", modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                    if (next.isEmpty()) {
                        Text(
                            "Nothing still ahead this month.",
                            style = LegionType.stamp,
                            color = sem.faint,
                            modifier = Modifier.padding(vertical = 6.dp),
                        )
                    } else {
                        next.forEach { entry ->
                            Text(
                                entry.label,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                if (entry.allDay) formatDateOnly(entry.timeMs) else formatDateTime(entry.timeMs),
                                style = LegionType.stamp,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(bottom = 6.dp),
                            )
                        }
                    }
                }
            }
        } else {
            // View B: the tapped day's agenda, split "yet to do" / "done" (this ticket's own
            // instruction). Rendered with [CalendarDayRow], a sparse local row (checkbox + label +
            // date only) rather than `ui/notes/InboxScreen.kt`'s own [InboxRow] - that row also
            // carries an edit tap-through and a REMOVE/DELETE button neither this screen's brief nor
            // Kevin's "sparse UI + voice modals" instruction asked for, and a button that visibly
            // exists but does nothing on tap is worse than one that is simply absent. The WRITE
            // funnel is unchanged either way - [toggle] below calls the identical
            // [NotesController.tick]/[tickAppointment] pair [InboxRow]'s own `onToggle` would have.
            TextButton(onClick = { selectedDayStart = null }) {
                Text("BACK TO MONTH", style = LegionType.stamp, color = MaterialTheme.colorScheme.primary)
            }
            // The day's plan checklist (rehomed from the deleted `ui/TodayScreen.kt`'s HERO pane,
            // one-today ticket 07) - ONLY on today's own day view: a checklist of "today's items" is
            // nonsense sitting under a day in March, so a day view that is not today omits this pane
            // entirely rather than showing it empty (this ticket's own instruction).
            val todayStart = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
            if (day == todayStart) {
                com.kevin.legion.ui.goals.GoalChecklistPanel(
                    compact = true,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
            val notDone = dayRows.filter { !it.done }
            val done = dayRows.filter { it.done }
            Column(Modifier.padding(horizontal = 12.dp)) {
                DeckSectionRule("Yet to do")
                if (notDone.isEmpty()) {
                    Text("Nothing left on this day.", style = LegionType.stamp, color = sem.faint, modifier = Modifier.padding(vertical = 6.dp))
                } else {
                    notDone.forEach { row -> CalendarDayRow(row = row, onToggle = { toggle(row.id) }) }
                }
                DeckSectionRule("Done")
                if (done.isEmpty()) {
                    Text("Nothing done on this day yet.", style = LegionType.stamp, color = sem.faint, modifier = Modifier.padding(vertical = 6.dp))
                } else {
                    done.forEach { row -> CalendarDayRow(row = row, onToggle = { toggle(row.id) }) }
                }
            }
        }
    }
}

/** How many upcoming entries the no-day-selected "NEXT" pane shows - short, per this ticket's
 * "short 'Next' pane" instruction, not the whole rest of the month.
 *
 * Raised from 5 to 12 (fixed on-device 2026-09-01): with the month grid collapsed and only 5 NEXT
 * rows, roughly a quarter of the screen below sat empty black - "short" was never meant to mean
 * "leaves the lower third of the screen unused". 12 is still a short list, not the whole rest of
 * the month; it is sized to fill the room a collapsed grid leaves rather than to hit an exact
 * pixel count, since this pane already scrolls if a genuinely busy month runs past it. */
private const val NEXT_PANE_LIMIT = 12

/**
 * One day-view row: a checkbox (absent, not merely disabled, when [InboxRowView.tickable] is
 * false - a recurring occurrence with no per-occurrence write path, `.scratch/one-today/issues/
 * 02-ticking-an-appointment.md` point 2) plus the label and date. Deliberately narrower than
 * `ui/notes/InboxScreen.kt`'s [com.kevin.legion.ui.notes.InboxRow] - see [CalendarScreen]'s own
 * call-site comment for why.
 */
@Composable
private fun CalendarDayRow(row: InboxRowView, onToggle: () -> Unit) {
    val sem = LocalLegionSemantics.current
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        if (row.tickable) {
            Checkbox(checked = row.done, onCheckedChange = { onToggle() })
        } else {
            Column(Modifier.padding(start = 12.dp, end = 12.dp)) {}
        }
        Column {
            Text(
                row.text,
                style = MaterialTheme.typography.bodyMedium,
                color = if (row.done) sem.faint else MaterialTheme.colorScheme.onSurface,
            )
            row.dateLabel?.let { label ->
                Text(label, style = LegionType.stamp, color = MaterialTheme.colorScheme.primary)
            }
            if (row.recurring) {
                Text("Recurring - not tickable", style = LegionType.stamp, color = sem.faint)
            }
        }
    }
}

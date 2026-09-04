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
import androidx.compose.foundation.clickable
import com.kevin.legion.backend.EventKind
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.Event
import com.kevin.legion.data.local.ListItem
import com.kevin.legion.data.local.VoiceNote
import com.kevin.legion.data.local.activeByKindInLocalWindow
import com.kevin.legion.notes.NotesController
import com.kevin.legion.ui.agenda.MonthCalendar
import com.kevin.legion.ui.common.DeckSectionRule
import com.kevin.legion.ui.common.dailyBuckets
import com.kevin.legion.ui.notes.DAY_FILTER_WINDOW_MS
import com.kevin.legion.ui.notes.InboxRowView
import com.kevin.legion.ui.notes.MonthCell
import com.kevin.legion.ui.notes.buildInboxRows
import com.kevin.legion.ui.notes.buildMonthCells
import com.kevin.legion.ui.notes.buildWeekAheadDayCounts
import com.kevin.legion.ui.notes.formatDateTime
import com.kevin.legion.util.documentDateCompact
import com.kevin.legion.ui.notes.toAppointmentEvent
import com.kevin.legion.ui.theme.LegionType
import com.kevin.legion.ui.theme.LocalLegionSemantics
import com.kevin.legion.ui.voicenotes.VoiceNoteDetailScreen
import com.kevin.legion.ui.voicenotes.formatVoiceNoteDuration
import com.kevin.legion.util.clockTime
import com.kevin.legion.voice.VoiceNoteController
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
 * **CORRECTED 2026-09-01: this used to be View A (no day selected, month grid + a NEXT pane) and
 * View B (a day selected, grid hidden, "BACK TO MONTH" to return) as two mutually-exclusive states
 * of [selectedDayStart].** That was itself internal Compose state rather than a nav-graph argument -
 * [LegionRoute]'s own doc comment still establishes that convention ("nothing here takes a
 * navigation argument") and [NotesScreen]'s day-filter drill-down still follows it - but hiding the
 * grid on selection read as a navigation to Kevin even though it was not one, so View A is gone: see
 * the paragraph below for the live shape (grid always visible, a day always selected).
 *
 * **No new write path.** Ticking a row calls the SAME [NotesController.tick]/[NotesController.untick]
 * (one-off reminders) and [NotesController.tickAppointment]/[NotesController.untickAppointment]
 * (tasks) funnels `ui/notes/InboxScreen.kt`'s own day-filtered mode already calls, over the
 * SAME [com.kevin.legion.ui.notes.buildInboxRows] pure merge - restated here (not imported as a
 * screen) only because that file's own add-item bar/edit-dialog/CAL-tag furniture is more than this
 * sparse view wants, per Kevin's "sparse UI + voice modals" instruction. A recurring reminder's
 * occurrence stays `tickable = false` for the same reason it already is on that screen -
 * `.scratch/one-today/issues/02-ticking-an-appointment.md` point 2 leaves "tick one occurrence of a
 * recurring series" an open design question, not something this ticket may invent an answer to.
 *
 * **The month grid never hides itself on a day tap (fixed on-device 2026-09-01, Kevin verbatim:
 * "calendar > next view i want it dropped. instead it should show the due items on the tapped
 * date. right now it takes me to a new screen (every calendar date tap)").** [selectedDayStart] is
 * never null - it defaults to today on open - and the grid stays on screen with the tapped day
 * bordered ([MonthCellView]'s `isSelected` treatment); only the manual HIDE/MONTH toggle
 * ([calendarCollapsed]) may collapse it now, never the act of selecting a day. The old View A/View
 * B split (a "NEXT" pane shown only with no day selected, reached by tapping "BACK TO MONTH") is
 * gone with it - Kevin's complaint was that tapping a day read as a navigation even though it was
 * only internal state, and with a day always selected there is no unselected state left for a NEXT
 * pane to answer or a BACK link to return from.
 *
 * **The day agenda (formerly "View B", now the only view) splits into THREE sections (one-today
 * ticket 08, "events are not todos",
 * 2026-09-01 - Kevin verbatim: "i dont mark an event done, it just passes whether or not i do it,
 * like classes").** This reverses ticket 02's "every calendar-table row gets a checkbox": half of
 * what that made tickable were never appointments, they were assignments a title-based heuristic
 * could not tell apart from a class. So:
 * - **SCHEDULE** - [scheduleRows], [EventKind.EVENT] rows only, time-ordered, **no checkbox at all -
 *   not a disabled one.** An event greys by time alone; it is never in [dayRows] and [toggle] is
 *   never wired to one.
 * - **YET TO DO** / **DONE** - [dayRows] split by [InboxRowView.done], covering reminders
 *   ([ListItem]) and [EventKind.TASK] rows (nothing writes a task yet - Canvas is its own ticket).
 *   Every row here keeps its checkbox, same write funnel as before.
 *
 * **The completion ratio counts tasks (and reminders) only, never events** - `ui/goals/
 * GoalChecklistPanel.kt`'s own "N TODAY" accent below is scoped to the BIO checklist, a wholly
 * separate table this screen's own agenda has no ratio of its own to get wrong; see
 * `.scratch/one-today/issues/08-events-are-not-todos.md` point 4 for the density-dot audit this
 * screen's own [MonthCell.eventCount]/[eventDotCount] were checked against (they count workload,
 * not completion, and were left as an honest "how busy" figure rather than folded into this rule).
 *
 * **RECORDED (Kevin, 2026-09-04: "i do want it to link to the claneadr too, together with its own
 * ui") - a fourth section, a read-time join, never a write into [Event]/`events`.** A voice note is
 * not an event and copying it into that table would recreate exactly the mess ticket 08 exists to
 * fix: two different kinds of thing sharing one discriminator that cannot tell them apart. Instead
 * [selectedDayStart]'s own [LaunchedEffect] separately queries [VoiceNoteController.listInRange]
 * and [recordedRows] renders alongside, never merged into [dayRows]/[scheduleRows]. Same two
 * ticket-08 constraints SCHEDULE already honours, restated for a different table: **no checkbox** -
 * a recording already happened, it is not an obligation to meet or miss, ticket 08's reasoning for
 * an event applies unchanged - and **excluded from the completion ratio** ([notDone]/[done] below
 * are built from [dayRows] alone; [recordedRows] never touches either). Tapping a row opens the
 * SAME [VoiceNoteDetailScreen] `ui/voicenotes/VoiceNotesScreen.kt`'s own list opens - one detail
 * implementation, not a second copy.
 *
 * **An empty day and a failed voice-notes read are not the same sentence** - the calendar-briefing
 * failure (`calendar/OpenerCalendarBriefing.kt`) in a new place: a refused permission and a clear
 * day must never render identically. [recordedLoadFailed] is set from
 * [VoiceNoteController.VoiceNotesForDayResult.Failed] and rendered as its own sentence, distinct
 * from the ordinary "nothing recorded this day" [recordedRows]-is-empty case.
 */
@Composable
fun CalendarScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val zone = remember { ZoneId.systemDefault() }

    // A day is always selected (fixed on-device 2026-09-01, Kevin: "it should show the due items
    // on the tapped date" - no unselected state, no navigation). Today's start on open, matching
    // [MonthCellView]'s own `isToday` computation below.
    val todayStartOnOpen = remember { LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli() }
    var displayedMonth by remember { mutableStateOf(YearMonth.now()) }
    var selectedDayStart by remember { mutableStateOf(todayStartOnOpen) }
    var calendarCollapsed by remember { mutableStateOf(false) }
    var monthLoading by remember { mutableStateOf(true) }
    var monthCells by remember { mutableStateOf(emptyList<MonthCell>()) }

    // The day view's own row state - a real [ListItem]/[Event] behind every tickable row, same
    // shape `ui/notes/InboxScreen.kt`'s [rawItems]/[rawAppointments] pair already holds for its own
    // day-filtered mode, kept here rather than imported because this screen owns a different
    // [reloadNonce] cadence (this screen has no add-item bar to also trigger a reload).
    var rawItems by remember { mutableStateOf(emptyList<ListItem>()) }
    // ONE-TODAY TICKET 08: [rawAppointments] now holds only [EventKind.TASK] rows - the only
    // calendar-table kind [toggle] may ever write through [NotesController.tickAppointment]/
    // [untickAppointment] (that pair itself refuses anything else - see its own doc comment). An
    // [EventKind.EVENT] row never lands here at all; it lives in [scheduleRows] instead, which
    // [toggle] never reads.
    var rawAppointments by remember { mutableStateOf(emptyList<Event>()) }
    var dayRows by remember { mutableStateOf(emptyList<InboxRowView>()) }
    // SCHEDULE section (ticket 08): [EventKind.EVENT] rows, time-ordered, never merged into
    // [dayRows] - an event has no [InboxRowView.done]/[InboxRowView.tickable] question to answer,
    // so it does not belong in the same list a checkbox-driven filter later splits.
    var scheduleRows by remember { mutableStateOf(emptyList<InboxRowView>()) }
    // RECORDED section (this screen's own file doc comment) - a read-time join against
    // `voice_notes`, never merged into [scheduleRows]/[dayRows]: no checkbox, no completion ratio.
    var recordedRows by remember { mutableStateOf(emptyList<VoiceNote>()) }
    // Distinct from "recordedRows is empty" - see [VoiceNoteController.VoiceNotesForDayResult]'s
    // own doc comment for why these two must never render as the same sentence.
    var recordedLoadFailed by remember { mutableStateOf(false) }
    var selectedRecordingId by remember { mutableStateOf<Long?>(null) }
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
        monthLoading = false
    }

    // Re-fetched on [selectedDayStart] AND [reloadNonce] (a tick just written) - same two keys
    // `ui/notes/InboxScreen.kt`'s own effect re-fetches on. [NotesController.allItems] is
    // unwindowed (see that screen's own comment on why), so only the calendar-table half needs a
    // day-scoped query.
    LaunchedEffect(selectedDayStart, reloadNonce) {
        val day = selectedDayStart
        val items = NotesController.allItems(context)
        rawItems = items
        val dayEndExclusive = day + DAY_FILTER_WINDOW_MS
        val db = CarDatabase.getDatabase(context)
        // Queried separately, never merged into one list: an event's own section has no checkbox
        // and no completion question, a task's does - ticket 08's whole point.
        // [activeByKindInLocalWindow], not the raw DAO query - an all-day row's `startsAt` is UTC
        // midnight of its date, not a device-zone instant (see that function's own doc comment);
        // this is the SCHEDULE section Kevin found a Canvas due date one day early on, 2026-09-01.
        val events = db.eventDao().activeByKindInLocalWindow(EventKind.EVENT, day, dayEndExclusive - 1, zone)
        val tasks = db.eventDao().activeByKindInLocalWindow(EventKind.TASK, day, dayEndExclusive - 1, zone)
        rawAppointments = tasks
        val now = System.currentTimeMillis()
        // Tasks merge into the SAME chronological/done-split stream as reminders - both are
        // completable, so [buildInboxRows]' existing merge (which already sets
        // [InboxRowView.tickable] correctly per-kind, see that function's own doc comment) is reused
        // rather than forked. Events never pass through here.
        val rows = buildInboxRows(items, now, tasks.map { it.toAppointmentEvent() })
        dayRows = rows.filter { row -> row.instantMs != null && row.instantMs >= day && row.instantMs < dayEndExclusive }
        scheduleRows = events.sortedBy { it.startsAt ?: 0L }.map { event ->
            InboxRowView(
                id = event.id,
                text = event.title,
                done = false,
                tickable = false,
                recurring = false,
                // [event] is a calendar-table row (`kind = event`) - its allDay convention is UTC
                // midnight of the date (see [activeByKindInLocalWindow]'s own doc comment), so the
                // label reads it through [documentDateCompact] (UTC), never [formatDateOnly]
                // (device zone), the same split `ui/notes/NotesResolvers.kt`'s two `toInboxRowView`
                // overloads now draw between a reminder's LOCAL-midnight allDay and an appointment's
                // UTC-midnight one.
                dateLabel = event.startsAt?.let { at -> if (event.allDay) documentDateCompact(at) else formatDateTime(at) },
                overdue = false,
                placeLabel = null,
                exactDowngraded = false,
                source = com.kevin.legion.ui.AgendaSource.GOOGLE,
                instantMs = event.startsAt,
            )
        }
        // RECORDED - a read-time join against `voice_notes`, same [day, dayEndExclusive) window
        // the SCHEDULE/task queries above use. [VoiceNote.startedAt] is a real epoch-millis
        // timestamp (when the recording genuinely started), not an all-day UTC-midnight
        // convention like [Event.startsAt] can be - so this needs no [zone] parameter the way
        // [activeByKindInLocalWindow] does; [day]/[dayEndExclusive] are already device-zone
        // instants from [selectedDayStart]'s own construction.
        when (val result = VoiceNoteController.listInRange(context, day, dayEndExclusive)) {
            is VoiceNoteController.VoiceNotesForDayResult.Loaded -> {
                recordedRows = result.notes
                recordedLoadFailed = false
            }
            is VoiceNoteController.VoiceNotesForDayResult.Failed -> {
                // Degrade with words, never with a silently empty list - an empty [recordedRows]
                // here would read identically to a day with nothing recorded on it, which is
                // exactly the calendar-briefing failure this screen's own file doc comment names.
                recordedRows = emptyList()
                recordedLoadFailed = true
            }
        }
    }

    // Only ever called from [dayRows] (reminders + tasks) - [scheduleRows] rows carry no checkbox
    // (see [CalendarDayRow]'s own `row.tickable` gate) and this function is never wired to one.
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

    // RECORDED drill-down: opens the SAME [VoiceNoteDetailScreen]
    // `ui/voicenotes/VoiceNotesScreen.kt`'s own list opens - one detail implementation, not a
    // second copy, matching this screen's own file doc comment. [recordedRows] is scoped to this
    // day only, but it holds the real [VoiceNote] row (transcript, summary, audio path included),
    // never a copy - so nothing about the detail view differs from opening the same note from the
    // Recordings screen. Mirrors [VoiceNotesScreen]'s own "early-return the whole screen" drill-down
    // convention.
    val selectedRecording = recordedRows.firstOrNull { it.id == selectedRecordingId }
    if (selectedRecording != null) {
        VoiceNoteDetailScreen(
            note = selectedRecording,
            onBack = { selectedRecordingId = null },
            onRenamed = { reloadNonce++ },
            onDeleted = { selectedRecordingId = null; reloadNonce++ },
        )
        return
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
                // Only the manual HIDE/MONTH toggle collapses the grid now (fixed on-device
                // 2026-09-01) - selecting a day used to force this true and hide the grid
                // entirely, which is exactly the "takes me to a new screen" complaint. The grid
                // stays up; [MonthCellView]'s own `isSelected` border marks the tapped day on it.
                collapsed = calendarCollapsed,
                selectedDayStart = selectedDayStart,
                onToggleCollapsed = { calendarCollapsed = !calendarCollapsed },
                onPrevMonth = { displayedMonth = displayedMonth.minusMonths(1) },
                onNextMonth = { displayedMonth = displayedMonth.plusMonths(1) },
                // Kevin, fixed on-device 2026-09-01: "it should show the due items on the tapped
                // date" - changes what renders below, in place, never a navigation.
                onSelectDay = { day -> selectedDayStart = day },
                onGrantCalendar = {},
            )
        }

        // The tapped day's agenda, split into SCHEDULE / YET TO DO / DONE (one-today ticket
        // 08, "events are not todos" - this screen's own file doc comment has the full
        // account). Rendered with [CalendarDayRow], a sparse local row (checkbox + label + date
        // only) rather than `ui/notes/InboxScreen.kt`'s own [InboxRow] - that row also carries an
        // edit tap-through and a REMOVE/DELETE button neither this screen's brief nor Kevin's
        // "sparse UI + voice modals" instruction asked for, and a button that visibly exists but
        // does nothing on tap is worse than one that is simply absent. The WRITE funnel is
        // unchanged either way - [toggle] below calls the identical
        // [NotesController.tick]/[tickAppointment] pair [InboxRow]'s own `onToggle` would have.
        //
        // The old "BACK TO MONTH" link is gone (fixed on-device 2026-09-01) - there is no
        // longer a month-only state to return to; the grid above is always visible already, and
        // [selectedDayStart] is never null, so this section always has a day to render.
        // The day's plan checklist (rehomed from the deleted `ui/TodayScreen.kt`'s HERO pane,
        // one-today ticket 07) - ONLY on today's own day view: a checklist of "today's items" is
        // nonsense sitting under a day in March, so a day view that is not today omits this pane
        // entirely rather than showing it empty (this ticket's own instruction).
        val todayStart = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
        if (selectedDayStart == todayStart) {
            com.kevin.legion.ui.goals.GoalChecklistPanel(
                compact = true,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
        val notDone = dayRows.filter { !it.done }
        val done = dayRows.filter { it.done }
        Column(Modifier.padding(horizontal = 12.dp)) {
            // SCHEDULE - events, time-ordered, no checkbox (ticket 08). [scheduleRows] is
            // already sorted by [Event.startsAt]; every row's own [InboxRowView.tickable] is
            // false, so [CalendarDayRow] renders it with no checkbox at all - never a disabled
            // one - and greys it by time alone via its own `done` styling if the caller ever
            // marked it past (it never does here; [InboxRowView.done] is hardcoded false for
            // this section since an event has no completion state to be false ABOUT).
            DeckSectionRule("Schedule")
            if (scheduleRows.isEmpty()) {
                Text("Nothing on the calendar this day.", style = LegionType.stamp, color = sem.faint, modifier = Modifier.padding(vertical = 6.dp))
            } else {
                scheduleRows.forEach { row -> CalendarDayRow(row = row, onToggle = {}) }
            }
            // RECORDED - a read-time join, no checkbox, excluded from the completion ratio below
            // (this screen's own file doc comment). [recordedLoadFailed] renders its own distinct
            // sentence, never folded into the ordinary empty-list case.
            DeckSectionRule("Recorded")
            if (recordedLoadFailed) {
                Text(
                    "Couldn't load recordings for this day.",
                    style = LegionType.stamp,
                    color = sem.estimated,
                    modifier = Modifier.padding(vertical = 6.dp),
                )
            } else if (recordedRows.isEmpty()) {
                Text("Nothing recorded this day.", style = LegionType.stamp, color = sem.faint, modifier = Modifier.padding(vertical = 6.dp))
            } else {
                recordedRows.forEach { note -> RecordedDayRow(note = note, onClick = { selectedRecordingId = note.id }) }
            }
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

/**
 * One day-view row: a checkbox (absent, not merely disabled, when [InboxRowView.tickable] is
 * false) plus the label and date. Two different reasons a row lands here with no checkbox, both
 * intentional: a recurring occurrence with no per-occurrence write path (`.scratch/one-today/
 * issues/02-ticking-an-appointment.md` point 2), and - as of one-today ticket 08, "events are not
 * todos" - EVERY [scheduleRows] row, because an event has no completion state to check off at all
 * (Kevin: "i dont mark an event done, it just passes"). Deliberately narrower than
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

/**
 * One RECORDED row: title (same unnamed fallback `ui/voicenotes/VoiceNotesScreen.kt`'s own list
 * uses), time, duration. **No checkbox** - a recording already happened; it is not an obligation to
 * meet or miss, same reasoning [CalendarDayRow] already applies to a SCHEDULE row, restated here
 * for a different table (this screen's own file doc comment). Deliberately narrower than
 * [com.kevin.legion.ui.voicenotes.VoiceNoteRow] - no state word, no derived-summary preview, no
 * interrupted callout - because a driver scanning a day's rows wants only enough to decide whether
 * to open one; the fuller disclosures live in [VoiceNoteDetailScreen] itself, one tap away.
 */
@Composable
private fun RecordedDayRow(note: VoiceNote, onClick: () -> Unit) {
    val sem = LocalLegionSemantics.current
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp)) {
        // Empty leading column, same width as [CalendarDayRow]'s own non-tickable branch, so a
        // RECORDED row's label lines up with a SCHEDULE row's rather than starting flush left.
        Column(Modifier.padding(start = 12.dp, end = 12.dp)) {}
        Column {
            Text(
                note.title ?: "Untitled recording",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                "${clockTime(note.startedAt)} - ${formatVoiceNoteDuration(note.startedAt, note.endedAt)}",
                style = LegionType.stamp,
                color = sem.faint,
            )
        }
    }
}

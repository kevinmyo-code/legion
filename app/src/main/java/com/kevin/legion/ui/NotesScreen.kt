package com.kevin.legion.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kevin.legion.calendar.CalendarProvider
import com.kevin.legion.notes.NotesController
import com.kevin.legion.notes.Recurrence
import com.kevin.legion.notes.endFromItem
import com.kevin.legion.notes.ruleFromItem
import com.kevin.legion.ui.common.DeckPane
import com.kevin.legion.ui.common.DeckRow
import com.kevin.legion.ui.common.DeckTag
import com.kevin.legion.ui.common.DeckTagStyle
import com.kevin.legion.ui.common.EqualHeightRow
import com.kevin.legion.ui.common.HalfTile
import com.kevin.legion.ui.common.dailyBuckets
import com.kevin.legion.ui.notes.CalendarNotLinkedRow
import com.kevin.legion.ui.notes.DashedHairline
import com.kevin.legion.ui.notes.DayEventsDialog
import com.kevin.legion.ui.notes.GroceryScreen
import com.kevin.legion.ui.notes.InboxScreen
import com.kevin.legion.ui.notes.MissedRow
import com.kevin.legion.ui.notes.MissedRowView
import com.kevin.legion.ui.notes.MonthCell
import com.kevin.legion.ui.notes.NotificationsBlockedBanner
import com.kevin.legion.ui.notes.buildListsTile
import com.kevin.legion.ui.notes.buildMissedRows
import com.kevin.legion.ui.notes.buildMissedTile
import com.kevin.legion.ui.notes.buildMonthCells
import com.kevin.legion.ui.notes.buildWeekAheadDayCounts
import com.kevin.legion.ui.notes.entriesForDay
import com.kevin.legion.ui.notes.eventDotCount
import com.kevin.legion.ui.notes.mergeAgenda
import com.kevin.legion.ui.theme.LegionType
import com.kevin.legion.ui.theme.LocalLegionSemantics
import com.kevin.legion.util.clockTime
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale
import androidx.compose.material3.MaterialTheme as M3
import kotlinx.coroutines.launch

/**
 * `notes` tab - the sixth top-level destination. **One screen, one stream** (Kevin, 2026-08-11:
 * "merge the list to just one type of item. 1 list. many items appended. all with their own due
 * dates").
 *
 * This supersedes ticket 07's three-screen shape (list-of-lists -> a single list, with a LISTS |
 * CALENDAR toggle over the same data). The toggle, the drill-down and the separate agenda view are
 * all gone from the nav: [com.kevin.legion.ui.notes.InboxScreen] reads every item across every list
 * into one due-date-sorted stream, which IS the agenda, so there is nothing left for a second view
 * to show. See that file's doc comment for what the old shape cost and why the multi-list MODEL
 * (and its voice verbs) survives underneath unchanged.
 *
 * [openItemId]/[openItemNonce] are the notification-tap deep link (ticket 12: "tapping the
 * notification opens the item") - [MainActivity] reads
 * [com.kevin.legion.service.ReminderAlarmReceiver.EXTRA_OPEN_ITEM_ID] off the launching `Intent` and
 * passes both down. They now go straight through to the inbox: with no list to navigate into first,
 * the item id alone is enough to open its editor.
 *
 * The MISSED banner (ticket 12) and the app-wide notifications-blocked warning still render ABOVE
 * the stream - a missed reminder or a blocked channel is true regardless of what you are looking at.
 */
@Composable
fun NotesScreen(openItemId: Long? = null, openItemNonce: Int = 0) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var missedRows by remember { mutableStateOf(emptyList<MissedRowView>()) }
    var missedReloadNonce by remember { mutableStateOf(0) }
    var notificationsBlocked by remember { mutableStateOf(false) }
    // Tapping a MISSED row opens that item's editor down in the stream - the same deep-link path a
    // notification tap uses, so there is one way in rather than two.
    var missedOpenId by remember { mutableStateOf<Long?>(null) }
    var missedOpenNonce by remember { mutableStateOf(0) }
    var mode by remember { mutableStateOf(LogMode.ITEMS) }

    // Quant-viz ticket 14: the LOG tab's month calendar, replacing the WEEK AHEAD strip (Kevin,
    // 2026-08-14: "lets make it a calendar with events on it"). [displayedMonth] is its own state
    // (not tied to "today") so PREV/NEXT can browse away from the current month; [selectedDayStart]
    // is the tapped-day filter `InboxScreen` reads, cleared whenever the month changes (a selection
    // surviving a month change would filter the list against a day no longer on screen, reading as
    // an empty list for no visible reason - the ticket's own stated failure mode).
    var displayedMonth by remember { mutableStateOf(YearMonth.now()) }
    var calendarCollapsed by remember { mutableStateOf(false) }
    var selectedDayStart by remember { mutableStateOf<Long?>(null) }
    var monthLoading by remember { mutableStateOf(true) }
    var monthCells by remember { mutableStateOf(emptyList<MonthCell>()) }
    // Quant-viz ticket 16: the SAME `merged` list the month load already builds to derive the grid's
    // dot counts, kept in state so the day-tap popup below renders from it too - not a second fetch,
    // not a second stream, so the dots and the popup can never disagree (the ticket's own stated
    // failure mode).
    var monthEntries by remember { mutableStateOf(emptyList<AgendaEntry>()) }
    // Ticket 16: which in-month day cell was tapped, or null when no popup is open. Leading/trailing
    // blank cells have no [MonthCell.dayStart] to set this with, so they stay untappable by
    // construction (see [MonthCellView]'s own `onClick` gate).
    var popupDayStart by remember { mutableStateOf<Long?>(null) }
    var monthCalendarLinked by remember { mutableStateOf(true) }
    // Mission-control ticket 16's LOG build: the TODAY hero pane's own today-only window - a
    // SEPARATE fetch from [monthEntries] above (which is the browsable MONTH window and goes stale
    // for "today" the instant PREV/NEXT moves [displayedMonth] away from the current month). Reloads
    // on [missedReloadNonce], the same cadence MISSED already uses, never [displayedMonth] - TODAY
    // must stay today regardless of what month the calendar is browsing.
    var todayEntries by remember { mutableStateOf(emptyList<AgendaEntry>()) }
    var todayCalendarLinked by remember { mutableStateOf(true) }
    // Mission-control ticket 16's LISTS tile: open (not-done) count across the single merged list -
    // see [buildListsTile]'s own doc comment for why this is a LOCAL-only count, not the wider
    // Google-merged stream `ui/notes/InboxScreen.kt`'s own ITEMS badge counts.
    var openListCount by remember { mutableStateOf(0) }
    // Same "request both, in context" shape TodayScreen's own calendar-grant launcher uses -
    // a grant just bumps the shared reload nonce so the grid re-queries on the same load path a
    // fresh screen open uses, rather than this screen inventing a second reload mechanism.
    val requestCalendar = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { _ ->
        missedReloadNonce++
    }

    LaunchedEffect(missedReloadNonce) {
        val missed = NotesController.missedItems(context)
        val names = NotesController.listNamesById(context)
        missedRows = buildMissedRows(missed, names)
        notificationsBlocked = NotesController.anyNotificationsBlocked(context)

        // Mission-control ticket 16's TODAY hero: today's timed items, one-off and recurring,
        // merged with today's Google events - the SAME NotesController.timedItemsInWindow /
        // allRecurringItems + Recurrence.occurrencesInWindow pair, and the SAME mergeAgenda,
        // `ui/TodayScreen.kt`'s own AGENDA pane uses for its identical [dayStart, dayEnd) window.
        // Restated here (that effect is private to TodayScreen) rather than shared - the same "same
        // calls, different window" move this file's own month effect below already makes for the
        // grid, just windowed a day instead of a month.
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val todayStartMs = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val todayEndMs = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
        val todayOneOff = NotesController.timedItemsInWindow(context, todayStartMs, todayEndMs)
            .filter { !it.done }
            .mapNotNull { item -> item.startsAt?.let { AgendaEntry(item.text, it, item.allDay) } }
        val todayRecurring = NotesController.allRecurringItems(context).flatMap { item ->
            val startsAt = item.startsAt
            val rule = startsAt?.let { ruleFromItem(item) }
            if (startsAt == null || rule == null) {
                emptyList()
            } else {
                val skips = NotesController.skippedDates(context, item)
                Recurrence.occurrencesInWindow(startsAt, rule, endFromItem(item), skips, todayStartMs, todayEndMs)
                    .map { occMs -> AgendaEntry(item.text, occMs, item.allDay) }
            }
        }
        todayCalendarLinked = CalendarProvider.hasReadPermission(context)
        val todayGoogleEvents = if (todayCalendarLinked) {
            CalendarProvider.eventsInWindow(context, todayStartMs, todayEndMs)
        } else {
            emptyList()
        }
        todayEntries = mergeAgenda(todayOneOff + todayRecurring, todayGoogleEvents)

        // Mission-control ticket 16's LISTS tile: open count, restated as a count-only read of the
        // SAME NotesController.allItems `ui/notes/InboxScreen.kt` reads for its own stream - never a
        // second row-building path, see buildListsTile's own doc comment for why this stays
        // LOCAL-only rather than folding in the Google-merged window.
        openListCount = NotesController.allItems(context).count { !it.done }
    }

    // Reloads on a MISSED refresh (a grant lands here too) OR a month change - kept as its own
    // effect, keyed separately from the MISSED block above, so browsing months never re-touches
    // the missed-reminders read this screen already does on its own cadence.
    LaunchedEffect(missedReloadNonce, displayedMonth) {
        // The displayed month, the SAME [NotesController] pair (`timedItemsInWindow` for one-offs,
        // `allRecurringItems` + `skippedDates` + [Recurrence.occurrencesInWindow] for recurrences)
        // `ui/TodayScreen.kt`'s own AGENDA pane reads for its single-day window, and the SAME
        // [mergeAgenda] it folds Google events in with - just windowed over a month instead of a
        // day, never a second query shape.
        val zone = ZoneId.systemDefault()
        val monthStart = displayedMonth.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val monthEnd = displayedMonth.atEndOfMonth().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
        val dayStarts = dailyBuckets(monthStart, monthEnd, zone)

        val oneOff = NotesController.timedItemsInWindow(context, monthStart, monthEnd)
            .filter { !it.done }
            .mapNotNull { item -> item.startsAt?.let { AgendaEntry(item.text, it, item.allDay) } }
        val recurringMonth = NotesController.allRecurringItems(context).flatMap { item ->
            val startsAt = item.startsAt
            val rule = startsAt?.let { ruleFromItem(item) }
            if (startsAt == null || rule == null) {
                emptyList()
            } else {
                val skips = NotesController.skippedDates(context, item)
                Recurrence.occurrencesInWindow(startsAt, rule, endFromItem(item), skips, monthStart, monthEnd)
                    .map { occMs -> AgendaEntry(item.text, occMs, item.allDay) }
            }
        }

        val calendarLinked = CalendarProvider.hasReadPermission(context)
        monthCalendarLinked = calendarLinked
        // Ticket 14 (unlike the strip it replaces): calendar-not-linked still draws the grid from
        // LOCAL items - they are real - rather than suppressing it entirely. CalendarNotLinkedRow
        // says so in words directly beneath the grid, so it is never silently presenting a partial
        // picture as complete.
        val merged = if (calendarLinked) {
            val googleEvents = CalendarProvider.eventsInWindow(context, monthStart, monthEnd)
            mergeAgenda(oneOff + recurringMonth, googleEvents)
        } else {
            oneOff + recurringMonth
        }
        val counts = buildWeekAheadDayCounts(merged, dayStarts, zone)
        val countsByDayStart = dayStarts.zip(counts).toMap()
        monthCells = buildMonthCells(displayedMonth, countsByDayStart, zone)
        // Ticket 16: the exact list [counts]/[monthCells]'s dots were bucketed from, kept for the
        // day-tap popup - see that state's own doc comment for why this is a store, not a re-fetch.
        monthEntries = merged
        monthLoading = false
    }

    val sem = LocalLegionSemantics.current

    // Quant-viz ticket 16: the day-tap popup, rendered from [monthEntries] via [entriesForDay] -
    // NOT a second fetch, the SAME list [buildWeekAheadDayCounts] bucketed to draw the dots on the
    // cell that was just tapped, so the two can never disagree.
    popupDayStart?.let { day ->
        DayEventsDialog(
            dayStart = day,
            entries = entriesForDay(monthEntries, day),
            onShowInList = { selectedDayStart = day; popupDayStart = null },
            onDismiss = { popupDayStart = null },
        )
    }

    when (mode) {
        // Quant-viz ticket 15: ITEMS mode's furniture (title, calendar, MISSED, GoalsPanel,
        // LogModeToggle) is no longer a stack of fixed `Column` children ABOVE `InboxScreen`'s own
        // list - every one of ticket 14's fixed headers is what buried the list below the fold with
        // the calendar expanded (see the ticket's own measurement table). It is instead emitted
        // straight into [InboxScreen]'s `header` slot, so [InboxScreen]'s `LazyColumn` becomes the
        // WHOLE screen's only scroll surface: scrolling the list scrolls the calendar away too,
        // which is exactly "the visual obscures the scroll interface" answered rather than deferred.
        LogMode.ITEMS -> InboxScreen(
            highlightItemId = missedOpenId ?: openItemId,
            highlightNonce = missedOpenNonce + openItemNonce,
            dayFilterStartMs = selectedDayStart,
            onClearDayFilter = { selectedDayStart = null },
            header = {
                item(key = "notes-title") {
                    Text(
                        "NOTES",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    )
                }

                // Mission-control ticket 16's LOG build, ticket 12's inventory: TODAY (FULL, hero) -
                // today's items, first thing under the title, the same weight `ui/TodayScreen.kt`'s
                // own INTAKE/AGENDA panes carry at the top of HOME. See [todayEntries]'s own state
                // doc comment for why this is a SEPARATE fetch from the browsable month grid below.
                item(key = "notes-today") {
                    Column {
                        TodayPane(
                            entries = todayEntries,
                            calendarLinked = todayCalendarLinked,
                            onGrantCalendar = { requestCalendar.launch(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)) },
                        )
                        DashedHairline()
                    }
                }

                // Mission-control ticket 16's LOG build, ticket 12's inventory: MISSED / LISTS
                // (HALF, one row of two) - the exact [EqualHeightRow]/[HalfTile] shell HOME's own
                // BIO/CRED/FLEET/LOG row and BIO's/FLEET's own tile rows already use, never a bare
                // `Row(IntrinsicSize.Min)` (see [EqualHeightRow]'s own doc comment for why that
                // crashes on-device against a [DeckPane] child). MISSED's own FULL-detail rows below
                // (per-row DISMISS, tap-to-open) are UNCHANGED and kept, deliberately - see
                // [com.kevin.legion.ui.notes.buildMissedTile]'s own doc comment for why this ticket's
                // HALF categorization and the screen's pre-existing real controls both survive here,
                // rather than one replacing the other.
                item(key = "notes-tile-row") {
                    val missedTile = buildMissedTile(missedRows)
                    val listsTile = buildListsTile(openListCount)
                    Column {
                        EqualHeightRow(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalGap = 9.dp) {
                            HalfTile(header = "Missed", hero = missedTile.hero, caption = missedTile.caption)
                            HalfTile(header = "Lists", hero = listsTile.hero, caption = listsTile.caption)
                        }
                        DashedHairline()
                    }
                }

                if (missedRows.isNotEmpty()) {
                    item(key = "notes-missed-header") {
                        // No `headerAccent` here any more - the tile row above already carries this
                        // exact count (`missedRows.size`, the same field both read), so a second
                        // rendering of it here would be a driftable duplicate rather than a useful one.
                        DeckPane(header = "MISSED") {}
                    }
                    // Quant-viz ticket 15 point 3: no nested LazyColumn. A same-direction
                    // scrollable inside a scrollable is exactly the shape that made this screen hard
                    // to reason about - MISSED is capped to [MISSED_INLINE_LIMIT] plain items in the
                    // OUTER list instead, with a worded "+ N more" for the rest (density said in
                    // words, never by a scrollbar a driver has to discover). The tile row above still
                    // carries the TRUE total, uncapped.
                    val shownMissed = missedRows.take(MISSED_INLINE_LIMIT)
                    items(shownMissed, key = { "notes-missed-${it.id}" }) { row ->
                        Column {
                            MissedRow(
                                row = row,
                                // Opening a missed item now just scrolls the same stream's editor
                                // open, so the tap is handled by the inbox itself via the deep-link
                                // path.
                                onOpen = { missedOpenId = row.id; missedOpenNonce++ },
                                onDismiss = {
                                    scope.launch {
                                        val item = NotesController.itemById(context, row.id) ?: return@launch
                                        NotesController.dismissMissed(context, item)
                                        missedReloadNonce++
                                    }
                                },
                            )
                            DashedHairline()
                        }
                    }
                    if (missedRows.size > MISSED_INLINE_LIMIT) {
                        item(key = "notes-missed-more") {
                            Text(
                                "+ ${missedRows.size - MISSED_INLINE_LIMIT} more",
                                style = LegionType.stamp,
                                color = sem.faint,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            )
                        }
                    }
                    item(key = "notes-missed-hairline") { DashedHairline() }
                }

                if (notificationsBlocked) {
                    item(key = "notes-notifications-blocked") {
                        Column {
                            NotificationsBlockedBanner()
                            DashedHairline()
                        }
                    }
                }

                // Mission-control ticket 16's LOG build, ticket 12's inventory: CALENDAR / INBOX
                // (FULL) - the month grid here, then [LogModeToggle] plus this composable's own
                // stream/[GroceryScreen] content below (the INBOX half of the same named row), one
                // continuous FULL section at the tail of the header slot. Quant-viz ticket 14: the
                // month calendar - Notes' only real series is schedule density, so this is the one
                // glanceable graphic the tab gets (the WEEK AHEAD strip's own map taste call 1,
                // carried over). Suppressed entirely while still loading, same "nothing drawn before
                // the real read lands" posture the rest of this screen's sections use.
                if (!monthLoading) {
                    item(key = "notes-calendar") {
                        Column {
                            MonthCalendar(
                                calendarLinked = monthCalendarLinked,
                                month = displayedMonth,
                                cells = monthCells,
                                collapsed = calendarCollapsed,
                                selectedDayStart = selectedDayStart,
                                onToggleCollapsed = { calendarCollapsed = !calendarCollapsed },
                                // Ticket 16: changing month closes the popup too - same reasoning
                                // ticket 14 used for clearing the day filter, a popup left open for a
                                // day no longer on screen would read as stuck for no visible reason.
                                onPrevMonth = { selectedDayStart = null; popupDayStart = null; displayedMonth = displayedMonth.minusMonths(1) },
                                onNextMonth = { selectedDayStart = null; popupDayStart = null; displayedMonth = displayedMonth.plusMonths(1) },
                                // Ticket 16: tapping a day cell now opens the events popup rather than
                                // filtering the list directly - the filter is still one tap away, via
                                // the popup's own SHOW IN LIST button, which is what keeps ticket 14's
                                // day-filter feature alive underneath this.
                                onSelectDay = { tapped -> popupDayStart = tapped },
                                onGrantCalendar = { requestCalendar.launch(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)) },
                            )
                            DashedHairline()
                        }
                    }
                }

                // Ticket 19's GOALS panel - LOG aspect (com.kevin.legion.advisor.playbooks
                // .LogPlaybook's own doc comment). Same self-contained shape as
                // com.kevin.legion.ui.BodyScreen's - see GoalsPanel's own doc comment for why it
                // manages its own load rather than joining this screen's state. A fixed number of
                // goal rows (no pagination), so it is safe as one lazy item despite its own internal
                // `Column`/`DeckPane` layout.
                item(key = "notes-goals") {
                    Column {
                        com.kevin.legion.ui.goals.GoalsPanel(aspect = "log")
                        DashedHairline()
                    }
                }

                item(key = "notes-mode-toggle") {
                    Column {
                        LogModeToggle(mode, onSelect = { mode = it })
                        DashedHairline()
                    }
                }
            },
        )
        // Quant-viz ticket 15 point 4: GROCERY is ITEMS-mode furniture's opposite by design - a
        // shopping list is a fast in-and-out surface, and neither the month calendar nor GoalsPanel
        // belongs above it. [GroceryScreen] keeps its OWN pre-existing structure untouched (its own
        // root `LazyColumn`), so this branch stays the ticket-14 shape: a small fixed header (title +
        // toggle only, ~76dp) above a `Box(Modifier.weight(1f))` - the same structural guarantee
        // ticket 14 established, just no longer shared with ITEMS mode's now-much-taller furniture.
        LogMode.GROCERY -> Column(Modifier.fillMaxSize()) {
            Text(
                "NOTES",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            )
            LogModeToggle(mode, onSelect = { mode = it })
            DashedHairline()
            Box(Modifier.weight(1f)) {
                GroceryScreen()
            }
        }
    }
}

/** Quant-viz ticket 15 point 3: the MISSED banner's own cap when it moved from a nested,
 * independently-scrolling `LazyColumn` (capped at 220dp) into plain items in the outer list - see
 * `header`'s own doc comment inside [NotesScreen] for why a second scroll surface is gone entirely. */
private const val MISSED_INLINE_LIMIT = 4

/**
 * The two things the LOG tab holds. They are separate MODES, not two views of one dataset (contrast
 * the LISTS | CALENDAR toggle this replaced, which was two renderings of the same rows): an item on
 * the stream is kept until removed, while a grocery line is expected to be destroyed within the
 * hour. See [com.kevin.legion.data.local.GroceryItem]'s doc comment for why that difference earns a
 * separate table rather than a flag on `list_items`.
 */
private enum class LogMode { ITEMS, GROCERY }

@Composable
private fun LogModeToggle(selected: LogMode, onSelect: (LogMode) -> Unit) {
    val sem = LocalLegionSemantics.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        LogMode.entries.forEach { candidate ->
            TextButton(onClick = { onSelect(candidate) }) {
                Text(
                    candidate.name,
                    style = LegionType.stamp,
                    color = if (candidate == selected) M3.colorScheme.primary else sem.faint,
                )
            }
        }
    }
}

/**
 * Mission-control ticket 16's LOG build: the TODAY hero (ticket 12's inventory row, FULL) - today's
 * timed items, one-off and recurring, merged with today's Google events, the same
 * [DeckRow]/[AgendaSource.GOOGLE] `CAL` tag treatment `ui/TodayScreen.kt`'s own AGENDA pane uses for
 * its identical [entries]. Restated here rather than reusing that pane's private `AgendaRow`
 * directly - Kotlin's top-level `private` is FILE-private, not package-private, so a `private fun`
 * in `TodayScreen.kt` is invisible from this file even though both share `package com.kevin.legion.ui`
 * (traced: `AgendaRow` carries no package/internal modifier, only `private`). Same restatement
 * posture `TodayGapResolvers.kt`'s own `buildAlertRows` doc comment already states for a different
 * cross-file visibility gap.
 *
 * Empty state reads `NOTHING DUE` through [DeckRow]'s own mint `value` slot - a real, checked
 * absence, not a loading placeholder (see [todayEntries]'s own state doc comment for why this pane's
 * load never straddles the browsable month grid below it).
 */
@Composable
private fun TodayPane(entries: List<AgendaEntry>, calendarLinked: Boolean, onGrantCalendar: () -> Unit) {
    DeckPane(header = "Today", headerAccent = entries.size.toString()) {
        if (entries.isEmpty()) {
            DeckRow(label = "Today", value = "NOTHING DUE")
        } else {
            entries.forEach { entry ->
                DeckRow(
                    label = entry.label,
                    value = if (entry.allDay) "ALL DAY" else clockTime(entry.timeMs),
                    tag = if (entry.source == AgendaSource.GOOGLE) {
                        { DeckTag("CAL", DeckTagStyle.OUTLINE_MUTED) }
                    } else {
                        null
                    },
                )
            }
        }
        // Same "not linked" posture as [MonthCalendar] below: today's LOCAL items still render even
        // when Google is unread, and the gap is said in words rather than silently narrowing the
        // pane's own claim.
        if (!calendarLinked) {
            CalendarNotLinkedRow(
                "Calendar not linked - grant access to see today's Google events here too.",
                onGrant = onGrantCalendar,
            )
        }
    }
}

/**
 * Quant-viz ticket 14's Notes-tab month calendar, replacing the WEEK AHEAD strip - Kevin,
 * 2026-08-14: "i cant scroll down anymore. the visual obscures the scroll interface. lets make it
 * a calendar with events on it." [cells] is [buildMonthCells]'s own output, already padded to
 * whole weeks; this composable only lays them out and colours today/[selectedDayStart].
 *
 * **Calendar-not-linked keeps drawing the grid from LOCAL items** (unlike the strip it replaces,
 * which suppressed itself entirely) - [CalendarNotLinkedRow] renders directly beneath the grid so
 * the picture is never silently presented as complete when Google events are unread.
 *
 * [collapsed] hides everything below the month header row - Kevin's direct complaint answered:
 * the graphic can always be got out of the way without leaving the tab or losing the month/day
 * state underneath it.
 */
@Composable
private fun MonthCalendar(
    calendarLinked: Boolean,
    month: YearMonth,
    cells: List<MonthCell>,
    collapsed: Boolean,
    selectedDayStart: Long?,
    onToggleCollapsed: () -> Unit,
    onPrevMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onSelectDay: (Long) -> Unit,
    onGrantCalendar: () -> Unit,
) {
    val sem = LocalLegionSemantics.current
    val zone = ZoneId.systemDefault()
    val todayStart = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()

    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        // Prev/next month, same pattern as `ui/ledger/BudgetSection.kt`'s `< MONTH >` navigator -
        // this calendar has no natural min/max bound (there is no coverage concept the way ledger
        // has statements), so both arrows stay enabled always rather than growing an artificial one.
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onPrevMonth) {
                Text("<", style = LegionType.stamp, color = MaterialTheme.colorScheme.primary)
            }
            Text(monthGridLabel(month), style = LegionType.reading, color = MaterialTheme.colorScheme.onSurface)
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onToggleCollapsed) {
                    Text(if (collapsed) "MONTH" else "HIDE", style = LegionType.stamp, color = sem.faint)
                }
                TextButton(onClick = onNextMonth) {
                    Text(">", style = LegionType.stamp, color = MaterialTheme.colorScheme.primary)
                }
            }
        }

        if (!collapsed) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                weekdayLetters().forEach { letter ->
                    Text(
                        letter,
                        style = LegionType.stamp,
                        color = sem.faint,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            // Cell height 34dp (ticket 14) - six week-rows plus the two header rows above stay
            // well under ~260dp total, giving height back to the inbox list below.
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                cells.chunked(7).forEach { week ->
                    Row(Modifier.fillMaxWidth()) {
                        week.forEach { cell ->
                            MonthCellView(
                                cell = cell,
                                isToday = cell.dayStart != null && cell.dayStart == todayStart,
                                isSelected = cell.dayStart != null && cell.dayStart == selectedDayStart,
                                onClick = { cell.dayStart?.let(onSelectDay) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
            if (!calendarLinked) {
                CalendarNotLinkedRow(
                    "Calendar not linked - grant access to see Google events on the calendar too.",
                    onGrant = onGrantCalendar,
                )
            }
        }
    }
}

/**
 * One 34dp cell: the day number, and up to three [eventDotCount] dots beneath it (density only -
 * never source or importance, per that function's own doc comment). Today fills with
 * [MaterialTheme.colorScheme.primary]/`onPrimary`, the SAME inverted-amber treatment
 * `ui/common/DeckCharts.kt`'s `DeckRangeSelector` already uses for its own selected stencil chip -
 * a selected (but not today's) day instead gets a 1dp primary border, so the two states can never
 * be confused for each other. A blank slot ([MonthCell.dayOfMonth] null) renders nothing and is
 * not clickable - it belongs to the neighbouring month, not this one.
 */
@Composable
private fun MonthCellView(cell: MonthCell, isToday: Boolean, isSelected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .height(34.dp)
            .let { if (isToday) it.background(MaterialTheme.colorScheme.primary) else it }
            .let { if (isSelected) it.border(1.dp, MaterialTheme.colorScheme.primary) else it }
            .let { if (cell.dayStart != null) it.clickable(onClick = onClick) else it },
        contentAlignment = Alignment.Center,
    ) {
        if (cell.dayOfMonth != null) {
            val dotColor = if (isToday) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    cell.dayOfMonth.toString(),
                    style = LegionType.stamp,
                    color = if (isToday) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                )
                val dots = eventDotCount(cell.eventCount)
                if (dots > 0) {
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        repeat(dots) {
                            Box(Modifier.size(3.dp).background(dotColor, CircleShape))
                        }
                    }
                }
            }
        }
    }
}

private val MONTH_GRID_LABEL: DateTimeFormatter = DateTimeFormatter.ofPattern("MMMM yyyy")

private fun monthGridLabel(month: YearMonth): String = month.format(MONTH_GRID_LABEL).uppercase()

/** The grid's weekday header letters, locale-ordered starting at [WeekFields.firstDayOfWeek] -
 * [buildMonthCells] lays its columns out in the SAME order, so the two must never diverge. */
private fun weekdayLetters(): List<String> {
    val firstDayOfWeek = WeekFields.of(Locale.getDefault()).firstDayOfWeek
    return (0 until 7).map { i -> firstDayOfWeek.plus(i.toLong()).getDisplayName(TextStyle.NARROW, Locale.ENGLISH) }
}

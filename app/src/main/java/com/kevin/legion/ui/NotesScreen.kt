package com.kevin.legion.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
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
import com.kevin.legion.notes.NotesController
import com.kevin.legion.ui.agenda.MonthCalendar
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
import com.kevin.legion.ui.theme.LegionType
import com.kevin.legion.ui.theme.LocalLegionSemantics
import com.kevin.legion.util.clockTime
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
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
    // One-today ticket 01 cut the runtime permission this launcher used to request - the local
    // `events` table needs none. [CalendarNotLinkedRow]'s `onGrantCalendar` slot below is now a
    // no-op rather than deleted outright: `monthCalendarLinked`/`todayCalendarLinked` are always
    // true post-cut, so that row can no longer render at all.

    LaunchedEffect(missedReloadNonce) {
        val missed = NotesController.missedItems(context)
        val names = NotesController.listNamesById(context)
        missedRows = buildMissedRows(missed, names)
        notificationsBlocked = NotesController.anyNotificationsBlocked(context)

        // Mission-control ticket 16's TODAY hero: today's timed items, one-off and recurring,
        // merged with today's Google events - the SAME NotesController.timedItemsInWindow /
        // allRecurringItems + Recurrence.occurrencesInWindow pair, and the SAME mergeAgenda,
        // `ui/TodayScreen.kt`'s own AGENDA pane uses for its identical [dayStart, dayEnd) window.
        // **Extracted into `ui/agenda/DayAgenda.kt`'s [buildDayAgenda]** - this was restated here
        // verbatim (Kotlin top-level `private` is file-scoped, so that effect was unreachable from
        // this file) alongside a third, near-identical copy for the month grid below; all three are
        // now the one shared builder, called with a different window.
        val zone = ZoneId.systemDefault()
        // One-today ticket 01 cut the live `CalendarContract` read this used to gate on - the local
        // `events` table is always readable, so `todayCalendarLinked` is always true post-cut.
        todayCalendarLinked = true
        todayEntries = com.kevin.legion.ui.agenda.buildDayAgenda(context, LocalDate.now(zone), zone)

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
        // The displayed month, the SAME shared builder [buildDayAgenda]/[buildMonthAgenda]'s own
        // `ui/agenda/DayAgenda.kt` doc comment describes - windowed over a month instead of a day,
        // never a second query shape. This was the third verbatim restatement of the same
        // NotesController + Recurrence + mergeAgenda triple (see the TODAY-hero effect above for
        // the other one); all three now call [com.kevin.legion.ui.agenda.buildAgendaInWindow]'s
        // month-windowed wrapper.
        val zone = ZoneId.systemDefault()
        val monthStart = displayedMonth.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val monthEnd = displayedMonth.atEndOfMonth().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
        val dayStarts = dailyBuckets(monthStart, monthEnd, zone)

        // One-today ticket 01 cut the live `CalendarContract` read this used to gate on - the local
        // `events` table is always readable, so `monthCalendarLinked` is always true post-cut
        // (kept as a field for [CalendarNotLinkedRow]'s plumbing, which can no longer fire).
        monthCalendarLinked = true
        val merged = com.kevin.legion.ui.agenda.buildMonthAgenda(context, displayedMonth, zone)
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
                            onGrantCalendar = {},
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
                                onGrantCalendar = {},
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

// MonthCalendar/MonthCellView (and their weekdayLetters/monthGridLabel helpers) moved to
// `ui/agenda/MonthCalendar.kt` and made public - this file's own `private fun` copies were
// invisible outside this file (Kotlin top-level `private` is file-scoped, not package-scoped),
// which is exactly the gap `TodayPane`'s doc comment above complains about for `AgendaRow`.
// Imported from `com.kevin.legion.ui.agenda` now.

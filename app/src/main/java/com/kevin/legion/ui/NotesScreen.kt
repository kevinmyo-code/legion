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
import com.kevin.legion.ui.common.DeckPane
import com.kevin.legion.ui.common.DeckRow
import com.kevin.legion.ui.common.DeckTag
import com.kevin.legion.ui.common.DeckTagStyle
import com.kevin.legion.ui.common.EqualHeightRow
import com.kevin.legion.ui.common.HalfTile
import com.kevin.legion.ui.notes.CalendarNotLinkedRow
import com.kevin.legion.ui.notes.DashedHairline
import com.kevin.legion.ui.notes.GroceryScreen
import com.kevin.legion.ui.notes.InboxScreen
import com.kevin.legion.ui.notes.MissedRow
import com.kevin.legion.ui.notes.MissedRowView
import com.kevin.legion.ui.notes.NotificationsBlockedBanner
import com.kevin.legion.ui.notes.buildListsTile
import com.kevin.legion.ui.notes.buildMissedRows
import com.kevin.legion.ui.notes.buildMissedTile
import com.kevin.legion.ui.theme.LegionType
import com.kevin.legion.ui.theme.LocalLegionSemantics
import com.kevin.legion.util.clockTime
import java.time.LocalDate
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
 *
 * [startMode]/[startModeNonce] (fixed on-device 2026-09-01, Kevin: "meters > lists are hard to
 * use... groceries trip tapping it brings me to not a grocery list. tapping persistant list also
 * brings me to the old calendar and goals view") let [MetersScreen]'s two Lists rows land on the
 * mode they name - the "Groceries trip" row opens [LogMode.GROCERY], "Persistent list" opens
 * [LogMode.ITEMS] - same nonce-keyed shape [openItemId]/[openItemNonce] already use for the
 * notification deep link, since a repeat tap on the SAME row while already on this screen still
 * has to re-apply (an unchanged nullable value alone would be skipped as a no-op state write).
 */
@Composable
fun NotesScreen(
    openItemId: Long? = null,
    openItemNonce: Int = 0,
    startMode: LogMode? = null,
    startModeNonce: Int = 0,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var missedRows by remember { mutableStateOf(emptyList<MissedRowView>()) }
    var missedReloadNonce by remember { mutableStateOf(0) }
    var notificationsBlocked by remember { mutableStateOf(false) }
    // Tapping a MISSED row opens that item's editor down in the stream - the same deep-link path a
    // notification tap uses, so there is one way in rather than two.
    var missedOpenId by remember { mutableStateOf<Long?>(null) }
    var missedOpenNonce by remember { mutableStateOf(0) }
    // Keyed on [startModeNonce] (not [startMode] itself) so a caller's repeat request for the SAME
    // mode still applies - see this function's own doc comment. A user's own [LogModeToggle] tap
    // still wins until the next nonce bump, matching [pendingMoneyCategory]'s identical shape on
    // `ui/MainActivity.kt`'s `LegionShell`.
    var mode by remember(startModeNonce) { mutableStateOf(startMode ?: LogMode.ITEMS) }

    // CORRECTED 2026-09-01: quant-viz ticket 14 put a month calendar here (Kevin, 2026-08-14:
    // "lets make it a calendar with events on it"), then ticket 16 added a day-tap popup and a
    // day-filtered list over it. Both are GONE (Kevin, 2026-09-01: tapping a day on the Meters
    // "Persistent list" row "brings me to the old calendar" - the calendar home built in the
    // meantime, `ui/CalendarScreen.kt`, owns this now and this was the duplicate). Deleted with it:
    // [displayedMonth]/[calendarCollapsed]/[selectedDayStart]/[monthLoading]/[monthCells]/
    // [monthEntries]/[popupDayStart]/[monthCalendarLinked], their own `LaunchedEffect`, the
    // [DayEventsDialog] popup, and the `dayFilterStartMs`/`onClearDayFilter` wiring into
    // [InboxScreen] below (both keep their own default-`null`/no-op parameters on that screen for
    // any future caller; this was simply the only one). [MonthCalendar] the composable is untouched
    // - `ui/CalendarScreen.kt` still uses it.
    // Mission-control ticket 16's LOG build: the TODAY hero pane's own today-only window - its own
    // fetch, reloading on [missedReloadNonce] (the same cadence MISSED already uses). CORRECTED
    // 2026-09-01: this used to also be kept separate from a now-deleted browsable month window (see
    // this state block's own doc comment above) - that reason is gone with the month calendar, but
    // the pane still owns its read rather than joining [InboxScreen]'s stream below, unrelated to
    // the calendar removal.
    var todayEntries by remember { mutableStateOf(emptyList<AgendaEntry>()) }
    var todayCalendarLinked by remember { mutableStateOf(true) }
    // Mission-control ticket 16's LISTS tile: open (not-done) count across the single merged list -
    // see [buildListsTile]'s own doc comment for why this is a LOCAL-only count, not the wider
    // Google-merged stream `ui/notes/InboxScreen.kt`'s own ITEMS badge counts.
    var openListCount by remember { mutableStateOf(0) }
    // One-today ticket 01 cut the runtime permission this launcher used to request - the local
    // `events` table needs none. [CalendarNotLinkedRow]'s `onGrantCalendar` slot below is now a
    // no-op rather than deleted outright: `todayCalendarLinked` is always true post-cut, so that
    // row can no longer render at all.

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

    // CORRECTED 2026-09-01: the month-load `LaunchedEffect` and the day-tap [DayEventsDialog]
    // popup that used to sit here are gone with the month calendar - see the state block's own
    // doc comment above for what left and why.
    val sem = LocalLegionSemantics.current

    when (mode) {
        // Quant-viz ticket 15: ITEMS mode's furniture (title, calendar, MISSED, GoalsPanel,
        // LogModeToggle - the calendar itself is gone as of 2026-09-01, see this function's own
        // state-block doc comment; the rest of the shape stands) is no longer a stack of fixed
        // `Column` children ABOVE `InboxScreen`'s own list - every one of ticket 14's fixed headers
        // is what buried the list below the fold with the calendar expanded (see the ticket's own
        // measurement table). It is instead emitted straight into [InboxScreen]'s `header` slot, so
        // [InboxScreen]'s `LazyColumn` becomes the WHOLE screen's only scroll surface: scrolling the
        // list scrolls the rest of the furniture away too, which is exactly "the visual obscures the
        // scroll interface" answered rather than deferred.
        // dayFilterStartMs/onClearDayFilter no longer passed (CORRECTED 2026-09-01) - both stay
        // default-null/no-op on [InboxScreen] itself; this was their only real caller, and it went
        // with the month calendar that used to set [selectedDayStart] (see this function's own
        // state-block doc comment).
        LogMode.ITEMS -> InboxScreen(
            highlightItemId = missedOpenId ?: openItemId,
            highlightNonce = missedOpenNonce + openItemNonce,
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
                // doc comment for why this is a SEPARATE fetch from the rest of this mode's reads.
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

                // CORRECTED 2026-09-01: mission-control ticket 16's CALENDAR/INBOX (FULL) section
                // used to put quant-viz ticket 14's month calendar here, above [LogModeToggle] and
                // this composable's own stream/[GroceryScreen] content. Kevin, 2026-09-01: tapping
                // a day on the Meters "Persistent list" row "brings me to the old calendar and goals
                // view" - `ui/CalendarScreen.kt`'s own month grid + day view is the one calendar home
                // now, so the duplicate here is gone (its `item(key = "notes-calendar")` block, and
                // the [monthLoading]/[monthCalendarLinked]/[displayedMonth]/[monthCells]/
                // [calendarCollapsed]/[selectedDayStart]/[popupDayStart] state feeding it, went with
                // it - see this function's own state-block doc comment). [LogModeToggle] below is
                // unaffected; it switches ITEMS/GROCERY, nothing to do with the calendar.

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
// Made non-private (fixed on-device 2026-09-01) so `ui/MainActivity.kt`'s LegionShell and
// `ui/MetersScreen.kt` can reference it too, threading a start mode down from the Meters Lists
// rows - see [NotesScreen]'s own `startMode` doc comment. Kotlin top-level `private` is FILE-scoped,
// not package-scoped (the same gap this file's own [TodayPane] doc comment already notes for a
// different symbol), so a plain visibility drop is all that was needed; both callers already sit
// in the same `com.kevin.legion.ui` package.
enum class LogMode { ITEMS, GROCERY }

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
 * absence, not a loading placeholder (see [todayEntries]'s own state doc comment for why this pane
 * owns its own fetch rather than joining another section's read).
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
        // CORRECTED 2026-09-01: this used to match [MonthCalendar]'s identical "not linked" posture
        // rendered further down this same screen - that instance is gone (see the state block's own
        // doc comment on [NotesScreen]), but the posture itself still holds here regardless: today's
        // LOCAL items still render even when Google is unread, and the gap is said in words rather
        // than silently narrowing the pane's own claim.
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

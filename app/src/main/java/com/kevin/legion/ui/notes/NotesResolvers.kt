@file:Suppress("MatchingDeclarationName") // ticket 10 slice C left one class; renaming would detach git history

package com.kevin.legion.ui.notes

import com.kevin.legion.backend.EventKind
import com.kevin.legion.data.local.ListItem
import com.kevin.legion.ui.AgendaSource
import com.kevin.legion.util.documentDateCompact
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Pure resolvers behind the notes screens. **CORRECTED one-today ticket 10 slice C, 2026-09-05:
 * `ui/NotesScreen.kt` and `ui/notes/InboxScreen.kt`, this file's own doc comment's original two
 * callers, are both DELETED** ("everything is a checklist now") - [InboxRowView]/[buildInboxRows]
 * below now serve `ui/CalendarScreen.kt`'s day view directly, the same merge, unchanged. This file's
 * own MISSED-banner resolvers ([MissedRowView]/`buildMissedRows`/`buildMissedTile`/`buildListsTile`)
 * and [LISTS_DO_NOT_SYNC_NOTICE] went with the screens that were their only callers (grep-confirmed
 * before deletion) - the underlying facts they read ([com.kevin.legion.notes.NotesController.missedItems]/
 * `dismissMissed`/`listNamesById`) are untouched and still feed the digest builders
 * (`advisor/digest/HomeDigestBuilder.kt`/`LogDigestBuilder.kt`), which is why NONE of those
 * `NotesController` functions were removed even though their only UI reader was.
 * No Compose, no Room, no `Context`: the same
 * "branching logic lives in a pure function, a composable only reads its output" split
 * [com.kevin.legion.ui.TodayGapResolvers]/[com.kevin.legion.ui.BodyGapResolvers]/
 * [com.kevin.legion.ui.ledger.LedgerPendingResolver] already use, so every branch here is a plain
 * JUnit test (`NotesResolversTest`), never a Robolectric one.
 *
 * **Dates use the DEVICE zone, not UTC.** `[com.kevin.legion.util].documentDate`'s own doc comment
 * draws this line: a value printed on a source document reads back in the zone it was WRITTEN in
 * (UTC, for every ledger/pantry parser), but a reminder's `startsAt` is a REAL future instant a
 * driver picked and `AlarmManager` will actually fire at - the same family as `shortDate`/
 * `compactDate`, which read in [ZoneId.systemDefault]. This file's formatters follow that, and the
 * screens that build a `startsAt` from a date/time picker must convert through the same zone or the
 * alarm fires at the wrong wall-clock hour.
 */

// ------------------------------------------------------------------- one-stream inbox (2026-08-11)

/**
 * One row of the single-stream inbox (originally `ui/notes/InboxScreen.kt`, now-deleted per this
 * file's own class doc - `ui/CalendarScreen.kt`'s day view is the one reader left) - Kevin,
 * 2026-08-11: "1 list, many items appended, all with their own due dates".
 *
 * Two points are the whole reason this replaced the old per-list row view:
 * - [dateLabel] is its OWN field, never folded into a general-purpose trigger string. The bug that
 *   started this was a due date that did not read as a due date on the row, and a date sharing one
 *   slot with "At Camping Store" and "Recurring - not tickable" is a date that can be crowded out.
 * - [tickable] is `!recurring` only. The checklist-vs-note split is gone: every item ticks, every
 *   item may carry a date, one type.
 */
data class InboxRowView(
    val id: Long,
    val text: String,
    val done: Boolean,
    /** False for a recurring [AgendaSource.LOCAL] item (ticket 04's "a recurring item cannot be
     * ticked") and, as of one-today ticket 08 ("events are not todos"), false for ANY
     * [AgendaSource.GOOGLE] row whose [com.kevin.legion.data.local.Event.kind] is
     * [com.kevin.legion.backend.EventKind.EVENT] - Kevin, verbatim: "i dont mark an event done, it
     * just passes whether or not i do it, like classes". **This reverses one-today ticket 02**
     * ("ticking an appointment"), which made every calendar-table row tickable; ticket 08's own
     * text calls that "wrong about the fix" - half those rows were never appointments, they were
     * assignments a heuristic could not tell apart from a class. A [AgendaSource.GOOGLE] row with
     * [com.kevin.legion.backend.EventKind.TASK] IS still tickable (nothing produces one yet -
     * Canvas is its own ticket), and writes [com.kevin.legion.data.local.Event.done] directly
     * (never through [com.kevin.legion.notes.NotesController]'s reminder-only funnel - see
     * [com.kevin.legion.notes.NotesController.tickAppointment]). */
    val tickable: Boolean,
    /** For a [AgendaSource.LOCAL] row: whether it repeats (ticket 04's own meaning). Always false
     * for a [AgendaSource.GOOGLE] row - one-today ticket 01 cut the live Google read that used to
     * carry a real `RRULE`/`RDATE` fact here; every appointment row now stored locally is already a
     * single expanded occurrence with no recurrence concept of its own (see
     * [com.kevin.legion.ui.notes.AppointmentEvent]'s own doc comment), so there is nothing left to
     * disambiguate on edit or delete - the old "this one or all of them" prompt
     * ([com.kevin.legion.ui.notes.CalendarEditResolver], now deleted) has nothing to ask. */
    val recurring: Boolean,
    /** "Aug 14" / "Aug 14, 8:00 AM" / "Repeats - next Aug 14", or null for an undated item. */
    val dateLabel: String?,
    /** Said in WORDS on the row, never colour alone - a past-due open item. Never true for a
     * recurring item (it re-arms forward) or a done one (nothing left to be late for). */
    val overdue: Boolean,
    /** A place trigger's words, kept separate from [dateLabel] - the two are mutually exclusive
     * (charting decision 4) but they are not the same fact and must not share a slot. */
    val placeLabel: String?,
    val exactDowngraded: Boolean,
    /** LOCAL (default) is a real [ListItem] row, tickable/editable/deletable through
     * [NotesController] as normal. GOOGLE (ticket 13 follow-up, `.scratch/google-account-
     * integration/issues/13-calendar-read.md`, Kevin 2026-08-13; editable as of ticket 22,
     * `.scratch/google-account-integration/issues/22-edit-calendar-entries-from-log.md`; **local as
     * of one-today ticket 01, "cut Google entirely"**) is a calendar-table row - `kind = 'event'`
     * (renamed from `'appointment'`, never tickable - one-today ticket 08) or `kind = 'task'`
     * (tickable, nothing produces one yet) - merged into the SAME stream, originally over a 90-day
     * forward window (the retired `InboxScreen`'s own `INBOX_CALENDAR_WINDOW_DAYS`, deleted with it
     * one-today ticket 10 slice C since it had no other reader; `ui/CalendarScreen.kt`'s day view
     * windows to one calendar day instead, its own `dayEndExclusive`). The retired `InboxRow` showed
     * a `CAL` tag ticket 13 put on `TodayScreen`'s AGENDA pane for this same row - the
     * distinction from a plain reminder is always in WORDS, never colour alone - even though, post
     * one-today, both live in the exact same local table, editable/deletable alike, just through
     * separate [NotesController] functions (see [tickable]'s own doc comment for why they are no
     * longer alike on tickability). */
    val source: AgendaSource = AgendaSource.LOCAL,
    /** The real (positive) [com.kevin.legion.data.local.Event.id] behind a [AgendaSource.GOOGLE]
     * row - null for a LOCAL row. **No longer a synthetic negative id** (one-today ticket 01
     * retired that Room-id-space trick along with the live `CalendarContract` read it existed for -
     * an appointment's [Event.id] is disjoint from a reminder's by construction,
     * [com.kevin.legion.data.local.Event.APPOINTMENT_ID_BASE]'s own doc comment - so [id] itself now
     * equals this field for a GOOGLE row). Carried as its own field anyway, matching this row's
     * pre-existing shape, so a caller never has to special-case which id space [id] is in. */
    val calendarEventId: Long? = null,
    /** The appointment's own `startsAt`, for pre-filling an edit dialog and computing its
     * duration. Null for a LOCAL row. */
    val calendarOccurrenceStartMs: Long? = null,
    /** The appointment's own `endsAt`, for pre-filling an edit dialog's time fields. Null for a
     * LOCAL row. */
    val calendarOccurrenceEndMs: Long? = null,
    /** Whether the appointment is all-day, for pre-filling an edit dialog and for choosing the
     * UTC-midnight-vs-device-zone convention a write uses. Null for a LOCAL row (that row's own
     * `allDay` lives on the underlying [ListItem] instead, unrelated to this field). */
    val calendarAllDay: Boolean? = null,
    /** Ticket 14: the row's own real instant, LOCAL zone-independent millis - `item.startsAt` for a
     * LOCAL row, [calendarOccurrenceStartMs] for a GOOGLE row (carried separately here rather than
     * read off that field at the render layer, since a LOCAL row has no occurrence field at all).
     * Null for an undated local row, which a day filter never matches - an undated item cannot
     * belong to any one day. This is what let the retired `InboxScreen`'s own `dayFilterStartMs`
     * param filter the ALREADY-BUILT row list without a second stream-building path, and is the
     * same reason `ui/CalendarScreen.kt`'s day view can filter [buildInboxRows]' output straight
     * against its own `[day, dayEndExclusive)` window today: every row this file ever produces
     * already carries the one timestamp a day filter needs. */
    val instantMs: Long? = null,
)

// INBOX_CALENDAR_WINDOW_DAYS (90-day forward Google-merge window) deleted one-today ticket 10
// slice C, 2026-09-05 - the retired `ui/notes/InboxScreen.kt` was its only reader
// (grep-confirmed before deletion); `ui/CalendarScreen.kt`'s day view windows to one calendar day
// at a time instead ([DAY_FILTER_WINDOW_MS], `ui/notes/NotesRows.kt`), which needs no separate
// named forward-window constant of its own.

/**
 * The whole inbox, in reading order: **dated items first, soonest due at the top; undated items
 * after, in the order they were appended.** [appointments] (ticket 13 follow-up) interleaves into
 * the DATED section by real start time via [mergeByTime] - the same chronological merge
 * `ui/TodayScreen.kt`'s AGENDA pane uses via [mergeAgenda], reused rather than forked. Appointment
 * rows never land in the undated section: every [AppointmentEvent] this file is handed carries a
 * real `startMs`, by construction of [com.kevin.legion.data.local.EventDao.activeByKindInWindow]'s
 * `startsAt IS NOT NULL` guard.
 *
 * A due date is the only ordering the driver asked for, so an item that has one always outranks one
 * that does not - sorting undated items to the top by `sortOrder` would bury exactly the rows the
 * screen exists to surface. Ticked items are NOT dropped or sunk: they stay in place, struck
 * through, so a mis-tap is undone where it happened rather than hunted for.
 *
 * [now] is passed in rather than read from the clock so [InboxRowView.overdue] is a pure function of
 * its inputs and testable without freezing time. [appointments] defaults to empty so every existing
 * two-argument call site (and test) is unchanged.
 */
fun buildInboxRows(items: List<ListItem>, now: Long, appointments: List<AppointmentEvent> = emptyList()): List<InboxRowView> {
    val dated = items.filter { it.startsAt != null }
    val undated = items.filter { it.startsAt == null }

    val datedLocalRows: List<Pair<Long, InboxRowView>> = dated.map { item -> item.startsAt!! to toInboxRowView(item, now) }
    val datedRows = mergeByTime(datedLocalRows, appointments) { event -> toInboxRowView(event) }

    return datedRows + undated.map { toInboxRowView(it, now) }
}

private fun toInboxRowView(item: ListItem, now: Long): InboxRowView {
    val recurring = item.repeatKind != null
    val startsAt = item.startsAt
    return InboxRowView(
        id = item.id,
        text = item.text,
        done = item.done,
        tickable = !recurring,
        recurring = recurring,
        dateLabel = startsAt?.let { at ->
            val whenLabel = if (item.allDay) formatDateOnly(at) else formatDateTime(at)
            if (recurring) "Repeats - next $whenLabel" else whenLabel
        },
        overdue = startsAt != null && !recurring && !item.done && startsAt < now,
        placeLabel = item.triggerPlaceLabel?.let { "At $it" },
        exactDowngraded = item.exactDowngraded,
        source = AgendaSource.LOCAL,
        instantMs = startsAt,
    )
}

/**
 * One [InboxRowView] per calendar-table row (ticket 13 follow-up; editable as of ticket 22;
 * **[tickable] no longer unconditionally true - one-today ticket 08 ("events are not todos")
 * reversed ticket 02's "every calendar-table row is tickable"** - see [InboxRowView.tickable]'s own
 * doc comment for why only a [com.kevin.legion.backend.EventKind.TASK] row still is). [id] is the
 * real, positive [com.kevin.legion.data.local.Event.id] now - one-today ticket 01 retired the old
 * synthetic-negative-id trick along with the live `CalendarContract` read it protected against, and
 * this row's id space is disjoint from a reminder's BY CONSTRUCTION
 * ([com.kevin.legion.data.local.Event.APPOINTMENT_ID_BASE]'s own doc comment), so there is nothing
 * left for a collision-avoiding offset to guard against. [recurring] is always false - see
 * [InboxRowView.recurring]'s own doc comment for why a stored row of this shape has no recurrence
 * concept left to carry post-repoint.
 */
private fun toInboxRowView(event: AppointmentEvent): InboxRowView =
    InboxRowView(
        id = event.eventId,
        text = event.title,
        done = event.done,
        tickable = event.kind != EventKind.EVENT,
        recurring = event.recurring,
        // [event] is a calendar-table row (`kind = event`/`task`) - its allDay convention is UTC
        // midnight of the date (`LiveToolbox.addAppointment`'s own comment), unlike a reminder
        // [ListItem]'s LOCAL-midnight allDay just above in [toInboxRowView] - found 2026-09-01,
        // Kevin: "the due dates seem to be advanced by 1 day some how". [documentDateCompact]
        // reads it back through UTC; [formatDateOnly] (device zone) would put it a day early west
        // of UTC, which is exactly the bug this fixes.
        dateLabel = if (event.allDay) documentDateCompact(event.startMs) else formatDateTime(event.startMs),
        overdue = false,
        placeLabel = null,
        exactDowngraded = false,
        source = AgendaSource.GOOGLE,
        calendarEventId = event.eventId,
        calendarOccurrenceStartMs = event.startMs,
        calendarOccurrenceEndMs = event.endMs,
        calendarAllDay = event.allDay,
        instantMs = event.startMs,
    )

// MISSED banner resolvers (`MissedRowView`/`buildMissedRows`, ticket 12) and the LOG-tile builders
// (`MissedTileData`/`buildMissedTile`/`ListsTileData`/`buildListsTile`, mission-control ticket 16)
// deleted one-today ticket 10 slice C, 2026-09-05 - `ui/NotesScreen.kt` was every one of their only
// callers (grep-confirmed before deletion) and is itself deleted with this slice. The FACTS they
// read are untouched: [com.kevin.legion.notes.NotesController.missedItems]/`dismissMissed`/
// `listNamesById` still exist and still feed `advisor/digest/HomeDigestBuilder.kt`/
// `LogDigestBuilder.kt`'s own spoken/written missed-reminder line - only the on-screen MISSED
// banner and its per-row DISMISS tap are gone. **This is a real, narrow loss, reported rather than
// silently absorbed**: `dismissMissed` (silence a missed flag WITHOUT completing or deleting the
// item) has no other caller left anywhere in the tree, so that specific action - as opposed to
// ticking or removing the reminder, both still reachable from `ui/CalendarScreen.kt`'s day view -
// has no hands path any more. Ticket 10 did not ask for a replacement UI for it, and none was built.

// LISTS_DO_NOT_SYNC_NOTICE ("lists do not sync", ticket 09's accepted cost) deleted alongside
// `ui/notes/NotesRows.kt`'s own `ListsDoNotSyncNote` composable, its only renderer - both retired
// one-today ticket 10 slice C, 2026-09-05 (`ui/notes/InboxScreen.kt` was the only screen that ever
// showed the notice). The underlying fact (`list_items` does not sync while `Todo`/`Groceries`
// checklists do) is unchanged; only this specific worded surface is gone.

// ------------------------------------------------------------------------- formatting (device zone)

private val DATE_ONLY: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d")
private val TIME_ONLY: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a")

fun formatDateOnly(epochMs: Long): String =
    Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalDate().format(DATE_ONLY)

fun formatDateTime(epochMs: Long): String {
    val z = Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault())
    return "${z.toLocalDate().format(DATE_ONLY)}, ${z.toLocalTime().format(TIME_ONLY)}"
}

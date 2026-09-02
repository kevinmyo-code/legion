package com.kevin.legion.ui.notes

import com.kevin.legion.backend.EventKind
import com.kevin.legion.data.local.ListItem
import com.kevin.legion.ui.AgendaSource
import com.kevin.legion.util.documentDateCompact
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Pure resolvers behind the notes screens (`ui/NotesScreen.kt`, `ui/notes/InboxScreen.kt`).
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
 * One row of the single-stream inbox ([com.kevin.legion.ui.notes.InboxScreen]) - Kevin, 2026-08-11:
 * "1 list, many items appended, all with their own due dates".
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
     * (tickable, nothing produces one yet) - merged into the SAME stream over a 90-day forward
     * window ([com.kevin.legion.ui.notes.INBOX_CALENDAR_WINDOW_DAYS]). `ui/notes/NotesRows.kt`'s
     * [InboxRow] shows the same `CAL` tag ticket 13 put on `TodayScreen`'s AGENDA pane - the
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
     * Null for an undated local row, which the month calendar's day filter never matches - an
     * undated item cannot belong to any one day. This is what lets [InboxScreen]'s new
     * `dayFilterStartMs` param filter the ALREADY-BUILT row list without a second stream-building
     * path: every row this file ever produces already carries the one timestamp a day filter needs. */
    val instantMs: Long? = null,
)

/**
 * The window Google events merge into the inbox stream over - **90 days forward from now, nothing
 * in the past.** Kevin's call, 2026-08-13 (`.scratch/google-account-integration/issues/
 * 13-calendar-read.md` follow-up): "far enough for anything worth planning around, short enough
 * that a yearly recurring series does not flood the stream." An appointment that already happened
 * is not a thing left to plan around - `TodayScreen`'s own AGENDA pane already covers today
 * specifically, and this window starts exactly where that one's coverage of the past would end.
 * `ui/notes/InboxScreen.kt` is the only reader; it lives here (not there) so [buildInboxRows]'
 * own tests can assert against the same named constant the screen queries with, rather than a
 * copy of the number.
 */
const val INBOX_CALENDAR_WINDOW_DAYS = 90L

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

// ------------------------------------------------------------------------------- MISSED (ticket 12)

data class MissedRowView(val id: Long, val text: String, val listName: String, val missedLabel: String)

/**
 * Rows for the MISSED banner - "reported, never silent" (ticket 12: a one-off reminder due while
 * the phone was off is genuinely gone, and that fact must be surfaced, never dropped). [listNamesById]
 * lets a caller pass one batched name lookup ([com.kevin.legion.notes.NotesController.listNamesById])
 * rather than a query per row.
 */
fun buildMissedRows(items: List<ListItem>, listNamesById: Map<Long, String>): List<MissedRowView> =
    items.map { item ->
        MissedRowView(
            id = item.id,
            text = item.text,
            listName = listNamesById[item.listId] ?: "a list",
            missedLabel = item.missedAt?.let { "was due ${formatDateTime(it)}" } ?: "was due earlier",
        )
    }

// ------------------------------------------------------------- mission-control ticket 16: LOG tiles

/**
 * MISSED tile (mission-control ticket 16's LOG build): a HALF-tile snapshot of [MissedRowView]'s
 * own count. Ticket 12's inventory called this whole panel HALF; `ui/NotesScreen.kt` keeps its
 * pre-existing FULL-detail rows (per-row DISMISS, tap-to-open) unchanged below this tile rather than
 * replacing them with it - this domain has no drilldown to send a tap to, and collapsing real,
 * working per-row controls into a single passive figure would be a functional regression the ticket
 * never asked for. Both now coexist: this is the at-a-glance figure, the rows below are the actual
 * controls. Deviation from the ticket's literal shape, reported rather than silently taken.
 */
data class MissedTileData(val hero: String, val caption: String)

fun buildMissedTile(missedRows: List<MissedRowView>): MissedTileData =
    if (missedRows.isEmpty()) {
        MissedTileData(hero = "0", caption = "no missed reminders")
    } else {
        MissedTileData(hero = missedRows.size.toString(), caption = "overdue reminders")
    }

/**
 * LISTS tile (mission-control ticket 16's LOG build): open (not-done) item count - ticket 12's
 * "count of open items across lists" read onto the post-2026-08-11 one-list model
 * (`ui/NotesScreen.kt`'s own file doc comment: "the multi-list model is gone"). [openCount] is
 * expected to be [ListItem]-only (`ui/notes/InboxScreen.kt`'s own `NotesController.allItems` read,
 * counted `!it.done`) - deliberately NOT the wider Google-merged stream that screen's ITEMS badge
 * counts, so the two figures are never the same claim under two different numbers. This tile's
 * caption says "open items", never bare "items", to keep that distinction in words.
 */
data class ListsTileData(val hero: String, val caption: String)

fun buildListsTile(openCount: Int): ListsTileData = ListsTileData(hero = openCount.toString(), caption = "open items")

// --------------------------------------------------------------------------- sync honesty (ticket 09)

/** "Lists do not sync" (ticket 09's accepted cost) - one line, said plainly, matching CLAUDE.md §4
 * rule seven's "say so in words on every surface" discipline applied to this domain's own cost. */
const val LISTS_DO_NOT_SYNC_NOTICE = "Lists stay on this phone only - they do not sync to your other device yet."

// ------------------------------------------------------------------------- formatting (device zone)

private val DATE_ONLY: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d")
private val TIME_ONLY: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a")

fun formatDateOnly(epochMs: Long): String =
    Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalDate().format(DATE_ONLY)

fun formatDateTime(epochMs: Long): String {
    val z = Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault())
    return "${z.toLocalDate().format(DATE_ONLY)}, ${z.toLocalTime().format(TIME_ONLY)}"
}

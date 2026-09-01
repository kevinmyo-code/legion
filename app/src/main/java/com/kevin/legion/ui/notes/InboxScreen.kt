package com.kevin.legion.ui.notes

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.Event
import com.kevin.legion.backend.EventKind
import com.kevin.legion.data.local.ListItem
import com.kevin.legion.notes.NotesController
import com.kevin.legion.ui.common.DeckPane
import com.kevin.legion.ui.theme.LegionTheme
import com.kevin.legion.ui.theme.LegionType
import com.kevin.legion.ui.theme.LocalLegionSemantics
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.launch

/**
 * **The** notes screen: one stream, every item, each with its own due date (Kevin, 2026-08-11 -
 * "merge the list to just one type of item. 1 list. many items appended. all with their own due
 * dates").
 *
 * Replaces the three-screen shape ticket 07 built (list-of-lists -> a single list, with a separate
 * CALENDAR tab over the same data). What that shape cost, and why this one exists:
 * - A date could only be put on an item AFTER appending it, through an edit dialog reached by
 *   tapping the item's text - an affordance nothing on the row advertised. The add row here takes
 *   the date inline, so appending an item with a due date is one action ([NotesController.addItemDue]).
 * - A due date shared one line with place triggers and recurrence notes, so a set date could read
 *   as no date. [InboxRowView.dateLabel] is its own field on its own line ([InboxRow]).
 * - Items were partitioned across named lists a driver had to remember. An F150 recall appointment
 *   filed itself onto "Car", where nothing surfaced it.
 *
 * **The multi-list model is gone**, not merely hidden ("dissolve the car list. merge everything into
 * one list model"). [com.kevin.legion.data.local.MIGRATION_12_13] folded every list into one row;
 * [NotesController.theList] is the only accessor left, and the voice tool that used to create,
 * archive, copy and delete lists is retired. This still reads via [NotesController.allItems] rather
 * than by list id, so an item stranded on a stray list by a half-applied migration is visible rather
 * than invisible.
 *
 * The checklist-vs-note split ([com.kevin.legion.data.local.ItemList.tickable]) is gone with it:
 * every item ticks, every item may carry a date, one type. The column survives, unread here.
 *
 * Split per the repo's vendored `compose-state-holder-ui-split` skill: [InboxScreen] owns the loads
 * and every write, [InboxContent] is plain state plus callbacks and is what the `@Preview`s below
 * exercise.
 */
data class InboxUiState(
    val loading: Boolean = true,
    val rows: List<InboxRowView> = emptyList(),
    /** Ticket 13 point 7 / follow-up (2026-08-13): whether `READ_CALENDAR` is currently granted -
     * [buildAgendaCalendarNotice] turns this, not [rows]' emptiness alone, into whatever this pane
     * says about its own Google coverage. Defaults true so a screen that has not finished its first
     * load never flashes a false "not linked" prompt before the real check runs - same default
     * [com.kevin.legion.ui.TodayUiState.calendarPermissionGranted] uses. */
    val calendarPermissionGranted: Boolean = true,
)

@Composable
fun InboxScreen(
    highlightItemId: Long? = null,
    highlightNonce: Int = 0,
    /** Ticket 14: non-null when the LOG tab's month calendar has a day selected - restricts the
     * rendered stream to rows whose [InboxRowView.instantMs] falls in that local day. This is the
     * calendar's own state, hoisted up to `ui/NotesScreen.kt` (which owns the grid); [InboxScreen]
     * only reads the value and filters the row list it was already building - no new query, no
     * second stream-building path. */
    dayFilterStartMs: Long? = null,
    /** Ticket 14: clears the calendar's selection - wired from `ui/NotesScreen.kt` down through
     * here so the SHOW ALL affordance [InboxContent] renders can reach back up to the state that
     * actually owns [dayFilterStartMs], since this screen does not own it itself. */
    onClearDayFilter: () -> Unit = {},
    /** Ticket 15: `ui/NotesScreen.kt`'s own furniture (title, calendar, MISSED, GoalsPanel,
     * LogModeToggle), passed straight through to [InboxContent]'s header slot - see that param's
     * own doc comment for why the LazyColumn it feeds is the whole screen's only scroll surface. */
    header: LazyListScope.() -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(InboxUiState()) }
    var rawItems by remember { mutableStateOf(emptyList<ListItem>()) }
    var rawAppointments by remember { mutableStateOf(emptyList<Event>()) }
    var reloadNonce by remember { mutableStateOf(0) }
    var editingItem by remember { mutableStateOf<ListItem?>(null) }
    // One-today ticket 01: the appointment dialog this screen owns - never more than one of
    // [GoogleRowDialog.Edit]/[GoogleRowDialog.SingleDeleteConfirm] is non-null at a time.
    var googleDialog by remember { mutableStateOf<GoogleRowDialog?>(null) }

    // Ticket 15 point 2: re-fetch keyed on [dayFilterStartMs] too, not [reloadNonce] alone - a day
    // selected AFTER the initial load must trigger the widened appointment fetch below, not reuse a
    // forward-only result computed before the filter existed.
    LaunchedEffect(reloadNonce, dayFilterStartMs) {
        // Touch the inbox list on first load so ADD never has to create it mid-tap, and so an
        // empty install shows a real (if empty) list rather than nothing at all.
        NotesController.theList(context)
        // [NotesController.allItems] reads every ACTIVE local row unconditionally - it is not
        // windowed at all, unlike `ui/NotesScreen.kt`'s own month-calendar count query - so a past
        // day's LOCAL items were never the false-empty bug ticket 15 describes and need no widening
        // here. Only the appointment fetch below is forward-window-only.
        val items = NotesController.allItems(context)
        rawItems = items

        // Appointment half (ticket 13 follow-up, Kevin 2026-08-13; **repointed off the live
        // `CalendarContract` read onto the local `events` table by one-today ticket 01, "cut Google
        // entirely"**): a 90-day FORWARD window, nothing in the past - see
        // [INBOX_CALENDAR_WINDOW_DAYS]'s own doc comment for why this differs from
        // `ui/TodayScreen.kt`'s AGENDA pane, which stays windowed to today alone. The local `events`
        // table is always readable (there is no permission to be refused any more), so
        // `calendarPermissionGranted` is always true post-cut - kept as a field so
        // [buildAgendaCalendarNotice]'s worded-empty-vs-unreadable split still compiles and still
        // reads correctly for the one case left that matters (a genuinely empty window).
        val now = System.currentTimeMillis()
        val db = CarDatabase.getDatabase(context)
        val windowEnd = now + INBOX_CALENDAR_WINDOW_DAYS * 24L * 60L * 60L * 1000L
        val forward = db.eventDao().activeByKindInWindow(EventKind.EVENT, now, windowEnd)
        // Ticket 15 point 2: the month calendar counts a whole-month window (past AND future), but
        // this fetch was forward-only from "now" - a PAST day the grid drew dots for was never in
        // [forward] at all, so the day filter below read a real "nothing here" that was actually
        // "nothing FETCHED". Cover the selected day too, IN ADDITION to the forward window, never
        // instead of it - deduped by id, since a selected day inside the forward window would
        // otherwise hand this query the same row twice.
        val appointments = if (dayFilterStartMs != null) {
            val dayWindowEndExclusive = dayFilterStartMs + DAY_FILTER_WINDOW_MS
            val dayEvents = db.eventDao().activeByKindInWindow(EventKind.EVENT, dayFilterStartMs, dayWindowEndExclusive - 1)
            (forward + dayEvents).distinctBy { it.id }
        } else {
            forward
        }
        rawAppointments = appointments

        state = InboxUiState(
            loading = false,
            rows = buildInboxRows(items, now, appointments.map { it.toAppointmentEvent() }),
            calendarPermissionGranted = true,
        )
    }

    // Notification-tap deep link (ticket 12: "tapping the notification opens the item"). The nonce,
    // not the id alone, is what makes a REPEAT tap on the same item re-open it - same reason
    // `ui/NotesScreen.kt`'s own openItemNonce exists.
    LaunchedEffect(highlightNonce, state.loading) {
        val id = highlightItemId ?: return@LaunchedEffect
        if (state.loading) return@LaunchedEffect
        editingItem = rawItems.firstOrNull { it.id == id }
    }

    InboxContent(
        state = state,
        dayFilterStartMs = dayFilterStartMs,
        onClearDayFilter = onClearDayFilter,
        header = header,
        onAdd = { text, dueAt ->
            scope.launch {
                val list = NotesController.theList(context)
                NotesController.addItemDue(context, list.id, text, dueAt)
                reloadNonce++
            }
        },
        onToggle = { id ->
            // One-today ticket 02: a reminder ([rawItems]) and an appointment ([rawAppointments])
            // now share one id space by construction (disjoint, never colliding -
            // `Event.APPOINTMENT_ID_BASE`'s own doc comment) but two separate tick funnels, since
            // an appointment must never touch `AlarmScheduler` the way a reminder tick does.
            val target = rawItems.firstOrNull { it.id == id }
            if (target != null) {
                scope.launch {
                    if (target.done) NotesController.untick(context, target) else NotesController.tick(context, target)
                    reloadNonce++
                }
                return@InboxContent
            }
            val appointment = rawAppointments.firstOrNull { it.id == id } ?: return@InboxContent
            scope.launch {
                if (appointment.done) NotesController.untickAppointment(context, appointment) else NotesController.tickAppointment(context, appointment)
                reloadNonce++
            }
        },
        onEdit = { id -> editingItem = rawItems.firstOrNull { it.id == id } },
        onRemove = { id ->
            val target = rawItems.firstOrNull { it.id == id } ?: return@InboxContent
            scope.launch { NotesController.removeItem(context, target); reloadNonce++ }
        },
        onEditGoogle = { row -> googleDialog = GoogleRowDialog.Edit(row) },
        onDeleteGoogle = { row -> googleDialog = GoogleRowDialog.SingleDeleteConfirm(row) },
    )

    val editing = editingItem
    if (editing != null) {
        // The full editor is reused as-is: repeats, place triggers and exact alarms all stay
        // reachable. The add row covers the common case (text + a date); this covers the rest.
        ItemEditDialog(
            item = editing,
            onDismiss = { editingItem = null },
            onSaved = { editingItem = null; reloadNonce++ },
        )
    }

    // One-today ticket 01: every write below goes to the LOCAL `events` table now
    // ([NotesController.updateAppointment]/[NotesController.removeAppointment]), never to Google -
    // the old scope-choice ("this one or all of them") dialog is gone with the recurring concept it
    // existed to disambiguate (see `ui/notes/NotesRows.kt`'s own doc comment).
    when (val dialog = googleDialog) {
        is GoogleRowDialog.SingleDeleteConfirm -> SingleDeleteConfirmDialog(
            onDismiss = { googleDialog = null },
            onConfirm = {
                val eventId = dialog.row.calendarEventId
                if (eventId != null) {
                    scope.launch { NotesController.removeAppointment(context, eventId); reloadNonce++ }
                }
                googleDialog = null
            },
        )
        is GoogleRowDialog.Edit -> CalendarEventEditDialog(
            row = dialog.row,
            onDismiss = { googleDialog = null },
            onSave = { title, startMs, endMs, allDay ->
                val eventId = dialog.row.calendarEventId
                if (eventId != null) {
                    scope.launch {
                        NotesController.updateAppointment(context, eventId, title, startMs, endMs, allDay)
                        reloadNonce++
                    }
                }
                googleDialog = null
            },
        )
        null -> {}
    }
}

/**
 * The appointment-row dialog this screen may be showing - one-today ticket 01 collapsed ticket 22's
 * three-armed [GoogleRowDialog] (`ScopeChoice`/`SingleDeleteConfirm`/`Edit`) down to two: cutting
 * the live Google read also cut the only fact ([recurring]) the old scope-choice prompt existed to
 * disambiguate (see `ui/notes/NotesRows.kt`'s own doc comment) - every stored appointment row is
 * already a single occurrence, so there is nothing left to ask "this one or all of them" about.
 * Still a sealed class so at most one of [SingleDeleteConfirm]/[Edit] can ever be shown at once, by
 * construction.
 */
private sealed class GoogleRowDialog {
    /** The plain delete confirm - ticket 22 point 3, worded from [SINGLE_DELETE_CONFIRM]. */
    data class SingleDeleteConfirm(val row: InboxRowView) : GoogleRowDialog()

    /** The title/date/time editor. */
    data class Edit(val row: InboxRowView) : GoogleRowDialog()
}

/** Ticket 22 point 3's delete confirm, unchanged in wording by one-today ticket 01 - a local delete
 * still is not undoable from here, even though it no longer propagates to Google. */
private const val SINGLE_DELETE_CONFIRM = "Delete this event? This can't be undone from here."

/** The plain delete confirm - ticket 22 point 3. */
@Composable
private fun SingleDeleteConfirmDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete event") },
        text = { Text(SINGLE_DELETE_CONFIRM) },
        confirmButton = { TextButton(onClick = onConfirm) { Text("DELETE") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("CANCEL") } },
    )
}

/**
 * The appointment editor - title and time only (ticket 22 point 1: "title and time"), a smaller
 * surface than [ItemEditDialog] because an appointment carries no place trigger, no LEGION repeat
 * rule and no exact-alarm flag of its own, and (one-today ticket 01) no recurrence concept left to
 * edit at all - see [GoogleRowDialog]'s own doc comment.
 *
 * All-day handling matches the retired `CalendarProvider.insertEvent`'s own convention, preserved
 * here so an appointment already stored with a UTC-midnight `startsAt` keeps reading and writing
 * that same way: an all-day [InboxRowView.calendarAllDay] event's [onSave] millis are UTC midnight
 * of the typed date, never device-zone midnight.
 */
@Composable
private fun CalendarEventEditDialog(
    row: InboxRowView,
    onDismiss: () -> Unit,
    onSave: (title: String, startMs: Long, endMs: Long, allDay: Boolean) -> Unit,
) {
    val sem = LocalLegionSemantics.current
    val allDay = row.calendarAllDay ?: false
    val originalStart = row.calendarOccurrenceStartMs
    val originalEnd = row.calendarOccurrenceEndMs
    // The occurrence's own duration survives the edit unchanged - this dialog only asks for a new
    // START, not a new END, matching ItemEditDialog's own "text fields, no separate duration picker"
    // simple-first posture.
    val durationMs = if (originalStart != null && originalEnd != null && originalEnd > originalStart) {
        originalEnd - originalStart
    } else if (allDay) {
        86_400_000L
    } else {
        3_600_000L
    }

    var title by remember(row.id) { mutableStateOf(row.text) }
    var dateText by remember(row.id) {
        mutableStateOf(originalStart?.let { epochToZonedLocalDate(it, allDay) }?.format(EDIT_DATE_FORMAT) ?: "")
    }
    var timeText by remember(row.id) {
        mutableStateOf(
            if (!allDay && originalStart != null) epochToZonedLocalTime(originalStart).format(EDIT_TIME_FORMAT) else "",
        )
    }

    val parsedDate = runCatching { LocalDate.parse(dateText, EDIT_DATE_FORMAT) }.getOrNull()
    val parsedTime = if (allDay) LocalTime.MIDNIGHT else runCatching { LocalTime.parse(timeText, EDIT_TIME_FORMAT) }.getOrNull()
    val canSave = title.isNotBlank() && parsedDate != null && parsedTime != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit calendar event") },
        text = {
            Column {
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Title") })
                Column(Modifier.padding(top = 12.dp)) {}
                OutlinedTextField(value = dateText, onValueChange = { dateText = it }, label = { Text("Date (YYYY-MM-DD)") })
                if (!allDay) {
                    OutlinedTextField(value = timeText, onValueChange = { timeText = it }, label = { Text("Time (24h HH:MM)") })
                }
                if (parsedDate == null || parsedTime == null) {
                    // ADVISORY (ticket 13 re-home): a form validation error, not a failed gate.
                    Text("Date/time couldn't be read.", style = LegionType.stamp, color = sem.estimated)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSave,
                onClick = {
                    val startMs = if (allDay) {
                        parsedDate!!.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
                    } else {
                        LocalDateTime.of(parsedDate!!, parsedTime!!).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                    }
                    onSave(title, startMs, startMs + durationMs, allDay)
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** [allDay] reads back through UTC (the platform's own all-day convention the retired
 * `CalendarProvider.insertEvent` established and every stored appointment row still follows); a
 * timed event reads back through the device zone, matching
 * `ui/notes/NotesResolvers.kt`'s formatting convention for a real future instant. */
private fun epochToZonedLocalDate(epochMs: Long, allDay: Boolean): LocalDate =
    Instant.ofEpochMilli(epochMs).atZone(if (allDay) ZoneOffset.UTC else ZoneId.systemDefault()).toLocalDate()

private fun epochToZonedLocalTime(epochMs: Long): LocalTime =
    Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).toLocalTime()

/**
 * Plain UI: [state] plus callbacks, no controller/DB reference - see the file doc comment.
 *
 * **Ticket 15 - this [LazyColumn] is the LOG tab's ONLY scroll surface.** Ticket 14's `weight(1f)`
 * fix kept this list from being measured to zero, but on a real phone it was still buried below
 * ~770dp of non-scrolling furniture (`ui/NotesScreen.kt`'s title/calendar/MISSED/GoalsPanel/toggle,
 * then this composable's own list-sync note/add-row/hairline) with nothing above it able to scroll
 * out of the way. Per the repo's vendored `compose-slot-api-pattern` skill, [header] is a
 * `LazyListScope` receiver slot - `ui/NotesScreen.kt` emits its own furniture straight into THIS
 * list as items, rather than as fixed children of a parent `Column` this list sits below. Emitted
 * FIRST, then this composable's own former fixed furniture (the list-sync note, the add-item row,
 * the date-unreadable warning) as items too - nothing above this `LazyColumn` remains anywhere in
 * the LOG/ITEMS mode tree, so scrolling the list scrolls everything, calendar included.
 */
@Composable
fun InboxContent(
    state: InboxUiState,
    /** Ticket 14 - see [InboxScreen]'s own doc comment on the param of the same name. */
    dayFilterStartMs: Long? = null,
    /** Ticket 14 - see [InboxScreen]'s own doc comment on the param of the same name. */
    onClearDayFilter: () -> Unit = {},
    onAdd: (text: String, dueAt: Long?) -> Unit,
    onToggle: (Long) -> Unit,
    onEdit: (Long) -> Unit,
    onRemove: (Long) -> Unit,
    onRequestCalendarPermission: () -> Unit = {},
    /** Ticket 22 - a Google row's own tap-to-edit, kept a SEPARATE callback from [onEdit] rather
     * than routed through it: [onEdit] takes a plain id and looks it up in the LOCAL [ListItem]
     * list, which can never resolve a Google row's synthetic negative id, and point 7 requires
     * local items keep that exact path unchanged. This callback receives the whole
     * [InboxRowView] instead, because editing a Google row needs [InboxRowView.calendarEventId]/
     * [InboxRowView.calendarOccurrenceStartMs]/etc, none of which a bare id carries. */
    onEditGoogle: (InboxRowView) -> Unit = {},
    /** Ticket 22 - the Google-row equivalent of [onRemove], same reasoning as [onEditGoogle]. */
    onDeleteGoogle: (InboxRowView) -> Unit = {},
    /** Ticket 15 - see the function doc comment. `ui/NotesScreen.kt`'s furniture, emitted before
     * this composable's own items. Defaults to nothing so every pre-ticket-15 `@Preview` below
     * keeps compiling and rendering unchanged. */
    header: LazyListScope.() -> Unit = {},
) {
    val sem = LocalLegionSemantics.current
    var addText by remember { mutableStateOf("") }
    var addDate by remember { mutableStateOf("") }

    // A BLANK date field is a valid undated item, not an error - only a date that was typed and
    // cannot be read is. Distinguishing the two is the whole point: silently refusing to ADD
    // because of an unreadable date, with no words, is how a typed date becomes no date.
    val parsedDate = if (addDate.isBlank()) null else runCatching { LocalDate.parse(addDate, EDIT_DATE_FORMAT) }.getOrNull()
    val dateUnreadable = addDate.isNotBlank() && parsedDate == null
    val canAdd = addText.isNotBlank() && !dateUnreadable

    // Ticket 14: filter the ALREADY-BUILT row list to the selected day, at this render layer - no
    // new query, no second stream-building path. [InboxRowView.instantMs] is null for an undated
    // local row, which can never fall in any one day, so it is correctly excluded rather than
    // matched by accident.
    val dayEndExclusiveMs = dayFilterStartMs?.let { it + DAY_FILTER_WINDOW_MS }
    val visibleRows = if (dayFilterStartMs == null) {
        state.rows
    } else {
        state.rows.filter { row -> row.instantMs != null && row.instantMs >= dayFilterStartMs && row.instantMs < dayEndExclusiveMs!! }
    }

    // Ticket 13 point 7 / follow-up (2026-08-13): permission state is worded independently of the
    // row list's own emptiness - see [AgendaCalendarNotice]'s doc comment for why the two can never
    // be collapsed into one check without risking the "nothing on" false read. Uses [visibleRows],
    // not [state.rows]: a day filter that hides everything else must still read as an honest
    // "nothing here", not a false claim about the whole stream.
    val calendarNotice = buildAgendaCalendarNotice(state.calendarPermissionGranted, visibleRows.size)

    // NO Surface/Column wrapper, NO Modifier.verticalScroll anywhere near this - a LazyColumn inside
    // a vertically scrollable parent gets infinite height constraints and throws (ticket 15's own
    // explicit warning). This LazyColumn IS the root.
    LazyColumn(Modifier.fillMaxSize()) {
        header()

        item(key = "inbox-lists-note") {
            Column {
                ListsDoNotSyncNote()
                DashedHairline()
            }
        }

        item(key = "inbox-add-row") {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = addText,
                    onValueChange = { addText = it },
                    label = { Text("Add item") },
                    singleLine = true,
                    modifier = Modifier.weight(1.6f),
                )
                OutlinedTextField(
                    value = addDate,
                    onValueChange = { addDate = it },
                    label = { Text("Due (YYYY-MM-DD)") },
                    singleLine = true,
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                )
                TextButton(
                    enabled = canAdd,
                    onClick = {
                        val dueAt = parsedDate
                            ?.atStartOfDay(ZoneId.systemDefault())
                            ?.toInstant()
                            ?.toEpochMilli()
                        onAdd(addText, dueAt)
                        addText = ""
                        addDate = ""
                    },
                ) {
                    Text("ADD", style = LegionType.stamp, color = if (canAdd) MaterialTheme.colorScheme.primary else sem.ghost)
                }
            }
        }

        if (dateUnreadable) {
            item(key = "inbox-date-warning") {
                // ADVISORY (ticket 13 re-home): a form validation error, not a failed gate.
                Text(
                    "Due date couldn't be read - use YYYY-MM-DD, or leave it blank for no date.",
                    style = LegionType.stamp,
                    color = sem.estimated,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                )
            }
        }

        item(key = "inbox-add-hairline") { DashedHairline() }

        if (state.loading) {
            item(key = "inbox-loading") {
                Text("Loading...", style = LegionType.stamp, color = sem.ghost, modifier = Modifier.padding(12.dp))
            }
            return@LazyColumn
        }

        // Stencil section header (ticket 19) - a [DeckPane] with an empty content lambda, same
        // reason ListsScreen.kt gives: this list is unbounded by design and must stay in a plain
        // lazy column rather than a pane's non-lazy ColumnScope.
        item(key = "inbox-header") {
            DeckPane(
                header = "ITEMS",
                // [visibleRows], not [state.rows]: the badge must count what is actually on
                // screen. QA 2026-08-14 saw it READ HIGHER while filtered to one day (29 against
                // three visible rows) - ticket 15's widened day fetch adds that day's events to
                // the loaded set, so counting the unfiltered list made selecting a day appear to
                // ADD items while showing fewer. A count that contradicts the rows under it is
                // the same "surface says what the data denies" shape as the false-empty bug.
                headerAccent = visibleRows.count { !it.done }.toString(),
            ) {}
        }
        // Ticket 14: "never a bare filtered list with no statement of what was hidden" - said in
        // words, with the one affordance back out of the filter, whenever one is active. Above the
        // calendar-not-linked notice and the row list both, since it qualifies the WHOLE section
        // below it, not just the rows.
        if (dayFilterStartMs != null) {
            item(key = "inbox-day-filter") {
                DayFilterRow(dayFilterStartMs, onClearDayFilter)
            }
        }
        if (calendarNotice.message != null) {
            item(key = "inbox-calendar-notice") {
                CalendarNotLinkedRow(calendarNotice.message, onGrant = onRequestCalendarPermission)
            }
        }
        if (calendarNotice.showNothingScheduled) {
            // The one case where "nothing here" is an honest claim: permission is granted (so there
            // is nothing Google-side this screen was refused from reading) AND the merged stream
            // itself is empty.
            item(key = "inbox-empty") {
                Text(
                    "Nothing here yet - type an item above, with a due date if it has one.",
                    style = MaterialTheme.typography.bodySmall,
                    color = sem.faint,
                    modifier = Modifier.padding(12.dp),
                )
            }
        } else {
            // Keyed by id AND occurrence start, never id alone (crash found on-device 2026-08-13). A
            // recurring Google event expands to one ROW PER OCCURRENCE, and every one of them
            // carries the same synthetic `-(eventId + 1)` id - so a daily event with three
            // occurrences in the window handed LazyColumn three identical keys and it threw
            // `IllegalArgumentException: Key "-180" was already used`, killing the process on entry
            // to Notes. Kevin's own recurring events are all YEARLY, so exactly one occurrence falls
            // in the 90-day window and the collision never fired for him - it was latent, not
            // absent, and any weekly or daily event would have triggered it. A LOCAL row has no
            // occurrence time and keeps its plain Room id.
            items(visibleRows, key = { "${it.id}@${it.calendarOccurrenceStartMs ?: 0L}" }) { row ->
                InboxRow(
                    row = row,
                    onToggle = { onToggle(row.id) },
                    onEdit = { onEdit(row.id) },
                    onRemove = { onRemove(row.id) },
                    onEditGoogle = { onEditGoogle(row) },
                    onDeleteGoogle = { onDeleteGoogle(row) },
                )
                DashedHairline()
            }
        }
    }
}

// ------------------------------------------------------------------------ previews

private const val PREVIEW_NOW = 1_754_600_000_000L

private val previewItems = listOf(
    ListItem(id = 1, listId = 1, text = "Oil change", sortOrder = 0, startsAt = 1_754_400_000_000L),
    ListItem(id = 2, listId = 1, text = "Renew insurance", sortOrder = 1, startsAt = 1_754_900_000_000L),
    ListItem(id = 3, listId = 1, text = "Pack the car", sortOrder = 2, startsAt = 1_755_500_000_000L, allDay = false),
    ListItem(id = 4, listId = 1, text = "Water the plants", sortOrder = 3, startsAt = 1_755_600_000_000L, repeatKind = "DAILY", repeatEvery = 1),
    ListItem(id = 5, listId = 1, text = "Buy milk", sortOrder = 4),
    ListItem(id = 6, listId = 1, text = "Call the bank", sortOrder = 5, done = true, doneAt = PREVIEW_NOW),
)

@Preview(name = "Inbox: loading", widthDp = 400, heightDp = 720)
@Composable
private fun PreviewInboxLoading() = LegionTheme {
    InboxContent(InboxUiState(loading = true), onAdd = { _, _ -> }, onToggle = {}, onEdit = {}, onRemove = {})
}

@Preview(name = "Inbox: dated, overdue, undated and done", widthDp = 400, heightDp = 720)
@Composable
private fun PreviewInboxMixed() = LegionTheme {
    InboxContent(
        InboxUiState(loading = false, rows = buildInboxRows(previewItems, PREVIEW_NOW)),
        onAdd = { _, _ -> }, onToggle = {}, onEdit = {}, onRemove = {},
    )
}

@Preview(name = "Inbox: empty", widthDp = 400, heightDp = 720)
@Composable
private fun PreviewInboxEmpty() = LegionTheme {
    InboxContent(InboxUiState(loading = false), onAdd = { _, _ -> }, onToggle = {}, onEdit = {}, onRemove = {})
}

package com.kevin.legion.ui.notes

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.kevin.legion.ui.AgendaSource
import com.kevin.legion.ui.common.DeckButton
import com.kevin.legion.ui.common.DeckTag
import com.kevin.legion.ui.common.DeckTagStyle
import com.kevin.legion.ui.theme.LegionType
import com.kevin.legion.ui.theme.LocalLegionSemantics

/**
 * Row furniture for the notes screens - the same "extracted shared row, thin screen wrapper" split
 * [com.kevin.legion.ui.common.CommonRows]/`ui/fleet/CarRows.kt` already use. Every row here takes
 * already-resolved view state ([InboxRowView]/[MissedRowView] from `ui/notes/NotesResolvers.kt`)
 * plus callbacks - no [com.kevin.legion.notes.NotesController] reference, so these are what the
 * screens' own `@Preview`s exercise.
 *
 * The list-of-lists, single-list and agenda rows that used to live here went with their screens
 * (2026-08-11: "dissolve the car list. merge everything into one list model").
 *
 * Reskinned under cyberdeck-ui ticket 19 (map "Ledger Drive-folder ingestion" -> cyberdeck-ui, per
 * ticket 10's answer #2: "usability beats fiction"). This is a RESKIN, not a redesign - every
 * callback signature here is unchanged from pre-ticket-19, so a caller's tap count cannot have
 * changed. [DashedHairline] replaces [com.kevin.legion.ui.common.Hairline] as this domain's row
 * separator (ticket 01: "rows separated by DASHED hairlines" - [com.kevin.legion.ui.common.DeckRow]
 * draws the same dash pattern for its own top rule; this is that same visual language for rows that
 * are NOT [com.kevin.legion.ui.common.DeckRow] itself, because a list/reminder row's `label` is USER
 * TEXT - [com.kevin.legion.ui.common.DeckRow] force-uppercases its `label` slot, which is correct for
 * a field name but would be wrong here (ticket 19's brief: "keep item content text as the user typed
 * it, never uppercase user content"), so these rows keep their own `Text` composition instead of
 * routing through [com.kevin.legion.ui.common.DeckRow].
 */

/**
 * The dashed row-separator used throughout this file in place of
 * [com.kevin.legion.ui.common.Hairline]'s solid line - see the file doc comment for why a plain
 * duplicate of [com.kevin.legion.ui.common.DeckRow]'s own dash lives here rather than being
 * extracted into `DeckPanels.kt` (ticket 19 stayed inside its named screen/row files only).
 */
@Composable
fun DashedHairline() {
    val sem = LocalLegionSemantics.current
    val dashStroke = with(androidx.compose.ui.platform.LocalDensity.current) { 1.dp.toPx() }
    Box(
        Modifier.fillMaxWidth().height(1.dp).drawBehind {
            drawLine(
                color = sem.ruleFaint,
                start = Offset(0f, size.height / 2f),
                end = Offset(size.width, size.height / 2f),
                strokeWidth = dashStroke,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 5f), 0f),
            )
        },
    )
}

/**
 * One row of the single-stream inbox ([com.kevin.legion.ui.notes.InboxScreen]) - Kevin, 2026-08-11.
 *
 * The due date gets its OWN line under the text, in the accent colour, never crowded in with the
 * other notes - that crowding is what let a set date read as no date at all. OVERDUE is spelled out
 * as a word ahead of the date rather than being carried by the quarantine colour alone, matching
 * this domain's existing "in words or shape, never colour alone" rule (ticket 08/03).
 *
 * A [row] whose [InboxRowView.source] is [AgendaSource.GOOGLE] (ticket 13 follow-up, made editable
 * by ticket 22, Kevin 2026-08-13; **local as of one-today ticket 01/02**) still carries the same
 * `CAL` tag `ui/TodayScreen.kt`'s AGENDA pane puts on it - the distinction from a plain reminder is
 * always in WORDS, never colour alone, even though both now live in the same local `events` table
 * and are both tickable/editable/deletable. **The old read-only-calendar/recurring-scope gate
 * ([com.kevin.legion.ui.notes.CalendarEditResolver]) is gone with the live Google read it existed
 * for** - one-today ticket 01: a local appointment row is always writable (Kevin owns it outright,
 * there is no read-only "someone else's calendar" concept any more) and always a single occurrence
 * (ticket 01's own class doc on [com.kevin.legion.ui.notes.AppointmentEvent]), so there is nothing
 * left to gate. **Every GOOGLE row still always offers edit/delete, unconditionally - `tick` is the
 * one exception (one-today ticket 08, "events are not todos", reversing this doc comment's own
 * former claim that ticking was unconditional too).** A `kind = event` row (renamed from
 * `appointment`, every row that used to read that value) renders with no checkbox at all -
 * [InboxRowView.tickable]'s own doc comment has the full account of why - and this composable's
 * `row.tickable` gate below is what actually enforces it; only a `kind = task` row still ticks.
 * [onEditGoogle]/[onDeleteGoogle]/[onToggle] all write the SAME local `events` table
 * [onEdit]/[onRemove]/[com.kevin.legion.notes.NotesController] do for a LOCAL row - they are kept as
 * separate callbacks only because an appointment's edit dialog needs
 * [InboxRowView.calendarOccurrenceStartMs]/etc, which a bare id does not carry (see those params'
 * own doc comments in `ui/notes/InboxScreen.kt`).
 */
@Composable
fun InboxRow(
    row: InboxRowView,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
    onEditGoogle: () -> Unit = {},
    onDeleteGoogle: () -> Unit = {},
) {
    val sem = LocalLegionSemantics.current
    val isGoogle = row.source == AgendaSource.GOOGLE
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (row.tickable) {
            Checkbox(checked = row.done, onCheckedChange = { onToggle() })
        } else {
            // Same width as the checkbox slot so a recurring row still lines up with its tickable
            // siblings - an empty Column of that width, not a smaller start padding.
            Column(Modifier.padding(start = 12.dp, end = 12.dp)) {}
        }
        Column(
            if (!isGoogle) Modifier.weight(1f).clickable(onClick = onEdit) else Modifier.weight(1f).clickable(onClick = onEditGoogle),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // `weight(1f, fill = false)` is load-bearing, not tidying (ticket 21). Unweighted,
                // this Text claimed the whole row and a long title measured the CAL tag out of
                // existence - so a Google event whose title wrapped to two lines rendered with no
                // tag at all, distinguishable from a LEGION reminder only by the ABSENCE of a
                // checkbox. That is exactly the inference ticket 08 refused to rely on ("in words,
                // never colour alone", and by extension never by a missing control). Weighted, the
                // tag is measured first and the title wraps into what is left. `fill = false` so a
                // short title still hugs its tag instead of stranding it at the far edge.
                Text(
                    row.text,
                    modifier = Modifier.weight(1f, fill = false),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (row.done) sem.faint else MaterialTheme.colorScheme.onSurface,
                    textDecoration = if (row.done) TextDecoration.LineThrough else null,
                )
                if (isGoogle) {
                    DeckTag("CAL", DeckTagStyle.OUTLINE_MUTED, modifier = Modifier.padding(start = 6.dp))
                }
            }
            row.dateLabel?.let { label ->
                // ADVISORY (ticket 13 re-home): overdue is act-on-this, not a failed gate.
                Text(
                    if (row.overdue) "OVERDUE - $label" else label,
                    style = LegionType.stamp,
                    color = if (row.overdue) sem.estimated else MaterialTheme.colorScheme.primary,
                )
            }
            val notes = buildList {
                if (row.recurring && !isGoogle) add("Recurring - not tickable")
                row.placeLabel?.let { add(it) }
                if (row.exactDowngraded) add("Exact time refused by the system - using an approximate alarm instead")
            }
            if (notes.isNotEmpty()) {
                Text(notes.joinToString("  ·  "), style = LegionType.stamp, color = sem.faint)
            }
        }
        if (!isGoogle) {
            TextButton(onClick = onRemove) { Text("REMOVE", style = LegionType.stamp, color = sem.faint) }
        } else {
            TextButton(onClick = onDeleteGoogle) { Text("DELETE", style = LegionType.stamp, color = sem.faint) }
        }
    }
}

/** One row of the MISSED banner ([com.kevin.legion.ui.NotesScreen]) - ticket 12: "reported, never silent". */
@Composable
fun MissedRow(row: MissedRowView, onOpen: () -> Unit, onDismiss: () -> Unit) {
    val sem = LocalLegionSemantics.current
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 12.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(row.text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            // ADVISORY (ticket 13 re-home): a missed reminder, act-on-this, not a failed gate.
            Text("${row.listName} - ${row.missedLabel}", style = LegionType.stamp, color = sem.estimated)
        }
        TextButton(onClick = onDismiss) { Text("DISMISS", style = LegionType.stamp, color = sem.faint) }
    }
}

/**
 * `POST_NOTIFICATIONS` refused - ticket 12: "must not fail silently". Opens the app's own
 * notification settings page, the one system surface that can actually fix it - there is no
 * in-app permission re-request once the OS has recorded a denial past the first ask.
 */
@Composable
fun NotificationsBlockedBanner() {
    val context = LocalContext.current
    val sem = LocalLegionSemantics.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // ADVISORY (ticket 13 re-home): a blocked capability, not a failed gate.
        Text(
            "Notifications are off, so reminders here can never alert you.",
            style = LegionType.stamp,
            color = sem.estimated,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = {
            val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            context.startActivity(intent)
        }) {
            Text("SETTINGS", style = LegionType.stamp, color = MaterialTheme.colorScheme.primary)
        }
    }
}

/**
 * Ticket 13 point 7: said in words, with the grant action right where it is needed, rather than a
 * silent empty day/list. Shared between `ui/TodayScreen.kt`'s AGENDA pane and this package's
 * [InboxScreen] (ticket 13 follow-up, Kevin 2026-08-13: InboxScreen carries Google events too, and
 * must show the same permission-refused wording, not a forked copy) - it lives here rather than in
 * `TodayScreen.kt` because this is already the shared "notes row furniture" file, the same reason
 * [DashedHairline] and [ListsDoNotSyncNote] live here rather than in either screen. Shown ABOVE
 * whatever local rows exist too, not only when the pane would otherwise be empty - see
 * [com.kevin.legion.ui.notes.AgendaCalendarNotice]'s doc comment for why a driver with local rows
 * still needs to be told a Google-owned appointment might be missing.
 *
 * **Migrated to [com.kevin.legion.ui.common.DeckButton]** (mission-control ticket 16's LOG build,
 * per that ticket's own binding: "migrate any control you touch to DeckControls" - HOME's build
 * deliberately left this bare [TextButton] alone because it is shared with LOG, and LOG is the
 * surface that finally touches it). Content and callback are unchanged; only the control itself
 * moved, same "control migrates, behaviour does not" shape [com.kevin.legion.ui.PurgeLedgerRow]'s
 * own migration doc comment describes.
 */
@Composable
fun CalendarNotLinkedRow(message: String, onGrant: () -> Unit) {
    val sem = LocalLegionSemantics.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(message, style = LegionType.stamp, color = sem.faint, modifier = Modifier.weight(1f))
        DeckButton(text = "GRANT", onClick = onGrant)
    }
}

/** The local day a [DayFilterRow] describes formats as e.g. "Fri 15 Aug" (ticket 14's own example
 * wording) - its own formatter rather than [formatDateOnly]'s "Aug 14" (that one is tuned for a
 * due-date column, this one is read aloud as a whole sentence and wants the weekday up front). */
private val DAY_FILTER_LABEL: java.time.format.DateTimeFormatter = java.time.format.DateTimeFormatter.ofPattern("EEE d MMM")

/** A local day-start in millis through the day after it, exclusive - the exact window
 * `ui/notes/InboxScreen.kt`'s day filter matches an [InboxRowView.instantMs] against. */
const val DAY_FILTER_WINDOW_MS: Long = 24L * 60 * 60 * 1000

/**
 * Ticket 14: "never a bare filtered list with no statement of what was hidden" - the words line
 * [InboxScreen] renders above the row list whenever the LOG tab's month calendar has a day
 * selected, with the one affordance back out of it. Same "message plus one action, same row" shape
 * as [CalendarNotLinkedRow], for the same reason: a filter (or a coverage gap) a driver cannot see
 * the far side of must never read as a silently short list.
 */
@Composable
fun DayFilterRow(dayFilterStartMs: Long, onClear: () -> Unit) {
    val sem = LocalLegionSemantics.current
    val label = java.time.Instant.ofEpochMilli(dayFilterStartMs)
        .atZone(java.time.ZoneId.systemDefault())
        .toLocalDate()
        .format(DAY_FILTER_LABEL)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text("showing $label - tap SHOW ALL for everything", style = LegionType.stamp, color = sem.faint, modifier = Modifier.weight(1f))
        TextButton(onClick = onClear) {
            Text("SHOW ALL", style = LegionType.stamp, color = MaterialTheme.colorScheme.primary)
        }
    }
}

/** Ticket 09's accepted cost, said in words, unobtrusively - a small footer line, not a banner. */
@Composable
fun ListsDoNotSyncNote() {
    val sem = LocalLegionSemantics.current
    Text(
        LISTS_DO_NOT_SYNC_NOTICE,
        style = LegionType.stamp,
        color = sem.ghost,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

package com.kevin.legion.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import com.kevin.legion.R
import com.kevin.legion.checklists.ChecklistController
import com.kevin.legion.checklists.measurePromptLabel
import com.kevin.legion.checklists.measureTargetResult
import com.kevin.legion.checklists.measureTargetResultLabel
import com.kevin.legion.checklists.measureValueDisplay
import com.kevin.legion.data.local.Checklist
import com.kevin.legion.data.local.ListItem
import com.kevin.legion.data.local.VoiceNote
import com.kevin.legion.notes.NotesController
import com.kevin.legion.ui.notes.InboxRowView
import com.kevin.legion.ui.theme.LocalLegionSemantics
import com.kevin.legion.ui.theme.soft.AreaAccent
import com.kevin.legion.ui.theme.soft.MsIcon
import com.kevin.legion.ui.theme.soft.SoftColors
import com.kevin.legion.ui.voicenotes.VoiceNoteDetailScreen
import com.kevin.legion.ui.voicenotes.formatVoiceNoteDuration
import com.kevin.legion.util.clockTime

/*
 * The day view's row composables, split out of `ui/CalendarScreen.kt` (shrink pass, 2026-11-22) so
 * the screen file holds the screen. Internal only because [CalendarScreen] calls them.
 */

/**
 * One day-view row: a checkbox (absent, not merely disabled, when [InboxRowView.tickable] is
 * false) plus the label and date. Two different reasons a row lands here with no checkbox, both
 * intentional: a recurring occurrence with no per-occurrence write path (`.scratch/one-today/
 * issues/02-ticking-an-appointment.md` point 2), and - as of one-today ticket 08, "events are not
 * todos" - EVERY [scheduleRows] row, because an event has no completion state to check off at all
 * (Kevin: "i dont mark an event done, it just passes"). Deliberately narrower than the now-deleted
 * `ui/notes/InboxScreen.kt`'s own `InboxRow` - see [CalendarScreen]'s own call-site comment for why.
 *
 * [onEdit]/[onRemove] added one-today ticket 10 slice C, 2026-09-05 (this screen's own file doc
 * comment has the full account) - both null for [checklistsToday]'s own rows; [onEdit] null for
 * [scheduleRows]'s events, which get [onRemove] alone (2026-11-22, through
 * [NotesController.removeAppointment]); both non-null for a real [ListItem] reminder in [dayRows].
 * [onEdit] wraps the label column in the same tap-to-edit
 * `Modifier.clickable` the retired `InboxRow` used; [onRemove] renders the same REMOVE stamp that
 * row's own separate button did, wired to [NotesController.removeItem] through [CalendarScreen]'s
 * own call site rather than a second write path here.
 */
@Composable
internal fun CalendarDayRow(
    row: InboxRowView,
    onToggle: () -> Unit,
    onEdit: (() -> Unit)? = null,
    onRemove: (() -> Unit)? = null,
) {
    val sem = LocalLegionSemantics.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (row.tickable) {
            Checkbox(checked = row.done, onCheckedChange = { onToggle() }, colors = softCheckboxColors())
        } else {
            // Same 12dp lead-in the checkbox-less rows always had, so a SCHEDULE row's text lines up
            // with a tickable row's.
            Column(Modifier.padding(start = 12.dp, end = 12.dp)) {}
        }
        Column(
            if (onEdit != null) Modifier.weight(1f).clickable(onClick = onEdit) else Modifier.weight(1f),
        ) {
            Text(
                row.text,
                style = MaterialTheme.typography.bodyLarge,
                color = if (row.done) sem.faint else SoftColors.text,
            )
            row.dateLabel?.let { label ->
                // The calendar accent's light tone, not the app primary: a time is this area's own.
                Text(label, style = MaterialTheme.typography.bodySmall, color = AreaAccent.CALENDAR.onContainer)
            }
            if (row.recurring) {
                Text("Recurring - not tickable", style = MaterialTheme.typography.bodySmall, color = sem.faint)
            }
        }
        if (onRemove != null) {
            // The word "REMOVE" became a trash icon; contentDescription keeps it named for TalkBack.
            IconButton(onClick = onRemove) {
                MsIcon(R.drawable.ms_delete, contentDescription = "Remove", tint = sem.faint, size = 20.dp)
            }
        }
    }
}

/** The rounded `SoftColors.card` every agenda section's rows sit on (Lists' card shape and HOME's
 * 16dp gutter), replacing the ruled square panes. */
@Suppress("FunctionNaming") // @Composable convention is PascalCase; detekt's rule does not know it.
@Composable
internal fun DayCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(SoftColors.card, MaterialTheme.shapes.large)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        content = content,
    )
}

/** An empty-section or failed-read sentence inside a [DayCard]. [failed] only changes the colour to
 * the caution tone; the words ("Couldn't load ...") are what carry the difference from an empty day,
 * never the colour alone (the calendar-briefing rule at the head of this file). */
@Suppress("FunctionNaming") // @Composable convention is PascalCase; detekt's rule does not know it.
@Composable
internal fun DayNote(text: String, failed: Boolean = false) {
    val sem = LocalLegionSemantics.current
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (failed) sem.estimated else sem.faint,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp),
    )
}

/** Soft checkbox: the calendar accent when checked. Lists' `tickedBox` grey is deliberately NOT
 * reused - on a calendar row the user can still untick, it would read as disabled. */
@Composable
private fun softCheckboxColors() = CheckboxDefaults.colors(
    checkedColor = AreaAccent.CALENDAR.onContainer,
    checkmarkColor = AreaAccent.CALENDAR.container,
    uncheckedColor = SoftColors.text3,
)

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
internal fun RecordedDayRow(note: VoiceNote, onClick: () -> Unit) {
    val sem = LocalLegionSemantics.current
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Empty leading column, same width as [CalendarDayRow]'s own non-tickable branch, so a
        // RECORDED row's label lines up with a SCHEDULE row's rather than starting flush left.
        Column(Modifier.padding(start = 12.dp, end = 12.dp)) {}
        Column {
            Text(
                note.title ?: "Untitled recording",
                style = MaterialTheme.typography.bodyLarge,
                color = SoftColors.text,
            )
            Text(
                "${clockTime(note.startedAt)} - ${formatVoiceNoteDuration(note.startedAt, note.endedAt)}",
                style = MaterialTheme.typography.bodySmall,
                color = sem.faint,
            )
        }
    }
}

/** One [Checklist] applicable to the viewed day, plus its items' tick state for that day - the
 * per-checklist unit [CalendarScreen]'s own `checklistsToday` list holds. See this screen's own
 * file doc comment for why a checklist gets its own section rather than joining [dayRows].
 *
 * [loadFailed], added one-today ticket 09's third build: true when [ChecklistController.itemsWithTickState]
 * returned [ChecklistController.ChecklistItemsResult.Failed] for this checklist on this day -
 * [items] is then always empty, but [loadFailed] is the fact a caller must render ("couldn't
 * load"), never inferred back out of an empty [items] list, which would read identically to a
 * genuinely empty checklist.
 */
internal data class ChecklistDayEntry(val checklist: Checklist, val items: List<ChecklistController.ItemState>, val loadFailed: Boolean = false)

/**
 * One checklist item row for the day view. A plain (non-measured) item keeps the original real tick
 * checkbox - never absent/disabled the way [CalendarDayRow] renders an event or a recurring
 * occurrence - plus the item's text, struck to [LocalLegionSemantics.faint] once ticked, same "done
 * fades" convention [CalendarDayRow] already uses for a reminder/task row.
 *
 * **A measured item (one-today ticket 09's third build, [ChecklistItem.measureUnit] non-null) asks
 * for a number instead of toggling** - Kevin's ruling, verbatim: "a measured item with no value
 * entered counts as skipped." Ticked and unticked render as genuinely different rows: unticked shows
 * [measurePromptLabel] beside a number field and a TICK stamp wired to [onSubmitMeasure] (which
 * calls [CalendarScreen]'s own `tickMeasuredItem`, itself calling [ChecklistController.tick] with
 * whatever was typed, blank included - the controller's own refusal is what surfaces [refusalMessage]
 * here, never a client-side validation that would swallow its wording); ticked shows
 * [measureValueDisplay] against the target, plus [measureTargetResult] in WORDS (never colour alone,
 * the same rule an UNRECONCILED ledger row follows) and an UNTICK stamp.
 *
 * Deliberately has no date label - a checklist item has no due instant of its own, only a per-day
 * tick state, which the section header's own progress count already carries in aggregate.
 *
 * **[queued], django-engine ticket 09: this row's tick for this day is in the outbox because the
 * engine could not be reached.** The tick itself is real and local - it is in Room, the checkbox is
 * ticked, tomorrow's read will still see it - so the row renders exactly as a synced one does, plus
 * one line of words saying the engine does not have it yet. That wording is the whole point:
 * CLAUDE.md section 7 forbids anything claiming an outcome it did not observe, and a queued write
 * that renders identically to a delivered one is that claim made by omission. **In words, never by
 * colour alone**, the same rule an UNRECONCILED ledger row follows.
 */
// detekt's FunctionNaming does not know about @Composable, whose own convention is PascalCase;
// this file's other composables sit in config/detekt/baseline.xml for exactly that reason. This
// one's baseline entry is keyed on its full signature and stopped matching the moment `queued` was
// added, and this ticket's brief forbids editing the baseline - so the same already-accepted fact
// is stated here instead of being re-recorded there.
@Suppress("FunctionNaming") // @Composable convention is PascalCase; see the comment above.
@Composable
internal fun ChecklistItemDayRow(
    itemState: ChecklistController.ItemState,
    onToggle: () -> Unit,
    measureInput: String,
    onMeasureInputChange: (String) -> Unit,
    onSubmitMeasure: () -> Unit,
    refusalMessage: String?,
    queued: Boolean = false,
) {
    val sem = LocalLegionSemantics.current
    val item = itemState.item

    if (item.measureUnit == null) {
        PlainChecklistItemRow(itemState, onToggle, queued)
        return
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
        val value = itemState.value
        if (itemState.ticked && value != null) {
            Row {
                Column(Modifier.weight(1f)) {
                    Text(item.text, style = MaterialTheme.typography.bodyLarge, color = sem.faint)
                    val resultLabel = measureTargetResult(item, value)?.let { " - ${measureTargetResultLabel(it)}" } ?: ""
                    Text(
                        measureValueDisplay(item, value) + resultLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = sem.faint,
                    )
                    if (queued) QueuedNote()
                }
                // "Untick", not "UNTICK": sentence case. ADR 0049's rule is that the word stays
                // "tick"/"untick" and never becomes "check"/"complete".
                TextButton(onClick = onToggle) {
                    Text("Untick", style = MaterialTheme.typography.labelLarge, color = sem.faint)
                }
            }
        } else {
            Text(item.text, style = MaterialTheme.typography.bodyLarge, color = SoftColors.text)
            measurePromptLabel(item)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = sem.faint) }
            Row(modifier = Modifier.padding(top = 4.dp)) {
                OutlinedTextField(
                    value = measureInput,
                    onValueChange = onMeasureInputChange,
                    label = { Text(item.measureUnit) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onSubmitMeasure, modifier = Modifier.padding(start = 8.dp)) {
                    Text("Tick", style = MaterialTheme.typography.labelLarge, color = AreaAccent.CALENDAR.onContainer)
                }
            }
            // Kevin's ruling on the edge: the controller's own refused message shows in words,
            // where the tap happened - never a toast that vanishes, never swallowed.
            if (refusalMessage != null) {
                Text(
                    refusalMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = sem.estimated,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            // An unticked measured item can still carry a queued UNTICK - the row is unticked
            // locally and the engine has not been told yet, which is exactly as much a
            // not-yet-delivered fact as a queued tick.
            if (queued) QueuedNote()
        }
    }
}

/** A plain (non-measured) checklist item: a real checkbox plus its text, faded once ticked. Split out
 * of [ChecklistItemDayRow] only to keep that function under detekt's length limit. */
@Suppress("FunctionNaming") // @Composable convention is PascalCase; detekt's rule does not know it.
@Composable
private fun PlainChecklistItemRow(itemState: ChecklistController.ItemState, onToggle: () -> Unit, queued: Boolean) {
    val sem = LocalLegionSemantics.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = itemState.ticked, onCheckedChange = { onToggle() }, colors = softCheckboxColors())
        Column {
            Text(
                itemState.item.text,
                style = MaterialTheme.typography.bodyLarge,
                color = if (itemState.ticked) sem.faint else SoftColors.text,
            )
            if (queued) QueuedNote()
        }
    }
}

/** The one-line "the engine does not have this yet" note - see [ChecklistItemDayRow]'s own
 * [queued] paragraph. Its own composable so the three places a checklist row can render it cannot
 * word it three different ways. */
@Suppress("FunctionNaming") // @Composable convention is PascalCase - same reason as ChecklistItemDayRow above.
@Composable
private fun QueuedNote() {
    Text(
        "Queued - not on the engine yet.",
        style = MaterialTheme.typography.bodySmall,
        color = LocalLegionSemantics.current.estimated,
        modifier = Modifier.padding(top = 2.dp),
    )
}

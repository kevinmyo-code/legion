package com.kevin.legion.ui.checklists

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kevin.legion.R
import com.kevin.legion.ui.theme.soft.MsIcon
import com.kevin.legion.ui.theme.soft.SoftColors

/**
 * A list, opened - Keep-style (ticket 01 §3, ticket 04's own layout). Tap a box to tick; a ticked
 * row sinks into the collapsible "ticked" group, struck through. Long-press for edit/move/delete.
 * Rename/Schedule/History/Archive/Delete live in the overflow menu.
 */
@Composable
fun ListDetailContent(state: ListDetailState, callbacks: ListDetailCallbacks) {
    val checklist = state.checklist
    Column(Modifier.fillMaxSize().background(SoftColors.ground)) {
        DetailTopBar(name = checklist?.name ?: "", callbacks = callbacks)

        if (state.loading || checklist == null) return@Column

        if (state.loadFailed) {
            Text(
                "Couldn't load this list's items.",
                style = MaterialTheme.typography.bodyMedium,
                color = SoftColors.onAlert,
                modifier = Modifier.padding(16.dp),
            )
            return@Column
        }

        ScheduleLine(checklist = checklist, state = state)
        WriteErrorBanner(message = state.writeError)

        if (state.unticked.isEmpty() && state.ticked.isEmpty()) {
            Text(
                "Nothing on this list yet. Add the first item below.",
                style = MaterialTheme.typography.bodyMedium,
                color = SoftColors.text2,
                modifier = Modifier.padding(16.dp),
            )
        }

        DetailItemsList(checklist = checklist, state = state, callbacks = callbacks, modifier = Modifier.weight(1f))
    }

    DetailDialogs(checklist = checklist, state = state, callbacks = callbacks)
}

@Composable
private fun ScheduleLine(checklist: com.kevin.legion.data.local.Checklist, state: ListDetailState) {
    if (checklist.scheduleKind == null) return
    Text(
        if (state.appliesToday) "${state.scheduleLabel}. Ticks count for today, $todayDayLabel." else "Not scheduled today.",
        style = MaterialTheme.typography.bodySmall,
        color = SoftColors.text2,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

/** A thrown controller write (audit finding 6) - a one-line banner on the screen itself, never a
 * toast, matching [UntickedRow]'s own existing `refusal` styling. `null` renders nothing. */
@Composable
private fun WriteErrorBanner(message: String?) {
    message ?: return
    Text(
        message,
        style = MaterialTheme.typography.bodySmall,
        color = SoftColors.onAlert,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

@Composable
private fun DetailItemsList(
    checklist: com.kevin.legion.data.local.Checklist,
    state: ListDetailState,
    callbacks: ListDetailCallbacks,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier.fillMaxWidth()) {
        items(state.unticked, key = { "u${it.item.id}" }) { row ->
            UntickedRow(row = row, refusal = state.refusals[row.item.id], inputValue = state.inputValues[row.item.id].orEmpty(), callbacks = callbacks)
        }
        item(key = "add-item-row") {
            AddItemRow(draft = state.draft, callbacks = callbacks)
        }
        if (state.ticked.isNotEmpty()) {
            item(key = "ticked-header") {
                TickedHeader(count = state.ticked.size, isRoutine = checklist.scheduleKind != null, expanded = state.showTicked, onToggle = callbacks.onToggleShowTicked)
            }
            if (state.showTicked) {
                items(state.ticked, key = { "t${it.item.id}" }) { row ->
                    TickedRow(row = row, callbacks = callbacks)
                }
            }
        }
        if (checklist.scheduleKind == null && state.unticked.isEmpty() && state.ticked.isNotEmpty()) {
            item(key = "all-done") {
                AllDoneCard(onDelete = { callbacks.onShowDeleteConfirm(true) })
            }
        }
    }
}

/** Every dialog/sheet [ListDetailContent] can show, gathered in one place so the main function
 * stays a state-to-layout mapping rather than also owning six independent `if`/`?.let` dialog
 * mounts (detekt's own [LongMethod]/[CyclomaticComplexMethod] ceilings, applied honestly rather
 * than suppressed). */
@Composable
private fun DetailDialogs(checklist: com.kevin.legion.data.local.Checklist?, state: ListDetailState, callbacks: ListDetailCallbacks) {
    state.editingItem?.let { item ->
        ItemEditorDialog(
            currentText = item.text,
            currentUnit = item.measureUnit,
            currentTarget = item.measureTarget,
            currentDirection = item.measureDirection,
            onDismiss = callbacks.onDismissEdit,
            onSave = callbacks.onSaveEdit,
        )
    }

    if (state.showRenameDialog && checklist != null) {
        RenameDialog(
            currentText = checklist.name,
            title = "Rename list",
            onDismiss = { callbacks.onShowRenameDialog(false) },
            onSave = callbacks.onRename,
        )
    }

    if (state.showSchedulePicker && checklist != null) {
        ScheduleDialog(
            initialKind = checklist.scheduleKind,
            initialDaysOfWeek = checklist.scheduleDaysOfWeek,
            onDismiss = { callbacks.onShowSchedulePicker(false) },
            onSave = callbacks.onSetSchedule,
        )
    }

    if (state.showDeleteConfirm && checklist != null) {
        DeleteChecklistConfirmDialog(
            name = checklist.name,
            onDismiss = { callbacks.onShowDeleteConfirm(false) },
            onConfirm = callbacks.onConfirmDelete,
        )
    }

    state.longPressItem?.let { item ->
        LongPressSheet(item = item, callbacks = callbacks)
    }
}

/** The schedule line's own "Ticks count for today, <day>." wording - `research/prototype-canvas/ListsC.dc.html`'s
 * own worked example ("Ticks count for today, Sun 27 Sep."). This screen is always TODAY
 * ([ListsViewModel]'s own class doc), so the day named here is always [java.time.LocalDate.now]. */
private val todayDayLabel: String
    get() = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM"))

@Composable
private fun DetailTopBar(name: String, callbacks: ListDetailCallbacks) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = callbacks.onBack, modifier = Modifier.size(48.dp)) {
            MsIcon(res = R.drawable.ms_arrow_back, contentDescription = "Back to lists", tint = SoftColors.text)
        }
        Text(
            name,
            style = MaterialTheme.typography.titleLarge,
            color = SoftColors.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 4.dp),
        )
        Box {
            IconButton(
                onClick = { menuOpen = true; callbacks.onToggleOverflowMenu(true) },
                modifier = Modifier.size(48.dp),
            ) {
                MsIcon(res = R.drawable.ms_more_vert, contentDescription = "List options", tint = SoftColors.text2)
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false; callbacks.onToggleOverflowMenu(false) }) {
                DropdownMenuItem(text = { Text("Rename") }, onClick = { menuOpen = false; callbacks.onShowRenameDialog(true) })
                DropdownMenuItem(text = { Text("Schedule") }, onClick = { menuOpen = false; callbacks.onShowSchedulePicker(true) })
                DropdownMenuItem(text = { Text("History") }, onClick = { menuOpen = false; callbacks.onOpenHistory() })
                DropdownMenuItem(text = { Text("Archive/Unarchive") }, onClick = { menuOpen = false; callbacks.onArchiveToggle() })
                DropdownMenuItem(
                    text = { Text("Delete list", color = SoftColors.onAlert) },
                    onClick = { menuOpen = false; callbacks.onShowDeleteConfirm(true) },
                )
            }
        }
    }
}


package com.kevin.legion.ui.checklists

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.unit.dp
import com.kevin.legion.checklists.ChecklistController
import com.kevin.legion.checklists.historyGroupedByDayDescending
import com.kevin.legion.data.local.Checklist
import com.kevin.legion.data.local.ChecklistItem
import com.kevin.legion.ui.common.DeckScreenHeader
import com.kevin.legion.ui.common.GapEmptyRow
import com.kevin.legion.ui.common.Hairline
import com.kevin.legion.ui.common.SectionHeader
import com.kevin.legion.ui.theme.LegionType
import com.kevin.legion.ui.theme.LocalLegionSemantics
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

/**
 * The hands path (and only path - no voice tools were built for this ticket) for recurring
 * checklists (`.scratch/one-today/issues/09-a-list-you-tick-every-day.md`'s UI slice). Reached from
 * `ui/MetersScreen.kt`'s LISTS pane, following its 2026-09-01 fix ("groceries trip tapping it
 * brings me to not a grocery list") - this row opens THIS screen, never a different list's editor.
 *
 * **Every write here calls [ChecklistController] - the single write/read funnel that object's own
 * class doc establishes.** This screen owns no DAO reference and re-derives nothing the controller
 * already decides (trap 1 - a day before a checklist existed - and trap 2 - a soft-deleted item's
 * text still resolving in history - are both closed in the controller, never re-checked here).
 *
 * **Three internal states, not three nav-graph destinations** - list -> single-checklist editor ->
 * that checklist's history, same convention [com.kevin.legion.ui.voicenotes.VoiceNotesScreen]'s own
 * list-to-detail drill-down and [com.kevin.legion.ui.companions.PlaybookScreen]'s list-to-editor
 * both already establish: nothing below the top level needs a deep link of its own.
 *
 * **This screen manages STRUCTURE (name, recurrence, items, order, archive) and shows HISTORY. It
 * does not own "today's" tick affordance** - that lives on `ui/CalendarScreen.kt`'s own day view,
 * one section per applicable checklist, which is where a driver actually ticks a line day to day.
 * Duplicating a tick checkbox here would be a second ticking surface with no day context of its
 * own to tick FOR, which is exactly the ambiguity `ChecklistController.tick`'s `day` parameter
 * exists to avoid.
 */
@Composable
fun ChecklistsScreen(onBack: () -> Unit) {
    var mode by remember { mutableStateOf<ChecklistsMode>(ChecklistsMode.ListMode) }
    when (val m = mode) {
        is ChecklistsMode.ListMode -> ChecklistListContent(
            onBack = onBack,
            onOpenChecklist = { id -> mode = ChecklistsMode.Detail(id) },
        )
        is ChecklistsMode.Detail -> ChecklistDetailContent(
            checklistId = m.id,
            onBack = { mode = ChecklistsMode.ListMode },
            onOpenHistory = { mode = ChecklistsMode.History(m.id) },
        )
        is ChecklistsMode.History -> ChecklistHistoryContent(
            checklistId = m.id,
            onBack = { mode = ChecklistsMode.Detail(m.id) },
        )
    }
}

private sealed class ChecklistsMode {
    object ListMode : ChecklistsMode()
    data class Detail(val id: Long) : ChecklistsMode()
    data class History(val id: Long) : ChecklistsMode()
}

// -------------------------------------------------------------------------------- list

@Composable
private fun ChecklistListContent(onBack: () -> Unit, onOpenChecklist: (Long) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var checklists by remember { mutableStateOf(emptyList<Checklist>()) }
    // Archived checklists stay reachable (unarchive, or open to review) rather than vanishing -
    // matches [com.kevin.legion.notes.NotesController]'s own "archived is hidden, not gone" posture
    // for the persistent list this screen sits beside.
    var showArchived by remember { mutableStateOf(false) }
    var reloadNonce by remember { mutableStateOf(0) }
    var showCreateDialog by remember { mutableStateOf(false) }

    LaunchedEffect(reloadNonce, showArchived) {
        checklists = ChecklistController.allChecklists(context, includeArchived = showArchived)
        loading = false
    }

    if (showCreateDialog) {
        CreateChecklistDialog(
            onDismiss = { showCreateDialog = false },
            onCreate = { name, recursDaily ->
                scope.launch {
                    ChecklistController.createChecklist(context, name, recursDaily)
                    showCreateDialog = false
                    reloadNonce++
                }
            },
        )
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            DeckScreenHeader(title = "Checklists", onBack = onBack)

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "+ NEW CHECKLIST",
                    style = LegionType.stamp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { showCreateDialog = true },
                )
                Text(
                    if (showArchived) "HIDE ARCHIVED" else "SHOW ARCHIVED",
                    style = LegionType.stamp,
                    color = LocalLegionSemantics.current.faint,
                    modifier = Modifier.clickable { showArchived = !showArchived },
                )
            }
            Hairline()

            when {
                loading -> {} // no flicker of an empty state before the one load this screen does
                checklists.isEmpty() -> GapEmptyRow(
                    label = "No checklists yet",
                    message = "A checklist is a named list you tick day to day - \"bio\", \"morning routine\", " +
                        "whatever you want to track. Make one with the button above.",
                )
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(checklists) { checklist ->
                        ChecklistRow(
                            checklist = checklist,
                            onClick = { onOpenChecklist(checklist.id) },
                            onToggleArchived = {
                                scope.launch {
                                    if (checklist.archived) {
                                        ChecklistController.unarchiveChecklist(context, checklist.id)
                                    } else {
                                        ChecklistController.archiveChecklist(context, checklist.id)
                                    }
                                    reloadNonce++
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChecklistRow(checklist: Checklist, onClick: () -> Unit, onToggleArchived: () -> Unit) {
    val sem = LocalLegionSemantics.current
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    checklist.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (checklist.archived) sem.faint else MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    if (checklist.recursDaily) "Daily" else "One-off",
                    style = LegionType.stamp,
                    color = sem.faint,
                )
            }
            Text(
                if (checklist.archived) "UNARCHIVE" else "ARCHIVE",
                style = LegionType.stamp,
                color = sem.faint,
                modifier = Modifier.clickable(onClick = onToggleArchived),
            )
        }
        Hairline()
    }
}

@Composable
private fun CreateChecklistDialog(onDismiss: () -> Unit, onCreate: (String, Boolean) -> Unit) {
    var name by remember { mutableStateOf("") }
    var recursDaily by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New checklist") },
        text = {
            Column {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") })
                Row(
                    Modifier.fillMaxWidth().padding(top = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Repeats every day")
                    Switch(checked = recursDaily, onCheckedChange = { recursDaily = it })
                }
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { onCreate(name.trim(), recursDaily) }) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// -------------------------------------------------------------------------------- detail

@Composable
private fun ChecklistDetailContent(checklistId: Long, onBack: () -> Unit, onOpenHistory: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var checklist by remember { mutableStateOf<Checklist?>(null) }
    var checklistItems by remember { mutableStateOf(emptyList<ChecklistItem>()) }
    var loading by remember { mutableStateOf(true) }
    var reloadNonce by remember { mutableStateOf(0) }
    var newItemText by remember { mutableStateOf("") }
    var editingItem by remember { mutableStateOf<ChecklistItem?>(null) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var deleted by remember { mutableStateOf(false) }

    LaunchedEffect(checklistId, reloadNonce) {
        checklist = ChecklistController.getChecklist(context, checklistId)
        checklistItems = ChecklistController.itemsFor(context, checklistId)
        loading = false
    }

    // The checklist was just deleted from underneath this composable (this screen's own delete
    // button below) - back out rather than keep rendering a detail view for a row that is gone.
    if (deleted) {
        onBack()
        return
    }

    editingItem?.let { item ->
        EditItemDialog(
            currentText = item.text,
            onDismiss = { editingItem = null },
            onSave = { text ->
                scope.launch {
                    ChecklistController.editItem(context, item.id, text)
                    editingItem = null
                    reloadNonce++
                }
            },
        )
    }

    if (showRenameDialog) {
        val current = checklist
        if (current != null) {
            EditItemDialog(
                currentText = current.name,
                title = "Rename checklist",
                onDismiss = { showRenameDialog = false },
                onSave = { text ->
                    scope.launch {
                        ChecklistController.renameChecklist(context, checklistId, text)
                        showRenameDialog = false
                        reloadNonce++
                    }
                },
            )
        }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            DeckScreenHeader(title = checklist?.name ?: "Checklist", onBack = onBack)

            if (loading || checklist == null) return@Column

            val current = checklist!!
            val sem = LocalLegionSemantics.current

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Repeats every day", style = MaterialTheme.typography.bodySmall)
                Switch(
                    checked = current.recursDaily,
                    onCheckedChange = { checked ->
                        scope.launch {
                            ChecklistController.setRecursDaily(context, checklistId, checked)
                            reloadNonce++
                        }
                    },
                )
            }

            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                Text(
                    "RENAME",
                    style = LegionType.stamp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { showRenameDialog = true },
                )
                Text(
                    "HISTORY",
                    style = LegionType.stamp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 20.dp).clickable(onClick = onOpenHistory),
                )
                Text(
                    "DELETE LIST",
                    style = LegionType.stamp,
                    color = sem.faint,
                    modifier = Modifier.padding(start = 20.dp).clickable {
                        scope.launch {
                            ChecklistController.deleteChecklist(context, checklistId)
                            deleted = true
                        }
                    },
                )
            }
            Hairline()

            SectionHeader("ITEMS", "${checklistItems.size}")

            if (checklistItems.isEmpty()) {
                GapEmptyRow(label = "No items yet", message = "Add a line below - \"3 sets goblet squats\", whatever belongs on this list.")
            } else {
                LazyColumn(Modifier.weight(1f)) {
                    items(checklistItems, key = { it.id }) { item ->
                        val index = checklistItems.indexOf(item)
                        ChecklistItemEditRow(
                            item = item,
                            canMoveUp = index > 0,
                            canMoveDown = index < checklistItems.lastIndex,
                            onEdit = { editingItem = item },
                            onDelete = {
                                scope.launch {
                                    ChecklistController.deleteItem(context, item.id)
                                    reloadNonce++
                                }
                            },
                            onMoveUp = {
                                val above = checklistItems[index - 1]
                                scope.launch {
                                    // A plain adjacent swap of [ChecklistItem.sortOrder] - no
                                    // renumbering pass over the whole list, so an item's own
                                    // history-view position (sorted by this same column) only
                                    // ever moves relative to its immediate neighbour, matching
                                    // what a single tap of an up/down arrow should do.
                                    ChecklistController.reorderItem(context, item.id, above.sortOrder)
                                    ChecklistController.reorderItem(context, above.id, item.sortOrder)
                                    reloadNonce++
                                }
                            },
                            onMoveDown = {
                                val below = checklistItems[index + 1]
                                scope.launch {
                                    ChecklistController.reorderItem(context, item.id, below.sortOrder)
                                    ChecklistController.reorderItem(context, below.id, item.sortOrder)
                                    reloadNonce++
                                }
                            },
                        )
                    }
                }
            }

            Hairline()
            Row(
                Modifier.fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = newItemText,
                    onValueChange = { newItemText = it },
                    label = { Text("New item") },
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    enabled = newItemText.isNotBlank(),
                    onClick = {
                        val text = newItemText.trim()
                        scope.launch {
                            // New items sort after every existing one - [checklistItems.size] is a valid
                            // next [ChecklistItem.sortOrder] regardless of any prior soft-deletes,
                            // since [ChecklistController.itemsFor] (live items only) is what
                            // [checklistItems] holds here.
                            ChecklistController.addItem(context, checklistId, text, sortOrder = checklistItems.size)
                            newItemText = ""
                            reloadNonce++
                        }
                    },
                ) { Text("ADD") }
            }
        }
    }
}

@Composable
private fun ChecklistItemEditRow(
    item: ChecklistItem,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    val sem = LocalLegionSemantics.current
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                item.text,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f).clickable(onClick = onEdit),
            )
            Text(
                "▲",
                style = LegionType.stamp,
                color = if (canMoveUp) MaterialTheme.colorScheme.primary else sem.ghost,
                modifier = Modifier.padding(horizontal = 6.dp).let { if (canMoveUp) it.clickable(onClick = onMoveUp) else it },
            )
            Text(
                "▼",
                style = LegionType.stamp,
                color = if (canMoveDown) MaterialTheme.colorScheme.primary else sem.ghost,
                modifier = Modifier.padding(horizontal = 6.dp).let { if (canMoveDown) it.clickable(onClick = onMoveDown) else it },
            )
            Text(
                "REMOVE",
                style = LegionType.stamp,
                color = sem.faint,
                modifier = Modifier.padding(start = 6.dp).clickable(onClick = onDelete),
            )
        }
        Hairline()
    }
}

@Composable
private fun EditItemDialog(currentText: String, title: String = "Edit item", onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(currentText) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text("Text") }) },
        confirmButton = {
            TextButton(enabled = text.isNotBlank(), onClick = { onSave(text.trim()) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// -------------------------------------------------------------------------------- history

/** How far back [ChecklistHistoryContent] looks by default - a plain window, not a setting; the
 * brief asks to "look back and see what i did", not to configure a retention policy. Kevin can
 * still see further back by widening this later if it turns out to matter; nothing about the
 * controller read below limits it to this figure. */
private const val HISTORY_WINDOW_DAYS = 30L

/** [ChecklistController.ChecklistHistoryLine.day] is a pure [LocalDate.toEpochDay] value with no
 * zone in it at all - formatting it by round-tripping through an epoch-millis instant (the way
 * [com.kevin.legion.util.shortDate] expects) would re-introduce exactly the west-of-UTC "previous
 * calendar day" bug `util/Dates.kt`'s own [com.kevin.legion.util.documentDate] doc comment
 * describes for a different table. This formats the [LocalDate] directly instead - there is no
 * instant to get wrong. */
private val HISTORY_DAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy")
private fun historyDayLabel(day: Int): String = LocalDate.ofEpochDay(day.toLong()).format(HISTORY_DAY_FORMAT)

/**
 * "Look back and see what i did" - the brief's own words. **Shown, never scored**: no streak, no
 * percentage, no "X of Y days" - only which lines were ticked on which day, read straight through
 * [ChecklistController.checklistHistory] and grouped by [historyGroupedByDayDescending]. Renders a
 * soft-deleted item's line exactly like a live one's (trap 2) - this screen never filters on
 * [ChecklistItem.deleted] and never sees that column at all, since [ChecklistController.checklistHistory]
 * itself already resolved each line's [ChecklistItem.text] before handing it back.
 */
@Composable
private fun ChecklistHistoryContent(checklistId: Long, onBack: () -> Unit) {
    val context = LocalContext.current
    var checklist by remember { mutableStateOf<Checklist?>(null) }
    var lines by remember { mutableStateOf(emptyList<ChecklistController.ChecklistHistoryLine>()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(checklistId) {
        checklist = ChecklistController.getChecklist(context, checklistId)
        val toDay = LocalDate.now().toEpochDay().toInt()
        val fromDay = toDay - HISTORY_WINDOW_DAYS.toInt()
        lines = ChecklistController.checklistHistory(context, checklistId, fromDay, toDay)
        loading = false
    }

    val sem = LocalLegionSemantics.current
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            DeckScreenHeader(title = "${checklist?.name ?: "Checklist"} - history", onBack = onBack)

            when {
                loading -> {}
                lines.isEmpty() -> GapEmptyRow(
                    label = "Nothing to look back on yet",
                    message = "No ticks in the last $HISTORY_WINDOW_DAYS days - either this list is new, or nothing on " +
                        "it has been ticked in that window.",
                )
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(historyGroupedByDayDescending(lines)) { (day, dayLines) ->
                        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                            Text(
                                historyDayLabel(day),
                                style = LegionType.stamp,
                                color = sem.faint,
                            )
                            dayLines.forEach { line ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    // Display-only - history never re-ticks or unticks, matching
                                    // this function's own "shown, never scored" doc comment; a
                                    // checked box here would invite editing the past from a grid
                                    // that has no per-line write funnel wired to it.
                                    Checkbox(checked = true, onCheckedChange = null, enabled = false)
                                    Text(line.item.text, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                        Hairline()
                    }
                }
            }
        }
    }
}

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
import com.kevin.legion.checklists.checklistScheduleLabel
import com.kevin.legion.checklists.formatMeasureNumber
import com.kevin.legion.checklists.historyGroupedByDayDescending
import com.kevin.legion.checklists.measurePromptLabel
import com.kevin.legion.checklists.measureTargetResult
import com.kevin.legion.checklists.measureTargetResultLabel
import com.kevin.legion.checklists.measureValueDisplay
import com.kevin.legion.data.local.Checklist
import com.kevin.legion.data.local.ChecklistItem
import com.kevin.legion.data.local.MeasureDirection
import com.kevin.legion.notes.formatWeekdays
import com.kevin.legion.notes.parseWeekdays
import com.kevin.legion.ui.common.DeckScreenHeader
import com.kevin.legion.ui.common.GapEmptyRow
import com.kevin.legion.ui.common.Hairline
import com.kevin.legion.ui.common.SectionHeader
import com.kevin.legion.ui.theme.LegionType
import com.kevin.legion.ui.theme.LocalLegionSemantics
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
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
            onCreate = { name, scheduleKind, scheduleDaysOfWeek ->
                scope.launch {
                    // [Checklist.recursDaily] is deliberately never written here (this ticket's own
                    // instruction) - it stays at its constructor default (`true`), which is the
                    // correct reading for every [scheduleKind] this picker can produce: even `null`
                    // ("no schedule") means "applies every day" per [Checklist.scheduleKind]'s own
                    // doc comment, so [ChecklistController.itemsWithTickState] should track this
                    // checklist's ticks per-day regardless of which of the three options was chosen.
                    ChecklistController.createChecklist(
                        context,
                        name,
                        scheduleKind = scheduleKind,
                        scheduleEvery = if (scheduleKind != null) 1 else null,
                        scheduleDaysOfWeek = scheduleDaysOfWeek,
                    )
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
                    // Schedule, in words - one-today ticket 09's third build replaces the old
                    // recursDaily-driven "Daily"/"One-off" label with [checklistScheduleLabel], which
                    // reads the same [Checklist.scheduleKind]/[scheduleEvery]/[scheduleDaysOfWeek]
                    // columns the new schedule picker below writes ("Mon Wed Fri", "Daily", "No
                    // schedule").
                    checklistScheduleLabel(checklist),
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

/**
 * [onCreate]'s second/third arguments are [Checklist.scheduleKind]/[Checklist.scheduleDaysOfWeek] -
 * one-today ticket 09's third build replaces the old `recursDaily` [Switch] with [SchedulePicker]'s
 * three states (none / daily / weekly on chosen days), per Kevin's own instruction for the item
 * editor's sparseness applied here too.
 */
@Composable
private fun CreateChecklistDialog(onDismiss: () -> Unit, onCreate: (String, String?, String?) -> Unit) {
    var name by remember { mutableStateOf("") }
    var scheduleKind by remember { mutableStateOf<String?>(null) }
    var scheduleDaysOfWeek by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New checklist") },
        text = {
            Column {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") })
                Column(Modifier.padding(top = 12.dp)) {
                    Text("Schedule", style = MaterialTheme.typography.bodySmall)
                    SchedulePicker(
                        scheduleKind = scheduleKind,
                        scheduleDaysOfWeek = scheduleDaysOfWeek,
                        onChange = { kind, days -> scheduleKind = kind; scheduleDaysOfWeek = days },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { onCreate(name.trim(), scheduleKind, scheduleDaysOfWeek) }) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * none / daily / weekly-on-chosen-days - the three schedule states ticket 09's third build asks
 * for, shared by [CreateChecklistDialog] and [ChecklistDetailContent]'s own schedule row. Sparse by
 * design (Kevin's own "not a form" instruction): three stamp-style taps for the kind, a row of
 * seven day-abbreviation taps underneath ONLY when `"WEEKLY"` is selected. **Always pins
 * [Checklist.scheduleEvery] to 1 via the caller** - this picker offers no "every N days/weeks"
 * cadence of its own, only the three states Kevin named; [checklistScheduleLabel] still formats a
 * wider N correctly for a schedule written some other way, this picker just never produces one.
 * **Never touches [Checklist.recursDaily]** - see this file's own `onCreate`/schedule-row call-site
 * comments for why leaving it at its default is the correct read, not an oversight.
 */
@Composable
private fun SchedulePicker(
    scheduleKind: String?,
    scheduleDaysOfWeek: String?,
    onChange: (kind: String?, daysOfWeek: String?) -> Unit,
) {
    val sem = LocalLegionSemantics.current
    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            listOf("NONE" to null, "DAILY" to "DAILY", "WEEKLY" to "WEEKLY").forEach { (label, kind) ->
                Text(
                    label,
                    style = LegionType.stamp,
                    color = if (scheduleKind == kind) MaterialTheme.colorScheme.primary else sem.faint,
                    modifier = Modifier.clickable {
                        // Switching away from WEEKLY drops any chosen days rather than keeping them
                        // around unseen - a NONE/DAILY checklist carrying a stale scheduleDaysOfWeek
                        // would be a landmine for whoever next flips it back to WEEKLY.
                        onChange(kind, if (kind == "WEEKLY") scheduleDaysOfWeek else null)
                    },
                )
            }
        }
        if (scheduleKind == "WEEKLY") {
            val days = parseWeekdays(scheduleDaysOfWeek.orEmpty()).orEmpty()
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DayOfWeek.values().sortedBy { it.value }.forEach { day ->
                    val selected = day in days
                    Text(
                        day.getDisplayName(TextStyle.SHORT, Locale.US).uppercase(),
                        style = LegionType.stamp,
                        color = if (selected) MaterialTheme.colorScheme.primary else sem.faint,
                        modifier = Modifier.clickable {
                            val next = if (selected) days - day else days + day
                            onChange("WEEKLY", formatWeekdays(next))
                        },
                    )
                }
            }
        }
    }
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
        // ItemEditorDialog, not the plain-text EditItemDialog - an item may optionally declare a
        // measure (unit/target/direction), one-today ticket 09's third build. [setMeasure] is
        // always called alongside [editItem], even when [unit] is null: that is how a measure gets
        // CLEARED, matching [ChecklistController.setMeasure]'s own "all three columns written
        // together" contract.
        ItemEditorDialog(
            currentText = item.text,
            currentUnit = item.measureUnit,
            currentTarget = item.measureTarget,
            currentDirection = item.measureDirection,
            onDismiss = { editingItem = null },
            onSave = { text, unit, target, direction ->
                scope.launch {
                    ChecklistController.editItem(context, item.id, text)
                    ChecklistController.setMeasure(context, item.id, unit, target, direction)
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

            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                // Schedule picker, not the old recursDaily [Switch] - one-today ticket 09's third
                // build. **Never calls [ChecklistController.setRecursDaily]** - the brief's own
                // instruction ("do not write recursDaily anymore"), and correct besides:
                // [Checklist.recursDaily] stays at its default `true`, which is the right per-day
                // tick-state reading for every one of these three schedule states (see
                // [SchedulePicker]'s own doc comment).
                Text("Schedule", style = MaterialTheme.typography.bodySmall)
                SchedulePicker(
                    scheduleKind = current.scheduleKind,
                    scheduleDaysOfWeek = current.scheduleDaysOfWeek,
                    onChange = { kind, days ->
                        scope.launch {
                            ChecklistController.setSchedule(
                                context,
                                checklistId,
                                scheduleKind = kind,
                                scheduleEvery = if (kind != null) 1 else null,
                                scheduleDaysOfWeek = days,
                            )
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
            // Quick add stays plain text - a fast way to type "3 sets goblet squats" without a
            // measure dialog in the way (Kevin's own "not a form" instruction, applied to the
            // COMMON case rather than the measured one). A unit/target/direction is added
            // afterward by tapping the item, which opens [ItemEditorDialog] below - the same
            // editor a rename goes through, so there is exactly one place that ever writes a
            // measure, not two.
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
            Column(Modifier.weight(1f).clickable(onClick = onEdit)) {
                Text(item.text, style = MaterialTheme.typography.bodyMedium)
                // The item's own declared measure, if any - "target: at least 10,000 steps" or
                // "in kg" - so a glance at the structure editor shows what tapping into it will
                // find, without opening [ItemEditorDialog] first.
                measurePromptLabel(item)?.let { Text(it, style = LegionType.stamp, color = sem.faint) }
            }
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

/**
 * An item's text plus its optional measure - one-today ticket 09's third build. All three measure
 * fields travel together, matching [ChecklistItem]'s own doc comment: a blank [unit] clears the
 * whole measure (unit/target/direction all null, back to a plain binary item, exactly
 * [ChecklistController.setMeasure]'s "all three columns written together" contract); a non-blank
 * unit with a blank target keeps [ChecklistItem.measureTarget]/[ChecklistItem.measureDirection]
 * null ("just record it", that class's own doc comment); a non-blank unit AND target requires
 * picking AT LEAST or AT MOST, defaulting to AT LEAST so a target is never silently direction-less.
 * **Sparse on purpose** (Kevin's own "not a form" instruction) - three stacked fields plus one
 * two-way picker that only appears once a target is actually typed, nothing shown before it is
 * needed.
 */
@Composable
private fun ItemEditorDialog(
    currentText: String,
    currentUnit: String?,
    currentTarget: Double?,
    currentDirection: String?,
    onDismiss: () -> Unit,
    onSave: (text: String, unit: String?, target: Double?, direction: String?) -> Unit,
) {
    var text by remember { mutableStateOf(currentText) }
    var unit by remember { mutableStateOf(currentUnit ?: "") }
    var targetText by remember { mutableStateOf(currentTarget?.let { formatMeasureNumber(it) } ?: "") }
    var direction by remember { mutableStateOf(currentDirection ?: MeasureDirection.AT_LEAST.name) }
    val sem = LocalLegionSemantics.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Item") },
        text = {
            Column {
                OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text("Text") })
                OutlinedTextField(
                    value = unit,
                    onValueChange = { unit = it },
                    label = { Text("Unit (optional) - steps, kg, min") },
                    modifier = Modifier.padding(top = 8.dp),
                )
                if (unit.isNotBlank()) {
                    OutlinedTextField(
                        value = targetText,
                        onValueChange = { targetText = it },
                        label = { Text("Target (optional)") },
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    if (targetText.isNotBlank()) {
                        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            listOf(MeasureDirection.AT_LEAST to "AT LEAST", MeasureDirection.AT_MOST to "AT MOST").forEach { (d, label) ->
                                Text(
                                    label,
                                    style = LegionType.stamp,
                                    color = if (direction == d.name) MaterialTheme.colorScheme.primary else sem.faint,
                                    modifier = Modifier.clickable { direction = d.name },
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = text.isNotBlank(),
                onClick = {
                    val trimmedUnit = unit.trim().ifBlank { null }
                    // A target with no parseable number is treated exactly like no target at all -
                    // never silently kept as text nobody can read back as a number.
                    val target = if (trimmedUnit != null) targetText.trim().toDoubleOrNull() else null
                    onSave(text.trim(), trimmedUnit, target, if (target != null) direction else null)
                },
            ) { Text("Save") }
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
 *
 * **A measured line shows its recorded value (ticket 09's third build)** - [measureValueDisplay]
 * against the target, plus [measureTargetResult] in words (never colour alone), exactly the same
 * two functions the calendar day view's own ticked row uses, so a value reads identically wherever
 * it appears. Still shown, never scored: this adds ONE more shown fact per line, not a computed
 * streak or an average of them.
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
                                    Column {
                                        Text(line.item.text, style = MaterialTheme.typography.bodySmall)
                                        val value = line.value
                                        if (value != null) {
                                            val resultLabel = measureTargetResult(line.item, value)
                                                ?.let { " - ${measureTargetResultLabel(it)}" } ?: ""
                                            Text(
                                                measureValueDisplay(line.item, value) + resultLabel,
                                                style = LegionType.stamp,
                                                color = sem.faint,
                                            )
                                        }
                                    }
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

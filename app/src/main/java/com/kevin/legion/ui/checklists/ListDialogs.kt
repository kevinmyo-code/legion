package com.kevin.legion.ui.checklists

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kevin.legion.notes.formatWeekdays
import com.kevin.legion.notes.parseWeekdays
import com.kevin.legion.ui.theme.soft.SoftColors
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

/**
 * "New list" (Lists page) and the open list's own Schedule dialog both build a name/schedule (or
 * just a schedule) via this pair - restyled onto [SoftColors]/soft typography, same shape the
 * pre-04 screen's own `CreateChecklistDialog`/`SchedulePicker` had.
 */
@Composable
fun CreateChecklistDialog(onDismiss: () -> Unit, onCreate: (String, String?, String?) -> Unit, error: String? = null) {
    var name by remember { mutableStateOf("") }
    var scheduleKind by remember { mutableStateOf<String?>(null) }
    var scheduleDaysOfWeek by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New list") },
        text = {
            Column {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") })
                Column(Modifier.padding(top = 12.dp)) {
                    Text("Schedule", style = MaterialTheme.typography.bodySmall, color = SoftColors.text2)
                    SchedulePicker(
                        scheduleKind = scheduleKind,
                        scheduleDaysOfWeek = scheduleDaysOfWeek,
                        onChange = { kind, days -> scheduleKind = kind; scheduleDaysOfWeek = days },
                    )
                }
                // A thrown createChecklist call (audit finding 6) - stated here rather than the
                // dialog closing on a failed create, so what did not happen is legible right where
                // the attempt was made.
                if (error != null) {
                    Text(error, style = MaterialTheme.typography.bodySmall, color = SoftColors.onAlert, modifier = Modifier.padding(top = 8.dp))
                }
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { onCreate(name.trim(), scheduleKind, scheduleDaysOfWeek) }) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** none / daily / weekly-on-chosen-days - unchanged three-state shape from the pre-04 screen, just
 * restyled onto soft tokens rather than [com.kevin.legion.ui.theme.LegionType]'s stamp face.
 * Always pins `scheduleEvery` to 1 via the caller; offers no "every N days/weeks" cadence. Never
 * touches the deprecated `Checklist.recursDaily`. */
@Composable
fun SchedulePicker(scheduleKind: String?, scheduleDaysOfWeek: String?, onChange: (kind: String?, daysOfWeek: String?) -> Unit) {
    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            listOf("None" to null, "Daily" to "DAILY", "Weekly" to "WEEKLY").forEach { (label, kind) ->
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (scheduleKind == kind) SoftColors.primary else SoftColors.text2,
                    modifier = Modifier.clickable {
                        onChange(kind, if (kind == "WEEKLY") scheduleDaysOfWeek else null)
                    },
                )
            }
        }
        if (scheduleKind == "WEEKLY") {
            val days = parseWeekdays(scheduleDaysOfWeek.orEmpty()).orEmpty()
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DayOfWeek.values().sortedBy { it.value }.forEach { dow ->
                    val selected = dow in days
                    Text(
                        dow.getDisplayName(TextStyle.SHORT, Locale.US),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (selected) SoftColors.primary else SoftColors.text2,
                        modifier = Modifier.clickable {
                            val next = if (selected) days - dow else days + dow
                            onChange("WEEKLY", formatWeekdays(next))
                        },
                    )
                }
            }
        }
    }
}

/** A plain single-line rename dialog - the checklist's own name, or (unused here but kept
 * general) an item's text. */
@Composable
fun RenameDialog(currentText: String, title: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(currentText) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text("Name") }) },
        confirmButton = {
            TextButton(enabled = text.isNotBlank(), onClick = { onSave(text.trim()) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** The open list's own "Schedule" dialog (overflow menu) - the same three-state [SchedulePicker],
 * pre-filled from the checklist's current schedule, saved as one fact rather than three
 * independently-settable columns (matches [com.kevin.legion.checklists.ChecklistController.setSchedule]'s
 * own "all three columns written together" contract). */
@Composable
fun ScheduleDialog(initialKind: String?, initialDaysOfWeek: String?, onDismiss: () -> Unit, onSave: (String?, String?) -> Unit) {
    var kind by remember { mutableStateOf(initialKind) }
    var days by remember { mutableStateOf(initialDaysOfWeek) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Schedule") },
        text = { SchedulePicker(scheduleKind = kind, scheduleDaysOfWeek = days, onChange = { k, d -> kind = k; days = d }) },
        confirmButton = { TextButton(onClick = { onSave(kind, days) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** "Delete <name>? The list goes. What you ticked off it is kept." - ADR 0049's own wording
 * ("ticked", never "bought"), and the ticket's own fix: delete always confirms now, the old DELETE
 * LIST stamp deleted on one tap. */
@Composable
fun DeleteChecklistConfirmDialog(name: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete $name?") },
        text = { Text("The list goes. What you ticked off it is kept.") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Delete list", color = SoftColors.onAlert) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

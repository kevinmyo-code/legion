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
import com.kevin.legion.checklists.formatMeasureNumber
import com.kevin.legion.data.local.MeasureDirection
import com.kevin.legion.ui.theme.soft.SoftColors

/**
 * An item's text plus its optional measure - restyled onto soft tokens; unchanged shape from the
 * pre-04 screen's own `ItemEditorDialog`. All three measure fields travel together: a blank [unit]
 * clears the whole measure, a unit with a blank target keeps it target-less ("just record it"),
 * a unit AND target requires AT LEAST/AT MOST (default AT LEAST).
 *
 * **The "last ticked" line** ([rememberLastTickedLabel], kept in its own file per that file's own
 * doc comment) reads straight through the same [com.kevin.legion.checklists.TickHistoryController.lastTicked]
 * the `get_last_ticked` voice tool calls (ADR 0035's hands path). Says "ticked", never "bought"
 * (ADR 0049/CLAUDE.md §4 rule 5).
 */
@Composable
fun ItemEditorDialog(
    currentText: String,
    currentUnit: String?,
    currentTarget: Double?,
    currentDirection: String?,
    onDismiss: () -> Unit,
    onSave: (text: String, unit: String?, target: Double?, direction: String?) -> Unit,
    /** The open list's name: Groceries shows "last bought" (ADR 0055), every other list keeps
     * "last ticked" (ADR 0049). Null (a caller that does not know) keeps "last ticked". */
    listName: String? = null,
) {
    var text by remember { mutableStateOf(currentText) }
    var unit by remember { mutableStateOf(currentUnit ?: "") }
    var targetText by remember { mutableStateOf(currentTarget?.let { formatMeasureNumber(it) } ?: "") }
    var direction by remember { mutableStateOf(currentDirection ?: MeasureDirection.AT_LEAST.name) }
    val lastTickedLabel = rememberLastItemLabel(listName, currentText)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Item") },
        text = {
            Column {
                OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text("Text") })
                lastTickedLabel?.let {
                    Text(it, style = MaterialTheme.typography.labelMedium, color = SoftColors.text3, modifier = Modifier.padding(top = 4.dp))
                }
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
                            listOf(MeasureDirection.AT_LEAST to "At least", MeasureDirection.AT_MOST to "At most").forEach { (d, label) ->
                                Text(
                                    label,
                                    style = MaterialTheme.typography.labelLarge,
                                    color = if (direction == d.name) SoftColors.primary else SoftColors.text2,
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
                    val target = if (trimmedUnit != null) targetText.trim().toDoubleOrNull() else null
                    onSave(text.trim(), trimmedUnit, target, if (target != null) direction else null)
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

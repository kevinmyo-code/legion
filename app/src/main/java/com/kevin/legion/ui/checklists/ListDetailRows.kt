@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.kevin.legion.ui.checklists

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kevin.legion.R
import com.kevin.legion.checklists.measurePromptLabel
import com.kevin.legion.checklists.measureTargetResult
import com.kevin.legion.checklists.measureTargetResultLabel
import com.kevin.legion.checklists.measureValueDisplay
import com.kevin.legion.data.local.ChecklistItem
import com.kevin.legion.ui.theme.soft.MsIcon
import com.kevin.legion.ui.theme.soft.SoftColors

/**
 * The per-item row composables [ListDetailContent] lists inside its `LazyColumn`, split into
 * their own file purely to stay under detekt's per-file [TooManyFunctions] ceiling - the same
 * reasoning `ui/checklists/LastTickedLabel.kt`'s own doc comment already gives for this file's
 * neighbour ("that screen file was already at the limit before this ticket touched it").
 */

/** A 22dp rounded-square checkbox in a 48dp touch target, unticked state - 2dp [SoftColors.text3]
 * border, matching `research/prototype-canvas/ListsC.dc.html`'s own unticked glyph exactly. */
@Composable
private fun UntickedCheckbox(onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(22.dp)
                .clip(RoundedCornerShape(7.dp))
                .border(2.dp, SoftColors.text3, RoundedCornerShape(7.dp)),
        )
    }
}

@Composable
fun UntickedRow(row: ListItemUi, refusal: String?, inputValue: String, callbacks: ListDetailCallbacks) {
    val item = row.item
    val isMeasured = item.measureUnit != null
    Column(
        Modifier.combinedClickable(
            onClick = { if (!isMeasured) callbacks.onTick(item.id) },
            onLongClick = { callbacks.onLongPress(item) },
        ),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            UntickedCheckbox(onClick = { if (isMeasured) callbacks.onTickMeasured(item.id) else callbacks.onTick(item.id) })
            Column(Modifier.weight(1f)) {
                Text(item.text, style = MaterialTheme.typography.bodyLarge, color = SoftColors.text)
                measurePromptLabel(item)?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = SoftColors.text3)
                }
            }
            if (isMeasured) {
                Row(Modifier.padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = inputValue,
                        onValueChange = { callbacks.onSetInputValue(item.id, it) },
                        modifier = Modifier.width(64.dp),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { callbacks.onTickMeasured(item.id) }),
                    )
                    Text(item.measureUnit.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = SoftColors.text2, modifier = Modifier.padding(start = 6.dp))
                }
            }
        }
        if (refusal != null) {
            Text(
                refusal,
                style = MaterialTheme.typography.labelMedium,
                color = SoftColors.caution,
                modifier = Modifier.padding(start = 48.dp, bottom = 8.dp),
            )
        }
    }
}

@Composable
fun TickedRow(row: ListItemUi, callbacks: ListDetailCallbacks) {
    val item = row.item
    val value = row.value
    val text = if (value != null) {
        val resultLabel = measureTargetResult(item, value)?.let { " - ${measureTargetResultLabel(it)}" } ?: ""
        measureValueDisplay(item, value) + resultLabel
    } else {
        item.text
    }
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { row.tickDay?.let { day -> callbacks.onUntick(item.id, day) } },
                onLongClick = { callbacks.onLongPress(item) },
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier.size(22.dp).clip(RoundedCornerShape(7.dp)).background(SoftColors.tickedBox),
                contentAlignment = Alignment.Center,
            ) {
                MsIcon(res = R.drawable.ms_check, contentDescription = null, tint = SoftColors.ground, size = 16.dp)
            }
        }
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = SoftColors.text3,
            textDecoration = TextDecoration.LineThrough,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (row.queued) {
            Row(Modifier.padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                MsIcon(res = R.drawable.ms_cloud_off, contentDescription = null, tint = SoftColors.caution, size = 16.dp)
                Text("Not synced yet", style = MaterialTheme.typography.labelSmall, color = SoftColors.caution, modifier = Modifier.padding(start = 4.dp))
            }
        }
    }
}

@Composable
fun TickedHeader(count: Int, isRoutine: Boolean, expanded: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(44.dp).clickable(onClick = onToggle).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MsIcon(
            res = if (expanded) R.drawable.ms_keyboard_arrow_up else R.drawable.ms_keyboard_arrow_down,
            contentDescription = null,
            tint = SoftColors.text2,
        )
        Text(
            if (isRoutine) "$count done today" else "$count ticked",
            style = MaterialTheme.typography.labelLarge,
            color = SoftColors.text2,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

@Composable
fun AddItemRow(draft: String, callbacks: ListDetailCallbacks) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        MsIcon(res = R.drawable.ms_add, contentDescription = null, tint = SoftColors.text2, modifier = Modifier.size(48.dp).padding(12.dp))
        OutlinedTextField(
            value = draft,
            onValueChange = callbacks.onDraftChange,
            placeholder = { Text("Add item") },
            modifier = Modifier.weight(1f),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { callbacks.onAddItem() }),
        )
    }
}

@Composable
fun AllDoneCard(onDelete: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(12.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(SoftColors.card)
            .padding(16.dp),
    ) {
        Text("Everything is ticked.", style = MaterialTheme.typography.titleMedium, color = SoftColors.text)
        Text(
            "Delete the list? What you ticked off it is kept.",
            style = MaterialTheme.typography.bodyMedium,
            color = SoftColors.text2,
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDelete) { Text("Delete list", color = SoftColors.onAlert) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LongPressSheet(item: ChecklistItem, callbacks: ListDetailCallbacks) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = callbacks.onDismissLongPress, sheetState = sheetState) {
        Column {
            SheetRow("Edit") { callbacks.onEdit(item) }
            SheetRow("Move up") { callbacks.onMoveUp(item.id) }
            SheetRow("Move down") { callbacks.onMoveDown(item.id) }
            SheetRow("Delete item", color = SoftColors.onAlert) { callbacks.onDeleteItem(item.id) }
        }
    }
}

@Composable
private fun SheetRow(label: String, color: Color = SoftColors.text, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(52.dp).clickable(onClick = onClick).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = color)
    }
}

package com.kevin.legion.ui.companions

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kevin.legion.R
import com.kevin.legion.memory.MemberMemory
import com.kevin.legion.memory.MemoryWording
import com.kevin.legion.memory.ReviewChange
import com.kevin.legion.ui.theme.soft.MsIcon
import com.kevin.legion.ui.theme.soft.SoftColors
import com.kevin.legion.ui.theme.soft.SoftTheme

/**
 * `settings/memory`, v2 (memory-and-vault ticket 11, ADR 0075): what the assistant remembers, read
 * from the ENGINE, the one store the phone, the web and the Claude worker share. The hands path
 * for every action of the `memory` voice tool (ADR 0035), through the same
 * [com.kevin.legion.memory.MemoryController] via [MemoryViewModel].
 *
 * - Each memory shows its text, its kind, where it came from ("from a conversation on <date>" for a
 *   distilled one), and whether it is private to you or shared with the household.
 * - **Delete is a hard delete and asks first** (Kevin, 2026-11-05: "forget that" deletes it for
 *   real). The old screen deleted on one tap with no confirm because a soft delete could be undone
 *   on the server; this one cannot, so it asks, and says "Deleted for good" only from the engine.
 * - The shared/private switch appears only on your own memories (the engine refuses the rest).
 * - Search narrows the list on the phone, so it works on the cached copy too.
 * - Offline, the phone's copy is shown with a line saying so; a failed read is never an empty list.
 * - **To check** and **Retired** (memory-review ticket 09): the daily review's suggestions wait at
 *   the top of the list, and what it retired can be read and restored (`MemoryReviewSection.kt`).
 *
 * The read-through gate does not apply to the add dialog: a typed entry has no conversation turn
 * behind it, so no mail could have been read into it (same reasoning as the v1 screen).
 */
@Composable
fun MemoryScreen(onBack: () -> Unit) {
    val viewModel: MemoryViewModel = viewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    SoftTheme {
        MemoryContent(
            state = state,
            callbacks = MemoryCallbacks(
                onBack = onBack,
                onQuery = viewModel::setQuery,
                onRetry = viewModel::refresh,
                onAdd = viewModel::openAdd,
                onEdit = viewModel::openEdit,
                onForget = viewModel::askForget,
                onShared = viewModel::setShared,
                onDialogText = viewModel::editDialogText,
                onAddShared = viewModel::setAddShared,
                onSaveAdd = viewModel::saveAdd,
                onSaveEdit = viewModel::saveEdit,
                onConfirmForget = viewModel::confirmForget,
                onDismiss = viewModel::dismissDialog,
                onFilter = viewModel::setFilter,
                onAcceptChange = viewModel::acceptChange,
                onDismissChange = viewModel::dismissChange,
                onAcceptCluster = viewModel::acceptCluster,
                onRestore = viewModel::restore,
            ),
        )
    }
}

data class MemoryCallbacks(
    val onBack: () -> Unit = {},
    val onQuery: (String) -> Unit = {},
    val onRetry: () -> Unit = {},
    val onAdd: () -> Unit = {},
    val onEdit: (MemberMemory) -> Unit = {},
    val onForget: (MemberMemory) -> Unit = {},
    val onShared: (MemberMemory, Boolean) -> Unit = { _, _ -> },
    val onDialogText: (String) -> Unit = {},
    val onAddShared: (Boolean) -> Unit = {},
    val onSaveAdd: () -> Unit = {},
    val onSaveEdit: () -> Unit = {},
    val onConfirmForget: () -> Unit = {},
    val onDismiss: () -> Unit = {},
    // memory-review ticket 09: To check and Retired.
    val onFilter: (MemoryFilter) -> Unit = {},
    val onAcceptChange: (ReviewChange) -> Unit = {},
    val onDismissChange: (ReviewChange) -> Unit = {},
    val onAcceptCluster: (String) -> Unit = {},
    val onRestore: (MemberMemory) -> Unit = {},
)

/** Plain UI: state in, callbacks out. Rendered by the screenshot and Compose tests directly. */
@Composable
fun MemoryContent(state: MemoryUiState, callbacks: MemoryCallbacks) {
    Column(Modifier.fillMaxSize().background(SoftColors.ground)) {
        TopBar(callbacks.onBack)
        OutlinedTextField(
            value = state.query,
            onValueChange = callbacks.onQuery,
            label = { Text("Search what it remembers") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
        state.message?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = SoftColors.text,
                modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp).testTag("memory-message"),
            )
        }
        TextButton(onClick = callbacks.onAdd, enabled = !state.busy, modifier = Modifier.padding(horizontal = 8.dp)) {
            Text("Add a memory by hand")
        }
        FilterRow(state.filter, callbacks.onFilter)
        if (state.filter == MemoryFilter.Retired) {
            RetiredBody(state.retired, state.busy, callbacks)
        } else {
            Body(state, callbacks)
        }
    }
    when (val dialog = state.dialog) {
        is MemoryDialog.Add -> AddDialog(dialog, state.busy, callbacks)
        is MemoryDialog.Edit -> EditDialog(dialog, state.busy, callbacks)
        is MemoryDialog.ConfirmForget -> ForgetDialog(dialog.memory, state.busy, callbacks)
        null -> Unit
    }
}

@Composable
private fun TopBar(onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
            MsIcon(res = R.drawable.ms_arrow_back, contentDescription = "Back", tint = SoftColors.text)
        }
        Text("What it remembers", style = MaterialTheme.typography.titleLarge, color = SoftColors.text)
    }
}

@Composable
private fun Body(state: MemoryUiState, callbacks: MemoryCallbacks) {
    when (val view = state.view) {
        MemoryView.Loading -> Note("Reading your memories from the engine...")
        is MemoryView.Unavailable -> Column(Modifier.padding(16.dp)) {
            Text(view.sentence, style = MaterialTheme.typography.bodyMedium, color = SoftColors.onAlert)
            Text(
                "This is not an empty memory; it just can't be read right now.",
                style = MaterialTheme.typography.labelMedium,
                color = SoftColors.text2,
                modifier = Modifier.padding(top = 6.dp),
            )
            TextButton(onClick = callbacks.onRetry) { Text("Try again") }
        }
        is MemoryView.Listed -> {
            view.cachedNote?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelMedium,
                    color = SoftColors.onAlert,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            val shown = state.shown
            when {
                view.memories.isEmpty() -> Note(
                    "Nothing remembered yet. What you tell it, and what it learns from conversations, shows here.",
                )
                shown.isEmpty() -> Note("No memory matches \"${state.query.trim()}\".")
                else -> LazyColumn(
                    Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (state.review.changes.isNotEmpty() || state.review.note != null) {
                        item(key = "review") { ReviewSection(state.review, state.busy, callbacks) }
                    }
                    item(key = "count") {
                        Text(
                            "${shown.size} of ${view.memories.size}",
                            style = MaterialTheme.typography.titleSmall,
                            color = SoftColors.text2,
                        )
                    }
                    items(shown, key = { it.id }) { MemoryRow(it, state.busy, callbacks) }
                }
            }
        }
    }
}

@Composable
private fun MemoryRow(memory: MemberMemory, busy: Boolean, callbacks: MemoryCallbacks) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(SoftColors.card).padding(14.dp)
            .testTag("memory-${memory.id}"),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(memory.text, style = MaterialTheme.typography.bodyLarge, color = SoftColors.text)
        Text(
            "${kindWord(memory.kind)}, ${MemoryWording.origin(memory)}",
            style = MaterialTheme.typography.labelMedium,
            color = SoftColors.text2,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                MemoryWording.sharing(memory).replaceFirstChar { it.uppercase() },
                style = MaterialTheme.typography.labelMedium,
                color = SoftColors.text3,
                modifier = Modifier.weight(1f),
            )
            if (memory.aboutYou) {
                Switch(
                    checked = memory.shared,
                    onCheckedChange = { callbacks.onShared(memory, it) },
                    enabled = !busy,
                    modifier = Modifier.testTag("share-${memory.id}"),
                )
            }
        }
        if (!memory.aboutYou) {
            Text(
                "About someone else in the household, so only they can make it private.",
                style = MaterialTheme.typography.labelMedium,
                color = SoftColors.text3,
            )
        }
        Row {
            TextButton(onClick = { callbacks.onEdit(memory) }, enabled = !busy) { Text("Edit") }
            TextButton(
                onClick = { callbacks.onForget(memory) },
                enabled = !busy,
                modifier = Modifier.testTag("forget-${memory.id}"),
            ) { Text("Forget") }
        }
    }
}

private fun kindWord(kind: String): String = when (kind) {
    "preference" -> "Preference"
    "event" -> "Event"
    else -> "Fact"
}

@Composable
private fun Note(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = SoftColors.text2,
        modifier = Modifier.padding(16.dp),
    )
}

@Composable
private fun AddDialog(dialog: MemoryDialog.Add, busy: Boolean, callbacks: MemoryCallbacks) {
    AlertDialog(
        onDismissRequest = callbacks.onDismiss,
        title = { Text("Remember something") },
        text = {
            Column {
                OutlinedTextField(
                    value = dialog.text,
                    onValueChange = callbacks.onDialogText,
                    label = { Text("What it should remember") },
                    enabled = !busy,
                )
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                    Text(
                        if (dialog.shared) "Shared with the household" else "Private to you",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(checked = dialog.shared, onCheckedChange = callbacks.onAddShared, enabled = !busy)
                }
                Text(
                    "ID numbers (passport, SSN, bank account) belong in the ID vault, not here.",
                    style = MaterialTheme.typography.labelMedium,
                    color = SoftColors.text3,
                )
                dialog.error?.let { Text(it, color = SoftColors.onAlert, modifier = Modifier.padding(top = 8.dp)) }
            }
        },
        confirmButton = {
            TextButton(onClick = callbacks.onSaveAdd, enabled = !busy && dialog.text.isNotBlank()) { Text("Remember") }
        },
        dismissButton = { TextButton(onClick = callbacks.onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun EditDialog(dialog: MemoryDialog.Edit, busy: Boolean, callbacks: MemoryCallbacks) {
    AlertDialog(
        onDismissRequest = callbacks.onDismiss,
        title = { Text("Edit this memory") },
        text = {
            Column {
                OutlinedTextField(value = dialog.text, onValueChange = callbacks.onDialogText, enabled = !busy)
                dialog.error?.let { Text(it, color = SoftColors.onAlert, modifier = Modifier.padding(top = 8.dp)) }
            }
        },
        confirmButton = {
            TextButton(onClick = callbacks.onSaveEdit, enabled = !busy && dialog.text.isNotBlank()) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = callbacks.onDismiss) { Text("Cancel") } },
    )
}

/** "Forget this?" - the memory named, and that it cannot come back. Nothing is deleted until Forget. */
@Composable
private fun ForgetDialog(memory: MemberMemory, busy: Boolean, callbacks: MemoryCallbacks) {
    AlertDialog(
        onDismissRequest = callbacks.onDismiss,
        title = { Text("Forget this for good?") },
        text = {
            Text(
                "\"${memory.text}\" will be deleted from the engine, with any older version of it. " +
                    "It cannot be brought back.",
            )
        },
        confirmButton = {
            TextButton(
                onClick = callbacks.onConfirmForget,
                enabled = !busy,
                modifier = Modifier.testTag("confirm-forget"),
            ) {
                Text("Forget")
            }
        },
        dismissButton = { TextButton(onClick = callbacks.onDismiss) { Text("Keep it") } },
    )
}

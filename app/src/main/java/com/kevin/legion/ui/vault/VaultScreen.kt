package com.kevin.legion.ui.vault

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.kevin.legion.R
import com.kevin.legion.ui.common.exposeTestTags
import com.kevin.legion.vault.VaultController
import com.kevin.legion.vault.VaultItem
import com.kevin.legion.ui.theme.soft.MsIcon
import com.kevin.legion.ui.theme.soft.SoftColors

/** What the vault screen can ask for. [onReveal] starts the fingerprint prompt; it never reveals by itself. */
data class VaultCallbacks(
    val onBack: () -> Unit = {},
    val onRetry: () -> Unit = {},
    val onReveal: (VaultItem) -> Unit = {},
    val onHide: () -> Unit = {},
    val onCopy: () -> Unit = {},
    val onAdd: () -> Unit = {},
    val onEdit: (VaultItem) -> Unit = {},
    val onForm: ((VaultForm) -> VaultForm) -> Unit = {},
    val onSaveForm: () -> Unit = {},
    val onCloseForm: () -> Unit = {},
    val onDelete: (VaultItem) -> Unit = {},
    val onConfirmDelete: () -> Unit = {},
    val onCancelDelete: () -> Unit = {},
)

/**
 * The ID vault (ADR 0076): labels listed, a value shown only after the fingerprint, with Copy and
 * Hide. Plain UI, state in and callbacks out, so the screenshot test renders it with no engine.
 * The activity around it ([VaultActivity]) sets FLAG_SECURE: no screenshots, no recents thumbnail.
 */
@Composable
fun VaultContent(state: VaultUiState, callbacks: VaultCallbacks) {
    // The vault is its own window, so it exposes its own tags (phone-verify ticket 04). `tools/phone`
    // reads vault-show/vault-copy to REFUSE them; the tags are how the rail finds the reveal path.
    Column(Modifier.fillMaxSize().background(SoftColors.ground).exposeTestTags()) {
        Row(
            Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = callbacks.onBack, modifier = Modifier.size(48.dp)) {
                MsIcon(res = R.drawable.ms_arrow_back, contentDescription = "Back", tint = SoftColors.text)
            }
            Text("ID vault", style = MaterialTheme.typography.titleLarge, color = SoftColors.text)
        }
        Text(
            "Opens only here, behind your fingerprint. Never spoken, never sent to the assistant.",
            style = MaterialTheme.typography.labelMedium,
            color = SoftColors.text2,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        state.message?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = SoftColors.text,
                modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp).testTag("vault-message"),
            )
        }
        TextButton(onClick = callbacks.onAdd, enabled = !state.busy, modifier = Modifier.padding(horizontal = 8.dp)) {
            Text("Add an item")
        }
        when (val view = state.view) {
            VaultView.Loading -> Note("Reading the vault's labels...")
            is VaultView.Unavailable -> Column(Modifier.padding(16.dp)) {
                Text(view.sentence, style = MaterialTheme.typography.bodyMedium, color = SoftColors.onAlert)
                TextButton(onClick = callbacks.onRetry) { Text("Try again") }
            }
            is VaultView.Listed -> if (view.items.isEmpty()) {
                Note("Nothing in the vault yet. Add a passport, SSN or account number here; type it, never say it.")
            } else {
                LazyColumn(
                    Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(view.items, key = { it.id }) { ItemRow(it, state, callbacks) }
                }
            }
        }
    }
    state.form?.let { FormDialog(it, state.busy, callbacks) }
    state.confirmDelete?.let { item ->
        AlertDialog(
            onDismissRequest = callbacks.onCancelDelete,
            title = { Text("Delete ${item.label}?") },
            text = { Text("It is deleted from the engine for good. It cannot be brought back.") },
            confirmButton = {
                TextButton(onClick = callbacks.onConfirmDelete, enabled = !state.busy) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = callbacks.onCancelDelete) { Text("Keep it") } },
        )
    }
}

@Composable
private fun ItemRow(item: VaultItem, state: VaultUiState, callbacks: VaultCallbacks) {
    val focused = state.focusId == item.id
    val shown = state.shown?.takeIf { it.itemId == item.id }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(SoftColors.card)
            .then(if (focused) Modifier.border(2.dp, SoftColors.text2, RoundedCornerShape(16.dp)) else Modifier)
            .padding(14.dp).testTag("vault-${item.id}"),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(item.label, style = MaterialTheme.typography.titleSmall, color = SoftColors.text)
        Text(
            VaultController.kindWord(item.kind),
            style = MaterialTheme.typography.labelMedium,
            color = SoftColors.text2,
        )
        if (shown != null) {
            Text(
                shown.secret.reveal(),
                style = MaterialTheme.typography.headlineSmall,
                color = SoftColors.text,
                modifier = Modifier.padding(vertical = 6.dp),
            )
            Text(
                "Hides itself in a minute. Copy clears the clipboard after 60 seconds.",
                style = MaterialTheme.typography.labelMedium,
                color = SoftColors.text3,
            )
            Row {
                TextButton(onClick = callbacks.onCopy, modifier = Modifier.testTag("vault-copy")) { Text("Copy") }
                TextButton(onClick = callbacks.onHide) { Text("Hide") }
            }
        } else {
            Row {
                TextButton(
                    onClick = { callbacks.onReveal(item) },
                    enabled = !state.busy,
                    modifier = Modifier.testTag("vault-show"),
                ) { Text("Show") }
                TextButton(onClick = { callbacks.onEdit(item) }, enabled = !state.busy) { Text("Edit") }
                TextButton(onClick = { callbacks.onDelete(item) }, enabled = !state.busy) { Text("Delete") }
            }
        }
    }
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

/**
 * Add or edit. The value is typed in a password field (no suggestions, no learning keyboard) and
 * never dictated: a spoken value would land in the conversation record. Editing leaves the value
 * blank; blank keeps the stored one, so an edit never needs it revealed first.
 */
@Composable
private fun FormDialog(form: VaultForm, busy: Boolean, callbacks: VaultCallbacks) {
    AlertDialog(
        onDismissRequest = callbacks.onCloseForm,
        title = { Text(if (form.editing == null) "Add to the vault" else "Edit ${form.editing.label}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = form.label,
                    onValueChange = { v -> callbacks.onForm { it.copy(label = v) } },
                    label = { Text("Label, like Passport") },
                    singleLine = true,
                    enabled = !busy,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    VaultController.KINDS.take(KIND_CHIPS_PER_ROW).forEach { KindChip(it, form, callbacks) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    VaultController.KINDS.drop(KIND_CHIPS_PER_ROW).forEach { KindChip(it, form, callbacks) }
                }
                OutlinedTextField(
                    value = form.value,
                    onValueChange = { v -> callbacks.onForm { it.copy(value = v) } },
                    label = { Text(if (form.editing == null) "The number" else "New number (blank keeps it)") },
                    singleLine = true,
                    enabled = !busy,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                )
                form.error?.let { Text(it, color = SoftColors.onAlert) }
            }
        },
        confirmButton = { TextButton(onClick = callbacks.onSaveForm, enabled = !busy) { Text("Save") } },
        dismissButton = { TextButton(onClick = callbacks.onCloseForm) { Text("Cancel") } },
    )
}

@Composable
private fun KindChip(kind: String, form: VaultForm, callbacks: VaultCallbacks) {
    FilterChip(
        selected = form.kind == kind,
        onClick = { callbacks.onForm { it.copy(kind = kind) } },
        label = { Text(VaultController.kindWord(kind)) },
    )
}

private const val KIND_CHIPS_PER_ROW = 3

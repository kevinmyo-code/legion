package com.kevin.legion.ui.ledger

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kevin.legion.R
import com.kevin.legion.data.local.LedgerTransaction
import com.kevin.legion.data.local.MerchantAlias
import com.kevin.legion.ledger.LedgerController
import com.kevin.legion.ledger.extractMerchantKey
import com.kevin.legion.ledger.matchingMerchantAlias
import com.kevin.legion.ui.theme.LegionType
import com.kevin.legion.ui.theme.LocalLegionSemantics
import kotlinx.coroutines.launch

/**
 * The household's merchant aliases (Kevin, 2026-10-07), oldest first, for every composable that
 * renders a bank description: `displayDescription(raw, LocalMerchantAliases.current)`. A local
 * rather than a parameter on thirty row composables; the empty default means a preview or a screen
 * outside [ProvideMerchantAliases] simply shows the bank's own (noise-stripped) text.
 */
val LocalMerchantAliases = compositionLocalOf<List<MerchantAlias>> { emptyList() }

/** Collects the live aliases from Room once, near the app root, and provides them below. */
@Suppress("FunctionNaming") // @Composable convention is PascalCase; detekt's rule does not know it.
@Composable
fun ProvideMerchantAliases(content: @Composable () -> Unit) {
    val context = LocalContext.current.applicationContext
    val flow = remember(context) { LedgerController.merchantAliasesFlow(context) }
    val aliases by flow.collectAsStateWithLifecycle(initialValue = emptyList())
    CompositionLocalProvider(LocalMerchantAliases provides aliases, content = content)
}

/**
 * The single-transaction rename surface (ADR 0035's hands path for the rename). Shows "Bank text:"
 * in words whenever an alias is hiding it, then the Rename / Edit button and its dialog. Saving
 * and removing go through [LedgerController], the same controller a future voice tool would call.
 */
@Suppress("FunctionNaming") // @Composable convention is PascalCase; detekt's rule does not know it.
@Composable
fun MerchantRenameRow(txn: LedgerTransaction) {
    val aliases = LocalMerchantAliases.current
    val applied = matchingMerchantAlias(txn.description, aliases)
    var dialogOpen by remember(txn.id) { mutableStateOf(false) }
    val sem = LocalLegionSemantics.current
    Column(Modifier.fillMaxWidth()) {
        if (applied != null) {
            Text(
                stringResource(R.string.merchant_alias_bank_text, txn.description),
                style = LegionType.stamp,
                color = sem.faint,
            )
        }
        TextButton(onClick = { dialogOpen = true }) {
            Text(
                stringResource(if (applied != null) R.string.merchant_alias_edit else R.string.merchant_alias_rename),
                style = LegionType.stamp,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
    if (dialogOpen) {
        RenameMerchantDialog(
            bankText = txn.description,
            existing = applied,
            onDismiss = { dialogOpen = false },
        )
    }
}

/**
 * "Bank text contains" is prefilled with [extractMerchantKey] (editable, so one rename reaches the
 * merchant's sibling rows, the same reasoning the recategorise panel gives for its key); "Show as"
 * starts empty, or with the current name when [existing] already applies. Save stays disabled while
 * either field is blank. [existing] also offers "Remove rename".
 */
@Suppress("FunctionNaming") // @Composable convention is PascalCase; detekt's rule does not know it.
@Composable
private fun RenameMerchantDialog(bankText: String, existing: MerchantAlias?, onDismiss: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var substring by remember { mutableStateOf(existing?.substring ?: extractMerchantKey(bankText)) }
    var displayName by remember { mutableStateOf(existing?.displayName.orEmpty()) }
    var busy by remember { mutableStateOf(false) }
    val canSave = substring.isNotBlank() && displayName.isNotBlank() && !busy

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.merchant_alias_dialog_title)) },
        text = {
            RenameFields(
                substring = substring,
                onSubstring = { substring = it },
                displayName = displayName,
                onDisplayName = { displayName = it },
                busy = busy,
                canRemove = existing != null,
                onRemove = {
                    busy = true
                    scope.launch {
                        existing?.let { LedgerController.removeMerchantAlias(context, it.id) }
                        onDismiss()
                    }
                },
            )
        },
        confirmButton = {
            TextButton(
                enabled = canSave,
                onClick = {
                    busy = true
                    scope.launch {
                        // An edit is a replacement: the old rule is tombstoned and a new one inserted,
                        // so the oldest-wins order cannot leave a stale name shadowing the edit.
                        if (existing != null) LedgerController.removeMerchantAlias(context, existing.id)
                        LedgerController.addMerchantAlias(context, substring, displayName)
                        onDismiss()
                    }
                },
            ) { Text(stringResource(R.string.merchant_alias_save)) }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) { Text(stringResource(R.string.merchant_alias_cancel)) }
        },
    )
}

@Suppress("FunctionNaming") // @Composable convention is PascalCase; detekt's rule does not know it.
@Composable
private fun RenameFields(
    substring: String,
    onSubstring: (String) -> Unit,
    displayName: String,
    onDisplayName: (String) -> Unit,
    busy: Boolean,
    canRemove: Boolean,
    onRemove: () -> Unit,
) {
    val sem = LocalLegionSemantics.current
    Column {
        Text(
            stringResource(R.string.merchant_alias_explainer),
            style = LegionType.stamp,
            color = sem.faint,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = substring,
            onValueChange = onSubstring,
            singleLine = true,
            enabled = !busy,
            label = { Text(stringResource(R.string.merchant_alias_bank_contains)) },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = displayName,
            onValueChange = onDisplayName,
            singleLine = true,
            enabled = !busy,
            label = { Text(stringResource(R.string.merchant_alias_show_as)) },
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
        if (canRemove) {
            TextButton(enabled = !busy, onClick = onRemove) {
                Text(stringResource(R.string.merchant_alias_remove))
            }
        }
    }
}

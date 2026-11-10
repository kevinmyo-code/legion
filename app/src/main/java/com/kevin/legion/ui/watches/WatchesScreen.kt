package com.kevin.legion.ui.watches

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kevin.legion.ui.common.DeckScreenHeader
import com.kevin.legion.ui.theme.soft.SoftColors
import com.kevin.legion.ui.theme.soft.SoftTheme
import com.kevin.legion.watches.KIND_BALANCE_UNDER
import com.kevin.legion.watches.KIND_CHARGE_OVER
import com.kevin.legion.watches.KIND_DEPOSIT_OVER
import com.kevin.legion.watches.Watch
import com.kevin.legion.watches.WatchesController

/**
 * `settings/watches` (ADR 0083): the hands path for every action of the `watch` voice tool
 * (ADR 0035) through the same [WatchesController]. List the watches (the built-in engine signals
 * among them), pause or resume each, remove one, add one of the simple kinds, and read what they
 * found. Plain existing components: a UI redesign (feat/typeset-ui) is in flight, so this stays
 * small. Offline, it says the engine cannot be reached; it never shows an empty list for that.
 */
@Composable
fun WatchesScreen(onBack: () -> Unit) {
    val viewModel: WatchesViewModel = viewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    SoftTheme {
        WatchesContent(
            state = state,
            callbacks = WatchesCallbacks(
                onBack = onBack,
                onRetry = viewModel::refresh,
                onKind = viewModel::setAddKind,
                onAmount = viewModel::setAddAmount,
                onText = viewModel::setAddText,
                onAdd = viewModel::add,
                onPaused = viewModel::setPaused,
                onRemove = viewModel::remove,
            ),
        )
    }
}

data class WatchesCallbacks(
    val onBack: () -> Unit = {},
    val onRetry: () -> Unit = {},
    val onKind: (String) -> Unit = {},
    val onAmount: (String) -> Unit = {},
    val onText: (String) -> Unit = {},
    val onAdd: () -> Unit = {},
    val onPaused: (Watch, Boolean) -> Unit = { _, _ -> },
    val onRemove: (Watch) -> Unit = {},
)

private val MONEY_KINDS = setOf(KIND_CHARGE_OVER, KIND_DEPOSIT_OVER, KIND_BALANCE_UNDER)

/** Plain UI: state in, callbacks out. */
@Composable
fun WatchesContent(state: WatchesUiState, callbacks: WatchesCallbacks) {
    Column(Modifier.fillMaxSize().background(SoftColors.ground)) {
        DeckScreenHeader(title = "Watches", onBack = callbacks.onBack)
        state.message?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = SoftColors.text,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag("watches-message"),
            )
        }
        when (val view = state.view) {
            WatchesView.Loading -> Line("Reading your watches from the engine...")
            is WatchesView.Unavailable -> {
                Line(view.sentence)
                TextButton(onClick = callbacks.onRetry) { Text("Try again") }
            }
            is WatchesView.Listed -> Listed(view, state, callbacks)
        }
    }
}

@Composable
private fun Listed(view: WatchesView.Listed, state: WatchesUiState, callbacks: WatchesCallbacks) {
    LazyColumn(Modifier.fillMaxSize()) {
        item { Section("What it watches") }
        if (view.watches.isEmpty()) item { Line("No watches are set.") }
        items(view.watches, key = { it.id }) { watch -> WatchRow(watch, state.busy, callbacks) }
        // Unreadable is not empty: a reply watch this phone cannot check says so, in words.
        state.mailNote?.let { note -> item { Line(note) } }
        item { Section("Add a watch") }
        item { AddForm(view, state, callbacks) }
        item { Section("What it found") }
        if (view.found.isEmpty()) item { Line("Nothing yet.") }
        items(view.found, key = { it.id }) { found -> Line("${found.facts} (${found.watchLabel})") }
    }
}

@Composable
private fun WatchRow(watch: Watch, busy: Boolean, callbacks: WatchesCallbacks) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).testTag("watch-${watch.id}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(watch.label, color = SoftColors.text, style = MaterialTheme.typography.bodyLarge)
            val built = if (watch.builtin != null) "Built in. " else ""
            val where = if (watch.evaluatedOn == "phone") " Checked on this phone." else ""
            Text(
                "$built${statusWords(watch.status)}$where",
                color = SoftColors.text2,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Switch(checked = !watch.paused, enabled = !busy, onCheckedChange = { on -> callbacks.onPaused(watch, !on) })
        TextButton(onClick = { callbacks.onRemove(watch) }, enabled = !busy) { Text("Remove") }
    }
}

private fun statusWords(status: String): String = when (status) {
    "paused" -> "Paused."
    "done" -> "Fired once and done."
    "expired" -> "Expired."
    else -> "On."
}

@Composable
private fun AddForm(view: WatchesView.Listed, state: WatchesUiState, callbacks: WatchesCallbacks) {
    Column(Modifier.padding(horizontal = 16.dp)) {
        view.kinds.chunked(2).forEach { pair ->
            Row {
                pair.forEach { kind ->
                    FilterChip(
                        selected = state.addKind == kind.kind,
                        onClick = { callbacks.onKind(kind.kind) },
                        label = { Text(kind.title) },
                        modifier = Modifier.padding(end = 8.dp),
                    )
                }
            }
        }
        view.kinds.firstOrNull { it.kind == state.addKind }?.let {
            Text(
                it.says.replaceFirstChar(Char::uppercase) + ".",
                color = SoftColors.text2,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (state.addKind in MONEY_KINDS) {
            OutlinedTextField(
                value = state.addAmount,
                onValueChange = callbacks.onAmount,
                label = { Text("Amount in dollars") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        textHint(state.addKind)?.let { hint ->
            OutlinedTextField(
                value = state.addText,
                onValueChange = callbacks.onText,
                label = { Text(hint) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        TextButton(onClick = callbacks.onAdd, enabled = !state.busy) { Text("Add this watch") }
    }
}

private fun textHint(kind: String): String? = when (WatchesController.textParam(kind)) {
    "merchant" -> "Merchant (optional)"
    "payer" -> "Payer (optional)"
    "account" -> "Account's last four digits (optional)"
    "course" -> "Course (optional)"
    "companies" -> "Companies, separated by commas"
    else -> null
}

@Composable
private fun Section(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = SoftColors.text,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun Line(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = SoftColors.text2,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

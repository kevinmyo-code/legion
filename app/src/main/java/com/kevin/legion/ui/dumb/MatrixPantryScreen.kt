package com.kevin.legion.ui.dumb

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * Matrix mode's pantry (matrix-open ticket 06): recent grocery receipts, grocery spend per currency, and
 * one receipt opened to its lines. Read only. A receipt's printed prices and its macro estimates are
 * separate sections, every estimate is labelled "estimate" on its own line, and a receipt that was not
 * fully verified says so in words on its row.
 */
@Composable
internal fun MatrixPantryScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val viewModel: MatrixPantryViewModel = viewModel(factory = MatrixPantryViewModel.Factory(context))
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
    val receipt = state.selected
    if (receipt != null) {
        MatrixScreen(receipt.title, viewModel::close) {
            MatrixPantryText.detailLines(receipt).forEach { MatrixNote(it.text, it.tone) }
        }
    } else {
        MatrixScreen("Pantry", onBack) { MatrixPantryList(state, viewModel) }
    }
}

@Composable
private fun MatrixPantryList(state: MatrixPantryUiState, viewModel: MatrixPantryViewModel) {
    MatrixNote(MatrixPantryText.NO_STOCK, MatrixTone.DIM)
    MatrixNote("Grocery spend", MatrixTone.HEADING)
    if (state.spendFailed && state.spend.isEmpty()) MatrixNote(MatrixPantryText.SPEND_UNREADABLE, MatrixTone.ALERT)
    state.spend.forEach { spend ->
        MatrixInfoRow(
            spend.line,
            tone = if (spend.caveat != null) MatrixTone.UNVERIFIED else MatrixTone.HEADING,
            detail = spend.caveat,
        )
    }
    MatrixNote("Recent receipts", MatrixTone.HEADING)
    MatrixPantryText.listNote(state)?.let { MatrixNote(it.text, it.tone) }
    state.receipts.forEach { receipt ->
        MatrixRow(
            receipt.title,
            { viewModel.open(receipt.id) },
            tone = if (receipt.unverified != null) MatrixTone.UNVERIFIED else MatrixTone.HEADING,
            detail = listOfNotNull(receipt.summary, receipt.unverified?.let { "Unverified" }).joinToString(" - "),
        )
    }
}

package com.kevin.legion.ui.dumb

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * Matrix mode's ledger (matrix-open ticket 06): account balances and the newest transactions, as text.
 * Read only. Every unconfirmed figure and row says so in words on its own line, and when the engine
 * could not be read the screen says it is showing the phone's copy.
 */
@Composable
internal fun MatrixLedgerScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val viewModel: MatrixLedgerViewModel = viewModel(factory = MatrixLedgerViewModel.Factory(context))
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
    MatrixScreen("Ledger", onBack) {
        state.syncLine?.let { MatrixNote(it, MatrixTone.WARNING) }
        MatrixNote("Balances", MatrixTone.HEADING)
        MatrixLedgerText.balancesNote(state)?.let { MatrixNote(it.text, it.tone) }
        state.balances.forEach { balance ->
            MatrixInfoRow(
                balance.title,
                tone = if (balance.unconfirmed) MatrixTone.UNVERIFIED else MatrixTone.HEADING,
                detail = balance.detail,
            )
        }
        MatrixNote("Recent transactions", MatrixTone.HEADING)
        MatrixLedgerText.recentNote(state)?.let { MatrixNote(it.text, it.tone) }
        state.recent.forEach { txn ->
            MatrixInfoRow(
                txn.title,
                tone = if (txn.unverified) MatrixTone.UNVERIFIED else MatrixTone.BODY,
                detail = txn.detail,
            )
        }
    }
}

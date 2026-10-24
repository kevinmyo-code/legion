package com.kevin.legion.ui.dumb

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.kevin.legion.backend.LedgerMirrorStatus
import com.kevin.legion.ledger.LedgerController
import com.kevin.legion.ledger.groupAccountBalances
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The data seam under the Matrix ledger. Reads only. */
internal interface MatrixLedgerSource {
    /** One worded balance per account. Throws when it cannot be read; never returns a guess. */
    suspend fun balances(): List<MatrixBalance>

    /** The newest transactions, worded. Throws when it cannot be read. */
    suspend fun recent(): List<MatrixTxn>

    /**
     * Non-null when the phone is showing its own copy because the engine could not be read, or refused
     * rows (LedgerMirrorStatus): the sentence to say so in words.
     */
    fun syncLine(): String?
}

/**
 * Everything the Matrix ledger draws. The two reads fail apart: [balancesFailed] and [recentFailed] are
 * each "could not read", never a zero balance or an empty list. [syncLine] says the figures are the
 * phone's copy when the engine could not be reached.
 */
internal data class MatrixLedgerUiState(
    val loading: Boolean = true,
    val balances: List<MatrixBalance> = emptyList(),
    val balancesFailed: Boolean = false,
    val recent: List<MatrixTxn> = emptyList(),
    val recentFailed: Boolean = false,
    val syncLine: String? = null,
)

/** Matrix mode's ledger lookups ViewModel (matrix-open ticket 06): balances and recent transactions. */
internal class MatrixLedgerViewModel(
    private val source: MatrixLedgerSource,
    scope: CoroutineScope? = null,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val scope: CoroutineScope = scope ?: viewModelScope

    private val _state = MutableStateFlow(MatrixLedgerUiState())
    val state: StateFlow<MatrixLedgerUiState> = _state.asStateFlow()

    fun refresh() {
        scope.launch {
            val balances = withContext(io) { runCatching { source.balances() } }
            val recent = withContext(io) { runCatching { source.recent() } }
            val sync = withContext(io) { runCatching { source.syncLine() }.getOrNull() }
            _state.update { current ->
                current.copy(
                    loading = false,
                    balances = balances.getOrDefault(current.balances),
                    balancesFailed = balances.isFailure,
                    recent = recent.getOrDefault(current.recent),
                    recentFailed = recent.isFailure,
                    syncLine = sync,
                )
            }
        }
    }

    class Factory(context: Context) : ViewModelProvider.Factory {
        private val app = context.applicationContext

        @Suppress("UNCHECKED_CAST") // the only type this factory is asked for
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            MatrixLedgerViewModel(AndroidMatrixLedgerSource(app)) as T
    }
}

/** [MatrixLedgerSource] over [LedgerController], the read funnel the Soft Money screens use. */
internal class AndroidMatrixLedgerSource(private val context: Context) : MatrixLedgerSource {
    override suspend fun balances(): List<MatrixBalance> =
        groupAccountBalances(LedgerController.accountBalances(context)).map(MatrixLedgerText::balanceOf)

    override suspend fun recent(): List<MatrixTxn> {
        val aliases = LedgerController.merchantAliases(context)
        return LedgerController.recentTransactions(context, RECENT_LIMIT).map { MatrixLedgerText.txnOf(it, aliases) }
    }

    override fun syncLine(): String? = LedgerMirrorStatus.line(context)

    private companion object {
        const val RECENT_LIMIT = 20
    }
}

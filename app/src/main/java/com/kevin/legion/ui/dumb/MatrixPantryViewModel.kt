package com.kevin.legion.ui.dumb

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.kevin.legion.pantry.PantryController
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The data seam under the Matrix pantry. Reads only. */
internal interface MatrixPantrySource {
    /** The newest receipts with their lines. Throws when it cannot be read. */
    suspend fun receipts(): List<MatrixReceipt>

    /** Grocery spend per currency. Throws when it cannot be read. */
    suspend fun spend(): List<MatrixSpend>
}

/**
 * Everything the Matrix pantry draws. [readFailed] and [spendFailed] each mean "could not read" and are
 * never rendered as an empty list or a zero. [selectedId] non-null means one receipt is open.
 */
internal data class MatrixPantryUiState(
    val loading: Boolean = true,
    val readFailed: Boolean = false,
    val receipts: List<MatrixReceipt> = emptyList(),
    val spendFailed: Boolean = false,
    val spend: List<MatrixSpend> = emptyList(),
    val selectedId: Long? = null,
) {
    val selected: MatrixReceipt? get() = receipts.firstOrNull { it.id == selectedId }
}

/** Matrix mode's pantry lookups ViewModel (matrix-open ticket 06): recent receipts, their lines, spend. */
internal class MatrixPantryViewModel(
    private val source: MatrixPantrySource,
    scope: CoroutineScope? = null,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val scope: CoroutineScope = scope ?: viewModelScope

    private val _state = MutableStateFlow(MatrixPantryUiState())
    val state: StateFlow<MatrixPantryUiState> = _state.asStateFlow()

    fun refresh() {
        scope.launch {
            val receipts = withContext(io) { runCatching { source.receipts() } }
            val spend = withContext(io) { runCatching { source.spend() } }
            _state.update { current ->
                current.copy(
                    loading = false,
                    receipts = receipts.getOrDefault(current.receipts),
                    readFailed = receipts.isFailure,
                    spend = spend.getOrDefault(current.spend),
                    spendFailed = spend.isFailure,
                )
            }
        }
    }

    fun open(id: Long) = _state.update { it.copy(selectedId = id) }

    fun close() = _state.update { it.copy(selectedId = null) }

    class Factory(context: Context) : ViewModelProvider.Factory {
        private val app = context.applicationContext

        @Suppress("UNCHECKED_CAST") // the only type this factory is asked for
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            MatrixPantryViewModel(AndroidMatrixPantrySource(app)) as T
    }
}

/** [MatrixPantrySource] over [PantryController], the read funnel the Soft pantry screen uses. */
internal class AndroidMatrixPantrySource(private val context: Context) : MatrixPantrySource {
    override suspend fun receipts(): List<MatrixReceipt> =
        PantryController.recentReceiptsWithItems(context).map { (receipt, items) ->
            MatrixPantryText.receiptOf(receipt, items)
        }

    override suspend fun spend(): List<MatrixSpend> =
        PantryController.totalSpendCentsByCurrency(context).map(MatrixPantryText::spendOf)
}

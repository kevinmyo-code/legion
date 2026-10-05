package com.kevin.legion.ui.bought

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kevin.legion.purchases.Purchase
import com.kevin.legion.ui.theme.soft.SoftTheme

/**
 * The bought log's entry point (purchase-log ticket 08, variant C from ticket 05: search first).
 * Reached from the Lists page's "Bought log" button, route `LegionRoute.BOUGHT`.
 *
 * Two internal modes, not two nav destinations ([BoughtMode]), the same convention
 * `ChecklistsScreen` uses: the search screen and the "Log it" form. Both go through
 * [BoughtViewModel] and so through [com.kevin.legion.purchases.PurchasesController], the controller
 * the `bought_log` voice tool also calls (ADR 0035).
 */
@Composable
fun BoughtScreen(onBack: () -> Unit) {
    val viewModel: BoughtViewModel = viewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Back from the form returns to the search, not out of the log.
    BackHandler(enabled = state.mode == BoughtMode.LOG) { viewModel.closeLog() }

    SoftTheme {
        when (state.mode) {
            BoughtMode.SEARCH -> BoughtSearchContent(
                state = state,
                callbacks = BoughtCallbacks(
                    onBack = onBack,
                    onQueryChange = viewModel::setQuery,
                    onRetry = viewModel::refresh,
                    onLogIt = { viewModel.openLog(it) },
                    onEditEntry = viewModel.changes::openEdit,
                    onDeleteEntry = viewModel.changes::askDelete,
                    onConfirmDelete = viewModel.changes::confirmDelete,
                    onCancelDelete = viewModel.changes::cancelDelete,
                ),
            )
            BoughtMode.LOG -> LogPurchaseContent(
                form = state.form,
                today = state.today,
                callbacks = LogCallbacks(
                    onBack = viewModel::closeLog,
                    onEdit = viewModel::editForm,
                    onSave = viewModel::save,
                ),
            )
        }
    }
}

/** What the search screen can ask for. [onLogIt] carries the search words so "I couldn't find
 * shampoo" can become "log shampoo" without retyping it. */
data class BoughtCallbacks(
    val onBack: () -> Unit,
    val onQueryChange: (String) -> Unit,
    val onRetry: () -> Unit,
    val onLogIt: (prefill: String?) -> Unit,
    /** Edit and Delete show only on entries the member may change ([Purchase.mayChange]). */
    val onEditEntry: (Purchase) -> Unit = {},
    val onDeleteEntry: (Purchase) -> Unit = {},
    val onConfirmDelete: () -> Unit = {},
    val onCancelDelete: () -> Unit = {},
)

/** What the form can ask for. [onEdit] takes a transform so the form's one state is edited in one place. */
data class LogCallbacks(
    val onBack: () -> Unit,
    val onEdit: ((LogFormState) -> LogFormState) -> Unit,
    val onSave: () -> Unit,
)

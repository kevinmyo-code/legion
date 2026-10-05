package com.kevin.legion.ui.checklists

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kevin.legion.ui.theme.soft.SoftTheme

/**
 * The hands path (and only path - no voice tools were built for this ticket) for recurring
 * checklists. **Rewritten by home-launcher ticket 04** onto the icon-card Lists page and a
 * Keep-style open list (ticket 01 §§3-4, ADR 0051) - `MainActivity.kt` and the
 * `LegionRoute.CHECKLISTS` route are unchanged, this stays the entry point.
 *
 * **Three internal states, not three nav-graph destinations** ([ListsMode]) - list -> open list ->
 * that list's history, same convention this file has always used.
 *
 * Everything below renders inside [SoftTheme] (ticket 04's own instruction) - the whole reason
 * this file, unlike most of the app, does not wrap its content in
 * [com.kevin.legion.ui.theme.LegionTheme].
 *
 * **Every write funnels through [ListsViewModel], which itself calls only
 * [com.kevin.legion.checklists.ChecklistController]** - this screen owns no DAO reference and
 * re-derives nothing the controller already decided (the untick trap - [ListsViewModel.untick]'s
 * own doc comment - is closed in the controller via `ItemState.tickDay`, never re-checked here).
 */
@Composable
fun ChecklistsScreen(onBack: () -> Unit, onOpenBought: (() -> Unit)? = null) {
    val viewModel: ListsViewModel = viewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.refresh()
    }

    SoftTheme {
        when (state.mode) {
            is ListsMode.Page -> ListsContent(
                state = state.page,
                callbacks = listsPageCallbacks(viewModel, onBack, onOpenBought),
            )
            is ListsMode.Detail -> {
                state.detail?.let { detail ->
                    ListDetailContent(
                        state = detail,
                        callbacks = ListDetailCallbacks(
                            onBack = viewModel::backToPage,
                            onOpenHistory = viewModel::openHistory,
                            onToggleOverflowMenu = viewModel::toggleOverflowMenu,
                            onShowRenameDialog = viewModel::showRenameDialog,
                            onRename = viewModel::rename,
                            onShowSchedulePicker = viewModel::showSchedulePicker,
                            onSetSchedule = viewModel::setSchedule,
                            onArchiveToggle = viewModel::archiveToggle,
                            onShowDeleteConfirm = viewModel::showDeleteConfirm,
                            onConfirmDelete = viewModel::confirmDelete,
                            onTick = viewModel::tick,
                            onUntick = viewModel::untick,
                            onTickMeasured = viewModel::tickMeasured,
                            onSetInputValue = viewModel::setInputValue,
                            onDraftChange = viewModel::setDraft,
                            onAddItem = viewModel::addItem,
                            onToggleShowTicked = viewModel::toggleShowTicked,
                            onLongPress = viewModel::longPress,
                            onDismissLongPress = viewModel::dismissLongPress,
                            onEdit = viewModel::startEdit,
                            onDismissEdit = viewModel::dismissEdit,
                            onSaveEdit = viewModel::saveEdit,
                            onMoveUp = viewModel::moveUp,
                            onMoveDown = viewModel::moveDown,
                            onDeleteItem = viewModel::deleteItem,
                        ),
                    )
                }
                Unit
            }
            is ListsMode.History -> {
                state.history?.let { history ->
                    ListHistoryContent(state = history, onBack = viewModel::backFromHistory)
                }
                Unit
            }
        }
    }
}

/** The Lists page's callbacks, built outside [ChecklistsScreen] so that function stays under
 * detekt's length ceiling once it also carries the bought-log entry point (purchase-log ticket 08). */
private fun listsPageCallbacks(viewModel: ListsViewModel, onBack: () -> Unit, onOpenBought: (() -> Unit)?) =
    ListsPageCallbacks(
        onBack = onBack,
        onOpenList = viewModel::openList,
        onToggleArchived = viewModel::toggleShowArchived,
        onShowCreateDialog = viewModel::showCreateDialog,
        onCreate = viewModel::createList,
        onOpenBought = onOpenBought,
    )

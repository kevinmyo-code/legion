package com.kevin.legion.ui.dumb

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kevin.legion.ui.checklists.ListDetailState
import com.kevin.legion.ui.checklists.ListItemUi
import com.kevin.legion.ui.checklists.ListsMode
import com.kevin.legion.ui.checklists.ListsPageState
import com.kevin.legion.ui.checklists.ListsViewModel

/**
 * Matrix mode's lists and to-dos (matrix-open ticket 03): the list of lists, an open list with tick,
 * untick, add and delete. Text rows only. It drives the SAME [ListsViewModel] the Soft lists screen
 * does, so every write is the same [com.kevin.legion.checklists.ChecklistController] call and a
 * failed one arrives as the same `writeError` sentence; this file only draws it differently.
 */
@Composable
internal fun MatrixListsScreen(onBack: () -> Unit) {
    val viewModel: ListsViewModel = viewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
    when (state.mode) {
        is ListsMode.Page -> MatrixScreen("Lists", onBack) { MatrixListsPage(state.page, viewModel) }
        else -> {
            val detail = state.detail
            MatrixScreen(detail?.checklist?.name ?: "List", viewModel::backToPage) {
                if (detail != null) MatrixListDetail(detail, viewModel)
            }
        }
    }
}

@Composable
private fun MatrixListsPage(page: ListsPageState, viewModel: ListsViewModel) {
    var newName by remember { mutableStateOf("") }
    MatrixListsText.pageNote(page)?.let { MatrixNote(it) }
    MatrixListsText.groceriesNote(page)?.let { MatrixNote(it, MatrixTone.DIM) }
    (MatrixListsText.pageRows(page) + MatrixListsText.archivedRows(page)).forEach { row ->
        MatrixRow(row.text, { viewModel.openList(row.id) }, Modifier.fillMaxWidth(), row.tone, row.detail)
    }
    MatrixListsText.archivedToggle(page)?.let {
        MatrixRow(it, viewModel::toggleShowArchived, tone = MatrixTone.DIM)
    }
    page.createError?.let { MatrixNote(it, MatrixTone.ALERT) }
    MatrixField("New list name", newName, { newName = it }, {
        if (newName.isNotBlank()) {
            // Opening the create state first clears a stale error from a previous attempt.
            viewModel.showCreateDialog(true)
            viewModel.createList(newName, null, null)
            newName = ""
        }
    })
}

@Composable
private fun MatrixListDetail(detail: ListDetailState, viewModel: ListsViewModel) {
    MatrixListsText.scheduleNote(detail)?.let { MatrixNote(it, MatrixTone.DIM) }
    detail.writeError?.let { MatrixNote(it, MatrixTone.ALERT) }
    val note = MatrixListsText.detailNote(detail)
    note?.let { MatrixNote(it, if (detail.loadFailed) MatrixTone.ALERT else MatrixTone.BODY) }
    if (detail.loading || detail.loadFailed) return
    detail.unticked.forEach { row -> MatrixOpenItem(row, detail, viewModel) }
    detail.ticked.forEach { row -> MatrixTickedItem(row, viewModel) }
    MatrixField("Add item", detail.draft, viewModel::setDraft, viewModel::addItem)
}

@Composable
private fun MatrixOpenItem(row: ListItemUi, detail: ListDetailState, viewModel: ListsViewModel) {
    val shown = MatrixListsText.openRow(row)
    val measured = row.item.measureUnit != null
    MatrixItemLine(
        shown,
        onTap = { if (measured) viewModel.tickMeasured(row.item.id) else viewModel.tick(row.item.id) },
        onDelete = { viewModel.deleteItem(row.item.id) },
    )
    if (measured) {
        MatrixField(
            "Value in ${row.item.measureUnit}, then Enter to tick",
            detail.inputValues[row.item.id].orEmpty(),
            { viewModel.setInputValue(row.item.id, it) },
            { viewModel.tickMeasured(row.item.id) },
        )
    }
    detail.refusals[row.item.id]?.let { MatrixNote(it, MatrixTone.WARNING) }
}

@Composable
private fun MatrixTickedItem(row: ListItemUi, viewModel: ListsViewModel) {
    MatrixItemLine(
        MatrixListsText.tickedRow(row),
        // A ticked row without a tick day cannot be unticked; the Soft screen does nothing then too.
        onTap = { row.tickDay?.let { viewModel.untick(row.item.id, it) } },
        onDelete = { viewModel.deleteItem(row.item.id) },
    )
}

/** An item: the row taps to tick or untick, and "delete" beside it removes the item. */
@Composable
private fun MatrixItemLine(row: MatrixListRow, onTap: () -> Unit, onDelete: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        MatrixRow(row.text, onTap, Modifier.weight(1f), row.tone, row.detail)
        Text(
            "delete",
            color = MatrixTone.DIM.color(),
            fontFamily = DumbMono,
            fontSize = 14.sp,
            modifier = Modifier
                .heightIn(min = 44.dp)
                .clickable(role = Role.Button, onClickLabel = "Delete ${row.text}", onClick = onDelete)
                .padding(start = 12.dp, top = 12.dp),
        )
    }
}

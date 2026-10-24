package com.kevin.legion.ui.dumb

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * Matrix mode's notes (matrix-open ticket 05): the list, read one, write one, edit its text, tick it,
 * delete it. Drives [MatrixNotesViewModel], whose source is the same
 * [com.kevin.legion.notes.NotesController] the Soft calendar's reminders write through.
 */
@Composable
internal fun MatrixNotesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val viewModel: MatrixNotesViewModel = viewModel(factory = MatrixNotesViewModel.Factory(context))
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
    val note = state.selected
    if (note != null) {
        MatrixScreen("Note", viewModel::close) { MatrixNoteDetail(note, state, viewModel) }
    } else {
        MatrixScreen("Notes", onBack) { MatrixNotesList(state, viewModel) }
    }
}

@Composable
private fun MatrixNotesList(state: MatrixNotesUiState, viewModel: MatrixNotesViewModel) {
    MatrixField("New note", state.draft, viewModel::setDraft, viewModel::add)
    state.notice?.let { MatrixNote(it) }
    MatrixNotesText.listNote(state)?.let {
        MatrixNote(it, if (state.readFailed) MatrixTone.ALERT else MatrixTone.DIM)
    }
    MatrixNotesText.staleNote(state)?.let { MatrixNote(it, MatrixTone.WARNING) }
    state.items.forEach { item ->
        MatrixRow(
            MatrixNotesText.rowText(item),
            { viewModel.open(item.id) },
            tone = MatrixNotesText.rowTone(item),
            detail = MatrixNotesText.rowDetail(item),
        )
    }
}

@Composable
private fun MatrixNoteDetail(note: MatrixNoteItem, state: MatrixNotesUiState, viewModel: MatrixNotesViewModel) {
    state.notice?.let { MatrixNote(it) }
    MatrixNotesText.rowDetail(note)?.let { MatrixNote(it, MatrixTone.DIM) }
    MatrixField("Text", state.editText, viewModel::setEditText, viewModel::saveEdit)
    MatrixRow("Save change", viewModel::saveEdit)
    MatrixRow(if (note.done) "Untick" else "Tick off", viewModel::toggleDone)
    if (state.confirmingDelete) {
        MatrixRow("Tap again to delete this note", viewModel::confirmDelete, tone = MatrixTone.ALERT)
    } else {
        MatrixRow("Delete", viewModel::askDelete, tone = MatrixTone.DIM)
    }
}

package com.kevin.legion.ui.dumb

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One note, flattened out of [com.kevin.legion.data.local.ListItem] so the text layer needs no Android types. */
internal data class MatrixNoteItem(
    val id: Long,
    val text: String,
    val done: Boolean,
    val whenLabel: String? = null,
    val repeats: Boolean = false,
    val placeLabel: String? = null,
)

/** The data seam under the Matrix notes: the real one is over [com.kevin.legion.notes.NotesController]. */
internal interface MatrixNotesSource {
    /** Every note. Throws when it cannot be read; never returns a guess. */
    suspend fun items(): List<MatrixNoteItem>

    /** True only when the write was accepted. */
    suspend fun add(text: String): Boolean

    suspend fun rename(id: Long, text: String): Boolean

    suspend fun setDone(id: Long, done: Boolean): Boolean

    suspend fun remove(id: Long): Boolean
}

/**
 * Everything the Matrix notes screen draws. [readFailed] (could not read) is never rendered as an empty
 * list. [selectedId] non-null means one note is open; [editText] is its text field, [confirmingDelete]
 * the second-tap state of Delete. [notice] is the sentence from the last action, including the failure
 * that did NOT happen.
 */
internal data class MatrixNotesUiState(
    val loading: Boolean = true,
    val readFailed: Boolean = false,
    val items: List<MatrixNoteItem> = emptyList(),
    val draft: String = "",
    val selectedId: Long? = null,
    val editText: String = "",
    val confirmingDelete: Boolean = false,
    val notice: String? = null,
) {
    val selected: MatrixNoteItem? get() = items.firstOrNull { it.id == selectedId }
}

/**
 * Matrix mode's notes ViewModel (matrix-open ticket 05): the notes list, read one, write one, edit its
 * text, tick it, delete it. Every action goes through [MatrixNotesSource], whose real implementation
 * calls the same [com.kevin.legion.notes.NotesController] funnels the Soft calendar's reminders use.
 */
internal class MatrixNotesViewModel(
    private val source: MatrixNotesSource,
    scope: CoroutineScope? = null,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val scope: CoroutineScope = scope ?: viewModelScope

    private val _state = MutableStateFlow(MatrixNotesUiState())
    val state: StateFlow<MatrixNotesUiState> = _state.asStateFlow()

    fun refresh() {
        scope.launch {
            val read = withContext(io) { runCatching { source.items() } }
            _state.update { current ->
                read.fold(
                    onSuccess = { current.copy(loading = false, readFailed = false, items = it) },
                    onFailure = { current.copy(loading = false, readFailed = true) },
                )
            }
        }
    }

    fun setDraft(text: String) = _state.update { it.copy(draft = text) }

    fun setEditText(text: String) = _state.update { it.copy(editText = text) }

    fun open(id: Long) = _state.update { s ->
        s.copy(selectedId = id, editText = s.items.firstOrNull { it.id == id }?.text.orEmpty(), notice = null)
    }

    fun close() = _state.update { it.copy(selectedId = null, confirmingDelete = false) }

    fun add() {
        val text = _state.value.draft.trim()
        if (text.isEmpty()) return
        write("Saved a new note.", "That note was not saved", { it.copy(draft = "") }) { source.add(text) }
    }

    fun saveEdit() {
        val id = _state.value.selectedId ?: return
        val text = _state.value.editText.trim()
        if (text.isEmpty()) {
            _state.update { it.copy(notice = "A note can't be empty. Nothing was changed.") }
            return
        }
        write("Saved the change.", "The change was not saved") { source.rename(id, text) }
    }

    fun toggleDone() {
        val note = _state.value.selected ?: return
        val verb = if (note.done) "unticked" else "ticked"
        if (note.repeats && !note.done) {
            _state.update { it.copy(notice = "This note repeats, so it can't be ticked off here. Nothing changed.") }
            return
        }
        write("Note $verb.", "The note was not $verb") { source.setDone(note.id, !note.done) }
    }

    fun askDelete() = _state.update { it.copy(confirmingDelete = true) }

    fun confirmDelete() {
        val id = _state.value.selectedId ?: return
        val closed: (MatrixNotesUiState) -> MatrixNotesUiState = { it.copy(selectedId = null) }
        write("Deleted the note.", "The note was not deleted", closed) { source.remove(id) }
    }

    /**
     * Runs a write. On success says [done], applies [onOk] to the state and re-reads; on failure says
     * [failed] plus that nothing was changed, and leaves everything else as it was.
     */
    private fun write(
        done: String,
        failed: String,
        onOk: (MatrixNotesUiState) -> MatrixNotesUiState = { it },
        action: suspend () -> Boolean,
    ) {
        scope.launch {
            val ok = withContext(io) { runCatching { action() }.getOrDefault(false) }
            _state.update {
                val next = if (ok) onOk(it) else it
                next.copy(notice = if (ok) done else "$failed. Nothing was changed.", confirmingDelete = false)
            }
            if (ok) refresh()
        }
    }

    class Factory(context: Context) : ViewModelProvider.Factory {
        private val app = context.applicationContext

        @Suppress("UNCHECKED_CAST") // the only type this factory is asked for
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            MatrixNotesViewModel(AndroidMatrixNotesSource(app)) as T
    }
}

package com.kevin.legion.ui.dumb

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.kevin.legion.data.local.VoiceNote
import com.kevin.legion.voice.VoiceNoteRecordingState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The data seam under the Matrix voice notes. The real one is over [com.kevin.legion.voice.VoiceNoteController]. */
internal interface MatrixVoiceNotesSource {
    /** The live recording state: the one flow every surface that can start a recording reads. */
    val recording: StateFlow<VoiceNoteRecordingState>

    /** Newest first. Throws when it cannot be read. */
    suspend fun list(): List<VoiceNote>

    /** Null when a recording started, else the controller's own refusal sentence. */
    suspend fun start(): String?

    suspend fun stop()

    /** Null when renamed plainly, else the sentence to show (a refusal or "saved on this phone only"). */
    suspend fun rename(id: Long, title: String): String?

    /** Null when deleted (or already gone), else why it was not. */
    suspend fun delete(id: Long): String?

    /** Null when a retry started, else why it could not. */
    suspend fun retry(id: Long): String?
}

/**
 * Everything the Matrix voice notes screen draws. [readFailed] is never an empty list. [startRefusal] is
 * the controller's own sentence when a recording did not start. [notice] is the last action's sentence.
 * [renameDraft] non-null means the title field is open; [confirmingDelete] is Delete's second tap.
 */
internal data class MatrixVoiceNotesUiState(
    val loading: Boolean = true,
    val readFailed: Boolean = false,
    val notes: List<VoiceNote> = emptyList(),
    val selectedId: Long? = null,
    val recording: VoiceNoteRecordingState = VoiceNoteRecordingState.Idle,
    val startRefusal: String? = null,
    val notice: String? = null,
    val renameDraft: String? = null,
    val confirmingDelete: Boolean = false,
) {
    val selected: VoiceNote? get() = notes.firstOrNull { it.id == selectedId }
}

/**
 * Matrix mode's voice notes ViewModel (matrix-open ticket 05): list, record, stop, open one, rename,
 * delete, retry a failed transcription. Record and stop are [com.kevin.legion.voice.VoiceNoteController.start]
 * and `stop`, the same recorder the Soft screen and the voice tools use; playback stays in the screen.
 */
internal class MatrixVoiceNotesViewModel(
    private val source: MatrixVoiceNotesSource,
    scope: CoroutineScope? = null,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val scope: CoroutineScope = scope ?: viewModelScope

    private val _state = MutableStateFlow(MatrixVoiceNotesUiState(recording = source.recording.value))
    val state: StateFlow<MatrixVoiceNotesUiState> = _state.asStateFlow()

    init {
        // `this.scope`: the constructor parameter of the same name is the nullable test seam.
        this.scope.launch { source.recording.collect { live -> _state.update { it.copy(recording = live) } } }
    }

    fun refresh() {
        scope.launch {
            val read = withContext(io) { runCatching { source.list() } }
            _state.update { current ->
                read.fold(
                    onSuccess = { current.copy(loading = false, readFailed = false, notes = it) },
                    onFailure = { current.copy(loading = false, readFailed = true) },
                )
            }
        }
    }

    fun record() {
        scope.launch {
            val refusal = withContext(io) { source.start() }
            _state.update { it.copy(startRefusal = refusal, notice = null) }
        }
    }

    fun stop() {
        scope.launch {
            withContext(io) { source.stop() }
            _state.update { it.copy(startRefusal = null, notice = MatrixVoiceNotesText.STOPPED) }
            refresh()
        }
    }

    fun open(id: Long) = _state.update { it.copy(selectedId = id, notice = null, renameDraft = null) }

    fun close() = _state.update { it.copy(selectedId = null, renameDraft = null, confirmingDelete = false) }

    fun startRename() = _state.update { it.copy(renameDraft = it.selected?.title.orEmpty()) }

    fun setRenameDraft(text: String) = _state.update { it.copy(renameDraft = text) }

    fun saveRename() {
        val id = _state.value.selectedId ?: return
        val title = _state.value.renameDraft?.trim().orEmpty()
        scope.launch {
            val said = withContext(io) { runCatching { source.rename(id, title) }.getOrElse { UNEXPECTED } }
            _state.update { it.copy(renameDraft = null, notice = said ?: "Renamed.") }
            refresh()
        }
    }

    fun askDelete() = _state.update { it.copy(confirmingDelete = true) }

    fun confirmDelete() {
        val id = _state.value.selectedId ?: return
        scope.launch {
            val failure = withContext(io) { runCatching { source.delete(id) }.getOrElse { UNEXPECTED_DELETE } }
            _state.update {
                if (failure == null) {
                    it.copy(selectedId = null, confirmingDelete = false, notice = MatrixVoiceNotesText.DELETED)
                } else {
                    it.copy(confirmingDelete = false, notice = failure)
                }
            }
            if (failure == null) refresh()
        }
    }

    fun retry() {
        val id = _state.value.selectedId ?: return
        scope.launch {
            val failure = withContext(io) { runCatching { source.retry(id) }.getOrElse { UNEXPECTED_RETRY } }
            _state.update { it.copy(notice = failure ?: MatrixVoiceNotesText.RETRYING) }
        }
    }

    class Factory(context: Context) : ViewModelProvider.Factory {
        private val app = context.applicationContext

        @Suppress("UNCHECKED_CAST") // the only type this factory is asked for
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            MatrixVoiceNotesViewModel(AndroidMatrixVoiceNotesSource(app)) as T
    }

    private companion object {
        const val UNEXPECTED = "The rename did not happen: something went wrong. Nothing was changed."
        const val UNEXPECTED_DELETE = "The recording was not deleted: something went wrong. Nothing was removed."
        const val UNEXPECTED_RETRY = "The retry did not start: something went wrong."
    }
}

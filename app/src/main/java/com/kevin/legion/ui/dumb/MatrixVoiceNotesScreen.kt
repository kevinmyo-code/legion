package com.kevin.legion.ui.dumb

import android.media.MediaPlayer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kevin.legion.data.local.VoiceNote
import com.kevin.legion.voice.VoiceNoteRecordingState
import kotlinx.coroutines.delay

/**
 * Matrix mode's voice notes (matrix-open ticket 05): record and stop, the list, and one recording's
 * summary and transcript with play, rename, delete and retry. Drives [MatrixVoiceNotesViewModel] over the
 * same [com.kevin.legion.voice.VoiceNoteController] as the Soft Recordings screen; playback is the same
 * MediaPlayer calls the Soft detail makes.
 */
@Composable
internal fun MatrixVoiceNotesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val viewModel: MatrixVoiceNotesViewModel = viewModel(factory = MatrixVoiceNotesViewModel.Factory(context))
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
    val note = state.selected
    if (note != null) {
        MatrixScreen("Recording", viewModel::close) { MatrixVoiceNoteDetail(note, state, viewModel) }
    } else {
        MatrixScreen("Voice notes", onBack) { MatrixVoiceNotesList(state, viewModel) }
    }
}

@Composable
private fun MatrixVoiceNotesList(state: MatrixVoiceNotesUiState, viewModel: MatrixVoiceNotesViewModel) {
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.recording) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(ELAPSED_TICK_MS)
        }
    }
    MatrixNote(MatrixVoiceNotesText.recordingLine(state.recording, nowMs), MatrixTone.HEADING)
    MatrixRow(
        MatrixVoiceNotesText.recordActionLabel(state.recording),
        { if (state.recording is VoiceNoteRecordingState.Recording) viewModel.stop() else viewModel.record() },
    )
    state.startRefusal?.let { MatrixNote(it, MatrixTone.WARNING) }
    state.notice?.let { MatrixNote(it) }
    if (state.recording is VoiceNoteRecordingState.Idle) {
        MatrixNote(MatrixVoiceNotesText.IDLE_HINT, MatrixTone.DIM)
    }
    MatrixVoiceNotesText.listNote(state)?.let {
        MatrixNote(it, if (state.readFailed) MatrixTone.ALERT else MatrixTone.DIM)
    }
    MatrixVoiceNotesText.staleNote(state)?.let { MatrixNote(it, MatrixTone.WARNING) }
    state.notes.forEach { note ->
        MatrixRow(
            MatrixVoiceNotesText.rowTitle(note),
            { viewModel.open(note.id) },
            tone = MatrixVoiceNotesText.rowTone(note),
            detail = MatrixVoiceNotesText.rowDetail(note),
        )
    }
}

@Composable
private fun MatrixVoiceNoteDetail(
    note: VoiceNote,
    state: MatrixVoiceNotesUiState,
    viewModel: MatrixVoiceNotesViewModel,
) {
    MatrixNote(MatrixVoiceNotesText.rowTitle(note), MatrixTone.HEADING)
    state.notice?.let { MatrixNote(it) }
    MatrixVoiceNoteDetailText.statusLines(note).forEach { MatrixNote(it.text, it.tone) }
    if (MatrixVoiceNoteDetailText.canRetry(note)) MatrixRow("Retry transcription", viewModel::retry)
    MatrixPlayRow(note)
    MatrixVoiceNoteDetailText.bodyLines(note).forEach { MatrixNote(it.text, it.tone) }
    val draft = state.renameDraft
    if (draft == null) {
        MatrixRow("Rename", viewModel::startRename, tone = MatrixTone.DIM)
    } else {
        MatrixField("Title", draft, viewModel::setRenameDraft, viewModel::saveRename)
        MatrixRow("Save title", viewModel::saveRename)
    }
    if (state.confirmingDelete) {
        MatrixRow(
            "Tap again to delete this recording, its audio, transcript and summary",
            viewModel::confirmDelete,
            tone = MatrixTone.ALERT,
        )
    } else {
        MatrixRow("Delete", viewModel::askDelete, tone = MatrixTone.DIM)
    }
}

/** Play or pause the audio. A failure to play is said, never a silent no-op. Nothing renders without audio. */
@Composable
private fun MatrixPlayRow(note: VoiceNote) {
    val path = note.audioPath ?: return
    val player = remember { MediaPlayer() }
    var playing by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    DisposableEffect(Unit) { onDispose { player.release() } }
    MatrixRow(
        if (playing) "Pause" else "Play audio",
        {
            if (playing) {
                player.pause()
                playing = false
            } else {
                failure = runCatching {
                    player.reset()
                    player.setDataSource(path)
                    player.prepare()
                    player.setOnCompletionListener { playing = false }
                    player.start()
                    playing = true
                    null
                }.getOrElse {
                    playing = false
                    "The audio could not be played. Nothing was changed."
                }
            }
        },
    )
    failure?.let { MatrixNote(it, MatrixTone.WARNING) }
}

private const val ELAPSED_TICK_MS = 500L


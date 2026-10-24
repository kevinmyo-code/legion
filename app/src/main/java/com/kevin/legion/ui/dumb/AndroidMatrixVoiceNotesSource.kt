package com.kevin.legion.ui.dumb

import android.content.Context
import com.kevin.legion.data.local.VoiceNote
import com.kevin.legion.data.local.VoiceNoteKind
import com.kevin.legion.ui.voicenotes.renameNotice
import com.kevin.legion.voice.VoiceNoteController
import com.kevin.legion.voice.VoiceNoteRecordingState
import com.kevin.legion.voice.VoiceNoteStartResult
import kotlinx.coroutines.flow.StateFlow

/**
 * [MatrixVoiceNotesSource] over [VoiceNoteController], the recorder the Soft Recordings screen and the
 * `start_voice_note` tool use. Wording that already exists is reused rather than rewritten: a start
 * refusal is the controller's own sentence and a rename's outcome goes through the Soft screen's
 * `renameNotice`.
 */
internal class AndroidMatrixVoiceNotesSource(private val context: Context) : MatrixVoiceNotesSource {
    override val recording: StateFlow<VoiceNoteRecordingState> get() = VoiceNoteController.recordingState(context)

    override suspend fun list(): List<VoiceNote> = VoiceNoteController.listNotes(context)

    override suspend fun start(): String? =
        when (val started = VoiceNoteController.start(context, VoiceNoteKind.SOLO)) {
            is VoiceNoteStartResult.Started -> null
            is VoiceNoteStartResult.Refused -> started.reason
        }

    override suspend fun stop() {
        VoiceNoteController.stop(context)
    }

    override suspend fun rename(id: Long, title: String): String? =
        renameNotice(VoiceNoteController.rename(context, id, title))

    override suspend fun delete(id: Long): String? =
        when (val result = VoiceNoteController.delete(context, id)) {
            is VoiceNoteController.DeleteResult.Deleted, VoiceNoteController.DeleteResult.NotFound -> null
            is VoiceNoteController.DeleteResult.Failed -> result.reason
        }

    override suspend fun retry(id: Long): String? =
        when (VoiceNoteController.retryTranscription(context, id)) {
            VoiceNoteController.RetryResult.Retrying -> null
            VoiceNoteController.RetryResult.NotFound -> "That recording is gone, so there is nothing to retry."
            VoiceNoteController.RetryResult.NoAudio -> "No audio is left for that recording, so it can't be retried."
        }
}

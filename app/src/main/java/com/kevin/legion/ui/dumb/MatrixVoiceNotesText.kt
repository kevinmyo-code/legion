package com.kevin.legion.ui.dumb

import com.kevin.legion.data.local.VoiceNote
import com.kevin.legion.data.local.VoiceNoteKind
import com.kevin.legion.ui.voicenotes.VoiceNoteRowState
import com.kevin.legion.ui.voicenotes.formatMmSs
import com.kevin.legion.ui.voicenotes.formatVoiceNoteDuration
import com.kevin.legion.ui.voicenotes.voiceNoteRowState
import com.kevin.legion.ui.voicenotes.voiceNoteRowStateLabel
import com.kevin.legion.util.clockTime
import com.kevin.legion.util.shortDate
import com.kevin.legion.voice.VoiceNoteRecordingState

/** One tone-coloured line of text. */
internal data class MatrixTextLine(val text: String, val tone: MatrixTone = MatrixTone.BODY)

/**
 * The words on the Matrix voice notes screen (matrix-open ticket 05). The state word is the Soft
 * screen's own ([voiceNoteRowState] / [voiceNoteRowStateLabel]) so the two cannot name one state two
 * ways. A summary or transcript is always labelled AI-generated, in words (ADR 0041), and an
 * interrupted or failed recording says so on its row, not only once opened.
 */
internal object MatrixVoiceNotesText {
    const val LOADING = "Reading your recordings..."
    const val UNREADABLE = "Couldn't read your recordings. This is not an empty list: nothing could be read."
    const val EMPTY = "No voice notes yet."
    const val STALE = "Couldn't refresh your recordings. These may be out of date."
    const val IDLE_HINT =
        "A stopped recording is saved and transcribed in the background. It shows a summary once that " +
            "finishes, not immediately."
    const val STOPPED = "Stopped. The recording is saved and is being transcribed; it is not ready to read yet."
    const val RETRYING = "Retrying the transcription. It is not ready yet."
    const val DELETED = "Deleted. The audio, transcript and summary went with it."
    private const val INTERRUPTED_ROW = "Interrupted - this recording may be incomplete"
    private const val PREVIEW_CHARS = 140

    fun listNote(state: MatrixVoiceNotesUiState): String? = when {
        state.loading -> LOADING
        state.readFailed && state.notes.isEmpty() -> UNREADABLE
        state.notes.isEmpty() -> EMPTY
        else -> null
    }

    fun staleNote(state: MatrixVoiceNotesUiState): String? =
        if (state.readFailed && state.notes.isNotEmpty()) STALE else null

    /** "Recording - 0:12" while recording, else "Not recording". [nowMs] is passed in so it is testable. */
    fun recordingLine(state: VoiceNoteRecordingState, nowMs: Long): String = when (state) {
        VoiceNoteRecordingState.Idle -> "Not recording"
        is VoiceNoteRecordingState.Recording -> "Recording - ${formatMmSs(nowMs - state.startedAt)}"
    }

    fun recordActionLabel(state: VoiceNoteRecordingState): String =
        if (state is VoiceNoteRecordingState.Recording) "Stop" else "Record"

    private fun kindWord(note: VoiceNote) = if (note.kind == VoiceNoteKind.MEETING) "Meeting" else "Solo"

    fun whenLine(note: VoiceNote) =
        "${kindWord(note)} - ${shortDate(note.startedAt)} ${clockTime(note.startedAt)} - " +
            formatVoiceNoteDuration(note.startedAt, note.endedAt)

    fun rowTitle(note: VoiceNote): String = note.title ?: "Untitled recording"

    /** The row's lines under the title: when, state word, then summary preview / failure / not transcribed. */
    fun rowDetail(note: VoiceNote): String {
        val state = voiceNoteRowStateLabel(voiceNoteRowState(note))
        val third = when {
            note.summary != null -> "AI-generated summary: ${preview(note.summary)}"
            note.transcriptionFailureReason != null -> note.transcriptionFailureReason
            else -> "Not transcribed yet"
        }
        val interrupted = if (note.interrupted) listOf(INTERRUPTED_ROW) else emptyList()
        return (listOf("${whenLine(note)} - $state", third) + interrupted).joinToString("\n")
    }

    private fun preview(text: String) =
        if (text.length <= PREVIEW_CHARS) text else text.take(PREVIEW_CHARS).trimEnd() + "..."

    fun rowTone(note: VoiceNote): MatrixTone = when (voiceNoteRowState(note)) {
        VoiceNoteRowState.INTERRUPTED, VoiceNoteRowState.FAILED -> MatrixTone.UNVERIFIED
        else -> MatrixTone.HEADING
    }
}

/** The open recording's text: its status lines, then the AI-labelled summary and transcript. */
internal object MatrixVoiceNoteDetailText {
    const val SUMMARY_LABEL = "AI-generated summary - not a verbatim account:"
    const val TRANSCRIPT_LABEL = "AI-generated transcript - as close to verbatim as the model could make out:"
    const val SUMMARY_PENDING =
        "Not transcribed yet. The audio is saved and this fills in once transcription finishes."
    const val NO_AUDIO = "Audio is no longer available for this recording."
    const val INTERRUPTED = "This recording was interrupted before it finished and may be incomplete."

    /** The open recording's status: when, then interrupted, failure and missing audio said in words. */
    fun statusLines(note: VoiceNote): List<MatrixTextLine> = buildList {
        val state = voiceNoteRowStateLabel(voiceNoteRowState(note))
        add(MatrixTextLine("${MatrixVoiceNotesText.whenLine(note)} - $state", MatrixTone.DIM))
        if (note.interrupted) add(MatrixTextLine(INTERRUPTED, MatrixTone.UNVERIFIED))
        if (note.transcript == null && note.transcriptionFailureReason != null) {
            add(MatrixTextLine("Transcription failed: ${note.transcriptionFailureReason}", MatrixTone.UNVERIFIED))
        }
        if (note.audioPath == null) add(MatrixTextLine(NO_AUDIO, MatrixTone.DIM))
    }

    /** The summary and the transcript, each labelled as AI-generated, or said not to exist yet. */
    fun bodyLines(note: VoiceNote): List<MatrixTextLine> = buildList {
        add(MatrixTextLine("Summary", MatrixTone.HEADING))
        if (note.summary != null) {
            add(MatrixTextLine(SUMMARY_LABEL, MatrixTone.DIM))
            add(MatrixTextLine(note.summary))
        } else {
            add(MatrixTextLine(SUMMARY_PENDING, MatrixTone.DIM))
        }
        add(MatrixTextLine("Transcript", MatrixTone.HEADING))
        if (note.transcript != null) {
            add(MatrixTextLine(TRANSCRIPT_LABEL, MatrixTone.DIM))
            add(MatrixTextLine(note.transcript))
        } else {
            add(MatrixTextLine("Not transcribed yet.", MatrixTone.DIM))
        }
    }

    /** True when a Retry row should show: the last attempt failed and there is no transcript. */
    fun canRetry(note: VoiceNote): Boolean = note.transcript == null && note.transcriptionFailureReason != null
}

package com.kevin.legion.ui.voicenotes

import com.kevin.legion.data.local.VoiceNote

/**
 * The five states a recording can be in, read straight (`ui/METERS pane and this screen's own
 * list both need it): "recorded / transcribing / failed / ready / interrupted", per the
 * recordings-UI ticket - "these are different sentences and must read differently". Pulled out of
 * [VoiceNoteRow]'s own body (which previously only ever branched on `summary != null` vs `null`
 * plus a separate, unconditional `interrupted` line) so the mapping is one pure function, testable
 * without standing up Compose, and so the METERS pane's own recordings row can read the identical
 * state a list row would show for the same note rather than reimplementing the branching.
 *
 * **[FAILED] added (`.scratch` follow-up to CLAUDE.md §7's outcome-verb rule) because a row whose
 * transcription attempt genuinely failed used to fall into the SAME branch as one still in
 * progress** - both simply had a null [VoiceNote.transcript] - so a permanent failure read as
 * "Transcribing" forever, a spinner making exactly the claim §7 forbids: that work is still
 * underway when it is over and lost. Reading [VoiceNote.transcriptionFailureReason] is what tells
 * the two apart now.
 *
 * **Precedence, in order:** [INTERRUPTED] first - a note flagged interrupted may still pick up a
 * transcript later (mid-recording preemption does not stop the audio already captured from being
 * sent to [com.kevin.legion.ai.VoiceNoteAgent]), but the interruption is the fact a driver most
 * needs told, so it is never quietly demoted to "ready" once a summary shows up. [RECORDED] is
 * next - [VoiceNote.endedAt] null means the recording genuinely has not been stopped yet (this
 * table's own doc comment: a crashed, never-observed stop is swept to [INTERRUPTED] on the next
 * app start before anything ever reads it, so an [endedAt]-null row seen here is a recording
 * still actually in progress). [FAILED] is next - a stored [VoiceNote.transcriptionFailureReason]
 * with no transcript yet means the last attempt genuinely failed (or was abandoned mid-call by a
 * process death, swept to a failure reason by
 * [com.kevin.legion.voice.VoiceNoteController.reconcileAfterProcessDeath]), and this is checked
 * BEFORE [TRANSCRIBING] specifically so a failed row never reads as still in progress.
 * [TRANSCRIBING] covers the ordinary wait after a clean stop with no failure on record, before
 * [VoiceNoteController.transcribeAndPersist]'s background call has written a transcript back onto
 * the row. [READY] is everything else - a transcript is present, so [VoiceNote.summary] is too
 * (the class doc's own nullability contract: summary is never non-null while transcript is null).
 */
enum class VoiceNoteRowState { RECORDED, TRANSCRIBING, FAILED, READY, INTERRUPTED }

/** The pure mapping described on [VoiceNoteRowState] itself. */
fun voiceNoteRowState(note: VoiceNote): VoiceNoteRowState = when {
    note.interrupted -> VoiceNoteRowState.INTERRUPTED
    note.endedAt == null -> VoiceNoteRowState.RECORDED
    note.transcript != null -> VoiceNoteRowState.READY
    note.transcriptionFailureReason != null -> VoiceNoteRowState.FAILED
    else -> VoiceNoteRowState.TRANSCRIBING
}

/** The word a list row or the METERS pane shows for [VoiceNoteRowState] - one vocabulary, so a
 * driver never has to learn two different words for the same fact depending on which screen they
 * are looking at. */
fun voiceNoteRowStateLabel(state: VoiceNoteRowState): String = when (state) {
    VoiceNoteRowState.RECORDED -> "Recording"
    VoiceNoteRowState.TRANSCRIBING -> "Transcribing"
    VoiceNoteRowState.FAILED -> "Transcription failed"
    VoiceNoteRowState.READY -> "Ready"
    VoiceNoteRowState.INTERRUPTED -> "Interrupted"
}

/**
 * `m:ss`, floored at zero seconds - the one duration format this screen and the METERS pane both
 * need: a live elapsed clock while a recording is running, and a finished recording's length once
 * it has an [VoiceNote.endedAt]. [totalMs] is never negative in the finished case ([endedAt] is
 * always taken after [startedAt] - [VoiceNoteRecorder] sets it at the moment the platform recorder
 * genuinely stops) but a `coerceAtLeast(0)` guards the live-elapsed caller too, whose own clock
 * read could in principle race a fresh [recordStartedAt] by a few milliseconds.
 */
fun formatMmSs(totalMs: Long): String {
    val totalSeconds = totalMs.coerceAtLeast(0) / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}

/**
 * A finished recording's own length, or "in progress" for the rare case a list is rendered while
 * [VoiceNote.endedAt] is still null - the same "still recording" fact [voiceNoteRowState] reads as
 * [VoiceNoteRowState.RECORDED], worded here for the duration column specifically rather than typed
 * as a state.
 */
fun formatVoiceNoteDuration(startedAt: Long, endedAt: Long?): String {
    if (endedAt == null) return "in progress"
    return formatMmSs(endedAt - startedAt)
}

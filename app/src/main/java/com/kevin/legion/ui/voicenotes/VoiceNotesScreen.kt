package com.kevin.legion.ui.voicenotes

import android.media.MediaPlayer
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kevin.legion.data.local.VoiceNote
import com.kevin.legion.data.local.VoiceNoteKind
import com.kevin.legion.ui.common.DeckButton
import com.kevin.legion.ui.common.DeckPane
import com.kevin.legion.ui.common.DeckScreenHeader
import com.kevin.legion.ui.common.SectionHeader
import com.kevin.legion.ui.theme.LegionType
import com.kevin.legion.ui.theme.LocalLegionSemantics
import com.kevin.legion.ui.theme.soft.AreaAccent
import com.kevin.legion.ui.theme.soft.LocalSoftActive
import com.kevin.legion.ui.theme.soft.SoftColors
import com.kevin.legion.ui.theme.soft.SoftTheme
import com.kevin.legion.util.clockTime
import com.kevin.legion.util.shortDate
import com.kevin.legion.voice.VoiceNoteController
import com.kevin.legion.voice.VoiceNoteRecordingState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * ADR 0035's hands path for the four `start_voice_note`/`stop_voice_note`/`read_voice_note`/
 * `list_voice_notes` voice tools (ticket 04, `.scratch/voice-notes/issues/04-voice-tools-and-the-hands-path.md`).
 * **Every write here calls [VoiceNoteController] - the SAME controller the voice tools dispatch
 * to** (see that object's own class doc for why there is exactly one). This screen does not
 * reimplement recording, transcription or the delete cascade; it only holds the record button,
 * the list, and the detail/rename/delete affordances a voice tool has no equivalent for.
 *
 * **List-then-detail as internal Compose state**, not two nav-graph destinations - same convention
 * [com.kevin.legion.ui.NotesScreen]'s own doc comment and
 * [com.kevin.legion.ui.companions.PlaybookScreen]'s list-to-editor drill-down already establish:
 * nothing below the top level needs a deep link of its own.
 *
 * **Every derived line says so in words, on both the list row and the detail** (ticket 04's own
 * load-bearing rule): [VoiceNote.summary] is model-generated from the transcript, never a verbatim
 * account, and [VoiceNoteRow]/[VoiceNoteDetail] both label it "AI-generated summary" rather than
 * relying on layout or colour alone. An interrupted recording says so on the LIST ROW too, not only
 * once you open it - a driver deciding which recording to trust should not have to open every one
 * to find out.
 *
 * **Soft Material (ADR 0051, soft-misc):** both screens here render inside [SoftTheme] with a
 * RECORDINGS-chip header, rounded cards, sentence-case copy and a record pill drawn with
 * [SoftColors.recordDot] / [SoftColors.recordingContainer]. Presentation only - every state word,
 * the AI-generated labels and the interrupted/failed lines are the same sentences as before and
 * stay always-visible text, never colour alone and never behind a HelpRow.
 */
@Composable
fun VoiceNotesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var loading by remember { mutableStateOf(true) }
    var notes by remember { mutableStateOf(emptyList<VoiceNote>()) }
    var reloadNonce by remember { mutableStateOf(0) }
    var selectedId by remember { mutableStateOf<Long?>(null) }
    // Observable, not remembered - see RecordControlRow's own doc comment for why this is a
    // collected flow rather than a local boolean this screen only updates when IT starts/stops.
    val recordingState by VoiceNoteController.recordingState(context).collectAsStateWithLifecycle()
    var startRefusal by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(reloadNonce) {
        notes = VoiceNoteController.listNotes(context)
        loading = false
    }

    val selectedNote = notes.firstOrNull { it.id == selectedId }
    if (selectedNote != null) {
        VoiceNoteDetailScreen(
            note = selectedNote,
            onBack = { selectedId = null },
            onRenamed = { reloadNonce++ },
            onDeleted = { selectedId = null; reloadNonce++ },
            onRetried = { reloadNonce++ },
        )
        return
    }

    SoftTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = SoftColors.ground) {
            Column(Modifier.fillMaxSize()) {
                DeckScreenHeader(title = "Voice notes", onBack = onBack, accent = AreaAccent.RECORDINGS)

                RecordControlRow(
                    state = recordingState,
                    onStart = {
                        scope.launch {
                            when (val started = VoiceNoteController.start(context, VoiceNoteKind.SOLO)) {
                                is com.kevin.legion.voice.VoiceNoteStartResult.Started -> {
                                    startRefusal = null
                                }
                                is com.kevin.legion.voice.VoiceNoteStartResult.Refused -> {
                                    startRefusal = started.reason
                                }
                            }
                        }
                    },
                    onStop = {
                        scope.launch {
                            // Same outcome-verb posture as the stop_voice_note tool (ticket 04): this
                            // never claims the note is ready, only that it saved and is transcribing -
                            // see the toast-equivalent text below.
                            VoiceNoteController.stop(context)
                            reloadNonce++
                        }
                    },
                )
                startRefusal?.let { reason ->
                    Text(
                        reason,
                        style = LegionType.stamp,
                        color = LocalLegionSemantics.current.estimated,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                    )
                }
                if (recordingState == VoiceNoteRecordingState.Idle) {
                    // The stop button already reads before its own outcome verb - this line is what a
                    // driver sees right after tapping stop, so it carries the same "saved and being
                    // transcribed, never ready" wording the voice tool's own result string does.
                    Text(
                        "A stopped recording is saved and transcribed in the background - it will show " +
                            "a summary here once that finishes, not immediately.",
                        style = LegionType.stamp,
                        color = LocalLegionSemantics.current.faint,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                    )
                }

                if (loading) {
                    Text("Loading...", style = LegionType.stamp, color = LocalLegionSemantics.current.ghost,
                        modifier = Modifier.padding(20.dp))
                } else {
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 0.dp, bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        item(key = "header") { SectionHeader("Recordings", notes.size.toString()) }
                        if (notes.isEmpty()) {
                            item(key = "empty") {
                                Text(
                                    "No voice notes yet.",
                                    style = LegionType.stamp,
                                    color = LocalLegionSemantics.current.ghost,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 10.dp),
                                )
                            }
                        } else {
                            items(notes, key = { it.id }) { note ->
                                VoiceNoteRow(note = note, onClick = { selectedId = note.id })
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Not `private` - `ui/MetersScreen.kt`'s own RECORDINGS pane (the recordings-UI ticket's "a RECORD
 * control in the pane, so starting is one tap from a home tab rather than a navigation") reuses
 * this exact composable rather than reimplementing the elapsed-clock/refusal/button wiring a second
 * time. Both call sites drive it off [VoiceNoteController.start]/[VoiceNoteController.stop]
 * themselves - this stays presentation-only, no controller reference of its own.
 *
 * **Takes [state] straight from [VoiceNoteController.recordingState] - never a per-screen local
 * boolean.** The recordings-UI follow-up ticket's own defect report: a screen that only knows about
 * a recording IT started reports idle for one it did not, which is worse than no recorder at all
 * once there are two surfaces that can start one. Both call sites collect the SAME
 * [com.kevin.legion.voice.VoiceNoteRecordingState] flow, so this row shows the truth regardless of
 * which surface tapped RECORD. The elapsed clock is derived from
 * [com.kevin.legion.voice.VoiceNoteRecordingState.Recording.startedAt] - the recording's own real
 * start timestamp, the same one written to [com.kevin.legion.data.local.VoiceNote.startedAt] - so
 * navigating away and back does not restart it at zero.
 */
@Composable
fun RecordControlRow(
    state: VoiceNoteRecordingState,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    val startedAt = (state as? VoiceNoteRecordingState.Recording)?.startedAt
    var elapsedMs by remember(startedAt) { mutableStateOf(startedAt?.let { System.currentTimeMillis() - it } ?: 0L) }
    LaunchedEffect(startedAt) {
        while (startedAt != null) {
            elapsedMs = System.currentTimeMillis() - startedAt
            delay(500)
        }
    }
    val soft = LocalSoftActive.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = if (soft) 16.dp else 12.dp, vertical = 8.dp)
            .let { if (soft) it.background(SoftColors.card, MaterialTheme.shapes.medium).padding(horizontal = 14.dp, vertical = 10.dp) else it },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (startedAt != null) {
            // The live recording shows as words ("Recording - 0:12") with the red dot beside it; the
            // dot is reinforcement, the sentence is the state.
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (soft) {
                    Box(Modifier.padding(end = 8.dp).size(10.dp).background(SoftColors.recordDot, CircleShape))
                }
                Text(
                    "Recording - ${formatMmSs(elapsedMs)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = LocalLegionSemantics.current.debit,
                )
            }
            if (soft) {
                RecordPill(label = "Stop", filled = true, onClick = onStop)
            } else {
                DeckButton("Stop", onClick = onStop)
            }
        } else {
            Text("Not recording", style = LegionType.stamp, color = LocalLegionSemantics.current.faint)
            if (soft) {
                RecordPill(label = "Record", filled = false, onClick = onStart)
            } else {
                DeckButton("Record", onClick = onStart)
            }
        }
    }
}

/**
 * The soft record control: a pill whose dot is [SoftColors.recordDot]. Idle it is the quiet
 * `cardHigh` pill with the dot as its only colour; while recording it fills
 * [SoftColors.recordingContainer], so the stop affordance reads as the live one. The label is
 * always words ("Record" / "Stop"), the dot never carries the state alone.
 */
@Composable
private fun RecordPill(label: String, filled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .background(if (filled) SoftColors.recordingContainer else SoftColors.cardHigh, RoundedCornerShape(percent = 50))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.padding(end = 8.dp).size(10.dp).background(SoftColors.recordDot, CircleShape))
        Text(label, style = MaterialTheme.typography.labelLarge, color = SoftColors.text)
    }
}

/**
 * One recording in the list. **Renders the derived-summary label and the interrupted state in
 * words, unconditionally** - ticket 04: "An interrupted recording says so on the list row, not
 * only in the detail." Never a HelpRow, never colour-only (CLAUDE.md §7's trust-disclosure rule):
 * both facts are plain [Text] lines, always visible.
 */
@Composable
fun VoiceNoteRow(note: VoiceNote, onClick: () -> Unit) {
    val sem = LocalLegionSemantics.current
    val rowState = voiceNoteRowState(note)
    val soft = LocalSoftActive.current
    Column(
        Modifier
            .fillMaxWidth()
            .let { if (soft) it.background(SoftColors.card, MaterialTheme.shapes.medium) else it }
            .clickable(onClick = onClick)
            .padding(horizontal = if (soft) 14.dp else 12.dp, vertical = if (soft) 12.dp else 10.dp),
    ) {
        Text(
            note.title ?: "Untitled recording",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            val kindWord = if (note.kind == VoiceNoteKind.MEETING) "Meeting" else "Solo"
            Text(
                "$kindWord - ${shortDate(note.startedAt)} ${clockTime(note.startedAt)} - " +
                    formatVoiceNoteDuration(note.startedAt, note.endedAt),
                style = LegionType.stamp, color = sem.faint,
            )
        }
        // The state word (recorded/transcribing/ready/interrupted) reads as its own line, colour
        // matched to how much trust the row deserves - never folded into the summary text below,
        // which is worded to be readable on its own even when [note.summary] is still null.
        val stateColor = when (rowState) {
            VoiceNoteRowState.INTERRUPTED -> sem.estimated
            VoiceNoteRowState.TRANSCRIBING -> sem.ghost
            VoiceNoteRowState.FAILED -> sem.estimated
            VoiceNoteRowState.RECORDED -> sem.debit
            VoiceNoteRowState.READY -> sem.faint
        }
        Text(voiceNoteRowStateLabel(rowState), style = LegionType.stamp, color = stateColor)
        if (note.summary != null) {
            Text(
                "AI-generated summary: ${note.summary}",
                style = LegionType.stamp,
                color = sem.faint,
                maxLines = 2,
            )
        } else if (note.transcriptionFailureReason != null) {
            // Said in words on the list row too, not only once opened - same "a driver deciding
            // which recording to trust should not have to open every one" posture the interrupted
            // line below already follows. Open the recording to retry.
            Text(
                note.transcriptionFailureReason,
                style = LegionType.stamp,
                color = sem.estimated,
                maxLines = 2,
            )
        } else {
            Text("Not transcribed yet", style = LegionType.stamp, color = sem.ghost)
        }
        // Load-bearing per the list-row rule above: never folded behind the summary line's own
        // truncation, always its own visible line - kept even though the state word above already
        // says "Interrupted", because this is the fuller sentence a driver reads to know what it
        // means, matching [VoiceNoteDetail]'s own longer sentence for the same fact.
        if (note.interrupted) {
            Text(
                "Interrupted - this recording may be incomplete",
                style = LegionType.stamp,
                color = sem.estimated,
            )
        }
    }
}

/**
 * The line to show after a rename, or null when there is nothing to say because it simply worked.
 *
 * Pure and file-visible rather than inline in [VoiceNoteDetailScreen], for two reasons: that
 * composable already sits at detekt's cyclomatic-complexity ceiling, and the WORDING is the
 * deliverable here - a screen that renders the server's refusal is only useful if the refusal
 * actually reaches this function, and that is checkable without a Compose harness.
 *
 * [VoiceNoteController.RenameResult.Refused] and
 * [VoiceNoteController.RenameResult.SavedOnThisPhoneOnly] both carry a sentence written to be read
 * by a person - the engine's own words in the first case - and neither is reworded here.
 */
internal fun renameNotice(result: VoiceNoteController.RenameResult): String? = when (result) {
    VoiceNoteController.RenameResult.Renamed -> null
    VoiceNoteController.RenameResult.NotFound ->
        "That recording is no longer here, so nothing was renamed."
    is VoiceNoteController.RenameResult.Refused -> result.message
    is VoiceNoteController.RenameResult.SavedOnThisPhoneOnly -> result.message
}

/**
 * Detail: full transcript, summary, playback, rename, delete. Every write here - rename, delete -
 * calls [VoiceNoteController] directly, same call the voice tools make.
 *
 * Not `private` - `ui/CalendarScreen.kt`'s RECORDED section (the calendar-day-view follow-up
 * ticket, "tapping one opens the same detail view the Recordings screen opens - one detail
 * implementation, not a second copy") opens this exact composable rather than reimplementing the
 * transcript/summary/playback/rename/delete surface a second time.
 */
@Composable
fun VoiceNoteDetailScreen(
    note: VoiceNote,
    onBack: () -> Unit,
    onRenamed: () -> Unit,
    onDeleted: () -> Unit,
    onRetried: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showRenameDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var deleteError by remember { mutableStateOf<String?>(null) }

    /** What the last rename actually did, when that was anything other than a plain success -
     * the server's own sentence on a refusal, or "renamed here, not on the server" when the engine
     * could not be reached. `VoiceNoteController.rename` used to return a bare Boolean this screen
     * discarded, so a rename the server rejected closed the dialog with nothing said. Rendered
     * beside [deleteError], which already had this shape. */
    var renameError by remember { mutableStateOf<String?>(null) }
    var playing by remember { mutableStateOf(false) }
    // Local, immediate feedback that the tap registered - VoiceNoteController.retryTranscription
    // is fire-and-forget (same shape stop() itself uses), so this reads "Retrying..." the instant
    // it is tapped rather than staying on the stale "Transcription failed" text until the next
    // reload happens to land after the background call finishes.
    var retrying by remember(note.id) { mutableStateOf(false) }

    val mediaPlayer = remember { MediaPlayer() }
    DisposableEffect(Unit) {
        onDispose { mediaPlayer.release() }
    }

    if (showRenameDialog) {
        RenameVoiceNoteDialog(
            currentTitle = note.title ?: "",
            onDismiss = { showRenameDialog = false },
            onRename = { newTitle ->
                scope.launch {
                    // The result is surfaced rather than discarded, the same way `delete` already
                    // surfaces its own Failed branch just below. `rename` used to return a bare
                    // Boolean that was `true` even when the push failed; it is now server-first on
                    // the engine and reports the server's own words on a refusal. The branch-to-
                    // sentence mapping lives in the pure [renameNotice] below rather than inline,
                    // both because a composable this size is already at detekt's complexity ceiling
                    // and because the WORDING is worth being able to test without a screen.
                    renameError = renameNotice(VoiceNoteController.rename(context, note.id, newTitle))
                    showRenameDialog = false
                    onRenamed()
                }
            },
        )
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete this recording?") },
            // Ticket 04: "Delete confirms in words that audio and transcript go with it" - ADR
            // 0041's cascade, stated plainly rather than assumed understood.
            text = { Text("This deletes the audio, the transcript, and the summary together. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        when (val result = VoiceNoteController.delete(context, note.id)) {
                            is VoiceNoteController.DeleteResult.Deleted -> { showDeleteDialog = false; onDeleted() }
                            VoiceNoteController.DeleteResult.NotFound -> { showDeleteDialog = false; onDeleted() }
                            is VoiceNoteController.DeleteResult.Failed -> {
                                deleteError = result.reason
                                showDeleteDialog = false
                            }
                        }
                    }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") } },
        )
    }

    // Nested SoftTheme is harmless when a caller (CalendarScreen's RECORDED section) already
    // provides one, and is what makes this screen soft when it is opened from here.
    SoftTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = SoftColors.ground) {
            Column(Modifier.fillMaxSize()) {
                DeckScreenHeader(title = note.title ?: "Untitled recording", onBack = onBack, accent = AreaAccent.RECORDINGS)
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    DeckButton("Rename", onClick = { showRenameDialog = true })
                    DeckButton("Delete", onClick = { showDeleteDialog = true }, destructive = true)
                }
                deleteError?.let {
                    Text(it, style = LegionType.stamp, color = LocalLegionSemantics.current.estimated,
                        modifier = Modifier.padding(horizontal = 20.dp))
                }
                renameError?.let {
                    Text(it, style = LegionType.stamp, color = LocalLegionSemantics.current.estimated,
                        modifier = Modifier.padding(horizontal = 20.dp))
                }
                VoiceNoteDetail(
                    note = note,
                    playing = playing,
                    retrying = retrying,
                    onRetry = {
                        retrying = true
                        scope.launch {
                            VoiceNoteController.retryTranscription(context, note.id)
                            onRetried()
                        }
                    },
                    onTogglePlayback = {
                        val path = note.audioPath
                        if (path == null) return@VoiceNoteDetail
                        if (playing) {
                            mediaPlayer.pause()
                            playing = false
                        } else {
                            try {
                                mediaPlayer.reset()
                                mediaPlayer.setDataSource(path)
                                mediaPlayer.prepare()
                                mediaPlayer.setOnCompletionListener { playing = false }
                                mediaPlayer.start()
                                playing = true
                            } catch (e: Exception) {
                                playing = false
                            }
                        }
                    },
                )
            }
        }
    }
}

/**
 * The transcript/summary body - a single row-shaped function so
 * [com.kevin.legion.ui.voicenotes.VoiceNoteDetailContentTest] can pin the derived-summary and
 * interrupted wording without standing up the whole screen (delete dialog, MediaPlayer, nav state).
 */
@Composable
fun VoiceNoteDetail(
    note: VoiceNote,
    playing: Boolean,
    retrying: Boolean = false,
    onRetry: () -> Unit = {},
    onTogglePlayback: () -> Unit,
) {
    val sem = LocalLegionSemantics.current
    // Scrolls: a long transcript used to run off the bottom of a fixed-height column (found while
    // restyling; a presentation fix, no data path touched).
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
        val kindWord = if (note.kind == VoiceNoteKind.MEETING) "Meeting" else "Solo"
        Text(
            "$kindWord recording - ${shortDate(note.startedAt)} ${clockTime(note.startedAt)} - " +
                formatVoiceNoteDuration(note.startedAt, note.endedAt),
            style = LegionType.stamp, color = sem.faint,
        )

        if (note.interrupted) {
            Text(
                "This recording was interrupted before it finished and may be incomplete.",
                style = MaterialTheme.typography.bodyMedium,
                color = sem.estimated,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        // FAILED per voiceNoteRowState - said in words (CLAUDE.md §7's outcome-verb rule) with a
        // Retry the user can act on. The audio is unchanged on disk (VoiceNoteAgent never touches
        // the file on a failure), so a retry is always genuinely possible here.
        if (note.transcript == null && note.transcriptionFailureReason != null) {
            Text(
                "Transcription failed: ${note.transcriptionFailureReason}",
                style = MaterialTheme.typography.bodyMedium,
                color = sem.estimated,
                modifier = Modifier.padding(top = 6.dp),
            )
            DeckButton(
                if (retrying) "Retrying..." else "Retry",
                onClick = onRetry,
                enabled = !retrying,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        if (note.audioPath != null) {
            DeckButton(
                if (playing) "Pause" else "Play audio",
                onClick = onTogglePlayback,
                modifier = Modifier.padding(top = 10.dp),
            )
        } else {
            Text("Audio is no longer available for this recording.", style = LegionType.stamp, color = sem.ghost,
                modifier = Modifier.padding(top = 10.dp))
        }

        Spacer(Modifier.height(10.dp))
        DeckPane(header = "Summary") {
            if (note.summary != null) {
                // Same wording as the list row, deliberately - one vocabulary for this claim across
                // the whole screen (ticket 04's own rule: never by colour or a glyph alone).
                Text("AI-generated summary - not a verbatim account:", style = LegionType.stamp, color = sem.faint)
                Text(note.summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = 4.dp))
            } else {
                Text("Not transcribed yet - the audio is saved and this will fill in once transcription finishes.",
                    style = LegionType.stamp, color = sem.ghost)
            }
        }

        Spacer(Modifier.height(10.dp))
        DeckPane(header = "Transcript") {
            if (note.transcript != null) {
                Text("AI-generated transcript - as close to verbatim as the model could make out:",
                    style = LegionType.stamp, color = sem.faint)
                Text(note.transcript, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = 4.dp))
            } else {
                Text("Not transcribed yet.", style = LegionType.stamp, color = sem.ghost)
            }
        }
    }
}

@Composable
private fun RenameVoiceNoteDialog(currentTitle: String, onDismiss: () -> Unit, onRename: (String) -> Unit) {
    var title by remember { mutableStateOf(currentTitle) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename recording") },
        text = {
            OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Title") })
        },
        confirmButton = {
            TextButton(enabled = title.isNotBlank(), onClick = { onRename(title.trim()) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

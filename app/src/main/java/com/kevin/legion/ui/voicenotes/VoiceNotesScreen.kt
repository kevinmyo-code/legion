package com.kevin.legion.ui.voicenotes

import android.media.MediaPlayer
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kevin.legion.data.local.VoiceNote
import com.kevin.legion.data.local.VoiceNoteKind
import com.kevin.legion.ui.common.DeckScreenHeader
import com.kevin.legion.ui.common.Hairline
import com.kevin.legion.ui.common.SectionHeader
import com.kevin.legion.ui.theme.LegionType
import com.kevin.legion.ui.theme.LocalLegionSemantics
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
        )
        return
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            DeckScreenHeader(title = "Voice notes", onBack = onBack)

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
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
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
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }

            if (loading) {
                Text("Loading...", style = LegionType.stamp, color = LocalLegionSemantics.current.ghost,
                    modifier = Modifier.padding(12.dp))
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    item(key = "header") { SectionHeader("RECORDINGS", notes.size.toString()) }
                    if (notes.isEmpty()) {
                        item(key = "empty") {
                            Text(
                                "No voice notes yet.",
                                style = LegionType.stamp,
                                color = LocalLegionSemantics.current.ghost,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            )
                        }
                    } else {
                        items(notes, key = { it.id }) { note ->
                            Column {
                                VoiceNoteRow(note = note, onClick = { selectedId = note.id })
                                Hairline()
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
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (startedAt != null) {
            Text(
                "Recording - ${formatMmSs(elapsedMs)}",
                style = MaterialTheme.typography.bodyMedium,
                color = LocalLegionSemantics.current.debit,
            )
            Button(onClick = onStop) { Text("STOP") }
        } else {
            Text("Not recording", style = LegionType.stamp, color = LocalLegionSemantics.current.faint)
            Button(onClick = onStart) { Text("RECORD") }
        }
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
    Column(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
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
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showRenameDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var deleteError by remember { mutableStateOf<String?>(null) }
    var playing by remember { mutableStateOf(false) }

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
                    VoiceNoteController.rename(context, note.id, newTitle)
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
                }) { Text("DELETE") }
            },
            dismissButton = { TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") } },
        )
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            DeckScreenHeader(title = note.title ?: "Untitled recording", onBack = onBack)
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { showRenameDialog = true }) { Text("RENAME") }
                TextButton(onClick = { showDeleteDialog = true }) {
                    Text("DELETE", color = LocalLegionSemantics.current.estimated)
                }
            }
            deleteError?.let {
                Text(it, style = LegionType.stamp, color = LocalLegionSemantics.current.estimated,
                    modifier = Modifier.padding(horizontal = 12.dp))
            }
            VoiceNoteDetail(
                note = note,
                playing = playing,
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

/**
 * The transcript/summary body - a single row-shaped function so
 * [com.kevin.legion.ui.voicenotes.VoiceNoteDetailContentTest] can pin the derived-summary and
 * interrupted wording without standing up the whole screen (delete dialog, MediaPlayer, nav state).
 */
@Composable
fun VoiceNoteDetail(note: VoiceNote, playing: Boolean, onTogglePlayback: () -> Unit) {
    val sem = LocalLegionSemantics.current
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp)) {
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

        if (note.audioPath != null) {
            Button(onClick = onTogglePlayback, modifier = Modifier.padding(top = 10.dp)) {
                Text(if (playing) "PAUSE" else "PLAY AUDIO")
            }
        } else {
            Text("Audio is no longer available for this recording.", style = LegionType.stamp, color = sem.ghost,
                modifier = Modifier.padding(top = 10.dp))
        }

        SectionHeader("SUMMARY")
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

        SectionHeader("TRANSCRIPT")
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

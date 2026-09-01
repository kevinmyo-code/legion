package com.kevin.legion.voice

import android.content.Context
import android.util.Log
import com.kevin.legion.ai.VoiceNoteAgent
import com.kevin.legion.backend.SupabaseClientProvider
import com.kevin.legion.backend.SupabaseVoiceNotesBackend
import com.kevin.legion.backend.VoiceNoteFields
import com.kevin.legion.backend.VoiceNotesBackend
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.VoiceNote
import com.kevin.legion.data.local.VoiceNoteDao
import com.kevin.legion.data.local.VoiceNoteStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * **ADR 0035's "one controller, not two implementations", written down for voice notes** (ticket
 * 04, `.scratch/voice-notes/issues/04-voice-tools-and-the-hands-path.md`: "ADR 0035 does not mean
 * two implementations - both paths call this one controller"). `service/LiveToolbox.kt`'s
 * `start_voice_note`/`stop_voice_note`/`read_voice_note`/`list_voice_notes` and
 * `ui/voicenotes/VoiceNotesScreen.kt`'s record button, list, rename and delete all call the
 * functions below and nothing else - neither surface talks to [VoiceNoteRecorder], [VoiceNoteStore]
 * or [VoiceNoteAgent] directly, so there is exactly one place either path's behaviour can be
 * changed, and one place a future third caller (a widget, a shortcut) would plug into too.
 *
 * **Owns the one [VoiceNoteRecorder] instance for the process.** [VoiceNoteRecorder]'s own doc
 * comment is explicit that it is "not a singleton... the caller owns the instance and its
 * lifetime" - this object is that caller, lazily building one [VoiceNoteRecorder] against the
 * application [Context] the first time anything here needs it, and handing every later call
 * (voice or hands, from whichever screen or tool dispatch happens to run first) the SAME instance.
 * Two different [VoiceNoteRecorder]s would each think they own the microphone independently and
 * would not even agree with each other on whether a recording is currently running, let alone with
 * [MicArbiter][com.kevin.legion.service.MicArbiter]'s own single source of truth.
 *
 * **The stop-time transcribe is fire-and-forget from the caller's point of view, by design** (the
 * ticket's own outcome-verb rule: "says the recording is saved and being transcribed, never that
 * the note is ready"). [stop] returns the instant [VoiceNoteRecorder.stop] does - before
 * [VoiceNoteAgent.transcribeAndSummarize] has even started - and hands back only
 * [StopOutcome.Saved]/[StopOutcome.NothingRecording]. The actual transcription runs on
 * [controllerScope], independent of whatever turn or screen called [stop], so a live voice turn
 * ending (or a screen navigating away) can never cancel or block on it.
 */
object VoiceNoteController {
    private const val TAG = "VoiceNoteController"

    /** Test seam: settable from a unit test so a [VoiceNotesBackend] fake can be injected without
     * a real [SupabaseClientProvider] / network - same mechanism as
     * [com.kevin.legion.location.PlaceController.backendOverride]. Defaults to null, meaning
     * "resolve normally"; production code never sets this. */
    @Volatile
    internal var backendOverride: VoiceNotesBackend? = null

    /** Test seam: settable from a unit test so a fake [VoiceNoteRecorder] (a real
     * [AudioCaptureFactory] fake, per that class's own doc comment) can stand in for the
     * lazily-built production singleton below. Defaults to null, meaning "resolve normally". */
    @Volatile
    internal var recorderOverride: VoiceNoteRecorder? = null

    /** Backing field for the lazily-built production [VoiceNoteRecorder] singleton - see this
     * object's own class doc for why there must be exactly one per process. */
    @Volatile
    private var recorderInstance: VoiceNoteRecorder? = null
    private val recorderLock = Any()

    /** Own scope for the transcribe-after-stop work (see this object's class doc), independent of
     * any caller's lifecycle - [Dispatchers.IO] because [VoiceNoteAgent.transcribeAndSummarize]
     * reads a file off disk and makes a network call, neither of which belongs on a caller's own
     * dispatcher. [SupervisorJob] so one failed transcription can never cancel a sibling one still
     * in flight. */
    private val controllerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun dao(context: Context): VoiceNoteDao = CarDatabase.getDatabase(context).voiceNoteDao()

    private fun store(context: Context): VoiceNoteStore = VoiceNoteStore(dao(context))

    private fun recorder(context: Context): VoiceNoteRecorder {
        recorderOverride?.let { return it }
        recorderInstance?.let { return it }
        synchronized(recorderLock) {
            recorderInstance?.let { return it }
            val fresh = VoiceNoteRecorder(context.applicationContext, dao(context.applicationContext))
            recorderInstance = fresh
            return fresh
        }
    }

    /** Resolves the active backend, or null when Supabase is not configured - the signal every
     * sync-touching function below branches on. Never performs network I/O itself. */
    private fun backend(context: Context): VoiceNotesBackend? {
        backendOverride?.let { return it }
        val client = SupabaseClientProvider.get(context) ?: return null
        return SupabaseVoiceNotesBackend(client)
    }

    // -------------------------------------------------------------------- start / stop

    /** Starts a new recording. See [VoiceNoteRecorder.start] for the refusal cases (already
     * recording, mic held by a higher-ranked [com.kevin.legion.service.MicArbiter.Claimant]) - this
     * function adds nothing beyond routing to the one shared [VoiceNoteRecorder]. */
    suspend fun start(context: Context, kind: String, titleHint: String? = null): VoiceNoteStartResult =
        recorder(context).start(kind, titleHint)

    /** What [stop] hands back - deliberately NOT a note the caller can read a summary off of.
     * [Saved.noteId] is only ever a routing hint (e.g. so a screen can highlight the right row); it
     * is not a promise that [VoiceNote.summary]/[VoiceNote.transcript] exist yet. */
    sealed interface StopOutcome {
        data class Saved(val noteId: Long) : StopOutcome
        /** No recording was in progress to stop - see [VoiceNoteStopResult.NothingRecording]'s own
         * doc comment: a normal, expected outcome, never reported as a stop having happened. */
        data object NothingRecording : StopOutcome
    }

    /**
     * Stops the active recording and kicks off transcription in the background - **returns before
     * transcription has even started**, which is the whole point (ticket 04's outcome-verb rule).
     * Neither `start_voice_note`/`stop_voice_note` in `LiveToolbox.kt` nor
     * `ui/voicenotes/VoiceNotesScreen.kt` may phrase this as the note being "ready" - only that the
     * recording is saved and is being transcribed.
     */
    suspend fun stop(context: Context): StopOutcome {
        val appContext = context.applicationContext
        return when (val result = recorder(appContext).stop()) {
            VoiceNoteStopResult.NothingRecording -> StopOutcome.NothingRecording
            is VoiceNoteStopResult.Stopped -> {
                controllerScope.launch { transcribeAndPersist(appContext, result.noteId, result.audioPath) }
                StopOutcome.Saved(result.noteId)
            }
        }
    }

    /**
     * The background half of [stop]: runs [VoiceNoteAgent.transcribeAndSummarize] against the
     * audio [VoiceNoteRecorder] just finished writing, and on success writes the transcript/summary
     * onto the SAME row (never a new one - [VoiceNote.id] is preserved throughout, matching
     * [com.kevin.legion.notes.NotesController]'s own id-carry posture) before syncing it to the
     * backend if one is configured.
     *
     * **A failure here leaves the row exactly as [stop] left it** - [VoiceNote.summary]/
     * [VoiceNote.transcript] stay null, [VoiceNote.audioPath] stays untouched on disk
     * ([VoiceNoteAgent.transcribeAndSummarize]'s own doc comment: "never touches the file"), and
     * nothing about this is surfaced as an error to any live caller, because by the time this runs
     * the turn or screen that triggered [stop] is long gone. [get]/[listNotes]/[findByTitleOrLatest]
     * read whatever state the row is actually in and say so in words (no summary yet vs. a summary
     * present) - there is
     * no separate "processing failed" flag to set, and the audio being intact means a future retry
     * (not built by this ticket) could always be added without losing anything.
     *
     * `internal`, not `private`, so [VoiceNoteControllerTest] can call this directly and await it
     * synchronously - [stop]'s own `controllerScope.launch` makes the real production call
     * fire-and-forget, which a test cannot deterministically wait on without this seam.
     */
    internal suspend fun transcribeAndPersist(context: Context, noteId: Long, audioPath: String?) {
        if (audioPath == null) {
            Log.w(TAG, "transcribeAndPersist: note $noteId has no audio path - nothing to transcribe")
            return
        }
        when (val result = VoiceNoteAgent.transcribeAndSummarize(audioPath)) {
            is VoiceNoteAgent.Result.Failed -> {
                Log.w(TAG, "transcribeAndPersist: note $noteId failed: ${result.reason}")
            }
            is VoiceNoteAgent.Result.Success -> {
                val current = dao(context).getById(noteId) ?: run {
                    Log.w(TAG, "transcribeAndPersist: note $noteId no longer exists (deleted mid-transcribe)")
                    return
                }
                val updated = current.copy(
                    // A user-supplied titleHint (start's own argument) is never overwritten by the
                    // model's guess - only an untitled note picks up the model's title.
                    title = current.title ?: result.title,
                    summary = result.summary,
                    transcript = result.transcript,
                )
                dao(context).update(updated)
                syncToBackend(context, updated)
            }
        }
    }

    /** Pushes [note]'s current fields to the backend if one is configured, and stamps the returned
     * [com.kevin.legion.backend.RemoteVoiceNote.serverId] back onto the Room row on a genuine ACK -
     * same "write the replica only after the server confirms" posture as
     * [com.kevin.legion.location.PlaceController.tagPlace]. A failure here is logged, never thrown
     * and never surfaced to a live caller (this always runs off [controllerScope], after whichever
     * turn triggered it has already returned) - the row stays correct locally either way, and the
     * next mutation (a rename, a later successful transcribe) retries the same upsert. */
    internal suspend fun syncToBackend(context: Context, note: VoiceNote) {
        val backend = backend(context) ?: return
        val fields = VoiceNoteFields(
            startedAtMs = note.startedAt,
            endedAtMs = note.endedAt,
            title = note.title,
            summary = note.summary,
            transcript = note.transcript,
            kind = note.kind,
            interrupted = note.interrupted,
        )
        val remote = backend.upsert(note.serverId, fields).getOrElse {
            Log.w(TAG, "syncToBackend: upsert failed for note ${note.id}: ${it.message}")
            return
        }
        if (remote.serverId != note.serverId) {
            dao(context).update(note.copy(serverId = remote.serverId))
        }
    }

    /** Crash-recovery sweep (ticket 01's own gap, closed here): [VoiceNoteRecorder.reconcileAfterProcessDeath]
     * had nothing calling it. `service/AriaForegroundService.kt`'s `onCreate` is the one caller,
     * once, before anything reads a [VoiceNote] for display - routed through the same shared
     * [recorder] instance every other function on this object uses, so its in-memory state (there
     * is none active this early) and the sweep agree with each other. */
    suspend fun reconcileAfterProcessDeath(context: Context) {
        recorder(context).reconcileAfterProcessDeath()
    }

    // -------------------------------------------------------------------- read / list

    /** Every voice note, most recent first - the hands-path list and the voice `list_voice_notes`
     * query tool's shared read. [limit] guards the voice tool from reading out an unbounded list;
     * the screen itself may pass a larger one or none. */
    suspend fun listNotes(context: Context, limit: Int = 200): List<VoiceNote> =
        dao(context).getAll().take(limit)

    suspend fun get(context: Context, id: Long): VoiceNote? = dao(context).getById(id)

    /**
     * The `read_voice_note` tool's own lookup: a case-insensitive substring match against
     * [VoiceNote.title] when [titleQuery] is given and non-blank, else the single most recent note.
     * Returns null when a [titleQuery] was given and nothing matches - the caller (LiveToolbox) is
     * responsible for wording that as "I couldn't find a recording called that" rather than
     * silently falling back to the latest note, which would answer a different question than the
     * one asked.
     */
    suspend fun findByTitleOrLatest(context: Context, titleQuery: String?): VoiceNote? {
        val all = dao(context).getAll()
        val query = titleQuery?.trim()
        if (query.isNullOrBlank()) return all.firstOrNull()
        return all.firstOrNull { it.title?.contains(query, ignoreCase = true) == true }
    }

    // -------------------------------------------------------------------- rename / delete

    /** Renames a note by [id]. Returns false for an id with no row - never reported as a rename
     * having happened (§7's outcome-verb rule applied to a plain CRUD op, same posture as
     * [com.kevin.legion.location.PlaceController.forget]). */
    suspend fun rename(context: Context, id: Long, newTitle: String): Boolean {
        val note = dao(context).getById(id) ?: return false
        val updated = note.copy(title = newTitle)
        dao(context).update(updated)
        syncToBackend(context, updated)
        return true
    }

    /** What [delete] hands back - every branch worded so a caller can surface it directly, same
     * §7 posture [VoiceNoteStartResult.Refused]'s own doc comment describes. */
    sealed interface DeleteResult {
        data object Deleted : DeleteResult
        /** No such id - already deleted, or a stale reference. Never reported as a delete having
         * happened. */
        data object NotFound : DeleteResult
        data class Failed(val reason: String) : DeleteResult
    }

    /**
     * ADR 0041's delete cascade, run through the backend first when one is configured (same
     * "server first, then the local replica" order [com.kevin.legion.location.PlaceController.forgetPlace]
     * uses) and [VoiceNoteStore.delete] always: audio file and Room row - transcript and summary
     * live as columns on that row, so deleting it takes them with it, same shape
     * [VoiceNoteStore]'s own class doc describes. A backend failure stops here and leaves BOTH the
     * server row and the local one untouched - never a local delete that quietly diverges from a
     * server row still very much alive, which would silently break sync on the next pull.
     */
    suspend fun delete(context: Context, id: Long): DeleteResult {
        val note = dao(context).getById(id) ?: return DeleteResult.NotFound
        val backend = backend(context)
        if (backend != null && note.serverId != null) {
            val deletedRemotely = backend.softDelete(note.serverId).getOrElse {
                return DeleteResult.Failed(
                    "Couldn't delete that recording from the server - nothing was removed. ${it.message ?: ""}".trim()
                )
            }
            if (!deletedRemotely) return DeleteResult.NotFound
        }
        return if (store(context).delete(id)) DeleteResult.Deleted else DeleteResult.NotFound
    }
}

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
import kotlinx.coroutines.flow.StateFlow
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

    /** Test seam: settable from a unit test so `stop`/[retryTranscription]'s fire-and-forget
     * launches run on a scope the test's own `runTest` can actually wait on, instead of a real
     * background thread pool a test has no deterministic way to await. Same
     * [recorderOverride]/[backendOverride] shape. Defaults to null, meaning "use [controllerScope]
     * below"; production code never sets this. Found necessary 2026-09-04: adding the
     * attempt-started/failure-reason writes below (the FAILED-state ticket) doubled the number of
     * Room writes each background transcription performs, which was enough to turn an already-fragile
     * "nobody awaits this" gap into [VoiceNoteControllerTest] flaking on unrelated test methods -
     * `kotlinx.coroutines.test.UncaughtExceptionsBeforeTest` blaming whichever test happened to be
     * running when a PREVIOUS test's leaked write finally landed against a Robolectric shadow layer
     * already torn down for a different test method. */
    @Volatile
    internal var controllerScopeOverride: CoroutineScope? = null

    /** Own scope for the transcribe-after-stop work (see this object's class doc), independent of
     * any caller's lifecycle - [Dispatchers.IO] because [VoiceNoteAgent.transcribeAndSummarize]
     * reads a file off disk and makes a network call, neither of which belongs on a caller's own
     * dispatcher. [SupervisorJob] so one failed transcription can never cancel a sibling one still
     * in flight. */
    private val defaultControllerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val controllerScope: CoroutineScope get() = controllerScopeOverride ?: defaultControllerScope

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

    /**
     * The one live-recording truth both hands-path surfaces collect (recordings-UI follow-up
     * ticket: "recording state must be observable, not remembered per-screen"). Backed by the SAME
     * singleton [VoiceNoteRecorder] every other function on this object routes through - see
     * [recorder]'s own doc comment - so a recording started on METERS and observed from
     * `VoiceNotesScreen` (or the reverse) reads identically, and navigating away and back never
     * loses track of it. Not `suspend`: [recorder] only touches [Context]/[VoiceNoteDao]
     * construction, which is cheap and synchronous, so a composable can call this directly inside
     * `remember` without a coroutine.
     */
    fun recordingState(context: Context): StateFlow<VoiceNoteRecordingState> =
        recorder(context.applicationContext).recordingState

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
     * **A failure here leaves [VoiceNote.summary]/[VoiceNote.transcript] null and
     * [VoiceNote.audioPath] untouched on disk** ([VoiceNoteAgent.transcribeAndSummarize]'s own doc
     * comment: "never touches the file") - **but, unlike before, no longer leaves the row silently
     * indistinguishable from one still in progress.** [VoiceNote.transcriptionFailureReason] is
     * written in words (CLAUDE.md §7's outcome-verb rule, §4 rule 8's "store the reason, not just
     * the verdict"), and [voiceNoteRowState][com.kevin.legion.ui.voicenotes.voiceNoteRowState]
     * reads it into [com.kevin.legion.ui.voicenotes.VoiceNoteRowState.FAILED] rather than
     * `TRANSCRIBING`. [VoiceNote.transcriptionAttemptStartedAt] is stamped BEFORE the call and
     * cleared on every exit path (success or failure) - a value still sitting there on the NEXT app
     * start is exactly how [reconcileAfterProcessDeath] recognises an attempt this same function
     * never got to finish, because the process running it died first.
     *
     * `internal`, not `private`, so [VoiceNoteControllerTest] can call this directly and await it
     * synchronously - [stop]'s own `controllerScope.launch` makes the real production call
     * fire-and-forget, which a test cannot deterministically wait on without this seam.
     */
    internal suspend fun transcribeAndPersist(context: Context, noteId: Long, audioPath: String?) {
        if (audioPath == null) {
            Log.w(TAG, "transcribeAndPersist: note $noteId has no audio path - nothing to transcribe")
            // Safe no-op if the row is already gone - Room's generated UPDATE...WHERE id=:id simply
            // matches zero rows, no existence check needed first.
            markFailed(context, noteId, "No audio was saved for this recording, so it can't be transcribed.")
            return
        }
        if (dao(context).getById(noteId) == null) {
            Log.w(TAG, "transcribeAndPersist: note $noteId no longer exists (deleted before transcribe started)")
            return
        }
        // Narrow column-only writes throughout this function, deliberately never a read-`.copy()`-
        // write - see VoiceNoteDao.markTranscriptionAttemptStarted's own doc comment for the
        // lost-update race that shape has (a concurrent rename losing its own write to a stale
        // snapshot this function held from before its network call). Cleared on every exit below -
        // a stale non-null value on the next app start is read by reconcileAfterProcessDeath as
        // "this attempt never finished", per this function's own doc comment. Also clears any
        // PRIOR failure reason, so a retry that is currently in flight never reads as still-failed.
        dao(context).markTranscriptionAttemptStarted(noteId, now())
        when (val result = VoiceNoteAgent.transcribeAndSummarize(audioPath)) {
            is VoiceNoteAgent.Result.Failed -> {
                Log.w(TAG, "transcribeAndPersist: note $noteId failed: ${result.reason}")
                markFailed(context, noteId, result.reason)
            }
            is VoiceNoteAgent.Result.Success -> {
                dao(context).applyTranscriptionSuccess(noteId, result.title, result.summary, result.transcript)
                // Read AFTER the write (never the stale pre-write snapshot) so syncToBackend
                // upserts the row's genuinely-current state, title-preservation rule included.
                val updated = dao(context).getById(noteId) ?: run {
                    Log.w(TAG, "transcribeAndPersist: note $noteId no longer exists (deleted mid-transcribe)")
                    return
                }
                syncToBackend(context, updated)
            }
        }
    }

    /** Shared by [transcribeAndPersist]'s own failure branch and its no-audio-path guard - writes
     * [reason] onto the row and clears the in-flight marker via [VoiceNoteDao.markTranscriptionFailed]'s
     * narrow column-only update (see that function's own doc comment for why never a read-`.copy()`-
     * write). Room's generated `UPDATE ... WHERE id = :id` is itself a safe no-op against a row that
     * no longer exists (deleted mid-transcribe) - matches the "nothing left to roll back" posture
     * [syncToBackend]'s own doc comment describes for a backend failure. */
    private suspend fun markFailed(context: Context, noteId: Long, reason: String) {
        dao(context).markTranscriptionFailed(noteId, reason)
    }

    private fun now(): Long = System.currentTimeMillis()

    /**
     * The hands-path (and, per ADR 0035, any future voice-path) retry for a row
     * [voiceNoteRowState][com.kevin.legion.ui.voicenotes.voiceNoteRowState] reads as
     * [com.kevin.legion.ui.voicenotes.VoiceNoteRowState.FAILED] - "a failure the user cannot act
     * on is a dead end" per this ticket's own brief, and the audio [VoiceNoteAgent] never touches
     * on a failure is exactly what makes a retry always possible. Runs the SAME
     * [transcribeAndPersist] [stop] itself kicks off, on [controllerScope] so a screen navigating
     * away can never cancel it, same fire-and-forget shape as the original attempt.
     */
    sealed interface RetryResult {
        /** The retry was kicked off - does not itself mean it will succeed, same "Saved never
         * means ready" posture [StopOutcome.Saved] already uses for the original attempt. */
        data object Retrying : RetryResult
        /** No such id - never reported as a retry having started. */
        data object NotFound : RetryResult
        /** The row has no audio left to retry against (already surfaced via [markFailed]'s own
         * "No audio was saved" reason, so this is the read-time confirmation of that same fact). */
        data object NoAudio : RetryResult
    }

    suspend fun retryTranscription(context: Context, noteId: Long): RetryResult {
        val appContext = context.applicationContext
        val note = dao(appContext).getById(noteId) ?: return RetryResult.NotFound
        val audioPath = note.audioPath ?: return RetryResult.NoAudio
        controllerScope.launch { transcribeAndPersist(appContext, noteId, audioPath) }
        return RetryResult.Retrying
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
     * is none active this early) and the sweep agree with each other.
     *
     * **Also sweeps abandoned transcription attempts** - a SECOND, unrelated crash shape from the
     * same root cause (a process that died mid-work), closed here rather than in
     * [VoiceNoteRecorder]: [VoiceNoteRecorder.reconcileAfterProcessDeath] only ever scans
     * [VoiceNoteDao.getUnended] (a recording that never observed a stop), which says nothing about
     * a recording that DID stop cleanly but whose background transcription was still running when
     * the process died. [VoiceNoteDao.getStalledTranscriptions] is that second, independent scan -
     * see [VoiceNote.transcriptionAttemptStartedAt]'s own doc comment for why a non-null value read
     * back here can only mean the attempt never finished. Each stalled row is marked
     * [VoiceNote.transcriptionFailureReason] rather than silently re-launched: the audio is
     * unchanged on disk either way, so nothing is lost by requiring the explicit
     * [retryTranscription] a person can see and act on, rather than resuming background network
     * work the moment the app happens to start (which could run at an arbitrary time with no one
     * watching for a second failure).
     */
    suspend fun reconcileAfterProcessDeath(context: Context) {
        recorder(context).reconcileAfterProcessDeath()
        val stalled = dao(context).getStalledTranscriptions()
        for (note in stalled) {
            // markTranscriptionFailed's own narrow column-only UPDATE, not a read-.copy()-write -
            // this runs once at app start before anything else could plausibly be racing the same
            // row, but the narrow form costs nothing and keeps every writer in this file consistent.
            dao(context).markTranscriptionFailed(
                note.id,
                "Transcription was interrupted when the app closed. Tap Retry to try again.",
            )
        }
    }

    // -------------------------------------------------------------------- read / list

    /** Every voice note, most recent first - the hands-path list and the voice `list_voice_notes`
     * query tool's shared read. [limit] guards the voice tool from reading out an unbounded list;
     * the screen itself may pass a larger one or none. */
    suspend fun listNotes(context: Context, limit: Int = 200): List<VoiceNote> =
        dao(context).getAll().take(limit)

    /** What [listInRange] hands back - the day view's own "an empty day and a failed read are not
     * the same sentence" requirement (the calendar-briefing failure this mirrors: a refused
     * permission must never render identically to a clear day). [Loaded] with an empty list IS the
     * quiet-day case; [Failed] is the other one, and a caller must say so in words rather than
     * falling back to an empty list that would look the same. */
    sealed interface VoiceNotesForDayResult {
        data class Loaded(val notes: List<VoiceNote>) : VoiceNotesForDayResult
        data class Failed(val reason: String) : VoiceNotesForDayResult
    }

    /** Every note whose [VoiceNote.startedAt] falls in `[startInclusive, endExclusive)` - the
     * RECORDED section's read-time join in `ui/CalendarScreen.kt`. Deliberately a plain Room read
     * wrapped in [runCatching] rather than assumed infallible: a `@Query` against a local SQLite
     * database can still throw (a corrupt page, a full disk), and per this function's own
     * [VoiceNotesForDayResult] doc, that failure must read differently from a day with nothing
     * recorded on it. */
    suspend fun listInRange(context: Context, startInclusive: Long, endExclusive: Long): VoiceNotesForDayResult =
        runCatching { dao(context).getInRange(startInclusive, endExclusive) }.fold(
            onSuccess = { VoiceNotesForDayResult.Loaded(it) },
            onFailure = {
                Log.w(TAG, "listInRange: query failed for [$startInclusive, $endExclusive)", it)
                VoiceNotesForDayResult.Failed(it.message ?: "unknown error")
            },
        )

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

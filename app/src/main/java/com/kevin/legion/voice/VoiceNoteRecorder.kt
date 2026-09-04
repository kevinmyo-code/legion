package com.kevin.legion.voice

import android.content.Context
import android.media.MediaRecorder
import android.util.Log
import com.kevin.legion.data.local.VoiceNote
import com.kevin.legion.data.local.VoiceNoteDao
import com.kevin.legion.service.MicArbiter
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The audio side of one voice note - start and stop are the whole verb set (voice-notes ticket 01,
 * `.scratch/voice-notes/issues/01-the-recorder-and-the-mic.md`). "No timer, no VAD, no auto-stop
 * on silence. A recording ends because someone ended it or because the process died" - ticket 01's
 * own words, and the second half of that sentence is exactly why [reconcileAfterProcessDeath]
 * exists.
 *
 * **[AudioCapture] is injected, not constructed inline, so this class is unit-testable without a
 * real microphone.** [MediaRecorder] needs a live Android audio stack to `prepare()`/`start()`
 * against - a plain JVM test cannot exercise it, and this class's own state machine (mic
 * arbitration, the Room row's lifecycle, orphan/interrupted recovery) is exactly the part ticket
 * 01 asks to be pinned by a "Unit test", not an instrumented one. See [MediaRecorderAudioCapture]
 * for the real implementation and this file's test for the fake.
 */
interface AudioCapture {
    fun start()
    fun stop()
    fun release()
}

fun interface AudioCaptureFactory {
    fun create(outputPath: String): AudioCapture
}

/**
 * The real [AudioCapture] - AAC in an MPEG-4 container, mono, 16 kHz, ~32 kbps (ticket 01's own
 * budget: "An hour lands near 14 MB, which matters to ticket 03's upload"). `VOICE_COMMUNICATION`
 * as the audio source matches this codebase's one existing convention for opening the mic
 * ([com.kevin.legion.service.GeminiLiveSession], [com.kevin.legion.service.WakeWordEngine] both
 * use it) rather than introducing a second source constant for a third capture path.
 *
 * The no-arg `MediaRecorder()` constructor, not `MediaRecorder(Context)` - the latter needs API 31
 * and this app's `minSdk` is 24 (`app/build.gradle.kts`).
 */
@Suppress("DEPRECATION")
class MediaRecorderAudioCapture(outputPath: String) : AudioCapture {
    private val recorder = MediaRecorder().apply {
        setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
        setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        setAudioChannels(1)
        setAudioSamplingRate(16_000)
        setAudioEncodingBitRate(32_000)
        setOutputFile(outputPath)
    }

    override fun start() {
        recorder.prepare()
        recorder.start()
    }

    override fun stop() {
        recorder.stop()
    }

    override fun release() {
        recorder.release()
    }
}

sealed interface VoiceNoteStartResult {
    data class Started(val noteId: Long, val audioPath: String) : VoiceNoteStartResult
    /** [reason] is worded for direct use as a spoken refusal - CLAUDE.md §7's outcome-verb rule:
     * nothing calling this may say "started" unless it got [Started] back. */
    data class Refused(val reason: String) : VoiceNoteStartResult
}

sealed interface VoiceNoteStopResult {
    data class Stopped(val noteId: Long, val audioPath: String?) : VoiceNoteStopResult
    /** No recording was in progress on this recorder to stop - a normal, expected outcome (never
     * started, or already stopped/preempted), never reported as a stop having happened. */
    data object NothingRecording : VoiceNoteStopResult
}

/**
 * The recorder's own live state, observable rather than remembered per-screen (recordings-UI
 * follow-up ticket: "Neither surface queries `VoiceNoteRecorder` for its actual live state when it
 * mounts - each only knows about a recording IT started"). Exactly two values because [start]/
 * [stop]/[onMicPreempted] together only ever hold one recording at a time (this class's own class
 * doc: "a single active capture at a time").
 *
 * [Recording.startedAt] is the row's real [VoiceNote.startedAt] - the SAME timestamp written to
 * Room, not a second clock a composable starts on its own mount. A surface deriving its elapsed
 * clock from this rather than from "now minus when I first saw this composable" cannot restart at
 * zero on navigating away and back, because it is reading the recording's actual start, not its own.
 */
sealed interface VoiceNoteRecordingState {
    data object Idle : VoiceNoteRecordingState
    data class Recording(val noteId: Long, val startedAt: Long) : VoiceNoteRecordingState
}

/**
 * One process's worth of recording state - a single active capture at a time, matching
 * [MicArbiter]'s own one-holder model. Not a singleton (unlike [MicArbiter]): the caller (ticket
 * 04's `voice/VoiceNoteController.kt`) owns the instance and its lifetime.
 */
class VoiceNoteRecorder(
    private val context: Context,
    private val dao: VoiceNoteDao,
    private val audioCaptureFactory: AudioCaptureFactory = AudioCaptureFactory { path -> MediaRecorderAudioCapture(path) },
    /** Injected for the same reason [audioCaptureFactory] is - a fixed clock makes "started at
     * X, ended at X" assertions exact in a test instead of racing the real one. */
    private val now: () -> Long = System::currentTimeMillis,
    /** [MicArbiter.Listener.onMicPreempted] fires synchronously on the preempting caller's thread
     * and forbids blocking that call with real teardown work (see that interface's own doc
     * comment) - this scope is where [onMicPreempted] dispatches [AudioCapture.stop] and the Room
     * update instead. */
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    companion object {
        private const val TAG = "VoiceNoteRecorder"
        private const val DIR_NAME = "voicenotes"
    }

    private val lock = Any()
    private var activeCapture: AudioCapture? = null
    private var activeNoteId: Long? = null

    /** Backing field for [recordingState] - see that sealed interface's own doc comment for why
     * this exists at all. Written under [lock] alongside [activeCapture]/[activeNoteId] so the
     * flow's published value and this recorder's own internal notion of "is something active"
     * never disagree with each other. */
    private val _recordingState = MutableStateFlow<VoiceNoteRecordingState>(VoiceNoteRecordingState.Idle)

    /** The one source of truth for whether THIS process's recorder is currently recording, and
     * since [VoiceNoteController] holds exactly one [VoiceNoteRecorder] per process (that object's
     * own class doc), this is the one source of truth full stop - collected by both
     * `ui/MetersScreen.kt`'s RECORDINGS pane and `ui/voicenotes/VoiceNotesScreen.kt`'s record
     * control so either surface reports the truth regardless of which one started the recording. */
    val recordingState: StateFlow<VoiceNoteRecordingState> = _recordingState.asStateFlow()

    /**
     * True from the moment [start] claims the mic until it publishes [activeCapture]. That window
     * is real and blocking - a Room insert plus `MediaRecorder.prepare()/start()` sit inside it -
     * and [onMicPreempted] fires SYNCHRONOUSLY on the preempting thread, so without this flag a
     * ring arriving mid-start found both active fields still null, did nothing, and left the
     * recorder opening a microphone the arbiter had already handed away.
     */
    private var startInFlight = false

    /** Set by [onMicPreempted] when it lands inside the [startInFlight] window. [start] reads it
     * once its capture is running and unwinds instead of publishing a recording that no longer
     * holds the mic. */
    private var preemptedDuringStart = false

    private fun voiceNotesDir(): File = File(context.cacheDir, DIR_NAME).apply { mkdirs() }

    /**
     * Starts a recording. Claims [MicArbiter.Claimant.VOICE_NOTE] first - a refusal there (a
     * [MicArbiter.Claimant.LIVE_TURN] or [MicArbiter.Claimant.RING_LISTENING] already holding the
     * mic) means nothing is created at all, matching CLAUDE.md §7: no outcome verb without a
     * successful action behind it.
     *
     * The [VoiceNote] row is inserted BEFORE [AudioCapture.start] is even attempted, with
     * [VoiceNote.audioPath] already pointing at the file [AudioCapture] is about to write - so a
     * process death between insert and a successful `start()` still leaves a row
     * [reconcileAfterProcessDeath] can find and mark interrupted, rather than a start that
     * silently never happened. If [AudioCapture.start] itself throws, the row and the mic claim
     * are both rolled back and this returns [VoiceNoteStartResult.Refused] - nothing is left
     * pretending a recording exists that never actually opened the microphone successfully.
     */
    suspend fun start(kind: String, titleHint: String? = null): VoiceNoteStartResult {
        synchronized(lock) {
            if (activeCapture != null || startInFlight) {
                return VoiceNoteStartResult.Refused("A voice note is already recording.")
            }
            startInFlight = true
            preemptedDuringStart = false
        }

        if (!MicArbiter.request(MicArbiter.Claimant.VOICE_NOTE) { onMicPreempted() }) {
            synchronized(lock) { startInFlight = false }
            return VoiceNoteStartResult.Refused("Couldn't start recording - the microphone is in use.")
        }

        val file = File(voiceNotesDir(), "${UUID.randomUUID()}.m4a")
        // Captured once and reused for the Room row AND for recordingState below - two calls to
        // now() here would let the flow's elapsed-clock anchor drift from the row's own
        // VoiceNote.startedAt by whatever real time passed between them.
        val startedAt = now()
        val noteId = dao.insert(
            VoiceNote(
                startedAt = startedAt,
                title = titleHint,
                audioPath = file.absolutePath,
                kind = kind,
            )
        )

        val capture = audioCaptureFactory.create(file.absolutePath)
        try {
            capture.start()
        } catch (e: Exception) {
            Log.w(TAG, "start: audio capture failed to start, rolling back", e)
            synchronized(lock) { startInFlight = false }
            MicArbiter.release(MicArbiter.Claimant.VOICE_NOTE)
            dao.deleteById(noteId)
            runCatching { capture.release() }
            file.delete()
            return VoiceNoteStartResult.Refused("Couldn't start recording: ${e.message ?: "unknown error"}")
        }

        val lostTheMic = synchronized(lock) {
            startInFlight = false
            if (preemptedDuringStart) {
                true
            } else {
                activeCapture = capture
                activeNoteId = noteId
                _recordingState.value = VoiceNoteRecordingState.Recording(noteId, startedAt)
                false
            }
        }

        if (lostTheMic) {
            // A higher-ranked claimant took the mic while prepare()/start() was still blocking.
            // Ticket 01's rule for a preempted recording applies unchanged - stop, keep the audio,
            // mark the row interrupted - and this returns Refused rather than Started, because by
            // the time it returns nothing is recording. MicArbiter.release is deliberately NOT
            // called: the holder is already someone else, and release() no-ops for a non-holder
            // anyway, so calling it would be a claim we cannot make.
            Log.w(TAG, "start: preempted during start, unwinding note $noteId")
            runCatching { capture.stop() }
            runCatching { capture.release() }
            dao.getById(noteId)?.let { dao.update(it.copy(endedAt = now(), interrupted = true)) }
            return VoiceNoteStartResult.Refused("Couldn't start recording - the microphone was taken.")
        }

        return VoiceNoteStartResult.Started(noteId, file.absolutePath)
    }

    /** A deliberate, user-initiated stop. [VoiceNote.endedAt] is set to [now], and
     * [VoiceNote.interrupted] is set from whether [AudioCapture.stop] ACTUALLY succeeded - `false`
     * on the ordinary complete-recording path, `true` when the underlying stop threw and the file
     * on disk cannot be trusted to be whole. */
    suspend fun stop(): VoiceNoteStopResult {
        val active = takeActive() ?: return VoiceNoteStopResult.NothingRecording
        val (capture, noteId) = active
        val stoppedAt = now()

        // MediaRecorder.stop() throws precisely when no valid data was recorded - stopped too
        // soon, or a corrupt state - which is the case where the .m4a on disk is truncated or
        // unusable. Writing interrupted = false regardless would make that row assert a complete
        // audio anchor it does not have, which is the one thing ADR 0041 forbids. The outcome is
        // read from the call, never assumed.
        val stoppedCleanly = runCatching { capture.stop() }
            .onFailure { Log.w(TAG, "stop: capture.stop() failed - marking the note interrupted", it) }
            .isSuccess
        runCatching { capture.release() }
        MicArbiter.release(MicArbiter.Claimant.VOICE_NOTE)

        val note = dao.getById(noteId)
        note?.let { dao.update(it.copy(endedAt = stoppedAt, interrupted = !stoppedCleanly)) }
        return VoiceNoteStopResult.Stopped(noteId, note?.audioPath)
    }

    /**
     * [MicArbiter.Listener] callback for [MicArbiter.Claimant.VOICE_NOTE] losing the mic - only
     * [MicArbiter.Claimant.RING_LISTENING] can trigger this (ticket 01: "A call arriving stops the
     * recording, marks it interrupted, and keeps the audio" - [MicArbiter.Claimant.LIVE_TURN]
     * cannot preempt [MicArbiter.Claimant.VOICE_NOTE] at all, see [MicArbiter]'s own `outranks` doc
     * comment). Fires synchronously on the preempting caller's thread, so the real teardown work
     * is dispatched onto [scope] rather than run inline - the mic is already gone the instant this
     * is called; only the graceful `MediaRecorder` shutdown and the Room update are deferred.
     */
    private fun onMicPreempted() {
        val active = takeActive() ?: run {
            // Nothing published yet. If a start is still in flight, record the loss so start()
            // unwinds when it gets there; otherwise this is a stale callback and there is nothing
            // to tear down.
            synchronized(lock) { if (startInFlight) preemptedDuringStart = true }
            return
        }
        val (capture, noteId) = active
        val stoppedAt = now()
        scope.launch {
            runCatching { capture.stop() }.onFailure { Log.w(TAG, "onMicPreempted: capture.stop() failed", it) }
            runCatching { capture.release() }
            val note = dao.getById(noteId)
            note?.let { dao.update(it.copy(endedAt = stoppedAt, interrupted = true)) }
        }
    }

    /** Atomically reads and clears the active capture/note pair, or returns null if nothing is
     * active. Shared by [stop] and [onMicPreempted] so both agree on what "active" means -
     * including [recordingState], published back to [VoiceNoteRecordingState.Idle] in the same
     * locked section so no observer can ever see a stale [VoiceNoteRecordingState.Recording] after
     * this returns. */
    private fun takeActive(): Pair<AudioCapture, Long>? = synchronized(lock) {
        val capture = activeCapture
        val noteId = activeNoteId
        if (capture == null || noteId == null) {
            null
        } else {
            activeCapture = null
            activeNoteId = null
            _recordingState.value = VoiceNoteRecordingState.Idle
            capture to noteId
        }
    }

    /**
     * Crash-recovery reconcile, meant to run once at app startup before anything reads [dao] for
     * display (ticket 01: "Survive process death honestly"). Two independent sweeps:
     *
     * 1. Every row [VoiceNoteDao.getUnended] still returns - a recording whose process died before
     *    [stop] or [onMicPreempted] ever ran - is marked [VoiceNote.interrupted]. Its
     *    [VoiceNote.endedAt] stays null forever (nothing observed a real stop time to write there;
     *    see [VoiceNote]'s own doc comment for why that is the ONE case [VoiceNote.endedAt]'s
     *    nullness is load-bearing rather than just informative), and its audio is kept as-is,
     *    whatever was flushed to disk before the process died.
     * 2. Every `.m4a` under [voiceNotesDir] that no row's [VoiceNote.audioPath] claims - written
     *    before its row's insert landed, or left behind by a delete that bypassed
     *    [com.kevin.legion.data.local.VoiceNoteStore] - is an orphan and is deleted.
     *
     * **Nothing calls this yet.** Ticket 01's own file scope is this class; wiring a call into app
     * startup (`AriaForegroundService`, most likely) is left to whichever ticket first constructs
     * a [VoiceNoteRecorder] in production - flagged explicitly in this session's own report as an
     * open gap rather than assumed done.
     */
    suspend fun reconcileAfterProcessDeath() {
        dao.getUnended().forEach { note ->
            if (!note.interrupted) {
                dao.update(note.copy(interrupted = true))
            }
        }

        val claimedPaths = dao.getAllAudioPaths().toSet()
        voiceNotesDir()
            .listFiles { file -> file.isFile && file.name.endsWith(".m4a") }
            ?.forEach { file ->
                if (file.absolutePath !in claimedPaths && !file.delete()) {
                    Log.w(TAG, "reconcileAfterProcessDeath: failed to delete orphan file ${file.absolutePath}")
                }
            }
    }
}

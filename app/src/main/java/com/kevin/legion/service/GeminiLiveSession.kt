package com.kevin.legion.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import androidx.core.content.ContextCompat
import com.kevin.legion.BuildConfig
import com.kevin.legion.ai.CrisisDetector
import com.kevin.legion.ai.GeminiKeyProvider
import com.kevin.legion.car.CarProbeLog
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.EpisodicTurn
import com.kevin.legion.media.MusicController
import com.kevin.legion.media.NowPlayingController
import com.kevin.legion.vehicle.ActiveVehicle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import com.kevin.legion.MidnightEvents
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Events surfaced from a live session to its owner. Delivered on the main
 * thread so Compose/UI state can be updated directly.
 */
sealed interface LiveEvent {
    /** Setup acknowledged - the mic is now open and streaming. */
    data object Connected : LiveEvent
    /** Zero has begun speaking this turn (first audio chunk arrived). */
    data object SpeakingStarted : LiveEvent
    /** The driver barged in; queued model audio was flushed. */
    data object Interrupted : LiveEvent
    /** Zero finished a turn and is waiting for the driver. */
    data object TurnComplete : LiveEvent
    /**
     * The mic has ACTUALLY started capturing - [AudioRecord.startRecording] returned in
     * micLoop, not merely that [openMicForUser] set the intent to. 2026-08-17: this is the
     * fix for "shows Listening but the mic is shut" (same defect shape as 57ed400's
     * Phase.THINKING fix, a UI phase claiming one thing while the code does another). Real
     * capture sits behind [awaitPlaybackDrained], which can run up to
     * PLAYBACK_DRAIN_TIMEOUT_MS + MIC_REOPEN_SETTLE_MS (~1.56s) after [TurnComplete] fires -
     * an owner that flips to "Listening..." on TurnComplete itself is lying to the driver for
     * up to that long, and keeps lying indefinitely if capture then dies silently. The owner
     * should gate any "Listening" UI state on THIS event, not on TurnComplete.
     */
    data object MicOpened : LiveEvent
    /**
     * The mic has ACTUALLY stopped capturing ([AudioRecord.stop] ran in micLoop), whether
     * because Zero started speaking (half-duplex mute) or the session is tearing down. [why]
     * mirrors the reason strings already logged to [com.kevin.legion.MidnightEvents.micState].
     * Not currently consumed for a phase change (SpeakingStarted already covers the ordinary
     * half-duplex-mute case, effectively simultaneously) - exposed for symmetry with
     * [MicOpened] and so a future consumer doesn't need a second wiring pass.
     */
    data class MicClosed(val why: String) : LiveEvent
    /** Gemini wants a local tool run; reply via [GeminiLiveSession.sendToolResponse]. */
    data class ToolCall(val id: String, val name: String, val args: JSONObject) : LiveEvent
    /** Live transcript of what Zero is saying this turn (debug subtitle), accumulated. */
    data class Subtitle(val text: String) : LiveEvent
    /**
     * [CrisisDetector] matched the driver's transcript (CLAUDE.md sec 9.1). Queued
     * model audio has already been flushed. The owner must surface real crisis
     * resources plainly and OUT of character - never route this back through Zero's
     * voice, which is the exact thing sec 9.1 says to stop doing.
     */
    data object CrisisDetected : LiveEvent
    /**
     * The conversation ended but the socket is being kept warm (connected, mic
     * closed). A tap resumes instantly via [GeminiLiveSession.beginConversation]
     * with no reconnect. The socket fully closes (â†’ [Closed]) after the warm hold.
     */
    /**
     * The conversation ended but the socket is still warm. [backstop] is true only when
     * the 30-minute no-input cap ([CONVERSATION_BACKSTOP_MS]) ended it rather than the
     * driver or a proactive line - the one case the driver never asked for and must
     * therefore be TOLD about (Kevin, 2026-08-18: on screen, not spoken).
     */
    data class Idle(val backstop: Boolean) : LiveEvent
    /** The session ended (closed, timed out, or errored). */
    data class Closed(val reason: String) : LiveEvent
    /**
     * Ticket 02 (drive-test-2026-08-18, "the context dies with the socket"): the server sent a
     * resumable [SessionResumptionUpdate] - `handle` is the token a REPLACEMENT socket can pass
     * back in `sessionResumption.handle` to continue this same conversation server-side. Gemini
     * Live keeps conversation history in the socket, not the client, so this handle is the only
     * thing that survives a socket death; [LiveSessionController] persists it (a [GeminiLiveSession]
     * instance does not outlive its own socket) and threads it into the next [start] call.
     */
    data class ResumeHandleUpdated(val handle: String) : LiveEvent
}

/**
 * A single Gemini Live (BidiGenerateContent) session: a full speech-to-speech
 * turn loop run by Gemini itself. We stream raw mic audio up and play the
 * model's spoken audio back; Gemini does the STT, reasoning (with
 * google_search + our function tools), and TTS.
 *
 * Two modes, chosen at [start] via `vad`:
 *  - **Conversation** (`vad = true`): the floating button starts a hands-free,
 *    Gemini-Live-style chat. Server voice-activity detection runs turn-taking,
 *    and we run it half-duplex - the mic is muted while Zero speaks (so he
 *    doesn't hear himself through the head-unit speakers and interrupt himself)
 *    and reopened the moment he finishes, then the chat ends after
 *    [IDLE_TIMEOUT_MS] of silence - **speak-only sessions only**. A hands-free
 *    conversation has no timeout, only the thirty-minute forgotten-conversation
 *    cap in [armConversationBackstop].
 *  - **Speak-only** (`vad = false`): the proactive engine injects a line via
 *    [sendText]; Zero speaks it and the mic never opens.
 *
 * This replaces the old sherpa STT -> REST brain -> Piper TTS pipeline. There's
 * no wake word.
 *
 * Talks to the Live WebSocket directly via OkHttp rather than the Google GenAI
 * Java SDK: that SDK's Live client is built on java.net.http.WebSocket, which
 * isn't available below Android API 33 (our minSdk is 24). The wire protocol is
 * a plain WebSocket, so OkHttp covers it with the same API key.
 */
/**
 * How a [GeminiLiveSession] socket authenticates. Per CLAUDE.md sec 2 (2026-07-16
 * rewrite, two tiers, no trial, no subscription, no broker), the only path is
 * [Direct]: the driver's own Gemini key (or a dev BuildConfig key), sent as a
 * `?key=` query param on the v1beta endpoint. The broker-minted ephemeral-token
 * path was removed with the broker.
 */
sealed class ConnectionMode {
    data class Direct(val rawKey: String) : ConnectionMode()
}

class GeminiLiveSession(
    context: Context,
    private val onEvent: (LiveEvent) -> Unit,
) {
    private val appContext = context.applicationContext
    private val apiKey get() = GeminiKeyProvider.key()

    private val io = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val main = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    // Long-lived socket: disable the read timeout so the stream stays open, and
    // ping to keep intermediaries from dropping an idle connection.
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    @Volatile private var webSocket: WebSocket? = null
    private val running = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    // Warm = connected and set up, but no active conversation (mic closed). Set on
    // setupComplete and whenever a conversation parks (parkWarm); cleared the
    // moment a conversation begins. Lets a tap resume instantly without a new
    // socket. Only meaningful when [keepWarm] is on (the tap-driven chat path).
    private val warm = AtomicBoolean(false)
    @Volatile private var keepWarm = false
    // How long a SPEAK-ONLY session waits before armIdleTimeout() closes it (see
    // its doc). Configurable per-[start] call: onboarding needs a much longer
    // grace period than routine command-response turns - the main app's default
    // (IDLE_TIMEOUT_MS, 10s) was being hit by completely normal "let me think
    // about that" pauses during open-ended onboarding questions
    // (name/personality/car trim), and ConversationalOnboardingScreen's ChatStep
    // was treating that benign timeout exactly like a real network failure.
    //
    // **That finding was right and its fix was never applied where it mattered.**
    // No caller has ever passed this parameter, so hands-free conversation ran on
    // the same 10 seconds and died on the same benign pauses - on a drive, where
    // they are more frequent, not less. A conversation no longer consults this at
    // all (Kevin, 2026-08-18); it waits for the driver, bounded only by
    // [armConversationBackstop]'s thirty-minute forgotten-conversation cap.
    @Volatile private var idleTimeoutMs = IDLE_TIMEOUT_MS
    @Volatile private var conversationActive = false
    private var warmHoldJob: Job? = null

    // One-shot: the next completed turn parks warm instead of opening the mic.
    // Set when a proactive line is spoken on a warm socket, so the opener/alert is
    // voiced without turning into a listening conversation.
    @Volatile private var suppressMicNextTurn = false

    private var micJob: Job? = null
    private var idleJob: Job? = null
    private var audioTrack: AudioTrack? = null

    /**
     * Guards every transition of [audioTrack]'s lifecycle - create, play, flush, release.
     *
     * **Crash fix, 2026-08-11** (`IllegalStateException: Unable to retrieve AudioTrack pointer for
     * start()`, reproduced on-device by tapping tap-to-talk while Zero was mid-sentence).
     * [closeSession] cancels [audioPlaybackJob] and then calls [releaseTrack] - but
     * [playAudio] is a plain blocking function, so cancelling the job that runs it does not
     * interrupt it mid-body. That left this interleave wide open:
     *
     * 1. the playback consumer is inside [playAudio] and has just read a live track out of
     *    [ensureTrack];
     * 2. the tap runs [closeSession] on another thread, which releases that very track;
     * 3. the consumer calls `play()` on the now-released track and the process dies.
     *
     * `@Volatile` on the field would not have helped: the bug is a torn read-then-use across two
     * threads, not a stale value. Every section that touches the track is short and non-blocking
     * and runs under this lock; the one genuinely blocking call ([AudioTrack.write]) deliberately
     * stays OUTSIDE it, so a barge-in never has to wait out a full ~0.5s output buffer to tear the
     * session down. That write is instead made safe by re-checking identity each iteration.
     */
    private val trackLock = Any()

    /**
     * True once [releaseTrack] has run, until a new session explicitly reopens playback.
     *
     * Without this, a playback coroutine still draining [audioQueue] after teardown would call
     * [ensureTrack], find `audioTrack` null, and helpfully build a BRAND NEW track for a session
     * that is already closed - speaking the tail of a reply the driver interrupted, out of a
     * session that no longer exists, holding audio focus nothing will ever release.
     */
    @Volatile private var playbackClosed = false
    // Frames handed to [audioTrack] since it was created or last flushed, compared
    // against its playback head to know when Zero's speech has really finished
    // (see awaitPlaybackDrained). Volatile: written on the playback consumer,
    // read on the mic loop.
    @Volatile private var framesWritten: Long = 0
    private var speakingThisTurn = false

    // Monotonic generation counter, bumped by [flushAudio] every time queued model
    // audio is discarded (barge-in tap in [beginConversation], the server's own
    // "interrupted" signal, [checkForCrisis], and now the short-capture retry in
    // micLoop too). Exists to close a race the driver hit as "the mic I just opened
    // by tapping shut itself again" (2026-08-17, measured alongside the
    // awaitPlaybackDrained defect above): a barge-in tap flushes audio, clears
    // [speakingThisTurn], and opens the mic - but nothing tells the SERVER a
    // barge-in happened, so a `modelTurn` WebSocket message that was already in
    // flight when the tap landed can still arrive afterward. That stale message
    // hits `handleServerContent`'s `!speakingThisTurn` branch, which - reasonably,
    // for a NORMAL turn - sets speakingThisTurn back to true and (half-duplex)
    // shuts the mic that was just opened for the driver.
    // `handleServerContent` snapshots this counter once per incoming message and
    // compares it against the live value before touching speakingThisTurn/
    // capturing for that message's audio chunks - a mismatch means the chunk
    // belongs to a turn this session has already moved past, and it is dropped
    // rather than acted on.
    //
    // SENIOR REVIEW FIX (2026-08-17): AtomicInteger, not a @Volatile Int with `++`.
    // flushAudio() has genuinely concurrent callers - the OkHttp socket-callback
    // thread (handleServerContent's interrupted/crisis branches) and the mic
    // coroutine (beginConversation's barge-in path, and now micLoop's own retry
    // branch) can both call it around the same moment. `turnGeneration++` on a
    // plain volatile field is read-modify-write, not atomic; a lost increment
    // between two racing callers can land the counter back on a value a stale
    // snapshotted message still holds, resurrecting the exact race this field
    // exists to close. incrementAndGet() in [flushAudio], get() everywhere else.
    private val turnGeneration = java.util.concurrent.atomic.AtomicInteger(0)

    // HYPOTHESIS FIX (B12, unverified as of 2026-07-07 - a driver-reported
    // "turn goes silent, only plays back after I tap again"). AudioTrack.write()
    // is a blocking call; previously it ran synchronously inside the OkHttp
    // WebSocket callback that also processes turnComplete/tool-call frames. If
    // playback ever stalls on flaky head-unit audio hardware (this file's own
    // comments already flag capture/playback HAL contention as a known issue),
    // a blocked write could wedge processing of every later server message
    // behind it - including the turnComplete that should have ended the turn -
    // which matches "nothing happens until some other action (a tap) unsticks
    // it". Decoupling playback onto its own consumer means a stalled write can
    // never block frame processing, regardless of the exact stall cause.
    // Unbounded: audio chunks are small and a session isn't long-lived enough
    // for this to be a real memory concern, and bounding it risks silently
    // dropping audio instead.
    private val audioQueue = Channel<ByteArray>(Channel.UNLIMITED)
    private var audioPlaybackJob: Job? = null

    // The per-car prebuilt voice for this session (blank -> the default [VOICE]),
    // passed in at [start] from the active vehicle's profile.
    @Volatile private var voiceName: String = ""

    // Conversation mode: Gemini's server VAD runs turn-taking and we capture the
    // driver's mic continuously (half-duplex - muted while Zero speaks). False
    // for a speak-only proactive line, where the mic never opens.
    @Volatile private var vadMode = false
    // First mic-open of a conversation flushes the pre-roll buffer; later turns
    // don't, so we never clip the start of the driver's reply.
    @Volatile private var vadMicOpenedOnce = false

    private val audioManager by lazy {
        appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
    private var audioFocusRequest: AudioFocusRequest? = null

    // Registered on every requestAudioFocus() call below (2026-08-17) - previously the
    // AudioFocusRequest had no listener at all, so a TRANSIENT focus loss (a notification
    // tone, an incoming call, Spotify grabbing focus) was completely invisible: nothing
    // logged it, and nothing could correlate one against a bad turn's timing. DIAGNOSTIC
    // ONLY - this does not change capture behavior on any focus transition. Nothing has
    // yet shown focus loss is actually implicated in the "sometimes it doesn't hear me"
    // reports; that has to be established from a real log pull before it's worth reacting
    // to. Adding capture-altering logic here on spec, before that evidence exists, would be
    // exactly the kind of guess this file's own delicacy warns against.
    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        val name = when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> "AUDIOFOCUS_GAIN"
            AudioManager.AUDIOFOCUS_LOSS -> "AUDIOFOCUS_LOSS"
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> "AUDIOFOCUS_LOSS_TRANSIENT"
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> "AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK"
            else -> "unknown($focusChange)"
        }
        Log.d(TAG, "audio focus change: $name")
        CarProbeLog.log("AUDIO_FOCUS", "focus change: $name")
    }

    // The MIC_HANDOFF_MS delay gives anything else briefly holding the mic (e.g.
    // system audio routing) a moment to release it before we grab it - only
    // relevant for the first capture of a session. Re-applying it on every turn
    // would just clip the start of the driver's speech.
    @Volatile private var micHandedOff = false

    // Whether the mic loop should forward audio right now. The AudioRecord stays
    // open for the whole session; this gates capture half-duplex - true while the
    // driver may be talking, flipped false the moment Zero starts speaking
    // (openMicForUser / the modelTurn handler). Reopening the record per turn
    // raced the mic and dropped a turn's audio, so we gate instead of reopen.
    @Volatile private var capturing = false

    // Set on the first mic-open of a conversation: the mic loop resets the
    // AudioRecord on its own thread before forwarding, discarding any pre-roll
    // buffer (e.g. audio a backgrounded device replayed). See openMicForUser.
    @Volatile private var flushCapture = false

    // Ticket 15 (.scratch/android-auto/issues/15-the-live-session-can-be-silenced.md):
    // the platform can silence our capture with NO exception and NO AudioRecord state
    // change - a privacy-sensitive capture elsewhere (the Android Auto Assistant is the
    // motivating case) wins arbitration and AudioRecord.read() keeps returning zeroes
    // while the record stays ACTIVE. There is no way to detect this from AudioRecord
    // itself; the only signal is AudioManager.AudioRecordingCallback +
    // AudioRecordingConfiguration.isClientSilenced() (API 29+), registered/unregistered
    // around each capture in micLoop (see recordingCallback below). Exposed here so any
    // UI surface (the car probe screen, a future in-app indicator) can say "I am being
    // silenced" instead of quietly appearing to listen. False on API < 29, where the
    // platform gives no way to know either way - that is a real gap, not a claim of
    // safety, and micLoop logs it once to CarProbeLog so a field session says so.
    private val _isSilenced = MutableStateFlow(false)
    val isSilenced: StateFlow<Boolean> = _isSilenced

    // Bytes of mic audio forwarded to Gemini since the current turn began.
    // Logged on turnComplete as a capture-health trace: a turn that forwards ~0
    // bytes means Gemini got no new audio. A healthy turn forwards tens of KB.
    @Volatile private var bytesThisTurn = 0L

    // Whether micLoop has already spent this turn's ONE short-capture retry
    // (2026-08-17, measured alongside the two defects above: dumpsys appops
    // RECORD_AUDIO history shows the mic genuinely opening and closing again
    // under a second on bad turns - 964/991/663/276 ms against healthy turns'
    // 15367/13256/9171/5582/4374 ms). A capture that dies that fast, forwarding
    // next to nothing, almost certainly means the server's VAD end-pointed on
    // noise or a stray sound rather than real speech - accepting the model's
    // reply to it treats a turn the driver never got to speak in as an answered
    // question. micLoop reopens the mic once per turn when it sees that shape;
    // this flag is what stops "once" from becoming "forever" if the retry comes
    // up short too (a genuinely dead mic must still surface as broken, not loop
    // silently). Reset to false in [openMicForUser] - a fresh turn, fresh retry.
    @Volatile private var retriedThisTurn = false

    // What Gemini transcribed the driver as saying this turn, accumulated from
    // inputTranscription deltas and logged on turnComplete. Paired with
    // bytesThisTurn this pins down the "repeat" bug: distinct presses that yield
    // the SAME transcript (with real bytes) mean the mic is feeding identical
    // audio (e.g. an emulator replaying host audio) rather than a code fault.
    private val userTurnText = StringBuilder()

    // Guards LiveEvent.CrisisDetected to once per turn. The detector runs on every
    // partial transcript delta, and the transcript only grows within a turn, so a
    // match on one delta matches on every delta after it. Reset on turnComplete.
    @Volatile private var crisisFiredThisTurn = false

    // Ticket 15 (google-account-integration), ticket 07's read-through rule: a mail tool
    // called anywhere in this turn means the driver's transcript AND whatever Alfred says in
    // reply (which may itself be a subject line read out loud) must not land in the episodic
    // log - see captureEpisodicTurn's doc comment. Set from handleToolCall the moment a
    // functionCall named in LiveToolbox's mail-tool set arrives (before dispatch even runs,
    // since we only need to know a mail tool was ASKED for, not what it returned), cleared on
    // turnComplete alongside every other per-turn accumulator.
    @Volatile private var mailToolCalledThisTurn = false

    // Zero's own speech this turn, from outputAudioTranscription - only requested
    // when the debug subtitle toggle is on, and emitted as LiveEvent.Subtitle.
    @Volatile private var subtitles = false
    private val companionTurnText = StringBuilder()

    // Companion-memory ticket 01 (2026-07-22): groups this connection's
    // EpisodicTurn rows. Minted fresh in start(); blank before the first
    // start() call, which captureTurn() below treats as "don't capture yet."
    @Volatile private var episodicSessionId: String = ""

    // Ticket 02 (drive-test-2026-08-18): the resumption handle THIS connection was opened
    // with, set once in [start] and read by [buildSetup]. Separate from the handle we may
    // later RECEIVE (below) because those are different moments - one is "what we asked to
    // resume with", the other is "what the server told us we can resume with next".
    @Volatile private var requestedResumeHandle: String? = null

    // The latest resumable handle the server has actually confirmed via
    // SessionResumptionUpdate (resumable=true). Only overwritten on resumable=true - see
    // [handleSessionResumptionUpdate]'s doc for why a resumable=false update must not
    // clobber a good earlier handle. Read nowhere in this class beyond that guard; the
    // owner (LiveSessionController) is what actually persists it across a socket death,
    // via the [LiveEvent.ResumeHandleUpdated] emitted alongside it.
    @Volatile private var lastResumableHandle: String? = null

    // Emulators have no real acoustic path: the hardware echo-canceller/noise-
    // suppressor do nothing useful and can interfere, and the virtual mic can
    // replay the same host audio every turn. Detected so we can skip the audio
    // effects and warn that audio must be validated on real hardware.
    private val isEmulator: Boolean by lazy {
        val fp = Build.FINGERPRINT.lowercase()
        fp.contains("generic") || fp.contains("emulator") || fp.contains("sdk") ||
            Build.MODEL.lowercase().contains("sdk_gphone") ||
            Build.PRODUCT.lowercase().contains("sdk") ||
            Build.HARDWARE.lowercase().let { it.contains("goldfish") || it.contains("ranchu") }
    }

    /**
     * Opens the session. [systemInstruction] is the full persona + live-context
     * prompt (built by [com.kevin.legion.ai.AriaBrain]); [functionDeclarations] is
     * a JSON array of Gemini function declarations for local tools (may be empty).
     * [vad] selects conversation mode (server VAD + half-duplex mic) vs. a
     * speak-only proactive line (no mic). See the class doc.
     */
    fun start(
        systemInstruction: String,
        functionDeclarations: JSONArray = JSONArray(),
        vad: Boolean = false,
        voiceName: String = "",
        keepWarm: Boolean = false,
        prewarmOnly: Boolean = false,
        idleTimeoutMs: Long = IDLE_TIMEOUT_MS,
        connectionMode: ConnectionMode = ConnectionMode.Direct(apiKey),
        // Ticket 02: a handle from a PRIOR connection's ResumeHandleUpdated, so this new
        // socket continues that same conversation instead of starting cold. Null is a
        // perfectly normal value (first-ever session, or the owner has none to offer) -
        // buildSetup still opts into resumption either way, just without a handle to resume
        // FROM, so this connection itself becomes resumable going forward.
        resumeHandle: String? = null,
    ) {
        if (!running.compareAndSet(false, true)) return
        closed.set(false)
        requestedResumeHandle = resumeHandle
        lastResumableHandle = resumeHandle
        // Companion-memory ticket 01 (2026-07-22): one id per real connection,
        // groups this session's EpisodicTurn rows for ticket 02's consolidation
        // pass. Minted here (not per-conversation) so a warm-idle resume within
        // the same socket stays one session; a fresh start() (new socket) is a
        // new one.
        episodicSessionId = java.util.UUID.randomUUID().toString()
        micHandedOff = false
        capturing = false
        vadMode = vad
        this.voiceName = voiceName
        this.keepWarm = keepWarm
        this.idleTimeoutMs = idleTimeoutMs
        // Output transcription drives the live captions under the avatar (Cruise) and the
        // floating button. Subtitles are now a core UX surface, not a debug-only feature, so
        // it's always on; the token cost of the speech transcript is small. DebugSettings keeps
        // the toggle for diagnostics but no longer gates whether captions appear.
        subtitles = true
        vadMicOpenedOnce = false
        conversationActive = false
        warm.set(false)
        suppressMicNextTurn = false
        // A pre-connect (no conversation yet) must NOT mark the app busy - the
        // proactive engine may still speak on the warm socket. A cold session that
        // will speak/listen right away marks busy so proactive stays quiet for it.
        if (!prewarmOnly) ConversationState.setBusy(true)
        // Build the playback track up front so the first audio chunk plays without
        // paying AudioTrack setup latency mid-reply (pre-warming the output path).
        // Undo any previous teardown's latch before the consumer starts pulling chunks, then
        // pre-warm the output path so the first chunk plays without AudioTrack setup latency.
        reopenPlayback()
        io.launch { ensureTrack() }
        // Consumer for audioQueue - see its declaration for why playback runs on
        // its own coroutine instead of inline in the WebSocket callback.
        if (audioPlaybackJob?.isActive != true) {
            audioPlaybackJob = io.launch {
                for (chunk in audioQueue) playAudio(chunk)
            }
        }
        // Ducking is scoped to the active conversation: taken when the mic first
        // opens / Zero first speaks (duckNow) and released at closeSession, so
        // music drops for the chat and returns when it ends. See duckNow().

        val request = when (connectionMode) {
            is ConnectionMode.Direct -> Request.Builder().url("$WS_URL?key=${connectionMode.rawKey}").build()
        }
        webSocket = httpClient.newWebSocket(
            request,
            SocketListener(systemInstruction, functionDeclarations)
        )
    }

    /**
     * Begins (or resumes) a hands-free conversation on an already-connected
     * socket. [opener] is an optional line for Zero to speak first (e.g. a
     * greeting + fresh live context); pass null to open the mic immediately for
     * the driver (snappy resume - no greeting round-trip). Safe to call only once
     * the session is connected; the controller gates on [LiveEvent.Connected] /
     * [isWarm].
     */
    /** @return false if the socket is already gone (stale warm socket), so the
     *  controller can cold-connect instead of sitting silently in LISTENING. */
    fun beginConversation(opener: String? = null): Boolean {
        if (!running.get() || closed.get() || webSocket == null) return false
        warmHoldJob?.cancel()
        warm.set(false)
        // A tap can land while Zero is still finishing a proactive line (or
        // the tail of a prior turn) - openMicForUser() below would otherwise
        // open the mic on top of his own still-playing audio, since half-
        // duplex assumes only one of playback/capture is active at a time.
        // Barge in exactly like a server-detected interruption: stop his
        // audio now so the mic doesn't capture his own trailing voice.
        if (speakingThisTurn) {
            flushAudio()
            speakingThisTurn = false
        }
        conversationActive = true
        vadMode = true
        suppressMicNextTurn = false
        ConversationState.setBusy(true)
        return if (opener != null) {
            sendText(opener)
        } else {
            openMicForUser()
            true
        }
    }

    /**
     * Speaks [text] on a warm socket without opening the mic afterward - the
     * proactive path when a warm conversation socket already exists, so an opener
     * or alert is voiced and the socket stays warm rather than spinning up a new
     * connection or turning into a listening turn.
     *
     * Deliberately does NOT clear [warm]/[conversationActive] here (unlike
     * [beginConversation]): those flags back [isWarm] and [inConversation],
     * which the controller's onTap() uses to decide whether a driver tap can
     * resume this session in place. Clearing them mid-utterance used to make
     * an alive, still-speaking proactive session look like neither warm nor
     * in-conversation to onTap(), which fell through to destroying it and
     * cold-restarting a whole new conversation - an abrupt cutoff plus a full
     * reconnect+greeting instead of just handing control to the driver. The
     * turnComplete handler still transitions state correctly afterward via
     * suppressMicNextTurn -> parkWarm().
     */
    fun speakOnWarm(text: String) {
        if (!running.get() || closed.get()) return
        warmHoldJob?.cancel()
        suppressMicNextTurn = true
        ConversationState.setBusy(true)
        sendText(text)
    }

    /** Connected and idle (warm), ready for an instant [beginConversation]. */
    fun isWarm(): Boolean = running.get() && !closed.get() && warm.get() && !conversationActive

    /** A conversation (mic open / mid-turn) is currently running. */
    val inConversation: Boolean get() = running.get() && !closed.get() && conversationActive

    /**
     * Half-duplex turn handoff: open the driver's mic and wait for their reply.
     * Called when Zero finishes a turn in conversation mode. The mic loop starts
     * capturing; server VAD decides when the driver has finished speaking.
     */
    private fun openMicForUser() {
        idleJob?.cancel()
        duckNow()
        bytesThisTurn = 0L
        // A fresh driver turn - the short-capture retry (see [retriedThisTurn]'s
        // own doc, consumed in micLoop) gets one fresh attempt per turn, not one
        // for the whole session. Reset here rather than only after a successful
        // full-length turn, so a genuinely short but INTENTIONAL driver utterance
        // ("yes", "no", "stop") on turn N+1 isn't penalized by a retry spent on
        // turn N.
        retriedThisTurn = false
        // Only the very first open flushes the pre-roll buffer; flushing every
        // turn would clip the start of the driver's reply.
        val firstOpen = !vadMicOpenedOnce
        if (firstOpen) {
            flushCapture = true
            vadMicOpenedOnce = true
        }
        // Breadcrumb, not just a local flag flip: "showed LISTENING but heard
        // nothing" (Kevin, 2026-07-16) is precisely a UI phase that says the mic is
        // open while `capturing` is false. Pairing this with voiceTurn's
        // forwardedBytes tells the two apart - mic never opened, vs opened and
        // forwarded nothing.
        MidnightEvents.micState(open = true, why = if (firstOpen) "first open" else "turn handback")
        capturing = true
        // DIAGNOSTIC (B10, remove once root-caused): pairs with the "forwarded N
        // bytes" turn-transcript log - if a turn logs ~0 bytes forwarded, this
        // timestamp shows how long after mic-open the driver's reply was expected,
        // to distinguish "mic opened but recorded nothing" from "mic opened too late".
        Log.d(TAG, "mic open t=${System.currentTimeMillis()} firstOpen=$firstOpen")
        startMic()
    }

    /** Driver-initiated close (tap to stop). */
    fun stop() = closeSession("stopped")

    /**
     * Injects a text turn so Gemini speaks it - used by the proactive engine for
     * openers and health alerts. Gemini replies with audio as if the driver had
     * prompted it.
     */
    /**
     * Returns whether the send was enqueued. False means the socket is gone
     * (null or rejected), which the caller ([beginConversation]) uses to detect a
     * stale "warm" socket and fall back to a cold connect.
     */
    fun sendText(text: String): Boolean {
        val msg = JSONObject().put("clientContent", JSONObject().apply {
            put("turns", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("parts", JSONArray().put(JSONObject().put("text", text)))
            }))
            put("turnComplete", true)
        })
        return webSocket?.send(msg.toString()) ?: false
    }

    /**
     * Returns the result of a [LiveEvent.ToolCall] back to Gemini.
     *
     * Returns whether the send was enqueued, same contract as [sendText] just above: OkHttp's
     * `WebSocket.send` returns false (it does NOT throw) when the socket is already closing or
     * closed, so a caller that only wraps this in try/catch never learns the response was dropped.
     * That matters more here than for `sendText` - a tool response is the one message Gemini is
     * actually WAITING on to close out a mid-turn function call, so a silently-dropped one leaves
     * the model (and the driver-facing UI) wedged with no error and no way to know why. The bool
     * lets the caller ([LiveSessionController.handleToolCall]) treat a false return as a real,
     * user-visible failure instead of a false success.
     */
    fun sendToolResponse(id: String, name: String, response: JSONObject): Boolean {
        val msg = JSONObject().put("toolResponse", JSONObject().apply {
            put("functionResponses", JSONArray().put(JSONObject().apply {
                put("id", id)
                put("name", name)
                put("response", response)
            }))
        })
        return webSocket?.send(msg.toString()) ?: false
    }

    /**
     * Ticket 21 (google-account-integration, "close the remember leak"): read-only window onto
     * [mailToolCalledThisTurn] for `remember`'s dispatch-time gate. The episodic exclusion above
     * already keeps a mail-touched turn out of [com.kevin.legion.data.local.EpisodicTurn], but
     * `remember` is a second, independent writer straight into
     * [com.kevin.legion.data.local.MemoryEntry] that never checked this flag at all - a driver
     * saying "remember that" right after Alfred read an email put mail content into permanent
     * memory, in the same turn [captureEpisodicTurn] was correctly throwing away as unrecordable.
     *
     * [LiveSessionController] is the only caller (its own [handleToolCall] reads this once, right
     * before calling [LiveToolbox.dispatch]) and hands the value in as a plain boolean rather than
     * `dispatch` reaching back into a live session instance - the smallest surface that closes the
     * hole without threading a boolean through every one of `dispatch`'s other branches or every
     * test call site that constructs its own [org.json.JSONObject] args and never touches this at
     * all. See [LiveToolbox.rememberBlockedByReadThroughTool] for why the actual refusal decision
     * lives as its own free function instead of being inlined here or into `dispatch`.
     */
    fun readThroughToolTouchedThisTurn(): Boolean = mailToolCalledThisTurn

    val isActive: Boolean get() = running.get() && !closed.get()

    // --- WebSocket -------------------------------------------------------

    private inner class SocketListener(
        private val systemInstruction: String,
        private val functionDeclarations: JSONArray,
    ) : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            MidnightEvents.sessionStart()
            webSocket.send(buildSetup(systemInstruction, functionDeclarations).toString())
        }

        override fun onMessage(webSocket: WebSocket, text: String) = handleServerMessage(text)

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) =
            handleServerMessage(bytes.utf8())

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "WebSocket failure: ${t.message} (http ${response?.code})", t)
            MidnightEvents.sessionEnd("failure http ${response?.code}: ${t.message}")
            // The HTTP upgrade response carries the real cause on an auth/quota
            // reject, so map it to a stable reason the controller phrases for the
            // driver. Transport failures (no response) keep the exception message.
            closeSession(
                when (response?.code) {
                    400, 401, 403 -> "key rejected"
                    429 -> "quota"
                    else -> t.message ?: "connection failed"
                }
            )
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(NORMAL_CLOSE, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            MidnightEvents.sessionEnd(reason.ifBlank { "closed code $code" })
            closeSession(reason.ifBlank { "closed code $code" })
        }
    }

    private fun buildSetup(systemInstruction: String, functionDeclarations: JSONArray): JSONObject {
        val tools = JSONArray().put(JSONObject().put("googleSearch", JSONObject()))
        if (functionDeclarations.length() > 0) {
            tools.put(JSONObject().put("functionDeclarations", functionDeclarations))
        }

        val setup = JSONObject().apply {
            put("model", MODEL)
            put("generationConfig", JSONObject().apply {
                put("responseModalities", JSONArray().put("AUDIO"))
                put("speechConfig", JSONObject().put(
                    "voiceConfig",
                    JSONObject().put(
                        "prebuiltVoiceConfig",
                        JSONObject().put("voiceName", voiceName.ifBlank { VOICE })
                    )
                ))
            })
            put("systemInstruction", JSONObject().put(
                "parts", JSONArray().put(JSONObject().put("text", systemInstruction))
            ))
            put("tools", tools)
            // Ticket 02 (drive-test-2026-08-18): opt into session resumption on every
            // connection, not just the ones asking to resume something. Gemini Live keeps
            // conversation history in the SOCKET - without this the server never sends
            // SessionResumptionUpdate at all, and a dropped socket has nothing to hand a
            // replacement socket to continue from. Field names verified against
            // ai.google.dev/api/live (fetched 2026-08-19): `sessionResumption.handle` on
            // the request, `sessionResumptionUpdate.{newHandle,resumable}` on the response.
            // An absent/blank `handle` is documented as "then a new session is created" -
            // exactly what a first-ever connection wants.
            put("sessionResumption", JSONObject().apply {
                requestedResumeHandle?.let { if (it.isNotBlank()) put("handle", it) }
            })
            // Ticket 02, candidate 3 (contextWindowCompression): a DIFFERENT ender than the
            // network drop this ticket is chiefly about - this addresses the context window
            // filling up over a long conversation, not a dropped socket. slidingWindow with
            // no explicit triggerTokens uses the server's own default threshold. Included
            // because it costs nothing and closes a second known way a conversation's memory
            // can end, but it does NOT make session resumption unnecessary - a network drop
            // has nothing to do with window size.
            put("contextWindowCompression", JSONObject().put("slidingWindow", JSONObject()))
            // Opt into the driver's input transcript: lets us detect that the
            // driver has started replying (cancels the idle-timeout) and feeds
            // the mic-capture diagnostic log (see userTurnText).
            put("inputAudioTranscription", JSONObject())
            // Zero's own speech transcript, only when the debug subtitle toggle
            // is on (it adds output-transcription tokens, so off by default).
            if (subtitles) put("outputAudioTranscription", JSONObject())
            // Conversation mode lets Gemini's server VAD run turn-taking;
            // speak-only mode disables it (the mic never opens anyway).
            put("realtimeInputConfig", JSONObject().put(
                "automaticActivityDetection",
                JSONObject().apply {
                    put("disabled", !vadMode)
                    if (vadMode) {
                        // Give the driver room to pause mid-sentence before the turn
                        // ends (see VAD_SILENCE_MS). Only in conversation mode - the
                        // speak-only path has no mic and never runs VAD.
                        put("silenceDurationMs", VAD_SILENCE_MS)
                        put("prefixPaddingMs", VAD_PREFIX_PADDING_MS)
                    }
                },
            ))
        }
        return JSONObject().put("setup", setup)
    }

    private fun sendRealtimeInput(body: JSONObject) {
        val ok = webSocket?.send(JSONObject().put("realtimeInput", body).toString()) ?: false
        // Mid-turn the socket can die (dead zone, cloud drop). Detect it here so
        // the driver gets a "connection lost" notice instead of a mic that streams
        // into the void. closeSession is idempotent (guards on the closed flag).
        if (!ok && !closed.get()) closeSession("stale socket")
    }

    private fun handleServerMessage(json: String) {
        val root = try {
            JSONObject(json)
        } catch (e: Exception) {
            Log.w(TAG, "Unparseable server message: ${e.message}")
            return
        }

        // Setup acknowledged - the socket is connected and warm. The controller
        // decides what happens next: begin a conversation (greeting, then the mic
        // opens on the first turnComplete), speak a proactive line, or just stay
        // warm until the driver taps.
        if (root.has("setupComplete")) {
            warm.set(true)
            emit(LiveEvent.Connected)
            return
        }

        root.optJSONObject("serverContent")?.let { handleServerContent(it) }
        root.optJSONObject("toolCall")?.let { handleToolCall(it) }
        // Ticket 02 (drive-test-2026-08-18): both used to be ignored outright, which is
        // half of why a dropped socket silently dropped the conversation's memory with it.
        root.optJSONObject("goAway")?.let { handleGoAway(it) }
        root.optJSONObject("sessionResumptionUpdate")?.let { handleSessionResumptionUpdate(it) }
    }

    /**
     * Ticket 02: the server just confirmed a checkpoint we can resume from. `resumable=false`
     * means resumption is not possible AT THIS EXACT POINT (mid function-call, mid-generation -
     * per ai.google.dev/api/live) and `newHandle` is documented empty in that case - it does
     * NOT mean the conversation itself is lost, just that this particular message isn't a
     * usable checkpoint. Only overwriting [lastResumableHandle] on resumable=true means a
     * resumable=false blip never throws away a good earlier handle still sitting in
     * [LiveSessionController]'s own copy.
     */
    private fun handleSessionResumptionUpdate(update: JSONObject) {
        if (!update.optBoolean("resumable", false)) return
        val handle = update.optString("newHandle")
        if (handle.isBlank()) return
        lastResumableHandle = handle
        emit(LiveEvent.ResumeHandleUpdated(handle))
    }

    /**
     * Ticket 02, candidate 1 (goAway): the server's own advance warning that it is about to
     * disconnect us with `timeLeft` (a protobuf Duration) left. Turns a surprise ABORTED close
     * into a deliberate one - closeSession() here runs our own clean teardown (release the
     * track, restore ducked audio, etc.) BEFORE the server cuts the socket mid-write, and gives
     * [LiveSessionController] a distinct "goAway" reason it can treat as expected rather than a
     * fault, since by the time it fires we already hold whatever [lastResumableHandle] the
     * server has given us to reconnect with.
     *
     * **UNMEASURED as of 2026-08-19.** No live session has run against this code yet, so the
     * actual `timeLeft` value Google sends is unknown - [GOAWAY_RECONNECT_MARGIN_MS] is a
     * guessed safety margin, not a calibrated one. If a real drive shows `timeLeft` is too
     * short to act on (e.g. under a couple of seconds), this whole early-close branch is
     * pointless and should be deleted in favor of just letting the server's own cutoff run
     * through the ordinary onFailure/onClosed path, which already handles it correctly (just
     * without warning). Log the raw value on every occurrence specifically so that
     * measurement can happen from a real logcat pull.
     */
    private fun handleGoAway(goAway: JSONObject) {
        val raw = goAway.opt("timeLeft")
        val ms = parseGoAwayDurationMs(raw)
        Log.w(TAG, "goAway received, timeLeft=$raw (parsed ${ms}ms)")
        CarProbeLog.log("LIVE_GOAWAY", "timeLeft=$raw parsedMs=$ms")
        if (ms == null) return
        val delayMs = ms - GOAWAY_RECONNECT_MARGIN_MS
        if (delayMs <= 0) return
        io.launch {
            delay(delayMs)
            if (running.get() && !closed.get()) closeSession("goAway")
        }
    }

    /**
     * Runs the sec 9.1 crisis backstop over the driver's transcript so far.
     *
     * Deliberately fires on PARTIAL transcript rather than waiting for
     * turnComplete: by the time a turn completes the model has usually already
     * generated and started speaking its reply, and the whole point is to not let
     * the character answer this. Firing early means we flush before or during the
     * first audio chunks.
     *
     * **This does not stop the model generating.** We can't unsay what Gemini has
     * already decided to say; [flushAudio] only drops what's queued locally, and
     * chunks that arrive after this still play unless the owner closes the
     * session. Killing the audio path outright was the alternative and it's worse:
     * it would leave the driver in silence with no idea whether anything heard
     * them. The owner handling [LiveEvent.CrisisDetected] is what actually ends
     * the performance.
     */
    /**
     * Companion-memory ticket 01 (2026-07-22): persists this turn's transcript
     * into the raw [EpisodicTurn] buffer, for ticket 02's consolidation pass to
     * later distill into scored, sec-9.1-categorized [com.kevin.legion.data.local.CompanionMemory]
     * rows. Fire-and-forget on [io] - never blocks the conversation, and a
     * write failure here must not affect the live turn in any way.
     *
     * Skips a blank driver line (nothing to remember) and skips entirely when
     * [episodicSessionId] hasn't been minted yet (start() never ran - shouldn't
     * happen mid-turnComplete, but this is transcript capture, not the
     * conversation itself, so it fails silent rather than throwing).
     *
     * **Also skips the WHOLE turn when [mailToolCalledThisTurn] is set (ticket 15, ticket 07's
     * read-through rule).** Not just the companion half - the driver's own line can be "what's
     * unread in my inbox" and Alfred's reply can literally be a subject line he just read out
     * loud, and either half landing here is a mail-shaped fact leaving the device the moment
     * sync's whole-database backup next runs (commit 7c3822f, 2026-08-12). Dropping the entire
     * turn rather than trying to redact just the mail-bearing half is deliberate: there is no
     * reliable way to tell "the reply that used the mail tool" apart from "the reply that also
     * mentioned something else" from plain transcript text, and the guarantee this rule exists
     * to give is that mail was never stored - not that something remembered to scrub it.
     */
    private fun captureEpisodicTurn(driverText: String, companionText: String) {
        val sessionId = episodicSessionId
        if (sessionId.isBlank() || driverText.isBlank()) return
        if (mailToolCalledThisTurn) return
        io.launch {
            try {
                val dao = CarDatabase.getDatabase(appContext).episodicTurnDao()
                val vehicleId = ActiveVehicle.current(appContext)
                val now = System.currentTimeMillis()
                dao.insert(EpisodicTurn(
                    sessionId = sessionId, vehicleId = vehicleId,
                    role = EpisodicTurn.Role.DRIVER, text = driverText, timestamp = now,
                ))
                if (companionText.isNotBlank()) {
                    dao.insert(EpisodicTurn(
                        sessionId = sessionId, vehicleId = vehicleId,
                        role = EpisodicTurn.Role.COMPANION, text = companionText, timestamp = now,
                    ))
                }
            } catch (e: Exception) {
                Log.w(TAG, "episodic turn capture failed: ${e.message}")
            }
        }
    }

    private fun checkForCrisis() {
        if (crisisFiredThisTurn) return
        if (!CrisisDetector.detect(userTurnText.toString())) return
        crisisFiredThisTurn = true
        Log.w(TAG, "Crisis phrase detected in transcript - flushing audio, notifying owner")
        flushAudio()
        speakingThisTurn = false
        emit(LiveEvent.CrisisDetected)
    }

    private fun handleServerContent(content: JSONObject) {
        content.optJSONObject("inputTranscription")?.optString("text")?.let {
            if (it.isNotEmpty()) {
                idleJob?.cancel() // driver is talking - cancel any pending auto-close
                userTurnText.append(it)
                checkForCrisis()
            }
        }

        // Zero's own speech, accumulated for the full-turn transcript log but
        // emitted live as only the most recent tail - the caption UI
        // (CruiseScreen/LightsOutScreen) renders a fixed couple of lines with
        // no scroll, so showing the whole growing turn text made it look
        // "frozen" on the first sentence once the box's line cap was hit,
        // while Zero kept talking well past it (session-16 bug B4). A
        // rolling tail always shows what he's saying right now instead.
        if (subtitles) {
            content.optJSONObject("outputTranscription")?.optString("text")?.let {
                if (it.isNotEmpty()) {
                    companionTurnText.append(it)
                    emit(LiveEvent.Subtitle(captionTail(companionTurnText)))
                }
            }
        }

        content.optJSONObject("modelTurn")?.optJSONArray("parts")?.let { parts ->
            // Snapshot ONCE per incoming message, right before touching any audio chunk
            // it carries - see [turnGeneration]'s declaration doc for the race this
            // closes. Anything above this point in the function (crisis detection) may
            // itself have already bumped the counter for THIS message; that's fine and
            // intended, it just means this message's own chunks are compared against
            // the post-bump value, which they'll match (nothing external can have
            // bumped it again in between).
            val myTurnGeneration = turnGeneration.get()
            for (i in 0 until parts.length()) {
                val data = parts.optJSONObject(i)?.optJSONObject("inlineData")?.optString("data")
                if (!data.isNullOrEmpty()) {
                    if (myTurnGeneration != turnGeneration.get()) {
                        // Superseded: a barge-in/interrupt/crisis flush landed between this
                        // message being sent by the server and being processed here. Drop
                        // the chunk outright - do not flip speakingThisTurn or capturing
                        // (that would re-close a mic the driver just opened by tapping) and
                        // do not enqueue it for playback (that would speak the tail of a
                        // reply the driver already interrupted).
                        continue
                    }
                    if (!speakingThisTurn) {
                        speakingThisTurn = true
                        // Half-duplex: mute the mic while Zero speaks so his own
                        // voice (through the head-unit speakers) isn't captured and
                        // mistaken by the server VAD for the driver interrupting.
                        if (vadMode) {
                            capturing = false
                            MidnightEvents.micState(open = false, why = "half-duplex: companion speaking")
                        }
                        duckNow()
                        emit(LiveEvent.SpeakingStarted)
                    }
                    // DIAGNOSTIC (B12, remove once root-caused): timestamp each audio
                    // chunk as it arrives off the socket, paired with the write-return
                    // log in playAudio, to catch whether a chunk is arriving late
                    // (server-side) or being written late (local AudioTrack stall) -
                    // now more informative than before, since a gap here directly
                    // shows the *consumer* stalling rather than possibly being masked
                    // by the write running inline on this same callback.
                    Log.d(TAG, "audio chunk recv t=${System.currentTimeMillis()} bytes=${data.length}")
                    // Enqueue rather than call playAudio() directly here - see
                    // audioQueue's declaration comment.
                    audioQueue.trySend(Base64.decode(data, Base64.DEFAULT))
                }
            }
        }

        if (content.optBoolean("interrupted")) {
            flushAudio()
            speakingThisTurn = false
            emit(LiveEvent.Interrupted)
        }
        if (content.optBoolean("turnComplete")) {
            speakingThisTurn = false
            crisisFiredThisTurn = false
            val heard = userTurnText.toString().trim()
            Log.d(TAG, "Turn transcript: \"$heard\" (forwarded $bytesThisTurn bytes)")
            // Also a Crashlytics breadcrumb: logcat is blocked on the head unit
            // (§14), so a logcat-only diagnostic is invisible in the one place
            // the bug is reported from. Debug-only - it carries the driver's speech.
            if (BuildConfig.DEBUG) MidnightEvents.voiceTurn(heard, bytesThisTurn)
            // Force this turn to surface as a retrievable Crashlytics non-fatal if the driver was
            // actually listened to (vadMode = a real conversational turn, not a proactive/
            // onboarding line) but almost nothing was forwarded - a plain breadcrumb alone never
            // gets pulled without a triggering event (see silentMicTurn's own doc for why the
            // previous breadcrumb-only version left the last two field reports with zero data).
            if (vadMode && bytesThisTurn < SILENT_TURN_BYTES_THRESHOLD) {
                MidnightEvents.silentMicTurn(heard, bytesThisTurn)
            }
            captureEpisodicTurn(heard, companionTurnText.toString().trim())
            mailToolCalledThisTurn = false
            userTurnText.setLength(0)
            companionTurnText.setLength(0)
            emit(LiveEvent.TurnComplete)

            // DIAGNOSTIC (B9/B10/B12, remove once root-caused): the exact flags this
            // branch decision is made from, so a field-test log pull shows which path
            // fired for a given "listened when it shouldn't have" / "didn't hear my
            // reply" report instead of us guessing after the fact.
            val decision = "suppressMicNextTurn=$suppressMicNextTurn vadMode=$vadMode " +
                "conversationActive=$conversationActive warm=${warm.get()}"
            Log.d(TAG, "turnComplete decision: $decision")
            MidnightEvents.voiceTurnDecision(decision)

            when {
                // A proactive line spoken on a warm socket: Zero is done, park
                // the socket warm again (no mic, music back up) rather than listen.
                suppressMicNextTurn -> {
                    suppressMicNextTurn = false
                    parkWarm()
                }
                // Conversation: hand the mic back and wait for the driver's reply, and
                // stay ducked through the chat.
                //
                // **No conversational timeout here (Kevin, 2026-08-18).** This branch used
                // to arm a 10s one, and a quiet spell parked the socket, stopped the mic
                // and put the strip back to "Tap to talk" with nothing said about it. On a
                // drive that is a normal pause - a mirror check, a merge, finishing a
                // thought - so the conversation appeared to "drop after 3 turns". The
                // identical failure had already been found once during onboarding and
                // fixed with the per-session [idleTimeoutMs] override (see its own
                // comment); no caller ever passed one, so every conversation still ran on
                // 10 seconds.
                //
                // What is armed instead is [armConversationBackstop] - thirty minutes,
                // which is a forgotten-conversation cap rather than a timeout, and which
                // announces itself. A conversation otherwise ends only when the driver
                // ends it (tapping the strip: LiveSessionController.onTap's
                // `inConversation -> stop()`), when the socket dies (which flashes a
                // notice), or on the crisis path.
                vadMode -> {
                    openMicForUser()
                    armConversationBackstop()
                }
                // Cold speak-only proactive: bring music back, then close shortly.
                else -> {
                    restoreAudio()
                    armIdleTimeout()
                }
            }
        }
    }

    /**
     * The last [CAPTION_TAIL_CHARS] of [sb], snapped forward to the next word
     * boundary so the visible caption never starts mid-word.
     */
    private fun captionTail(sb: StringBuilder): String {
        if (sb.length <= CAPTION_TAIL_CHARS) return sb.toString()
        val start = sb.length - CAPTION_TAIL_CHARS
        val spaceAt = sb.indexOf(" ", start)
        val wordStart = if (spaceAt in start until sb.length) spaceAt + 1 else start
        return sb.substring(wordStart)
    }

    private fun handleToolCall(toolCall: JSONObject) {
        val calls = toolCall.optJSONArray("functionCalls") ?: return
        for (i in 0 until calls.length()) {
            val call = calls.optJSONObject(i) ?: continue
            val name = call.optString("name")
            // Ticket 15: flag the whole turn as mail-shaped the moment the ASK arrives, not
            // once dispatch returns - captureEpisodicTurn (below) checks this on turnComplete,
            // which can land before the owner's own dispatch/response round trip finishes in
            // some orderings, and the exclusion must hold regardless of that race.
            if (isEpisodicExcludedTool(name)) mailToolCalledThisTurn = true
            emit(LiveEvent.ToolCall(
                id = call.optString("id"),
                name = name,
                args = call.optJSONObject("args") ?: JSONObject(),
            ))
        }
    }

    // --- Microphone capture ---------------------------------------------

    private fun startMic() {
        if (micJob?.isActive == true) return
        micJob = io.launch { micLoop() }
    }

    @Suppress("MissingPermission") // checked explicitly below, not just assumed from startup
    private suspend fun micLoop() {
        // AudioRecord's constructor throws SecurityException immediately if
        // RECORD_AUDIO isn't granted - it used to be assumed granted "at
        // startup" with no check here, which crashed uncaught (inside a
        // launched coroutine, so it took the whole session/service with it)
        // for any driver who denied the mic permission. A denial is a
        // legitimate, common outcome now that voice setup is fully optional
        // (2026-07-08) - it must degrade to "can't listen" rather than crash.
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "micLoop: RECORD_AUDIO not granted, closing session instead of capturing")
            closeSession("microphone permission not granted")
            return
        }

        // Give anything else that may have briefly held the mic a moment to
        // release it before we open ours. Only needed for the first capture of a
        // session; on later turns the delay would just clip the start of the
        // driver's speech.
        if (!micHandedOff) {
            delay(MIC_HANDOFF_MS)
            micHandedOff = true
        }

        val minBuf = AudioRecord.getMinBufferSize(
            INPUT_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        // Ticket 15 (.scratch/android-auto/issues/15-the-live-session-can-be-silenced.md):
        // VOICE_RECOGNITION (the old source) is not on Android's privacy-sensitive list,
        // so when another app opens a privacy-sensitive capture - the Android Auto
        // Assistant on "Hey Google" is the motivating case - the platform silences ours
        // with no exception and no callback of its own; see the class doc and the ticket
        // for the citation trail. VOICE_COMMUNICATION *is* privacy-sensitive, so an
        // ordinary Assistant capture cannot preempt ours once we're already running, and
        // it also carries platform echo cancellation (the ticket's research Q4), which
        // the car surface wants anyway since Zero speaks through the car's own speakers
        // while still listening. setPrivacySensitive(true) (API 30+) is the
        // belt-and-braces version of the same guarantee, stated explicitly rather than
        // inferred from the source constant - AudioRecord.Builder is used instead of the
        // legacy constructor specifically so this call has somewhere to attach.
        //
        // This is a real behavioural change to the core voice path, not a car-only one:
        // every phone session goes through this same micLoop. Kept surgical - sample
        // rate/channel/format are unchanged (still INPUT_RATE/CHANNEL_IN_MONO/
        // ENCODING_PCM_16BIT), only the source and the privacy flag move - but that is a
        // traced-safe claim about what changed, not a tested-safe claim about how the
        // phone call path behaves with it; unverified until a real session runs.
        val format = AudioFormat.Builder()
            .setSampleRate(INPUT_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .build()
        val recordBuilder = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            .setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(minBuf, INPUT_RATE)) // >= ~0.5s headroom
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            recordBuilder.setPrivacySensitive(true)
        }
        // var, not val: on AudioRecord.ERROR_DEAD_OBJECT below, the read loop rebuilds this
        // once from the same recordBuilder rather than crashing capture outright for the
        // rest of the session. The recordingCallback closure just below and the effects
        // list further down both read `record`/`audioSessionId` dynamically at the point
        // they're used, so a reassignment here is picked up by both without any further
        // change - see the read-loop's error branch for where the reassignment happens.
        var record = recordBuilder.build()
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.w(TAG, "AudioRecord init failed")
            record.release()
            closeSession("microphone unavailable")
            return
        }
        CarProbeLog.log(
            "MIC_SOURCE",
            "capture opened: source=VOICE_COMMUNICATION privacySensitive=" +
                "${Build.VERSION.SDK_INT >= Build.VERSION_CODES.R} " +
                "sessionId=${record.audioSessionId} state=${record.state}",
        )
        Log.d(TAG, "AudioRecord opened: source=VOICE_COMMUNICATION sessionId=${record.audioSessionId}")

        // Ticket 15: the platform silences a losing capture with no exception and
        // no AudioRecord state change, so the only way to know it happened is to
        // ask AudioManager directly. isClientSilenced() needs API 29; below that
        // there is no equivalent signal at all, and _isSilenced simply stays
        // false (a real gap, logged once below rather than pretended away).
        var recordingCallback: AudioManager.AudioRecordingCallback? = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val callback = object : AudioManager.AudioRecordingCallback() {
                override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
                    val ours = configs.firstOrNull { it.clientAudioSessionId == record.audioSessionId }
                    val silenced = ours?.isClientSilenced ?: false
                    if (silenced != _isSilenced.value) {
                        _isSilenced.value = silenced
                        val message = if (silenced) {
                            "capture silenced by platform arbitration (another privacy-sensitive " +
                                "capture is active, sessionId=${record.audioSessionId})"
                        } else {
                            "capture no longer silenced (sessionId=${record.audioSessionId})"
                        }
                        CarProbeLog.log("MIC_SILENCED", message)
                        Log.w(TAG, message)
                    }
                }
            }
            recordingCallback = callback
            audioManager.registerAudioRecordingCallback(callback, Handler(Looper.getMainLooper()))
        } else {
            CarProbeLog.log(
                "MIC_SILENCED",
                "isClientSilenced() needs API 29 (device is ${Build.VERSION.SDK_INT}); " +
                    "silencing cannot be detected on this device",
            )
        }

        // Cancel the device's own speaker output (Maps turn-by-turn guidance,
        // music, and Zero's own TTS) out of the captured signal, so the open
        // mic doesn't feed that audio back to Gemini as if it were the driver.
        // Without this, once navigation is running its continuous voice
        // guidance bleeds into every captured turn. Both effects are
        // hardware-dependent; skip them where unavailable.
        // var alongside `record` above: a dead-object recreation tears down and rebuilds
        // this list too, since the old effects were bound to the OLD AudioRecord's session
        // id and are meaningless (and leak-risk) once that record is released.
        var effects = if (isEmulator) {
            Log.w(TAG, "Emulator detected: skipping hardware voice effects, and note the " +
                "virtual mic may replay the same host audio every turn - validate on real hardware.")
            emptyList()
        } else {
            attachVoiceEffects(record.audioSessionId)
        }

        // Raw little-endian PCM16 bytes, sent base64 in ~100 ms chunks.
        val buffer = ByteArray(CHUNK_BYTES)
        // The AudioRecord object lives for the whole session (avoids the
        // open/reopen race that used to drop a turn's audio), but its HAL capture
        // stream is only ACTIVE while we're capturing. Between turns we stop it so
        // playback runs solo: many devices - and the emulator's audio HAL in
        // particular - cannot run capture and playback at once, and an always-on
        // mic makes every output write fail, so Zero's reply is silent. Idling
        // the mic while he speaks is what lets him be heard.
        var recording = false
        // Set true the first time this loop actually starts capturing (which
        // already paid the MIC_HANDOFF_MS wait above, before the loop began).
        // Distinguishes "this session's very first mic-open" from "turn 2+
        // re-open", so the settle delay below only applies to the latter.
        var everCaptured = false
        // Bytes forwarded and wall-clock time since the CURRENT record.startRecording()
        // call, as opposed to [bytesThisTurn] (bytes since [openMicForUser], which a
        // successful retry deliberately does NOT reset - see the short-capture block
        // below). Both reset every time recording actually (re)starts, first-open or
        // retry alike.
        var recordingStartedAtMs = 0L
        var bytesSinceOpen = 0L
        // Caps the ERROR_DEAD_OBJECT recreation below at once per SESSION (not per turn,
        // unlike [retriedThisTurn] above) - a dead AudioRecord is a hardware/driver-level
        // failure, not a VAD hiccup, and micLoop itself only runs once per session (see
        // [startMic]'s isActive guard), so this local naturally scopes to that lifetime.
        var recreatedRecordOnce = false
        try {
            while (isActive && running.get()) {
                if (webSocket == null) break

                if (!capturing) {
                    if (recording) {
                        // Short-capture retry (2026-08-17): decide BEFORE stopping the
                        // record, while we still have this capture's own numbers. See
                        // [retriedThisTurn]'s declaration for the measured shape this
                        // catches - vadMode is required because capturing only ever
                        // reads true via [openMicForUser], which only runs in
                        // conversation mode; a speak-only proactive line never opens
                        // the mic at all, so there is nothing here to retry.
                        val heldMs = System.currentTimeMillis() - recordingStartedAtMs
                        val short = vadMode &&
                            heldMs < SHORT_CAPTURE_RETRY_WINDOW_MS &&
                            bytesSinceOpen < SILENT_TURN_BYTES_THRESHOLD
                        if (short && !retriedThisTurn) {
                            // Treat this as a false end-of-turn rather than a real
                            // answer: reopen right here, bypassing the normal
                            // turnComplete -> openMicForUser handback, so the driver
                            // gets a second window with no round trip through
                            // whatever short reply the model may already be
                            // generating. capturing is forced back on directly
                            // (rather than waiting for the server) because there is
                            // no server-side "undo the end-of-turn" message to send -
                            // this is a purely local mitigation, and a legitimate
                            // half-duplex mute (the model actually starting to speak)
                            // will simply reassert capturing=false again on its own
                            // the moment real model audio arrives.
                            retriedThisTurn = true
                            Log.w(TAG, "micLoop: short capture (${bytesSinceOpen}b in " +
                                "${heldMs}ms) - reopening mic once rather than accepting " +
                                "as a heard turn")
                            CarProbeLog.log(
                                "MIC_SHORT_CAPTURE",
                                "reopening once: bytes=$bytesSinceOpen heldMs=$heldMs",
                            )
                            // SENIOR REVIEW FIX (2026-08-17): the bogus reply itself must be
                            // discarded before the mic reopens, exactly like the barge-in path
                            // in beginConversation() - otherwise it is still queued on
                            // audioQueue / playing on the AudioTrack, the reopened mic captures
                            // Zero's own voice (the half-duplex violation this file's mic-open
                            // comment above warns about), and that can re-trigger the same
                            // false-VAD pattern with the one retry already spent. flushAudio()
                            // also bumps turnGeneration, which is correct here too: the bogus
                            // reply's remaining chunks SHOULD be staled out by the same
                            // mechanism a driver's barge-in tap uses.
                            flushAudio()
                            speakingThisTurn = false
                            capturing = true
                            recordingStartedAtMs = System.currentTimeMillis()
                            bytesSinceOpen = 0L
                            continue // recording is still true; skip straight to reading
                        }
                        if (short && retriedThisTurn) {
                            // The retry ALSO came up short - this is no longer "maybe
                            // noise", it's a capture that cannot hold on to a real
                            // driver turn twice in a row. Accept the model's reply
                            // this time (retrying again risks the loop the ticket
                            // explicitly ruled out) but say so, rather than let the
                            // driver think they were heard when they weren't.
                            Log.w(TAG, "micLoop: retry also came up short " +
                                "(${bytesSinceOpen}b in ${heldMs}ms) - accepting and notifying")
                            CarProbeLog.log(
                                "MIC_SHORT_CAPTURE",
                                "retry also short: bytes=$bytesSinceOpen heldMs=$heldMs",
                            )
                            CompanionPhase.showNotice("DIDN'T CATCH THAT - TRY AGAIN")
                        }
                        try { record.stop() } catch (_: Exception) {}
                        recording = false
                        // Physical close - see [LiveEvent.MicClosed]'s doc for why this is a
                        // separate signal from the capturing=false flip that led here (that
                        // flip is the INTENT, this is the platform call actually returning).
                        emit(LiveEvent.MicClosed(if (short) "short capture, giving up" else "half-duplex mute"))
                    }
                    delay(20) // idle, leaving the HAL free for playback
                    continue
                }

                // Turn is active: make sure the mic is running. A fresh start also
                // discards any pre-roll buffer (a backgrounded device can replay
                // its last buffer), so honor flushCapture by restarting too.
                if (!recording || flushCapture) {
                    flushCapture = false
                    try {
                        if (recording) record.stop()
                        // B10/B12 (2026-07-23): turn 1's mic-open already waited
                        // MIC_HANDOFF_MS above, before this loop began. Every
                        // later re-open (turn 2+) goes straight from Zero's own
                        // playback into AudioRecord.startRecording(). The earlier
                        // fix here was a fixed 120ms guess, and a field drive still
                        // reported "sometimes it hears me, sometimes it doesn't" on
                        // the second turn - because the real tail is up to the
                        // AudioTrack's ~0.5s buffer, so 120ms was several times too
                        // short. Wait for the playback head to actually catch up
                        // instead of guessing (see awaitPlaybackDrained).
                        if (everCaptured) awaitPlaybackDrained()
                        record.startRecording()
                        everCaptured = true
                        recording = true
                        recordingStartedAtMs = System.currentTimeMillis()
                        bytesSinceOpen = 0L
                        // Physical open, fired AFTER startRecording() actually returns - the
                        // whole point of this event is that it lags the capturing=true intent
                        // flip by however long the awaitPlaybackDrained() wait above just took.
                        // See [LiveEvent.MicOpened]'s doc.
                        emit(LiveEvent.MicOpened)
                    } catch (e: Exception) {
                        Log.w(TAG, "Capture start/flush failed: ${e.message}")
                        delay(20)
                        continue
                    }
                }

                val n = record.read(buffer, 0, buffer.size)
                if (n < 0) {
                    // record.read() returns a negative AudioRecord.ERROR_* constant on
                    // failure, not a byte count - ERROR_INVALID_OPERATION (-3), ERROR_BAD_VALUE
                    // (-2), ERROR_DEAD_OBJECT (-6), and plain ERROR (-1) were previously
                    // indistinguishable here from "n == 0, no data arrived yet", and
                    // (unlike the `!capturing` branch above, which delays 20ms) this path had
                    // NO delay at all - a dead AudioRecord busy-loops silently forever,
                    // burning a core and producing nothing anyone would ever see or hear.
                    val errorName = when (n) {
                        AudioRecord.ERROR_INVALID_OPERATION -> "ERROR_INVALID_OPERATION"
                        AudioRecord.ERROR_BAD_VALUE -> "ERROR_BAD_VALUE"
                        AudioRecord.ERROR_DEAD_OBJECT -> "ERROR_DEAD_OBJECT"
                        AudioRecord.ERROR -> "ERROR"
                        else -> "unknown($n)"
                    }
                    Log.w(TAG, "micLoop: record.read failed: $errorName")
                    CarProbeLog.log("MIC_READ_ERROR", "record.read failed: $errorName")
                    if (n == AudioRecord.ERROR_DEAD_OBJECT && !recreatedRecordOnce) {
                        // The HAL/driver dropped the object out from under us - stopping and
                        // re-starting the SAME AudioRecord instance won't help (it's the
                        // instance itself that's dead), so build a fresh one from the same
                        // recordBuilder config instead. One attempt only: if the platform is
                        // handing out dead AudioRecords, a second one is unlikely to fare
                        // better, and this must not become the busy-loop it's fixing.
                        recreatedRecordOnce = true
                        Log.w(TAG, "micLoop: AudioRecord died, recreating once")
                        CarProbeLog.log("MIC_DEAD_OBJECT", "recreating AudioRecord once")
                        // SENIOR REVIEW FIX (2026-08-17): emit the physical close BEFORE
                        // tearing the dead record down. This stop reaches no other MicClosed
                        // site - it is not the `!capturing` branch's own emit (capturing is
                        // still true here; the record just stopped answering), so without
                        // this the rebuilt record's MicOpened below would be unbalanced
                        // against no matching close, which the commit that introduced
                        // MicOpened/MicClosed promised never happens for a real stop.
                        if (recording) emit(LiveEvent.MicClosed("AudioRecord dead, recreating"))
                        recording = false
                        runCatching { record.stop() }
                        runCatching { record.release() }
                        effects.forEach { runCatching { it.release() } }
                        val fresh = recordBuilder.build()
                        if (fresh.state == AudioRecord.STATE_INITIALIZED) {
                            record = fresh
                            effects = if (isEmulator) emptyList() else attachVoiceEffects(record.audioSessionId)
                            // recording is already false (set above) so the top of the loop
                            // goes through the normal startRecording() path again (with its
                            // own awaitPlaybackDrained() settle and its own MicOpened emit)
                            // rather than assuming the new object is instantly ready to read
                            // from.
                        } else {
                            Log.w(TAG, "micLoop: AudioRecord recreation also failed, closing session")
                            fresh.release()
                            closeSession("microphone unavailable")
                            break
                        }
                    }
                    delay(RECORD_READ_ERROR_BACKOFF_MS)
                    continue
                }
                if (n == 0) continue
                // capturing may have flipped false during the blocking read; only
                // forward audio that belongs to an active turn.
                if (!capturing) continue
                bytesThisTurn += n
                bytesSinceOpen += n
                val b64 = Base64.encodeToString(buffer, 0, n, Base64.NO_WRAP)
                sendRealtimeInput(JSONObject().put(
                    "audio",
                    JSONObject().put("mimeType", INPUT_MIME).put("data", b64)
                ))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Mic loop error: ${e.message}")
        } finally {
            recordingCallback?.let { audioManager.unregisterAudioRecordingCallback(it) }
            _isSilenced.value = false
            // A stop reached from HERE (loop broke via `while` condition going false, or an
            // uncaught exception in the try block above) means capture ended WITHOUT going
            // through the normal `!capturing` branch's own MicClosed emit - exactly the
            // "keeps claiming Listening when capture silently dies" case this event exists
            // to close. Emit before stopping, not after: [record] may already be in a state
            // where `stop()` throws, and the owner still needs to know capture is over.
            if (recording) emit(LiveEvent.MicClosed("mic loop ending"))
            try {
                if (recording) record.stop()
            } catch (_: Exception) {
            }
            record.release()
            effects.forEach { it.release() }
        }
    }

    /**
     * Enables the hardware acoustic echo canceler and noise suppressor on the
     * given capture session, where the device supports them. Returns the
     * created effects so the caller can release them with the AudioRecord.
     */
    private fun attachVoiceEffects(sessionId: Int): List<android.media.audiofx.AudioEffect> {
        val effects = mutableListOf<android.media.audiofx.AudioEffect>()
        try {
            if (AcousticEchoCanceler.isAvailable()) {
                AcousticEchoCanceler.create(sessionId)?.also { it.enabled = true; effects.add(it) }
            }
            if (NoiseSuppressor.isAvailable()) {
                NoiseSuppressor.create(sessionId)?.also { it.enabled = true; effects.add(it) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Voice effects unavailable: ${e.message}")
        }
        return effects
    }

    // --- Audio playback --------------------------------------------------

    /**
     * The playback track, building it on first use. **Null once playback is closed** - see
     * [playbackClosed] for why a post-teardown caller must never be handed a fresh track.
     */
    private fun ensureTrack(): AudioTrack? = synchronized(trackLock) { ensureTrackLocked() }

    /** Must be called holding [trackLock]. */
    private fun ensureTrackLocked(): AudioTrack? {
        if (playbackClosed) return null
        audioTrack?.let { return it }
        return buildTrackLocked()
    }

    /** Must be called holding [trackLock]. */
    private fun buildTrackLocked(): AudioTrack {
        val minBuf = AudioTrack.getMinBufferSize(
            OUTPUT_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(OUTPUT_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuf, OUTPUT_RATE)) // ~0.5s buffer
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        // Keep Zero's own track at full gain; ducking is handled cooperatively
        // via audio focus (see duckNow), never by lowering the shared stream this
        // track plays on.
        track.setVolume(AudioTrack.getMaxVolume())
        track.play()
        audioTrack = track
        // A fresh track's playback head starts at 0, so the counter it's compared
        // against has to start there too. Not reachable today (releaseTrack only
        // runs on terminal close, and each session gets a new instance), but if a
        // stale count ever survived into a new track, awaitPlaybackDrained would
        // never see the head catch up and every mic re-open would eat its full
        // 1.5s timeout - a silent 1.5s of deafness per turn.
        framesWritten = 0
        return track
    }

    private fun playAudio(data: ByteArray) {
        // Acquire-and-start under the lock, as one step. Reading the track and then starting it as
        // two separate steps is precisely the crash this fixes - see [trackLock].
        val track = synchronized(trackLock) {
            val t = ensureTrackLocked() ?: return
            try {
                if (t.playState != AudioTrack.PLAYSTATE_PLAYING) t.play()
            } catch (_: IllegalStateException) {
                // Lost the race anyway (released between the null check and play()). Dropping the
                // chunk is correct: the only reason this track is gone is that the driver
                // interrupted or the session closed, and both mean stop talking.
                return
            }
            t
        }
        var offset = 0
        while (offset < data.size) {
            // write() blocks when the output buffer is full, so it stays outside the lock (a
            // barge-in must not wait out ~0.5s of buffered speech). Re-check identity each pass
            // instead: if the track was released or swapped while we were blocked, this chunk
            // belongs to a turn that no longer exists and must not be written to its replacement.
            if (audioTrack !== track) break
            val written = try {
                track.write(data, offset, data.size - offset)
            } catch (_: IllegalStateException) {
                break // released mid-write - same barge-in/teardown case as above
            }
            if (written <= 0) break // paused/flushed (barge-in) or error
            offset += written
        }
        // Frames handed to the track, NOT frames actually heard - write() returns as
        // soon as the data is accepted into the buffer. awaitPlaybackDrained compares
        // this against the playback head to tell when Zero has really stopped talking.
        framesWritten += offset / BYTES_PER_FRAME
        // DIAGNOSTIC (B12, remove once root-caused): pairs with the "audio chunk
        // recv" log - a large gap between recv and this write-complete timestamp
        // means the AudioTrack write blocked (stalled hardware/focus), which would
        // explain a reply only "arriving" once something later (e.g. the next tap)
        // unblocks the track and it drains its backlog.
        Log.d(TAG, "audio chunk write-complete t=${System.currentTimeMillis()} " +
            "wrote=$offset/${data.size} playState=${track.playState}")
    }

    /** Barge-in: drop any audio still queued so Zero stops mid-sentence. */
    private fun flushAudio() {
        // Bump the generation FIRST, before anything else - see [turnGeneration]'s
        // declaration doc. Any modelTurn message still in flight on the socket
        // callback thread snapshots this counter when it arrives; bumping it here
        // guarantees that snapshot can never match again, so a stale chunk from the
        // turn being flushed is recognized as stale the moment it shows up, however
        // soon after this call that is. incrementAndGet(), not a plain read-modify-
        // write - flushAudio() is called from more than one thread (the socket
        // callback thread and the mic coroutine) and a lost increment here would
        // silently undo the whole point of this counter.
        turnGeneration.incrementAndGet()
        // Discard chunks not yet handed to the AudioTrack too, not just what's
        // already in its hardware buffer - otherwise stale queued audio could
        // still play out after the "interruption" once the consumer catches up.
        while (audioQueue.tryReceive().isSuccess) { /* drain */ }
        synchronized(trackLock) {
            audioTrack?.let { track ->
                try {
                    track.pause()
                    track.flush()
                    track.play()
                    // flush() resets the playback head to 0, so the frame counter it's
                    // compared against has to reset with it or awaitPlaybackDrained
                    // would wait for frames that will never play.
                    framesWritten = 0
                } catch (_: Exception) {
                }
            }
        }
    }

    /**
     * Suspends until Zero's audio has actually finished coming out of the speaker,
     * or [PLAYBACK_DRAIN_TIMEOUT_MS] passes.
     *
     * B10/B12 root cause (2026-07-23, replacing the earlier fixed-delay guess): the
     * server's `turnComplete` means "the model finished SENDING", not "the driver
     * has stopped hearing it". [playAudio] writes into a MODE_STREAM [AudioTrack]
     * with a ~0.5s buffer and `write()` returns as soon as the data is *accepted*,
     * so at `turnComplete` there can still be up to half a second of speech queued,
     * plus whatever is still sitting in [audioQueue]. Re-opening the mic then means
     * capture starts while playback is live - which on a head unit that can't do
     * concurrent capture+playback corrupts or drops the start of the driver's reply.
     * That is the "sometimes it hears me, sometimes it doesn't" report, and why it
     * got worse after longer replies (more audio left to drain).
     *
     * Comparing the playback head against frames written waits for the real tail
     * instead of guessing a constant, and it self-corrects: [framesWritten] keeps
     * growing while the consumer is still feeding the track, so this also covers
     * "chunks not handed over yet" without a second condition. The timeout keeps a
     * stalled track from wedging the mic shut forever.
     *
     * head >= framesWritten IS NOT "drained" ON ITS OWN (2026-08-17, measured defect):
     * [framesWritten] only advances inside [playAudio], AFTER a chunk is dequeued from
     * [audioQueue] and written to the track. Model-turn audio streams in as separate
     * WebSocket messages (see `handleServerContent`'s modelTurn branch), each one just
     * `trySend`-ed onto that channel - so there is a real window where the consumer has
     * caught up to everything it has been HANDED so far (head >= framesWritten holds) while
     * a later chunk is still sitting unconsumed in the channel, having arrived off the
     * socket but not yet reached [playAudio]. Comparing only head-vs-written treats that
     * window as "drained": the mic reopens, `record.startRecording()` runs, and the tail of
     * Zero's own reply plays out AFTER capture has already started - on a phone speaker
     * that is exactly the reopen-then-sub-second-close shape this file's mic-loop measured
     * (dumpsys appops RECORD_AUDIO history: healthy turns 15367/13256/9171/5582/4374 ms vs
     * bad turns 964/991/663/276 ms). The server VAD hears the assistant's own tail through
     * the open mic, treats it as a short complete utterance, and replies before the driver
     * ever gets a word in. The queue itself has no cheap "did I miss a send" signal, so
     * checking [Channel.isEmpty] on every poll (in addition to the head check, not instead
     * of it - the head check is still what proves the LAST written chunk has actually left
     * the speaker) is the fix: drained now means both "nothing left to write" AND "nothing
     * written has yet to play."
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun awaitPlaybackDrained() {
        val deadline = System.currentTimeMillis() + PLAYBACK_DRAIN_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            // Read the head under the lock (2026-08-11, same fix as [playAudio]): this loop runs on
            // the mic coroutine while teardown can release the track from another thread, so
            // reading `audioTrack` and then querying it as two steps is the same torn
            // read-then-use that crashed playback. Returning null here breaks the wait, which is
            // the right answer - a released track has nothing left to drain.
            val head = synchronized(trackLock) {
                val track = audioTrack ?: return@synchronized null
                if (track.playState != AudioTrack.PLAYSTATE_PLAYING) return@synchronized null
                // Head position is frames since play()/flush(); mask so a wrap past
                // Int.MAX_VALUE reads as a large positive rather than negative.
                try {
                    track.playbackHeadPosition.toLong() and 0xFFFF_FFFFL
                } catch (_: IllegalStateException) {
                    null
                }
            } ?: break
            // Both conditions, not either: the head check proves the track has finished
            // PLAYING everything it was given; the queue check proves nothing is waiting to
            // be given to it. Either alone is exactly the gap measured above.
            if (head >= framesWritten && audioQueue.isEmpty) break
            delay(PLAYBACK_DRAIN_POLL_MS)
        }
        // Small settle after the last sample lands - the HAL needs a beat to release
        // the output path before capture opens on units that share it.
        delay(MIC_REOPEN_SETTLE_MS)
    }

    private fun releaseTrack() = synchronized(trackLock) {
        // Set the flag BEFORE releasing: a playback coroutine that is between chunks must see
        // "closed" rather than a null track it would rebuild for a dead session ([playbackClosed]).
        playbackClosed = true
        audioTrack?.let { track ->
            try {
                track.pause()
                track.flush()
                track.release()
            } catch (_: Exception) {
            }
        }
        audioTrack = null
    }

    /**
     * Reopens playback for a new session, undoing [releaseTrack]'s latch.
     *
     * Deliberately NOT folded into [ensureTrack]: if reopening were a side effect of asking for a
     * track, a stale coroutine from the previous session could reopen playback simply by draining
     * one more queued chunk, which is the exact thing [playbackClosed] exists to prevent. Only
     * starting a session may clear it.
     */
    private fun reopenPlayback() = synchronized(trackLock) {
        playbackClosed = false
    }

    // --- Idle auto-close -------------------------------------------------

    /**
     * Close a **speak-only** session if nothing follows within [idleTimeoutMs].
     *
     * **This no longer governs hands-free conversation** (Kevin, 2026-08-18) - see the
     * `vadMode` branch of the turn-complete handler for why a conversation now waits
     * indefinitely instead. The only remaining caller is the cold proactive branch,
     * where [vadMode] is false, there is no mic open, and nothing is worth keeping warm
     * for: the companion said its line and the socket should go.
     *
     * Cancelled the moment the driver's input transcript starts arriving (see
     * handleServerContent), and by the conversation branch above.
     */
    /**
     * The only thing that can end a hands-free conversation the driver did not end
     * himself: **thirty minutes with no input at all** ([CONVERSATION_BACKSTOP_MS]).
     *
     * Kevin's call, 2026-08-18, in two parts. First, that a conversation waits for him
     * rather than for a timer - the old 10s idle park is what made a drive feel like it
     * "dropped after 3 turns", and no length short enough to be a timeout is long enough
     * to survive a merge. Second, asked directly about the consequence, that there
     * should still be a backstop, because a conversation left running holds a live mic
     * and a billed session on his own key until the service dies.
     *
     * Thirty minutes is not a guess at how long a pause can be. It is far past any
     * plausible one, so reaching it means the conversation was FORGOTTEN, which is the
     * only case this exists for. It parks warm rather than closing, so a tap resumes
     * instantly; [WARM_HOLD_MS] then closes the socket for real shortly after.
     *
     * Re-armed on every completed turn and cancelled the moment the driver's input
     * transcript starts arriving (see handleServerContent), so it measures silence, not
     * conversation length. A two-hour chat with a reply every few minutes never trips it.
     *
     * Unlike the timer it replaces, this one SAYS SO - see [LiveEvent.Idle.backstop].
     */
    private fun armConversationBackstop() {
        idleJob?.cancel()
        idleJob = io.launch {
            delay(CONVERSATION_BACKSTOP_MS)
            if (!running.get()) return@launch
            Log.d(TAG, "conversation backstop reached after ${CONVERSATION_BACKSTOP_MS}ms of no input")
            parkWarm(backstop = true)
        }
    }

    private fun armIdleTimeout() {
        idleJob?.cancel()
        idleJob = io.launch {
            delay(idleTimeoutMs)
            if (!running.get()) return@launch
            closeSession("idle")
        }
    }

    /**
     * Ends the current conversation but keeps the socket connected (warm) so the
     * next tap resumes instantly. Closes the mic, brings music back, and starts a
     * hold timer that fully closes the socket after [WARM_HOLD_MS] of no use (so
     * an unused warm connection doesn't linger and bill indefinitely).
     */
    private fun parkWarm(backstop: Boolean = false) {
        idleJob?.cancel(); idleJob = null
        conversationActive = false
        capturing = false
        suppressMicNextTurn = false
        restoreAudio()
        ConversationState.setBusy(false)
        warm.set(true)
        emit(LiveEvent.Idle(backstop))
        warmHoldJob?.cancel()
        warmHoldJob = io.launch {
            delay(WARM_HOLD_MS)
            if (running.get() && !conversationActive) closeSession("warm expired")
        }
    }

    // --- Audio ducking ---------------------------------------------------

    // Whether media is ducked for the current conversation. Idempotent guard so
    // opening the mic and the first speaking chunk don't double-apply.
    @Volatile private var ducked = false

    // Whether the phone's music was actually playing at the moment we ducked.
    // AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK only *hints* that an app may lower its
    // own volume instead of pausing - many phone music apps (and the AVRCP/A2DP
    // path some cheap head-unit BT stacks use) don't implement ducking at all
    // and just pause on transient focus loss, then never auto-resume on regain
    // (drive-notes ticket 01: "music not resuming after voice turn ends or PTT
    // is canceled"). Recording this at duck-time and nudging play() back on
    // restore is a belt-and-suspenders fix for exactly that case; it never
    // resumes music that was already paused before the conversation started.
    @Volatile private var musicWasPlayingBeforeDuck = false

    /**
     * Ducks other apps' audio (Spotify et al.) for the current turn via
     * cooperative audio focus with the "may duck" hint - the system asks the
     * media app to lower itself while our spoken audio (USAGE_ASSISTANT) holds
     * focus, so Zero stays prominent.
     *
     * We deliberately do NOT lower STREAM_MUSIC's system volume here: on phones
     * (and most head units) USAGE_ASSISTANT is mapped to STREAM_MUSIC, so
     * lowering that stream lowers Zero's own playback track too - which made him
     * inaudible mid-reply. Two outputs can't carry different volumes on one
     * stream, so focus-based ducking is the only mechanism that keeps Zero loud.
     *
     * If a real head unit honors MAY_DUCK too weakly and you want music to drop
     * harder, route the playback track ([ensureTrack]) onto a genuinely separate
     * bus (e.g. USAGE_ASSISTANCE_NAVIGATION_GUIDANCE on Automotive) BEFORE
     * reintroducing any stream-volume ducking - never duck the stream Zero
     * plays on.
     */
    private fun duckNow() {
        if (ducked) return
        ducked = true
        musicWasPlayingBeforeDuck = NowPlayingController.state.value?.isPlaying == true
        requestDuckFocus()
    }

    /**
     * Abandons audio focus once the turn is over, bringing other apps back up.
     * Also nudges an explicit transport play() if music was actually playing
     * before we ducked - see [musicWasPlayingBeforeDuck] for why abandoning
     * focus alone isn't reliable enough on its own.
     */
    private fun restoreAudio() {
        if (!ducked) return
        ducked = false
        abandonDuckFocus()
        if (musicWasPlayingBeforeDuck) {
            musicWasPlayingBeforeDuck = false
            MusicController.play(appContext)
        }
    }

    private fun requestDuckFocus() {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attributes)
                .setWillPauseWhenDucked(false)
                .setOnAudioFocusChangeListener(audioFocusChangeListener, Handler(Looper.getMainLooper()))
                .build()
            audioFocusRequest = req
            audioManager.requestAudioFocus(req)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                audioFocusChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
            )
        }
    }

    private fun abandonDuckFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            audioFocusRequest = null
        } else {
            // Pass the SAME listener instance passed to requestAudioFocus() above -
            // the legacy API matches abandon-to-request by listener identity, so
            // passing null here would silently no-op the abandon on API < O.
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(audioFocusChangeListener)
        }
    }

    // --- Teardown --------------------------------------------------------

    private fun closeSession(reason: String) {
        if (!closed.compareAndSet(false, true)) return
        running.set(false)
        warm.set(false)
        conversationActive = false
        capturing = false
        warmHoldJob?.cancel(); warmHoldJob = null
        idleJob?.cancel(); idleJob = null
        micJob?.cancel(); micJob = null
        audioPlaybackJob?.cancel(); audioPlaybackJob = null
        try {
            webSocket?.close(NORMAL_CLOSE, "client closing")
        } catch (_: Exception) {
        }
        webSocket = null
        releaseTrack()
        restoreAudio()
        ConversationState.setBusy(false)
        emit(LiveEvent.Closed(reason))
    }

    /** Fully tears down the session and its scopes; call when discarding. */
    fun destroy() {
        closeSession("destroyed")
        io.cancel()
        main.cancel()
    }

    /**
     * Tears the session down WITHOUT emitting a Closed event, and cancels its
     * scopes so any already-queued event delivery is dropped. Used when the
     * controller supersedes a stale socket (a dead warm socket it's replacing):
     * a normal close posts a Closed event that would race in on the main thread
     * and clobber the fresh session the controller starts in its place. Setting
     * [closed] first also makes any later OkHttp onFailure -> closeSession a no-op.
     */
    fun silentDestroy() {
        closed.set(true)
        running.set(false)
        warm.set(false)
        conversationActive = false
        capturing = false
        warmHoldJob?.cancel(); warmHoldJob = null
        idleJob?.cancel(); idleJob = null
        micJob?.cancel(); micJob = null
        audioPlaybackJob?.cancel(); audioPlaybackJob = null
        try {
            webSocket?.close(NORMAL_CLOSE, "superseded")
        } catch (_: Exception) {
        }
        webSocket = null
        releaseTrack()
        restoreAudio()
        ConversationState.setBusy(false)
        io.cancel()
        main.cancel()
    }

    private fun emit(event: LiveEvent) {
        main.launch { onEvent(event) }
    }

    companion object {
        private const val TAG = "GeminiLiveSession"

        /**
         * Pure decision behind [mailToolCalledThisTurn]'s skip inside [captureEpisodicTurn]
         * (ticket 15, google-account-integration): true when [toolName] is one this repo has
         * decided must never let its own turn reach [com.kevin.legion.data.local.EpisodicTurn]/
         * [com.kevin.legion.data.local.CompanionMemory] (ticket 07's read-through rule).
         *
         * Pulled out to its own top-level-testable function, not inlined into [handleToolCall],
         * **specifically so it is a plain JVM unit test target.** This class needs a live
         * [Context], a real [OkHttpClient] websocket, [AudioTrack], and Room to instantiate at
         * all, so nothing about the class itself can be exercised from a fast JVM test - this
         * function is deliberately the one seam that can, and it is the exact production
         * decision [handleToolCall] uses, not a re-implementation a test could pass while the
         * real path drifts.
         *
         * [excludedTools] defaults to the real [LiveToolbox.EPISODIC_EXCLUDED_TOOLS] and every
         * production call site (just [handleToolCall]) relies on that default and never overrides
         * it. The parameter exists so a test can hand in a different set and prove this genuinely
         * decides by SET MEMBERSHIP rather than by hardcoding "search_mail"/"read_mail" as
         * literals - ticket 21 (google-account-integration) needed exactly that proof for
         * [LiveToolbox.rememberBlockedByReadThroughTool], which is built on the same guarantee:
         * whatever else joins [LiveToolbox.EPISODIC_EXCLUDED_TOOLS] later (the map's own standing
         * rule - it is "precedent for every future read-through sense") must be excluded here, and
         * gated in `remember`, with zero code change in either place.
         */
        internal fun isEpisodicExcludedTool(
            toolName: String,
            excludedTools: Set<String> = LiveToolbox.EPISODIC_EXCLUDED_TOOLS,
        ): Boolean = toolName in excludedTools

        /**
         * Ticket 02: parses a protobuf `Duration` (the type of [GoAway.timeLeft]) into
         * milliseconds. Proto3's well-known JSON mapping for `Duration` is a string like
         * `"9.5s"`; the `{seconds, nanos}` object form is handled defensively only - it is
         * `Duration`'s wire/struct shape, not its documented JSON shape, and has not been
         * observed from a real response (no live session has run against this code yet).
         * Pulled out to a pure top-level-testable function for the same reason
         * [isEpisodicExcludedTool] is: [GeminiLiveSession] cannot be constructed from a plain
         * JVM test, so this is the seam that can be, and it is the exact function
         * [handleGoAway] calls, not a re-implementation that could drift from it.
         */
        internal fun parseGoAwayDurationMs(raw: Any?): Long? = when (raw) {
            is String -> raw.trim().removeSuffix("s").toDoubleOrNull()?.let { (it * 1000).toLong() }
            is JSONObject -> {
                val seconds = raw.optLong("seconds", 0L)
                val nanos = raw.optLong("nanos", 0L)
                seconds * 1000 + nanos / 1_000_000
            }
            else -> null
        }

        // Live caption tail length (see captionTail). Sized for the caption
        // boxes' ~2-3 line, ~13-15sp, ~460dp-max-width rendering - long enough
        // to read as a couple of sentences, short enough to fit without a
        // scroll mechanism in either caption UI.
        private const val CAPTION_TAIL_CHARS = 140

        private const val WS_URL =
            "wss://generativelanguage.googleapis.com/ws/" +
                "google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"

        // Live-capable model. Swap here if Google promotes a newer Live model.
        private const val MODEL = "models/gemini-3.1-flash-live-preview"

        // Prebuilt Gemini voice. Deeper/informative reads suit the Zero persona;
        // other options include Puck, Kore, Fenrir, Aoede. Swap freely.
        private const val VOICE = "Sulafat"

        private const val INPUT_RATE = 16000   // Live API requires 16 kHz input
        private const val OUTPUT_RATE = 24000  // Live API emits 24 kHz output
        private const val INPUT_MIME = "audio/pcm;rate=16000"

        // ~100 ms of 16 kHz mono PCM16 = 1600 samples * 2 bytes.
        private const val CHUNK_BYTES = 3200

        private const val MIC_HANDOFF_MS = 250L
        // Settle after playback has actually drained, before capture opens. Small
        // on purpose - awaitPlaybackDrained already waited for the real tail, this
        // is just a beat for the HAL to release the output path.
        private const val MIC_REOPEN_SETTLE_MS = 60L
        // Mono PCM16 output: 2 bytes per frame.
        private const val BYTES_PER_FRAME = 2
        // Upper bound on waiting for playback to drain, so a stalled AudioTrack
        // can't wedge the mic shut. Comfortably past the track's ~0.5s buffer.
        private const val PLAYBACK_DRAIN_TIMEOUT_MS = 1_500L
        private const val PLAYBACK_DRAIN_POLL_MS = 20L
        // Server-VAD end-of-turn tuning. Gemini's default silence window is short
        // and cuts the driver off when they pause mid-sentence to think (the "iffy
        // turn-taking" field report); a longer window waits them out. prefixPadding
        // keeps a little audio before speech onset so the first word isn't clipped.
        private const val VAD_SILENCE_MS = 900
        private const val VAD_PREFIX_PADDING_MS = 300
        // Driver silence after Zero's turn that ends a hands-free conversation.
        private const val IDLE_TIMEOUT_MS = 10_000L
        // How long a parked (warm) socket stays connected with no use before it
        // fully closes. Long enough that follow-up turns through a drive resume
        // instantly; short enough that an idle warm connection (and its cloud
        // billing) doesn't linger. Reconnect is lazy on the next tap.
        /**
         * How long a hands-free conversation may sit with NO driver input before it parks
         * itself. See [armConversationBackstop] - a forgotten-conversation backstop, not a
         * conversational timeout, which is why it is measured in tens of minutes.
         */
        private const val CONVERSATION_BACKSTOP_MS = 30 * 60 * 1000L
        private const val WARM_HOLD_MS = 3 * 60 * 1000L
        // Ticket 02 (drive-test-2026-08-18): safety margin subtracted from a goAway's
        // reported timeLeft before scheduling our own deliberate close - see
        // [handleGoAway]'s doc for why this is a GUESS, unmeasured against a real
        // response as of 2026-08-19.
        private const val GOAWAY_RECONNECT_MARGIN_MS = 3_000L
        private const val NORMAL_CLOSE = 1000
        // Below this, a real (vadMode) conversational turn forwarded suspiciously little mic
        // audio - a healthy turn forwards tens of KB (see MidnightEvents.silentMicTurn's doc).
        // Triggers a forced non-fatal report, not just a breadcrumb, so the next field drive
        // actually produces retrievable Crashlytics data for B9/B10/B12.
        private const val SILENT_TURN_BYTES_THRESHOLD = 2_000L
        // Short-capture retry window (2026-08-17): a capture that closes THIS soon
        // after opening, forwarding fewer than SILENT_TURN_BYTES_THRESHOLD bytes, is
        // treated as a false end-of-turn rather than a real (if terse) reply - see
        // [retriedThisTurn]'s declaration for the measured on-device shape (bad turns
        // closing in well under a second, healthy ones running 4-15s). Comfortably
        // above the very shortest measured bad-turn duration (276ms) so genuine noise
        // blips are caught, comfortably below the shortest measured healthy turn
        // (4374ms) so a real short driver reply is never mistaken for one.
        private const val SHORT_CAPTURE_RETRY_WINDOW_MS = 1_200L
        // Backoff after a record.read() error (2026-08-17). Matches the `!capturing`
        // branch's own idle delay just above in the loop - the point is only that SOME
        // delay exists, so a persistently failing AudioRecord can't busy-loop the thread;
        // it does not need to be tuned separately from that value.
        private const val RECORD_READ_ERROR_BACKOFF_MS = 20L
    }
}

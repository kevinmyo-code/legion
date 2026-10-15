package com.kevin.midnightai.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Process
import android.util.Base64
import android.util.Log
import androidx.core.content.ContextCompat
import com.kevin.midnightai.BuildConfig
import com.kevin.midnightai.ai.CrisisDetector
import com.kevin.midnightai.ai.GeminiKeyProvider
import com.kevin.midnightai.data.local.CarDatabase
import com.kevin.midnightai.data.local.EpisodicTurn
import com.kevin.midnightai.media.MusicController
import com.kevin.midnightai.media.NowPlayingController
import com.kevin.midnightai.vehicle.ActiveVehicle
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import com.kevin.midnightai.MidnightEvents
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

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
    data object Idle : LiveEvent
    /** The session ended (closed, timed out, or errored). */
    data class Closed(val reason: String) : LiveEvent
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
 *    [IDLE_TIMEOUT_MS] of driver silence.
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

    // 2026-10-13 car report ("Midnight crashes and restarts on a voice tap"): these scopes had no
    // handler, so ANY exception in a session coroutine (playback thread, mic loop, idle timer)
    // went to the thread's default handler and killed the whole process - the launcher - and
    // with it App Remote, audio focus and Now Playing. A broken session is now closed and
    // reported as a non-fatal instead; the launcher keeps running.
    private val faultGuard = CoroutineExceptionHandler { _, e ->
        Log.e(TAG, "voice session coroutine failed", e)
        MidnightEvents.voiceSessionFault("coroutine", e)
        try {
            closeSession("internal error")
        } catch (_: Throwable) {
        }
    }
    private val io = CoroutineScope(Dispatchers.IO + SupervisorJob() + faultGuard)
    private val main = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob() + faultGuard)

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
    // How long the driver can go quiet before armIdleTimeout() closes/parks the
    // session (see its doc). Configurable per-[start] call: onboarding needs a
    // much longer grace period than routine command-response turns - the main
    // app's default (IDLE_TIMEOUT_MS, 10s) was being hit by completely normal
    // "let me think about that" pauses during open-ended onboarding questions
    // (name/personality/car trim), and ConversationalOnboardingScreen's ChatStep
    // was treating that benign timeout exactly like a real network failure.
    @Volatile private var idleTimeoutMs = IDLE_TIMEOUT_MS
    @Volatile private var conversationActive = false
    private var warmHoldJob: Job? = null

    // One-shot: the next completed turn parks warm instead of opening the mic.
    // Set when a proactive line is spoken on a warm socket, so the opener/alert is
    // voiced without turning into a listening conversation.
    @Volatile private var suppressMicNextTurn = false

    // Tap barge-in (voice-pipeline audit B4, 2026-10-15): the driver tapped while Zero was mid-turn.
    // beginConversation flushes the audio already queued, but the server does not know and keeps
    // streaming that turn; its next chunk used to flip speakingThisTurn back on, re-mute the mic
    // and play the rest of the sentence the driver had just cut off. While this is set, incoming
    // model audio for the turn is dropped and the mic is left alone. Cleared by the turn's
    // turnComplete or by a server `interrupted` (a new turn is starting), and on start().
    @Volatile private var discardModelAudioUntilTurnComplete = false

    private var micJob: Job? = null
    private var idleJob: Job? = null

    // Tool calls handed to the controller and not yet answered (id -> name). While any is
    // pending the idle timer must not park/close the session: ask_engine routinely takes
    // 30-60s, the model can send turnComplete before the tool response, and a park in that
    // window dropped the late answer. Written on the socket thread, read on the idle job.
    private val pendingToolCalls = java.util.concurrent.ConcurrentHashMap<String, String>()
    // Ids the server cancelled (toolCallCancellation, e.g. the driver interrupted). Their
    // late responses are dropped in sendToolResponse rather than sent stale.
    private val cancelledToolCalls = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    @Volatile private var audioTrack: AudioTrack? = null
    // Jitter buffer in front of [audioTrack] (one per track). Owns the frames-written
    // count that awaitPlaybackDrained compares against the playback head. Driven only
    // from the playback thread; its counters are safe to read anywhere.
    @Volatile private var feeder: PcmJitterFeeder? = null
    private val trackLock = Any()
    private var speakingThisTurn = false

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
    private val audioQueue = Channel<PlaybackItem>(Channel.UNLIMITED)
    private var audioPlaybackJob: Job? = null

    // Frames sitting in [audioQueue], not yet handed to the track. Added on enqueue,
    // subtracted after the chunk is written (so a chunk mid-write counts on both sides,
    // never neither) or when flushAudio drains it. Part of the playback backlog the mic
    // re-open waits on.
    private val pendingQueueFrames = AtomicLong(0)
    // Bumped by flushAudio. A chunk whose write straddles a flush sees the change and
    // stops; the Flushed marker then clears the track on the playback thread.
    private val flushGen = AtomicInteger(0)

    // Playback runs on its own URGENT_AUDIO thread, not a shared Dispatchers.IO worker:
    // on the head unit's slow CPU a normal-priority writer competes with Compose, the
    // always-on wallpaper animation and the socket reader, and a writer that loses the
    // CPU while the track is near-empty is an audible gap. One thread, timed out when idle.
    private val playbackExecutor = ThreadPoolExecutor(
        1, 1, 30L, TimeUnit.SECONDS, LinkedBlockingQueue(),
    ) { r ->
        Thread({
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            } catch (_: Exception) {
                try { Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO) } catch (_: Exception) {}
            }
            r.run()
        }, "zero-playback")
    }.apply { allowCoreThreadTimeOut(true) }
    private val playbackDispatcher = playbackExecutor.asCoroutineDispatcher()

    // Per-turn arrival trace for the choppy-voice diagnostics, socket thread only: a big
    // max gap between chunks points at the network, rebuffers without one at the device.
    private var turnChunks = 0
    private var turnFirstChunkMs = 0L
    private var turnLastChunkMs = 0L
    private var turnMaxChunkGapMs = 0L
    // Server `interrupted` events this session, and playback rebuffers this session.
    private var sessionInterruptions = 0
    private val sessionRebuffers = AtomicInteger(0)

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

    // Bytes of mic audio forwarded to Gemini since the current turn began.
    // Logged on turnComplete as a capture-health trace: a turn that forwards ~0
    // bytes means Gemini got no new audio. A healthy turn forwards tens of KB.
    @Volatile private var bytesThisTurn = 0L

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

    // Zero's own speech this turn, from outputAudioTranscription - only requested
    // when the debug subtitle toggle is on, and emitted as LiveEvent.Subtitle.
    @Volatile private var subtitles = false
    private val companionTurnText = StringBuilder()

    // Companion-memory ticket 01 (2026-07-22): groups this connection's
    // EpisodicTurn rows. Minted fresh in start(); blank before the first
    // start() call, which captureTurn() below treats as "don't capture yet."
    @Volatile private var episodicSessionId: String = ""

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
     * prompt (built by [com.kevin.midnightai.ai.AriaBrain]); [functionDeclarations] is
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
    ) {
        if (!running.compareAndSet(false, true)) return
        closed.set(false)
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
        discardModelAudioUntilTurnComplete = false
        // A pre-connect (no conversation yet) must NOT mark the app busy - the
        // proactive engine may still speak on the warm socket. A cold session that
        // will speak/listen right away marks busy so proactive stays quiet for it.
        if (!prewarmOnly) ConversationState.setBusy(true)
        // Build the playback track up front so the first audio chunk plays without
        // paying AudioTrack setup latency mid-reply (pre-warming the output path).
        io.launch {
            // A track that will not build here is retried by the first audio chunk; it must not
            // fail the session before it has even connected.
            try {
                ensureTrack()
            } catch (e: Exception) {
                Log.w(TAG, "AudioTrack pre-warm failed: ${e.message}")
            }
        }
        sessionInterruptions = 0
        sessionRebuffers.set(0)
        // Consumer for audioQueue - see its declaration for why playback runs on
        // its own coroutine instead of inline in the WebSocket callback, and
        // playbackExecutor for why that coroutine has its own audio-priority thread.
        if (audioPlaybackJob?.isActive != true) {
            audioPlaybackJob = io.launch(playbackDispatcher) {
                for (item in audioQueue) handlePlaybackItem(item)
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
        // audio now so the mic doesn't capture his own trailing voice. The backlog
        // check covers a tap after turnComplete while his reply is still playing out.
        if (speakingThisTurn || playbackBacklogFrames() > 0) {
            // Only a turn still STREAMING (no turnComplete yet) keeps sending chunks; a backlog
            // alone is the tail of a finished turn, and discarding "until turnComplete" there
            // would swallow the NEXT turn's whole reply.
            if (speakingThisTurn) discardModelAudioUntilTurnComplete = true
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
    /**
     * @return whether the line was handed to the socket. False (B15, 2026-10-15) means it was NOT
     * spoken: the socket is gone or refused the send. The session closes itself as a stale
     * socket in that case (the controller re-prewarms off the Closed event) and the controller
     * decides whether to keep the line for the next connect. This used to ignore the result, so a
     * proactive line to a dead warm socket vanished and left the busy flag set.
     */
    fun speakOnWarm(text: String): Boolean {
        if (!running.get() || closed.get()) return false
        warmHoldJob?.cancel()
        suppressMicNextTurn = true
        ConversationState.setBusy(true)
        val sent = sendText(text)
        if (!sent) {
            suppressMicNextTurn = false
            MidnightEvents.voiceTurnDecision("speakOnWarm: send refused, closing stale warm socket")
            closeSession("stale socket")
        }
        return sent
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
     * Adds [text] to the conversation as background context WITHOUT asking for a reply
     * (`turnComplete = false`), so the model has it when the driver speaks next. Used on a warm
     * resume (B2, 2026-10-15), which opens the mic with no greeting and so never carried the
     * fresh live context (now-playing, location, date, trouble codes) a cold start injects.
     * Same return contract as [sendText]: false means the socket is gone.
     *
     * Unverified on the wire: that the Live model accepts a non-completing client turn between
     * sessions of realtime audio. If it ignores or rejects it, the cost is a stale-context turn
     * (today's behaviour), not a failure; owed a check on the head unit.
     */
    fun sendContext(text: String): Boolean {
        val msg = JSONObject().put("clientContent", JSONObject().apply {
            put("turns", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("parts", JSONArray().put(JSONObject().put("text", text)))
            }))
            put("turnComplete", false)
        })
        return webSocket?.send(msg.toString()) ?: false
    }

    /** Returns the result of a [LiveEvent.ToolCall] back to Gemini. */
    fun sendToolResponse(id: String, name: String, response: JSONObject) {
        pendingToolCalls.remove(id)
        if (cancelledToolCalls.remove(id)) {
            // The server already cancelled this call; answering it now would be a stale reply
            // to a turn that no longer exists.
            Log.d(TAG, "Dropping response for cancelled tool call $name")
            return
        }
        val msg = JSONObject().put("toolResponse", JSONObject().apply {
            put("functionResponses", JSONArray().put(JSONObject().apply {
                put("id", id)
                put("name", name)
                put("response", response)
            }))
        })
        webSocket?.send(msg.toString())
    }

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
        root.optJSONObject("toolCallCancellation")?.let { handleToolCallCancellation(it) }
        root.optJSONObject("goAway")?.let { handleGoAway(it) }
        // sessionResumptionUpdate is ignored in this version.
    }

    /**
     * `goAway`: the server will end this connection soon (`timeLeft`). Ignored until 2026-10-15,
     * which let a PARKED socket die mid-reply to the next proactive line. A parked warm socket
     * is closed now (reason "go away", a normal close the controller re-prewarms from) so the
     * next tap or proactive finds a fresh one. A live conversation is left alone: tearing it
     * down early would cut the driver off, and the close path covers it when the server follows
     * through. Breadcrumbed either way.
     */
    private fun handleGoAway(goAway: JSONObject) {
        val timeLeft = goAway.optString("timeLeft")
        // suppressMicNextTurn is set while a proactive line is being spoken on the warm socket.
        val parked = warm.get() && !conversationActive && !speakingThisTurn && !suppressMicNextTurn
        MidnightEvents.voiceTurnDecision("goAway timeLeft=$timeLeft parked=$parked")
        if (parked) closeSession("go away")
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
     * later distill into scored, sec-9.1-categorized [com.kevin.midnightai.data.local.CompanionMemory]
     * rows. Fire-and-forget on [io] - never blocks the conversation, and a
     * write failure here must not affect the live turn in any way.
     *
     * Skips a blank driver line (nothing to remember) and skips entirely when
     * [episodicSessionId] hasn't been minted yet (start() never ran - shouldn't
     * happen mid-turnComplete, but this is transcript capture, not the
     * conversation itself, so it fails silent rather than throwing).
     */
    private fun captureEpisodicTurn(driverText: String, companionText: String) {
        val sessionId = episodicSessionId
        if (sessionId.isBlank() || driverText.isBlank()) return
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
            for (i in 0 until parts.length()) {
                val data = parts.optJSONObject(i)?.optJSONObject("inlineData")?.optString("data")
                if (!data.isNullOrEmpty()) {
                    // B4: the driver tapped over this turn; drop its remaining audio and do NOT
                    // re-mute the mic or announce speech (see discardModelAudioUntilTurnComplete).
                    if (discardModelAudioUntilTurnComplete) continue
                    if (!speakingThisTurn) {
                        speakingThisTurn = true
                        // Zero is answering, so the driver is not "quiet": cancel the idle
                        // timer (e.g. the fresh window armed after a tool response) so it
                        // cannot park the session mid-answer. This turn's turnComplete re-arms it.
                        idleJob?.cancel()
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
                    // Arrival trace (replaces the old per-chunk B12 logcat lines, which cost
                    // string building twice per chunk on a slow CPU and were invisible on the
                    // head unit anyway): summarized once per turn at turnComplete.
                    val now = System.currentTimeMillis()
                    if (turnChunks == 0) {
                        turnFirstChunkMs = now
                        turnMaxChunkGapMs = 0
                    } else {
                        turnMaxChunkGapMs = maxOf(turnMaxChunkGapMs, now - turnLastChunkMs)
                    }
                    turnLastChunkMs = now
                    turnChunks++
                    // Enqueue rather than write here - see audioQueue's declaration comment.
                    val pcm = Base64.decode(data, Base64.DEFAULT)
                    val frames = (pcm.size / BYTES_PER_FRAME).toLong()
                    pendingQueueFrames.addAndGet(frames)
                    audioQueue.trySend(PlaybackItem.Chunk(pcm, frames))
                }
            }
        }

        if (content.optBoolean("interrupted")) {
            // Counted so a car test shows whether "choppy" is Zero being cut off by the
            // server (barge-in, possibly his own voice reaching the mic) rather than
            // playback underruns. micOpen=false rules out our mic as the trigger.
            sessionInterruptions++
            MidnightEvents.voiceInterrupted(sessionInterruptions, speaking = speakingThisTurn, micOpen = capturing)
            turnChunks = 0
            discardModelAudioUntilTurnComplete = false
            flushAudio()
            speakingThisTurn = false
            emit(LiveEvent.Interrupted)
            // B9 (2026-10-15): in a conversation the mic was muted for Zero's speech, and the
            // interrupting turn's own turnComplete is what used to reopen it - leaving the driver
            // unheard (and the idle timer unarmed) in the gap. Reopen now; the later
            // turnComplete's openMicForUser is near-idempotent (the mic loop is already running).
            // Not for a proactive line spoken on a warm socket: that stays mic-less.
            if (vadMode && conversationActive && !suppressMicNextTurn) {
                openMicForUser()
                armIdleTimeout()
            }
        }
        if (content.optBoolean("turnComplete")) {
            // Releases a short reply still priming in the jitter buffer and closes the
            // turn's playback stats. Before the mic decision below, so the mic re-open's
            // drain wait sees that audio start moving.
            audioQueue.trySend(PlaybackItem.EndOfTurn)
            if (turnChunks > 0) {
                MidnightEvents.voiceAudioArrival(
                    chunks = turnChunks,
                    spanMs = turnLastChunkMs - turnFirstChunkMs,
                    maxGapMs = turnMaxChunkGapMs,
                    backlogAtTurnEndMs = playbackBacklogFrames() * 1000 / OUTPUT_RATE,
                )
                turnChunks = 0
            }
            speakingThisTurn = false
            discardModelAudioUntilTurnComplete = false
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
            userTurnText.setLength(0)
            companionTurnText.setLength(0)
            emit(LiveEvent.TurnComplete)

            // DIAGNOSTIC (B9/B10/B12, remove once root-caused): the exact flags this
            // branch decision is made from, so a field-test log pull shows which path
            // fired for a given "listened when it shouldn't have" / "didn't hear my
            // reply" report instead of us guessing after the fact.
            val toolsPending = pendingToolCalls.isNotEmpty()
            val decision = "suppressMicNextTurn=$suppressMicNextTurn vadMode=$vadMode " +
                "conversationActive=$conversationActive warm=${warm.get()} toolsPending=$toolsPending"
            Log.d(TAG, "turnComplete decision: $decision")
            MidnightEvents.voiceTurnDecision(decision)

            when (TurnEndDecision.decide(suppressMicNextTurn, toolsPending, vadMode)) {
                // A proactive line spoken on a warm socket: Zero is done, park
                // the socket warm again (no mic, music back up) rather than listen.
                TurnEndDecision.Action.PARK_WARM -> {
                    suppressMicNextTurn = false
                    parkWarm()
                }
                // B7 (2026-10-15): a proactive that Zero answered with a tool call. This
                // turnComplete is the call turn, not the spoken answer: keep the suppress flag so
                // the answer's own turnComplete parks, and do not open the mic. The idle timer
                // holds while the tool is pending and is the net if the response never comes.
                TurnEndDecision.Action.HOLD_FOR_TOOL -> armIdleTimeout()
                // Conversation: hand the mic back and wait for the driver's reply;
                // stay ducked through the chat. The idle timer below either parks
                // the socket warm (keepWarm) or closes it after the driver's quiet.
                TurnEndDecision.Action.OPEN_MIC -> {
                    openMicForUser()
                    armIdleTimeout()
                }
                // Cold speak-only proactive: bring music back, then close shortly.
                TurnEndDecision.Action.RESTORE_AND_CLOSE -> {
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
            val id = call.optString("id")
            val name = call.optString("name")
            // Recorded BEFORE emit so the idle timer sees it however fast the turn completes.
            pendingToolCalls[id] = name
            emit(LiveEvent.ToolCall(
                id = id,
                name = name,
                args = call.optJSONObject("args") ?: JSONObject(),
            ))
        }
    }

    /**
     * `toolCallCancellation` (`{"ids": [...]}`): the server dropped calls it had asked for,
     * typically because the driver interrupted. The controller's work is not torn down (that
     * would touch every tool); its eventual response is just not sent (see [sendToolResponse]).
     */
    private fun handleToolCallCancellation(cancellation: JSONObject) {
        val ids = cancellation.optJSONArray("ids") ?: return
        for (i in 0 until ids.length()) {
            val id = ids.optString(i)
            val name = pendingToolCalls.remove(id) ?: continue // already answered
            cancelledToolCalls += id
            MidnightEvents.toolCancelled(name)
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
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            INPUT_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, INPUT_RATE) // >= ~0.5s headroom
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.w(TAG, "AudioRecord init failed")
            record.release()
            closeSession("microphone unavailable")
            return
        }

        // Cancel the device's own speaker output (Maps turn-by-turn guidance,
        // music, and Zero's own TTS) out of the captured signal, so the open
        // mic doesn't feed that audio back to Gemini as if it were the driver.
        // Without this, once navigation is running its continuous voice
        // guidance bleeds into every captured turn. Both effects are
        // hardware-dependent; skip them where unavailable.
        val effects = if (isEmulator) {
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
        try {
            while (isActive && running.get()) {
                if (webSocket == null) break

                if (!capturing) {
                    if (recording) {
                        try { record.stop() } catch (_: Exception) {}
                        recording = false
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
                        // MIC_HANDOFF_MS above, before this loop started. Every
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
                    } catch (e: Exception) {
                        Log.w(TAG, "Capture start/flush failed: ${e.message}")
                        delay(20)
                        continue
                    }
                }

                val n = record.read(buffer, 0, buffer.size)
                if (n <= 0) continue
                // capturing may have flipped false during the blocking read; only
                // forward audio that belongs to an active turn.
                if (!capturing) continue
                bytesThisTurn += n
                val b64 = Base64.encodeToString(buffer, 0, n, Base64.NO_WRAP)
                sendRealtimeInput(JSONObject().put(
                    "audio",
                    JSONObject().put("mimeType", INPUT_MIME).put("data", b64)
                ))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Mic loop error: ${e.message}")
        } finally {
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

    // Locked: the start() pre-warm and the playback thread's first chunk can race here.
    // Null once the session is closed: a chunk the playback thread was already handling when
    // closeSession released the track must not build a fresh one that nothing ever releases
    // (a leaked, playing USAGE_ASSISTANT track holding the output on a HAL that cannot share it).
    private fun ensureTrack(): AudioTrack? {
        audioTrack?.let { return it }
        return synchronized(trackLock) {
            if (closed.get()) null else audioTrack ?: buildTrack()
        }
    }

    private fun buildTrack(): AudioTrack {
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
            // ~1s of headroom (was ~0.5s): room for the jitter buffer's prime plus a
            // burst arriving ahead of the playhead. Latency is set by the prime
            // threshold, not this capacity. Default performance mode on purpose -
            // LOW_LATENCY shrinks buffers and glitches cheap HALs.
            .setBufferSizeInBytes(maxOf(minBuf * 4, OUTPUT_RATE * BYTES_PER_FRAME))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        // Keep Zero's own track at full gain; ducking is handled cooperatively
        // via audio focus (see duckNow), never by lowering the shared stream this
        // track plays on.
        track.setVolume(AudioTrack.getMaxVolume())
        // Not play()ed here: the feeder starts each turn paused and plays once its
        // prime threshold is queued (see PcmJitterFeeder). A fresh feeder's frame
        // count starts at 0, matching the fresh track's playback head.
        feeder = PcmJitterFeeder(
            sink = AudioTrackSink(track),
            primeFrames = (OUTPUT_RATE * PLAYBACK_PRIME_MS / 1000).toInt(),
            sampleRate = OUTPUT_RATE,
            bytesPerFrame = BYTES_PER_FRAME,
        )
        audioTrack = track
        return track
    }

    /** Playback-thread side of [audioQueue]. */
    // Every AudioTrack call in here can throw IllegalStateException once closeSession has
    // released the track from another thread (play() on a released track, getUnderrunCount,
    // getPlaybackHeadPosition). EndOfTurn and Flushed used to be unguarded, so a turn ending
    // as the session closed took the process down. Nothing on this thread may throw out.
    private fun handlePlaybackItem(item: PlaybackItem) {
        try {
            when (item) {
                is PlaybackItem.Chunk -> {
                    try {
                        if (ensureTrack() != null) {
                            val gen = flushGen.get()
                            feeder?.feed(item.pcm) { flushGen.get() != gen }
                        }
                    } finally {
                        pendingQueueFrames.addAndGet(-item.frames)
                    }
                }
                PlaybackItem.EndOfTurn ->
                    feeder?.endOfTurn()?.let { reportPlaybackTurn(it, interrupted = false) }
                PlaybackItem.Flushed -> {
                    // flushAudio already silenced the track from its own thread; a chunk
                    // whose write straddled that flush may have put stale audio back in,
                    // so clear again here, where no write can be in flight.
                    audioTrack?.let { track ->
                        try {
                            track.pause()
                            track.flush()
                        } catch (_: Exception) {
                        }
                    }
                    feeder?.onFlushed()?.let { reportPlaybackTurn(it, interrupted = true) }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Playback item failed: ${e.message}")
            if (playbackFaultReported.compareAndSet(false, true)) {
                MidnightEvents.voiceSessionFault("playback", e)
            }
        }
    }

    private val playbackFaultReported = AtomicBoolean(false)

    private fun reportPlaybackTurn(stats: TurnPlaybackStats, interrupted: Boolean) {
        val total = sessionRebuffers.addAndGet(stats.rebuffers)
        MidnightEvents.voicePlaybackTurn(
            chunks = stats.chunks,
            audioMs = stats.audioMs,
            rebuffers = stats.rebuffers,
            halUnderruns = stats.halUnderruns,
            droppedBytes = stats.droppedBytes,
            interrupted = interrupted,
            sessionRebuffers = total,
        )
    }

    /** Frames of Zero's audio not yet heard: queued for the track plus inside it. */
    private fun playbackBacklogFrames(): Long =
        pendingQueueFrames.get().coerceAtLeast(0) + (feeder?.bufferedFrames() ?: 0L)

    /** Drops every queued item, keeping [pendingQueueFrames] in step. */
    private fun drainAudioQueue() {
        while (true) {
            val item = audioQueue.tryReceive().getOrNull() ?: break
            if (item is PlaybackItem.Chunk) pendingQueueFrames.addAndGet(-item.frames)
        }
    }

    /** Barge-in: drop any audio still queued so Zero stops mid-sentence. */
    private fun flushAudio() {
        flushGen.incrementAndGet()
        // Discard chunks not yet handed to the AudioTrack too, not just what's
        // already in its hardware buffer - otherwise stale queued audio could
        // still play out after the "interruption" once the consumer catches up.
        // Any EndOfTurn goes with them; the Flushed marker below closes the turn.
        drainAudioQueue()
        // Silence now, from this thread, without waiting for the consumer (it may be
        // blocked in a write). The track stays paused; the feeder primes the next turn.
        audioTrack?.let { track ->
            try {
                track.pause()
                track.flush()
            } catch (_: Exception) {
            }
        }
        audioQueue.trySend(PlaybackItem.Flushed)
    }

    /**
     * Suspends until Zero's audio has actually finished coming out of the speaker.
     *
     * 2026-10-13 (choppy voice): this used to give up after a fixed 1.5s. Gemini sends
     * faster than real time, so a long reply can still have seconds queued at
     * turnComplete; the mic then started capturing over live playback, which on this
     * HAL (no concurrent capture + playback, see micLoop) glitches the output for the
     * rest of the reply and lets the mic hear Zero. It now waits on the real backlog
     * (queue + track) and gives up only when that backlog stops moving for
     * [PLAYBACK_DRAIN_TIMEOUT_MS] or after [PLAYBACK_DRAIN_CAP_MS] (see
     * [PlaybackDrainWatch]), so a wedged track still cannot hold the mic shut.
     *
     * B10/B12 root cause (2026-07-23, replacing the earlier fixed-delay guess): the
     * server's `turnComplete` means "the model finished SENDING", not "the driver
     * has stopped hearing it". Playback writes into a MODE_STREAM [AudioTrack]
     * and `write()` returns as soon as the data is *accepted*, so at `turnComplete`
     * there can still be a buffer's worth of speech queued, plus whatever is still
     * sitting in [audioQueue]. Re-opening the mic then means
     * capture starts while playback is live - which on a head unit that can't do
     * concurrent capture+playback corrupts or drops the start of the driver's reply.
     * That is the "sometimes it hears me, sometimes it doesn't" report, and why it
     * got worse after longer replies (more audio left to drain).
     *
     * Comparing the playback head against frames written waits for the real tail
     * instead of guessing a constant. Chunks still in [audioQueue] count too
     * ([pendingQueueFrames]), as does a short reply still priming in the paused track.
     */
    private suspend fun awaitPlaybackDrained(forMic: Boolean = true) {
        val start = System.currentTimeMillis()
        val watch = PlaybackDrainWatch(PLAYBACK_DRAIN_TIMEOUT_MS, PLAYBACK_DRAIN_CAP_MS, start)
        var verdict: PlaybackDrainWatch.Verdict
        while (true) {
            verdict = watch.step(playbackBacklogFrames(), System.currentTimeMillis())
            if (verdict != PlaybackDrainWatch.Verdict.WAIT) break
            delay(PLAYBACK_DRAIN_POLL_MS)
        }
        if (!forMic) return
        if (verdict != PlaybackDrainWatch.Verdict.DRAINED) {
            MidnightEvents.voiceDrainGaveUp(verdict.name, System.currentTimeMillis() - start)
        }
        // Small settle after the last sample lands - the HAL needs a beat to release
        // the output path before capture opens on units that share it.
        delay(MIC_REOPEN_SETTLE_MS)
    }

    private fun releaseTrack() = synchronized(trackLock) {
        // Unpublish first so other threads stop reaching for it, then release.
        val track = audioTrack
        audioTrack = null
        feeder = null
        track?.let {
            try {
                it.pause()
                it.flush()
            } catch (_: Exception) {
            }
            try {
                it.release()
            } catch (_: Exception) {
            }
        }
        drainAudioQueue()
    }

    /** What the socket thread hands the playback thread. */
    private sealed class PlaybackItem {
        class Chunk(val pcm: ByteArray, val frames: Long) : PlaybackItem()
        /** turnComplete: release a priming tail and close the turn's stats. */
        object EndOfTurn : PlaybackItem()
        /** flushAudio ran: re-clear the track on the playback thread, reset the feeder. */
        object Flushed : PlaybackItem()
    }

    /**
     * [PcmSink] over a real [AudioTrack]. Reads are safe from any thread even after the track
     * was released elsewhere (the main thread's backlog check on a tap, the socket thread at
     * turnComplete): a released track's native calls throw IllegalStateException, which here
     * reads as "nothing buffered" instead of an uncaught crash. play() still throws, so the
     * feeder's caller sees a real failure.
     */
    private class AudioTrackSink(private val track: AudioTrack) : PcmSink {
        override val capacityFrames: Int = try {
            track.bufferSizeInFrames
        } catch (_: Exception) {
            0
        }
        override val isPlaying: Boolean get() = track.playState == AudioTrack.PLAYSTATE_PLAYING
        // Frames since creation/flush; mask so a wrap past Int.MAX_VALUE reads as a
        // large positive rather than negative.
        override fun headFrames(): Long = try {
            track.playbackHeadPosition.toLong() and 0xFFFF_FFFFL
        } catch (_: Exception) {
            Long.MAX_VALUE // released: count everything written as played
        }
        override fun underrunCount(): Int = try {
            track.underrunCount
        } catch (_: Exception) {
            0
        }
        override fun play() = track.play()
        override fun pause() = track.pause()
        override fun write(data: ByteArray, offset: Int, size: Int): Int =
            track.write(data, offset, size)
    }

    // --- Idle auto-close -------------------------------------------------

    /**
     * After a completed turn, close the session if the driver doesn't speak
     * again within [IDLE_TIMEOUT_MS]. In conversation mode this is what ends the
     * hands-free chat once the driver goes quiet; it also bounds the cloud mic
     * stream (and cost) to active conversation. Cancelled the moment the driver's
     * input transcript starts arriving (see handleServerContent).
     */
    private fun armIdleTimeout() {
        idleJob?.cancel()
        idleJob = io.launch {
            // The quiet window starts when Zero stops talking, not when the server
            // finished sending: a long reply can still be playing out for seconds.
            awaitPlaybackDrained(forMic = false)
            delay(idleTimeoutMs)
            // A tool call is still running (ask_engine can take 30-60s): the driver is not
            // "quiet", they are waiting on an answer. Hold the session until the response has
            // gone back, then give a fresh quiet window. Capped so a response that never comes
            // (controller dropped it) cannot hold the socket open forever.
            if (pendingToolCalls.isNotEmpty()) {
                MidnightEvents.voiceTurnDecision("idle deferred: tool pending ${pendingToolCalls.values}")
                val waitUntil = System.currentTimeMillis() + MAX_PENDING_TOOL_HOLD_MS
                while (pendingToolCalls.isNotEmpty() && running.get() &&
                    System.currentTimeMillis() < waitUntil
                ) {
                    delay(PENDING_TOOL_POLL_MS)
                }
                delay(idleTimeoutMs)
            }
            if (!running.get()) return@launch
            // Conversation that went quiet: keep the socket warm for a quick
            // resume if we can, otherwise close. Cold speak-only sessions always
            // close (nothing to keep warm for).
            if (keepWarm && vadMode) parkWarm() else closeSession("idle")
        }
    }

    /**
     * Ends the current conversation but keeps the socket connected (warm) so the
     * next tap resumes instantly. Closes the mic, brings music back, and starts a
     * hold timer that fully closes the socket after [WARM_HOLD_MS] of no use (so
     * an unused warm connection doesn't linger and bill indefinitely).
     */
    private fun parkWarm() {
        idleJob?.cancel(); idleJob = null
        conversationActive = false
        capturing = false
        suppressMicNextTurn = false
        restoreAudio()
        ConversationState.setBusy(false)
        warm.set(true)
        emit(LiveEvent.Idle)
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
        // Which app it was, so the post-turn nudge goes to THAT app and nothing else (it used to
        // send play() to whichever session came first, which could be a stopped head-unit
        // Spotify or the BT bridge when the other one had been playing).
        duckedPackage = if (musicWasPlayingBeforeDuck) {
            try {
                MusicController.playingPackage(appContext)
            } catch (e: Exception) {
                null
            }
        } else {
            null
        }
        // B1 (2026-10-15): what the speakers looked like now, so restoreAudio can tell "nothing
        // changed but our ducking" from "a tool paused it / switched source / started a play".
        duckedSource = com.kevin.midnightai.media.MusicSource.current.value
        duckedPlayGeneration = com.kevin.midnightai.media.PlayRequests.currentGeneration()
        // Index changes while we hold focus are the system ducking, not the driver: VolumeSync
        // must not re-assert them (see ExternalVolumeTracker.setHeld).
        com.kevin.midnightai.media.VolumeSync.holdForVoice(true)
        requestDuckFocus()
    }

    // Package of the session that was playing when we ducked; null when nothing was.
    @Volatile private var duckedPackage: String? = null

    // Source and play-request generation at duck time (B1): the post-turn resume nudge is skipped
    // if either moved. See [com.kevin.midnightai.media.DuckResumeLogic].
    @Volatile private var duckedSource = com.kevin.midnightai.media.Source.PHONE
    @Volatile private var duckedPlayGeneration = 0L

    /**
     * A music tool ran this turn (pause, play, skip, a source switch, a new play, a mixtape).
     * The driver or the tool now owns the speakers, so the "it was playing before the turn"
     * snapshot is stale: forget it, and restoreAudio will not send a resume. Without this a
     * spoken "pause" was undone by the post-turn nudge a second later (B1, 2026-10-15). Called by
     * the controller after such a tool has answered.
     */
    fun forgetDuckedMusic() {
        if (musicWasPlayingBeforeDuck) {
            MidnightEvents.audioFocus("duck_snapshot_cleared: a music tool ran this turn")
        }
        musicWasPlayingBeforeDuck = false
        duckedPackage = null
    }

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

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
        com.kevin.midnightai.media.VolumeSync.holdForVoice(false)
        val pkg = duckedPackage
        duckedPackage = null
        val wasPlaying = musicWasPlayingBeforeDuck
        musicWasPlayingBeforeDuck = false
        val sourceNow = com.kevin.midnightai.media.MusicSource.current.value
        val generationNow = com.kevin.midnightai.media.PlayRequests.currentGeneration()
        if (wasPlaying && !com.kevin.midnightai.media.DuckResumeLogic.shouldNudge(
                wasPlaying, duckedSource, sourceNow, duckedPlayGeneration, generationNow,
            )
        ) {
            MidnightEvents.audioFocus(
                "post_turn_resume skipped: " + com.kevin.midnightai.media.DuckResumeLogic.skipReason(
                    wasPlaying, duckedSource, sourceNow, duckedPlayGeneration, generationNow,
                ),
            )
        } else if (wasPlaying) {
            // On the main looper (the media framework wants one; closeSession runs on socket and
            // io threads), and after a beat: an app that paused for the transient loss resumes
            // itself on the focus GAIN, and only one that did not gets the explicit play().
            // A Handler, not the session's main scope: silentDestroy cancels that scope right
            // after this call.
            mainHandler.postDelayed({
                val outcome = try {
                    MusicController.resumeIfPaused(appContext, pkg)
                } catch (e: Exception) {
                    "failed: ${e.javaClass.simpleName}"
                }
                MidnightEvents.audioFocus("post_turn_resume pkg=$pkg -> $outcome")
            }, POST_TURN_RESUME_DELAY_MS)
        }
    }

    // Focus changes delivered to US while a voice turn holds it (another app grabbing focus mid
    // turn, a call). The turn itself is not changed, but on a real LOSS (B5, 2026-10-15) the duck
    // state is: `ducked` used to stay true, so once Spotify took GAIN nothing ever asked for
    // focus again for the rest of the conversation and later replies played over full-volume
    // music. A LOSS now ends our duck bookkeeping, so the next duckNow() requests focus afresh.
    // Nothing is resumed on a loss: whoever took focus owns the speakers.
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        MidnightEvents.audioFocus("voice_turn_focus_change ${focusChangeName(change)}")
        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            handleFocusLoss()
        }
    }

    private fun handleFocusLoss() {
        if (!ducked) return
        ducked = false
        musicWasPlayingBeforeDuck = false
        duckedPackage = null
        audioFocusRequest = null // lost, nothing to abandon; the next duck builds a new request
        // restoreAudio early-returns on !ducked, so release the volume hold here or it would leak.
        com.kevin.midnightai.media.VolumeSync.holdForVoice(false)
        MidnightEvents.audioFocus("duck_state_cleared: focus lost, next duck re-requests")
    }

    private fun requestDuckFocus() {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        // GAIN_TRANSIENT_MAY_DUCK on purpose (never GAIN / GAIN_TRANSIENT): music should duck
        // (or at worst pause) for the turn and come back after it, keeping its own session.
        val result = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(attributes)
                    .setWillPauseWhenDucked(false)
                    .setOnAudioFocusChangeListener(focusListener, mainHandler)
                    .build()
                audioFocusRequest = req
                audioManager.requestAudioFocus(req)
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(
                    focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "audio focus request failed: ${e.message}")
            -1
        }
        MidnightEvents.audioFocus(
            "request MAY_DUCK result=${focusResultName(result)} playing=${duckedPackage ?: "none"} " +
                "source=${com.kevin.midnightai.media.MusicSource.current.value}",
        )
    }

    private fun abandonDuckFocus() {
        val result = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val r = audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
                audioFocusRequest = null
                r
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(focusListener)
            }
        } catch (e: Exception) {
            Log.w(TAG, "audio focus abandon failed: ${e.message}")
            -1
        }
        MidnightEvents.audioFocus("abandon result=${result?.let { focusResultName(it) } ?: "no request"}")
    }

    private fun focusResultName(r: Int): String = when (r) {
        AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> "granted"
        AudioManager.AUDIOFOCUS_REQUEST_FAILED -> "failed"
        2 -> "delayed" // AUDIOFOCUS_REQUEST_DELAYED, API 26
        else -> "error($r)"
    }

    private fun focusChangeName(c: Int): String = when (c) {
        AudioManager.AUDIOFOCUS_GAIN -> "GAIN"
        AudioManager.AUDIOFOCUS_LOSS -> "LOSS"
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> "LOSS_TRANSIENT"
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> "LOSS_TRANSIENT_CAN_DUCK"
        else -> c.toString()
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
        playbackExecutor.shutdown()
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
        playbackExecutor.shutdown()
    }

    private fun emit(event: LiveEvent) {
        main.launch { onEvent(event) }
    }

    companion object {
        private const val TAG = "GeminiLiveSession"

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
        // How long the playback backlog may sit unmoving before the mic re-open stops
        // waiting on it, so a stalled AudioTrack can't wedge the mic shut. A stall
        // window, not a total: a long reply that keeps playing is waited out.
        private const val PLAYBACK_DRAIN_TIMEOUT_MS = 1_500L
        // Absolute ceiling on that wait, whatever the backlog is doing.
        private const val PLAYBACK_DRAIN_CAP_MS = 45_000L
        private const val PLAYBACK_DRAIN_POLL_MS = 20L
        // After abandoning focus, how long the paused music app gets to resume itself on the
        // GAIN before we send it an explicit play().
        private const val POST_TURN_RESUME_DELAY_MS = 800L
        // Jitter buffer: audio queued in the track before each turn (and each
        // mid-turn re-prime) starts playing. Absorbs hotspot jitter and writer
        // scheduling hiccups on the slow head-unit CPU for ~200ms of added latency.
        private const val PLAYBACK_PRIME_MS = 200L
        // Server-VAD end-of-turn tuning. Gemini's default silence window is short
        // and cuts the driver off when they pause mid-sentence to think (the "iffy
        // turn-taking" field report); a longer window waits them out. prefixPadding
        // keeps a little audio before speech onset so the first word isn't clipped.
        private const val VAD_SILENCE_MS = 900
        private const val VAD_PREFIX_PADDING_MS = 300
        // Driver silence after Zero's turn that ends a hands-free conversation.
        private const val IDLE_TIMEOUT_MS = 10_000L
        // Longest the idle timer waits on an unanswered tool call. LiveSessionController cuts
        // ask_engine at 150s, but that cut can overrun by ~45s when a blocking
        // HttpURLConnection inside SubAgent is hung (withTimeoutOrNull cannot interrupt it),
        // so hold to ~200s; past that the response is treated as never coming.
        private const val MAX_PENDING_TOOL_HOLD_MS = 200_000L
        private const val PENDING_TOOL_POLL_MS = 250L
        // How long a parked (warm) socket stays connected with no use before it
        // fully closes. Long enough that follow-up turns through a drive resume
        // instantly; short enough that an idle warm connection (and its cloud
        // billing) doesn't linger. Reconnect is lazy on the next tap.
        private const val WARM_HOLD_MS = 3 * 60 * 1000L
        private const val NORMAL_CLOSE = 1000
        // Below this, a real (vadMode) conversational turn forwarded suspiciously little mic
        // audio - a healthy turn forwards tens of KB (see MidnightEvents.silentMicTurn's doc).
        // Triggers a forced non-fatal report, not just a breadcrumb, so the next field drive
        // actually produces retrievable Crashlytics data for B9/B10/B12.
        private const val SILENT_TURN_BYTES_THRESHOLD = 2_000L
    }
}

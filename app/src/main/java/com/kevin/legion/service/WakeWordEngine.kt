package com.kevin.legion.service

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.util.Log
import kotlin.math.abs
import com.kevin.legion.ai.CompanionProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File

/**
 * The shipping custom wake word: "hey <companion name>" (`.scratch/custom-wake-word/`,
 * `memory/library/decisions.md` 2026-07-19). Vosk with a runtime-reconfigurable grammar
 * built from [CompanionProfile.name] - the only approach that supports an arbitrary
 * driver-chosen name without per-phrase training at build time.
 *
 * Opt-in, off by default, supplements push-to-talk rather than replacing it: [start]
 * no-ops unless the driver has flipped the [WakeWordPreferences] Setup toggle on.
 *
 * On-hardware validation (2026-07-19): no false triggers across a real drive on the
 * "hey <name>" grammar; battery/CPU draw is a non-issue since the head unit is on shore
 * power the entire time the engine is running (parked-and-off is not a state this needs
 * to work in).
 */
object WakeWordEngine {
    private const val TAG = "WakeWordEngine"
    private const val SAMPLE_RATE = 16000.0f
    private const val EVENT_LIMIT = 20
    private const val MODEL_ASSET_DIR = "vosk-model"
    private const val WATCHDOG_INTERVAL_MS = 500L
    // 0.2s of audio per read, matching the cadence Vosk's own SpeechService used, so the
    // recognizer sees the same shaped chunks it always did.
    private const val BUFFER_SECONDS = 0.2f
    // One read is ~200ms, so a second is five of them. Bounded rather than indefinite: a wedged
    // audio driver must not deadlock the caller, which includes the wake-trigger path handing the
    // microphone to the live session.
    private const val CAPTURE_JOIN_TIMEOUT_MS = 1_000L
    // Consecutive watchdog ticks (500ms each) the capture may read as silenced before the engine
    // tears it down and tries again. Three is ~1.5s: long enough that an ordinary handoff settles
    // on its own without a pointless rebuild, short enough that the driver does not spend a minute
    // talking to something that stopped listening.
    private const val SILENCED_TICKS_BEFORE_REACQUIRE = 3

    // A single utterance produces several partial results plus a final one; without a
    // floor between triggers, one "hey <name>" would fire ACTION_TALK repeatedly before
    // the started conversation flips ConversationState.isBusy and the watchdog above
    // tears this recognizer down.
    private const val MIN_TRIGGER_GAP_MS = 4_000L

    /** One recognized utterance, for the on-screen debug panel (debug builds only). */
    data class Event(
        val atMs: Long,
        val text: String,
        val isFinal: Boolean,
        val hit: Boolean,
    )

    private val _events = MutableStateFlow<List<Event>>(emptyList())
    /** Last [EVENT_LIMIT] partial/final results, newest last - drives [com.kevin.legion.ui.WakeWordDebugPanel]. */
    val events = _events.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running = _running.asStateFlow()

    private var scope: CoroutineScope? = null
    private var model: Model? = null
    private var watchdogJob: Job? = null

    // stop() takes no Context but teardown has to unregister the recording callback, so the
    // application context (never an Activity - this outlives every screen) is held from start().
    @Volatile private var appContextForCallback: Context? = null

    // Ticket 08/12: this engine owns its own AudioRecord now, rather than letting Vosk's
    // SpeechService own a private one. That single change is what makes the microphone SOURCE,
    // its processing, and its silenced-state all choosable instead of inherited.
    private var captureJob: Job? = null
    @Volatile private var record: AudioRecord? = null
    private var recognizer: Recognizer? = null
    private var effects: List<AudioEffect> = emptyList()
    private var recordingCallback: AudioManager.AudioRecordingCallback? = null

    // Ticket 05 (`.scratch/wake-word/issues/05-mic-ownership.md`): this engine now goes through
    // MicArbiter for the mic instead of polling ConversationState.isBusy itself. This is the
    // watchdog's OWN belief about whether it currently holds the mic - lifted out of what used
    // to be a local var inside [startWatchdog]'s loop so [micPreemptionListener] can flip it the
    // instant a preemption happens, rather than the watchdog only finding out up to
    // WATCHDOG_INTERVAL_MS later on its next poll. Starts true: nothing has asked yet.
    @Volatile private var pausedForMic = true

    // Ticket 05: MicArbiter tells us we lost the mic via this, synchronously, on whichever
    // thread preempted us (a live conversation starting, or a ring-listening window opening).
    // MicArbiter's own doc explains why this must not block that thread - so the actual capture
    // teardown is dispatched onto our own [scope] rather than run inline here.
    private val micPreemptionListener = MicArbiter.Listener {
        Log.w(TAG, "preempted by a higher-priority MicArbiter claimant - releasing the microphone")
        pausedForMic = true
        scope?.launch { releaseSpeechService() }
    }

    private val _silenced = MutableStateFlow(false)
    /**
     * Ticket 08: true when the platform is handing this engine silence with no error and no state
     * change - another app won capture arbitration, or the device mic toggle is off. **A silenced
     * wake word is otherwise indistinguishable from a quiet room**, which is the failure this whole
     * map kept walking into. False on API < 29, where the platform offers no way to know either
     * way; that is a real gap, not a claim of safety.
     */
    val silenced = _silenced.asStateFlow()

    private val _peakLevel = MutableStateFlow(0)
    /**
     * Ticket 07/12: peak absolute PCM amplitude (0-32767) of the most recent buffer. Free once the
     * capture loop is ours, and it is the difference between "it did not trigger" and knowing
     * whether the microphone heard anything at all - which is exactly what the Jeep needed.
     */
    val peakLevel = _peakLevel.asStateFlow()
    private var targetWords: List<String> = emptyList()
    private var lastTriggerAtMs = 0L

    /**
     * Starts the engine if it isn't already running. Safe to call unconditionally (e.g.
     * from [AriaForegroundService.onCreate] on every launch, and again from the Setup
     * toggle the moment it's flipped on) - no-ops if the driver hasn't opted in, or if
     * already running.
     */
    fun start(context: Context) {
        if (!WakeWordPreferences.isEnabled(context)) return
        // The ambient-listening suppression that used to sit here is gone with the feature
        // (2026-08-21, `.scratch/proactive-mode/issues/12-retire-ambient-listening.md`). It was
        // always a no-op in practice: AmbientListenPreferences.setEnabled had no caller anywhere
        // and the flag defaulted to false, so the wake word was never actually suppressed.
        if (scope != null) return // already running

        val appContext = context.applicationContext
        appContextForCallback = appContext
        val runScope = CoroutineScope(Dispatchers.Default)
        scope = runScope
        runScope.launch {
            try {
                targetWords = buildTargetWords(appContext)
                // Ticket 09: a blank companion name builds an empty grammar, and an empty grammar
                // listens forever for a phrase nobody can say while still holding the microphone.
                // Refuse loudly instead - "running but deaf" is the exact shape this map keeps
                // finding, and it is indistinguishable from a quiet room.
                if (targetWords.isEmpty()) {
                    Log.w(TAG, "No companion name resolved - refusing to start with an empty grammar")
                    recordEvent("(no companion name - not listening)", isFinal = true, hit = false)
                    stop()
                    return@launch
                }
                val loadedModel = loadModel(appContext)
                model = loadedModel
                // Ticket 05: ask before opening the mic, rather than opening it unconditionally
                // and only finding out about contention on the watchdog's next poll. A refusal
                // here means a live turn or a ring-listening window already holds the mic at
                // this exact moment (rare at a cold start, but possible) - the watchdog's loop
                // below keeps retrying, same as it does after any later preemption.
                pausedForMic = !MicArbiter.request(MicArbiter.Claimant.WAKE_WORD, micPreemptionListener)
                if (!pausedForMic) beginListening(loadedModel, appContext)
                startWatchdog(loadedModel, appContext)
            } catch (e: Exception) {
                Log.e(TAG, "Vosk init failed: ${e.message}", e)
                recordEvent("(init failed: ${e.message})", isFinal = true, hit = false)
                stop()
            }
        }
    }

    /**
     * Rebuilds the grammar from the current companion name and, if listening, restarts
     * with it. Call on [AriaForegroundService.ACTION_CAR_SWITCHED] - same fix shape as
     * `drive-notes-batch` ticket 02 (avatar/persona/voice going stale on cold start):
     * [start] runs at service `onCreate`, before the OBD dongle has connected, so
     * `ActiveVehicle.current()` falls back to the default vehicle and the grammar is
     * built from a blank/wrong companion name - "hey zero" instead of "hey <real name>"
     * - and nothing ever rebuilt it once the real vehicle resolved. No-op if the
     * engine isn't running (a cold [start] afterward already reads the resolved name)
     * or if the name didn't actually change (avoids a pointless recognizer rebuild).
     */
    fun refresh(context: Context) {
        val loadedModel = model ?: return
        val appContext = context.applicationContext
        val newWords = buildTargetWords(appContext)
        if (newWords == targetWords) return
        targetWords = newWords
        // `record != null` is the post-refactor equivalent of the old `speechService != null`:
        // it means capture is actually open right now, rather than paused for a conversation.
        Log.d(TAG, "refresh: rebuilding grammar, capture open=" + (record != null))
        if (record != null) {
            releaseSpeechService()
            beginListening(loadedModel, appContext)
        }
        // else: paused for a conversation - the watchdog will resume with the
        // already-updated targetWords once it ends.
    }

    /** Stops and releases everything (mic, native model, coroutines). Safe to call repeatedly. */
    fun stop() {
        watchdogJob?.cancel()
        watchdogJob = null
        releaseSpeechService()
        // Idempotent no-op if we'd already been preempted (MicArbiter.release only clears the
        // claim when it's still ours) - safe to call unconditionally on every teardown path.
        MicArbiter.release(MicArbiter.Claimant.WAKE_WORD)
        pausedForMic = true
        model = null
        scope?.cancel()
        scope = null
        _running.value = false
    }

    /** Copies the bundled model out of assets on first use (Vosk needs a real filesystem path). */
    private fun loadModel(context: Context): Model {
        val dest = File(context.filesDir, MODEL_ASSET_DIR)
        if (!File(dest, "am/final.mdl").exists()) {
            copyAssetDir(context, MODEL_ASSET_DIR, dest)
        }
        return Model(dest.absolutePath)
    }

    private fun copyAssetDir(context: Context, assetPath: String, destDir: File) {
        val assets = context.assets
        val children = assets.list(assetPath).orEmpty()
        if (children.isEmpty()) {
            // Leaf: this "directory" listing came back empty because assetPath is a file.
            destDir.parentFile?.mkdirs()
            assets.open(assetPath).use { input -> destDir.outputStream().use { output -> input.copyTo(output) } }
            return
        }
        destDir.mkdirs()
        for (child in children) copyAssetDir(context, "$assetPath/$child", File(destDir, child))
    }

    /**
     * "Hey <name>" - a two-word phrase, not a bare word (custom-wake-word ticket 07,
     * 2026-07-19 field data). A bare single word false-triggers too easily on ordinary
     * cabin conversation, radio, or podcasts that happen to say a common short name; the
     * "hey" prefix is the weak-name guardrail that data called for.
     *
     * **Un-hardcoded 2026-08-20** (`.scratch/wake-word/issues/09-unhardcode-hey-moose.md`). From
     * 2026-07-21 until now this returned a fixed "hey moose" - a workaround for a head unit whose
     * companion name kept resolving blank, on hardware LEGION no longer targets. Its own note said
     * it masked the bug rather than fixing it, and that the frozen design (CLAUDE.md sec 8) is a
     * runtime grammar built from [CompanionProfile.name]. It was caught the first time this engine
     * ever ran in LEGION: the phone loaded "hey moose" while the active companion was Alfred, and
     * the new Settings row was already telling the driver to say "hey alfred".
     *
     * The near-homophone padding went with it. "hey mouse" and "hey moves" were guesses at how the
     * small model mishears "moose" specifically, and there is no way to guess equivalents for an
     * arbitrary driver-chosen name.
     *
     * **A blank name yields an EMPTY list, deliberately.** [start] refuses rather than building a
     * grammar of nothing and listening forever for a phrase that cannot be said - silently
     * listening for nothing is the failure this map keeps finding, and it is worse than not
     * starting. Un-hardcoding also restores [refresh] to the working part it was designed for:
     * the phrase list varies with the profile again, so a name change actually rebuilds it.
     */
    private fun buildTargetWords(context: Context): List<String> {
        val words = linkedSetOf<String>()
        val name = CompanionProfile.name(context).trim().lowercase()
        if (name.isNotBlank()) words.add("hey $name")
        return words.toList()
    }

    /**
     * Opens the microphone and drives [Recognizer] from our own read loop.
     *
     * **Retired Vosk's `SpeechService` (ticket 12).** That wrapper builds its `AudioRecord` with a
     * hardcoded `AudioSource.VOICE_RECOGNITION` and exposes no setter - and `VOICE_RECOGNITION`
     * applies deliberately minimal processing, because speech engines usually want raw audio. In a
     * Jeep at road speed that meant the wake word did not fire AT ALL, while ordinary tap-to-talk
     * heard Kevin perfectly on the same phone - because [GeminiLiveSession] opens
     * `VOICE_COMMUNICATION`, which brings noise suppression, gain and echo cancellation with it.
     * Kevin ruled out Bluetooth routing as the cause, so it is the processing, and the only way to
     * change the processing is to own the record. `SpeechService` was always a convenience wrapper
     * around `acceptWaveForm`, never a requirement.
     *
     * Owning it also buys the two things tickets 08 and 07 were blocked on: the silenced-state
     * signal, and a peak level per buffer.
     */
    private fun beginListening(loadedModel: Model, context: Context) {
        val grammar = JSONArray(targetWords + listOf("[unk]")).toString()
        val rec = Recognizer(loadedModel, SAMPLE_RATE, grammar)
        recognizer = rec

        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE.toInt(), AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        val chunk = Math.round(SAMPLE_RATE * BUFFER_SECONDS)
        val format = AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE.toInt())
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .build()
        val builder = AudioRecord.Builder()
            // The whole point of the refactor.
            .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            .setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(minBuf, chunk * 4))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) builder.setPrivacySensitive(true)

        val r = try {
            builder.build()
        } catch (e: Exception) {
            // Missing RECORD_AUDIO lands here. SpeechService used to throw IOException for this and
            // start() caught it; keep failing loudly rather than looking like a quiet room.
            Log.e(TAG, "AudioRecord build failed: " + e.message)
            recordEvent("(microphone unavailable)", isFinal = true, hit = false)
            return
        }
        if (r.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord not initialized - microphone may be in use")
            recordEvent("(microphone unavailable)", isFinal = true, hit = false)
            r.release()
            return
        }
        record = r
        effects = attachVoiceEffects(r.audioSessionId)
        registerSilenceWatch(context, r)

        // Mirrors GeminiLiveSession's own capture line on purpose: ticket 12 was diagnosed by
        // comparing the two paths' sources, and that comparison was only possible because one of
        // them said what it opened. Now both do.
        Log.d(
            TAG,
            "AudioRecord opened: source=VOICE_COMMUNICATION sessionId=" + r.audioSessionId +
                " effects=" + effects.size + " state=" + r.state,
        )
        r.startRecording()
        _running.value = true
        captureJob = scope?.launch(Dispatchers.IO) {
            val buf = ShortArray(chunk)
            try {
                while (isActive) {
                    val n = r.read(buf, 0, buf.size)
                    if (n < 0) {
                        // ERROR_INVALID_OPERATION / ERROR_DEAD_OBJECT. Say so rather than spinning
                        // silently, which would look exactly like a quiet room.
                        Log.w(TAG, "AudioRecord.read returned " + n + " - stopping capture")
                        recordEvent("(capture error)", isFinal = true, hit = false)
                        break
                    }
                    if (n == 0) continue
                    var peak = 0
                    for (i in 0 until n) {
                        val v = abs(buf[i].toInt())
                        if (v > peak) peak = v
                    }
                    _peakLevel.value = peak
                    if (rec.acceptWaveForm(buf, n)) {
                        handleResult(rec.result, isFinal = true, context)
                    } else {
                        handleResult(rec.partialResult, isFinal = false, context)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "capture loop ended: " + e.message)
            }
        }
    }

    /** Hardware echo cancellation, noise suppression and gain, where the device has them. */
    private fun attachVoiceEffects(sessionId: Int): List<AudioEffect> {
        val out = mutableListOf<AudioEffect>()
        try {
            if (AcousticEchoCanceler.isAvailable()) {
                AcousticEchoCanceler.create(sessionId)?.also { it.enabled = true; out.add(it) }
            }
            if (NoiseSuppressor.isAvailable()) {
                NoiseSuppressor.create(sessionId)?.also { it.enabled = true; out.add(it) }
            }
            // Not in GeminiLiveSession's set: a live turn is speech the driver deliberately aims at
            // the phone, while a wake word has to catch an aside from across a noisy cabin, so the
            // gain matters more here than it does there.
            if (AutomaticGainControl.isAvailable()) {
                AutomaticGainControl.create(sessionId)?.also { it.enabled = true; out.add(it) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Voice effects unavailable: " + e.message)
        }
        return out
    }

    /**
     * Ticket 08. The platform silences a losing capture with NO exception and NO state change, so
     * the only signal is [AudioRecordingConfiguration.isClientSilenced], matched to our own session
     * id - precisely what could not be done while Vosk owned the record privately.
     */
    private fun registerSilenceWatch(context: Context, r: AudioRecord) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Log.d(TAG, "API < 29: cannot detect platform silencing at all")
            return
        }
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val cb = object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
                val ours = configs.firstOrNull { it.clientAudioSessionId == r.audioSessionId }
                val now = ours?.isClientSilenced ?: false
                if (now != _silenced.value) {
                    _silenced.value = now
                    Log.w(TAG, if (now) "SILENCED by the platform - hearing nothing" else "no longer silenced")
                    recordEvent(
                        if (now) "(silenced - another app has the mic)" else "(hearing again)",
                        isFinal = true, hit = false,
                    )
                }
            }
        }
        recordingCallback = cb
        runCatching { am.registerAudioRecordingCallback(cb, null) }
    }

    private fun handleResult(hypothesisJson: String?, isFinal: Boolean, context: Context) {
        val raw = hypothesisJson ?: return
        val text = runCatching {
            val obj = JSONObject(raw)
            obj.optString("text", "").ifBlank { obj.optString("partial", "") }
        }.getOrDefault("").trim()
        if (text.isBlank() || text == "[unk]") return
        val hit = targetWords.any { w -> w.isNotBlank() && text.contains(w) }
        recordEvent(text, isFinal, hit)

        // Only a FINAL hit activates a real turn - a partial is a still-forming hypothesis
        // and firing on it would double up with the final result for the same utterance.
        if (isFinal && hit) triggerConversation(context)
    }

    private fun triggerConversation(context: Context) {
        val now = System.currentTimeMillis()
        if (now - lastTriggerAtMs < MIN_TRIGGER_GAP_MS) return
        lastTriggerAtMs = now
        // Release Vosk's own AudioRecord synchronously, right here, before Gemini's mic ever
        // opens - the watchdog's 500ms poll of ConversationState.isBusy is too slow (micLoop's
        // MIC_HANDOFF_MS is only 250ms), so without this there's a window where both AudioRecords
        // are open on the same mic and the new conversation captures silence. The watchdog's own
        // release call on its next tick is a harmless no-op once this has already run.
        releaseSpeechService()
        // Ticket 05: yield the claim ourselves rather than waiting to be preempted - the
        // conversation this triggers is about to become the LIVE_TURN holder anyway, and
        // releasing here (not just below in releaseSpeechService's caller) means MicArbiter's
        // state agrees with reality in the same synchronous window the mic handoff already
        // depends on, instead of lagging behind it until GeminiLiveSession's own request() runs.
        MicArbiter.release(MicArbiter.Claimant.WAKE_WORD)
        pausedForMic = true
        val intent = Intent(context, AriaForegroundService::class.java)
            .setAction(AriaForegroundService.ACTION_TALK)
            // Ticket 10: mark this turn as voice-opened so it gets spoken back to. A tap does not
            // need one - the driver is looking at the screen that just changed.
            .putExtra(AriaForegroundService.EXTRA_FROM_WAKE_WORD, true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
        else context.startService(intent)
    }

    private fun recordEvent(text: String, isFinal: Boolean, hit: Boolean) {
        val entry = Event(System.currentTimeMillis(), text, isFinal, hit)
        _events.value = (_events.value + entry).takeLast(EVENT_LIMIT)
    }

    /**
     * Releases the microphone and everything hanging off it. Safe to call repeatedly, and it must
     * stay synchronous for its caller in [fireTalkIntent]: the live session's mic opens ~250ms
     * later, so anything still holding this AudioRecord would leave two records on one microphone
     * and the conversation would capture silence.
     *
     * Order matters. The callback goes first (it closes over the record), then the record stops and
     * is released, then the effects, then the recognizer - releasing a Vosk `Recognizer` while the
     * loop could still call `acceptWaveForm` on it would be a native-side use-after-free.
     */
    private fun releaseSpeechService() {
        recordingCallback?.let { cb ->
            val am = appContextForCallback?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            runCatching { am?.unregisterAudioRecordingCallback(cb) }
        }
        recordingCallback = null
        _silenced.value = false

        // STOP, then JOIN, then release - in that order, and the join is not optional.
        //
        // Caught on the A25 the first time the new silence detector ran (2026-08-20): the engine
        // opened a second AudioRecord 8s after the first, on the ACTION_CAR_SWITCHED grammar
        // rebuild, and the NEW record came up silenced because the OLD one was still holding the
        // microphone. Cancelling a coroutine does not wait for it; the read loop was still parked
        // inside AudioRecord.read() on a record we had already released, so the platform still saw
        // a live client and arbitrated against us.
        //
        // Vosk's SpeechService did not have this bug - its stop() joins the recognizer thread - so
        // this was introduced by taking ownership of the capture loop, and it is exactly the
        // "running but deaf" failure this engine keeps producing. stop() first is what makes the
        // join fast: it forces the in-flight read() to return instead of blocking out its buffer.
        Log.d(TAG, "releaseSpeechService: hadRecord=" + (record != null) + " hadJob=" + (captureJob != null))
        val job = captureJob
        captureJob = null
        record?.let { r ->
            runCatching { if (r.recordingState == AudioRecord.RECORDSTATE_RECORDING) r.stop() }
        }
        runCatching {
            runBlocking { withTimeoutOrNull(CAPTURE_JOIN_TIMEOUT_MS) { job?.cancelAndJoin() } }
        }
        record?.let { r -> runCatching { r.release() } }
        record = null

        effects.forEach { runCatching { it.release() } }
        effects = emptyList()

        recognizer?.let { runCatching { it.close() } }
        recognizer = null

        _peakLevel.value = 0
        _running.value = false
    }

    /**
     * Ticket 05: no longer polls [ConversationState.isBusy] to decide when to pause - that was
     * the last written-down-precedence call site this engine had, and it is exactly the shape
     * ticket 05 replaced (see [MicArbiter]'s class doc). Pausing is now pushed to us immediately
     * by [micPreemptionListener] the instant a higher-priority claimant takes the mic, instead of
     * waiting up to [WATCHDOG_INTERVAL_MS] for the next poll to notice.
     *
     * What THIS loop still does, on a poll, because nothing pushes either of these to us:
     *  - **Retries acquiring the mic** while [pausedForMic] is true. MicArbiter has no "notify me
     *    when it's free again" mechanism - deliberately (see its class doc) - because this is the
     *    only claimant that ever needs one, so the retry loop lives here instead.
     *  - **Re-acquires after platform-level silencing** (ticket 08/12) - a DIFFERENT app winning
     *    OS-level capture arbitration, which MicArbiter cannot see or arbitrate at all: it only
     *    coordinates claimants inside this process.
     */
    private fun startWatchdog(loadedModel: Model, context: Context) {
        watchdogJob = scope?.launch {
            var silencedTicks = 0
            while (isActive) {
                delay(WATCHDOG_INTERVAL_MS)
                if (pausedForMic) {
                    if (MicArbiter.request(MicArbiter.Claimant.WAKE_WORD, micPreemptionListener)) {
                        pausedForMic = false
                        Log.d(TAG, "watchdog: mic granted - resuming")
                        beginListening(loadedModel, context)
                        silencedTicks = 0
                    }
                } else if (record != null && _silenced.value) {
                    // Found on the A25, 2026-08-20, and only visible because the silence detector
                    // existed to report it: resuming here reopened the microphone while the live
                    // session was STILL holding it, and the fresh capture came up silenced and
                    // stayed that way. ConversationState.isBusy going false is not the same fact as
                    // "the microphone is free" - the socket parks warm and lets go slightly later.
                    //
                    // So the wake word would go permanently deaf after the FIRST conversation of
                    // every launch, including the greeting that speaks on startup, and look
                    // completely normal doing it. That is very likely why it never fired in the
                    // Jeep while working in a driveway: a driveway test is often the first thing
                    // after launch, and a drive is not.
                    //
                    // Re-acquire rather than wait for a signal from the other engine. It fixes any
                    // app taking the microphone, not only ours, and it needs no coupling between
                    // the two.
                    silencedTicks++
                    if (silencedTicks >= SILENCED_TICKS_BEFORE_REACQUIRE) {
                        silencedTicks = 0
                        Log.w(TAG, "watchdog: still silenced - re-acquiring the microphone")
                        releaseSpeechService()
                        beginListening(loadedModel, context)
                    }
                } else if (!_silenced.value) {
                    silencedTicks = 0
                }
            }
        }
    }
}

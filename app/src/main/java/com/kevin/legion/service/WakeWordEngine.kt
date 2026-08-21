package com.kevin.legion.service

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.kevin.legion.ai.CompanionProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
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
    private var speechService: SpeechService? = null
    private var watchdogJob: Job? = null
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
        // Ambient listening (2026-07-22) is open-vocabulary and would already
        // catch "hey <name>" in its own transcript - running both would fight
        // over the mic for no benefit. Ambient supersedes the narrower wake word
        // whenever the driver has opted into it.
        if (AmbientListenPreferences.isEnabled(context)) return
        if (scope != null) return // already running

        val appContext = context.applicationContext
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
                beginListening(loadedModel, appContext)
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
        if (speechService != null) {
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

    private fun beginListening(loadedModel: Model, context: Context) {
        val grammar = JSONArray(targetWords + listOf("[unk]")).toString()
        val recognizer = Recognizer(loadedModel, SAMPLE_RATE, grammar)
        val svc = SpeechService(recognizer, SAMPLE_RATE)
        speechService = svc
        _running.value = true
        svc.startListening(object : RecognitionListener {
            override fun onPartialResult(hypothesis: String?) = handleResult(hypothesis, isFinal = false, context)
            override fun onResult(hypothesis: String?) = handleResult(hypothesis, isFinal = true, context)
            override fun onFinalResult(hypothesis: String?) = handleResult(hypothesis, isFinal = true, context)
            override fun onError(exception: Exception?) {
                Log.w(TAG, "Vosk recognition error: ${exception?.message}")
                recordEvent("(error: ${exception?.message})", isFinal = true, hit = false)
            }
            override fun onTimeout() {
                Log.d(TAG, "Vosk recognition timeout")
            }
        })
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

    private fun releaseSpeechService() {
        speechService?.let { svc ->
            runCatching { svc.stop() }
            runCatching { svc.shutdown() }
        }
        speechService = null
        _running.value = false
    }

    /**
     * Polls [ConversationState.isBusy] and fully tears down / rebuilds the recognizer
     * around real conversations, so this engine never holds the mic while Gemini Live
     * needs it - same busy-gate signal [AriaForegroundService]'s proactive engine and
     * [com.kevin.legion.vehicle.TelemetryRecorder] already use (CLAUDE.md sec 4.4).
     */
    private fun startWatchdog(loadedModel: Model, context: Context) {
        watchdogJob = scope?.launch {
            var pausedForConversation = false
            while (isActive) {
                delay(WATCHDOG_INTERVAL_MS)
                val busy = ConversationState.isBusy
                if (busy && !pausedForConversation) {
                    pausedForConversation = true
                    releaseSpeechService()
                } else if (!busy && pausedForConversation) {
                    pausedForConversation = false
                    beginListening(loadedModel, context)
                }
            }
        }
    }
}

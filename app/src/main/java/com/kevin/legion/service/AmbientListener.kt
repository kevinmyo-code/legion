package com.kevin.legion.service

import android.content.Context
import android.util.Log
import com.kevin.legion.ai.AgentResult
import com.kevin.legion.ai.SubAgent
import com.kevin.legion.billing.EntitlementManager
import com.kevin.legion.billing.RuntimeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File

/**
 * "Like having another person in the car" (Kevin, 2026-07-22): local, OPEN-
 * vocabulary transcription of ordinary cabin conversation (not the fixed 2-3
 * phrase grammar [WakeWordEngine] matches), occasionally reacting unprompted -
 * the tier-2 shape chosen over the two alternatives: pure on-device (nothing
 * ever reasons about it) and always-open Gemini Live audio (continuous cloud
 * cost + a live socket the whole drive). This keeps transcription local and
 * free (same bundled Vosk model as wake word - a small model already supports
 * open dictation, no bigger download needed) and only pays a Gemini call
 * periodically, on the accumulated text, not on raw audio.
 *
 * **Consent, not just a feature flag.** Unlike the wake word (which discards
 * everything except one fixed phrase), this transcribes ordinary conversation
 * from WHOEVER is in the cabin, not just the driver - a materially different
 * privacy posture. OFF BY DEFAULT ([AmbientListenPreferences]), and the driver
 * must knowingly opt in via Setup, not inherit wake word's consent. A
 * persistent on-screen indicator (Cruise/Lights Out) is required whenever this
 * is actually running - see the indicator composables in `ui/CruiseScreen.kt`.
 *
 * **The mute button is a hard listening gate, not just a speaking gate**
 * (Kevin's explicit requirement) - [ProactivePreferences.muted] stops this
 * engine from listening at all, not just from reacting. This is STRICTER than
 * [AriaForegroundService.speakProactive]'s existing mute check, which only
 * silences the SPEAKING half for other proactive sources.
 *
 * **Mic ownership:** mutually exclusive with [WakeWordEngine] - when ambient
 * listening is on, the driver saying "hey <name>" already shows up in this
 * open transcript, so the reasoning pass below can recognize being directly
 * addressed itself; running both would fight over the mic. See [start]'s guard
 * and [WakeWordEngine.start]'s matching one.
 *
 * **What "reacting" means:** NOT a raw canned line - the accumulated transcript
 * is handed to a background [SubAgent], told to react ONLY when it's genuinely
 * a good, in-character moment (never for private-sounding conversation between
 * passengers, never just because something was said), and the result (if any)
 * goes through [ProactiveBus.requestSpeak] - the exact mechanism idle chatter
 * and health alerts already use, so it inherits the same busy/in-call/mute
 * gating and speaks in Zero's actual voice/persona, not a hardcoded string.
 *
 * **Not yet wired into companion-memory:** the ambient transcript is its own
 * stream, separate from [com.kevin.legion.data.local.EpisodicTurn] (which
 * is tied to a real Gemini Live turn). Feeding ambient conversation into
 * long-term memory too is a natural future extension, not done here.
 */
object AmbientListener {
    private const val TAG = "AmbientListener"
    private const val SAMPLE_RATE = 16000.0f
    private const val MODEL_ASSET_DIR = "vosk-model"
    private const val WATCHDOG_INTERVAL_MS = 500L

    // How often the accumulated transcript is handed to the reasoning pass.
    // Long enough that a single sentence doesn't trigger a Gemini call on its
    // own (cost + noise), short enough that a reaction still feels timely.
    private const val REASONING_INTERVAL_MS = 45_000L

    // However often the reasoning pass runs, never react again sooner than
    // this - the rate-limit floor idle chatter already relies on, so a
    // conversation full of "good moments" can't turn into rapid-fire chatter.
    private const val MIN_REACTION_GAP_MS = 3 * 60 * 1000L

    data class Event(val atMs: Long, val text: String, val isFinal: Boolean)

    private val _events = MutableStateFlow<List<Event>>(emptyList())
    val events = _events.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running = _running.asStateFlow()

    private var scope: CoroutineScope? = null
    private var model: Model? = null
    private var speechService: SpeechService? = null
    private var watchdogJob: Job? = null
    private var reasoningJob: Job? = null

    // Accumulated FINAL utterances since the last reasoning pass. Cleared after
    // every pass (reacted or not) - same "distill and clear" shape as
    // MemoryConsolidator's episodic buffer, just in-memory instead of on disk
    // (this transcript was never meant to be durable - see the class doc).
    private val pendingTranscript = StringBuilder()
    private var lastReactionAtMs = 0L

    /**
     * Starts if not already running. No-ops unless: paid tier (nothing to
     * react WITH on free-tier Zero), the driver opted in
     * ([AmbientListenPreferences]), and NOT muted ([ProactivePreferences] -
     * the hard listening gate, see the class doc). Safe to call unconditionally
     * on every service launch, mirroring [WakeWordEngine.start].
     */
    fun start(context: Context) {
        if (EntitlementManager.mode.value != RuntimeMode.BYO_KEY) return
        if (!AmbientListenPreferences.isEnabled(context)) return
        if (ProactivePreferences.isMuted(context)) return
        if (scope != null) return // already running

        val appContext = context.applicationContext
        val runScope = CoroutineScope(Dispatchers.Default)
        scope = runScope
        runScope.launch {
            try {
                val loadedModel = loadModel(appContext)
                model = loadedModel
                beginListening(loadedModel)
                startWatchdog(loadedModel)
                startReasoningLoop(appContext)
            } catch (e: Exception) {
                Log.e(TAG, "Vosk init failed: ${e.message}", e)
                stop()
            }
        }
    }

    /** Stops and releases everything (mic, native model, coroutines, pending transcript). */
    fun stop() {
        watchdogJob?.cancel(); watchdogJob = null
        reasoningJob?.cancel(); reasoningJob = null
        releaseSpeechService()
        model = null
        scope?.cancel(); scope = null
        pendingTranscript.setLength(0)
        _running.value = false
    }

    /** Reuses the bundled model WakeWordEngine also copies - idempotent, same destination. */
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
            destDir.parentFile?.mkdirs()
            assets.open(assetPath).use { input -> destDir.outputStream().use { output -> input.copyTo(output) } }
            return
        }
        destDir.mkdirs()
        for (child in children) copyAssetDir(context, "$assetPath/$child", File(destDir, child))
    }

    /** No grammar argument = open-vocabulary dictation, unlike WakeWordEngine's constrained recognizer. */
    private fun beginListening(loadedModel: Model) {
        val recognizer = Recognizer(loadedModel, SAMPLE_RATE)
        val svc = SpeechService(recognizer, SAMPLE_RATE)
        speechService = svc
        _running.value = true
        svc.startListening(object : RecognitionListener {
            override fun onPartialResult(hypothesis: String?) {}
            override fun onResult(hypothesis: String?) = handleFinal(hypothesis)
            override fun onFinalResult(hypothesis: String?) = handleFinal(hypothesis)
            override fun onError(exception: Exception?) {
                Log.w(TAG, "Vosk recognition error: ${exception?.message}")
            }
            override fun onTimeout() {}
        })
    }

    private fun handleFinal(hypothesisJson: String?) {
        val raw = hypothesisJson ?: return
        val text = runCatching { JSONObject(raw).optString("text", "") }.getOrDefault("").trim()
        if (text.isBlank()) return
        synchronized(pendingTranscript) { pendingTranscript.append(text).append(". ") }
        val entry = Event(System.currentTimeMillis(), text, isFinal = true)
        _events.value = (_events.value + entry).takeLast(20)
    }

    private fun releaseSpeechService() {
        speechService?.let { svc -> runCatching { svc.stop() }; runCatching { svc.shutdown() } }
        speechService = null
        _running.value = false
    }

    /** Pauses/resumes around real conversations - same busy-gate shape as WakeWordEngine's watchdog. */
    private fun startWatchdog(loadedModel: Model) {
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
                    beginListening(loadedModel)
                }
            }
        }
    }

    /** Periodically hands the accumulated transcript to a background reasoning pass. */
    private fun startReasoningLoop(context: Context) {
        reasoningJob = scope?.launch {
            while (isActive) {
                delay(REASONING_INTERVAL_MS)
                if (ConversationState.isBusy) continue // don't reason mid-conversation, just wait
                val transcript = synchronized(pendingTranscript) {
                    val t = pendingTranscript.toString().trim()
                    pendingTranscript.setLength(0)
                    t
                }
                if (transcript.isBlank()) continue
                react(context, transcript)
            }
        }
    }

    private suspend fun react(context: Context, transcript: String) {
        val agent = SubAgent(systemInstruction = REASONING_SYSTEM_INSTRUCTION, useSearch = false)
        val result = agent.askTyped(context = "Overheard cabin conversation:\n$transcript", question = REASONING_QUESTION)
        val text = (result as? AgentResult.Success)?.text?.trim() ?: return
        if (text.isBlank() || text.equals("SILENT", ignoreCase = true)) return

        // Re-check every gate at the moment of reacting, not just at listen-time -
        // a lot can change during a 45s window plus a network round trip.
        val now = System.currentTimeMillis()
        if (now - lastReactionAtMs < MIN_REACTION_GAP_MS) return
        if (ConversationState.isBusy) return
        if (TelephonyController.isInCall) return
        if (ProactivePreferences.isMuted(context)) return
        lastReactionAtMs = now
        ProactiveBus.requestSpeak(
            "(System: you overheard the driver/cabin say something worth a quick, natural reaction - " +
                "here's what to say, in your own voice, one short line: \"$text\")"
        )
    }

    private const val REASONING_QUESTION =
        "Is there anything in this overheard cabin conversation genuinely worth a quick, natural " +
            "spoken reaction from a car companion who's just riding along? If yes, respond with ONLY " +
            "the one short line you'd say (plain text, no quotes, no markdown). If nothing is worth " +
            "reacting to - which is MOST of the time - respond with exactly the single word SILENT."

    // Plain constant, not CompanionIdentity-derived: this reasoning pass runs off
    // the main conversation entirely and never speaks directly - it only decides
    // WHETHER a reaction fits. ProactiveBus/the Live model supplies the actual
    // identity/persona for the spoken line itself once react() hands it a prompt.
    private const val REASONING_SYSTEM_INSTRUCTION = """
        You are deciding whether a car companion, quietly riding along and overhearing ordinary
        cabin conversation, should say anything right now. Be VERY conservative - most ordinary
        conversation, small talk, and back-and-forth between passengers deserves NO reaction at
        all. Stay silent for anything that sounds private, personal, or between other people in
        the car and not meant for you to weigh in on.

        Only react when it's a genuinely good, natural moment - something car-related worth a
        quick comment, a question that was effectively directed at you even without saying your
        name, or something notable enough that staying silent would feel odd. When in doubt, stay
        silent - a companion who comments on everything is annoying, not endearing.

        Never invent anything not actually said. Never claim feelings, sentience, or a need for
        the people in the car - if you react, it's a natural remark, not an emotional bid.
    """
}

package com.kevin.legion.service.cascade

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.util.Log
import com.kevin.legion.BuildConfig
import com.kevin.legion.ai.AriaBrain
import com.kevin.legion.ai.CrisisDetector
import com.kevin.legion.ai.CompanionProfile
import com.kevin.legion.ai.GeminiKeyProvider
import com.kevin.legion.ai.GeminiUsageMeter
import com.kevin.legion.ai.KeyHealth
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.ConversationAudit
import com.kevin.legion.data.local.auditContent
import com.kevin.legion.data.local.record
import com.kevin.legion.service.ChatTurn
import com.kevin.legion.service.CompanionPhase
import com.kevin.legion.greeting.GreetingBudget
import com.kevin.legion.greeting.GreetingOpening
import com.kevin.legion.greeting.GreetingPlan
import com.kevin.legion.service.FollowUpWindow
import com.kevin.legion.service.VoiceTurnRecorder
import com.kevin.legion.service.checkin.CheckInOutcome
import com.kevin.legion.service.checkin.CheckInRules
import com.kevin.legion.service.checkin.CheckIns
import com.kevin.legion.service.checkin.ClosingLines
import com.kevin.legion.service.GeminiLiveSession
import com.kevin.legion.service.LiveAudit
import com.kevin.legion.service.LiveLatency
import com.kevin.legion.service.LiveSessionController
import com.kevin.legion.service.ListenCue
import com.kevin.legion.service.LiveToolbox
import com.kevin.legion.service.MicArbiter
import com.kevin.legion.service.Phase
import com.kevin.legion.service.ProactiveBus
import com.kevin.legion.service.TelephonyController
import com.kevin.legion.service.TextChatEngine
import com.kevin.legion.service.TextToolExecutor
import com.kevin.legion.service.WakePhrases
import com.kevin.legion.service.conversationlog.ConversationLog
import com.kevin.legion.service.turn.BargeInGate
import com.kevin.legion.service.turn.CallAudioMode
import com.kevin.legion.service.turn.EndOfTurnPolicy
import com.kevin.legion.service.turn.MicReopen
import com.kevin.legion.service.turn.TurnModels
import com.kevin.legion.service.turn.TurnTaker
import com.kevin.legion.service.turn.VoiceTurnSettings
import com.kevin.legion.vehicle.ActiveVehicle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.time.LocalTime
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * One conversation on the laptop pipeline (Kevin, 2026-11-10: "full, wake word to tts the
 * alternative"): the phone records and decides turns; speech-to-text and text-to-speech run on the
 * laptop's voice service; Gemini text with tools runs on the phone in between.
 *
 * Built BESIDE [GeminiLiveSession], not on it: the mic loop and playback are trimmed copies of
 * Live's, so nothing on the proven path changed for this. What it shares is code that was already
 * shared by design: [TurnTaker] (Silero, Smart Turn, [BargeInGate]), [FollowUpWindow],
 * [CallAudioMode], [MicArbiter], [LiveAudit], [LiveLatency], and the controller's tool runner.
 *
 * Lifecycle: [open] (probe, connect, ack line, listen) -> turns -> [close] or a hand-over.
 *
 * **Laptop, then the cloud voice, then Live; never silence** (Kevin, 2026-11-15: "lets change the
 * fallback to the text+gemini voice, so that we save costs from the sts"). The speech half is a
 * [SpeechBackend]; everything else here is the same on either:
 * - At open, a laptop that is not set up, busy, unreachable or refuses the socket: the conversation
 *   opens on [CloudSpeech] (Gemini speech-to-text, the same text loop and tools, Gemini TTS in Puck).
 * - Mid-way, a laptop that drops or whose STT or TTS fails: the SAME conversation carries on, on the
 *   cloud voice ([swapToCloud]). The turn is never run again: the model and the tools ran on the
 *   phone, so a lost reply is voiced again from its text and lost words are transcribed again from
 *   the audio the phone kept ([TurnUtterance]). No write runs twice by construction.
 * - Live only when the cloud voice cannot start or fails, or the phone itself fails (turn models
 *   missing, mic, Silero, a crash): this session releases everything first and Live is told what was
 *   said and done ([Handover]) so Kevin never repeats himself.
 *
 * Threading: session state changes run on [loop] (one thread at a time). The mic runs on IO and
 * talks to [TurnTaker]; its sink posts back to [loop].
 */
@Suppress("TooManyFunctions", "LargeClass")
internal class CascadeSession(
    context: Context,
    private val host: CascadeHost,
    private val fromWakeWord: Boolean,
    private val oneShot: Boolean,
    /** ADR 0091: the personal greeting's pack and topic memory; null keeps the scripted ack alone. */
    private val greeting: GreetingOpening? = null,
    /** Engine LAPTOP: try the laptop first. Engine CLOUD: straight to the cloud voice. */
    private val laptopFirst: Boolean = true,
    /** A line the model is asked for first (Brief me, a proactive line), instead of a greeting. */
    private val opener: CascadeOpener? = null,
    /** Who holds the mic: a tapped conversation, or a ring window a real conversation may take. */
    private val micClaimant: MicArbiter.Claimant = MicArbiter.Claimant.LIVE_TURN,
    /** The cloud voice, in the companion's own voice (a fake in a test). */
    private val cloudSpeech: (CoroutineScope, (CallUsage) -> Unit) -> SpeechBackend = { parent, usage ->
        defaultCloudSpeech(parent, CompanionProfile.voice(context), usage)
    },
    /** Whether the cloud voice can start at all: it runs on the key Kevin pasted in Settings. */
    private val cloudCanStart: () -> Boolean = GeminiKeyProvider::hasKey,
) {
    private val ctx = context.applicationContext
    private val audioManager = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    /**
     * Anything thrown in this session's coroutines ends in Live, never in a dead assistant or a
     * crashed process (the reason the whole pipeline is allowed to exist while Kevin travels).
     */
    private val crashHandler = CoroutineExceptionHandler { _, t ->
        Log.w(TAG, "laptop pipeline failed", t)
        onCrash(t)
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + crashHandler)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val loop = Dispatchers.Default.limitedParallelism(1)

    private val audit = LiveAudit()
    private val ackLines = ACK

    /** Where speech goes now: the laptop, or the cloud voice after a fallback. Changed only on [loop]. */
    @Volatile private var backend: SpeechBackend? = null
    private var models: TurnModels.State.Ready? = null
    private var textLoop: GeminiTextLoop? = null
    @Volatile private var prefix: CascadePrefix? = null

    /** The prompt build started at open ([buildPrompt]); null until then. */
    @Volatile private var prefixReady: Deferred<CascadePrefix>? = null
    @Volatile private var preambleReady: Deferred<Unit>? = null

    /** The cost audit: every billed call's counts, summed at the end ([UsageLedger]). */
    private val usageLedger = UsageLedger()
    private val enginesUsed = java.util.concurrent.CopyOnWriteArrayList<String>()
    private val turnsAnswered = AtomicInteger(0)
    private var greetingLoop: GeminiTextLoop? = null

    /** Speculative answers (the setting, read once at open) and the one draft in flight ([TurnDraft]). */
    private var speculate = false
    @Volatile private var draft: TurnDraft? = null
    private var specPollJob: Job? = null
    private var cacheStore: ContextCacheStore? = null

    /** The open's start, for `open_first_audio_ms` (once). */
    private var openStartedAtMs = 0L
    private var firstAudioLogged = false
    @Volatile private var preamble = ""
    private var plan: CascadeRules.TurnPlan? = null
    @Volatile private var turnTaker: TurnTaker? = null

    /** The mic feeds [turnTaker] under this lock, so a swap never lands inside a feed. */
    private val turnLock = Any()

    /** A backend swap came while he was speaking: the new turn plan waits for that turn to end. */
    private var replanPending = false

    /** False for a speak-only line (a proactive line nobody answers): no mic, closes once said. */
    private val listens = opener?.listen != false

    /** Whether this session has a mic to go back to after a reply (the controller's tap reads it). */
    val listensForReply: Boolean get() = listens

    /** A background line nobody asked for: its failure is not put on screen. */
    private val quiet = opener != null && !opener.listen && !opener.userInitiated

    /** Focus, the car's hands-free mic, and the Bluetooth watch ([CascadeAudio]). */
    private val audioRoute = CascadeAudio(ctx, audioManager)
    private var player: ReplyPlayer? = null
    private var callAudio: CallAudioMode? = null
    private var micJob: Job? = null
    @Volatile private var micClaimed = false
    private var focus: AudioFocusRequest? = null
    private var auditStarted = false
    private var oneShotRequested = false
    private var uttCapJob: Job? = null

    private val closing = AtomicBoolean(false)
    @Volatile var isOpen = true
        private set

    /** True while a reply is audible (or its tail is still settling): what [TurnTaker.feed] calls `playing`. */
    @Volatile private var playbackActive = false
    @Volatile private var currentUtt: TurnUtterance? = null
    @Volatile private var lastLocalSpeechAtMs = 0L

    private var turnJob: Job? = null
    private var speaker: ReplySpeaker? = null
    @Volatile private var speaking = false
    private val history = mutableListOf<ChatTurn>()
    private var carry = ""
    private var turn: TurnRecord? = null
    private var dismissAfterReply = false
    private var answeredOnce = false
    private val turnSeq = AtomicLong(0)
    private val toolSeq = AtomicInteger(0)
    private val toolsInFlight = AtomicInteger(0)

    /** "Save my turns" (Settings, off by default): his turns' audio kept on the phone ([VoiceTurnRecorder]). */
    private val saveTurns = VoiceTurnRecorder.enabled(ctx)

    private fun keepTurnAudio(u: TurnUtterance) {
        if (saveTurns) u.keptAudio()?.let { VoiceTurnRecorder.keep(ctx, it) }
    }

    /**
     * A listening check-in's progress (alfred-greets ticket 03): [CheckInStep.LINE] while its line
     * plays, [CheckInStep.LISTENING] in the reply window, [CheckInStep.DONE] once he spoke or it
     * closed. [CheckInStep.NONE] for every other conversation.
     */
    private var checkInStep = if (opener?.checkIn != null) CheckInStep.LINE else CheckInStep.NONE

    private enum class CheckInStep { NONE, LINE, LISTENING, DONE }

    private var followUp = FollowUpWindow.State()
    private var followUpJob: Job? = null
    private var followUpArmedAtMs = 0L

    /** What one turn has done so far: what [Handover] needs if the laptop drops mid-turn. */
    private class TurnRecord(val heard: String, val fromUser: Boolean = true) {
        val reply = StringBuffer()
        val toolsRan = mutableListOf<String>()
        val writesDone = mutableListOf<String>()
        @Volatile var readThrough = false
        @Volatile var fillerPlayed = false
    }

    // --- open ------------------------------------------------------------------------------------

    fun open() {
        host.phase(Phase.CONNECTING, "Connecting...")
        scope.launch(loop) {
            try {
                openNow()
            } catch (e: CancellationException) {
                throw e
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                Log.w(TAG, "open failed", e)
                handOver(Handover.Moment.AtOpen, "open", "exception_${e.javaClass.simpleName}")
            }
        }
    }

    @Suppress("ReturnCount", "LongMethod", "CyclomaticComplexMethod") // one worded exit per open step
    private suspend fun openNow() {
        val started = System.currentTimeMillis()
        openStartedAtMs = started
        // The greeting's pack is fetched from the tap, overlapping the probe, connect and prompt
        // build, so it is long since answered (or timed out) by the time the line is due.
        val greetingPlan = if (opener == null) {
            greeting?.let { g -> scope.async(Dispatchers.IO) { g.prepare(LocalTime.now()) } }
        } else {
            null
        }
        val config = VoiceEngineSettings.laptop(ctx)
        CascadeWarmup.conversationOpen = true
        // The laptop is probed and connected while the mic and the turn models get ready (a wake
        // word's onset may have started it already); [tryLaptop] takes it.
        if (laptopFirst) LaptopPrewarm.start(ctx, "open")
        if (listens) {
            // The mic first, as Live claimed it before it connected: the wake capture lets go while
            // the backend opens, so the first capture is not refused.
            if (!MicArbiter.request(micClaimant) { scope.launch(loop) { onMicPreempted() } }) {
                LaptopPrewarm.cancel("open_aborted")
                host.notice("Didn't start - the microphone is in use")
                shutdown("mic_held")
                host.closed("mic_held")
                return
            }
            micClaimed = true
        }
        LiveLatency.log(LiveLatency.TAG, "open_mic_ms=${System.currentTimeMillis() - started}")
        // Either voice needs the phone's turn detection to listen: without it, nothing can.
        val models = if (listens) {
            withContext(Dispatchers.IO) { TurnModels.awaitReady(ctx, CascadeTimings.MODEL_WAIT_MS) }
        } else {
            null
        }
        LiveLatency.log(LiveLatency.TAG, "open_models_ms=${System.currentTimeMillis() - started}")
        if (listens && models == null) {
            LaptopPrewarm.cancel("open_aborted")
            handOver(Handover.Moment.AtOpen, "open", "turn_models_unavailable")
            return
        }
        this.models = models
        val opened = openBackend(config, started) ?: return
        LiveLatency.log(LiveLatency.TAG, "open_backend_ms=${System.currentTimeMillis() - started}")
        val b = opened.first
        val probeMs = opened.second
        backend = b
        if (closing.get()) return
        requestFocus()

        // The prompt and the cache build in the background; nothing he hears waits for them
        // (2026-11-15: phone logs showed the opening line ~9 s after the tap, held behind ~6.5 s of
        // prompt building and the cache create). See [buildPrompt].
        val brain = AriaBrain.get(ctx)
        val cache = CACHE
            ?: ContextCacheStore(
                OkHttpGemini(),
                CACHE_SCOPE,
                memo = PrefsCacheMemo(ctx),
                log = { LiveLatency.log(LiveLatency.TAG, it) },
            ).also { CACHE = it }
        // The cache this conversation creates is priced with it (storage runs for its TTL).
        cache.onCreated = { model, tokens, ttlS -> usageLedger.cacheCreated(model, tokens ?: 0, ttlS) }
        buildPrompt(brain, cache, started)
        textLoop = newTextLoop(cache, CallUsage.LLM)
        greetingLoop = newTextLoop(cache, CallUsage.GREETING)
        cacheStore = cache
        enginesUsed.add(b.kind.token)
        speculate = listens && VoiceEngineSettings.speculate(ctx)
        wireEarlySignals(b)

        val smartReady = models?.smartTurn != null && TurnModels.smartTurnBroken == null
        val p = CascadeRules.turnPlan(
            serviceEndsTurns = b.stt.endsTurns,
            smartSetting = VoiceTurnSettings.smartEndOfTurn(ctx),
            smartTurnReady = smartReady,
            bargeSetting = VoiceTurnSettings.bargeIn(ctx),
            route = CallAudioMode.route(audioManager),
            inCall = TelephonyController.isInCall,
        )
        plan = p
        if (models != null) {
            turnTaker = TurnTaker(
                p.config,
                models.silero.stream(),
                models.smartTurn.takeIf { p.config.smartTurn },
                sink,
                p.endBaseMs,
            )
            if (p.config.bargeIn) {
                val mode = CallAudioMode(audioManager)
                LiveLatency.log(TurnTaker.BARGE_TAG, "call audio ${mode.enter()} (cascade, ${b.kind.token})")
                callAudio = mode
                audioRoute.watchForBluetooth { onBluetoothConnected() }
            } else {
                // In the car: the car's own hands-free mic, as Live did (Kevin, 2026-11-10).
                callAudio = audioRoute.enterBluetoothMic(b.kind.token)
            }
        }
        player = ReplyPlayer(b.tts.sampleRate) { callAudio?.isActive == true }
        audit.enabled = ConversationLog.auditWantedAfterReading(ctx)
        audit.sessionStart("${b.kind.token}+$MODEL", emptySet(), brain.basePackToken)
        auditStarted = true
        LiveLatency.log(
            "LIVE_TURN",
            "config manual=true bargeIn=${p.config.bargeIn} smartTurn=${p.config.smartTurn} (${p.config.note})",
        )
        val openMs = System.currentTimeMillis() - started
        LiveLatency.log(LiveLatency.TAG, CascadeLines.open(openMs, probeMs, p, b.tts.streamsText, b.kind.token))
        if (listens) startMic()
        if (closing.get()) return
        if (opener != null) {
            speakOpener(opener)
            return
        }
        val plan = greetingPlan?.await()
        LiveLatency.log(LiveLatency.TAG, "open_greeting_plan_ms=${System.currentTimeMillis() - started} " +
            "plan=${plan?.javaClass?.simpleName ?: "none"}")
        if (closing.get()) return
        if (plan is GreetingPlan.Personal) {
            speakGreeting(plan)
        } else {
            if (plan is GreetingPlan.Scripted) {
                LiveLatency.log(LiveLatency.TAG, "greeting scripted reason=${plan.reason}")
            }
            speakScripted(ackLines.next())
        }
    }

    /**
     * The model's prompt, built off the open path (2026-11-15). Three phases, each logged
     * (`open_base_ms`, `open_declarations_ms`, `open_live_context_ms`, `open_prefix_ready_ms`) so the
     * next audit can see which one is slow:
     * - the base instruction (cached by [AriaBrain] for its TTL; cold it reads memories and the pack),
     * - the tool declarations, then the cache create (only for a conversation that listens),
     * - the live context for the first turn's preamble (clock, place by reverse geocode, continuity
     *   from the engine's memory list): the slowest and the least needed, so it never holds the
     *   cache, and the first turn waits for it only [CascadeTimings.LIVE_CONTEXT_WAIT_MS].
     * The opening line needs none of it; the greeting and the first turn await [prefixReady].
     */
    private fun buildPrompt(brain: AriaBrain, cache: ContextCacheStore, started: Long) {
        // Prepared ahead (app start, a wake word's onset, the last conversation): no build at all.
        val prepared = CascadePrep.fresh()
        LiveLatency.log(
            LiveLatency.TAG,
            "open_prompt prepared=${prepared != null}" +
                (prepared?.let { " age_s=${(System.currentTimeMillis() - it.builtAtMs) / MS_PER_S}" } ?: ""),
        )
        val base = scope.async(Dispatchers.IO) {
            val t = System.currentTimeMillis()
            (prepared?.split ?: CascadePrompt.split(brain.buildBaseInstruction())).also {
                LiveLatency.log(LiveLatency.TAG, "open_base_ms=${System.currentTimeMillis() - t}")
                // The date paragraph alone until the live context lands.
                preamble = CascadePrompt.preamble(it.volatile, "")
            }
        }
        prefixReady = scope.async(Dispatchers.IO) {
            val split = base.await()
            val t = System.currentTimeMillis()
            val built = prepared?.prefix ?: CascadePrefix(
                MODEL,
                split.cacheable,
                CascadeRules.declarations(LiveToolbox.declarations()),
            ).also { CascadePrep.offer(split, it) }
            LiveLatency.log(LiveLatency.TAG, "open_declarations_ms=${System.currentTimeMillis() - t}")
            built.also {
                prefix = it
                LiveLatency.log(LiveLatency.TAG, "open_prefix_ready_ms=${System.currentTimeMillis() - started}")
                // Reused across conversations while it lives (same prompt, same key): the line says which.
                LiveLatency.log(LiveLatency.TAG, "open_cache state=${cache.state(it)}")
                // A speak-only line makes one round: a cache it would create costs more than it saves.
                if (listens) cache.warm(it)
            }
        }
        preambleReady = scope.async(Dispatchers.IO) {
            val split = base.await()
            val t = System.currentTimeMillis()
            val live = runCatching { brain.buildLiveContext() }.getOrDefault("")
            LiveLatency.log(LiveLatency.TAG, "open_live_context_ms=${System.currentTimeMillis() - t}")
            preamble = CascadePrompt.preamble(split.volatile, live)
        }
    }

    /**
     * The prompt for a model call: waits for the build started at open (null if it failed), then up
     * to [liveContextWaitMs] for the live context (clock, place, car notes) so the call carries it;
     * past that it goes with the date paragraph alone. A cancel (tap, barge-in, merge) propagates.
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException") // a failed build is a null prompt
    private suspend fun awaitPrompt(liveContextWaitMs: Long = CascadeTimings.LIVE_CONTEXT_WAIT_MS): CascadePrefix? {
        val p = prefix ?: try {
            prefixReady?.await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (liveContextWaitMs > 0 && preambleReady?.isCompleted == false) {
            withTimeoutOrNull(liveContextWaitMs) {
                try {
                    preambleReady?.await()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Unit
                }
            }
        }
        return p
    }

    /**
     * The laptop first; anything that keeps it from opening opens the cloud voice instead (Kevin,
     * 2026-11-15). Null when neither can start: Live has the conversation by then. The second value
     * is the probe's time, for the open line.
     */
    @Suppress("ReturnCount") // the laptop, nothing (Live retired), or the cloud voice
    private suspend fun openBackend(config: LaptopVoiceConfig, started: Long): Pair<SpeechBackend, Long>? {
        // Engine CLOUD: no laptop is asked at all.
        // The connection started ahead ([LaptopPrewarm]) first; else the usual probe and connect.
        val laptop = if (laptopFirst) {
            takePrewarmed() ?: tryLaptop(config, started)
        } else {
            LaptopTry(null, CLOUD_ENGINE, -1L)
        }
        val probeMs = laptop.probeMs
        laptop.speech?.let { return it to probeMs }
        val refusal = laptop.refusal
        val next = CascadeRules.nextAfter(SpeechBackend.Kind.LAPTOP, cloudCanStart())
        if (refusal != CLOUD_ENGINE) {
            LiveLatency.log(LiveLatency.TAG, CascadeLines.fallback("laptop", next.name.lowercase(), "open", refusal))
        }
        if (next == CascadeRules.Next.LIVE) {
            handOver(
                Handover.Moment.AtOpen,
                "open",
                refusal,
                from = SpeechBackend.Kind.CLOUD,
                words = VoiceEngineSettings.cloudFailedWords("no_key", refusal.takeIf { it != CLOUD_ENGINE }),
            )
            return null
        }
        if (refusal != CLOUD_ENGINE) {
            val words = VoiceEngineSettings.statusWords(refusal)
            VoiceEngineSettings.noteStatus(ctx, words)
            if (!quiet) host.notice("Laptop voice: $words")
        }
        return cloudSpeech(scope, ::noteUsage) to probeMs
    }

    /** What asking the laptop gave: its backend, or why not (a [statusWords] token); the probe's time. */
    private class LaptopTry(val speech: SpeechBackend?, val refusal: String, val probeMs: Long)

    /** Probe and connect the laptop voice service, each step's refusal as a worded token. */
    @Suppress("ReturnCount") // one worded exit per open step
    private suspend fun tryLaptop(config: LaptopVoiceConfig, started: Long): LaptopTry {
        // A laptop that just failed to answer is not asked again for a little while: the wake word
        // must not wait out the probe on every conversation while Tailscale is off.
        if (config.isComplete && started - lastUnreachableAtMs < CascadeTimings.RECENT_UNREACHABLE_MS) {
            return LaptopTry(null, "unreachable_recent", -1L)
        }
        val probe = if (config.isComplete) LaptopProbe.probe(config, CascadeTimings.PROBE_TIMEOUT_MS) else null
        lastUnreachableAtMs = if (probe is LaptopProbe.Result.Unreachable) System.currentTimeMillis() else 0L
        val probeMs = when (probe) {
            is LaptopProbe.Result.Ready -> probe.ms
            is LaptopProbe.Result.Busy -> probe.ms
            is LaptopProbe.Result.Unreachable -> probe.ms
            else -> -1L
        }
        CascadeRules.openRefusal(config.isComplete, turnModelsReady = true, probe)?.let {
            LiveLatency.log(LiveLatency.TAG, "cascade_probe_ms=$probeMs probe=${probe?.token ?: "none"}")
            return LaptopTry(null, it, probeMs)
        }
        var laptop: LaptopSpeech? = null
        val c = LaptopVoiceConnection(
            connector = OkHttpWire(),
            config = config,
            clientName = "legion-phone/${BuildConfig.VERSION_CODE}",
            scope = scope,
            onDrop = { reason -> laptop?.let { b -> scope.launch(loop) { onBackendDropped(b, reason) } } },
        )
        val speech = LaptopSpeech(c)
        laptop = speech
        return when (val r = c.open(CascadeTimings.OPEN_TIMEOUT_MS)) {
            is LaptopVoiceConnection.OpenResult.Failed -> {
                lastUnreachableAtMs = System.currentTimeMillis()
                LaptopTry(null, "connect_${r.reason}", probeMs)
            }
            LaptopVoiceConnection.OpenResult.Ready -> {
                VoiceEngineSettings.noteStatus(ctx, VoiceEngineSettings.statusWords("ok"))
                LaptopTry(speech, "ok", probeMs)
            }
        }
    }

    /**
     * The laptop connection started at the wake word's onset or at the top of [openNow]
     * ([LaptopPrewarm]): its probe and hello/ready already ran (or are running) while the mic and the
     * turn models got ready. Null: nothing in hand, or it dropped; the usual probe and connect follow.
     */
    @Suppress("ReturnCount")
    private suspend fun takePrewarmed(): LaptopTry? {
        val a = LaptopPrewarm.take() ?: return null
        val waitMs = CascadeTimings.PROBE_TIMEOUT_MS + CascadeTimings.OPEN_TIMEOUT_MS
        val r = withTimeoutOrNull(waitMs) { runCatching { a.result.await() }.getOrNull() }
        when (r) {
            is LaptopPrewarm.Opened.Ready -> {
                if (r.connection.isDropped) {
                    LaptopPrewarm.release(a, "dropped")
                    return null
                }
                val speech = LaptopSpeech(r.connection)
                a.onDrop = { reason -> scope.launch(loop) { onBackendDropped(speech, reason) } }
                VoiceEngineSettings.noteStatus(ctx, VoiceEngineSettings.statusWords("ok"))
                return LaptopTry(speech, "ok", r.probeMs)
            }
            is LaptopPrewarm.Opened.Refused -> {
                if (r.token.startsWith("unreachable") || r.token.startsWith("connect_")) {
                    lastUnreachableAtMs = System.currentTimeMillis()
                }
                LiveLatency.log(LiveLatency.TAG, "cascade_probe_ms=${r.probeMs} probe=${r.token} prewarmed=true")
                return LaptopTry(null, r.token, r.probeMs)
            }
            null -> {
                LaptopPrewarm.release(a, "slow")
                return null
            }
        }
    }

    // --- the door from the controller ------------------------------------------------------------

    /**
     * A tap (or a second wake word) while this conversation is open: interrupts a reply that is
     * playing (ADR 0081), otherwise ends the conversation, the same two things a tap does on Live.
     * Returns true when it interrupted.
     */
    fun onTap(): Boolean {
        if (speaking) {
            scope.launch(loop) { interrupt("tap") }
            return true
        }
        close("stopped", userEnded = true)
        return false
    }

    /**
     * A line the model is asked for while this conversation is open (a proactive line, Brief me): it
     * runs after the turn in flight, as Live folded such a line into the open conversation. False
     * when this session cannot take it (closing, or a speak-only line still playing).
     */
    fun say(line: CascadeOpener): Boolean {
        // Still opening (no prompt yet): the line would end the conversation he just opened.
        if (closing.get() || !listens || prefixReady == null) return false
        scope.launch(loop) {
            val previous = turnJob
            turnJob = scope.launch(loop) {
                previous?.join()
                if (!closing.get()) answerTurn(line.prompt, null, promptRecord(line))
            }
        }
        return true
    }

    private fun promptRecord(o: CascadeOpener) = TurnRecord(o.prompt, fromUser = false).also {
        it.readThrough = o.readThrough
    }

    /** The opener's prompt as the first turn (no greeting, no ack line). */
    private fun speakOpener(o: CascadeOpener) {
        turnJob = scope.launch(loop) { answerTurn(o.prompt, null, promptRecord(o)) }
    }

    /** The side key while open: end after the next answer. */
    fun makeOneShot() {
        // After the next ANSWER, not after the opening line that may be playing now.
        scope.launch(loop) { oneShotRequested = true }
    }

    /** Ends the conversation. [userEnded] shows "Ended the chat". Safe to call more than once. */
    fun close(reason: String, userEnded: Boolean = false) {
        scope.launch(loop) {
            if (closing.get()) return@launch
            shutdown(reason)
            if (userEnded) host.notice("Ended the chat - tap to start another")
            host.closed(reason)
        }
    }

    /** The controller is going away or Live is taking the mic: release everything now. */
    fun destroy() {
        close("destroyed")
    }

    // --- turns -----------------------------------------------------------------------------------

    private val sink = object : TurnTaker.Sink {
        override fun sendActivityStart() {
            val b = backend ?: return
            var self: TurnUtterance? = null
            val inner = b.stt.begin {
                val utt = self ?: return@begin
                scope.launch(loop) { onServiceEnd(utt) }
            }
            // The laptop's words are also kept on the phone: if it fails, the cloud hears them. On
            // the cloud voice they are kept for a speculative draft's transcription.
            val keep = b.kind == SpeechBackend.Kind.LAPTOP || speculate || saveTurns
            val u = TurnUtterance(inner, b, keepAudio = keep)
            self = u
            currentUtt = u
            scope.launch(loop) {
                onUserTurnStart()
                if (speculate && b.kind == SpeechBackend.Kind.CLOUD) watchCloudSilence(u)
                // A wall-clock cap under the service's 30 s: in steady noise the silence cap never
                // fires, and a turn must still end.
                uttCapJob?.cancel()
                uttCapJob = scope.launch(loop) {
                    delay(CascadeTimings.UTTERANCE_CAP_MS)
                    if (currentUtt === u) {
                        currentUtt = null
                        keepTurnAudio(u)
                        u.end()
                        turnTaker?.reset()
                        onTurnEnd(u, System.currentTimeMillis(), CascadeRules.EotSource.PHONE_CAP, null)
                    }
                }
            }
        }

        override fun sendAudio(pcm: ByteArray, offset: Int, length: Int) {
            currentUtt?.audio(pcm, offset, length)
        }

        override fun sendActivityEnd() {
            val u = currentUtt ?: return
            currentUtt = null
            keepTurnAudio(u)
            // A cloud draft transcribed this audio and he said nothing since: that transcript is the
            // turn's, so the same words are not paid for twice.
            val d = draft?.takeIf { confirmsAudio(it, u) }
            if (d != null) {
                d.audioConfirmed = true
                u.cancel()
            } else {
                u.end()
            }
            val at = System.currentTimeMillis()
            val source = plan?.phoneEnd ?: CascadeRules.EotSource.PHONE_DETECTOR
            scope.launch(loop) { onTurnEnd(u, at, source, null) }
        }

        override fun onUserTurnStart(bargeIn: Boolean) {
            LiveLatency.log("LIVE_FOLLOWUP", "local speech start bargeIn=$bargeIn")
        }

        override fun onEndOfTurn(end: EndOfTurnPolicy.Action.EndActivity) {
            LiveLatency.log(
                LiveLatency.TAG,
                "eot_ms=${end.silenceMs} eot_reason=${end.reason.wire} st_checks=${end.scores.size}",
            )
        }

        override fun onBargeIn(fire: BargeInGate.Event.Fire) {
            scope.launch(loop) { interrupt("barge_in") }
        }

        override fun onBargeInDisabled(selfInterrupts: Int) {
            host.notice("Interrupting turned off for this conversation")
            scope.launch(loop) {
                callAudio?.let { if (it.isActive) it.exit("self-interrupts") }
                player?.rebuild()
            }
        }

        override fun onLocalSpeech() {
            lastLocalSpeechAtMs = System.currentTimeMillis()
            // He spoke again after a cloud draft took its audio: that draft is of half a turn.
            val d = draft ?: return
            val at = d.speechAtSnapshotMs ?: return
            if (lastLocalSpeechAtMs > at && d.resumed.compareAndSet(false, true)) {
                scope.launch(loop) { if (draft === d) discardDraft("speech_resumed") }
            }
        }

        override fun onSileroFailed(reason: String) {
            TurnModels.markBroken("Silero threw: $reason")
            // The cloud voice needs Silero too: a phone fault goes to Live.
            scope.launch(loop) { handOver(currentMoment(), "turn", "silero_failed", phoneFault = true) }
        }

        override fun onSmartTurnFailed(reason: String) {
            TurnModels.smartTurnBroken = reason
        }

        override fun log(tag: String, message: String) = LiveLatency.log(tag, message)
    }

    private fun onUserTurnStart() {
        if (checkInStep == CheckInStep.LINE || checkInStep == CheckInStep.LISTENING) {
            checkInStep = CheckInStep.DONE
            reportCheckIn(CheckInOutcome.ANSWERED)
        }
        draft?.takeIf { it.utt !== currentUtt }?.let { discardDraft("new_utterance") }
        followUpEvent(FollowUpWindow.Event.UserSpeech)
        audit.activityStart()
        if (!speaking) host.phase(Phase.LISTENING, "Listening...")
        // He started again before the last answer began playing: that answer is dropped and his
        // words carry into this turn, so a mid-thought pause never splits one request in two.
        val pending = turn?.takeIf { it.fromUser && it.toolsRan.isEmpty() }
        val answering = turnJob?.isActive == true && !speaking
        if (pending != null && answering) {
            carry = (carry + " " + pending.heard).trim()
            turnJob?.cancel()
            speaker?.interrupt()
            LiveLatency.log("LIVE_TURN", "turn_merged reason=speech_resumed")
        }
    }

    /**
     * The laptop ended the turn itself (contract 4b, Kyutai's pause head): its final text is already
     * waiting in the utterance. Stop streaming to that id.
     */
    private fun onServiceEnd(u: TurnUtterance) {
        if (currentUtt !== u) return
        currentUtt = null
        // The phone's own turn is over too: forget it, so its silence cap does not end a second one.
        turnTaker?.reset()
        onTurnEnd(u, System.currentTimeMillis(), CascadeRules.EotSource.SERVICE, null)
    }

    private fun onTurnEnd(u: TurnUtterance, eotAtMs: Long, source: CascadeRules.EotSource, prob: Float?) {
        // A swap that came while he was speaking: the new backend's turn plan starts now.
        if (replanPending) replan()
        val silence = lastLocalSpeechAtMs.takeIf { it > 0 }?.let { eotAtMs - it }
        LiveLatency.log(LiveLatency.TAG, CascadeLines.eot(source, silence, prob))
        audit.activityEnd()
        host.phase(Phase.THINKING, "...")
        val previous = turnJob
        turnJob = scope.launch(loop) {
            previous?.join()
            runTurn(u, eotAtMs)
        }
    }

    @Suppress("LongMethod", "ReturnCount", "CyclomaticComplexMethod") // one exit per way a turn ends
    private suspend fun runTurn(u: TurnUtterance, eotAtMs: Long) {
        // The cloud draft's transcript, when the phone confirmed the turn on the same audio.
        val fromDraft = draft?.takeIf { it.utt === u && it.audioConfirmed }
        val stt = fromDraft?.let { draftTranscript(it, u.backend.sttFinalTimeoutMs) }
            ?: u.result(u.backend.sttFinalTimeoutMs)
        LiveLatency.log(LiveLatency.TAG, "stt_final_ms=${System.currentTimeMillis() - eotAtMs}" +
            ((stt as? SttResult.Text)?.decodeMs?.let { " stt_decode_ms=$it" } ?: "") +
            " stt_backend=${u.backend.kind.token}" + if (fromDraft != null) " stt_spec=true" else "")
        val words = when (stt) {
            is SttResult.Failed -> {
                discardDraftOf(u, "stt_failed")
                if (stt.reason == "cancelled") return
                recoverWords(u, stt.reason) ?: return
            }
            is SttResult.Text -> stt.text
        }
        val heard = (carry + " " + words).trim()
        if (heard.isBlank()) {
            discardDraftOf(u, "nothing_heard")
            backToListening()
            return
        }
        carry = ""
        audit.user(heard, final = true)
        // A brush-off of the last unprompted line quiets that rule for a day (ProactiveBus.noteReply);
        // only the first turn after a raise counts, which the bus enforces.
        scope.launch(Dispatchers.IO) { runCatching { ProactiveBus.noteReply(ctx, heard) } }
        if (CrisisDetector.detect(heard)) {
            discardDraftOf(u, "not_answered")
            shutdown("crisis")
            host.crisis()
            return
        }
        if (WakePhrases.isSleepPhrase(heard)) {
            discardDraftOf(u, "not_answered")
            recordTurn(heard, "")
            endByVoice()
            return
        }
        answerTurn(heard, eotAtMs, TurnRecord(heard), takeDraft(u, heard))
    }

    /**
     * The model half of a turn: [record]'s words (his, or an opener's prompt) through the text loop
     * with tools, the reply spoken as it streams, history and records kept. Shared by a spoken turn
     * and a prompt turn ([speakOpener]) so both answer the same way.
     */
    @Suppress("LongMethod", "ReturnCount", "CyclomaticComplexMethod") // one exit per way a turn ends
    private suspend fun answerTurn(
        heard: String,
        eotAtMs: Long?,
        record: TurnRecord,
        /** A speculative draft confirmed with these words: its answer is this turn's. */
        drafted: SpeculativeAnswer? = null,
    ) {
        turn = record
        val seq = turnSeq.getAndIncrement()
        val pfx = try {
            awaitPrompt()
        } catch (e: CancellationException) {
            // Interrupted or merged while the prompt was still building: no stale turn, and his
            // words reach the record unless they carry into the next turn.
            withContext(NonCancellable) {
                if (turn === record) turn = null
                if (carry.isBlank()) recordTurn(heard, "", record.readThrough, seq, record.fromUser)
                drafted?.discard()
            }
            throw e
        } ?: run {
            if (turn === record) turn = null
            drafted?.discard()
            handOver(heardMoment(heard), "prompt", "prefix_missing")
            return
        }
        val voice = backend!!
        var reply = newSpeaker(eotAtMs)
        var outcome: ReplySpeaker.Outcome = ReplySpeaker.Outcome.Interrupted
        try {
            // A confirmed draft replays what it held (text, marks) and only now may run its tools.
            val result = drafted?.adopt(executorFor(record, seq), listenerFor(record, reply))
                ?: textLoop!!.answer(
                    pfx,
                    contentsFor(heard),
                    executorFor(record, seq),
                    listenerFor(record, reply),
                )
            LiveLatency.log(LiveLatency.TAG, CascadeLines.usage(MODEL, result.usage))
            val text = if (result.failure != null && !reply.anyText) {
                result.failureWords ?: result.failure.lead
            } else {
                null
            }
            // A background line nobody asked for does not announce a fault (Settings says why).
            if (text != null && !quiet) reply.say(text) else reply.finishText()
            outcome = reply.await()
            val failed = outcome as? ReplySpeaker.Outcome.Failed
            if (failed != null) {
                // The model and the tools already ran on the phone: only the voice was lost. The
                // reply goes again, whole, on the next voice; nothing is asked of the model twice.
                // If no voice can say it, the answer and what was done still reach the panel and
                // the record before the conversation ends.
                val words = text ?: result.text
                val unsaid = { recordTurn(heard, withWrites(words, record), record.readThrough, seq, record.fromUser) }
                val again = revoice(voice, failed.reason, heardMoment(heard, record), words, unsaid) ?: return
                reply = again
                outcome = again.await()
                if (outcome is ReplySpeaker.Outcome.Failed) {
                    unsaid()
                    handOver(heardMoment(heard, record), "tts", outcome.reason)
                    return
                }
            }
            val said = reply.spoken().ifBlank { result.text }
            val kept = if (outcome == ReplySpeaker.Outcome.Interrupted) withWrites(reply.spoken(), record) else said
            recordTurn(heard, kept, record.readThrough, seq, record.fromUser)
            answeredOnce = true
            turnsAnswered.incrementAndGet()
        } catch (e: CancellationException) {
            // Interrupted (tap, barge-in) or merged into the next turn: keep only what was heard.
            withContext(NonCancellable) {
                reply.interrupt()
                if (carry.isBlank() || record.toolsRan.isNotEmpty()) {
                    recordTurn(heard, withWrites(reply.spoken(), record), record.readThrough, seq, record.fromUser)
                }
            }
            throw e
        } finally {
            if (turn === record) turn = null
        }
        if (outcome != ReplySpeaker.Outcome.Played) return
        if (checkInStep == CheckInStep.LINE && !record.fromUser) {
            afterCheckInLine(record.reply.toString())
        } else {
            afterReply()
        }
    }

    // --- listening check-in (alfred-greets ticket 03; Kevin 2026-11-15) ----------------------------

    /**
     * The check-in's line has played. A question: a short cue, the listening mark and the reply
     * window ([FollowUpWindow.WINDOW_MS], about 8 s). A statement: no window and no closer.
     */
    private suspend fun afterCheckInLine(line: String) {
        if (!CheckInRules.isQuestion(line)) {
            checkInStep = CheckInStep.DONE
            reportCheckIn(CheckInOutcome.STATEMENT)
            player?.awaitDrained()
            speaking = false
            playbackActive = false
            audit.turnComplete()
            shutdown("check_in_statement")
            host.closed("check_in_statement")
            return
        }
        // The cue plays while the reply still counts as playback, so the mic never takes it as him.
        val p = player
        if (p != null && backend?.tts?.sampleRate == ListenCue.SAMPLE_RATE) {
            p.awaitDrained()
            val gen = p.generation()
            withContext(Dispatchers.IO) { p.write(ListenCue.earconPcm(), gen) }
        }
        if (checkInStep == CheckInStep.LINE) checkInStep = CheckInStep.LISTENING
        LiveLatency.log("LIVE_FOLLOWUP", "check_in listening")
        afterReply()
        if (checkInStep == CheckInStep.LISTENING && !closing.get()) {
            host.phase(Phase.LISTENING, "Listening for your answer...")
        }
    }

    /** The reply window closed in silence: the mic closes, a scripted closer is said, and it ends. */
    private suspend fun closeCheckInUnanswered() {
        checkInStep = CheckInStep.DONE
        reportCheckIn(CheckInOutcome.NO_REPLY)
        LiveLatency.log("LIVE_FOLLOWUP", "check_in no_reply")
        // Closed before the closer plays: nothing said from here on is taken as his turn.
        runCatching { micJob?.cancelAndJoin() }
        micJob = null
        currentUtt?.cancel()
        currentUtt = null
        val reply = newSpeaker(null)
        reply.say(ClosingLines.next(ctx))
        val o = withTimeoutOrNull(CLOSER_MAX_MS) { reply.await() }
        if (o == ReplySpeaker.Outcome.Played) player?.awaitDrained() else host.closingTone()
        shutdown("check_in_no_reply")
        host.closed("check_in_no_reply")
    }

    private fun reportCheckIn(outcome: CheckInOutcome) {
        val mark = opener?.checkIn ?: return
        val words = "check_in outcome=${outcome.name.lowercase()} moment=${mark.moment.name.lowercase()}"
        LiveLatency.log("LIVE_FOLLOWUP", words)
        CheckIns.onOutcome(ctx, mark, outcome)
    }

    /**
     * The words of [u] did not come back. From the laptop: the conversation moves to the cloud voice
     * and the cloud transcribes the same words from the audio the phone kept, so nothing he said is
     * lost. From the cloud (or nothing kept): Live, told the words were lost. Null when the turn ends
     * here.
     */
    @Suppress("ReturnCount") // one exit per way the words are lost
    private suspend fun recoverWords(u: TurnUtterance, reason: String): String? {
        val moment = if (carry.isBlank()) Handover.Moment.LostWords else heardMoment(carry)
        if (!swapToCloud(u.backend, "stt", reason)) {
            if (u.backend.kind == SpeechBackend.Kind.CLOUD) {
                missedWords(moment, reason, u.backend.kind)
            } else {
                handOver(moment, "stt", reason, from = u.backend.kind)
            }
            return null
        }
        val audio = u.keptAudio()
        val b = backend ?: return null
        if (audio == null || audio.isEmpty()) {
            missedWords(moment, reason, b.kind)
            return null
        }
        val started = System.currentTimeMillis()
        val again = b.stt.begin()
        again.audio(audio, 0, audio.size)
        again.end()
        return when (val r = again.result(b.sttFinalTimeoutMs)) {
            is SttResult.Text -> {
                LiveLatency.log(
                    LiveLatency.TAG,
                    "stt_recovered backend=${b.kind.token} ms=${System.currentTimeMillis() - started} from=$reason",
                )
                r.text
            }
            is SttResult.Failed -> {
                if (r.reason != "cancelled") missedWords(moment, r.reason, b.kind)
                null
            }
        }
    }

    /**
     * The cloud voice did not catch his words (after its one retry). With Live retired nothing can
     * take the conversation over, and ending it for one missed sentence would be worse than asking
     * again: it is said on screen and the floor is his. The follow-up window still closes a
     * conversation nobody speaks in. Before 2026-11-15 (or after a rollback) this went to Live.
     */
    private suspend fun missedWords(moment: Handover.Moment, reason: String, from: SpeechBackend.Kind) {
        if (!VoiceEngineSettings.LIVE_RETIRED) {
            handOver(moment, "stt", reason, from = from)
            return
        }
        LiveLatency.log(LiveLatency.TAG, "stt_missed backend=${from.token} reason=$reason")
        VoiceEngineSettings.noteStatus(ctx, "${VoiceEngineSettings.CLOUD_WORDS} missed a sentence (stt_$reason)")
        host.notice(MISSED_NOTICE)
        backToListening()
    }

    /**
     * A reply's voice failed on [failed] after the model had written it: move to the cloud voice and
     * speak [words] there. Null (after a hand-over to Live) when the cloud voice cannot take it.
     */
    private suspend fun revoice(
        failed: SpeechBackend,
        reason: String,
        moment: Handover.Moment,
        words: String,
        beforeGivingUp: () -> Unit,
    ): ReplySpeaker? {
        if (!swapToCloud(failed, "tts", reason)) {
            beforeGivingUp()
            handOver(moment, "tts", reason, from = failed.kind)
            return null
        }
        LiveLatency.log(LiveLatency.TAG, "reply_revoiced backend=${backend?.kind?.token} chars=${words.length}")
        return newSpeaker(null).also { it.say(words) }
    }

    /**
     * What history keeps of an interrupted reply: the words heard, plus the writes that DID happen,
     * so the next turn knows not to do them again (the hand-over openers carry the same note).
     */
    private fun withWrites(spoken: String, r: TurnRecord): String {
        val writes = synchronized(r) { r.writesDone.distinct() }
        if (writes.isEmpty()) return spoken
        return (spoken + " (Interrupted. Already done and confirmed: ${writes.joinToString(", ")}.)").trim()
    }

    private fun contentsFor(heard: String) = run {
        val capped = TextChatEngine.capHistory(history + ChatTurn(fromUser = true, text = heard))
        val contents = TextChatEngine.historyContents(capped)
        // The date, the place and this conversation's context ride in the first user turn (never
        // in the cached instruction).
        if (preamble.isNotEmpty() && contents.length() > 0) {
            val first = contents.getJSONObject(0)
            val part = first.getJSONArray("parts").getJSONObject(0)
            part.put("text", preamble + part.optString("text"))
        }
        contents
    }

    private fun executorFor(record: TurnRecord, seq: Long) = TextToolExecutor { name, args, readThrough ->
        val id = "c${toolSeq.incrementAndGet()}"
        audit.toolCall(id, name, false, args.toString())
        toolsInFlight.incrementAndGet()
        followUpEventAsync(FollowUpWindow.Event.ToolCall)
        if (!speaking) host.phase(Phase.THINKING, "Working...")
        val started = System.currentTimeMillis()
        val response = try {
            when (name) {
                // Arm, do not fire: the sign-off is spoken first (Live's ticket 11 rule).
                "end_conversation" -> {
                    scope.launch(loop) { dismissAfterReply = true }
                    JSONObject().put("success", true)
                        .put("instruction", LiveSessionController.END_CONVERSATION_INSTRUCTION)
                }
                in CascadeRules.LIVE_ONLY_TOOLS ->
                    JSONObject().put("success", false).put("message", "That only works on the Live voice engine.")
                // A read-through opener (ADR 0090) marks the turn before the loop's own flag can.
                else -> host.runTool(
                    name,
                    args,
                    readThrough || record.readThrough,
                    if (record.fromUser) record.heard else "",
                    seq,
                )
            }
        } finally {
            toolsInFlight.decrementAndGet()
        }
        audit.toolResult(
            LiveAudit.ToolResult(
                id = id,
                name = name,
                success = response.optBoolean("success", !response.has("error")),
                ms = System.currentTimeMillis() - started,
                async = false,
                sent = true,
                responseJson = response.toString(),
            ),
        )
        response
    }

    private fun listenerFor(record: TurnRecord, reply: ReplySpeaker) = object : GeminiTextLoop.Listener {
        override fun onText(delta: String) {
            record.reply.append(delta)
            reply.text(delta)
        }

        override fun onToolCalls(names: List<String>) {
            synchronized(record) { record.toolsRan.addAll(names) }
            // A confirmed turn going to a slow tool says so at once, unless the model already spoke
            // (its own "let me check" is enough). Never an outcome. A draft only gets here after
            // confirmation ([SpeculativeAnswer] holds this call until then).
            if (record.fromUser && ScriptedLines.fillerDue(names, reply.anyText, record.fillerPlayed)) {
                record.fillerPlayed = true
                if (reply.filler(ScriptedLines.FILLERS.random())) {
                    val slow = names.filter { it in ScriptedLines.SLOW_TOOLS }.joinToString(",")
                    LiveLatency.log(LiveLatency.TAG, "filler tools=$slow")
                }
            }
        }

        override fun onToolResult(name: String, args: JSONObject, response: JSONObject, ms: Long, writeDone: Boolean) {
            if (writeDone) synchronized(record) { record.writesDone.add(name) }
            LiveLatency.log(LiveLatency.TAG, "tool_ms=$ms tool=$name sent=true async=false")
        }

        override fun onReadThrough(source: String) {
            record.readThrough = true
            audit.readThrough(source)
        }

        override fun onFirstPart(round: Int, ms: Long) {
            LiveLatency.log(LiveLatency.TAG, "llm_first_ms=$ms round=$round")
        }
    }

    private fun newSpeaker(eotAtMs: Long?): ReplySpeaker {
        val b = backend!!
        val p = player!!
        val gen = p.generation()
        var ttsAskedAtMs = System.currentTimeMillis()
        var audible = false
        return ReplySpeaker(
            tts = b.tts,
            scope = scope,
            play = { pcm -> p.write(pcm, gen) },
            firstFrameTimeoutMs = b.ttsFirstAudioTimeoutMs,
            onFirstAudio = { _, filler ->
                val now = System.currentTimeMillis()
                if (filler) {
                    // Its own line: eot_reply_ms stays the answer's first audio, not the filler's.
                    eotAtMs?.let { LiveLatency.log(LiveLatency.TAG, "filler_first_ms=${now - it}") }
                } else {
                    eotAtMs?.let { LiveLatency.log(LiveLatency.TAG, "eot_reply_ms=${now - it}") }
                    LiveLatency.log(LiveLatency.TAG, "tts_first_ms=${now - ttsAskedAtMs}")
                }
                if (!audible) {
                    audible = true
                    scope.launch(loop) { onReplyAudible() }
                }
            },
            onClipStarted = { text ->
                ttsAskedAtMs = System.currentTimeMillis()
                audit.assistant(text, readThrough = turn?.readThrough == true)
                // The voice sheet and the talk strip read the caption, as for Live's subtitles.
                CompanionPhase.setCaption(text.takeLast(CAPTION_CHARS))
            },
        ).also { speaker = it }
    }

    private fun onReplyAudible() {
        if (closing.get()) return
        if (!firstAudioLogged && openStartedAtMs > 0) {
            firstAudioLogged = true
            LiveLatency.log(LiveLatency.TAG, "open_first_audio_ms=${System.currentTimeMillis() - openStartedAtMs}")
        }
        speaking = true
        playbackActive = true
        turnTaker?.let { if (it.bargeIn) it.onReplyStart() }
        followUpEvent(FollowUpWindow.Event.ModelSpeech)
        host.phase(Phase.SPEAKING, "Speaking...")
    }

    /** The reply played to the end: drain, settle, listen again (or hang up if he asked). */
    private suspend fun afterReply() {
        player?.awaitDrained()
        delay(MicReopen.settleMs(CallAudioMode.route(audioManager)))
        speaking = false
        playbackActive = false
        turnTaker?.onReplyEnd()
        audit.turnComplete()
        if (!listens) {
            shutdown("spoken")
            host.closed("spoken")
            return
        }
        val oneShotDone = (oneShot || oneShotRequested) && answeredOnce
        if (dismissAfterReply || oneShotDone) {
            host.closingTone()
            shutdown("dismissed")
            host.closed("dismissed")
            return
        }
        backToListening()
    }

    private fun backToListening() {
        if (closing.get()) return
        host.phase(Phase.LISTENING, "Listening...")
        followUpEvent(FollowUpWindow.Event.TurnComplete)
        followUpEvent(FollowUpWindow.Event.PlaybackDrained)
    }

    /** Tap or barge-in: stop the reply now. A tool already running finishes and is reported. */
    private fun interrupt(source: String) {
        if (closing.get()) return
        val wasSpeaking = speaking
        speaker?.interrupt()
        player?.flush()
        speaking = false
        playbackActive = false
        turnTaker?.onReplyEnd()
        turnJob?.cancel()
        audit.interrupted(source)
        LiveLatency.log("LIVE_TURN", "interrupted source=$source speaking=$wasSpeaking")
        host.phase(Phase.LISTENING, "Listening...")
        if (source == "tap") backToListening()
    }

    /**
     * A scripted line (the opening acknowledgement): no model call. On a laptop whose voice fails it
     * is said again on the cloud voice. If the cloud voice cannot say even this, it cannot start, and
     * the conversation opens on Live as if nothing had happened (nothing has been said yet).
     */
    private fun speakScripted(line: String) {
        val voice = backend!!
        if (voice.kind == SpeechBackend.Kind.CLOUD) {
            playCue()
            return
        }
        val reply = newSpeaker(null)
        reply.say(line)
        turnJob = scope.launch(loop) {
            when (val o = reply.await()) {
                ReplySpeaker.Outcome.Played -> afterReply()
                is ReplySpeaker.Outcome.Failed -> onScriptedFailed(voice, line, o.reason)
                ReplySpeaker.Outcome.Interrupted -> Unit
            }
        }
    }

    /**
     * The cloud voice's opening line: the same bundled clip in the companion's voice Live played
     * ([ListenCue]; an earcon when no clip matches the voice). No model and no TTS call: nothing is
     * paid for and nothing can fail before he speaks.
     */
    private fun playCue() {
        val cue = ListenCue.next(ctx, CompanionProfile.voice(ctx))
        val p = player ?: return
        LiveLatency.log(LiveLatency.TAG, "listen_cue ${cue.label} (cascade)")
        turnJob = scope.launch(loop) {
            onReplyAudible()
            val gen = p.generation()
            withContext(Dispatchers.IO) { p.write(cue.pcm, gen) }
            afterReply()
        }
    }

    private suspend fun onScriptedFailed(voice: SpeechBackend, line: String, reason: String) {
        val moment = if (history.isEmpty()) Handover.Moment.AtOpen else Handover.Moment.Idle
        if (swapToCloud(voice, "tts", reason)) {
            speakScripted(line)
        } else {
            handOver(moment, "tts", reason, from = voice.kind)
        }
    }

    /**
     * The personal greeting (ADR 0091, ticket 02): ONE streamed text call through the same cached
     * prefix and the same [textLoop] a turn uses, the reply spoken as it streams. The model is
     * called here, on the phone, with Alfred's cached instruction; the laptop only speaks it. Cost is
     * one text generation against the cache, which is what makes the greeting affordable on this
     * pipeline (Live has no such cheap path, see `LiveGreeting`).
     *
     * Every tool is refused for this call (it is a greeting: nothing is read or written, and ADR
     * 0090's rule that no write follows outside text is kept by construction). **Fallback:** no text
     * within [GreetingBudget.FIRST_OUTPUT_MS], or a failure with no text, speaks the scripted ack
     * instead; nothing about the miss is spoken. A tap while it plays interrupts like any reply.
     */
    @Suppress("LongMethod", "ReturnCount") // one exit per way the greeting ends
    private fun speakGreeting(plan: GreetingPlan.Personal) {
        val loopRef = greetingLoop
        if (loopRef == null) {
            speakScripted(ackLines.next())
            return
        }
        val voice = backend!!
        turnJob = scope.launch(loop) {
            // One budget for the whole greeting, prompt build included: past it, the scripted line.
            val deadline = System.currentTimeMillis() + GreetingBudget.FIRST_OUTPUT_MS
            // The greeting's facts come from its own pack: it does not wait on the live context.
            val pfx = withTimeoutOrNull(GreetingBudget.FIRST_OUTPUT_MS) { awaitPrompt(liveContextWaitMs = 0) }
            if (pfx == null) {
                LiveLatency.log(LiveLatency.TAG, "greeting prompt_slow scripted")
                speakScripted(ackLines.next())
                return@launch
            }
            val reply = newSpeaker(null)
            val firstText = CompletableDeferred<Unit>()
            val call = launch(Dispatchers.IO) {
                try {
                    loopRef.answer(
                        pfx,
                        contentsFor(plan.prompt),
                        TextToolExecutor { _, _, _ ->
                            JSONObject().put("success", false)
                                .put("message", "No tool runs during the greeting; nothing was done.")
                        },
                        object : GeminiTextLoop.Listener {
                            override fun onText(delta: String) {
                                reply.text(delta)
                                firstText.complete(Unit)
                            }

                            override fun onFirstPart(round: Int, ms: Long) {
                                LiveLatency.log(LiveLatency.TAG, "greeting_llm_first_ms=$ms")
                            }
                        },
                    )
                } finally {
                    firstText.complete(Unit) // a failure with no text ends the wait early too
                }
            }
            try {
                withTimeoutOrNull((deadline - System.currentTimeMillis()).coerceAtLeast(1L)) { firstText.await() }
                if (!reply.anyText) {
                    call.cancel()
                    reply.interrupt()
                    LiveLatency.log(LiveLatency.TAG, "greeting model_slow scripted")
                    speakScripted(ackLines.next())
                    return@launch
                }
                call.join()
                reply.finishText()
                greeting?.noteRaised(plan.topic)
                when (val o = reply.await()) {
                    ReplySpeaker.Outcome.Played -> {
                        // So his answer is understood: the greeting is the first exchange.
                        history.add(ChatTurn(fromUser = true, text = OPENED_NOTE))
                        history.add(ChatTurn(fromUser = false, text = reply.spoken()))
                        afterReply()
                    }
                    // The greeting is not said twice: the next voice opens with the scripted line.
                    is ReplySpeaker.Outcome.Failed -> onScriptedFailed(voice, ackLines.next(), o.reason)
                    ReplySpeaker.Outcome.Interrupted -> Unit
                }
            } catch (e: CancellationException) {
                withContext(NonCancellable) { reply.interrupt() }
                throw e
            }
        }
    }

    private suspend fun endByVoice() {
        host.closingTone()
        shutdown("sleep_phrase")
        host.notice("Conversation closed")
        host.closed("sleep_phrase")
    }

    // --- records ---------------------------------------------------------------------------------

    private fun recordTurn(
        heard: String,
        said: String,
        readThrough: Boolean = false,
        seq: Long = turnSeq.getAndIncrement(),
        fromUser: Boolean = true,
    ) {
        if (heard.isNotBlank()) history.add(ChatTurn(fromUser = true, text = heard))
        // A read-through reply (mail, web) is never kept as history or in the record in clear.
        val kept = if (readThrough) LiveAudit.REDACTED else said
        if (said.isNotBlank()) history.add(ChatTurn(fromUser = false, text = kept))
        host.turnTranscript(if (fromUser) heard else "", said)
        val vehicle = ActiveVehicle.current(ctx)
        scope.launch(Dispatchers.IO) {
            runCatching {
                val dao = CarDatabase.getDatabase(ctx).conversationAuditDao()
                val uploaded = com.kevin.legion.backend.conversationAuditUploadedThroughId(ctx)
                if (fromUser && heard.isNotBlank()) {
                    dao.record(
                        turnSeq = seq,
                        kind = ConversationAudit.Kind.USER,
                        content = heard,
                        vehicleId = vehicle,
                        uploadedThroughId = uploaded,
                    )
                }
                if (said.isNotBlank()) {
                    dao.record(
                        turnSeq = seq,
                        kind = ConversationAudit.Kind.COMPANION,
                        content = auditContent(said, readThrough),
                        redacted = readThrough,
                        vehicleId = vehicle,
                        uploadedThroughId = uploaded,
                    )
                }
            }
        }
    }

    // --- follow-up window (Live's own fold, ticket 17: 8 s of quiet closes) -----------------------

    private fun followUpEventAsync(event: FollowUpWindow.Event) {
        scope.launch(loop) { followUpEvent(event) }
    }

    private fun followUpEvent(event: FollowUpWindow.Event) {
        val d = FollowUpWindow.step(followUp, event)
        followUp = d.state
        when (d.action) {
            FollowUpWindow.Action.ARM -> armFollowUp()
            FollowUpWindow.Action.CANCEL -> followUpJob?.cancel()
            FollowUpWindow.Action.CLOSE -> onFollowUpExpired()
            FollowUpWindow.Action.NONE -> Unit
        }
        d.reason?.let { LiveLatency.log("LIVE_FOLLOWUP", "reason=$it") }
    }

    private fun armFollowUp() {
        followUpJob?.cancel()
        followUpArmedAtMs = System.currentTimeMillis()
        LiveLatency.log("LIVE_FOLLOWUP", "armed ms=${FollowUpWindow.WINDOW_MS} engine=${engineToken()}")
        followUpJob = scope.launch(loop) {
            delay(FollowUpWindow.WINDOW_MS)
            val now = System.currentTimeMillis()
            followUpEvent(
                FollowUpWindow.Event.Elapsed(
                    toolsInFlight = toolsInFlight.get(),
                    msSinceLocalSpeech = lastLocalSpeechAtMs.takeIf { it >= followUpArmedAtMs }?.let { now - it },
                ),
            )
        }
    }

    private fun onFollowUpExpired() {
        if (closing.get()) return
        if (checkInStep == CheckInStep.LISTENING) {
            scope.launch(loop) { closeCheckInUnanswered() }
            return
        }
        scope.launch(loop) {
            LiveLatency.log("LIVE_FOLLOWUP", "expired engine=${engineToken()}")
            host.closingTone()
            shutdown("follow_up")
            host.notice("Conversation closed")
            host.closed("follow_up")
        }
    }

    // --- failure ---------------------------------------------------------------------------------

    /**
     * A Bluetooth device connected while barge-in was on: off for the rest of this conversation and
     * out of call audio, as Live did (ADR 0064's half-duplex rule for the car).
     */
    private fun onBluetoothConnected() {
        scope.launch(loop) {
            val tt = turnTaker?.takeIf { it.bargeIn } ?: return@launch
            tt.disableBargeIn()
            LiveLatency.log(TurnTaker.BARGE_TAG, "off for this session: Bluetooth connected (cascade)")
            audioRoute.stopWatching()
            callAudio?.let { if (it.isActive) it.exit("bluetooth connected") }
            player?.rebuild()
        }
    }

    /** One billed call: its line now, and into the session's sum. Counts and model ids only. */
    private fun noteUsage(u: CallUsage) {
        LiveLatency.log(LiveLatency.TAG, u.line())
        usageLedger.add(u)
    }

    /**
     * The text loop for [kind] (`llm` for turns, `greeting` for the greeting): same model, cache,
     * tools. [sink] gets its usage lines (a draft's go through its [UsageHold]).
     */
    private fun newTextLoop(
        cache: ContextCacheStore,
        kind: String,
        sink: (CallUsage) -> Unit = ::noteUsage,
    ) = GeminiTextLoop(
        transport = OkHttpGemini(),
        // A speak-only line makes one round: no cache (a peek would start a create it never uses).
        cache = cache.takeIf { listens },
        isMutating = LiveToolbox::isMutatingCall,
        isReadThrough = { GeminiLiveSession.isEpisodicExcludedTool(it) },
        toolTimeoutMs = { TOOL_BACKSTOP_MS },
        meter = { model, usage ->
            meter(model, usage)
            sink(CallUsage.of(kind, model, usage))
        },
        discarded = { model, usage, chars ->
            sink(CallUsage.of(kind, model, usage, chars = chars, discarded = true))
        },
    )

    // --- speculative answers ([TurnDraft], Kevin approved 2026-11-15) ----------------------------------

    /** The laptop's early signals reach the session (only when the setting is on). */
    private fun wireEarlySignals(b: SpeechBackend) {
        val c = (b as? LaptopSpeech)?.connection
        LiveLatency.log(
            LiveLatency.TAG,
            "spec_config on=$speculate backend=${b.kind.token} maybe_end=${c?.sendsMaybeEnd ?: false}",
        )
        if (!speculate || c == null) return
        c.onMaybeEnd = { id, text, atEnd -> scope.launch(loop) { onMaybeEnd(id, text, atEnd) } }
        c.onPartial = { id, text -> scope.launch(loop) { onPartialText(id, text) } }
    }

    /** Why no draft may start now, or null. */
    private fun draftRefusal(): String? = when {
        !speculate -> "off"
        closing.get() -> "closing"
        prefix == null || cacheStore == null -> "prompt_not_ready"
        // The first answer waits for the live context (clock, place); a draft must not skip it.
        preambleReady?.isCompleted == false -> "context_not_ready"
        speaking || turnJob?.isActive == true -> "turn_busy"
        else -> null
    }

    /** The laptop says the turn may be over (or is, and is flushing): draft on its words. */
    @Suppress("ReturnCount") // one exit per reason not to draft
    private fun onMaybeEnd(id: Int, text: String, atEnd: Boolean) {
        val u = currentUtt?.takeIf { it.id == id } ?: return
        val heard = (carry + " " + text).trim()
        val d = draft
        if (d != null && d.utt === u && SpeculationRules.sameText(d.heard.orEmpty(), heard)) return
        val trigger = if (atEnd) "eot" else "maybe"
        if (!draftable(heard)) return
        draftRefusal()?.let { return }
        discardDraft("superseded")
        val next = TurnDraft(u, trigger, System.currentTimeMillis())
        draft = next
        startDraftAnswer(next, heard)
    }

    /** A new running transcript for a drafted utterance: different words mean he kept talking. */
    private fun onPartialText(id: Int, text: String) {
        val d = draft ?: return
        if (d.utt.id != id || d.speechAtSnapshotMs != null) return
        val heard = (carry + " " + text).trim()
        if (!SpeculationRules.sameText(d.heard.orEmpty(), heard)) discardDraft("speech_resumed")
    }

    /** Long enough, and nothing [runTurn] would stop before the model (crisis, the sleep phrase). */
    private fun draftable(heard: String): Boolean =
        SpeculationRules.longEnough(heard) && !CrisisDetector.detect(heard) && !WakePhrases.isSleepPhrase(heard)

    /** The model is asked on [heard]; its text and tool calls are held ([SpeculativeAnswer]). */
    @Suppress("ReturnCount")
    private fun startDraftAnswer(d: TurnDraft, heard: String) {
        val pfx = prefix ?: return
        val cache = cacheStore ?: return
        val hold = UsageHold(::noteUsage)
        val answer = SpeculativeAnswer(scope, newTextLoop(cache, CallUsage.LLM, hold::add), hold)
        d.heard = heard
        d.historySize = history.size
        d.preamble = preamble
        d.answer = answer
        if (d.stt == null) usageLedger.spec(started = 1)
        LiveLatency.log(
            LiveLatency.TAG,
            CascadeLines.specStart(d.trigger, d.utt.backend.kind.token, heard.length),
        )
        answer.start(pfx, contentsFor(heard))
    }

    /**
     * The cloud voice's early signal: [SpeculationRules.CLOUD_SILENCE_MS] of quiet after his speech
     * (the phone ends the turn at 700 ms or later). The audio so far is transcribed as a draft; if he
     * says nothing more, that transcript is the turn's and the answer drafted on it is kept.
     */
    private fun watchCloudSilence(u: TurnUtterance) {
        specPollJob?.cancel()
        specPollJob = scope.launch(loop) {
            while (currentUtt === u && !closing.get()) {
                delay(SpeculationRules.CLOUD_POLL_MS)
                if (currentUtt !== u) break
                val last = lastLocalSpeechAtMs
                val quiet = last > 0 && System.currentTimeMillis() - last >= SpeculationRules.CLOUD_SILENCE_MS
                val drafting = draft?.utt === u
                if (quiet && !drafting && cloudDraftAllowed(u)) startCloudDraft(u, last)
            }
        }
    }

    /** Another cloud draft may start for [u] now: its cap is not spent and nothing else refuses. */
    private fun cloudDraftAllowed(u: TurnUtterance): Boolean = u.cloudDraftBudgetLeft && draftRefusal() == null

    @Suppress("ReturnCount") // one exit per reason not to draft
    private fun startCloudDraft(u: TurnUtterance, speechAt: Long) {
        val gemini = (u.backend as? CloudSpeech)?.stt ?: return
        val audio = u.keptAudio() ?: return
        if (audio.size < SpeculationRules.CLOUD_MIN_AUDIO_BYTES) return
        if (!u.spendCloudDraft()) return
        discardDraft("superseded")
        val hold = UsageHold(::noteUsage)
        val d = TurnDraft(u, "silence", System.currentTimeMillis(), speechAtSnapshotMs = speechAt)
        d.sttHold = hold
        d.stt = scope.async(Dispatchers.IO) { gemini.transcribe(audio, hold::add) }
        draft = d
        usageLedger.spec(started = 1)
        LiveLatency.log(LiveLatency.TAG, CascadeLines.specStart(d.trigger, u.backend.kind.token, 0))
        scope.launch(loop) {
            val r = runCatching { d.stt?.await() }.getOrNull()
            if (draft !== d || d.answer != null) return@launch
            LiveLatency.log(LiveLatency.TAG, "spec_stt_ms=${System.currentTimeMillis() - d.startedAtMs} " +
                "audio_ms=${audio.size / PCM_BYTES_PER_MS}")
            val heard = (carry + " " + ((r as? SttResult.Text)?.text ?: return@launch)).trim()
            if (draftable(heard) && draftRefusal() == null) startDraftAnswer(d, heard)
        }
    }

    /** The draft transcript, as the turn's (its cost is billed as heard, not discarded). */
    private suspend fun draftTranscript(d: TurnDraft, timeoutMs: Long): SttResult {
        val job = d.stt ?: return SttResult.Failed("no_draft")
        val r = withTimeoutOrNull(timeoutMs) { runCatching { job.await() }.getOrNull() }
        d.sttHold?.release(discarded = false)
        if (r == null) job.cancel()
        return r ?: SttResult.Failed("timeout")
    }

    /**
     * The turn is confirmed as [heard]: the draft's answer when it was made from the same words, the
     * same history and the same context; otherwise it is thrown away (and costed as such).
     */
    @Suppress("ReturnCount") // nothing to take, dropped, or kept
    private fun takeDraft(u: TurnUtterance, heard: String): SpeculativeAnswer? {
        val d = draft ?: return null
        draft = null
        val answer = d.answer
        val reason = when {
            d.utt !== u -> "other_utterance"
            answer == null -> if (d.audioConfirmed) null else "no_answer"
            !SpeculationRules.sameText(d.heard.orEmpty(), heard) -> "text_changed"
            d.historySize != history.size || d.preamble != preamble -> "context_changed"
            answer.unusableReason != null -> answer.unusableReason
            else -> null
        }
        val age = System.currentTimeMillis() - d.startedAtMs
        if (reason != null || answer == null) {
            d.drop()
            if (reason != null) {
                usageLedger.spec(discarded = 1)
                LiveLatency.log(LiveLatency.TAG, CascadeLines.specDiscard(d.trigger, reason, age))
            } else {
                // The transcript was kept; there was no answer drafted to keep.
                usageLedger.spec(kept = 1)
                LiveLatency.log(LiveLatency.TAG, CascadeLines.specKept(d.trigger, age, null, 0, false, false))
            }
            return null
        }
        usageLedger.spec(kept = 1)
        val ahead = answer.firstPartAtMs?.let { System.currentTimeMillis() - it }
        LiveLatency.log(
            LiveLatency.TAG,
            CascadeLines.specKept(d.trigger, age, ahead, answer.draftChars, answer.heldAtTool, answer.isDone),
        )
        return answer
    }

    /** [d] transcribed [u]'s audio and he has not spoken since: its transcript is the turn's. */
    private fun confirmsAudio(d: TurnDraft, u: TurnUtterance): Boolean =
        d.utt === u && d.stt != null && lastLocalSpeechAtMs == d.speechAtSnapshotMs

    /** Throws the draft away (it was of [u], and the turn will not use it). */
    private fun discardDraftOf(u: TurnUtterance, reason: String) {
        if (draft?.utt === u) discardDraft(reason)
    }

    /** Throws away the draft in flight, if any: nothing it wrote is heard, nothing it asked for runs. */
    private fun discardDraft(reason: String) {
        val d = draft ?: return
        draft = null
        d.drop()
        usageLedger.spec(discarded = 1)
        val age = System.currentTimeMillis() - d.startedAtMs
        LiveLatency.log(LiveLatency.TAG, CascadeLines.specDiscard(d.trigger, reason, age))
    }

    /** What the follow-up lines carry: the backend speaking now. */
    private fun engineToken(): String = backend?.kind?.token ?: SpeechBackend.Kind.LAPTOP.token

    /**
     * The laptop's socket dropped. A turn in flight sees its own failed wait and recovers on the cloud
     * voice; between turns the swap is all there is to do. While the session is still opening the
     * open path sees the failure itself.
     */
    private fun onBackendDropped(failed: SpeechBackend, reason: String) {
        if (closing.get() || backend == null) return
        if (!swapToCloud(failed, "socket", reason)) {
            scope.launch(loop) { handOver(currentMoment(), "socket", reason, from = failed.kind) }
        }
    }

    /**
     * The laptop failed ([failed] is the backend that reported it): this conversation carries on, on
     * the cloud voice (Kevin, 2026-11-15). Mic, history, text loop, cache, tools and player stay; the
     * backend and the turn plan change. True when the cloud voice is (now, or already) the one
     * speaking; false when it cannot take over and the caller goes to Live. A second report of the
     * same laptop failure (the socket drop and the turn's own failed wait both arrive) finds the swap
     * done and changes nothing. Runs on [loop].
     */
    @Suppress("ReturnCount") // closing, already swapped, cannot swap, swapped: each its own answer
    private fun swapToCloud(failed: SpeechBackend, stage: String, reason: String): Boolean {
        if (closing.get()) return false
        val current = backend
        if (current !== failed) return current?.kind == SpeechBackend.Kind.CLOUD
        if (CascadeRules.nextAfter(failed.kind, cloudCanStart()) != CascadeRules.Next.CLOUD) return false
        LiveLatency.log(LiveLatency.TAG, CascadeLines.fallback(failed.kind.token, "cloud", stage, reason))
        VoiceEngineSettings.noteStatus(ctx, VoiceEngineSettings.statusWords("${stage}_$reason"))
        host.notice(DROPPED_NOTICE)
        discardDraft("backend_swap")
        step("laptop_close") { failed.close(reason) }
        val next = cloudSpeech(scope, ::noteUsage)
        enginesUsed.add(next.kind.token)
        backend = next
        val p = player
        if (p != null && p.sampleRate != next.tts.sampleRate) {
            p.release()
            player = ReplyPlayer(next.tts.sampleRate) { callAudio?.isActive == true }
        }
        replan()
        return true
    }

    /**
     * The turn plan for the backend speaking now (the cloud has no end-of-turn signal of its own, so
     * the phone's detector and Smart Turn take over from the laptop's). A turn he is in the middle of
     * keeps the plan it started with; the new one starts when it ends ([replanPending]).
     */
    @Suppress("ReturnCount") // no models, no backend, or a turn in progress: nothing to rebuild yet
    private fun replan() {
        val m = models ?: return
        val b = backend ?: return
        val p = synchronized(turnLock) {
            if (currentUtt != null) {
                replanPending = true
                return
            }
            replanPending = false
            val old = turnTaker
            val next = CascadeRules.turnPlan(
                serviceEndsTurns = b.stt.endsTurns,
                smartSetting = VoiceTurnSettings.smartEndOfTurn(ctx),
                smartTurnReady = m.smartTurn != null && TurnModels.smartTurnBroken == null,
                // Interrupting stays as it was (it may have been turned off for this conversation).
                bargeSetting = old?.bargeIn == true,
                route = CallAudioMode.route(audioManager),
                inCall = TelephonyController.isInCall,
            )
            turnTaker = TurnTaker(
                next.config,
                m.silero.stream(),
                m.smartTurn.takeIf { next.config.smartTurn },
                sink,
                next.endBaseMs,
            )
            plan = next
            old?.reset()
            next
        }
        if (!p.config.bargeIn) {
            // The barge-in call mode goes; the car's hands-free link (no barge-in) stays.
            callAudio?.takeUnless { it.onBluetoothMic }?.let {
                if (it.isActive) {
                    it.exit("swap to ${b.kind.token}")
                    player?.rebuild()
                }
            }
        }
        LiveLatency.log(
            "LIVE_TURN",
            "config manual=true bargeIn=${p.config.bargeIn} smartTurn=${p.config.smartTurn} (${p.config.note}) " +
                "backend=${b.kind.token}",
        )
    }

    private fun onMicPreempted() {
        if (closing.get()) return
        micClaimed = false
        scope.launch(loop) {
            LiveLatency.log("LIVE_TURN", "cascade preempted by a higher-priority mic claimant")
            shutdown("preempted")
            host.closed("preempted")
        }
    }

    private fun heardMoment(heard: String, r: TurnRecord? = null) = Handover.Moment.Heard(
        heard = heard,
        reply = r?.reply?.toString().orEmpty(),
        toolsRan = r?.let { synchronized(it) { it.toolsRan.toList() } }.orEmpty(),
        writesDone = r?.let { synchronized(it) { it.writesDone.toList() } }.orEmpty(),
        readThrough = r?.readThrough == true,
    )

    /** Where the conversation stands, for a hand-over that was not triggered inside a turn. */
    private fun currentMoment(): Handover.Moment {
        val r = turn
        return when {
            r != null -> heardMoment(r.heard, r)
            currentUtt != null -> Handover.Moment.LostWords
            else -> Handover.Moment.Idle
        }
    }

    /**
     * Releases everything, then opens Live for this conversation. Strictly in that order: a mic or
     * call-audio mode still held when Live starts would break his phone audio for the trip.
     *
     * Live is the last resort: the cloud voice failed or could not start ([from] CLOUD), or the
     * phone itself failed ([phoneFault]: mic, Silero, a crash). [words] overrides what Settings shows.
     */
    @Suppress("LongParameterList")
    private suspend fun handOver(
        moment: Handover.Moment,
        stage: String,
        reason: String,
        from: SpeechBackend.Kind? = backend?.kind,
        phoneFault: Boolean = false,
        words: String? = null,
    ) {
        if (!closing.compareAndSet(false, true)) return
        try {
            val source = if (phoneFault) "phone" else from?.token ?: "phone"
            val to = if (VoiceEngineSettings.LIVE_RETIRED) "none" else "live"
            LiveLatency.log(LiveLatency.TAG, CascadeLines.fallback(source, to, stage, reason))
            val said = words ?: failedWords(if (stage == "open") reason else "${stage}_$reason", from, phoneFault)
            VoiceEngineSettings.noteStatus(ctx, said)
            // A background line nobody asked for fails quietly on screen; Settings still says why.
            if (!quiet) host.notice(failedNotice(moment, said, from, phoneFault))
            release(reason)
        } finally {
            if (VoiceEngineSettings.LIVE_RETIRED) {
                // Live is retired (Kevin, 2026-11-15): never opened behind his back. The failure is
                // said in words above and the conversation ends with the closing tone.
                if (!quiet) host.closingTone()
                host.closed("voice_failed_$reason")
            } else {
                // Whatever happened above, Live is opened: never a dead assistant.
                host.handOverToLive(fromWakeWord, Handover.opener(moment))
            }
        }
    }

    /** What Settings says after a failure nothing could take over. */
    private fun failedWords(token: String, from: SpeechBackend.Kind?, phoneFault: Boolean): String = when {
        phoneFault -> VoiceEngineSettings.phoneFailedWords(token)
        from == SpeechBackend.Kind.CLOUD -> VoiceEngineSettings.cloudFailedWords(token)
        else -> VoiceEngineSettings.statusWords(token, usedCloud = false)
    }

    /** What the strip says after a failure nothing could take over. */
    private fun failedNotice(moment: Handover.Moment, said: String, from: SpeechBackend.Kind?, phoneFault: Boolean) =
        when {
            moment == Handover.Moment.AtOpen -> "Voice: $said"
            from == SpeechBackend.Kind.CLOUD && !phoneFault -> CLOUD_FAILED_NOTICE
            else -> LIVE_NOTICE
        }

    /** Anything thrown anywhere in this session: hand the conversation to Live (or just close). */
    private fun onCrash(t: Throwable) {
        val label = "crash_${t.javaClass.simpleName}"
        // Already closing: the hand-over or close in flight tells the controller; a session that
        // is not open any more is dropped by the controller's next tap in any case.
        if (closing.get()) return
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            handOver(currentMoment(), "crash", label, phoneFault = true)
        }
    }

    /** Idempotent full release. */
    private suspend fun shutdown(reason: String) {
        if (!closing.compareAndSet(false, true)) return
        release(reason)
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun release(reason: String) {
        // Called from inside a turn too (a hand-over, a dismissal): that turn must not cancel
        // itself, or the step after release (opening Live) would never run.
        val caller = kotlin.coroutines.coroutineContext[Job]
        withContext(NonCancellable) { releaseNow(reason, caller) }
    }

    /** Each release step on its own: one that throws must not keep the next from running. */
    @Suppress("TooGenericExceptionCaught")
    private inline fun step(name: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            Log.w(TAG, "release step $name failed: ${e.message}")
        }
    }

    private suspend fun releaseNow(reason: String, caller: Job?) {
        isOpen = false
        step("jobs") {
            followUpJob?.cancel()
            uttCapJob?.cancel()
            specPollJob?.cancel()
            discardDraft("closed")
            speaker?.interrupt()
            turnJob?.takeIf { it !== caller }?.cancel()
            currentUtt?.cancel()
            currentUtt = null
        }
        try {
            micJob?.takeIf { it !== caller }?.cancelAndJoin()
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            Log.w(TAG, "mic join: ${e.message}")
        }
        step("turn") { turnTaker?.reset() }
        step("player") { player?.release() }
        step("route_watch") { audioRoute.stopWatching() }
        step("call_audio") { callAudio?.let { if (it.isActive) it.exit("cascade $reason") } }
        step("focus") { abandonFocus() }
        step("caption") { CompanionPhase.setCaption("") }
        step("mic_claim") { if (micClaimed) MicArbiter.release(MicArbiter.Claimant.LIVE_TURN) }
        micClaimed = false
        step("socket") { backend?.close(reason) }
        step("usage") {
            if (auditStarted) {
                val engines = enginesUsed.joinToString(">")
                LiveLatency.log(LiveLatency.TAG, usageLedger.summary(engines, turnsAnswered.get()))
            }
            CACHE?.onCreated = null
        }
        step("audit") { if (auditStarted) audit.sessionEnd(reason) }
        step("warmup") {
            CascadeWarmup.conversationOpen = false
            // The next conversation finds its prompt ready ([CascadePrep]), and its greeting's pack.
            CascadePrep.prepare(ctx, "close")
            com.kevin.legion.greeting.GreetingFactory.prefetch(ctx, "close")
        }
        LiveLatency.log("LIVE_TURN", "cascade closed reason=$reason backend=${engineToken()}")
        scope.launch {
            delay(SCOPE_GRACE_MS)
            scope.cancel()
        }
    }

    // --- microphone ([CascadeMic], a trimmed copy of Live's micLoop) ---------------------------------

    private fun startMic() {
        micJob = scope.launch(Dispatchers.IO) {
            // The car's hands-free link, when it was asked for, carries the route before capture.
            audioRoute.awaitBluetoothMic()
            CascadeMic(ctx).run(
                // Under [turnLock]: a backend swap never lands inside a feed.
                onBuffer = { buf, n -> synchronized(turnLock) { turnTaker?.feed(buf, n, playbackActive) } },
                onNoPermission = {
                    host.notice("Didn't start - microphone permission is off")
                    close("no_mic_permission")
                },
                // A mic that cannot open or keeps failing is a deaf conversation, on any voice: Live.
                onFailure = { reason ->
                    scope.launch(loop) { handOver(currentMoment(), "mic", reason, phoneFault = true) }
                },
            )
        }
    }

    /**
     * Other audio ducks while the conversation is open (Live's own focus request: transient, may
     * duck, never paused by us), and comes back at release.
     */
    private fun requestFocus() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setWillPauseWhenDucked(false)
            .build()
        focus = req
        runCatching { audioManager.requestAudioFocus(req) }
    }

    private fun abandonFocus() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        focus?.let { audioManager.abandonAudioFocusRequest(it) }
        focus = null
    }

    companion object {
        private const val TAG = "CascadeSession"

        /** The text model (design doc: fastest measured, cheapest, no scheduled price change). */
        const val MODEL = "gemini-3.5-flash-lite"


        /** The loop's own backstop; the controller's runner already applies each tool's real ceiling. */
        const val TOOL_BACKSTOP_MS = 47_000L


        private const val CAPTION_CHARS = 160

        /** 16 kHz PCM16 mono. */
        private const val PCM_BYTES_PER_MS = 32
        private const val DROPPED_NOTICE = "Laptop voice dropped - switched to cloud voice"
        private val CLOUD_FAILED_NOTICE = if (VoiceEngineSettings.LIVE_RETIRED) {
            "Cloud voice failed - conversation ended, tap to try again"
        } else {
            "Cloud voice failed - switched to Live"
        }
        private val LIVE_NOTICE = if (VoiceEngineSettings.LIVE_RETIRED) {
            "Voice failed - conversation ended, tap to try again"
        } else {
            "Voice failed - switched to Live"
        }

        private const val MISSED_NOTICE = "Didn't catch that - say it again"

        /** The open's "refusal" token when the engine is Cloud: no laptop was asked. */
        private const val CLOUD_ENGINE = "cloud_engine"
        private const val SCOPE_GRACE_MS = 2_000L

        private val ACK = AckLines()

        /** The longest a check-in's closer may take to say before the conversation just ends. */
        private const val CLOSER_MAX_MS = 6_000L

        private const val OPENED_NOTE = "(He opened the conversation and you greeted him.)"

        @Volatile private var lastUnreachableAtMs = 0L

        private const val MS_PER_S = 1_000L

        /** The laptop failed to answer moments ago: not asked again yet ([CascadeTimings.RECENT_UNREACHABLE_MS]). */
        fun recentlyUnreachable(now: Long): Boolean = now - lastUnreachableAtMs < CascadeTimings.RECENT_UNREACHABLE_MS

        /** A successful Test connection in Settings: try the laptop again at once. */
        fun clearRecentFailure() {
            lastUnreachableAtMs = 0L
        }

        /** Every Gemini call a cascade makes is metered the same way (text loop, STT, TTS). */
        fun meter(model: String, usage: JSONObject?) {
            KeyHealth.noteOk()
            GeminiUsageMeter.recordRestCall(
                model,
                usage?.takeIf { it.has("promptTokenCount") }?.optInt("promptTokenCount"),
                usage?.takeIf { it.has("candidatesTokenCount") }?.optInt("candidatesTokenCount"),
                usage?.takeIf { it.has("totalTokenCount") }?.optInt("totalTokenCount"),
            )
        }

        /** The production cloud voice: Gemini on the user's own key, metered and logged like the text loop. */
        fun defaultCloudSpeech(parent: CoroutineScope, voice: String, usage: (CallUsage) -> Unit): SpeechBackend =
            CloudSpeech.create(
                parent = parent,
                transport = OkHttpGemini(),
                meter = { model, u -> meter(model, u) },
                log = { LiveLatency.log(LiveLatency.TAG, it) },
                voice = voice,
                usage = usage,
            )

        /** One cache store for the process: the cache outlives any one conversation. */
        @Volatile private var CACHE: ContextCacheStore? = null
        private val CACHE_SCOPE = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}

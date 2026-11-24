package com.kevin.legion.service.cascade

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.util.Log
import com.kevin.legion.BuildConfig
import com.kevin.legion.ai.AriaBrain
import com.kevin.legion.ai.CompanionProfile
import com.kevin.legion.clock.RingControl
import com.kevin.legion.clock.RingStopReason
import com.kevin.legion.ai.CrisisDetector
import com.kevin.legion.ai.GeminiKeyProvider
import com.kevin.legion.ai.GeminiUsageMeter
import com.kevin.legion.ai.KeyHealth
import com.kevin.legion.ai.VerbatimSpeech
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.ConversationAudit
import com.kevin.legion.data.local.auditContent
import com.kevin.legion.data.local.record
import com.kevin.legion.service.ChatTurn
import com.kevin.legion.service.CompanionPhase
import com.kevin.legion.greeting.GreetingBudget
import com.kevin.legion.backend.EventKind
import com.kevin.legion.calendar.CalendarWindowRead
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
import com.kevin.legion.service.Farewells
import com.kevin.legion.service.ListenCue
import com.kevin.legion.service.LiveToolbox
import com.kevin.legion.service.MicArbiter
import com.kevin.legion.service.Phase
import com.kevin.legion.service.ProactiveBus
import com.kevin.legion.service.TelephonyController
import com.kevin.legion.service.TextToolExecutor
import com.kevin.legion.service.WakePhrases
import com.kevin.legion.quiet.QuietMode
import com.kevin.legion.service.conversationlog.ConversationLog
import com.kevin.legion.service.turn.AudioRoute
import com.kevin.legion.service.turn.BargeInGate
import com.kevin.legion.service.turn.CallAudioMode
import com.kevin.legion.service.turn.EndOfTurnPolicy
import com.kevin.legion.service.turn.MicReopen
import com.kevin.legion.service.turn.TurnModels
import com.kevin.legion.service.turn.TurnTaker
import com.kevin.legion.service.turn.VoiceOutputRule
import com.kevin.legion.service.turn.VoiceTurnSettings
import com.kevin.legion.service.testturn.TestSession
import com.kevin.legion.service.testturn.TestTurns
import com.kevin.legion.service.tools.LiveSurface
import com.kevin.legion.service.tools.day.ConversationDay
import com.kevin.legion.service.tools.day.DateCarry
import com.kevin.legion.service.tools.day.EventRemoval
import com.kevin.legion.service.tools.day.RelativeDay
import com.kevin.legion.vehicle.ActiveVehicle
import com.kevin.legion.vehicle.ClearCodesVoiceGate
import com.kevin.legion.vehicle.DtcClearController
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
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
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
        defaultCloudSpeech(parent, VoiceEngineSettings.cloudVoice(context), usage)
    },
    /** Whether the cloud voice can start at all: it runs on the key Kevin pasted in Settings. */
    private val cloudCanStart: () -> Boolean = GeminiKeyProvider::hasKey,
    /** A test conversation from a computer ([TestSession]): test mic, logcat audit, no records kept. */
    private val test: TestSession? = null,
    /** Opened by a tap in the app (phone in hand): only then may the earpiece apply ([EarpieceRoute]). */
    private val inHand: Boolean = false,
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

    // A test conversation's audit goes to logcat only: never the engine spool, which memory reads.
    private val audit = if (test != null) {
        LiveAudit(sink = { TestTurns.auditSink(LiveAudit.TAG, it) }, onSession = {})
    } else {
        LiveAudit()
    }
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
    private val userTurns = AtomicInteger(0)

    /** Test clips waiting for the open or a reply to finish before they are queued ([testAudio]). */
    private val testClipsWaiting = AtomicInteger(0)
    private var greetingLoop: GeminiTextLoop? = null

    /** Speculative answers (the setting, read once at open) and the one draft in flight ([TurnDraft]). */
    private var speculate = false
    @Volatile private var draft: TurnDraft? = null
    private var specPollJob: Job? = null
    private var cacheStore: ContextCacheStore? = null

    /** The open's start (the controller's call, right after his tap or wake word), for every `open_*_ms`. */
    @Volatile private var openStartedAtMs = 0L

    /** How this conversation opens ([OpeningRule]): a tap gets the chime and the mic at once. */
    private val opening = OpeningRule.of(fromWakeWord, opener != null)

    /** A tap's audio from the mic's start until [turnTaker] exists ([MicBacklog]). Under [turnLock]. */
    private var backlog: MicBacklog? = null

    /** The route a tap entered before the voice service opened, and its barge-in decision; null otherwise. */
    @Volatile private var tapRoute: com.kevin.legion.service.turn.AudioRoute? = null
    @Volatile private var tapBarge = false

    /** The tap's chime has played (or could not): the listening window may start. */
    private val chimeDone = CompletableDeferred<Unit>()

    /** He started speaking at least once in this conversation (a tap's listening window then waits for his turn). */
    @Volatile private var heardSinceOpen = false
    private val micBufferSeen = AtomicBoolean(false)
    private val routeLogged = AtomicBoolean(false)
    private var firstAudioLogged = false
    @Volatile private var preamble = ""

    /** Today's agenda as read at open ([AgendaBlock]), for his turns only; "" when none is given. */
    @Volatile private var agendaRead = ""

    /** When [agendaRead] was read (0 when it is an "unavailable" line): it ages out after 30 min. */
    @Volatile private var agendaReadAtMs = 0L

    /** When this conversation first called a write: the agenda read before it is withdrawn. */
    @Volatile private var agendaChangedAt: LocalTime? = null
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

    /**
     * A background line nobody asked for: its failure is not put on screen. A listening check-in is
     * quiet too until its line has passed and played: a failure before that is never said.
     */
    private val quiet: Boolean
        get() = opener != null && !opener.userInitiated && (!opener.listen || checkInStep == CheckInStep.LINE)

    /** Focus, the car's hands-free mic, and the Bluetooth watch ([CascadeAudio]). */
    private val audioRoute = CascadeAudio(ctx, audioManager)
    private val earpiece = EarpieceRoute(ctx, audioManager)

    /** A call or a silenced capture mid-conversation ([CallPause]); [callPaused] once it has fired. */
    private val callWatch = CallWatch(ctx, audioManager) { reason -> scope.launch(loop) { pauseForCall(reason) } }
    @Volatile private var callPaused = false
    private var player: ReplyPlayer? = null
    private var callAudio: CallAudioMode? = null
    private var micJob: Job? = null
    /** The mic claim, released as the claimant it was asked as (a ring's too). */
    private val micHold = MicArbiter.Hold(micClaimant)
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

    /** The engine as the brain ([EngineBrain]); null with Settings' "Answer on the engine" off. */
    @Volatile private var engineBrain: EngineBrain? = null
    @Volatile private var lastLocalSpeechAtMs = 0L

    /** The laptop's running transcript, by utterance id: what the acknowledgement can tell at end of turn. */
    @Volatile private var heardSoFar: Pair<Int, String>? = null

    private var turnJob: Job? = null
    private var speaker: ReplySpeaker? = null
    @Volatile private var speaking = false
    private val history = mutableListOf<ChatTurn>()
    private var carry = ""
    private val readThroughCarry = ReadThroughCarry()
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

    /** Settings > Voice engine > Speaking speed, read once per conversation ([ReplyPlayer]). */
    private val speechSpeed: Float = VoiceEngineSettings.speechSpeed(ctx)

    /** Backchannels said lately ([Backchannel.pick] skips them) and a rotating index. */
    private val recentBackchannels =
        RecentLines(Backchannel.ROTATION, listOfNotNull(VoiceEngineSettings.lastBackchannel(ctx)))
    private val backchannelIndex = AtomicInteger(0)

    /** Whether his current speech started over a reply or a turn still running. */
    @Volatile private var uttOverReply = false

    /** The last utterance whose transcript was noise or empty: no model call, so no backchannel either. */
    @Volatile private var noiseUtt: TurnUtterance? = null

    /**
     * This turn's fillers ([WaitFillers]): how many played (every kind), when the last one ended, the
     * words said (no wait line repeats them), whether the answer's own audio started, and the timer.
     */
    private val turnFillers = AtomicInteger(0)
    @Volatile private var lastFillerEndAtMs = 0L
    private val saidThisTurn: MutableSet<String> = java.util.Collections.synchronizedSet(mutableSetOf())
    @Volatile private var answerAudioStarted = false

    /** A task line has started and not yet ended: no wait line starts meanwhile (F5). */
    @Volatile private var taskLinePlaying = false

    /**
     * This turn's answer as [DeadAir] reads it: when its first clip was asked of the voice, whether
     * the turn ends the conversation (a stop), the session's last ask-to-audio time, the turn's sound
     * timeline for the bench ([TurnGap]) and when the answer's first audio came into hand.
     */
    @Volatile private var answerAskedAtMs: Long? = null
    @Volatile private var turnEndsConversation = false
    @Volatile private var lastFirstAudioMs: Long? = null
    @Volatile private var turnGap: TurnGap? = null
    @Volatile private var answerInHandAtMs: Long? = null
    @Volatile private var toolSound = -1

    /** The model's greeting while it is held and spoken: no wait line plays over it. */
    @Volatile private var greetingJob: Job? = null

    @Volatile private var fillerSkip: Backchannel.Skip? = null
    private val recentWaitLines = RecentLines(WaitFillers.ROTATION)
    private val waitIndex = AtomicInteger(0)

    /**
     * The open's own audio (the tap chime, the listen cue): the job that plays or waits for it, and when
     * it ends. Not a reply: his first turn over it still gets a backchannel ([Backchannel.overReply]).
     */
    private var openingJob: Job? = null
    @Volatile private var openingCueEndsAtMs: Long? = null

    /**
     * This turn's backchannel and wait-line timers ([TurnFillerJobs]), ended with the turn's wait
     * (review B1), which turn they are for, and the backchannel clip's end.
     */
    private val fillerJobs by lazy { TurnFillerJobs(scope, loop) }

    /** A filler clip's playback mark, cleared at its end even when it is cut (review M1). */
    private val clipPlayback = ClipPlayback({ playbackActive = it }, { speaking }, { answerAudioStarted })
    private val backchannelSeq = AtomicInteger(0)
    @Volatile private var backchannelEndAtMs: Long? = null

    /** The utterance whose end scheduled the backchannel: a resume through the clip continues it. */
    @Volatile private var backchannelUtt: TurnUtterance? = null

    /** His audio through a half-duplex backchannel ([ClipListen]); under [turnLock]. */
    private var clipListen: ClipListen? = null

    /** Any audio of this turn's reply (filler or answer) has started: a backchannel due now is dropped. */
    @Volatile private var turnAudioStarted = false

    /** Every reply write goes through this, so a backchannel's clip and the answer never interleave. */
    private val playGate = kotlinx.coroutines.sync.Mutex()

    /** The last tool line said ([ToolFillers.pick] never repeats it next) and a rotating index. */
    @Volatile private var lastToolFiller: String? = null
    private val toolFillerIndex = java.util.concurrent.atomic.AtomicInteger(0)

    /** His languages (Settings) and the one this conversation is in; every conversation starts English. */
    private val languages: List<SpokenLanguages.Language> = SpokenLanguages.load(ctx)
    @Volatile private var conversationLanguage: SpokenLanguages.Language = SpokenLanguages.ENGLISH

    /** When the last reply finished playing (0: none yet), for [MissedLine.hold]. */
    @Volatile private var lastReplyEndAtMs = 0L

    /** "Didn't catch that" said this many times in a row; the third closes the conversation. */
    private var missedInARow = 0

    /** The date the model's prompt states as today ([RelativeDay.TurnDates]). */
    @Volatile private var promptDay: LocalDate = RelativeDay.today()

    /** The day the last turn was about, offered to this turn's clock-timed writes ([DateCarry.carried]). */
    private val conversationDay = ConversationDay()

    /** Writes that went through and that he has not heard said yet ([OwedWrites]). */
    private val owed = OwedWrites()

    /** A hand-over has begun: a second one (a crash during it) returns at once. */
    private val handingOver = java.util.concurrent.atomic.AtomicBoolean(false)

    /** His last turn's tools asked him something ([LoneWordWriteGuard.asks]): one word may answer it. */
    @Volatile private var lastTurnAsked = false

    /** Transcripts of noise in a row ([SttGuard.NOISE]): silent, but capped ([MAX_NOISE_IN_A_ROW]). */
    private var noiseInARow = 0

    /** The last line spoken (not a filler): a question gets the longer follow-up window. */
    @Volatile private var lastClipText: String? = null

    /** What one turn has done so far: what [Handover] needs if the laptop drops mid-turn. */
    private class TurnRecord(
        val heard: String,
        val fromUser: Boolean = true,
        /** The voiced time of the clip his words came from ([SttGuard.voicedMs]); null when not measured. */
        val voicedMs: Long? = null,
    ) {
        val reply = StringBuffer()

        /** When the turn started, and its date-taking tool calls so far ([RelativeDay.TurnDates]). */
        val startedAt: LocalTime = LocalTime.now()
        @Volatile var dateCalls = 0
        val toolsRan = mutableListOf<String>()
        val writesDone = mutableListOf<String>()

        /** Each write's facts as its result came in, and those whose code line played whole ([OwedWrites]). */
        val writes = TurnWrites()
        @Volatile var readThrough = false
        @Volatile var fillerPlayed = false

        /** Words the phone spoke that the model must never read back ([VerbatimSpeech.PRIVATE]). */
        @Volatile var privateSpoken: String? = null

        /**
         * Marked before any tool ran (ADR 0090): a read-through opener, or the turn after one
         * ([ReadThroughCarry]). Unlike [readThrough], a tool's own mark never sets it.
         */
        @Volatile var startMarked = false

        /** A write tool was called this turn (set as it starts, before it can run). */
        @Volatile var writeCalled = false

        /**
         * A resume through a clip took this turn over ([resumeThroughClip]): set under [turnLock], the same
         * lock a write takes to start, so a write and a takeover never both happen (review of 206a8898).
         */
        @Volatile var resumeClaimed = false

        /** The turn before asked him something ([LoneWordWriteGuard]); this turn's tools did. */
        @Volatile var askedBefore = false
        @Volatile var asked = false

        /** Dropped for his next words, which carry these: not recorded on its own. */
        @Volatile var merged = false

        /** A check-in line: held until whole, checked ([CheckInRules.lineRejection]), then said or dropped. */
        @Volatile var holdLine = false

        /** The engine heard this turn ([EngineUtterance]): it answers unless the phone keeps it. */
        @Volatile var engineUtt: EngineUtterance? = null

        /** The engine heard this turn's audio, even if its stream was released for a merge. */
        @Volatile var engineHeard = false

        /** The phone was waiting on his answer to its own question ([EngineBrainRules.phoneAwaits]). */
        @Volatile var awaits: String? = null
    }

    // --- open ------------------------------------------------------------------------------------

    fun open() {
        openStartedAtMs = System.currentTimeMillis()
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
        val started = openStartedAtMs.takeIf { it > 0 } ?: System.currentTimeMillis().also { openStartedAtMs = it }
        LiveLatency.log(LiveLatency.TAG, "open_kind=${opening.name.lowercase()}")
        // The greeting's pack is fetched from the wake word, overlapping the probe, connect and prompt
        // build, so it is long since answered (or timed out) by the time the line is due. A tap has none.
        val greetingPlan = if (opening == OpeningRule.Opening.GREETING) {
            greeting?.let { g -> scope.async(Dispatchers.IO) { g.prepare(LocalTime.now()) } }
        } else {
            null
        }
        val config = VoiceEngineSettings.laptop(ctx)
        CascadeWarmup.conversationOpen = true
        // Never for a test conversation: the engine has no test mode yet, and its own writes would
        // pass by the phone's TestTurnPolicy.
        if (listens && test == null) {
            engineBrain = EngineBrainOpen.open(ctx, scope)
        }
        // The laptop is probed and connected while the mic and the turn models get ready (a wake
        // word's onset may have started it already); [tryLaptop] takes it.
        if (laptopFirst) LaptopPrewarm.start(ctx, "open")
        if (listens) {
            // The mic first, as Live claimed it before it connected: the wake capture lets go while
            // the backend opens, so the first capture is not refused.
            if (!micHold.take { scope.launch(loop) { onMicPreempted() } }) {
                LaptopPrewarm.cancel("open_aborted")
                host.notice("Didn't start - the microphone is in use")
                shutdown("mic_held")
                host.closed("mic_held")
                return
            }
        }
        LiveLatency.log(LiveLatency.TAG, "open_mic_ms=${System.currentTimeMillis() - started}")
        if (listens && opening == OpeningRule.Opening.CHIME) openMicNow()
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
        // The transcriber's names (his cars, his name), from data already on the phone ([SttHintStore]).
        if (test == null) scope.launch(Dispatchers.IO) { SttHintStore.refresh(ctx) }
        SttHintStore.instruction = SpokenLanguages.sttInstruction(languages, SpokenLanguages.ENGLISH)
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
        // A tap entered its route before the voice service opened: the plan takes the same route, so
        // its barge-in decision is the one the audio mode was entered for.
        val early = tapRoute
        val p = CascadeRules.turnPlan(
            serviceEndsTurns = b.stt.endsTurns,
            smartSetting = VoiceTurnSettings.smartEndOfTurn(ctx),
            smartTurnReady = smartReady,
            bargeSetting = if (early != null) tapBarge else VoiceTurnSettings.bargeIn(ctx),
            route = early ?: CallAudioMode.route(audioManager),
            inCall = if (early != null) false else TelephonyController.isInCall,
            earlySetting = VoiceTurnSettings.earlyEndOfTurn(ctx),
        )
        plan = p
        if (models != null) {
            synchronized(turnLock) {
                val taker = TurnTaker(
                    p.config,
                    models.silero.stream(),
                    models.smartTurn.takeIf { p.config.smartTurn },
                    sink,
                    p.endBaseMs,
                    p.endFinishedMs,
                )
                turnTaker = taker
                // What he said since the tap goes through the detector first, in order, before the
                // next live buffer (the mic feeds under this same lock).
                backlog?.let { held ->
                    val ms = held.drainTo { pcm, n, playing -> taker.feed(pcm, n, playing) }
                    LiveLatency.log(
                        LiveLatency.TAG,
                        "open_backlog_ms=$ms dropped_ms=${held.droppedBytes / MicBacklog.PCM_BYTES_PER_MS}",
                    )
                }
                backlog = null
            }
            if (early == null) enterRoute(p.config.bargeIn, b.kind.token)
            LiveLatency.log(LiveLatency.TAG, "open_listening_ms=${System.currentTimeMillis() - started}")
        }
        player = ReplyPlayer(b.tts.sampleRate, { callAudio?.isActive == true }) { speechSpeed }
        audit.enabled = ConversationLog.auditWantedAfterReading(ctx) || test != null
        audit.sessionStart("${b.kind.token}+$MODEL", emptySet(), brain.basePackToken)
        auditStarted = true
        LiveLatency.log(
            "LIVE_TURN",
            "config manual=true bargeIn=${p.config.bargeIn} smartTurn=${p.config.smartTurn} (${p.config.note})",
        )
        val openMs = System.currentTimeMillis() - started
        LiveLatency.log(LiveLatency.TAG, CascadeLines.open(openMs, probeMs, p, b.tts.streamsText, b.kind.token))
        // A check-in opens the mic only once its line has passed and played ([afterCheckInLine]).
        if (listens && checkInStep == CheckInStep.NONE && micJob == null) startMic()
        if (closing.get()) return
        if (opener != null) {
            speakOpener(opener)
            return
        }
        if (opening == OpeningRule.Opening.CHIME) {
            listenAfterChime()
            return
        }
        val plan = greetingPlan?.await()
        LiveLatency.log(LiveLatency.TAG, "open_greeting_plan_ms=${System.currentTimeMillis() - started} " +
            "plan=${plan?.javaClass?.simpleName ?: "none"}")
        if (closing.get()) return
        if (plan is GreetingPlan.Spoken) {
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
            // The date the model is given: the prepared prompt's day, or today as it is built now.
            promptDay = prepared?.day ?: RelativeDay.today()
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
            // Today's agenda, beside the live context: the phone's own read, bounded by AGENDA_WAIT_MS.
            val agenda = async { loadAgenda() }
            val split = base.await()
            val t = System.currentTimeMillis()
            val live = runCatching { brain.buildLiveContext() }.getOrDefault("")
            LiveLatency.log(LiveLatency.TAG, "open_live_context_ms=${System.currentTimeMillis() - t}")
            preamble = CascadePrompt.preamble(split.volatile, live)
            agendaRead = agenda.await()
        }
    }

    /**
     * Today's agenda for his turns (voice quick wins C, ADR 0093): the phone's own read of today,
     * the same one `day read_calendar` makes ([CalendarWindowRead.today]), no network. Runs beside
     * the live context inside `preambleReady`, bounded by [AGENDA_WAIT_MS]; the greeting never waits
     * for it. "" for a test conversation or one with an opener (a proactive line or a check-in
     * states only its own facts).
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException") // no read is said in words, never a failed open
    private suspend fun loadAgenda(): String {
        if (test != null || opener != null) return ""
        val t = System.currentTimeMillis()
        val zone = RelativeDay.clock().zone
        val block = try {
            val nowMs = RelativeDay.clock().millis()
            val read = withTimeoutOrNull(AGENDA_WAIT_MS) { CalendarWindowRead.today(ctx, zone, nowMs) }
            if (read == null) {
                AgendaBlock.unavailable(AgendaBlock.NOT_READ_IN_TIME)
            } else {
                agendaReadAtMs = read.readAtMs
                AgendaBlock.render(agendaSnapshot(read), zone)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LiveLatency.log(LiveLatency.TAG, "agenda_unavailable reason=${e.javaClass.simpleName}")
            AgendaBlock.unavailable(AgendaBlock.READ_FAILED)
        }
        LiveLatency.log(LiveLatency.TAG, "open_agenda_ms=${System.currentTimeMillis() - t} chars=${block.length}")
        return block
    }

    /** The tool's read as the block takes it: events only (tasks are in its due summary). */
    private fun agendaSnapshot(read: CalendarWindowRead) = AgendaBlock.Snapshot(
        events = read.events.filter { it.kind == EventKind.EVENT && it.startsAt != null }.map {
            val start = it.startsAt ?: 0L
            AgendaBlock.AgendaEvent(it.title, start, it.endsAt ?: start, it.allDay, it.location)
        },
        due = read.due,
        readAtMs = read.readAtMs,
    )

    /** The agenda as his next turn sees it: as read, withdrawn after a write, or aged out. */
    private fun agendaNow(): String = AgendaBlock.current(
        agendaRead,
        agendaReadAtMs,
        agendaChangedAt,
        System.currentTimeMillis(),
        ZoneId.systemDefault(),
    )

    /** His turns' preamble: the shared one plus the agenda ([CascadePrompt.withAgenda]). */
    private fun turnPreamble(): String =
        CascadePrompt.withAgenda(preamble, agendaNow()) + SpokenLanguages.turnNote(conversationLanguage)

    /** A write was called: the agenda read at open is withdrawn, and this turn's result says so. */
    private fun noteChange(response: JSONObject) {
        if (agendaChangedAt == null) agendaChangedAt = LocalTime.now()
        if (agendaRead.isNotEmpty()) response.put("agenda_note", AgendaBlock.TOOL_NOTE)
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
    /**
     * A test turn (adb, [TestTurns]): [text] taken as his own words, as if speech-to-text had just
     * returned it. Waits for the open (and its greeting) to finish, then runs like a spoken turn.
     * False when this is not a test conversation or it is closing.
     */
    fun testTurn(text: String): Boolean {
        if (test == null || closing.get() || !listens) return false
        scope.launch(loop) {
            awaitTestOpen()
            if (closing.get()) return@launch
            val previous = turnJob
            turnJob = scope.launch(loop) {
                previous?.join()
                if (closing.get()) return@launch
                followUpEvent(FollowUpWindow.Event.UserSpeech)
                host.phase(Phase.THINKING, "...")
                userTurns.incrementAndGet()
                audit.user(text, final = true, typed = true)
                answerTurn(text, System.currentTimeMillis(), TurnRecord(text))
            }
        }
        return true
    }

    /**
     * A test clip ([TestSession.mic]) in place of his voice: queued once the open and any reply
     * have finished, as he would wait to speak, or at once with [barge] to talk over a reply.
     */
    fun testAudio(pcm: ByteArray, barge: Boolean): Boolean {
        val mic = test?.mic?.takeIf { !closing.get() && listens } ?: return false
        if (!barge) {
            testClipsWaiting.incrementAndGet()
            scope.launch(loop) {
                try {
                    awaitTestOpen()
                    turnJob?.join()
                    if (!closing.get()) mic.queue(pcm)
                } finally {
                    testClipsWaiting.decrementAndGet()
                }
            }
        }
        return !barge || mic.queue(pcm)
    }

    /** Still opening: the greeting (or its scripted line) becomes the first turnJob. */
    private suspend fun awaitTestOpen() {
        var waited = 0L
        while (turnJob == null && !closing.get() && waited < CascadeTimings.MODEL_WAIT_MS + OPEN_WAIT_MS) {
            delay(TEST_POLL_MS)
            waited += TEST_POLL_MS
        }
    }

    /** For the test door's status: a reply audible, a turn in flight, turns answered. */
    val testSpeaking: Boolean get() = speaking
    val testBusy: Boolean get() = turnJob?.isActive == true
    val answeredTurns: Int get() = turnsAnswered.get()
    val heardTurns: Int get() = userTurns.get()
    val testAudioPending: Boolean get() = testClipsWaiting.get() > 0 || test?.mic?.pending == true

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
        it.startMarked = o.readThrough
        it.holdLine = o.checkIn != null && checkInStep == CheckInStep.LINE
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
        // Call audio just started or its device moved: the echo canceller must learn the room again.
        override fun takeAudioPathChange() = callAudio?.takePathChange() == true

        override fun sendActivityStart() {
            val b = backend ?: return
            var self: TurnUtterance? = null
            // Waiting on his answer to the phone's own question: the phone hears and answers it (R1).
            val awaits = engineBrain?.let {
                val checkIn = checkInStep == CheckInStep.LINE || checkInStep == CheckInStep.LISTENING
                EngineBrainRules.phoneAwaits(checkIn, lastTurnAsked, it.context.mailExchange)
            }
            val inner = engineBrain?.utterance(b.kind == SpeechBackend.Kind.CLOUD, awaits) ?: b.stt.begin {
                val utt = self ?: return@begin
                scope.launch(loop) { onServiceEnd(utt) }
            }
            // His words are always kept on the phone (30 s cap): a laptop that fails is heard again by the
            // cloud, a cloud transcript that never came is measured for voice and asked again
            // ([MissedLine]), and a speculative draft is transcribed from them.
            val u = TurnUtterance(inner, b, keepAudio = true)
            u.awaits = awaits
            self = u
            currentUtt = u
            resumeThroughClip(u)
            scope.launch(loop) {
                onUserTurnStart()
                // No drafts for a turn the engine hears (plan 1119 row 25); the phone's own turns keep them.
                if (speculate && b.kind == SpeechBackend.Kind.CLOUD && u.engine == null) watchCloudSilence(u)
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
            // Smart Turn scores too (2026-11-20), so drive logs show how often one lands near 0.5.
            LiveLatency.log(LiveLatency.TAG, com.kevin.legion.service.turn.EndOfTurnLine.of(end))
        }

        override fun onBargeIn(fire: BargeInGate.Event.Fire) {
            scope.launch(loop) { interrupt("barge_in") }
        }

        override fun onBargeInDisabled(selfInterrupts: Int) {
            host.notice("Interrupting turned off for this conversation")
            scope.launch(loop) {
                // On the earpiece call audio is the route itself, not only barge-in: it stays.
                if (callAudio?.onEarpiece == true) return@launch
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
        heardSinceOpen = true
        if (continuesFirstHalf()) return
        // Over an audible reply or a turn still in flight: a correction or a merge, never a backchannel.
        // The open's chime or cue is neither (2026-11-20: it made the first turn after a tap over_reply).
        val opening = turnJob != null && turnJob === openingJob
        uttOverReply = Backchannel.overReply(speaking, turnJob?.isActive == true, openingCue = opening)
        cutAck()
        if (checkInStep == CheckInStep.LINE || checkInStep == CheckInStep.LISTENING) {
            checkInStep = CheckInStep.DONE
            reportCheckIn(CheckInOutcome.ANSWERED)
        }
        draft?.takeIf { it.utt !== currentUtt }?.let { discardDraft("new_utterance") }
        followUpEvent(FollowUpWindow.Event.UserSpeech)
        audit.activityStart()
        if (!speaking) host.phase(Phase.LISTENING, "Listening...")
        // He started again before the last answer began playing ([CascadeRules.newSpeech]): a turn
        // with no tool or only reads is dropped and his words carry into this one, so a pause never
        // splits one request in two; a turn that called a write is stopped as a barge-in stops it.
        val pending = turn?.takeIf { it.fromUser }
        val tools = pending?.let { synchronized(it) { it.toolsRan.size } } ?: 0
        val action = CascadeRules.newSpeech(
            turnRunning = turnJob?.isActive == true,
            replyAudible = speaking,
            pendingFromUser = pending != null,
            toolsCalled = tools > 0,
            writeCalled = pending?.writeCalled == true,
            bargeIn = turnTaker?.bargeIn == true,
        )
        when (action) {
            CascadeRules.NewSpeech.MERGE -> {
                val p = pending ?: return
                p.merged = true
                carry = (carry + " " + p.heard).trim()
                turnJob?.cancel()
                speaker?.interrupt()
                val reason = if (tools > 0) "speech_during_lookup" else "speech_resumed"
                LiveLatency.log("LIVE_TURN", "turn_merged reason=$reason")
            }
            CascadeRules.NewSpeech.INTERRUPT -> interrupt("speech_during_tools")
            CascadeRules.NewSpeech.NONE -> Unit
        }
    }

    /**
     * Called from the detector's start, under [turnLock]. A start soon after a half-duplex backchannel
     * ([ClipListen.resumes]) continues the turn before it, if that turn is still running and called no
     * write: the new utterance begins with the first half's audio and the audio kept during the clip.
     */
    @Suppress("ReturnCount") // nothing held, no first half, not a resume
    private fun resumeThroughClip(u: TurnUtterance) {
        val listen = clipListen ?: return
        clipListen = null
        val first = backchannelUtt ?: return
        val takesOver = ClipListen.takesOver(turnJob?.isActive == true, turn?.writeCalled == true)
        if (!listen.resumes() || !takesOver || first === u) return
        // Under turnLock (the mic feed holds it): from here no write of the first turn can start.
        turn?.resumeClaimed = true
        val prefix = listen.prefix(TurnTaker.PRE_ROLL_MS * PCM_BYTES_PER_MS)
        LiveLatency.log(LiveLatency.TAG, ClipListen.joinedLine(listen.keptMs, clipListenKind, clipListenIdx))
        u.audio(prefix, 0, prefix.size)
        u.resumedFrom = first
        LiveLatency.log(LiveLatency.TAG, "backchannel_resume prefix_ms=${prefix.size / PCM_BYTES_PER_MS}")
    }

    /** A start that resumed through a backchannel ([resumeThroughClip]): the turn goes on, true. */
    @Suppress("ReturnCount") // not resumed, a write already called, taken over
    private fun continuesFirstHalf(): Boolean {
        val first = currentUtt?.resumedFrom ?: return false
        // Asked again here: a write that started after the mic thread's check must not run twice.
        if (!ClipListen.takesOver(turnJob?.isActive == true, turn?.writeCalled == true)) {
            currentUtt?.resumedFrom = null
            LiveLatency.log("LIVE_TURN", "resume_declined reason=write_called")
            return false
        }
        takeOverFirstHalf(first)
        uttOverReply = true
        followUpEvent(FollowUpWindow.Event.UserSpeech)
        audit.activityStart()
        if (!speaking) host.phase(Phase.LISTENING, "Listening...")
        return true
    }

    /** The first half is not answered on its own: it is in the resumed utterance now. */
    private fun takeOverFirstHalf(first: TurnUtterance) {
        turn?.takeIf { it.fromUser }?.merged = true
        first.cancel()
        discardDraftOf(first, "resumed")
        turnJob?.cancel()
        speaker?.interrupt()
        LiveLatency.log("LIVE_TURN", "turn_merged reason=resumed_through_backchannel")
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
        scheduleBackchannel(u, eotAtMs)
        scheduleWaitFillers(eotAtMs)
        val previous = turnJob
        // A turn queued behind one still running is decided as soon as its words are in, not when
        // the queue reaches it: the engine's 5 s hold would not wait that long (review R2, round 3).
        u.engine?.takeIf { previous?.isActive == true }?.let { e -> scope.launch(loop) { decideEarly(u, e) } }
        turnJob = scope.launch(loop) {
            previous?.join()
            try {
                runTurn(u, eotAtMs)
            } finally {
                // Not answered by the engine (a stop, noise, the phone loop took it): its turn is cancelled.
                u.engine?.release()
            }
        }
    }

    /**
     * His speech ended: one backchannel ([Backchannel]) [Backchannel.DELAY_MS] later unless a rule
     * says no or the answer's audio is there first. Decided now from signals code already has (how
     * this turn started, Quiet, the route), never his words. Every turn of his, short or long.
     */
    private fun scheduleBackchannel(u: TurnUtterance, eotAtMs: Long) {
        // A new turn's wait starts: the last one's timers are over.
        fillerJobs.endTurn()
        backchannelUtt = u
        // What is not in memory yet is asked for again (a warm that failed, the first session after an update).
        warmFillers()
        turnAudioStarted = false
        backchannelEndAtMs = null
        turnFillers.set(0)
        lastFillerEndAtMs = eotAtMs
        saidThisTurn.clear()
        answerAudioStarted = false
        taskLinePlaying = false
        answerAskedAtMs = null
        answerInHandAtMs = null
        turnEndsConversation = false
        // The last turn's first-sound line if it ended with nothing heard, then this turn's tracker.
        turnGap?.silentLine()?.let { LiveLatency.log(LiveLatency.TAG, it) }
        turnGap = TurnGap(eotAtMs, lastLocalSpeechAtMs)
        val seq = backchannelSeq.incrementAndGet()
        val skip = Backchannel.decide(
            Backchannel.Turn(
                fromUser = true,
                overReplyOrTurn = uttOverReply || carry.isNotBlank(),
                quiet = QuietMode.isOn(ctx),
                onCall = callOnLine(),
            ),
        ) ?: ackWordsSkip(u)
        fillerSkip = skip
        if (skip != null) {
            LiveLatency.log(LiveLatency.TAG, "backchannel_skip reason=${skip.token}")
            turnGap?.noteSkip(skip.token)
            return
        }
        fillerJobs.startBackchannel { playBackchannel(seq, eotAtMs) }
    }

    /** What the words heard so far already rule out ([Backchannel.wordsSkip]); null with none heard yet. */
    private fun ackWordsSkip(u: TurnUtterance): Backchannel.Skip? = Backchannel.wordsSkip(
        // The final when the voice already has it (the laptop ending the turn itself), else the running
        // transcript (2026-11-21: on a laptop goodbye the running words were not yet the goodbye and an
        // acknowledgement played; the final was already in). The goodbye test is the stop rule's own.
        u.finalText() ?: heardSoFar?.takeIf { it.first == u.id }?.second,
        goodbye = ::endsConversation,
        crisis = CrisisDetector::detect,
    )

    /**
     * The backchannel itself: from memory only, measured before it plays ([Backchannel.clipFits]),
     * under [playGate] so the answer waits for it rather than overlapping. Counted as playback so the
     * mic never takes it for him; never a reply (no caption, no history, no follow-up event), but its
     * own audit line so the next drive audit can count it.
     */
    private suspend fun playBackchannel(seq: Int, eotAtMs: Long) {
        val b = backend ?: return
        val p = player ?: return
        AckRun(System::currentTimeMillis, playGate, ackSteps(seq, eotAtMs, b, p)).run(eotAtMs)
    }

    /** The session's side of [AckRun]: its checks, its clip, its audit and its marks. */
    private fun ackSteps(seq: Int, eotAtMs: Long, b: SpeechBackend, p: ReplyPlayer) = object : AckRun.Steps {
        override suspend fun stillDue() = backchannelStillDue(seq, eotAtMs)

        // A clip that fails the length gate is swapped for another word; silent only if none fits.
        override suspend fun pick() = AckClips.pick(b.tts, recentBackchannels, backchannelIndex.getAndIncrement())

        override fun noClip() {
            LiveLatency.log(LiveLatency.TAG, "backchannel_skip reason=no_clip_fits")
            turnGap?.noteSkip("no_clip_fits")
        }

        override fun started(picked: AckClips.Picked) = noteAckStarted(picked, eotAtMs)

        override suspend fun play(pcm: ByteArray) {
            val sound = turnGap?.started(TurnGap.BACKCHANNEL, System.currentTimeMillis())
            logFirstSound()
            clipPlayback.during { playAckClip(p, pcm) }
            sound?.let { turnGap?.ended(it, System.currentTimeMillis()) }
        }

        override suspend fun after() {
            // The Bluetooth tail settles before his audio counts again; the mark clears even if cut.
            clipPlayback.during { delay(MicReopen.settleMs(CallAudioMode.route(audioManager))) }
            backchannelEndAtMs = System.currentTimeMillis()
            lastFillerEndAtMs = System.currentTimeMillis()
        }
    }

    /** The acknowledgement plays: its rotation, its count and its audit line (numbers only). */
    private fun noteAckStarted(picked: AckClips.Picked, eotAtMs: Long) {
        val chosen = picked.chosen
        val line = chosen.word
        listenThroughClip("backchannel", Backchannel.LINES.indexOf(line))
        recentBackchannels.add(line)
        VoiceEngineSettings.setLastBackchannel(ctx, line)
        turnFillers.incrementAndGet()
        LiveLatency.log(
            LiveLatency.TAG,
            // The word as its index in Backchannel.LINES (a number, never the word), and how many
            // words' clips failed the length gate before it.
            "backchannel at_ms=${System.currentTimeMillis() - eotAtMs} clip_ms=${chosen.ms} " +
                "word=${Backchannel.LINES.indexOf(line)} take=${chosen.take} rejected=${chosen.rejected} " +
                // 1: the answer's text was already in, so the answer waits for this clip.
                "answer_text=${if (answerTextIn()) 1 else 0}",
        )
        audit.filler("backchannel", line)
    }

    /**
     * This turn's first sound was handed over: its line is logged once the playback head moves past
     * where it is now (the sound is being heard), or the turn ends with it unheard ([TurnGap.silentLine]).
     */
    private fun logFirstSound() {
        val gap = turnGap?.takeIf { it.awaitingHeard() } ?: return
        val p = player ?: return
        val from = p.headFrames() ?: 0L
        scope.launch(loop) {
            val until = System.currentTimeMillis() + HEARD_WATCH_MS
            while (System.currentTimeMillis() < until && gap.awaitingHeard()) {
                if ((p.headFrames() ?: from) > from) gap.heard(System.currentTimeMillis())
                gap.firstSoundLine()?.let { LiveLatency.log(LiveLatency.TAG, it) }
                delay(HEARD_POLL_MS)
            }
        }
    }

    /**
     * The acknowledgement's audio, cut at once when its job is cancelled (he spoke again, a stop, a
     * goodbye found in his words): it never plays on over him. Only its own audio is flushed: the
     * answer waits for this clip under [playGate], so nothing else is in the track.
     */
    private suspend fun playAckClip(p: ReplyPlayer, pcm: ByteArray) {
        val gen = p.generation()
        ackCut.playing = true
        try {
            withContext(Dispatchers.IO) { p.write(p.startable(pcm), gen) }
            // Never held past its own length and a margin: the answer waits on this clip's lock.
            p.awaitDrained(pcm.size * MS_PER_S / (p.sampleRate * PCM_BYTES_PER_SAMPLE) + ACK_DRAIN_MARGIN_MS)
        } finally {
            ackCut.playing = false
        }
    }

    /** Whoever ends the acknowledgement cuts it at once, before the next line is written ([AckCut]). */
    private val ackCut by lazy { AckCut(endJobs = { fillerJobs.endTurn() }, flush = { player?.flush() }) }

    private fun cutAck() = ackCut.cut()

    /** The clip whose audio [clipListen] keeps: its kind and line index, for [ClipListen.joinedLine]. */
    @Volatile private var clipListenKind = ""
    @Volatile private var clipListenIdx = -1

    /** A call on the line for the fillers: the phone state, or a call's audio mode not LEGION's own. */
    private fun callOnLine(): Boolean =
        Backchannel.callOnLine(TelephonyController.isInCall, audioManager.mode, CallAudioMode.ownCommActive)

    /** A task line started: audited, and counted toward this turn's fillers and its said words. */
    /** A task line played to its end: the next wait line counts from now (F5). */
    private fun noteTaskFillerEnded() {
        lastFillerEndAtMs = System.currentTimeMillis()
        taskLinePlaying = false
        // All of it is with the player now; it ends when what is queued has played.
        turnGap?.ended(toolSound, lastFillerEndAtMs + (player?.aheadMs() ?: 0L))
    }

    private fun noteTaskFiller(text: String) {
        taskLinePlaying = true
        toolSound = turnGap?.started(TurnGap.TOOL, System.currentTimeMillis()) ?: -1
        logFirstSound()
        audit.filler("task", text)
        turnFillers.incrementAndGet()
        saidThisTurn.add(text)
    }

    /**
     * His speech ended: the wait lines ([WaitFillers]) for this turn. Each is due [WaitFillers.FIRST_GAP_MS]
     * or [WaitFillers.NEXT_GAP_MS] after the last filler of any kind ended, until the answer's AUDIO is
     * in hand ([DeadAir]: the first text alone no longer ends them), up to [WaitFillers.MAX_PER_TURN]
     * fillers in all. Re-read every [WAIT_POLL_MS], so a backchannel or task line that ends later moves
     * the next one back.
     */
    private fun scheduleWaitFillers(eotAtMs: Long) {
        val seq = backchannelSeq.get()
        // The backchannel's own rules (Quiet, a call, over a reply, half-duplex) bind these too.
        if (fillerSkip != null) return
        fillerJobs.startWait {
            var said = 0
            while (seq == backchannelSeq.get() && !closing.get()) {
                val at = WaitFillers.nextAtMs(lastFillerEndAtMs, said, turnFillers.get()) ?: return@startWait
                val wait = at - System.currentTimeMillis()
                if (wait > 0) {
                    delay(minOf(wait, WAIT_POLL_MS))
                    continue
                }
                when (playWaitFiller(seq, eotAtMs, said)) {
                    WaitPlay.PLAYED -> said++
                    WaitPlay.NOT_YET -> delay(WAIT_POLL_MS)
                    WaitPlay.STOP -> return@startWait
                }
            }
        }
    }

    /** This turn's wait, now, as [DeadAir] reads it. */
    private fun deadAir(seq: Int) = DeadAir.State(
        nowMs = System.currentTimeMillis(),
        answerAudioReady = answerAudioStarted,
        answerAskedAtMs = answerAskedAtMs,
        expectedFirstAudioMs = DeadAir.expectedFirstAudioMs(
            cloud = backend?.kind == SpeechBackend.Kind.CLOUD,
            measuredMs = lastFirstAudioMs,
        ),
        closing = closing.get(),
        stopped = seq != backchannelSeq.get(),
        goodbye = turnEndsConversation,
        greetingHeld = greetingJob?.isActive == true,
    )

    /**
     * One wait line, from memory, under [playGate] like the backchannel. [DeadAir] decides: never once
     * the answer's audio is in hand, and after its first clip was asked for only a line that ends by its
     * expected audio. A line playing when the answer's audio arrives is cut if more than
     * [DeadAir.FINISH_MAX_MS] of it is left ([playPacedWait]); the answer's writes wait on the same gate.
     */
    private enum class WaitPlay { PLAYED, NOT_YET, STOP }

    @Suppress("ReturnCount") // one worded skip per reason
    private suspend fun playWaitFiller(seq: Int, eotAtMs: Long, said: Int): WaitPlay {
        val b = backend ?: return WaitPlay.STOP
        val p = player ?: return WaitPlay.STOP
        fun over() = QuietMode.isOn(ctx) || callOnLine() ||
            DeadAir.waitLine(deadAir(seq), 0L)?.let(DeadAir::endsWait) == true
        if (over()) return WaitPlay.STOP
        var pcm = ByteArray(0)
        val chosen = WaitFillers.choose(
            recentWaitLines,
            saidThisTurn.toSet(),
            waitIndex.getAndIncrement(),
            inHand = { line, take -> b.tts.inHandTake(SpokenText.forSpeech(line), take) },
        ) { line, take ->
            val (audio, ms) = AckClips.trimmed(b.tts, line, take)
            pcm = audio
            ms
        }
        if (chosen == null) {
            LiveLatency.log(LiveLatency.TAG, "filler_skip kind=wait reason=no_clip_fits")
            return WaitPlay.STOP
        }
        playGate.withLock {
            if (over()) return WaitPlay.STOP
            // Too long to end before the answer's expected audio: asked again at the next poll.
            if (DeadAir.waitLine(deadAir(seq), chosen.ms) != null) return WaitPlay.NOT_YET
            // A task line queued on the gate, or one that just ended, moves this one back (F5).
            val now = System.currentTimeMillis()
            if (!WaitFillers.dueNow(now, lastFillerEndAtMs, said, turnFillers.get(), taskLinePlaying)) {
                return WaitPlay.NOT_YET
            }
            recentWaitLines.add(chosen.line)
            saidThisTurn.add(chosen.line)
            turnFillers.incrementAndGet()
            LiveLatency.log(
                LiveLatency.TAG,
                "filler kind=wait at_ms=${System.currentTimeMillis() - eotAtMs} " +
                    "idx=${WaitFillers.LINES.indexOf(chosen.line)} " +
                    "answer_asked=${if (answerAskedAtMs != null) 1 else 0}",
            )
            audit.filler("wait", chosen.line)
            // Half-duplex (Bluetooth): his audio through the line is kept and handed on, as for a backchannel.
            listenThroughClip("wait", WaitFillers.LINES.indexOf(chosen.line))
            clipPlayback.during { playPacedWait(p, pcm, b.tts.sampleRate) }
        }
        // The Bluetooth tail settles before his audio counts again; the mark clears even if cut.
        clipPlayback.during { delay(MicReopen.settleMs(CallAudioMode.route(audioManager))) }
        lastFillerEndAtMs = System.currentTimeMillis()
        return WaitPlay.PLAYED
    }

    /**
     * A wait line's audio, a slice at a time ([PacedClip]), cut between slices once the answer's audio
     * is in hand and more than [DeadAir.FINISH_MAX_MS] of the line is left. Never a flush: the answer's
     * writes carry the generation its speaker was made with.
     */
    private suspend fun playPacedWait(p: ReplyPlayer, pcm: ByteArray, sampleRate: Int) {
        val sound = turnGap?.started(TurnGap.WAIT, System.currentTimeMillis())
        logFirstSound()
        val gen = p.generation()
        val end = PacedClip.play(
            pcm,
            sampleRate,
            write = { slice -> withContext(Dispatchers.IO) { p.write(slice, gen) } },
            aheadMs = { p.aheadMs() },
            cut = { left -> DeadAir.cutPlaying(answerAudioStarted, left) },
        )
        if (end == PacedClip.End.WHOLE) p.awaitDrained()
        if (end == PacedClip.End.CUT) LiveLatency.log(LiveLatency.TAG, "filler_cut kind=wait")
        sound?.let { turnGap?.ended(it, System.currentTimeMillis() + p.aheadMs()) }
    }

    /**
     * The checks a due backchannel passes before a clip is chosen, each skip logged with its reason.
     * Returns the "still due" check to repeat under [playGate], or null when the clip is skipped.
     */
    @Suppress("ReturnCount") // one worded skip per reason
    private suspend fun backchannelStillDue(seq: Int, eotAtMs: Long): (() -> Boolean)? {
        fun skip(reason: String): (() -> Boolean)? {
            LiveLatency.log(LiveLatency.TAG, "backchannel_skip reason=$reason")
            turnGap?.noteSkip(reason)
            return null
        }
        // The open's chime or cue still playing: wait for its end if the clip can still start in time.
        val cueWait = Backchannel.cueWaitMs(System.currentTimeMillis(), eotAtMs, openingCueEndsAtMs)
            ?: return skip("over_cue")
        if (cueWait > 0) delay(cueWait)
        // The cue counts as audible (onReplyAudible) until its job ends; past its end it is not.
        val cueOver = { openingCueEndsAtMs?.let { System.currentTimeMillis() >= it } ?: false }
        val due = {
            !closing.get() &&
                Backchannel.stillDue(turnAudioStarted, speaking && !cueOver(), seq == backchannelSeq.get())
        }
        if (!due()) return skip("answer_first")
        // The answer's text being in is no reason for silence (Kevin, 2026-11-20: a filler every
        // time); only its audio having started is. The answer then waits for the clip (<= its length).
        // His turn already read as noise or nothing (no model call happens): nothing to acknowledge.
        // A transcript that comes after the clip's time cannot stop it; that turn still gets one.
        if (backchannelUtt != null && backchannelUtt === noiseUtt) return skip("noise")
        return due
    }

    /**
     * The answer's text is already in: its first audio is a voice request away, so a clip now would
     * only hold it up (there is no earlier "first audio is due" signal to key on).
     */
    private fun answerTextIn(): Boolean = turn?.let { it.fromUser && it.reply.isNotEmpty() } == true

    /** Half-duplex (only with its setting): his audio is kept through the clip, never dropped ([ClipListen]). */
    private fun listenThroughClip(kind: String, idx: Int) {
        if (turnTaker?.bargeIn == true) return
        synchronized(turnLock) {
            clipListen = ClipListen(backchannelUtt?.keptAudio() ?: ByteArray(0))
            clipListenKind = kind
            clipListenIdx = idx
        }
    }

    /** A task line never starts within [Backchannel.SPACING_MS] of a backchannel's end (no stacking). */
    private suspend fun awaitBackchannelSpacing() {
        fillerJobs.awaitBackchannel()
        delay(Backchannel.spacingWaitMs(System.currentTimeMillis(), backchannelEndAtMs))
    }

    @Suppress("LongMethod", "ReturnCount", "CyclomaticComplexMethod") // one exit per way a turn ends
    private suspend fun runTurn(u: TurnUtterance, eotAtMs: Long) {
        // An engine turn cancelled after its early go (a stop or goodbye over it, round 4): never said.
        if (u.engine?.cancelled == true) return
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
                // The engine could not answer: said so, never a second brain ([EngineUtterance.SWITCHED_OFF]
                // is the server's own rollback, and the phone's transcriber and loop take that turn).
                val lost = u.engine?.takeIf { stt.reason != EngineUtterance.SWITCHED_OFF }
                if (lost != null) return engineFailed(lost, stt.reason == EngineUtterance.MISSED)
                val recovered = recoverWords(u, stt.reason) ?: return
                val recoveredStop = endsConversation(recovered)
                noteGoodbye(recovered, recoveredStop, recoveredStop, null, kept = true)
                if (recoveredStop) return endByStop(u, recovered)
                recovered
            }
            is SttResult.Text -> {
                // "Stop", "goodbye": before every noise and length guard, and on his latest words alone,
                // so a stop said during a lookup ends the conversation, never restarts the lookup.
                // A stop over no voice (a burst read as "Bye.") is not a stop; the guard below decides it.
                // Either way a stop phrase never restarts the request it interrupted: that is dropped.
                val stopWords = endsConversation(stt.text)
                val stop = stopWords && SttGuard.stopVoiced(stt.text, stt.voicedMs)
                if (stopWords && !stop) {
                    discardDraftOf(u, "not_answered")
                    carry = ""
                }
                val v = verdictOf(stt)
                // Every turn, numbers and the decision only: real car audio tunes the thresholds later.
                LiveLatency.log(
                    LiveLatency.TAG,
                    "stt_voiced ms=${stt.voicedMs ?: -1} audio_ms=${stt.audioMs ?: -1} decision=" +
                        if (stop) "stop" else (v as? SttGuard.Verdict.Drop)?.reason ?: "keep",
                )
                // The words the guard KEPT, never the ones it removed: a Keep still strips every word
                // in a script none of his languages use ([SttGuard.verdict]), and the raw text would
                // have put that stripped word in the log, which is the one thing this line must not do.
                noteGoodbye(
                    (v as? SttGuard.Verdict.Keep)?.text ?: stt.text,
                    stopWords, stop, stt.voicedMs, v is SttGuard.Verdict.Keep,
                )
                if (stop) return endByStop(u, stt.text)
                when (v) {
                    is SttGuard.Verdict.Keep -> v.text
                    is SttGuard.Verdict.Drop -> {
                        // Not plausibly his English (2026-11-17: Spanish and Chinese fragments, one acted
                        // on): never sent to the model. Counts only.
                        LiveLatency.log(
                            LiveLatency.TAG,
                            "stt_rejected reason=${v.reason} chars=${stt.text.length} audio_ms=${stt.audioMs ?: -1} " +
                                "voiced_ms=${stt.voicedMs ?: -1}",
                        )
                        if (!droppedWords(u, v.reason)) return
                        ""
                    }
                }
            }
        }
        val heard = (carry + " " + words).trim()
        if (heard.isNotBlank()) {
            missedInARow = 0
            noiseInARow = 0
        }
        if (heard.isBlank()) {
            noiseUtt = u
            discardDraftOf(u, "nothing_heard")
            if (!closedOnNoise()) backToListening()
            return
        }
        carry = ""
        userTurns.incrementAndGet()
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
        if (!followLanguage(heard)) {
            discardDraftOf(u, "not_answered")
            return
        }
        val record = TurnRecord(heard, voicedMs = (stt as? SttResult.Text)?.voicedMs)
        // Words carried from a dropped turn are not in this audio: the engine gets them as a transcript.
        record.engineUtt = u.engine?.takeIf { heard == words.trim() }
        record.engineHeard = u.engine != null
        record.awaits = u.awaits
        if (record.engineUtt == null) u.engine?.release()
        answerTurn(heard, eotAtMs, record, takeDraft(u, heard))
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
        if (record.fromUser) record.askedBefore = lastTurnAsked
        // The day the last turn was about is this turn's to carry, and no later turn's (DateCarry).
        if (record.fromUser) conversationDay.startTurn()
        // A calendar-entry remove question is answerable in his next turn only (EventRemoval).
        if (record.fromUser) EventRemoval.turnStarted()
        // ADR 0090 gap 5: the turn after a read-through opener starts marked, as on Live.
        if (readThroughCarry.startTurn(record.fromUser, record.startMarked)) {
            record.startMarked = true
            record.readThrough = true
            audit.readThrough(GeminiLiveSession.READ_THROUGH_CARRY)
        }
        val seq = turnSeq.getAndIncrement()
        val brain = engineBrain
        val engineStart = brain?.route(
            record.engineUtt,
            heard,
            EngineBrain.RouteFacts(
                test = test != null,
                fromUser = record.fromUser,
                // Streamed audio, a merged turn's words, or the laptop voice's words may go to the engine.
                engineHeard = record.engineHeard || backend?.kind == SpeechBackend.Kind.LAPTOP,
                awaits = record.awaits,
            ),
        )
        // The engine answers without the phone's prompt; only the phone loop waits for it.
        val pfx = if (engineStart != null) null else try {
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
                ?: engineStart?.let {
                    brain?.answer(it, record.engineUtt, executorFor(record, seq), listenerFor(record, reply))
                }
                ?: textLoop!!.answer(
                    checkNotNull(pfx),
                    turnContentsFor(heard),
                    executorFor(record, seq),
                    listenerFor(record, reply),
                )
            if (brain?.takeCrisis() == true) {
                reply.interrupt()
                shutdown("crisis")
                host.crisis()
                return
            }
            LiveLatency.log(LiveLatency.TAG, CascadeLines.usage(MODEL, result.usage))
            // A tool turn that ended on filler was asked once more ([FillerTurn]): counts only.
            if (result.fillerContinued > 0) LiveLatency.log(LiveLatency.TAG, "reply_filler_continued n=1")
            if (result.fillerAgain) LiveLatency.log(LiveLatency.TAG, "reply_filler_again n=1")
            // A balance said by the phone itself (VerbatimSpeech); model money sentences dropped after it.
            if (result.verbatimSpoken) LiveLatency.log(LiveLatency.TAG, "reply_verbatim n=1")
            if (result.moneyDropped > 0) {
                LiveLatency.log(LiveLatency.TAG, "reply_money_dropped n=${result.moneyDropped}")
            }
            if (result.diagDropped > 0) LiveLatency.log(LiveLatency.TAG, "reply_diag_dropped n=${result.diagDropped}")
            // Model sentences that named what code had just confirmed (ruling A): counts only.
            if (result.saidDropped > 0) LiveLatency.log(LiveLatency.TAG, "reply_said_dropped n=${result.saidDropped}")
            if (result.closingDropped) LiveLatency.log(LiveLatency.TAG, "reply_closing_dropped n=1")
            if (result.trailingFillerDropped > 0) {
                LiveLatency.log(LiveLatency.TAG, "reply_filler_trailing_dropped n=${result.trailingFillerDropped}")
            }
            if (record.holdLine) {
                val toolCalled = synchronized(record) { record.toolsRan.isNotEmpty() }
                val why = CheckInRules.lineRejection(result.text, result.failure != null, toolCalled)
                if (why != null) {
                    reply.interrupt()
                    dropCheckIn(why, result.failure?.name)
                    return
                }
            }
            // Filler twice after a tool: the failure line is said even after words already heard.
            // After a write code said, a failure is said too: the line is not the model's answer.
            val sayFailure = !reply.anyText || result.fillerAgain || result.codeLines > 0
            val text = if (!record.holdLine && result.failure != null && sayFailure) {
                result.failureWords ?: result.failure.lead
            } else {
                null
            }
            // A background line nobody asked for does not announce a fault (Settings says why).
            when {
                // The check-in line passed: said whole, as written.
                record.holdLine -> reply.say(result.text.trim())
                text != null && !quiet && reply.anyText -> {
                    reply.text(" $text")
                    reply.finishText()
                }
                text != null && !quiet -> reply.say(text)
                else -> reply.finishText()
            }
            outcome = reply.await()
            val failed = outcome as? ReplySpeaker.Outcome.Failed
            if (failed != null) {
                // The model and the tools already ran on the phone: only the voice was lost. A laptop
                // reply goes again, whole, on the cloud voice; nothing is asked of the model twice.
                // A cloud voice that fails (two tries, no audio): the answer and what was done reach
                // the panel and the record, he hears [sayVoiceLost], and the window opens again.
                // What played is heard; a write line that did not play is owed, so the re-voice says it
                // first ([OwedWrites.open]) and the words below never repeat it (review F1).
                owe(record, cutOff = false)
                val words = text ?: LeadingFiller.strip(modelText(record))
                val unsaid = {
                    // Nothing more can be said: the owed line goes to the record and the panel.
                    val line = owed.writeDown()?.also { host.notice(it) }
                    val kept = listOfNotNull(line, words.ifBlank { null }).joinToString(" ")
                    val keptWords = VerbatimSpeech.redact(withWrites(kept, record), record.privateSpoken)
                    recordTurn(heard, keptWords, record.readThrough, seq, record.fromUser)
                }
                val again = if (voice.kind == SpeechBackend.Kind.CLOUD) {
                    null
                } else {
                    revoice(voice, failed.reason, heardMoment(heard, record), words, unsaid) ?: return
                }
                outcome = again?.also { reply = it }?.await() ?: failed
                if (outcome is ReplySpeaker.Outcome.Failed) {
                    // Known limit (accepted 2026-11-19): the voice is lost, so a write's line is on the
                    // panel and in the record, not spoken.
                    unsaid()
                    answeredOnce = true
                    turnsAnswered.incrementAndGet()
                    sayVoiceLost(outcome.reason)
                    return
                }
            }
            // Without the model's own leading "Checking.", as the speaker dropped it ([LeadingFiller]).
            val said = reply.spoken().ifBlank { LeadingFiller.strip(result.text) }
            val kept = if (outcome == ReplySpeaker.Outcome.Interrupted) withWrites(reply.spoken(), record) else said
            val keptWords = VerbatimSpeech.redact(kept, record.privateSpoken)
            recordTurn(heard, keptWords, record.readThrough, seq, record.fromUser)
            owe(record, cutOff = outcome != ReplySpeaker.Outcome.Played)
            answeredOnce = true
            turnsAnswered.incrementAndGet()
        } catch (e: CancellationException) {
            // Interrupted (tap, barge-in) or merged into the next turn: keep only what was heard.
            withContext(NonCancellable) {
                reply.interrupt()
                // An engine reply cut off: the engine is told how much he heard, on its next turn.
                engineBrain?.context?.cutOff(reply.spoken().length)
                // A write that finished and was not heard is said at the next thing said ([owed]).
                owe(record, cutOff = true)
                if (!record.merged && (carry.isBlank() || record.toolsRan.isNotEmpty())) {
                    val spoken = VerbatimSpeech.redact(withWrites(reply.spoken(), record), record.privateSpoken)
                    recordTurn(heard, spoken, record.readThrough, seq, record.fromUser)
                }
            }
            throw e
        } finally {
            if (turn === record) turn = null
            if (record.fromUser && !record.merged) lastTurnAsked = record.asked
        }
        if (outcome != ReplySpeaker.Outcome.Played) return
        if (checkInStep == CheckInStep.LINE && !record.fromUser) {
            afterCheckInLine(TtsTags.strip(record.reply.toString()))
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
        // The mic opens only now that the line passed and played; the cue plays while the reply still
        // counts as playback, so the mic never takes it as him.
        if (listens && micJob == null) startMic()
        val p = player
        if (p != null && backend?.tts?.sampleRate == ListenCue.SAMPLE_RATE) {
            p.awaitDrained()
            val gen = p.generation()
            withContext(Dispatchers.IO) { p.write(p.startable(ListenCue.earconPcm()), gen) }
        }
        if (checkInStep == CheckInStep.LINE) checkInStep = CheckInStep.LISTENING
        LiveLatency.log("LIVE_FOLLOWUP", "check_in listening")
        afterReply()
        if (checkInStep == CheckInStep.LISTENING && !closing.get()) {
            host.phase(Phase.LISTENING, "Listening for your answer...")
        }
    }

    /**
     * The check-in's line failed or is not a check-in line: nothing is said, nothing is shown, the
     * mic never opened, and the conversation ends. No outcome is reported, so the ask is not counted
     * and the topic comes back under the retry rule ([CheckInMoments], [CheckInRules.RETRY_GAP_MS]).
     */
    private suspend fun dropCheckIn(reason: String, failure: String?) {
        checkInStep = CheckInStep.DONE
        val mark = opener?.checkIn
        LiveLatency.log(
            "LIVE_FOLLOWUP",
            "check_in dropped reason=$reason" + (failure?.let { " failure=$it" } ?: "") +
                (mark?.let { " moment=${it.moment.name.lowercase()}" } ?: ""),
        )
        speaking = false
        playbackActive = false
        shutdown("check_in_dropped")
        host.closed("check_in_dropped")
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
        reply.say(ClosingLines.next(ctx), tagsAllowed = true)
        val o = withTimeoutOrNull(CLOSER_MAX_MS) { reply.await() }
        if (o == ReplySpeaker.Outcome.Played) player?.awaitDrained()
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
                return cloudMissed(u, reason, moment)
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
                // The recovered transcript is believed only as much as a first one ([SttGuard]).
                when (val v = verdictOf(r)) {
                    is SttGuard.Verdict.Keep -> v.text
                    is SttGuard.Verdict.Drop -> if (droppedWords(u, v.reason)) "" else null
                }
            }
            is SttResult.Failed -> {
                if (r.reason != "cancelled") missedWords(moment, r.reason, b.kind)
                null
            }
        }
    }

    /**
     * The cloud transcript did not come back ([MissedLine], 2026-11-19): silence closes quietly, voiced
     * audio is transcribed once more, and only then is the missed line said. Null when the turn ends here.
     */
    @Suppress("ReturnCount") // one exit per way the turn ends: silent, said, retried
    private suspend fun cloudMissed(u: TurnUtterance, reason: String, moment: Handover.Moment): String? {
        val audio = u.keptAudio()?.takeIf { it.isNotEmpty() }
        val voiced = audio?.let { SttGuard.voicedMs(it) }
        when (MissedLine.onFailure(voiced, retried = false)) {
            MissedLine.OnFailure.SILENT -> {
                LiveLatency.log(LiveLatency.TAG, "stt_missed_silent reason=$reason voiced_ms=$voiced")
                if (!closedOnNoise()) {
                    lastClipText = null
                    backToListening()
                }
                return null
            }
            MissedLine.OnFailure.SAY -> {
                missedWords(moment, reason, u.backend.kind)
                return null
            }
            MissedLine.OnFailure.RETRY -> Unit
        }
        val b = backend ?: return null
        val pcm = audio ?: return null
        val started = System.currentTimeMillis()
        val again = b.stt.begin()
        again.audio(pcm, 0, pcm.size)
        again.end()
        return when (val r = again.result(b.sttFinalTimeoutMs)) {
            is SttResult.Text -> {
                LiveLatency.log(
                    LiveLatency.TAG,
                    "stt_recovered backend=${b.kind.token} ms=${System.currentTimeMillis() - started} from=$reason",
                )
                when (val v = verdictOf(r)) {
                    is SttGuard.Verdict.Keep -> v.text
                    is SttGuard.Verdict.Drop -> if (droppedWords(u, v.reason)) "" else null
                }
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
    @Suppress("ReturnCount") // one exit per way the line is not said
    private suspend fun missedWords(
        moment: Handover.Moment,
        reason: String,
        from: SpeechBackend.Kind,
        /** A transcript was heard but not believed ([SttGuard]): never a hand-over, always the line. */
        spokenFallback: Boolean = false,
    ) {
        if (!VoiceEngineSettings.LIVE_RETIRED && !spokenFallback) {
            handOver(moment, "stt", reason, from = from)
            return
        }
        // Never over his ongoing speech, nor on top of a reply that just ended (2026-11-19).
        val hold = MissedLine.hold(
            speakingNow = currentUtt != null,
            msSinceReplyEnd = lastReplyEndAtMs.takeIf { it > 0 }?.let { System.currentTimeMillis() - it },
        )
        if (hold != MissedLine.Hold.NONE) {
            LiveLatency.log(LiveLatency.TAG, "stt_missed_held backend=${from.token} reason=$reason why=${hold.token}")
            if (currentUtt == null) backToListening()
            return
        }
        LiveLatency.log(LiveLatency.TAG, "stt_missed backend=${from.token} reason=$reason")
        VoiceEngineSettings.noteStatus(ctx, "${VoiceEngineSettings.CLOUD_WORDS} missed a sentence (stt_$reason)")
        host.notice(MISSED_NOTICE)
        // Two in a row (steady noise, a sentence that keeps failing): close rather than loop, each
        // round paying for a transcription and saying the line again (2026-11-17 review).
        if (++missedInARow > MAX_MISSED_IN_A_ROW) {
            LiveLatency.log(LiveLatency.TAG, "stt_missed_close n=$missedInARow")
            sayOwed()
            shutdown("missed_twice")
            host.closed("missed_twice")
            return
        }
        // 2026-11-17: twice he spoke, heard nothing back, and the window closed on him. He hears that
        // it was missed, and the follow-up window opens in full (a question's, longer) after the line.
        sayMissedLine()
    }

    /**
     * His latest words are a stop ([WakePhrases.isSleepPhrase]); while an answer is awaited (either
     * signal, as in [awaitingAnswer]) only a hard stop, so "never mind" as an answer goes to the
     * model while "that's it" closes (Kevin, 2026-11-23, Q8). While anything rings, "stop the alarm" and the like
     * are a stop too ([RingControl.voiceStops]), and [endByStop] stops the ring.
     */
    private fun endsConversation(words: String): Boolean {
        return WakePhrases.isSleepPhrase(
            words,
            CompanionProfile.name(ctx),
            afterQuestion = FollowUpWindow.isQuestion(lastClipText),
            draftPending = draftPending(),
        ) || RingControl.voiceStops(words, CompanionProfile.name(ctx))
    }

    /**
     * His last turn asked him something his next word can answer ([lastTurnAsked], out of
     * [LoneWordWriteGuard.asks]), or the turn in hand did. A WRITE may be waiting on his yes
     * (a needs_confirmation, a draft_id, the first step of a two-step write), and often none is:
     * `needs_answer` covers a question asked BECAUSE nothing was written (a list add with a blank
     * item, a car write that named no item, a remove that matched nothing, the adapter's "which car
     * is this?"), so the name says more than the signal does.
     *
     * Either way the turn is weighed as one after a question ([WakePhrases.isSleepPhrase]), because a
     * read-back need not end in "?". What a close actually drops is named at [release] and
     * [releaseNow]: the asks that belong to this conversation and nothing else
     * ([DtcClearController.forgetClearQuestion], [EventRemoval.reset], `EventMove.reset`). Those have
     * to be made again from scratch. A close is not what throws a mail draft away: it is saved in his
     * Gmail the moment it is drafted (`EmailToolbox.drafted`).
     */
    private fun draftPending(): Boolean {
        return turn?.asked == true || lastTurnAsked
    }

    /**
     * Alfred is waiting for an answer, so a soft dismissal may be it and goes to the model instead of
     * closing in code.
     *
     * TWO signals, not one (blind review of 56e9404e, finding 4): his last spoken clip ended in a
     * question mark ([FollowUpWindow.isQuestion]), or something he can answer is pending
     * ([draftPending]). The second was missing and is the better one: a read-back need not end in
     * "?", and without it a turn like "yes, that's it" closed in code before the model and before the
     * write guard - the exact harm the 2026-11-18 review raised F1 for.
     *
     * This is the `LIVE_AUDIT` line's one field for it. The decision reads the same two signals and
     * answers them the same way ([WakePhrases.isSleepPhrase] takes one parameter each, one rule over
     * both), so the field and the decision cannot disagree.
     */
    private fun awaitingAnswer(): Boolean {
        return FollowUpWindow.isQuestion(lastClipText) || draftPending()
    }

    /**
     * Every whole-turn stop decision goes to `LIVE_AUDIT` ([LiveAudit.goodbye]), matched or not, so the
     * next conversation that does not close says in the log why (2026-11-23, goodbye hardening). Only whole turns:
     * a running partial is weighed by the backchannel rule many times a turn and its text is in no log.
     */
    private fun noteGoodbye(words: String, matched: Boolean, honoured: Boolean, voicedMs: Long?, kept: Boolean) {
        if (words.isBlank()) return
        // Nothing the guard threw away and nothing a stop rule recognised: a count, as before. A
        // transcript it dropped is noise or another language, and its words stay out of every log.
        if (!kept && !matched) return
        audit.goodbye(
            Farewells.normalise(words),
            matched = matched,
            afterQuestion = awaitingAnswer(),
            voicedMs = voicedMs,
            honoured = honoured,
        )
    }

    /**
     * A stop: nothing waiting is answered, the conversation ends with the tone. A write the stopped turn
     * already finished is said first ([endByVoice] says what is [owed]; 2026-11-18 review B3: it was
     * done and never said).
     */
    private suspend fun endByStop(u: TurnUtterance, words: String) {
        if (RingControl.voiceStops(words, CompanionProfile.name(ctx))) RingControl.stopAll(ctx, RingStopReason.VOICE)
        // No "Mm." before "Goodbye." (review F4): the acknowledgement, which now starts at end of turn,
        // is dropped, and cut now if it is playing, before the farewell is queued ([cutAck]).
        turnEndsConversation = true
        cutAck()
        discardDraftOf(u, "not_answered")
        carry = ""
        recordTurn(words, owed.due()?.line.orEmpty())
        endByVoice()
    }

    /**
     * The engine, this conversation's brain, could not answer [utt] (client contract section 9): no
     * second brain answers. Its words are said, true as said ([EngineBrain.lostWords]); he may say it
     * again. After [EngineBrainRules.FAILURE_BUDGET] such turns in a row the conversation closes (K3).
     * A transcript the engine missed ([missed]) is the missed line.
     */
    private suspend fun engineFailed(utt: EngineUtterance, missed: Boolean) {
        val close = engineBrain?.failed(missed) == true
        val words = if (missed) MISSED_LINE else EngineBrain.lostWords(utt)
        LiveLatency.log(LiveLatency.TAG, "engine_lost close=${if (close) 1 else 0} missed=${if (missed) 1 else 0}")
        if (close) engineLost(words) else sayMissedLine(words)
    }

    /** K3: [words] are said, shown, and the conversation closes. */
    private suspend fun engineLost(words: String) {
        if (!closing.get() && backend != null && player != null) {
            val reply = newSpeaker(null)
            reply.say(words)
            val played = withTimeoutOrNull(CLOSER_MAX_MS) { reply.await() } == ReplySpeaker.Outcome.Played
            if (played) player?.awaitDrained()
        }
        host.notice(words)
        shutdown("engine_unreachable")
        host.closed("engine_unreachable")
    }

    /** [record]'s writes that went through and were not heard are owed; a cut-off turn's unconfirmed ones too. */
    private fun owe(record: TurnRecord, cutOff: Boolean) = owed.endTurn(record.writes, cutOff)

    /** The model's own words of [record]'s reply, without code's lines (those are owed, not words). */
    private fun modelText(record: TurnRecord): String = TtsTags.strip(record.reply.toString()).trim()

    /**
     * Nothing more will be said (a close, a hand-over): what is owed goes to the record and the
     * panel ([OwedWrites.writeDown]), never silently nowhere (review F5).
     */
    private fun writeDownOwed() {
        val line = owed.writeDown() ?: return
        host.notice(line)
        recordTurn("", line, fromUser = false)
    }

    /**
     * What is [owed], said now, before a close that would otherwise say nothing more. Every other
     * thing said goes through [newSpeaker], which says it first; this is for the closes.
     */
    private suspend fun sayOwed() {
        if (owed.isEmpty() || backend == null || player == null) return
        val reply = newSpeaker(null)
        reply.finishText()
        if (reply.await() == ReplySpeaker.Outcome.Played) player?.awaitDrained()
    }

    private val farewellIndex = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * His farewell closes the conversation with a short line of code's own ([Farewells.SPOKEN],
     * 2026-11-20): from memory only, at most [CLOSER_MAX_MS]; not in memory, it is skipped and logged.
     */
    @Suppress("ReturnCount") // no voice, no player, not in memory
    private suspend fun sayFarewell() {
        val b = backend ?: return
        if (player == null) return
        val line = Farewells.SPOKEN[Math.floorMod(farewellIndex.getAndIncrement(), Farewells.SPOKEN.size)]
        if (!b.tts.inHand(SpokenText.forSpeech(line))) {
            LiveLatency.log(LiveLatency.TAG, "farewell_skip reason=not_in_memory")
            return
        }
        val reply = newSpeaker(null)
        reply.say(line)
        if (withTimeoutOrNull(CLOSER_MAX_MS) { reply.await() } == ReplySpeaker.Outcome.Played) player?.awaitDrained()
    }

    /** One more silent turn (noise, or no voice at all); the [MAX_NOISE_IN_A_ROW]th closes with the tone. */
    private suspend fun closedOnNoise(): Boolean {
        if (++noiseInARow < MAX_NOISE_IN_A_ROW) return false
        LiveLatency.log(LiveLatency.TAG, "stt_noise_close n=$noiseInARow")
        sayOwed()
        shutdown("noise")
        host.closed("noise")
        return true
    }

    /** Whether [stt]'s words are believed ([SttGuard]), for his languages and the names on the phone. */
    /**
     * His turn ended while the last one still plays (barge-in off: Bluetooth, a call). As soon as his
     * words are in, the same checks and route as [runTurn] decide: the engine's go now (its stream
     * waits, unread, until this turn is reached), or a take. While a tool of the last turn still runs
     * (it may leave state his words answer) this waits, at most [EARLY_WAIT_MS]; still running then,
     * the phone takes the turn. Words carried from a merged turn are the phone's too.
     */
    private suspend fun decideEarly(u: TurnUtterance, e: EngineUtterance) {
        val brain = engineBrain ?: return
        val stt = e.result(0) as? SttResult.Text
        withTimeoutOrNull(EARLY_WAIT_MS) { while (toolsInFlight.get() > 0) delay(EARLY_POLL_MS) }
        val words = stt?.text.orEmpty()
        val checkIn = checkInStep == CheckInStep.LINE || checkInStep == CheckInStep.LISTENING
        val asked = turn?.asked == true || lastTurnAsked
        val awaits = EngineBrainRules.phoneAwaits(checkIn, asked, brain.context.mailExchange)
        val facts = EngineBrainRules.Facts(test != null, true, true, brain.state == EngineBrain.State.ON, awaits)
        val unsettled = toolsInFlight.get() > 0 || carry.isNotBlank()
        val phoneOnly = unsettled || stt == null || phoneOnlyNow(stt)
        // A stop or goodbye said over a reply: every turn whose go went early is cancelled too.
        if (endsConversation(words)) brain.earlyTurns.forEach { it.release() }
        if (phoneOnly || EngineBrainRules.phoneKeeps(words, facts) != null) e.release() else e.goNow()
        LiveLatency.log(LiveLatency.TAG, "engine_early go=${if (e.goPosted) 1 else 0} for=u${u.id}")
    }

    /** [runTurn]'s own word checks, before any routing: nothing heard, stop, crisis, the guard, language. */
    private fun phoneOnlyNow(stt: SttResult.Text): Boolean {
        val words = stt.text
        val cloud = backend?.kind == SpeechBackend.Kind.CLOUD
        val language = SpokenLanguages.decide(words, languages, conversationLanguage, cloud, CompanionProfile.name(ctx))
        return when {
            words.isBlank() || endsConversation(words) || CrisisDetector.detect(words) -> true
            verdictOf(stt) is SttGuard.Verdict.Drop -> true
            else -> language != SpokenLanguages.Decision.Stay
        }
    }

    private fun verdictOf(stt: SttResult.Text): SttGuard.Verdict =
        SttGuard.verdict(stt.text, stt.voicedMs, SttHintStore.known, languages)

    /**
     * A transcript was not believed. With words still waiting from before (a request he made, then a
     * bump during the lookup), those words are answered: true, and the turn goes on with them. With
     * nothing waiting: silence for noise, the spoken miss otherwise; false, the turn ends here.
     */
    private suspend fun droppedWords(u: TurnUtterance, reason: String): Boolean {
        discardDraftOf(u, "stt_rejected")
        if (carry.isNotBlank()) return true
        if (reason in SttGuard.SILENT_REASONS) {
            // Nothing real was said (noise, an echo): silence. No line, no filler; the follow-up
            // window starts over in full, as after a statement. Steady noise that keeps opening turns
            // closes the conversation rather than keep the mic open and the transcriber billed.
            if (!closedOnNoise()) {
                lastClipText = null
                backToListening()
            }
        } else {
            missedWords(Handover.Moment.LostWords, reason, u.backend.kind, spokenFallback = true)
        }
        return false
    }

    /**
     * The conversation's language after [heard] ([SpokenLanguages]): a whole-turn request for one of his
     * languages, or a sentence spoken in one in its own non-Latin script, switches it; a request for one
     * not in his setting gets the scripted line and the turn ends there (false). The laptop voice
     * speaks English only: it says so and stays English, and the turn is answered in English.
     */
    private suspend fun followLanguage(heard: String): Boolean {
        val cloud = backend?.kind == SpeechBackend.Kind.CLOUD
        val name = CompanionProfile.name(ctx)
        return when (val d = SpokenLanguages.decide(heard, languages, conversationLanguage, cloud, name)) {
            SpokenLanguages.Decision.Stay -> true
            is SpokenLanguages.Decision.Refuse -> {
                LiveLatency.log(LiveLatency.TAG, "language_refused ${d.why}")
                sayMissedLine(d.line)
                false
            }
            is SpokenLanguages.Decision.To -> {
                conversationLanguage = d.language
                engineBrain?.context?.language = d.language.name
                SttHintStore.instruction = SpokenLanguages.sttInstruction(languages, d.language)
                LiveLatency.log(LiveLatency.TAG, "language_switch to=${d.language.name.lowercase().replace(' ', '_')}")
                true
            }
        }
    }

    /** [line] (default [MISSED_LINE]) out loud, then listening again as after any reply. */
    private suspend fun sayMissedLine(line: String = MISSED_LINE) {
        if (closing.get() || backend == null || player == null) {
            backToListening()
            return
        }
        val reply = newSpeaker(null)
        reply.say(line)
        when (reply.await()) {
            ReplySpeaker.Outcome.Played -> afterReply()
            ReplySpeaker.Outcome.Interrupted -> Unit
            is ReplySpeaker.Outcome.Failed -> backToListening()
        }
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

    // The date, the place and this conversation's context ride in the first user turn.
    private fun contentsFor(heard: String) = CascadePrompt.contents(history, heard, preamble)

    // His turns (and their drafts) also carry today's agenda; the greeting does not.
    private fun turnContentsFor(heard: String) = CascadePrompt.contents(history, heard, turnPreamble())

    /**
     * His one word is the answer to the which-car question code spoke on connect ([SpokenWhichCarAsk]):
     * only for adapter_in, and only as that function decides (this connection, in time, one car).
     */
    private suspend fun answersSpokenAsk(name: String, args: JSONObject, heard: String): Boolean {
        val r = LiveSurface.resolve(name, args)
        val adapterIn = r.name == "manage_vehicle" && r.args.optString("action") == "adapter"
        val word = LoneWordWriteGuard.loneWord(heard, CompanionProfile.name(ctx))
        return adapterIn && word != null && com.kevin.legion.vehicle.SpokenWhichCarAsk.answers(
            com.kevin.legion.vehicle.DongleCarController.connectionKey(), word, r.args.optString("vehicle"),
            com.kevin.legion.vehicle.DongleCarController.labels(ctx).values, System.currentTimeMillis(),
        )
    }

    /**
     * A write may start in [record]: under [turnLock], the lock a resume takes over a turn with, so the
     * two never both happen. False when a resume claimed the turn (its merged turn runs the write once).
     */
    private fun claimWrite(record: TurnRecord, name: String): Boolean {
        val mayRun = synchronized(turnLock) {
            if (record.resumeClaimed) {
                false
            } else {
                record.writeCalled = true
                true
            }
        }
        if (!mayRun) LiveLatency.log("LIVE_TURN", "write_superseded tool=$name")
        return mayRun
    }

    private fun executorFor(record: TurnRecord, seq: Long) = TextToolExecutor { name, args, readThrough ->
        // A check-in line calls no tool (its prompt says so); one asked for never runs, and the line drops.
        if (record.holdLine) {
            LiveLatency.log(LiveLatency.TAG, "check_in tool_refused tool=$name")
            return@TextToolExecutor JSONObject().put("success", false).put("message", "No tool for this line.")
        }
        // Before anything can run: his next words must never carry a write into a second run. Decided
        // with the resume's takeover under one lock (review of 206a8898); the write itself runs outside it.
        if (CascadeRules.isWrite(name, args) && !claimWrite(record, name)) {
            return@TextToolExecutor JSONObject().put("success", false)
                .put("message", "Superseded by his next words: not run. Say nothing about it.")
        }
        // One heard word changes nothing without his yes (2026-11-19, lone_word_write).
        val held = if (record.fromUser) {
            val asked = record.askedBefore || answersSpokenAsk(name, args, record.heard)
            LoneWordWriteGuard.refusal(name, args, record.heard, CompanionProfile.name(ctx), asked)
        } else {
            null
        }
        val id = "c${toolSeq.incrementAndGet()}"
        // Logged under the called name (the eval scorer reads `day/read_calendar`); LiveAudit judges
        // redaction on the tool it stands for, so a folded mail or brief result is redacted.
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
                // The turn's date facts ride in the call's context to the date fix ([RelativeDay.corrected]).
                // A lone word's write is held before it reaches the controller ([LoneWordWriteGuard]).
                else -> held ?: runDated(record, name, args, readThrough, seq)
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

    /** One tool call with the turn's date facts in its context, then the conversation's day noted. */
    private suspend fun runDated(
        record: TurnRecord,
        name: String,
        args: JSONObject,
        readThrough: Boolean,
        seq: Long,
    ): JSONObject {
        val dates = RelativeDay.TurnDates(promptDay, record.startedAt, record.dateCalls <= 1, conversationDay.day)
        val words = if (record.fromUser) record.heard else ""
        val response = withContext(dates + ClearCodesVoiceGate.HeardVoice(record.voicedMs)) {
            // The loop's flag leaves out this call's own mark; an opener or carry mark stays.
            host.runTool(name, args, readThrough || record.startMarked, words, seq)
        }
        noteAboutDay(name, args, words, dates, response)
        return response
    }

    /** The conversation's day after this call, read from the args it actually ran with. */
    private fun noteAboutDay(
        name: String,
        args: JSONObject,
        words: String,
        dates: RelativeDay.TurnDates,
        response: JSONObject,
    ) {
        val call = LiveSurface.resolve(name, args)
        val ran = DateCarry.resolved(call.name, call.args, words, dates)?.first ?: call.args
        conversationDay.note(call.name, ran, response.optBoolean("success", false), dates.day)
    }

    private fun listenerFor(record: TurnRecord, reply: ReplySpeaker) = object : GeminiTextLoop.Listener {
        override fun onText(delta: String) {
            record.reply.append(delta)
            if (!record.holdLine) reply.text(delta)
        }

        override fun onToolCalls(names: List<String>) {
            synchronized(record) { record.toolsRan.addAll(names) }
        }

        /**
         * A confirmed turn's tools are starting: the holding line says what is really happening
         * ([ToolFillers], 2026-11-17), unless the model already spoke. A slow tool's line at once; a
         * local tool's (or any write's) only if it is still running after [ToolFillers.FAST_DELAY_MS].
         * Never an outcome; only from memory; never the same line twice running; once a turn. A draft
         * only gets here after confirmation ([SpeculativeAnswer] holds this call until then).
         */
        override fun onToolsStarting(calls: List<Pair<String, JSONObject>>) {
            // Before any of this round runs: the date fix only touches a turn's one date-taking call.
            record.dateCalls += calls.count { (n, a) ->
                LiveSurface.resolve(n, a).let { RelativeDay.isDateCall(it.name, it.args) }
            }
            if (!record.fromUser || record.fillerPlayed || reply.anyText) return
            val plan = ToolFillers.plan(calls.map { (n, a) -> ToolFillers.Call(n, a, CascadeRules.isWrite(n, a)) })
                ?: return
            // fillerPlayed is set when a line actually plays ([sayToolFiller]): a fast tool in round 1
            // that never got its line leaves the slow one in round 2 free to say its own.
            scope.launch(loop) {
                if (!plan.slow) delay(ToolFillers.FAST_DELAY_MS)
                awaitBackchannelSpacing()
                val due = ToolFillers.stillDue(
                    slow = plan.slow,
                    sameTurn = turn === record,
                    toolsRunning = toolsInFlight.get() > 0,
                    answerStarted = reply.anyText,
                    played = record.fillerPlayed,
                )
                if (due) sayToolFiller(plan, reply, record)
            }
        }

        override fun onToolResult(name: String, args: JSONObject, response: JSONObject, ms: Long, writeDone: Boolean) {
            VerbatimSpeech.privateWords(response)?.let { record.privateSpoken = it }
            val write = CascadeRules.isWrite(name, args)
            if (write) noteChange(response)
            if (writeDone) synchronized(record) { record.writesDone.add(name) }
            if (LoneWordWriteGuard.asks(name, args, response)) record.asked = true
            LiveLatency.log(LiveLatency.TAG, "tool_ms=$ms tool=$name sent=true async=false")
        }

        override fun onReadThrough(source: String) {
            record.readThrough = true
            audit.readThrough(source)
        }

        override fun onWriteFact(fact: ScriptedLines.WriteFact) {
            record.writes.add(fact)
        }

        // Code's line for a write that went through (2026-11-19): heard only once it played whole.
        override fun onCodeLine(text: String, facts: List<ScriptedLines.WriteFact>) {
            if (!record.holdLine) owed.line(record.writes, reply, text, facts)
        }

        override fun onFirstPart(round: Int, ms: Long) {
            LiveLatency.log(LiveLatency.TAG, "llm_first_ms=$ms round=$round")
        }
    }

    /** [plan]'s line now, from memory only (else its audio is fetched for next time). */
    private fun sayToolFiller(plan: ToolFillers.Plan, reply: ReplySpeaker, record: TurnRecord) {
        val tts = backend?.tts
        val choice = ToolFillers.choose(plan, lastToolFiller, toolFillerIndex.getAndIncrement()) { line ->
            tts?.inHand(SpokenText.forSpeech(line)) == true
        }
        // The tool's own line, not in memory yet: one request, behind any reply ([GeminiTts.warm]).
        choice.fetch?.let { tts?.warm(listOf(SpokenText.forSpeech(it))) }
        val line = choice.say
        if (line == null) {
            LiveLatency.log(LiveLatency.TAG, "filler_skip reason=not_in_memory tool=${plan.key}")
            return
        }
        lastToolFiller = line
        val duplex = if (turnTaker?.bargeIn == true) "full" else "half"
        val timing = if (plan.slow) "start" else "late"
        if (reply.filler(line)) {
            record.fillerPlayed = true
            val generic = if (choice.fetch != null) " generic=true" else ""
            LiveLatency.log(LiveLatency.TAG, "filler tool=${plan.key} at=$timing duplex=$duplex$generic")
        }
    }

    private fun newSpeaker(eotAtMs: Long?): ReplySpeaker {
        val b = backend!!
        val p = player!!
        val gen = p.generation()
        var audible = false
        var answerAudible = false
        var self: ReplySpeaker? = null
        return ReplySpeaker(
            tts = b.tts,
            scope = scope,
            play = { pcm -> playGate.withLock { answerGotGate(); p.write(pcm, gen) } },
            firstFrameTimeoutMs = b.ttsFirstAudioTimeoutMs,
            onFirstAudio = { _, filler ->
                turnAudioStarted = true
                val now = System.currentTimeMillis()
                if (filler) {
                    // Its own line: eot_reply_ms stays the answer's first audio, not the filler's.
                    eotAtMs?.let { LiveLatency.log(LiveLatency.TAG, "filler_first_ms=${now - it}") }
                } else {
                    answerAudible = true
                    answerAudioIn(eotAtMs, now, self?.firstAskedAtMs)
                }
                if (!audible || !filler) {
                    // The answer after a filler the mic reopened for ([onFillerPlayed]): playing again.
                    val first = !audible
                    audible = true
                    scope.launch(loop) { if (first || !speaking) onReplyAudible() }
                }
            },
            onClipStarted = { text ->
                lastClipText = text
                audit.assistant(text, readThrough = turn?.readThrough == true)
                // The voice sheet and the talk strip read the caption, as for Live's subtitles.
                CompanionPhase.setCaption(text.takeLast(CAPTION_CHARS))
            },
            // Count only, never the words.
            onFillerDropped = { LiveLatency.log(LiveLatency.TAG, "reply_filler_dropped n=1") },
            onFillerStarted = { text -> noteTaskFiller(text) },
            // Only a turn of his has a wait to fill ([DeadAir]): its first answer clip asked of the voice.
            onAnswerAsked = { answerAsked(eotAtMs) },
            onFillerPlayed = {
                noteTaskFillerEnded()
                // Half-duplex: the filler is over, the answer is not here yet. Listen again once it
                // has drained (and its Bluetooth tail settled), so he can still add to his turn.
                if (turnTaker?.bargeIn != true) {
                    scope.launch(loop) {
                        p.awaitDrained()
                        delay(MicReopen.settleMs(CallAudioMode.route(audioManager)))
                        val stillWaiting = !answerAudible && speaker === self
                        if (stillWaiting && speaking && !closing.get()) {
                            speaking = false
                            playbackActive = false
                            host.phase(Phase.THINKING, "...")
                            LiveLatency.log(LiveLatency.TAG, "filler_mic_reopen")
                        }
                    }
                }
            },
        ).also {
            self = it
            speaker = it
            // The one place every spoken thing starts: a write he has not heard said goes first
            // ([owed]), and stays owed until its line played to the end.
            owed.open(it)
        }
    }

    /**
     * The answer's first audio is in hand at [now]: no wait line starts from here ([DeadAir]). On a turn
     * of his ([eotAtMs]), the bench's gap line ([TurnGap]) and this session's ask-to-audio time for the
     * next turn's estimate ([DeadAir.expectedFirstAudioMs]).
     */
    private fun answerAudioIn(eotAtMs: Long?, now: Long, askedAtMs: Long?) {
        answerAudioStarted = true
        eotAtMs?.let {
            LiveLatency.log(LiveLatency.TAG, "eot_reply_ms=${now - it}")
            answerInHandAtMs = now
            answerAskedAtMs?.let { asked -> lastFirstAudioMs = now - asked }
            turnGap?.answerHeard(now)
            logFirstSound()
            turnGap?.line(now)?.let { line -> LiveLatency.log(LiveLatency.TAG, line) }
        }
        // From the reply's first clip asked of the voice (it was timed from the clip's own first
        // audio, so it read about 0); a clip queued behind a task line counts that wait too.
        askedAtMs?.let { LiveLatency.log(LiveLatency.TAG, "tts_first_ms=${now - it}") }
    }

    /** A turn of his ([eotAtMs]): its first answer clip was asked of the voice now ([DeadAir]). */
    private fun answerAsked(eotAtMs: Long?) {
        if (eotAtMs != null && answerAskedAtMs == null) answerAskedAtMs = System.currentTimeMillis()
    }

    /** The answer's first write got the play gate: how long a filler clip held it (0 when none). */
    private fun answerGotGate() {
        val at = answerInHandAtMs ?: return
        answerInHandAtMs = null
        LiveLatency.log(LiveLatency.TAG, "answer_hold_ms=${System.currentTimeMillis() - at}")
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
        engineBrain?.runDeviceActions()
        lastReplyEndAtMs = System.currentTimeMillis()
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
            sayOwed()
            shutdown("dismissed")
            host.closed("dismissed")
            return
        }
        backToListening()
    }

    private fun backToListening() {
        if (closing.get()) return
        // A turn that ended with nothing heard still gets its first-sound line (kind=none).
        turnGap?.silentLine()?.let { LiveLatency.log(LiveLatency.TAG, it) }
        // The turn's wait is over (answered, noise, a dropped transcript, a failed voice): no more fillers.
        fillerJobs.endTurn()
        warmFillers()
        host.phase(Phase.LISTENING, "Listening...")
        followUpEvent(FollowUpWindow.Event.TurnComplete)
        followUpEvent(FollowUpWindow.Event.PlaybackDrained)
    }

    /**
     * The filler lines' audio, fetched once per voice while the floor is his (no reply is being
     * voiced then), so the first filler plays from memory (2026-11-16).
     */
    private fun warmFillers() {
        val b = backend ?: return
        // Only what is missing is fetched (a kept line in memory, or one already in flight, is skipped).
        b.tts.warm(FILLERS_SPOKEN)
        // The backchannels' and the wait lines' second renders, kept apart ([Backchannel.TAKES]).
        b.tts.warmTakes((Backchannel.LINES + WaitFillers.LINES).map { SpokenText.forSpeech(it) }, 1)
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
        // A tap or barge-in stops the wait too: no backchannel or wait line after it (review B1).
        fillerJobs.endTurn()
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
        // The bundled cues are Puck: another voice plays the earcon until they are re-rendered in it.
        val cue = ListenCue.next(ctx, VoiceEngineSettings.cloudVoice(ctx))
        val p = player ?: return
        LiveLatency.log(LiveLatency.TAG, "listen_cue ${cue.label} (cascade)")
        turnJob = scope.launch(loop) {
            onReplyAudible()
            val gen = p.generation()
            openingCueEndsAtMs = System.currentTimeMillis() +
                cue.pcm.size * MS_PER_S / (ListenCue.SAMPLE_RATE * PCM_BYTES_PER_SAMPLE)
            withContext(Dispatchers.IO) { p.write(p.startable(cue.pcm), gen) }
            afterReply()
        }
        openingJob = turnJob
    }

    private suspend fun onScriptedFailed(voice: SpeechBackend, line: String, reason: String) {
        val moment = if (history.isEmpty()) Handover.Moment.AtOpen else Handover.Moment.Idle
        when {
            voice.kind == SpeechBackend.Kind.CLOUD -> sayVoiceLost(reason)
            swapToCloud(voice, "tts", reason) -> speakScripted(line)
            else -> handOver(moment, "tts", reason, from = voice.kind)
        }
    }

    /**
     * The cloud voice could not say a line (two tries, no audio; 2026-11-18 review B1: this closed
     * the conversation). The words are already on the panel; he hears a kept line from memory, or the
     * earcon when it is not in hand, never a new TTS call, and the window opens again.
     */
    private suspend fun sayVoiceLost(reason: String) {
        LiveLatency.log(LiveLatency.TAG, "reply_voice_lost reason=$reason")
        val b = backend
        val p = player
        if (closing.get() || b == null || p == null) {
            backToListening()
            return
        }
        if (b.tts.inHand(SpokenText.forSpeech(VOICE_LOST_LINE))) {
            val reply = newSpeaker(null)
            reply.say(VOICE_LOST_LINE)
            when (reply.await()) {
                ReplySpeaker.Outcome.Played -> afterReply()
                ReplySpeaker.Outcome.Interrupted -> Unit
                is ReplySpeaker.Outcome.Failed -> backToListening()
            }
            return
        }
        if (b.tts.sampleRate == ListenCue.SAMPLE_RATE) {
            p.awaitDrained()
            // Counted as playback, as the opening cue is: on half-duplex the mic never takes it as a turn.
            onReplyAudible()
            val gen = p.generation()
            withContext(Dispatchers.IO) { p.write(p.startable(ListenCue.earconPcm()), gen) }
        }
        afterReply()
    }

    /**
     * The personal greeting (ADR 0091, ticket 02), or the hello with no topic ([GreetingPlan.Hello],
     * 2026-11-16): ONE streamed text call through the same cached
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
    private fun speakGreeting(plan: GreetingPlan.Spoken) {
        val loopRef = greetingLoop
        if (loopRef == null) {
            speakScripted(ackLines.next())
            return
        }
        val voice = backend!!
        // No background line fetch while the greeting is held and spoken: on the laptop it would sit
        // ahead of the greeting ([TextToSpeech.holdWarm]). Released when the greeting's job ends.
        voice.tts.holdWarm(true)
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
            // The model's whole hello as written. It is held, not streamed: the name check
            // ([GreetingWords.namesHim]) reads the whole line before any of it is spoken, and it is
            // what the "do not reuse" list keeps ([GreetingOpening.noteOpened]).
            val written = StringBuffer()
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
                                written.append(delta)
                                if (written.isNotBlank()) firstText.complete(Unit)
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
                val whole = written.isNotBlank() &&
                    withTimeoutOrNull(GreetingBudget.WHOLE_LINE_MS) { call.join() } != null
                val gate = greetingGate(whole, written.toString(), plan.name)
                if (gate != GreetingGate.SPEAK) {
                    call.cancel()
                    reply.interrupt()
                    greetingNotSpoken(gate)
                    return@launch
                }
                reply.text(written.toString())
                reply.finishText()
                // A test open (adb) does not use up the topic for his real greetings. The words are the
                // model's text, whole now: what was heard ([ReplySpeaker.spoken]) is still empty here,
                // since the stream ends before the clip starts, and the avoid list never filled (11-19
                // drive: most hellos were one line).
                if (test == null) greeting?.noteOpened(plan, GreetingWords.of(written.toString(), plan.name))
                when (val o = reply.await()) {
                    ReplySpeaker.Outcome.Played -> {
                        // So his answer is understood: the greeting is the first exchange.
                        history.add(ChatTurn(fromUser = true, text = OPENED_NOTE))
                        history.add(ChatTurn(fromUser = false, text = reply.spoken()))
                        engineBrain?.context?.greeted(reply.spoken())
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
        }.also { job ->
            greetingJob = job
            job.invokeOnCompletion { voice.tts.holdWarm(false) }
        }
    }

    /**
     * The model's greeting was not spoken ([gate] is not [GreetingGate.SPEAK]): logged; the scripted
     * hello for a slow or wrongly named line; for a drop because he spoke, his own turn carries on and
     * only the earcon may play ([GreetingGate.earconAfterDrop]).
     */
    private suspend fun greetingNotSpoken(gate: GreetingGate) {
        val earcon = gate == GreetingGate.HE_SPOKE &&
            GreetingGate.earconAfterDrop(System.currentTimeMillis(), lastLocalSpeechAtMs)
        gate.logLine(earcon)?.let { LiveLatency.log(LiveLatency.TAG, it) }
        if (gate.scripted) speakScripted(ackLines.next())
        if (earcon) playDropEarcon()
    }

    /**
     * The listen earcon after a dropped greeting when he is not talking now ([GreetingGate.earconAfterDrop]):
     * the wake is still acknowledged if the speech that dropped it was not his. Nothing else changes.
     */
    private suspend fun playDropEarcon() {
        val p = player ?: return
        if (backend?.tts?.sampleRate != ListenCue.SAMPLE_RATE) return
        val gen = p.generation()
        withContext(Dispatchers.IO) { p.write(p.startable(ListenCue.earconPcm()), gen) }
    }

    /**
     * The held greeting's fate ([GreetingGate]), read the moment before any of it would be spoken: he
     * spoke since the open ([heardSinceOpen]), it was not whole in time, or it calls him by a name
     * that is not [name] ([com.kevin.legion.greeting.GreetingNames]).
     */
    private fun greetingGate(whole: Boolean, written: String, name: String?): GreetingGate =
        GreetingGate.decide(heardSinceOpen, whole, whole && GreetingWords.namesHim(written, name))

    private suspend fun endByVoice() {
        sayOwed()
        sayFarewell()
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
        // A turn the phone answered goes to the engine's next turn as context ([EnginePhoneContext]).
        engineBrain?.context?.recorded(heard, said, readThrough, fromUser)
        if (heard.isNotBlank()) history.add(ChatTurn(fromUser = true, text = heard))
        if (fromUser && said.isNotBlank()) readThroughCarry.answered()
        // A read-through reply (mail, web) is never kept as history or in the record in clear.
        val kept = CascadePrompt.keptReply(said, readThrough)
        if (said.isNotBlank()) history.add(ChatTurn(fromUser = false, text = kept))
        host.turnTranscript(if (fromUser) heard else "", said)
        // A test conversation keeps no record: nothing uploads, nothing becomes a memory.
        if (test != null) return
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
        val windowMs = FollowUpWindow.windowAfter(lastClipText)
        LiveLatency.log("LIVE_FOLLOWUP", "armed ms=$windowMs engine=${engineToken()}")
        followUpJob = scope.launch(loop) {
            delay(windowMs)
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
            sayOwed()
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
            val tt = turnTaker?.takeIf { it.bargeIn }
            // Call audio entered for the earpiece alone (barge-in off) leaves for Bluetooth too.
            if (tt == null && callAudio?.onEarpiece != true) return@launch
            tt?.disableBargeIn()
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
        isMutating = CascadeRules::isWrite,
        isReadThrough = CascadeRules::isReadThrough,
        toolTimeoutMs = { TOOL_BACKSTOP_MS },
        // Every write that went through is said by code from its result (2026-11-19).
        writeFact = ScriptedLines::factOf,
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
        if (c == null) return
        // The running transcript is kept for the acknowledgement's word gate ([Backchannel.wordsSkip])
        // with the setting off too; the drafts use it only with it on.
        c.onPartial = { id, text ->
            heardSoFar = id to text
            if (speculate) scope.launch(loop) { onPartialText(id, text) }
        }
        if (!speculate) return
        c.onMaybeEnd = { id, text, atEnd -> scope.launch(loop) { onMaybeEnd(id, text, atEnd) } }
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
        SpeculationRules.longEnough(heard) && !CrisisDetector.detect(heard) &&
            !WakePhrases.isSleepPhrase(heard, CompanionProfile.name(ctx))

    /** The model is asked on [heard]; its text and tool calls are held ([SpeculativeAnswer]). */
    @Suppress("ReturnCount")
    private fun startDraftAnswer(d: TurnDraft, heard: String) {
        val pfx = prefix ?: return
        val cache = cacheStore ?: return
        val hold = UsageHold(::noteUsage)
        val answer = SpeculativeAnswer(scope, newTextLoop(cache, CallUsage.LLM, hold::add), hold)
        d.heard = heard
        d.historySize = history.size
        d.preamble = turnPreamble()
        d.answer = answer
        if (d.stt == null) usageLedger.spec(started = 1)
        LiveLatency.log(
            LiveLatency.TAG,
            CascadeLines.specStart(d.trigger, d.utt.backend.kind.token, heard.length),
        )
        answer.start(pfx, turnContentsFor(heard))
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
        d.stt = scope.async(Dispatchers.IO) { gemini.transcribe(audio, hold::add, hedged = false) }
        draft = d
        usageLedger.spec(started = 1)
        LiveLatency.log(LiveLatency.TAG, CascadeLines.specStart(d.trigger, u.backend.kind.token, 0))
        scope.launch(loop) {
            val r = runCatching { d.stt?.await() }.getOrNull()
            if (draft !== d || d.answer != null) return@launch
            LiveLatency.log(LiveLatency.TAG, "spec_stt_ms=${System.currentTimeMillis() - d.startedAtMs} " +
                "audio_ms=${audio.size / PCM_BYTES_PER_MS}")
            val said = r as? SttResult.Text ?: return@launch
            // A transcript the turn would not believe ([SttGuard]) is not drafted on either.
            val kept = verdictOf(said) as? SttGuard.Verdict.Keep ?: return@launch
            val heard = (carry + " " + kept.text).trim()
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
            d.historySize != history.size || d.preamble != turnPreamble() -> "context_changed"
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
            player = ReplyPlayer(next.tts.sampleRate, { callAudio?.isActive == true }) { speechSpeed }
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
                earlySetting = VoiceTurnSettings.earlyEndOfTurn(ctx),
            )
            turnTaker = TurnTaker(
                next.config,
                m.silero.stream(),
                m.smartTurn.takeIf { next.config.smartTurn },
                sink,
                next.endBaseMs,
                next.endFinishedMs,
            )
            plan = next
            old?.reset()
            next
        }
        if (!p.config.bargeIn) {
            // The barge-in call mode goes; the car's hands-free link (no barge-in) stays.
            callAudio?.takeUnless { it.onBluetoothMic || it.onEarpiece }?.let {
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
        micHold.lost()
        scope.launch(loop) {
            LiveLatency.log("LIVE_TURN", "cascade preempted by a higher-priority mic claimant")
            shutdown("preempted")
            host.closed("preempted")
        }
    }

    private fun heardMoment(heard: String, r: TurnRecord? = null) = Handover.Moment.Heard(
        heard = heard,
        reply = TtsTags.strip(r?.reply?.toString().orEmpty()),
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
        // One hand-over at a time (review round 2: a crash during a hand-over could say the owed line twice).
        if (closing.get() || !handingOver.compareAndSet(false, true)) return
        // A write he has not heard is said before the tone while the voice works, else written down.
        if (CascadeRules.handOverSaysOwed(stage, phoneFault)) withTimeoutOrNull(OWED_SAY_MAX_MS) { sayOwed() }
        if (!closing.compareAndSet(false, true)) return
        writeDownOwed()
        try {
            val source = if (phoneFault) "phone" else from?.token ?: "phone"
            val to = if (VoiceEngineSettings.LIVE_RETIRED) "none" else "live"
            LiveLatency.log(LiveLatency.TAG, CascadeLines.fallback(source, to, stage, reason))
            val said = words ?: failedWords(if (stage == "open") reason else "${stage}_$reason", from, phoneFault)
            VoiceEngineSettings.noteStatus(ctx, said)
            // A background line nobody asked for fails quietly on screen; Settings still says why.
            if (!quiet) host.notice(failedNotice(moment, said, from, phoneFault))
            release(CloseCue.voiceFailedReason(reason, VoiceEngineSettings.LIVE_RETIRED, quiet))
        } finally {
            if (VoiceEngineSettings.LIVE_RETIRED) {
                // Live is retired (Kevin, 2026-11-15): never opened behind his back. The failure is
                // said in words above and the conversation ends with the closing tone.
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

    /** The closing chime has played for this session's close ([CloseCue]: once only). */
    @Volatile private var closeCuePlayed = false

    /**
     * The closing chime ([ListenCue.closeChimePcm], [CloseCue]): through Alfred's own track, after the
     * mic has closed and before the route is released, waited for at most the chime plus the route's
     * tail ([CloseCue.waitCapMs]). Logged either way.
     */
    private suspend fun playCloseCue(reason: String): Boolean {
        val p = player
        val skip = CloseCue.decide(
            reason,
            quiet = QuietMode.isOn(ctx),
            onCall = callOnLine(),
            audioOpened = p != null,
            alreadyPlayed = closeCuePlayed,
            rateMatches = backend?.tts?.sampleRate == ListenCue.SAMPLE_RATE,
        )
        if (skip != null || p == null) {
            LiveLatency.log(LiveLatency.TAG, CloseCue.skippedLine(reason, skip ?: CloseCue.Skip.NO_AUDIO))
            return false
        }
        closeCuePlayed = true
        val pcm = ListenCue.closeChimePcm()
        val route = CallAudioMode.route(audioManager)
        val settle = MicReopen.settleMs(route)
        val chimeMs = ListenCue.durationMs(pcm)
        val cap = CloseCue.waitCapMs(chimeMs, settle)
        // Released only once the device says the chime's last frame was presented (review K1), with the
        // chime plus a fixed latency as the floor when it gives no timestamp, and the cap over both.
        // Frames the mixer has taken so far: the chime's are counted from here (the flush reset it).
        val head0 = p.headFrames() ?: 0L
        val chimeFrames = pcm.size / PCM_BYTES_PER_SAMPLE.toLong()
        val played = CloseCue.playHeard(
            write = { withContext(Dispatchers.IO) { p.write(p.startable(pcm), p.generation()) } },
            presented = p::presentedFrame,
            spec = CloseCue.Spec(chimeFrames, chimeMs, CloseCue.minPlayMs(chimeMs), cap),
        )
        val heard = played.copy(consumed = ((p.headFrames() ?: head0) - head0).coerceAtLeast(0), of = chimeFrames)
        // A Bluetooth sink plays its own buffer past what the phone presented: its tail, inside the cap.
        val tail = minOf(settle, cap - heard.ms).coerceAtLeast(0)
        if (heard.by != CloseCue.By.CAP && route == AudioRoute.BLUETOOTH) delay(tail)
        val routeToken = CloseCue.routeToken(route, earpiece = earpiece.wantsEarpiece() && callAudio?.isActive == true)
        LiveLatency.log(LiveLatency.TAG, CloseCue.playedLine(reason, routeToken, heard, chimeMs))
        return heard.by != CloseCue.By.CAP
    }

    /** The conversation's usage summary at its close, and the prompt cache's listener let go. */
    private fun logUsageAtClose() {
        if (auditStarted) {
            val engines = enginesUsed.joinToString(">")
            LiveLatency.log(LiveLatency.TAG, usageLedger.summary(engines, turnsAnswered.get()))
        }
        CACHE?.onCreated = null
    }

    /**
     * Someone's call, or the platform silencing our capture ([CallPause]): nothing more is heard or
     * said. The reply stops now, no audio goes on to any speech-to-text, the engine's turn in progress
     * is taken back through its release, and the conversation closes with no words and no chime
     * (the close reason is not one [CloseCue] plays for). Closing, not pausing: a call can run for an
     * hour, and a conversation resumed after it would answer a question from before it.
     */
    private suspend fun pauseForCall(reason: CallPause.Reason) {
        if (closing.get() || callPaused) return
        callPaused = true
        LiveLatency.log(LiveLatency.TAG, CallPause.line(reason))
        fillerJobs.endTurn()
        speaker?.interrupt()
        player?.flush()
        currentUtt?.engine?.release()
        shutdown(CallPause.closeReason(reason))
        host.closed(CallPause.closeReason(reason))
    }

    /** Idempotent full release. */
    private suspend fun shutdown(reason: String) {
        if (!closing.compareAndSet(false, true)) return
        // A close that said nothing more (a tap, preemption, destroy): written down, never dropped.
        writeDownOwed()
        release(reason)
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun release(reason: String) {
        // The conversation is over: no remove question outlives it.
        EventRemoval.reset()
        com.kevin.legion.service.tools.day.EventMove.reset()
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
        val releaseStartMs = System.currentTimeMillis()
        turnGap?.silentLine()?.let { LiveLatency.log(LiveLatency.TAG, it) }
        isOpen = false
        // A clear-codes question belongs to this conversation: a "yes" in the next one answers nothing.
        step("clear_question") { DtcClearController.forgetClearQuestion() }
        // Engine turns whose go went early and that no turn read: cancelled on the engine (round 4).
        engineBrain?.earlyTurns?.forEach { it.release() }
        step("jobs") {
            followUpJob?.cancel()
            uttCapJob?.cancel()
            specPollJob?.cancel()
            discardDraft("closed")
            speaker?.interrupt()
            // A stop mid-reply: what is still buffered goes now, so the chime is not queued behind it
            // (and neither is cut at the chime's cap). The chime takes the new generation.
            player?.flush()
            turnJob?.takeIf { it !== caller }?.cancel()
            currentUtt?.cancel()
            currentUtt = null
            // His held audio goes with the session (a failed open too: every exit releases).
            synchronized(turnLock) {
                backlog = null
                clipListen = null
            }
        }
        try {
            micJob?.takeIf { it !== caller }?.cancelAndJoin()
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            Log.w(TAG, "mic join: ${e.message}")
        }
        step("turn") { turnTaker?.reset() }
        step("call_watch") { callWatch.stop() }
        // The mic is closed: its claim goes now, so the wake word re-arms without waiting on the chime.
        step("mic_claim") { micHold.drop() }
        val micReleasedMs = System.currentTimeMillis() - releaseStartMs
        // The closing chime, before the player and the route go (Bluetooth would cut it off).
        val chimed = try {
            playCloseCue(reason)
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            Log.w(TAG, "close cue: ${e.message}")
            false
        }
        val chimeEndMs = if (chimed) System.currentTimeMillis() - releaseStartMs else null
        step("player") { player?.release() }
        step("route_watch") { audioRoute.stopWatching() }
        step("earpiece") { earpiece.stop() }
        step("call_audio") { callAudio?.let { if (it.isActive) it.exit("cascade $reason") } }
        val routeReleasedMs = System.currentTimeMillis() - releaseStartMs
        LiveLatency.log(LiveLatency.TAG, CloseCue.orderLine(micReleasedMs, chimeEndMs, routeReleasedMs))
        step("focus") { abandonFocus() }
        step("caption") { CompanionPhase.setCaption("") }
        step("socket") { backend?.close(reason) }
        step("usage") { logUsageAtClose() }
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

    private fun startMic(chime: Boolean = false) {
        micJob = scope.launch(Dispatchers.IO) {
            // The car's hands-free link, when it was asked for, carries the route before capture.
            audioRoute.awaitBluetoothMic()
            // Once the route is in: a call or a silenced capture closes the conversation ([CallPause]).
            callWatch.start(scope)
            if (routeLogged.compareAndSet(false, true)) {
                LiveLatency.log(LiveLatency.TAG, "open_route_ms=${System.currentTimeMillis() - openStartedAtMs}")
            }
            val recording = { if (chime) scope.launch(loop) { playOpenChime() } }
            // A test conversation never opens the real mic ([TestMic]).
            test?.mic?.let { m ->
                recording()
                m.play { buf, n -> onMicBuffer(buf, n) }
            } ?: CascadeMic(ctx).run(
                onBuffer = { buf, n -> onMicBuffer(buf, n) },
                onNoPermission = {
                    chimeDone.complete(Unit)
                    host.notice("Didn't start - microphone permission is off")
                    close("no_mic_permission")
                },
                // A mic that cannot open or keeps failing is a deaf conversation, on any voice: Live.
                onFailure = { reason ->
                    chimeDone.complete(Unit)
                    scope.launch(loop) { handOver(currentMoment(), "mic", reason, phoneFault = true) }
                },
                onRecording = { id ->
                    callWatch.ourCapture(id)
                    recording()
                },
            )
        }
    }

    /**
     * One mic buffer: to the turn detector, or held ([backlog]) while a tap's setup is still behind it.
     * Under [turnLock]: a backend swap, or the backlog's drain, never lands inside a feed.
     */
    private fun onMicBuffer(buf: ByteArray, n: Int) {
        // A call came: nothing more reaches any speech-to-text, from this buffer on ([pauseForCall]).
        if (callPaused) return
        if (micBufferSeen.compareAndSet(false, true)) {
            LiveLatency.log(LiveLatency.TAG, "open_capture_ms=${System.currentTimeMillis() - openStartedAtMs}")
        }
        synchronized(turnLock) {
            clipListen?.let { listen ->
                listen.onFrame(buf, n, playbackActive)
                if (listen.expired) clipListen = null
            }
            val taker = turnTaker
            if (taker != null) taker.feed(buf, n, playbackActive) else backlog?.add(buf, n, playbackActive)
        }
    }

    /**
     * A tap: the route (call audio, or the car's hands-free mic) is entered and the mic opened now,
     * before the turn models, the voice service and the prompt (Kevin, 2026-11-19). Capture follows the
     * audio mode it starts in, so the route goes first; its barge-in decision needs only the route.
     */
    private fun openMicNow() {
        val route = CallAudioMode.route(audioManager)
        val barge = OpeningRule.bargeIn(VoiceTurnSettings.bargeIn(ctx), route, TelephonyController.isInCall)
        tapRoute = route
        tapBarge = barge
        enterRoute(barge, "tap")
        synchronized(turnLock) { backlog = MicBacklog() }
        startMic(chime = true)
    }

    /**
     * Call audio for barge-in or the earpiece (with the Bluetooth watch), else the car's hands-free mic
     * when there is one. Then [earpiece] follows the toggle and headset changes until release.
     */
    private fun enterRoute(barge: Boolean, token: String) {
        // The earpiece only for a conversation he started by a tap in the app (EarpieceRoute's doc).
        earpiece.begin(VoiceOutputRule.opening(inHand, fromWakeWord, opener != null, test != null))
        val onEarpiece = earpiece.wantsEarpiece()
        if (VoiceOutputRule.callAudio(barge, onEarpiece)) {
            enterCallAudio(onEarpiece, token)
        } else {
            // In the car: the car's own hands-free mic, as Live did (Kevin, 2026-11-10).
            callAudio = audioRoute.enterBluetoothMic(token)
        }
        earpiece.follow(scope, loop, { callAudio }) { enterEarpieceMidway() }
    }

    private fun enterCallAudio(onEarpiece: Boolean, token: String) {
        val mode = CallAudioMode(audioManager)
        LiveLatency.log(TurnTaker.BARGE_TAG, "call audio ${mode.enter(onEarpiece)} (cascade, $token)")
        callAudio = mode
        audioRoute.watchForBluetooth(onOutputsChanged = { callAudio?.onDevicesChanged() }) { onBluetoothConnected() }
    }

    /**
     * The toggle went to CALL while outside call audio (barge-in off): the reply or clip in flight (a
     * filler sets only [playbackActive]) finishes on the loudspeaker, then call audio is entered and the
     * player rebuilt on its usage, so no word is cut.
     */
    private suspend fun enterEarpieceMidway() {
        while ((speaking || playbackActive) && !closing.get()) delay(MIDWAY_POLL_MS)
        if (closing.get() || callAudio?.isActive == true) return
        // He may have flipped back, or plugged headphones in, while the reply finished.
        if (!earpiece.wantsEarpiece()) return
        enterCallAudio(onEarpiece = true, token = "earpiece")
        player?.rebuild()
    }

    /**
     * The tap's chime, once capture has started: a short local earcon ([ListenCue.earconPcm]), no
     * model and no voice service, on its own player (the reply player needs the voice's sample rate,
     * which is not known yet). Half-duplex, it counts as playback so the mic never takes it for him,
     * and the Bluetooth tail settles before his audio counts again. Never a reply: [speaking], the
     * follow-up window and the record do not see it.
     */
    private suspend fun playOpenChime() {
        if (closing.get()) {
            chimeDone.complete(Unit)
            return
        }
        val chime = ReplyPlayer(ListenCue.SAMPLE_RATE, { callAudio?.isActive == true })
        try {
            if (!tapBarge) playbackActive = true
            firstAudioLogged = true
            LiveLatency.log(LiveLatency.TAG, "open_chime_ms=${System.currentTimeMillis() - openStartedAtMs}")
            host.phase(Phase.LISTENING, "Listening...")
            val earcon = ListenCue.earconPcm()
            openingCueEndsAtMs = System.currentTimeMillis() +
                earcon.size * MS_PER_S / (ListenCue.SAMPLE_RATE * PCM_BYTES_PER_SAMPLE)
            withContext(Dispatchers.IO) { chime.write(chime.startable(earcon), chime.generation()) }
            chime.awaitDrained()
            if (!tapBarge) delay(MicReopen.settleMs(CallAudioMode.route(audioManager)))
        } finally {
            chime.release()
            if (!speaking) playbackActive = false
            chimeDone.complete(Unit)
        }
    }

    /**
     * After a tap's open: once the chime has played, the floor is his and the follow-up window runs
     * as after any reply. Set as the first [turnJob] (the test door waits on it, as on the greeting).
     * If he already started speaking, his turn's reply opens the window instead.
     */
    private fun listenAfterChime() {
        turnJob = scope.launch(loop) {
            withTimeoutOrNull(CHIME_WAIT_MS) { chimeDone.await() }
            if (!heardSinceOpen) backToListening()
        }
        openingJob = turnJob
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
            // A loss to a call's capture closes the conversation ([CallWatch]); a bare loss does nothing.
            .setOnAudioFocusChangeListener(callWatch.focusListener)
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

        /** An early engine decision waits at most this long for the last turn's running tool (R2). */
        private const val EARLY_WAIT_MS = 2_000L
        private const val EARLY_POLL_MS = 50L


        /** The loop's own backstop; the controller's runner already applies each tool's real ceiling. */
        const val TOOL_BACKSTOP_MS = 47_000L

        /** A test turn waits this long (beyond the turn models) for the open to start its first line. */
        private const val OPEN_WAIT_MS = 20_000L
        private const val TEST_POLL_MS = 50L
        private const val MIDWAY_POLL_MS = 50L


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

        /** "Didn't catch that" twice in a row is the most; the next miss closes the conversation. */
        private const val MAX_MISSED_IN_A_ROW = 2
        const val MAX_NOISE_IN_A_ROW = 5

        /** Said when his words were not caught (2026-11-17); kept in memory like the fillers. */
        const val MISSED_LINE = ScriptedLines.MISSED
        const val VOICE_LOST_LINE = ScriptedLines.VOICE_LOST

        /** The open's "refusal" token when the engine is Cloud: no laptop was asked. */
        private const val CLOUD_ENGINE = "cloud_engine"
        private const val SCOPE_GRACE_MS = 2_000L

        private val ACK = AckLines()

        /**
         * Warmed at open: the generic lines and the missed line only (2026-11-17 review: all ~46 kept
         * lines at once queued ahead of the first answer). Tool lines are fetched one at a time, the
         * first time each is due, and play from the next time on.
         */
        private val FILLERS_SPOKEN =
            (
                ToolFillers.GENERIC + ScriptedLines.MISSED + ScriptedLines.VOICE_LOST + Backchannel.LINES +
                    WaitFillers.LINES + Farewells.SPOKEN + ScriptedLines.GENERIC_DONE
                )
                .distinct().map { SpokenText.forSpeech(it) }

        /** How often the wait-line timer looks again for a filler that ended later than it planned for. */
        private const val WAIT_POLL_MS = 250L

        /** A backchannel's audio must be in hand within this, or it is skipped (it is from memory). */
        private const val PCM_BYTES_PER_SAMPLE = 2

        /** How long the first sound's playback head is watched for ([logFirstSound]). */
        private const val HEARD_WATCH_MS = 6_000L
        private const val HEARD_POLL_MS = 10L

        /** An acknowledgement's drain wait: its own length plus this, never more. */
        private const val ACK_DRAIN_MARGIN_MS = 300L

        /** How long the phone's own read of today may take at open (normally well under 100 ms). */
        private const val AGENDA_WAIT_MS = 1_000L

        /** The longest a tap's open waits for its chime before the listening window starts anyway. */
        private const val CHIME_WAIT_MS = 3_000L

        /** The longest a check-in's closer may take to say before the conversation just ends. */
        private const val CLOSER_MAX_MS = 6_000L

        /** How long a hand-over waits to say a write he has not heard before its tone (review F5). */
        private const val OWED_SAY_MAX_MS = 6_000L

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
                hint = { SttHintStore.line },
                instruction = { SttHintStore.instruction },
            )

        /** One cache store for the process: the cache outlives any one conversation. */
        @Volatile private var CACHE: ContextCacheStore? = null
        private val CACHE_SCOPE = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}

package com.kevin.legion.service

import com.kevin.legion.service.tools.LiveSurface
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.content.ContextCompat
import com.kevin.legion.assistant.cards.ConversationCards
import com.kevin.legion.ai.AriaBrain
import com.kevin.legion.ai.CompanionProfile
import com.kevin.legion.ai.CrisisDetector
import com.kevin.legion.ai.GeminiKeyProvider
import com.kevin.legion.ai.KeyHealth
import com.kevin.legion.ai.WebReadMark
import com.kevin.legion.ai.firstGreetingOpener
import com.kevin.legion.car.CarProbeLog
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.ConversationAudit
import com.kevin.legion.data.local.record
import com.kevin.legion.data.local.auditContent
import com.kevin.legion.look.LookController
import com.kevin.legion.look.LookRules
import com.kevin.legion.ui.LegionRoute
import com.kevin.legion.ui.MainActivity
import com.kevin.legion.vehicle.ActiveVehicle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * Owns the single Gemini Live session and the conversation state machine for the
 * whole app.
 *
 * This logic used to live inside the `AriaLiveScreen` composable, which meant
 * voice only worked while that activity was in the foreground. Moving it into a
 * service-owned controller is the core of the overlay pivot: the floating button
 * (the since-removed floating overlay button) can now drive a turn while another app (Spotify,
 * Google Maps) is in front. [AriaForegroundService] creates one of these, wires
 * it to the proactive engine ([ProactiveBus]) and the floating button, and tears
 * it down in onDestroy.
 *
 * [phase] and [status] are exposed as flows so the overlay (and the setup
 * screen) can render the current state.
 *
 * **No socket while idle (Kevin, 2026-11-05):** *"stop keeping a voice session open
 * while idle; only open one when you tap or say the wake word."* A session is opened
 * only by something that needs it now - a tap or Talk ([onTap]), the wake word (also
 * [onTap]), the side key ([onAssistRequest]), or a proactive raise that has to speak
 * ([requestSpeak]) - and nothing reopens one after it closes. The idle prewarm this
 * replaced (on service start, after every close inside a 10 minute window, and on every
 * voice or car refresh) was closed by the server for "client inactivity" about every two
 * minutes and reopened at once, for as long as the window kept being renewed. The cost is
 * one cold connect (`LIVE_LATENCY cold_start_ms`) on a tap that used to find a warm socket.
 * [newSession] is the only door, and it supersedes any session still held, so there is at
 * most one. The typed chat panel never needs a socket ([onTyped]). The session is set up
 * with the cached static instruction ([AriaBrain.buildBaseInstruction]); fresh live context
 * ([AriaBrain.buildLiveContext]) is injected into the greeting at the start of each
 * conversation so it's current without rebuilding the whole prompt per turn.
 *
 * Three ways a session is driven:
 *  - The driver taps the floating button ([onTap]) to start (or resume) a
 *    hands-free chat: a cold/first start has Zero greet then listens; a warm
 *    resume opens the mic immediately (no greeting round-trip).
 *  - The proactive engine ([requestSpeak]) voices an opener/alert once, with no
 *    mic opened - reusing the warm socket when one exists.
 */
class LiveSessionController(context: Context) {
    private val appContext = context.applicationContext
    private val brain = AriaBrain.get(appContext)

    // Events from GeminiLiveSession arrive on the main thread; keep all state
    // transitions there too so reads in callbacks see the latest value.
    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    private var session: GeminiLiveSession? = null
        set(value) {
            field = value
            lookSession = value
            // A session that is gone cannot still be silenced, and there are ten
            // assignment sites that drop one. Clearing HERE rather than at each of
            // them is what stops a stale `true` outliving the socket that raised it
            // and leaving the strip permanently claiming LEGION is deaf. Whatever
            // session replaces it publishes its own state through [newSession]'s
            // collector.
            if (value == null) CompanionPhase.setSilenced(false)
        }

    // Set once destroy() runs so a final Closed event cannot start anything on a torn-down
    // controller.
    private var destroyed = false

    private val _phase = MutableStateFlow(Phase.IDLE)
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    private val _status = MutableStateFlow(IDLE_STATUS)
    val status: StateFlow<String> = _status.asStateFlow()

    // Driver-facing failures are published to CompanionPhase.notice (the Cruise /
    // Lights Out screens flash them) so a failed turn is never silent.

    // Live subtitle of what Zero is saying (debug toggle); empty string clears it.
    private val _subtitle = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val subtitle: SharedFlow<String> = _subtitle.asSharedFlow()

    // Whether the current session ever reached Connected - lets a close tell a
    // failed connection ("Couldn't connect") from a mid-chat drop ("Connection lost").
    private var connectedThisSession = false

    // What to do once the socket finishes connecting (the setup handshake is
    // async). A warm (parked) socket is already connected, so these only matter
    // for a cold connect.
    private enum class Pending { NONE, CONVERSATION, PROACTIVE_COLD, PROACTIVE_WARM }
    private var pendingAction = Pending.NONE
    private var pendingPrompt: String? = null

    /** A cold open whose scripted line waits for the socket ([CUE_TIMING] ON_CONNECT). */
    private var cueOnConnect = false

    /**
     * Typed chat (Kevin, 2026-10-27): a text model, not this controller's Live session. It shares
     * the voice path's instruction builder, tool declarations and tool runner ([runTool]), and is
     * never handed [session]. See [TypedChat] and [onTyped].
     */
    // Conversation-records ticket 03: typed turns write LIVE_AUDIT lines too (logcat and the engine spool).
    private val typedAudit = TypedChatAudit(
        enabled = { com.kevin.legion.service.conversationlog.ConversationLog.auditWantedAfterReading(appContext) },
    )

    private val typedChat = TypedChat(
        scope = scope,
        engine = TextChatEngine(
            // runToolBounded already applies the voice path's own per-tool ceiling; the engine's is
            // a backstop a little above it so the two never race to report the same timeout.
            toolTimeoutMs = { name -> toolTimeoutFor(name) + 2_000L },
            onReadThrough = typedAudit::readThrough,
        ),
        hasKey = GeminiKeyProvider::hasKey,
        isOnline = ::isOnline,
        isCrisis = CrisisDetector::detect,
        systemParts = { listOf(brain.buildTextInstruction(), brain.buildLiveContext()) },
        declarations = { LiveToolbox.textDeclarations() },
        runTool = { name, args, readThrough, turnSeq, userText ->
            val auditId = typedAudit.toolCall(name, args)
            val response = if (name in LiveToolbox.VOICE_SESSION_TOOLS) {
                // Not declared to the text model; refused in words if it is called anyway, because
                // end_conversation would otherwise arm a hang-up on the NEXT voice turn.
                JSONObject().put("success", false).put("message", "That only works in a voice conversation.")
            } else {
                typedToolStarted()
                try {
                    runToolBounded(name, args, readThrough, userText)
                } finally {
                    typedToolFinished()
                }
            }
            auditToolCall(name, args, response, turnSeq)
            typedAudit.toolResult(auditId, name, response)
            response
        },
        speakerName = ::speakerName,
        // The same teardown a crisis heard in speech gets: stop performing the character.
        onCrisis = { handleEvent(LiveEvent.CrisisDetected) },
        onInFlightChanged = ::typedPhase,
        record = {
            TypedTurnRecorder.record(appContext, it)
            typedAudit.turn(it)
        },
    )

    // True while a hands-free conversation (server VAD) is running, vs. a
    // speak-only proactive session. Drives whether a completed turn returns to
    // LISTENING (wait for the driver) or IDLE.
    private var conversationMode = false

    /**
     * One-shot conversation (2026-09-27): see [OneShotGate]. When the gate says the reply is done,
     * it arms [dismissAfterTurn], the same after-the-reply hang-up "that will be all" uses, so the
     * answer is always finished before the socket goes.
     */
    private val oneShotGate = OneShotGate()

    // Ticket 02 (drive-test-2026-08-18): the latest session-resumption handle Gemini has
    // confirmed we can reconnect with, threaded into the NEXT [GeminiLiveSession.start] call
    // (the next tap's startConversation). Lives on the controller, not the session, because a
    // [GeminiLiveSession] instance dies with its own socket and this is precisely the thing
    // meant to outlive that. Cleared on a driver-initiated stop (see the Closed branch) so a
    // deliberately ended chat doesn't silently bleed into whatever the driver starts next.
    private var sessionResumeHandle: String? = null

    // One automatic cold retry per tap (2026-10-02, "no connection, tap to retry, then it says
    // listening"). [retriedColdConnectThisTap] is reset by [onTap] and set the moment the Closed
    // branch re-drives the connect, so a second failure in the same tap falls through to the
    // notice instead of looping. [coldRetry] re-runs exactly the start that just failed; it is
    // null for starts with no safe replay (the companion handover).
    private var retriedColdConnectThisTap = false
    private var coldRetry: (() -> Unit)? = null

    // Ticket 02: set when a real conversation's socket died WITHOUT a resumption handle to
    // carry it forward - i.e. the thread is genuinely gone, not just reconnecting. Consumed
    // (and cleared) by whichever of [resumeWarm] / [startConversation] actually begins the
    // next conversation, which is the only place that can honestly tell the driver AND the
    // model the previous context is gone rather than silently answering cold.
    private var pendingThreadLossNotice = false

    // How many tool calls handleToolCall is currently mid-flight on. Gemini can
    // emit several functionCalls in one turn, each getting its own scope.launch,
    // so the FIRST one to finish must not drop the UI out of THINKING while a
    // sibling is still running - only the transition back to zero restores.
    // scope is confined to Dispatchers.Main.immediate (a single thread), so a
    // plain Int is correct here; no AtomicInteger needed.
    private var activeToolCalls = 0

    // How many tool calls a typed turn is mid-flight on (2026-10-27, Kevin: "separate thinking and
    // tool running"). Separate from [activeToolCalls] because that one also means "voice owns the
    // phase". Same Main.immediate confinement, so a plain Int.
    private var typedToolCalls = 0

    /**
     * Constructs a fresh [GeminiLiveSession] and, alongside the caller's own wiring, starts
     * mirroring its [GeminiLiveSession.isSilenced] transitions to [CarProbeLog] (ticket 15 wave 2's
     * signal, ticket 08's wave 3 consumer - `.scratch/android-auto/issues/15-the-live-session-can-be-silenced.md`).
     * The construction sites below ([startConversation], [speakAndListen], [startProactive]) all
     * route through here so the car probe session can see silencing regardless of which door opened
     * the socket, without each call site remembering to wire it separately.
     *
     * **The single-session guard (2026-11-05).** This is the only place a [GeminiLiveSession] is
     * made, so it is where "at most one" is enforced: a session still held is superseded
     * ([supersede]) before the new one exists, and only the CURRENT session's events reach
     * [handleEvent] ([LiveSessionLifecycle.accepts]). Before this, a replaced session's late
     * Closed could null out its successor and orphan a live socket, and the prod log showed
     * "superseded" followed by "already connected to an existing client".
     */
    private fun newSession(): GeminiLiveSession {
        supersede("new session")
        var self: GeminiLiveSession? = null
        val s = GeminiLiveSession(appContext) { event ->
            // Events are posted to the main thread, so [self] is set by the time any arrives.
            if (LiveSessionLifecycle.accepts(source = self, current = session)) {
                handleEvent(event)
            } else {
                android.util.Log.d("LiveSessionController", "dropped ${event::class.simpleName} from a superseded session")
            }
        }
        self = s
        scope.launch {
            s.isSilenced.collect { silenced ->
                CarProbeLog.log("CarProbeMicSilenced", "GeminiLiveSession.isSilenced=$silenced")
                // Identity guard: this collector is never cancelled, so a session that
                // was torn down and replaced can still emit (its own teardown sets the
                // flag back to false at GeminiLiveSession's `finally`). Only the CURRENT
                // session may speak for the driver-facing flag; a dead one's late emit
                // must not stomp the live one's state in either direction.
                if (session === s) CompanionPhase.setSilenced(silenced)
            }
        }
        return s
    }

    // Status first, then phase: the service renders the overlay on phase changes
    // and reads status.value, so status must already be current when phase emits.
    private fun set(phase: Phase, status: String) {
        _status.value = status
        _phase.value = phase
        // Mirror to the process-global holder so the Cruise screen (Activity) can
        // reflect the live state too, not just the service-drawn floating button.
        // 2026-10-27: WORKING vs THINKING. A typed turn's tool counts only while voice is idle.
        CompanionPhase.set(phase, toolRunning = toolRunningFor(activeToolCalls, typedToolCalls, voiceOwnsPhase()))
    }

    // --- public entry points (service / overlay) -------------------------

    /**
     * Tears down the session still held, if any, without a [LiveEvent.Closed] (so nothing in the
     * Closed branch runs for it), and drops the resumption handle.
     *
     * **Why the handle goes too.** A superseded socket can still be attached on the server for a
     * moment, and a start that resent its handle at once was refused with "BidiGenerateContent
     * session is already connected to an existing client" (A25 prod log, 2026-11-05: superseded at
     * 20:25:38, that refusal at 20:25:42). The next start goes out without one, which costs the
     * superseded socket's continuity and nothing else: every caller here is replacing or ending it.
     */
    private fun supersede(why: String) {
        val old = session ?: return
        android.util.Log.i("LiveSessionController", "superseding the held session: $why")
        old.silentDestroy()
        session = null
        sessionResumeHandle = null
    }

    /**
     * Closes an idle (parked warm) socket so the next line the companion speaks uses the CURRENT
     * voice and car: a socket captures voiceName at start, so after the voice changes (onboarding
     * finish, Settings) or the resolved car changes (an OBD dongle connecting or dropping,
     * `ActiveVehicle.notifyResolutionChanged`) a still-open socket would keep the old one - the
     * field-test "default voice after onboarding" bug.
     *
     * **It does not reopen one (2026-11-05).** It used to prewarm a fresh socket straight after,
     * past any idle window, which is how a dongle flapping in a parked car kept a voice session
     * open all evening (A25: `obd_disconnected` 20:46:55.718, `session_start` 0.2 s later, closed
     * for client inactivity two minutes on). Every start reads the voice and car itself, so
     * nothing is lost by waiting for the next tap. A conversation, a proactive line still
     * speaking, and a connect in flight are all left alone: each is short-lived and the next
     * socket picks up the change.
     */
    fun refreshIdleVoice() {
        if (destroyed || conversationMode) return
        val s = session ?: return
        if (s.isWarm()) supersede("voice or car changed while idle")
    }

    /**
     * Talk tap (Cruise avatar / Lights Out long-press). A tap during an active
     * conversation stops it; a tap on a warm socket resumes instantly (mic opens,
     * no greeting); otherwise it connects a fresh conversation.
     */
    /**
     * The side key / system assist gesture (ADR 0050's sibling: LEGION as the phone's assistant).
     * Starts a one-shot conversation - see [oneShot]. If a conversation is already listening, it
     * is NOT stopped (which is what a plain [onTap] would do); it just becomes one-shot, so it ends
     * after answering whatever is said next.
     */
    fun onAssistRequest() {
        val s = session
        if (s != null && s.inConversation) {
            oneShotGate.armMidConversation()
            return
        }
        onTap(oneShot = true)
    }

    /**
     * The alarm ring screen's "Brief me" (alfred-clock ticket 07, ADR 0079): one tap opens a
     * conversation whose first turn is the sitrep. A PERSON pressed it, so it is user-initiated in
     * every sense ticket 32 means ("sitreps stay tap only or via voice activation only"); nothing
     * ever calls this unasked. Same guards as [onTap], each refusal said on the strip.
     */
    @Suppress("ReturnCount") // one worded exit per guard, as in onTap
    fun onBrief() {
        val s = session
        if (s != null && s.inConversation) {
            if (!s.sendText(BRIEF_PROMPT)) refuse(VoiceRefusal.SOCKET_GONE)
            return
        }
        dismissAfterTurn = false
        oneShotGate.start(false)
        retriedColdConnectThisTap = false
        if (TelephonyController.isInCall) { refuse(VoiceRefusal.ON_A_CALL); return }
        if (!GeminiKeyProvider.hasKey()) { refuse(VoiceRefusal.NO_KEY); return }
        if (!hasMicPermission()) { refuse(VoiceRefusal.NO_MIC_PERMISSION); return }
        if (!isOnline()) { refuse(VoiceRefusal.OFFLINE); return }
        startConversation(opener = BRIEF_PROMPT)
    }

    @Suppress("ReturnCount") // one early exit per tap state; folding them is riskier than the rule is worth
    fun onTap(fromWakeWord: Boolean = false, oneShot: Boolean = false) {
        // Ticket 11: never let a dismissal armed by a previous turn survive into this one. If the
        // driver tapped stop, or the socket died, between the tool call and TurnComplete, the flag
        // was never consumed - and a stale one would hang up the NEXT conversation the instant the
        // assistant finished its first sentence, which would be indistinguishable from a bug.
        dismissAfterTurn = false
        // Set before anything async can emit MicOpened; a plain tap always clears it.
        oneShotGate.start(oneShot)
        val s = session
        // DIAGNOSTIC (B9/B12, remove once root-caused): entry state on every tap,
        // to catch a tap racing an in-flight proactive speakOnWarm() (session
        // neither inConversation nor isWarm mid-speech gets silently destroyed
        // and cold-restarted below) instead of resuming/opening the mic.
        android.util.Log.d(
            "LiveSessionController",
            "onTap: session=${s != null} inConversation=${s?.inConversation} isWarm=${s?.isWarm()}"
        )
        // A tap while Alfred speaks interrupts him so Kevin can talk (Kevin, 2026-11-10, ADR 0081),
        // through ADR 0064's own interrupt. The Home button is what ends the conversation now.
        if (s != null && s.inConversation && s.interruptByTap()) return
        // Otherwise a tap still ends an active conversation.
        if (s != null && s.inConversation) {
            s.stop()
            // Says which of the two things a tap can do actually happened. [Phase.IDLE] alone does
            // not: it is also what a FAILED tap leaves behind, and the strip reads "Tap to talk"
            // either way. Kevin's 24-connects-no-turns day is consistent with exactly this loop -
            // tap, nothing audible, tap again and silently end the session that was live, tap
            // again and pay for another connect.
            refuse(VoiceRefusal.ENDED_ACTIVE_CHAT)
            return
        }

        retriedColdConnectThisTap = false

        // Fast-fail with a visible reason instead of a silent 15s connect attempt.
        // Connected calls block a turn - the call owns the speakers. A RINGING phone does NOT
        // (2026-08-21): isInCall used to be set true on RINGING, so the one moment Kevin wants to
        // say "answer it" was the moment this returned early and showed "ON A CALL". Answering by
        // voice is impossible without this distinction.
        if (TelephonyController.isInCall) { refuse(VoiceRefusal.ON_A_CALL); return }
        // BYO-key only, no tiers (commercial model retired 2026-07-31).
        if (!GeminiKeyProvider.hasKey()) { refuse(VoiceRefusal.NO_KEY); return }
        // Added 2026-09-07, and it is a spend fix as much as an honesty one. Without it a tap with
        // RECORD_AUDIO revoked opened a socket, paid for the whole setup prompt (~16k tokens, see
        // `LiveSetupPayloadSizeTest`), connected, opened the mic, and only THEN found out
        // in [GeminiLiveSession.micLoop] - which closes with "microphone permission not granted" and
        // lands as a notice at the [LiveEvent.Closed] branch below. The answer was right and it cost
        // a connect to reach; [com.kevin.legion.ui.assistant.AssistantStrip] already checks this
        // before its own tap, but the wake word and the Android Auto voice button both call straight
        // in here and never did. Every path through [onTap] ends with the mic opening, so this is
        // safe to refuse on unconditionally - the speak-only proactive path does not come through
        // here.
        if (!hasMicPermission()) { refuse(VoiceRefusal.NO_MIC_PERMISSION); return }
        if (!isOnline()) { refuse(VoiceRefusal.OFFLINE); return }

        if (s != null && s.isWarm()) {
            resumeWarm(s, fromWakeWord)
            return
        }
        // Anything left here is neither in-conversation nor warm - e.g. mid
        // self-intro/proactive speech (speakOnWarm clears both flags before
        // speaking), or still cold-connecting. startConversation's newSession()
        // supersedes it, so a tap never leaves two live sessions running at once.
        startConversation(fromWakeWord)
    }

    /**
     * A TYPED message from the assistant panel. **It goes to a text model, never to the Live
     * session** (Kevin, 2026-10-27, superseding web-assistant ticket 06's muted-Live-turn design):
     * no cold connect, no microphone or wake-word side effect, no follow-up window. [TypedChat] and
     * the pure [TypedTurnPolicy] decide everything; this only forwards.
     *
     * **Typed during a live voice conversation:** answered in text, and the voice session is left
     * exactly as it was - speech keeps playing, the mic keeps its state, the follow-up window keeps
     * its own clock, and the phase is not touched ([typedPhase]). The text model sees the spoken
     * lines through the panel history; the Live session does not see the typed exchange.
     *
     * **Opens no Live socket** (2026-11-05): typing needs none, and every connect re-sends the
     * whole setup payload.
     *
     * **There is deliberately no microphone check here.** Every message that is not sent says so
     * in the panel, in words.
     */
    fun onTyped(text: String) {
        // Typed questions get the same cards as voice (Kevin, 2026-11-10, ADR 0081).
        ConversationCards.beginTyped()
        typedAudit.user(text)
        typedChat.onTyped(text)
    }

    /**
     * The strip's phase while a typed turn is answered: THINKING, but only when nothing voice-side
     * owns the phase (idle, no conversation). Restored to IDLE only if it is still the THINKING this
     * set. A voice conversation's LISTENING/SPEAKING is never stomped by a typed turn.
     */
    private fun typedPhase(inFlight: Boolean) {
        if (voiceOwnsPhase()) return
        if (inFlight && _phase.value == Phase.IDLE) {
            set(Phase.THINKING, "...")
        } else if (!inFlight && _phase.value == Phase.THINKING && pendingAction == Pending.NONE) {
            set(Phase.IDLE, IDLE_STATUS)
        }
    }

    /** A voice conversation or a voice tool call owns the strip's phase; typed chat must not touch it. */
    private fun voiceOwnsPhase(): Boolean =
        conversationMode || session?.inConversation == true || activeToolCalls > 0

    /**
     * A typed turn started a tool: show "Working..." (Matrix `> WORKING`) exactly as a voice tool
     * does, but only over typed chat's own THINKING. A live voice conversation's phase is left alone.
     */
    private fun typedToolStarted() {
        typedToolCalls++
        if (typedToolMayRepublish(voiceOwnsPhase(), _phase.value)) set(Phase.THINKING, "Working...")
    }

    /** The typed turn's last tool finished: back to plain THINKING while the reply is written. */
    private fun typedToolFinished() {
        typedToolCalls--
        if (typedToolCalls == 0 && typedToolMayRepublish(voiceOwnsPhase(), _phase.value)) {
            set(Phase.THINKING, "...")
        }
    }

    /** "New conversation": clears the panel and ends the session so the model's context is fresh too. */
    fun newConversation() {
        AssistantChatStore.clear()
        TypedTurnRecorder.newConversation()
        sessionResumeHandle = null
        pendingThreadLossNotice = false
        // A fresh conversation gets a fresh socket on the next tap; none is opened here
        // (2026-11-05). supersede() skips Closed, so the controller state a Closed would reset is
        // reset here.
        // The Home button and "new conversation" both clear the cards, typed or voice (ADR 0081).
        ConversationCards.end()
        if (session != null && !destroyed) {
            supersede("new conversation")
            pendingAction = Pending.NONE
            pendingPrompt = null
            conversationMode = false
            connectedThisSession = false
            set(Phase.IDLE, IDLE_STATUS)
        }
    }

    /** The active companion's name, as stamped on the panel's ASSISTANT lines. */
    private fun speakerName(): String? = CompanionProfile.name(appContext).trim().ifBlank { null }

    /**
     * Tells the person who asked that nothing is going to happen, and why.
     *
     * **The single exit for every refused ask.** Before this existed the guards each decided for
     * themselves whether to say anything: three of them flashed a shouted string, and six more -
     * every refusal on the [requestSpeak] side plus the stop branch of [onTap] - returned in
     * silence. See [VoiceRefusal]'s own doc for what that silence cost.
     *
     * [tellTheUser] is false for a BACKGROUND proactive raise, which nobody asked for: a raise that
     * cannot be spoken must not put an error on screen over whatever the person is actually doing,
     * and [ProactiveDelivery] is the layer that owns whether an unsolicited line is delivered at
     * all. The refusal is still logged with its own sentence, so the reason exists somewhere even
     * when the screen stays quiet. Every DELIBERATE ask - every path through [onTap], and a
     * [requestSpeak] call flagged `userInitiated` - passes true.
     */
    private fun refuse(refusal: VoiceRefusal, tellTheUser: Boolean = true) {
        val text = refusalNotice(refusal)
        android.util.Log.d("LiveSessionController", "refused ($refusal): $text")
        if (tellTheUser) CompanionPhase.showNotice(text)
    }

    /**
     * Whether RECORD_AUDIO is granted RIGHT NOW.
     *
     * Read live rather than cached: a person can revoke it from system Settings at any point while
     * the service keeps running, which is the same "goes stale for reasons outside this app"
     * reasoning [com.kevin.legion.ui.assistant.AssistantStrip] states for re-checking it on
     * ON_RESUME.
     */
    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Whether the device reports an internet-capable network. Deliberately does
     * NOT require NET_CAPABILITY_VALIDATED - head-unit captive-portal validation
     * is flaky, and a false "online" just falls through to the connect path,
     * which surfaces its own notice on failure.
     */
    private fun isOnline(): Boolean {
        val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /**
     * Proactive engine - voice [prompt] once, **no mic**, reusing the warm socket.
     *
     * [listensForReply] is the one exception, and it is narrow on purpose: the line opens the
     * microphone and waits for an answer, because it asked a question the user can act on. Only
     * `incoming_call` sets it (see [ProactiveRaise.listensForReply]); a window opened this way MUST
     * be closed by [stopListening], or it lingers to the idle backstop.
     *
     * **[userInitiated] decides whether a refusal is put on screen (2026-09-07).** Almost every
     * caller is [ProactiveBus], raising a line nobody asked for - those keep the default and stay
     * quiet on screen when they cannot be spoken, because an error banner over whatever the person
     * is doing is worse than a missed nudge, and [ProactiveDelivery] already owns the question of
     * whether an unsolicited line is delivered at all. The two callers where a PERSON pressed
     * something - `ACTION_TEST_SPEAK` from Setup, and the debug `DEBUG_SAY` broadcast - pass true
     * and get the same worded refusal a tap gets. Every branch below now reports; before this,
     * every single one of them dropped the line in silence.
     *
     * **Known gap, NOT fixed here.** [ProactiveBus.speak] chooses spoken-vs-notified BEFORE this
     * runs and records `DELIVERY_SPOKEN` in `proactive_raises` at that moment. When a branch below
     * refuses, that row asserts an outcome that did not happen and no notification is posted in its
     * place - the raise is simply lost. Routing a refusal back to [ProactiveDelivery.notify] is a
     * real fix and a bigger change than this one; it is written down here rather than left to be
     * rediscovered.
     */
    fun requestSpeak(
        prompt: String,
        listensForReply: Boolean = false,
        carriesReadThroughContent: Boolean = false,
        userInitiated: Boolean = false,
        listenHoldMs: Long = 0L,
    ) {
        // Mark BEFORE the line is spoken, and on every branch below, so a reply that arrives fast
        // still lands in a turn already flagged. See GeminiLiveSession.markTurnReadThrough.
        if (carriesReadThroughContent) session?.markTurnReadThrough()
        if (listensForReply) {
            speakAndListen(prompt, carriesReadThroughContent, userInitiated, listenHoldMs)
            return
        }
        val s = session
        val route = LiveSessionLifecycle.speakRoute(
            hasSession = s != null,
            inConversation = s?.inConversation == true,
            warm = s?.isWarm() == true,
            connectQueued = pendingAction != Pending.NONE,
        )
        android.util.Log.d("LiveSessionController", "requestSpeak branch: ${route.label}")
        when {
            // Nothing live: open a short-lived speak-only session for this line. The one door a
            // raise has to a socket (2026-11-05: nothing is held open for it in advance).
            s == null -> startProactive(prompt, userInitiated, carriesReadThroughContent)
            // Mid-conversation: fold the line into the ongoing turn flow (rare).
            // The segment is already active from when the conversation started.
            //
            // sendText returns false rather than throwing when OkHttp's socket is already
            // closing/closed - the same shape handleToolCall's own `sent` check documents. Ignoring
            // that false is how a line vanished with nothing logged and nothing said.
            route == LiveSessionLifecycle.SpeakRoute.FOLD_IN ->
                if (!s.sendText(prompt)) refuse(VoiceRefusal.SOCKET_GONE, userInitiated)
            // A parked warm socket (a conversation's 30 minute backstop): speak on it. This is a
            // proactive line spoken on an already-connected socket - it never fires
            // LiveEvent.Connected.
            route == LiveSessionLifecycle.SpeakRoute.SPEAK_ON_WARM ->
                if (!s.speakOnWarm(prompt)) refuse(VoiceRefusal.SOCKET_GONE, userInitiated)
            // A socket is still connecting with nothing queued on it: speak once it's up, warm.
            // If that connect never lands, the [LiveEvent.Closed] branch reports the dropped
            // prompt - which is what [pendingSpeakUserInitiated] is carried for.
            route == LiveSessionLifecycle.SpeakRoute.QUEUE_ON_CONNECT -> {
                pendingAction = Pending.PROACTIVE_WARM
                pendingPrompt = prompt
                pendingSpeakUserInitiated = userInitiated
            }
            // A connect is in flight for something else (a tap's conversation, another raise).
            // Queuing here used to overwrite its pending action, turning a tap into a spoken line
            // with no microphone. The raise is refused in words instead.
            else -> refuse(VoiceRefusal.CONNECT_IN_FLIGHT, userInitiated)
        }
    }

    /**
     * Whether the [pendingPrompt] currently queued for a PROACTIVE_* action came from a person
     * pressing something ([requestSpeak]'s `userInitiated`) rather than from a background raise.
     *
     * Held on the controller rather than passed along because the moment it is needed - the socket
     * dying before the queued line could be spoken - is handled in the [LiveEvent.Closed] branch,
     * which has no access to the call that queued it.
     */
    private var pendingSpeakUserInitiated = false

    /**
     * [LiveNotes]' sink: a note from outside (a finished Claude session task) reaches the model only
     * while a conversation is open. Read from a background poll, so the session is read once.
     */
    private val noteSink: LiveNotes.Sink = object : LiveNotes.Sink {
        override fun offer(key: String, text: String): Boolean {
            val s = session
            return s != null && s.inConversation && s.offerNote(key, text)
        }

        override fun withdraw(key: String) {
            session?.withdrawNote(key)
        }
    }.also { LiveNotes.register(it) }

    // ADR 0084, "look at this": [session] as seen from the camera's analysis thread, so frames always
    // follow the CURRENT session. Written only by [session]'s setter.
    @Volatile private var lookSession: GeminiLiveSession? = null

    /** [LookController]'s sink: frames into the current conversation, and the button's way in. */
    private val lookSink: LookController.Sink = object : LookController.Sink {
        override fun inConversation(): Boolean = lookSession?.inConversation == true

        override fun sendFrame(jpeg: ByteArray): Boolean = lookSession?.sendVideoFrame(jpeg) == true

        override fun beginByHand(): String? = onLookByHand()
    }.also { LookController.register(it) }

    /**
     * The look button's conversation (ADR 0084, ADR 0035): the same guards and the same door as
     * [onBrief], with [LookRules.HAND_OPENER] as the first turn, so the model gets the trust rules
     * the voice `look` result carries. Joins an open conversation instead of starting one. Returns
     * null when the line went out or a conversation is starting, else the refusal in words.
     */
    @Suppress("ReturnCount") // one worded exit per guard, as in onBrief
    private fun onLookByHand(): String? {
        val s = session
        if (s != null && s.inConversation) {
            return if (s.sendText(LookRules.HAND_OPENER)) null else refusalNotice(VoiceRefusal.SOCKET_GONE)
        }
        dismissAfterTurn = false
        oneShotGate.start(false)
        retriedColdConnectThisTap = false
        val refusal = when {
            TelephonyController.isInCall -> VoiceRefusal.ON_A_CALL
            !GeminiKeyProvider.hasKey() -> VoiceRefusal.NO_KEY
            !hasMicPermission() -> VoiceRefusal.NO_MIC_PERMISSION
            !isOnline() -> VoiceRefusal.OFFLINE
            else -> null
        }
        if (refusal != null) {
            refuse(refusal)
            return refusalNotice(refusal)
        }
        startConversation(opener = LookRules.HAND_OPENER)
        return null
    }

    /** Tears down the active session and the controller's scope. */
    fun destroy() {
        LiveNotes.unregister(noteSink)
        LookController.unregister(lookSink)
        LookController.close()
        destroyed = true
        session?.destroy()
        session = null
        scope.cancel()
    }

    // --- session lifecycle ----------------------------------------------

    /**
     * Ticket 02: reads and clears [pendingThreadLossNotice]. Returns false (and does nothing
     * else) the overwhelmingly common case - no loss to report. Returns true and flashes the
     * on-screen notice the one time it matters: a real conversation's socket died with no
     * resumption handle to carry it forward, so the model is about to answer cold and the
     * driver needs to know that before it does, not discover it mid-reply. On-screen only
     * (Kevin's own precedent, [LiveEvent.Idle]'s backstop notice just below) - this is not
     * spoken because the whole point is the model is NOT continuing the old conversation, so
     * there's no voice turn to fold a spoken aside into without it sounding like it remembers
     * the very thing it just forgot.
     */
    private fun consumeThreadLossNotice(): Boolean {
        if (!pendingThreadLossNotice) return false
        pendingThreadLossNotice = false
        CompanionPhase.showNotice("RECONNECTED - LOST TRACK OF WHAT WE WERE SAYING")
        return true
    }

    /**
     * Resume a warm socket. On the very first session ever (flag in
     * [CompanionProfile]) the companion is asked to introduce itself and begin
     * the conversational setup; every subsequent resume opens the mic immediately
     * with no greeting round-trip.
     */
    private fun resumeWarm(s: GeminiLiveSession, fromWakeWord: Boolean = false) {
        conversationMode = true
        ConversationCards.begin()
        val isFirst = !CompanionProfile.isFirstSessionDone(appContext)
        // Ticket 02: a warm socket that resumes here is a warm-parked conversation (its 30
        // minute backstop) or a socket a proactive line was spoken on. Nothing reopens an idle
        // socket any more (2026-11-05), but a loss flagged by the Closed handler is still
        // reported here, the first place a conversation begins again.
        val lostThread = consumeThreadLossNotice()
        val ok = when {
            isFirst -> {
                set(Phase.THINKING, "...")
                s.beginConversation(firstGreetingOpener(appContext))
            }
            lostThread -> {
                set(Phase.THINKING, "...")
                s.beginConversation(THREAD_LOST_PROMPT)
            }
            // Ticket 10 wanted the wake word acknowledged (a warm socket went straight to a silent
            // LISTENING). Since F2 (voice cost audit 2026-11-10) the acknowledgement is the
            // scripted line, for a tap and the wake word alike, and no generation is asked for.
            else -> {
                set(Phase.LISTENING, "Listening...")
                openListening(s, warm = true)
            }
        }
        if (!ok) {
            // The "warm" socket was actually stale (send no-op'd). Silently tear it
            // down - cancelling its scope drops any Closed event OkHttp may still
            // post, which would otherwise race in and clobber the fresh session we
            // start below - then do a real cold connect instead of sitting silently
            // in LISTENING forever. silentDestroy skips Closed, so close out any
            // dangling segment (e.g. a proactive line that was mid-speech on this
            // socket) before the fresh conversation starts its own.
            supersede("stale warm socket")
            // Ticket 10: a stale warm socket must not swallow the acknowledgement - the
            // driver still spoke, and still heard nothing back.
            startConversation(fromWakeWord)
            return
        }
        // Resuming a warm socket doesn't go through LiveEvent.Connected (it's
        // already connected), so this is the only place a resumed conversation's
        // active talk begins - only once the send actually landed (a stale-socket
        // failure above starts a fresh conversation instead, billed from its own
        // Connected event).
        // Commit the one-time intro flag only after the send actually landed.
        if (isFirst) CompanionProfile.markFirstSessionDone(appContext)
    }

    /**
     * Cold start a hands-free conversation (connect + setup, then greet/listen).
     *
     * On the first-ever session a bundled [firstGreetingOpener] line replaces the
     * normal greeting so the companion says a warm first hello; naming and setup
     * are the onboarding wizard's job, not this greeting. On every subsequent
     * start the normal greeting prompt runs. The first-session flag
     * is committed in [handleEvent] once the socket actually connects, so a
     * failed connection doesn't burn the one-time introduction.
     */
    private fun startConversation(fromWakeWord: Boolean = false, listenOnly: Boolean = false, opener: String? = null) {
        val s = newSession()
        session = s
        pendingAction = Pending.CONVERSATION
        conversationMode = true
        ConversationCards.begin()
        connectedThisSession = false
        coldRetry = { startConversation(fromWakeWord, listenOnly, opener) }
        set(Phase.CONNECTING, "Connecting...")
        scope.launch {
            val connectionMode = resolveLiveConnectionMode()
            if (connectionMode == null) {
                s.silentDestroy(); session = null
                set(Phase.IDLE, IDLE_STATUS)
                // resolveLiveConnectionMode() returns null for exactly one reason - no key saved -
                // so this says the same thing onTap's own hasKey() guard does. It is reachable only
                // when the key is cleared between that guard and this coroutine actually running.
                refuse(VoiceRefusal.NO_KEY)
                return@launch
            }
            val base = brain.buildBaseInstruction()
            val live = brain.buildLiveContext()
            val isFirst = !CompanionProfile.isFirstSessionDone(appContext)
            // Ticket 02: a lost thread takes priority over the ordinary greeting - the driver
            // and the model both need to know this is a fresh start, not a continued chat.
            // consumeThreadLossNotice() also flashes the on-screen notice as a side effect.
            val lostThread = consumeThreadLossNotice()
            // Voice cost audit 2026-11-10, F2 (Kevin: "drop the spoken greeting and wake reply"):
            // a tap or the wake word no longer asks the model to speak. GREETING_PROMPT and
            // WAKE_ACK_PROMPT cost a full billed generation (setup re-billed) before he said a word
            // and kept the mic shut until it finished. The phone plays a scripted line instead
            // (ListenCue) and the mic opens behind it. First run, a lost thread and Brief me still
            // speak first: each is something he genuinely needs told, or asked for. The live
            // context rides in the system instruction now ([LiveOpenContext]), not the opener.
            pendingPrompt = when {
                // A reconnect inside a running conversation (turn detector fallback): just listen.
                listenOnly -> null
                opener != null -> opener
                isFirst -> firstGreetingOpener(appContext)
                lostThread -> THREAD_LOST_PROMPT
                else -> null
            }
            s.start(
                LiveOpenContext.systemInstruction(base, live), LiveToolbox.declarations(),
                vad = true, voiceName = CompanionProfile.voice(appContext),
                keepWarm = true, connectionMode = connectionMode,
                resumeHandle = sessionResumeHandle,
            )
            // The scripted line: at once (AT_TAP, overlapping the connect) or once the socket is up
            // (ON_CONNECT, never promising a socket that then fails). See [CUE_TIMING].
            val wantsCue = pendingPrompt == null && !listenOnly
            cueOnConnect = wantsCue && CUE_TIMING == CueTiming.ON_CONNECT
            if (wantsCue && CUE_TIMING == CueTiming.AT_TAP) openListening(s)
        }
    }

    /**
     * Opens the floor for Kevin with a scripted line instead of a model generation (F2). Cold: the
     * line plays on [s]'s own track during the connect, and [GeminiLiveSession.beginConversation]
     * opens the mic behind it once the socket is up. Warm: both happen now.
     *
     * Phase 2 seam (Kevin 2026-11-10: "we offload as much as we can to the phones own speech
     * recognizer"): this is the one place a tap or wake word turns into "listening". A local
     * recognizer can take over here: play the same [ListenCue] through its own player, capture and
     * buffer the utterance on the phone, claim it if it matches a local intent, and only otherwise
     * open Live and hand the buffered audio over as the first turn, so Kevin never repeats himself.
     * [ListenCue] needs no socket for that.
     */
    private fun openListening(s: GeminiLiveSession, warm: Boolean = false): Boolean {
        val cue = ListenCue.next(appContext, CompanionProfile.voice(appContext))
        return if (warm) {
            s.beginConversation(null, cue)
        } else {
            s.playListenCue(cue)
            true
        }
    }

    /**
     * Whether the microphone is currently open because a raise asked something - as opposed to
     * because the user tapped. [stopListening] only tears down a window this opened, so a real
     * conversation the user started is never cut off by a phone that stopped ringing.
     */
    @Volatile private var ringListening = false

    /**
     * Speaks [prompt] AND opens the microphone, so the user can answer out loud.
     *
     * Structurally this is [beginConversation] with a supplied opener instead of a greeting:
     * `vad = true` and `keepWarm = true`, which is what actually opens the mic - `startProactive`'s
     * `vad = false` is why every other proactive line cannot be replied to.
     *
     * **It refuses while a conversation is already running.** Folding a ring announcement into a
     * live turn would be fine, but tearing one down to open this window would take the microphone
     * away from someone mid-sentence to tell them the phone is ringing, which they can already
     * hear.
     */
    private fun speakAndListen(
        prompt: String,
        carriesReadThroughContent: Boolean = false,
        userInitiated: Boolean = false,
        listenHoldMs: Long = 0L,
    ) {
        val existing = session
        if (existing != null && existing.inConversation) {
            // Already listening - fold the line in and let the open mic do its job.
            if (!existing.sendText(prompt)) refuse(VoiceRefusal.SOCKET_GONE, userInitiated)
            return
        }
        if (!GeminiKeyProvider.hasKey() || !isOnline()) {
            // No socket is possible, so nothing is half-opened here.
            //
            // **CORRECTED 2026-09-07.** This comment used to read "so say nothing rather than
            // half-opening a window. The notification fallback in ProactiveDelivery is what carries
            // the raise in this case." The first clause is right; the second was not true.
            // [ProactiveBus.speak] picks spoken-vs-notified BEFORE emitting and only calls
            // [ProactiveDelivery.notify] on the branch it did NOT emit on - so a raise that got
            // this far had already been recorded as SPOKEN and there is no fallback behind it. The
            // line is lost. It is at least reported now, and on screen when a person asked for it.
            refuse(if (!GeminiKeyProvider.hasKey()) VoiceRefusal.NO_KEY else VoiceRefusal.OFFLINE, userInitiated)
            return
        }
        // newSession() supersedes whatever [existing] is (a proactive line, a connect in flight).
        val s = newSession()
        session = s
        // The mark in requestSpeak landed on the PREVIOUS session (or on nothing, cold). This one
        // is the session that will actually carry the turn.
        if (carriesReadThroughContent) s.markTurnReadThrough()
        if (listenHoldMs > 0L) s.holdListenWindow(listenHoldMs)
        ringListening = true
        pendingAction = Pending.PROACTIVE_COLD
        pendingPrompt = prompt
        conversationMode = true
        ConversationCards.begin()
        connectedThisSession = false
        scope.launch {
            val connectionMode = resolveLiveConnectionMode()
            if (connectionMode == null) {
                s.silentDestroy(); session = null; ringListening = false
                refuse(VoiceRefusal.NO_KEY, userInitiated)
                return@launch
            }
            val base = brain.buildBaseInstruction()
            s.start(
                base, LiveToolbox.declarations(),
                vad = true, voiceName = CompanionProfile.voice(appContext),
                keepWarm = true, connectionMode = connectionMode,
                resumeHandle = sessionResumeHandle,
                // Ticket 05 (`.scratch/wake-word/issues/05-mic-ownership.md`): this window is
                // the RING_LISTENING claimant, not an ordinary LIVE_TURN - it can be preempted
                // by a real conversation the user starts, and it should never itself refuse to
                // yield the way a tapped conversation must.
                micClaimant = MicArbiter.Claimant.RING_LISTENING,
            )
        }
    }

    /**
     * Closes a microphone window opened by [speakAndListen] - the phone stopped ringing, so the
     * question it asked is moot.
     *
     * **Only closes a window this opened.** If the user tapped and is mid-conversation, that is
     * theirs and a caller hanging up must not end it. The [ringListening] flag is the whole
     * difference, and it is why this is not simply `session?.stop()`.
     *
     * The user answering by voice ends the window through this same path: the call goes OFFHOOK,
     * `TelephonyController` sees ringing stop, and the mic closes because the call now owns the
     * speakers. A half-spoken confirmation can be cut off by that, which is the right trade - the
     * call connecting is the answer.
     */
    fun stopListening() {
        if (!ringListening) return
        ringListening = false
        session?.let { if (it.inConversation) it.stop() }
    }

    /** Cold start a speak-only proactive session (no warm socket existed). */
    private fun startProactive(prompt: String, userInitiated: Boolean = false, readThrough: Boolean = false) {
        val s = newSession()
        session = s
        // The mark in requestSpeak landed on no session (cold). Mark the one that carries the line,
        // or a read-through raise's words (the wake-up brief's newsletters, ADR 0082) reach the
        // conversation and memory audits unredacted - the same re-mark speakAndListen does.
        if (readThrough) s.markTurnReadThrough()
        pendingAction = Pending.PROACTIVE_COLD
        pendingPrompt = prompt
        pendingSpeakUserInitiated = userInitiated
        conversationMode = false
        connectedThisSession = false
        scope.launch {
            val connectionMode = resolveLiveConnectionMode()
            if (connectionMode == null) {
                s.silentDestroy(); session = null
                refuse(VoiceRefusal.NO_KEY, userInitiated)
                return@launch
            }
            val base = brain.buildBaseInstruction()
            s.start(
                base, LiveToolbox.declarations(),
                vad = false, voiceName = CompanionProfile.voice(appContext),
                keepWarm = false, connectionMode = connectionMode,
                resumeHandle = sessionResumeHandle,
            )
        }
    }

    /**
     * The panel's half of a socket closing: marks a voice conversation ended (what was said stays
     * readable). Typed turns never ride this socket, so there is no queued typed message to fail
     * here; [ChatTranscript.sessionEnded] adds nothing when no spoken line was added since the
     * last end, so a speak-only socket closing under a typed-only chat leaves it alone.
     */
    private fun closeOutTypedChat() {
        AssistantChatStore.update { it.sessionEnded() }
    }

    private fun handleEvent(event: LiveEvent) {
        when (event) {
            is LiveEvent.Connected -> {
                connectedThisSession = true
                when (pendingAction) {
                    Pending.CONVERSATION -> {
                        // Commit the first-session flag only on a successful
                        // connect, so a tap that fails (no network, bad key)
                        // doesn't suppress the one-time intro.
                        CompanionProfile.markFirstSessionDone(appContext)
                        val held = session
                        if (cueOnConnect && pendingPrompt == null && held != null) {
                            cueOnConnect = false
                            openListening(held, warm = true)
                        } else {
                            session?.beginConversation(pendingPrompt)
                        }
                        // A first-run or lost-thread line is about to be spoken; otherwise the
                        // scripted line is draining and MicOpened flips this to LISTENING (F2).
                        set(Phase.THINKING, "...")
                    }
                    Pending.PROACTIVE_COLD -> {
                        pendingPrompt?.let { session?.sendText(it) }
                        set(Phase.IDLE, IDLE_STATUS)
                    }
                    Pending.PROACTIVE_WARM -> {
                        pendingPrompt?.let { session?.speakOnWarm(it) }
                        set(Phase.IDLE, IDLE_STATUS)
                    }
                    // Nothing opens a socket with nothing to do any more (2026-11-05); kept so the
                    // `when` stays exhaustive and a stray connect leaves the strip honest.
                    Pending.NONE -> set(Phase.IDLE, IDLE_STATUS)
                }
                pendingAction = Pending.NONE
                pendingPrompt = null
            }
            // Ticket 17: the 8 s follow-up window lapsed. A Closed("stopped") follows at once, which
            // raises no error notice and drops the resume handle; this is the part the person sees
            // and hears, so a conversation never ends by silently disappearing.
            is LiveEvent.FollowUpExpired -> {
                playClosingTone()
                CompanionPhase.showNotice("Conversation closed")
            }
            is LiveEvent.SpeakingStarted -> set(Phase.SPEAKING, "Speaking...")
            is LiveEvent.Interrupted -> set(Phase.LISTENING, "Listening...")
            is LiveEvent.CrisisDetected -> {
                // CLAUDE.md sec 9.1: stop performing the character. Tearing the
                // session down is that rule in code - it's the only way to
                // guarantee Zero says nothing further, since the session's
                // flushAudio only drops locally-queued chunks and the model may
                // still be generating on the wire.
                //
                // silentDestroy, not destroy: destroy emits LiveEvent.Closed with
                // an unrecognised reason, which the Closed branch below would flash
                // to the driver as a fault. A red error banner over a crisis card
                // is the worst possible moment to imply the app broke. Pause the
                // meter by hand since silentDestroy skips the Closed path that
                // normally does it (same reason as refreshIdleVoice).
                session?.silentDestroy()
                session = null
                conversationMode = false
                ConversationCards.end()
                // Ticket 02: a crisis teardown must not silently resume the very
                // conversation the crisis path exists to stop performing - the next chat
                // should start clean, not carry the interrupted turn's context forward.
                sessionResumeHandle = null
                pendingThreadLossNotice = false
                // The panel keeps what was said (the person can still read it) and a voice
                // conversation is marked ended; the crisis card is the response.
                AssistantChatStore.update { it.sessionEnded() }
                CompanionPhase.setCaption("")
                CompanionPhase.setCrisis()
                set(Phase.IDLE, IDLE_STATUS)
            }
            is LiveEvent.SleepPhraseHeard -> {
                // "That will be all" (Kevin, 2026-09-10). ARM, do not fire - the same flag
                // `end_conversation` sets, for the same reason, consumed by the TurnComplete
                // branch immediately below. Setting it here rather than stopping the session
                // is what lets the companion finish its sign-off.
                //
                // Idempotent on purpose: the model may ALSO have called `end_conversation` on
                // this turn, having been told the phrase means exactly that. Two paths, one
                // boolean, one dismissal. Nothing here needs to know which of them ran.
                //
                // Only in conversationMode. The session already gates its emit on `vadMode`,
                // so this is belt-and-braces rather than a second opinion, and it costs a
                // boolean read to be sure a proactive line can never arm a hang-up.
                if (conversationMode) dismissAfterTurn = true
            }
            is LiveEvent.TurnComplete -> {
                // One-shot: the reply to what the user said has just been spoken. Arm the same
                // hang-up "that will be all" uses; the branch below fires it before the mic reopens.
                if (oneShotGate.onTurnComplete(conversationMode)) dismissAfterTurn = true
                // Conversation: the session is ABOUT to reopen the mic, so this is
                // still active talk time and the segment stays open - but it does
                // NOT claim Listening here anymore (2026-08-17, same defect class as
                // 57ed400's Phase.THINKING fix: a phase claiming one thing while the
                // code does another). openMicForUser() has not even run yet at this
                // point, let alone the real AudioRecord.startRecording() behind
                // awaitPlaybackDrained() - up to ~1.56s later. LiveEvent.MicOpened
                // below is the actual signal; leaving the phase alone here means the
                // UI honestly keeps showing "Speaking..."/whatever it last was until
                // the mic is truly live, rather than lying "Listening..." early.
                // Speak-only: the proactive line just finished, nothing more to do -
                // pause the segment here (the socket may not fire a separate Idle for
                // this path, e.g. the cold speak-only session in startProactive).
                if (!conversationMode) {
                    set(Phase.IDLE, IDLE_STATUS)
                } else if (dismissAfterTurn) {
                    // Ticket 11: the sign-off has now actually been spoken. Hang up before the mic
                    // reopens, so the driver is not left with an open session he just dismissed -
                    // and so a dismissed conversation stops billing rather than idling warm.
                    dismissAfterTurn = false
                    session?.stop()
                }
            }
            // The mic has ACTUALLY started capturing - see [LiveEvent.MicOpened]'s doc for
            // why this, not TurnComplete, is what "Listening..." must be driven off. Not
            // gated on conversationMode: a bare tap-to-listen (beginConversation with no
            // opener) also lands here directly from the Connected branch's THINKING state,
            // and this is the only event that would otherwise ever move it off THINKING.
            // PlaybackDrained is the same moment with the mic already open (barge-in): Alfred has
            // finished speaking and is listening, so the phase must say so (A25, 2026-10-27).
            is LiveEvent.MicOpened, is LiveEvent.PlaybackDrained -> {
                oneShotGate.onMicOpened()
                set(Phase.LISTENING, "Listening...")
            }
            is LiveEvent.TurnNotice -> CompanionPhase.showNotice(event.text)
            // No phase change: SpeakingStarted already covers the ordinary half-duplex-mute
            // close (fires effectively simultaneously, off the same server message), and a
            // session-teardown close is about to be followed by its own Idle/Closed event
            // that sets phase correctly. See [LiveEvent.MicClosed]'s doc.
            is LiveEvent.MicClosed -> {}
            // Ticket 02: persist the handle regardless of whether a conversation is even
            // active right now - a parked or speak-only socket can still receive these, and the
            // next real conversation is what benefits.
            is LiveEvent.ResumeHandleUpdated -> sessionResumeHandle = event.handle
            is LiveEvent.Idle -> {
                // Conversation went quiet but the socket is warm - ready for an
                // instant resume on the next tap. Pause billing here too (also
                // reached via parkWarm after a warm-socket proactive line, on top
                // of the TurnComplete pause above - pause() is a no-op if the
                // segment is already closed).
                conversationMode = false
                ConversationCards.endVoice()
                set(Phase.IDLE, IDLE_STATUS)
                // The thirty-minute forgotten-conversation cap is the ONE way a chat
                // ends that the driver did not ask for, so it is the one that has to
                // say so. Everything else reaching here he did himself (tapped to stop)
                // or never started (a proactive line parking its own socket), and
                // narrating those would be noise. On screen only, per Kevin 2026-08-18 -
                // this fires after half an hour of nothing, which is precisely when
                // nobody is listening for a spoken line.
                if (event.backstop) CompanionPhase.showNotice("STOPPED LISTENING - TAP TO TALK")
            }
            is LiveEvent.Subtitle -> {
                _subtitle.tryEmit(event.text)
                // Mirror to the process-global holder so the Cruise screen renders captions too.
                CompanionPhase.setCaption(event.text)
                // The spoken-line audit does NOT live here. Subtitles stream AND are truncated to
                // a tail by captionTail, so this event is right for a caption and wrong for a
                // record. GeminiLiveSession.auditSpokenTurn writes the whole line at turn end.
            }
            // Typed turns no longer ride the Live socket (2026-10-27), so nothing sends one and this
            // event cannot carry a typed reply here. Ignored rather than routed anywhere.
            is LiveEvent.TypedReplyProgress -> Unit
            is LiveEvent.TurnTranscript -> {
                AssistantChatStore.update {
                    it.withSpeaker(speakerName()).turnComplete(event.heard, event.said, event.typed)
                }
            }
            is LiveEvent.ToolCall -> handleToolCall(event)
            is LiveEvent.Closed -> {
                // The one decision about what a close opens next ([LiveSessionLifecycle.afterClose]).
                // Only two answers open a socket, and both serve someone who is talking or just
                // tapped; an unattended close (client inactivity, a proactive line done, a stop)
                // opens nothing (Kevin, 2026-11-05).
                val outcome = LiveSessionLifecycle.afterClose(
                    reason = event.reason,
                    conversationMode = conversationMode,
                    destroyed = destroyed,
                    coldRetryArmed = coldRetry != null && pendingAction == Pending.CONVERSATION,
                    everConnected = connectedThisSession,
                    alreadyRetried = retriedColdConnectThisTap,
                )
                // Silero failed mid-conversation: the session closed itself to come back on server
                // VAD (said in words already, TurnNotice). Resume the same conversation, mic open,
                // no greeting.
                if (outcome == LiveSessionLifecycle.AfterClose.RESUME_CONVERSATION) {
                    session = null
                    startConversation(listenOnly = true)
                    return
                }
                closeOutTypedChat()
                ConversationCards.endVoice()
                val userInitiated = conversationMode
                val everConnected = connectedThisSession
                // Ticket 02: a real conversation just ended for a reason the driver did not
                // ask for, and we hold no handle to carry it forward - that IS the thread
                // dying, distinct from every other close reason this branch already handles.
                // Checked (and flagged, not acted on) here rather than where it's consumed,
                // because this is the only place that still has [event.reason] - by the time
                // resumeWarm/startConversation run, the close that caused this is history.
                if (shouldNotifyThreadLoss(userInitiated, event.reason, sessionResumeHandle != null)) {
                    pendingThreadLossNotice = true
                }
                // The handle rules ([resumeHandleAfterClose]): a deliberate stop, a resume that
                // never connected, and any close of a socket no conversation was using drop it.
                sessionResumeHandle = resumeHandleAfterClose(
                    sessionResumeHandle, event.reason, everConnected, userInitiated,
                )
                // A tap's cold connect that closed before ever connecting, for a reason that is not
                // the user's key, quota or mic, gets ONE silent retry, cold and with no handle
                // (just cleared above). Before this the failure flashed "NO CONNECTION - TAP TO
                // RETRY" and the strip then showed a working conversation under it. The phase
                // stays CONNECTING (startConversation sets it); the notice only appears if this
                // second attempt also fails, because [retriedColdConnectThisTap] is then set.
                val retry = coldRetry
                if (outcome == LiveSessionLifecycle.AfterClose.RETRY_COLD && retry != null) {
                    retriedColdConnectThisTap = true
                    session?.silentDestroy()
                    session = null
                    retry()
                    return
                }
                // Only surface errors the driver kicked off (a tap), not a failed
                // background proactive opener. "stopped"/"idle"/"destroyed"/"warm
                // expired"/"goAway" are normal closes; anything else is a fault worth
                // flashing. "goAway" joins that set because GeminiLiveSession.handleGoAway
                // only ever schedules it as OUR OWN deliberate, planned-ahead close - the
                // driver-facing loss (if any) is what pendingThreadLossNotice surfaces
                // instead, on the next conversation, not here as an error banner.
                if (userInitiated && event.reason !in NORMAL_CLOSE_REASONS) {
                    CompanionPhase.showNotice(
                        when {
                            // KeyHealth is noted by GeminiLiveSession's own onFailure now, WITH
                            // the HTTP status attached. Calling noteInvalid() again here would
                            // overwrite that evidence with a blank detail, leaving the Setup
                            // sentence unable to say what it actually saw.
                            event.reason == "key rejected" -> "KEY PROBLEM - CHECK SETUP"
                            // "TRY AGAIN SOON" until 2026-09-06, which was an assertion the app
                            // had no basis for: a 429 is Gemini's RESOURCE_EXHAUSTED for BOTH a
                            // per-minute rate limit (clears in seconds) and an exhausted quota
                            // (does not clear until the account is topped up). Kevin's key ran out
                            // of credits, and the chip promised him a recovery that was not coming.
                            // KeyHealth is noted by GeminiLiveSession itself now, with the status
                            // attached, so the Setup screen carries the full sentence; this chip
                            // says only what is certainly true and points at it.
                            event.reason == "quota" -> "GEMINI QUOTA OR RATE LIMIT - SEE SETUP"
                            
                            event.reason.contains("microphone", ignoreCase = true) -> "MIC UNAVAILABLE"
                            !everConnected -> "NO CONNECTION - TAP TO RETRY"
                            else -> "CONNECTION LOST - TAP TO RETRY"
                        }
                    )
                }
                // A queued proactive line whose socket died before it could ever be spoken
                // (2026-09-07). The `userInitiated` block above covers Pending.CONVERSATION - those
                // set conversationMode true - so this is only ever the PROACTIVE_* queue, which was
                // cleared four lines below in complete silence. On screen only when a person
                // actually asked for the line; see [refuse].
                if (pendingPrompt != null &&
                    (pendingAction == Pending.PROACTIVE_WARM || pendingAction == Pending.PROACTIVE_COLD)
                ) {
                    refuse(VoiceRefusal.SOCKET_GONE, pendingSpeakUserInitiated)
                }

                session = null
                pendingAction = Pending.NONE
                pendingPrompt = null
                pendingSpeakUserInitiated = false
                conversationMode = false
                connectedThisSession = false
                set(Phase.IDLE, IDLE_STATUS)

                // Nothing is reopened here (Kevin, 2026-11-05: "only open one when you tap or say
                // the wake word"). This block used to prewarm a fresh socket after every close
                // inside a 10 minute window of the last tap or raise; the server closes an idle one
                // for "client inactivity" about every two minutes, so the A25 reconnected all
                // evening and re-sent the ~16k-token setup each time (ticket 24 measured it). A
                // conversation's drop still keeps its handle (above), so the next tap resumes it.
            }
        }
    }

    /**
     * Ticket 11: set by the `end_conversation` tool, consumed at the next [LiveEvent.TurnComplete].
     *
     * **The delay is the entire point.** Stopping the session inside the tool handler would cut the
     * sign-off off mid-word - the model has not spoken it yet when the tool returns. TurnComplete in
     * conversation mode is the moment it has finished speaking and the mic is about to reopen, so it
     * is the only place where "let him finish, then hang up" is true rather than hoped.
     */
    @Volatile private var dismissAfterTurn = false

    /**
     * Ticket 17: the short tone that accompanies "Conversation closed". There was no earcon in the
     * codebase to reuse, so this is the platform's own ACK tone, ~150 ms, on the music stream at
     * low volume. Best-effort: a ToneGenerator that cannot be created is a missing beep, never a
     * reason to fail the close.
     */
    private fun playClosingTone() {
        runCatching {
            val tone = android.media.ToneGenerator(android.media.AudioManager.STREAM_MUSIC, CLOSING_TONE_VOLUME)
            tone.startTone(android.media.ToneGenerator.TONE_PROP_ACK, CLOSING_TONE_MS)
            scope.launch {
                delay(CLOSING_TONE_RELEASE_DELAY_MS)
                tone.release()
            }
        }
    }

    private fun handleToolCall(call: LiveEvent.ToolCall) {
        scope.launch {
            val s = session ?: return@launch
            // The socket goes quiet the instant the model calls a tool - no
            // SpeakingStarted, no Subtitle, nothing - so without this the phase
            // just sat wherever TurnComplete left it (LISTENING/"Listening...")
            // for however long the tool took, INCLUDING an investigate()-backed
            // sub-agent's up-to-30s loop. The driver watched "Listening..." while
            // the app was actually busy. Move to THINKING for the duration.
            activeToolCalls++
            // ADR 0065: a NON_BLOCKING lookup can arrive after its holding line started playing.
            // Leave SPEAKING alone; the count above still makes the strip read WORKING whenever
            // the phase is THINKING, and the restore below already refuses a phase it did not set.
            if (toolStartEntersThinking(s.isNonBlockingTool(call.name), _phase.value)) {
                set(Phase.THINKING, "Working...")
            }
            try {
                // A tool MUST always hand a response back, even on error/timeout, or
                // Gemini stays mid-turn and the UI wedges. [runToolBounded] bounds every tool.
                // Ticket 21 (google-account-integration): s.readThroughToolTouchedThisTurn() is what
                // `remember`'s dispatch branch gates on - read here, off the live session, rather
                // than dispatch reaching back into GeminiLiveSession itself.
                // Round 3: a sub-agent behind this call that got web text back (ask_fleet's
                // web_lookup) notes the mark; the turn turns read-through before the answer is sent.
                val webRead = WebReadMark()
                val response: JSONObject = withContext(webRead) {
                    runToolBounded(call.name, call.args, s.readThroughToolTouchedThisTurn(), s.userTextThisTurn())
                }
                if (webRead.touched) s.markToolResultReadThrough(call.name)
                // The panel's tool line: only for a tool that came back with an explicit `success`
                // (see [ChatTranscript.tool]) - a result that says nothing about success gets no
                // line rather than a "done" nobody checked. Failure detail is the tool's own words.
                if (response.has("success")) {
                    val ok = response.optBoolean("success", false)
                    val detail = if (ok) null else response.optString("message").takeIf { it.isNotBlank() }
                    AssistantChatStore.update { it.tool(call.name, ok, detail) }
                }
                // Ticket 23: this turn's TOOL_RESULT audit row. See [auditToolCall].
                auditToolCall(call.name, call.args, response, s.currentTurnSeq())
                // Sending can throw if the socket died mid-tool; the close path handles
                // recovery, so don't let it crash this scope. A THROWN exception isn't the
                // only failure shape though: OkHttp's WebSocket.send returns false (never
                // throws) when the socket is already closing/closed, so a stalled tool call
                // that finally resolves into a dead socket used to vanish in total silence -
                // no exception, no log, nothing for the driver to see or retry. Treat that
                // false the same as a real failure.
                try {
                    val sent = s.sendToolResponse(call.id, call.name, response)
                    if (!sent) {
                        android.util.Log.w(
                            "LiveSessionController", "sendToolResponse dropped (socket closed): ${call.name}",
                        )
                        // Only a driver-initiated conversation gets a visible notice - a
                        // background proactive turn has no tool calls to begin with, but stay
                        // consistent with the same userInitiated rule the Closed branch uses.
                        if (conversationMode) {
                            CompanionPhase.showNotice("CONNECTION LOST - TAP TO RETRY")
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.w("LiveSessionController", "sendToolResponse failed: ${e.message}")
                }
            } finally {
                // Gemini can emit several functionCalls in one turn, each running
                // through its own scope.launch of this function, so only the LAST
                // one finishing (the count reaching zero) may restore the phase -
                // otherwise the first tool to finish would drop the UI out of
                // THINKING while a sibling call is still mid-flight. And restore
                // only if nothing ELSE has moved the phase since (the model may
                // already be speaking, or the socket may have closed) - a stale
                // restore here would stomp a state a raced event already set.
                activeToolCalls--
                if (shouldRestoreAfterToolCall(activeToolCalls, _phase.value)) {
                    if (typedChat.inFlight && !conversationMode) {
                        // A typed turn's reply is still being written: not idle, not listening.
                        set(Phase.THINKING, "...")
                    } else if (conversationMode) {
                        set(Phase.LISTENING, "Listening...")
                    } else {
                        set(Phase.IDLE, IDLE_STATUS)
                    }
                }
            }
        }
    }

    /** The per-tool ceiling: the investigating specialists get a longer leash than plain tools. */
    private fun toolTimeoutFor(name: String): Long = when (name) {
        in SUB_AGENT_TOOLS -> SUB_AGENT_TOOL_TIMEOUT_MS
        BrowserToolbox.TOOL_NAME -> BROWSER_TOOL_TIMEOUT_MS
        // ADR 0086: a send waits up to 8s for Gmail, then up to 8s more to check Drafts.
        EmailToolbox.TOOL_NAME -> EMAIL_TOOL_TIMEOUT_MS
        else -> TOOL_TIMEOUT_MS
    }

    /**
     * [runTool] bounded by [toolTimeoutFor], never throwing: a timeout or a thrown tool comes back
     * as `success: false` with the reason in words. Shared by the voice path ([handleToolCall]) and
     * typed chat ([typedChat]).
     */
    private suspend fun runToolBounded(
        name: String,
        args: JSONObject,
        readThrough: Boolean,
        userText: String? = null,
    ): JSONObject = try {
        val timeoutName = LiveSurface.resolve(name, args).name
        withTimeoutOrNull(toolTimeoutFor(timeoutName)) { runTool(name, args, readThrough, userText) }
            ?: JSONObject()
                .put("success", false)
                .put("message", "That took too long and timed out.")
    } catch (e: Exception) {
        JSONObject().put("success", false).put("message", "Something went wrong running that.")
    }

    /**
     * Runs one tool call: the controller-local tools (the ones that own the session, the activity
     * or a modal) first, then [LiveToolbox.dispatch] for everything else. **The one tool runner**
     * for voice and typed chat alike (ADR 0035: both paths call the same code, so no tool has a
     * second implementation that can drift). Extracted from [handleToolCall] unchanged 2026-10-27.
     */
    private suspend fun runTool(
        wireName: String,
        wireArgs: JSONObject,
        readThrough: Boolean,
        userText: String?,
    ): JSONObject {
        // Live cost diet (2026-11-10): an action tool's call runs as the tool it stands for, so the
        // UI-scoped branches below (show_saved_places, show_agenda_modal...) still catch it. A
        // no-op for a name the session already resolved.
        val (name, args) = LiveSurface.resolve(wireName, wireArgs)
        return LiveSurface.withRule(name, runResolvedTool(name, args, readThrough, userText))
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    private suspend fun runResolvedTool(
        name: String,
        args: JSONObject,
        readThrough: Boolean,
        userText: String?,
    ): JSONObject =
        when (name) {
            // Session/UI-scoped tools the toolbox returns null for - we
            // own the session, the capture controller, and the activity.
            "show_saved_places" -> {
                if (args.optBoolean("visible", true)) openSavedPlaces()
                JSONObject().put("success", true)
            }
            // Ticket 11. Arm, do not fire - see [dismissAfterTurn].
            "end_conversation" -> {
                dismissAfterTurn = true
                JSONObject()
                    .put("success", true)
                    .put("instruction", END_CONVERSATION_INSTRUCTION)
            }
            "import_receipt" -> {
                openPantryImport()
                JSONObject().put("success", true)
            }
            // ADR 0084: the same controller as the Fleet screen's Show button (ADR 0035). Success
            // only once a frame actually went out; typed chat never reaches here (VOICE_SESSION_TOOLS).
            LookRules.TOOL_NAME -> LookController.openForVoice(appContext)
            // Voice-called modals (ADR 0040) - VoiceModalController.show() updates
            // the StateFlow VoiceModalHost is already collecting immediately, and
            // openVoiceModal's startActivity brings the app forward if it was
            // backgrounded. Both run every call, including a repeat one while the
            // app is already on top - VoiceModalPayload's shownAt (see its own doc)
            // is what makes a repeat "show my agenda" re-fire rather than being
            // read as an unchanged value.
            "show_agenda_modal" -> {
                // Coach voice fixes (2026-11-06): "No, what's on DA?" (Day A) opened the agenda.
                val refusal = CoachRouting.agendaRefusal(userText)
                if (refusal != null) {
                    JSONObject().put("success", false).put("message", refusal)
                } else {
                    VoiceModalController.show(VoiceModalTarget.AGENDA)
                    openVoiceModal()
                    JSONObject().put("success", true)
                }
            }
            // "show_list_modal" retired (one-today ticket 10 slice C, "everything is
            // a checklist now") - VoiceModalTarget.WHOLE_LIST/`ui/NotesScreen.kt`
            // went with it. A model that still calls it by name falls through to
            // the `else` branch below, where LiveToolbox.dispatch's own explicit
            // retired-tool branch says so in words rather than silently no-opping
            // (§7) - same shape "show_groceries_modal" already established below.
            // "show_groceries_modal" retired (one-today ticket 10 slice B, "everything
            // is a checklist now") - VoiceModalTarget.GROCERIES/GroceryScreen went
            // with it. A model that still calls it by name falls through to the
            // `else` branch below, where LiveToolbox.dispatch's own explicit retired-
            // tool branch says so in words rather than silently no-opping (§7).
            // A generated view (`.scratch/one-today/issues/06-*.md`) - the one
            // tool in this `when` that actually computes something before it can
            // answer, because "runs a real query and never renders a model value"
            // has to happen SOMEWHERE, and every other branch here is a pure
            // session-scoped side effect with nothing to validate first. Parse ->
            // run -> show, in that order; a parse or run failure never calls
            // GeneratedViewController.show at all, so the screen only ever paints
            // a genuinely-run answer, never a placeholder for one that failed.
            "show_generated_view" -> {
                when (val parsed = parseGeneratedViewSpec(
                    shape = args.optString("shape"),
                    source = args.optString("source"),
                    aggregation = args.optString("aggregation"),
                    window = args.optString("window"),
                    grouping = args.optString("grouping"),
                    title = args.optString("title"),
                )) {
                    is GeneratedViewSpecParse.Invalid -> {
                        JSONObject().put("success", false).put("message", parsed.reason)
                    }
                    is GeneratedViewSpecParse.Valid -> {
                        when (val run = GeneratedViewQueryRunner.run(appContext, parsed.spec)) {
                            is GeneratedViewQueryRunner.RunResult.Refusal -> {
                                JSONObject().put("success", false).put("message", run.reason)
                            }
                            is GeneratedViewQueryRunner.RunResult.Rendered -> {
                                GeneratedViewController.show(run.payload)
                                openVoiceModal()
                                JSONObject().put("success", true)
                            }
                        }
                    }
                }
            }
            // Ticket 21 (google-account-integration): readThrough
            // is what `remember`'s dispatch branch gates on - see that accessor's doc
            // for why the flag is read here, off the live session, rather than dispatch
            // reaching back into GeminiLiveSession itself.
            else -> {
                val startedAt = System.currentTimeMillis()
                val out = LiveToolbox.dispatch(appContext, name, args, readThrough, userText)
                    ?: JSONObject().put("success", true)
                // A tool inside a dispatcher can put up the codes card (2026-11-03); bring the
                // app forward for it the way show_agenda_modal does, or it only "pops up" the
                // next time the app is opened.
                if (modalShownSince(VoiceModalController.current.value, startedAt)) openVoiceModal()
                out
            }
        }

    /**
     * Ticket 23 (hands-and-senses, "an audit trail of every conversation and every tool call"):
     * every tool call gets its own [ConversationAudit] row - name, arguments, and the result that
     * actually came back, tagged with [turnSeq] so it regroups with that turn's USER/COMPANION rows.
     *
     * **Per-call redaction, not whole-turn**: a TOOL_RESULT row carries its own tool name, so a
     * `list_vehicles` result beside an `ask_mail` one can be told apart precisely. Reuses
     * [GeminiLiveSession.isEpisodicExcludedTool], the exact production membership test against
     * [LiveToolbox.EPISODIC_EXCLUDED_TOOLS], so this never drifts into a second notion of
     * read-through (ticket 23 decision 2). Shared by voice and typed chat.
     *
     * Best-effort and fire-and-forget: an audit write must never delay or fail a real tool response.
     */
    private fun auditToolCall(name: String, args: JSONObject, response: JSONObject, turnSeq: Long) {
        val toolRedacted = GeminiLiveSession.isEpisodicExcludedTool(name)
        val vehicleId = ActiveVehicle.current(appContext)
        scope.launch {
            runCatching {
                CarDatabase.getDatabase(appContext).conversationAuditDao().record(
                    turnSeq = turnSeq,
                    kind = ConversationAudit.Kind.TOOL_RESULT,
                    content = auditContent(response.toString(), toolRedacted),
                    toolName = name,
                    args = EmailToolbox.redactArgs(name, args.toString()),
                    redacted = toolRedacted,
                    vehicleId = vehicleId,
                    // Retention must not delete a row the server has not confirmed - see
                    // ConversationAuditDao.trimUploadedOlderThan. A TOOL_RESULT row is the single
                    // most expensive kind to lose: the only record of what a tool was asked and
                    // what it answered (ticket 20).
                    uploadedThroughId =
                        com.kevin.legion.backend.conversationAuditUploadedThroughId(appContext),
                )
            }
        }
    }

    // These three used to startActivity a dedicated orphan Activity each
    // (SavedPlacesActivity/LedgerImportActivity/PantryImportActivity, all
    // deleted - ticket 07 resolution §5). Their content now lives inside
    // MainActivity's single NavHost, so a voice tool lands there instead,
    // carrying the target sub-route as an intent extra (see
    // MainActivity.EXTRA_ROUTE's doc comment).

    private fun openSavedPlaces() {
        val intent = Intent(appContext, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_ROUTE, LegionRoute.FLEET_PLACES)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        appContext.startActivity(intent)
    }

    // openLedgerImport() deleted - backend-erp ticket 25 ("statement ingestion leaves the phone
    // entirely"). The `import_statement` voice tool it backed is gone with it.

    private fun openPantryImport() {
        val intent = Intent(appContext, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_ROUTE, LegionRoute.MONEY_PANTRY_IMPORT)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        appContext.startActivity(intent)
    }

    /**
     * Foregrounds [MainActivity] with NO [MainActivity.EXTRA_ROUTE] - unlike [openSavedPlaces]/
     * [openLedgerImport]/[openPantryImport], a voice-called modal does not navigate anywhere; it
     * paints over whatever destination is already showing (see [VoiceModalHost]). The actual
     * modal state was already set by the direct [VoiceModalController.show] call at the tool-call
     * site above - this only handles the case where the app was backgrounded and needs bringing
     * forward to see it. If the app is already foregrounded this is a harmless no-op
     * [android.app.Activity.onNewIntent] redelivery.
     */
    private fun openVoiceModal() {
        val intent = Intent(appContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        appContext.startActivity(intent)
    }

    /**
     * Every reason a DELIBERATE ask - a tap on the strip, a wake word, the Android Auto voice
     * button, a "test voice" press - ends with the assistant saying nothing.
     *
     * **Why this exists (2026-09-07).** `live_connect_day` recorded 24 Live connects in one day
     * with zero of them carrying a turn, while Kevin's own account of the same day was "I
     * couldn't connect to voice" and his conclusion was that his Gemini credits had run out.
     * They had not. Every guard below [onTap] was doing its job; several of them said nothing at
     * all on the way out, and the ones that did spoke into
     * [CompanionPhase.notice] while it had no collector (see that flow's own doc for the
     * `replay = 0` half of the same defect).
     *
     * CLAUDE.md sec 7 forbids the assistant asserting an outcome it did not observe. This is the
     * mirror image and costs exactly as much: **the app asserted nothing, and the person
     * reasonably concluded something false.** A refused tap is a fact the app knows and the
     * person cannot see.
     *
     * An enum rather than a string literal at each call site so [refusalNotice] can be a pure
     * function a plain JVM test walks exhaustively - [LiveSessionController] needs a live
     * Context/GeminiLiveSession/Room to construct at all, the same constraint
     * [resumeHandleAfterClose] and [shouldRestoreAfterToolCall] already live with.
     * `when` over an enum with no `else` is also what makes a NEW guard added below fail to
     * compile until somebody writes its sentence.
     */
    internal enum class VoiceRefusal {
        /** The tap stopped a conversation that was running, rather than starting one. */
        ENDED_ACTIVE_CHAT,

        /** A call is connected and owns the speakers. A RINGING phone is NOT this - see
         *  [onTap]'s own comment for why answering by voice depends on that distinction. */
        ON_A_CALL,

        /** No BYO Gemini key is saved, so no socket can be opened at all. */
        NO_KEY,

        /** RECORD_AUDIO is not granted. Checked BEFORE a socket is opened as of 2026-09-07 -
         *  see [onTap]. */
        NO_MIC_PERMISSION,

        /** The device reports no internet-capable network. */
        OFFLINE,

        /** There was a socket, and it was already gone by the time we tried to speak on it. */
        SOCKET_GONE,

        /** A connect was already in flight for something else (a tap's conversation, another
         *  raise), so this line had nowhere to go without overwriting it (2026-11-05). */
        CONNECT_IN_FLIGHT,
    }

    companion object {
        private const val IDLE_STATUS = "Tap to talk"

        /**
         * `end_conversation`'s result text. Async-lookups ticket 07 (ticket 04 run 3, F4): "Say one
         * short in-character sign-off" produced the stock "Very good, Sir." the register forbids. It
         * asks for the register's own dry line and names no phrase, because a phrase named here,
         * even as an example, is the one the model reaches for.
         */
        internal const val END_CONVERSATION_INSTRUCTION =
            "Sign off now with one dry line in your own register, about this conversation, then " +
                "stop. The conversation ends when you finish speaking."
        // Ticket 17's closing tone: ToneGenerator volume (0-100), length, and how long to wait
        // before releasing the generator.
        private const val CLOSING_TONE_VOLUME = 50
        private const val CLOSING_TONE_MS = 150
        private const val CLOSING_TONE_RELEASE_DELAY_MS = 500L

        /**
         * The sentence a [VoiceRefusal] puts in front of the person who asked.
         *
         * Sentence case, matching [com.kevin.legion.ui.assistant.AssistantStripResolver]'s own
         * labels, which is where these land - the SHOUTED strings these replaced ("ON A CALL",
         * "NO SIGNAL OUT HERE") were written for the since-deleted Cruise/Lights Out screens.
         *
         * **Each one names what did NOT happen, then why.** "On a call" is a state; "Didn't start -
         * you're on a call" is an outcome plus its reason, and only the second one answers the
         * question the person actually has, which is why nothing happened when they asked.
         */
        internal fun refusalNotice(refusal: VoiceRefusal): String = when (refusal) {
            VoiceRefusal.ENDED_ACTIVE_CHAT -> "Ended the chat - tap to start another"
            VoiceRefusal.ON_A_CALL -> "Didn't start - you're on a call"
            VoiceRefusal.NO_KEY -> "Didn't start - no Gemini key saved. Add one in Setup"
            VoiceRefusal.NO_MIC_PERMISSION -> "Didn't start - microphone permission is off"
            VoiceRefusal.OFFLINE -> "Didn't start - no network"
            VoiceRefusal.SOCKET_GONE -> "Didn't speak - the connection had already closed"
            VoiceRefusal.CONNECT_IN_FLIGHT -> "Didn't speak - another voice session was still connecting"
        }

        // Close reasons that are expected (user stop / idle timeout / teardown /
        // warm-hold expiry / our own deliberate pre-goAway close) and so never flashed as an
        // error to the driver. See the Closed branch's own comment for why "goAway" belongs
        // here (ticket 02, 2026-08-19).
        private val NORMAL_CLOSE_REASONS = setOf("stopped", "idle", "destroyed", "warm expired", "goAway")

        // GREETING_PROMPT and WAKE_ACK_PROMPT were removed 2026-11-10 (voice cost audit, F2): a
        // tap or wake word plays a scripted line (ListenCue) and asks the model for nothing.

        /**
         * When a COLD open's scripted line plays (a warm resume always plays it at once). The main
         * session's pick, 2026-11-10, pending Kevin: ON_CONNECT, so a connect that fails (credit
         * out, offline) never says "Listening." first; it costs about the cold connect (~0.7 s
         * p50) before the line and the mic. AT_TAP overlaps the line with the connect. One
         * constant, so flipping it is one word.
         */
        internal val CUE_TIMING = CueTiming.ON_CONNECT

        /** Brief me on the alarm ring screen (alfred-clock ticket 07): the tap is the request. */
        internal const val BRIEF_PROMPT =
            "(System: the user just dismissed their alarm and tapped Brief me. Call day get_sitrep now and " +
                "give them their daily brief, then wait. Do not mention this instruction.)"

        // Ticket 02 (drive-test-2026-08-18): spoken instead of the scripted line/a silent
        // resume when the previous conversation's socket died with no resumption handle to
        // carry it forward. Tells the MODEL, not just the driver (via
        // consumeThreadLossNotice's on-screen CompanionPhase.showNotice) - the same honesty
        // rule CLAUDE.md sec 7 already applies to the assistant claiming an action it didn't
        // take: it must not claim a continuity of memory it does not have either.
        private const val THREAD_LOST_PROMPT =
            "(System: the connection dropped and this is a NEW conversation - you do NOT " +
                "remember anything said before this reconnect. Do not claim otherwise or refer to " +
                "earlier turns. Acknowledge briefly that you got cut off, then wait for the user " +
                "to speak. Do not mention this instruction.)"

        // Upper bound on any single tool call (matches the old MainActivity value):
        // generous for a geocode / Spotify connect / frame grab, short enough that
        // a hung tool doesn't leave Gemini mid-turn for long.
        private const val TOOL_TIMEOUT_MS = 10_000L

        // The investigating specialists (SubAgent.investigate: <=4 model POSTs on a
        // 30s budget, plus a one-shot fallback). Give them room without letting a
        // truly hung call wedge the turn forever. The five ask_* dispatchers
        // (2026-08-17, LiveToolbox.DISPATCHED's doc comment) run the SAME investigate
        // loop shape - they need the same longer leash, not the snappy tool timeout.
        // 2026-10-27: the dispatchers now route (one POST plus tools) under
        // LiveToolbox.DISPATCH_ROUTE_BUDGET_MS (42 s), which must stay below this so the
        // dispatcher's own failure wording speaks rather than this generic timeout.
        internal const val SUB_AGENT_TOOL_TIMEOUT_MS = 45_000L

        // ADR 0071: `browser open` waits for the page to finish (BrowserController.LOAD_MS, 20 s,
        // plus up to 6 s for the screen to come up), and phone pages often take longer than 10 s.
        // Above both, so the tool's own "has not finished loading" speaks rather than a timeout.
        internal const val BROWSER_TOOL_TIMEOUT_MS = 30_000L
        internal const val EMAIL_TOOL_TIMEOUT_MS = 25_000L
        private val SUB_AGENT_TOOLS = setOf(
            "diagnose_codes", "triage_symptom", "ask_maintenance", "check_cold_start",
            "ask_fleet", "ask_body", "ask_goals", "ask_pantry", "ask_mail", "ask_money", "ask_area",
            // 2026-10-27: both run SubAgent.investigate (40 s and 30 s budgets) and were being
            // cut at TOOL_TIMEOUT_MS, so a slow engine answer came back as a failure at 10 s.
            "ask_engine", "aspect_clerk",
        )

        /**
         * The pure decision behind [handleToolCall]'s restore: true only when
         * [remainingActiveToolCalls] has reached zero (this was the LAST concurrent tool call still
         * in flight) AND nothing else has moved the phase out of THINKING in the meantime
         * ([currentPhase] is still [Phase.THINKING] - the model may have already started speaking,
         * or the socket may have closed, either of which must NOT be stomped by a late tool
         * restoring LISTENING/IDLE over it). On the companion object (not an instance member) and
         * internal, not private, precisely so it needs no [LiveSessionController] instance to call -
         * that class needs a live Context/GeminiLiveSession/Room to construct at all (same
         * constraint [GeminiLiveSessionEpisodicExclusionTest] already documents for a sibling class)
         * - so [LiveSessionControllerToolCallRestoreTest] can assert the refcount/guard logic
         * directly from a plain JVM test instead of only ever exercising it on-device.
         */
        /**
         * Whether the strip shows a tool RUNNING (WORKING) rather than thinking. A voice tool always
         * counts; a typed turn's tool counts only when voice does not own the phase, so typing during
         * a live voice conversation never turns voice's THINKING into WORKING.
         */
        internal fun toolRunningFor(voiceToolCalls: Int, typedToolCalls: Int, voiceOwnsPhase: Boolean): Boolean =
            voiceToolCalls > 0 || (typedToolCalls > 0 && !voiceOwnsPhase)

        /**
         * Whether a typed tool starting or finishing may re-publish the phase: only over typed chat's
         * own THINKING, never while voice owns the phase (LISTENING, SPEAKING, or its own THINKING).
         */
        internal fun typedToolMayRepublish(voiceOwnsPhase: Boolean, currentPhase: Phase): Boolean =
            !voiceOwnsPhase && currentPhase == Phase.THINKING

        internal fun shouldRestoreAfterToolCall(remainingActiveToolCalls: Int, currentPhase: Phase): Boolean =
            remainingActiveToolCalls == 0 && currentPhase == Phase.THINKING

        /**
         * Whether a voice tool starting moves the strip to THINKING ("Working..."). A blocking tool
         * always does: the model has gone quiet until it answers. A NON_BLOCKING lookup (ADR 0065)
         * does too when the user has just finished, but not while the model is SPEAKING its holding
         * line: that would claim it had stopped. A lookup finishing in LISTENING or SPEAKING never
         * moves the phase ([shouldRestoreAfterToolCall] wants THINKING), so it cannot close the mic.
         */
        internal fun toolStartEntersThinking(nonBlocking: Boolean, currentPhase: Phase): Boolean =
            !nonBlocking || currentPhase != Phase.SPEAKING

        /**
         * Ticket 02's pure decision behind the [LiveEvent.Closed] branch's thread-loss flag:
         * true only when all three hold at once - a real conversation was actually running
         * ([wasConversationActive]), it did not end because the driver asked it to
         * ([closeReason] is not `"stopped"`), and there is no session-resumption handle to
         * carry it into whatever reconnects next ([hasResumeHandle] is false). Any one of
         * those failing means either there is nothing to lose (no conversation, or a resume
         * handle already covers it) or the driver already knows (they tapped stop
         * themselves) - both are the ordinary case and must stay silent.
         *
         * On the companion object, not an instance member, for the same reason
         * [shouldRestoreAfterToolCall] is: [LiveSessionController] needs a live Context/
         * GeminiLiveSession/Room to construct at all, so this is the one seam a plain JVM
         * test can exercise directly against the real production decision.
         */
        internal fun shouldNotifyThreadLoss(
            wasConversationActive: Boolean,
            closeReason: String,
            hasResumeHandle: Boolean,
        ): Boolean = wasConversationActive && closeReason != "stopped" && !hasResumeHandle

        /**
         * Whether a [LiveEvent.Closed] should be answered with one silent cold retry instead of a
         * notice. True only for a connect a person asked for ([userInitiated]) that never reached
         * [LiveEvent.Connected] ([everConnected] false), for a reason that is not a normal close
         * and not one a retry cannot fix (a rejected key, quota, a missing microphone), and only
         * if this tap has not already spent its retry ([alreadyRetried]). Pure, so it is directly
         * unit-testable - see [LiveSessionControllerColdRetryTest].
         */
        internal fun shouldRetryColdConnect(
            userInitiated: Boolean,
            everConnected: Boolean,
            reason: String,
            alreadyRetried: Boolean,
        ): Boolean = userInitiated && !everConnected && !alreadyRetried &&
            reason !in NORMAL_CLOSE_REASONS &&
            reason != "key rejected" && reason != "quota" &&
            !reason.contains("microphone", ignoreCase = true)

        /**
         * What becomes of the resumption handle when a socket closes. Three closes drop it:
         *
         * - **"stopped"**: a deliberate stop is not a drop to resume FROM - the next chat should be
         *   a new one, not a silent continuation of the one the user chose to end.
         * - **never connected**: a resume that never connected is a dead handle and must not be
         *   sent again (2026-09-27). The server answers a stale handle with "BidiGenerateContent
         *   session not found" before setup completes, and resending it failed identically on every
         *   retry until the process died (Kevin: "the voice agent says no connection retry? i cant
         *   talk at all"). Dropping it costs one conversation's continuity.
         * - **a socket no conversation was using** ([userInitiated] false: a speak-only proactive
         *   line, a parked socket expiring, a server close for client inactivity). Nothing reopens
         *   on that close any more (2026-11-05), so the handle would wait for the next tap, and in
         *   the A25's 11-01..11-04 logs 10 of 157 setups were exactly that tap sending an idle
         *   chain's handle and being refused ("session not found or expired"), then a second setup:
         *   about a second wasted each time. A conversation's own drop keeps its handle, so the next
         *   tap resumes it - that is the mid-conversation recovery.
         *
         * Pure so [LiveSessionControllerIdleReconnectTest] pins it.
         */
        internal fun resumeHandleAfterClose(
            handle: String?,
            reason: String,
            everConnected: Boolean,
            userInitiated: Boolean,
        ): String? = when {
            handle == null -> null
            reason == "stopped" -> null
            !everConnected -> null
            !userInitiated -> null
            else -> handle
        }
    }
}

/** True when [payload] is a per-car card ([VoiceModalTarget.CODES]) put up at or after [sinceMs]. */
internal fun modalShownSince(payload: VoiceModalPayload?, sinceMs: Long): Boolean =
    payload != null && payload.target == VoiceModalTarget.CODES && payload.shownAt >= sinceMs

/** See [LiveSessionController.CUE_TIMING]. */
internal enum class CueTiming { AT_TAP, ON_CONNECT }

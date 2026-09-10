package com.kevin.legion.service

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.content.ContextCompat
import com.kevin.legion.ai.ActiveCompanionProfile
import com.kevin.legion.ai.AriaBrain
import com.kevin.legion.ai.CompanionProfile
import com.kevin.legion.ai.CompanionProfileStore
import com.kevin.legion.ai.CompanionSwitch
import com.kevin.legion.ai.GeminiKeyProvider
import com.kevin.legion.ai.KeyHealth
import com.kevin.legion.ai.firstGreetingOpener
import com.kevin.legion.car.CarProbeLog
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.CompanionProfileEntity
import com.kevin.legion.data.local.ConversationAudit
import com.kevin.legion.data.local.record
import com.kevin.legion.data.local.auditContent
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
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.UUID

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
 * **Warm sessions (latency):** the WebSocket + setup handshake is the dominant
 * per-conversation latency, so we avoid paying it on every tap. The service
 * [prewarm]s a connected-but-idle session on start; tapping resumes it
 * instantly. After a conversation goes quiet the socket parks *warm* (see
 * [GeminiLiveSession.parkWarm]) rather than closing, so follow-up turns through a
 * drive are instant too; it fully closes only after a few idle minutes, and the
 * next tap lazily reconnects. The session is set up with the cached static
 * instruction ([AriaBrain.buildBaseInstruction]); fresh live context
 * ([AriaBrain.buildLiveContext]) is injected into the greeting at the start of
 * each conversation so it's current without rebuilding the whole prompt per turn.
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
            // A session that is gone cannot still be silenced, and there are ten
            // assignment sites that drop one. Clearing HERE rather than at each of
            // them is what stops a stale `true` outliving the socket that raised it
            // and leaving the strip permanently claiming LEGION is deaf. Whatever
            // session replaces it publishes its own state through [newSession]'s
            // collector.
            if (value == null) CompanionPhase.setSilenced(false)
        }

    // Set once destroy() runs so a final Closed event doesn't re-prewarm a socket
    // on a torn-down controller.
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

    // Consecutive prewarm sockets that never connected (dead zone / bad key). The
    // auto-prewarm after a close backs off exponentially on this so we don't
    // hot-loop failed connects. Reset on any successful connect and on a fresh tap.
    private var consecutivePrewarmFailures = 0

    // Ticket 24 (`.scratch/hands-and-senses/issues/24-the-socket-that-never-rests.md`):
    // wall-clock time of the last genuine signal - a tap ([onTap]), a wake-word trigger (also
    // [onTap], with fromWakeWord=true), or a proactive raise that needed to speak
    // ([requestSpeak]). This is the only input [shouldAutoReconnectAfterClose] reads, and it is
    // what the [LiveEvent.Closed] branch's auto-reconnect is gated on: a server-initiated close
    // (measured at ~153s, unrelated to anything the app does) is only worth immediately
    // reconnecting when something real happened recently. Initialized to construction time so a
    // freshly-started service still gets the ordinary warm behaviour for a while, not an
    // instant cold shoulder.
    private var lastRealInteractionMs = System.currentTimeMillis()

    // What to do once the socket finishes connecting (the setup handshake is
    // async). A warm/prewarmed socket is already connected, so these only matter
    // for a cold connect.
    private enum class Pending { NONE, CONVERSATION, PROACTIVE_COLD, PROACTIVE_WARM }
    private var pendingAction = Pending.NONE
    private var pendingPrompt: String? = null

    // True while a hands-free conversation (server VAD) is running, vs. a
    // speak-only proactive session. Drives whether a completed turn returns to
    // LISTENING (wait for the driver) or IDLE.
    private var conversationMode = false

    // Ticket 02 (drive-test-2026-08-18): the latest session-resumption handle Gemini has
    // confirmed we can reconnect with, threaded into the NEXT [GeminiLiveSession.start] call
    // (prewarm/startConversation). Lives on the controller, not the session, because a
    // [GeminiLiveSession] instance dies with its own socket and this is precisely the thing
    // meant to outlive that. Cleared on a driver-initiated stop (see the Closed branch) so a
    // deliberately ended chat doesn't silently bleed into whatever the driver starts next.
    private var sessionResumeHandle: String? = null

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

    /**
     * Constructs a fresh [GeminiLiveSession] and, alongside the caller's own wiring, starts
     * mirroring its [GeminiLiveSession.isSilenced] transitions to [CarProbeLog] (ticket 15 wave 2's
     * signal, ticket 08's wave 3 consumer - `.scratch/android-auto/issues/15-the-live-session-can-be-silenced.md`).
     * The three construction sites below ([prewarm], [startConversation], [startProactive]) all
     * route through here so the car probe session can see silencing regardless of which door opened
     * the socket, without each call site remembering to wire it separately.
     */
    private fun newSession(): GeminiLiveSession {
        val s = GeminiLiveSession(appContext) { handleEvent(it) }
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
        CompanionPhase.set(phase)
    }

    // --- public entry points (service / overlay) -------------------------

    /**
     * Opens a connected-but-idle (warm) session ahead of the first tap so that
     * tap doesn't pay the connect + setup handshake. Safe/cheap to call when one
     * already exists (no-op). Called by the service on start and again after a
     * session fully closes, so there's normally always a warm socket ready.
     */
    fun prewarm() {
        if (destroyed || session != null) return
        // Only prewarm eagerly on a BYO/dev key. A broker-minted ephemeral token
        // (trial/subscribed) expires in ~6 min server-side, so eagerly opening a
        // warm socket ahead of any tap risks it going stale before the driver
        // ever taps. Trial/sub users pay a real connect handshake on first tap
        // instead (see resolveConnectionMode + onTap/startConversation) - a small
        // latency cost, not a correctness one.
        if (!GeminiKeyProvider.hasKey()) return
        val s = newSession()
        session = s
        pendingAction = Pending.NONE
        conversationMode = false
        connectedThisSession = false
        scope.launch {
            val base = brain.buildBaseInstruction()
            s.start(
                base, LiveToolbox.declarations(),
                vad = true, voiceName = CompanionProfile.voice(appContext),
                keepWarm = true, prewarmOnly = true,
                // Ticket 02: carry forward whatever the last session confirmed - a prewarm
                // that follows a dropped conversation should still be able to resume it.
                resumeHandle = sessionResumeHandle,
            )
        }
    }

    /**
     * Rebuilds the idle warm/prewarm socket so the next line the companion speaks
     * uses the CURRENT voice. [prewarm] captures voiceName at socket start, so after
     * the driver changes voice (onboarding finish, Settings) a still-open warm socket
     * would keep the old voice - the field-test "default voice after onboarding" bug.
     * No-op during an active conversation (never kills a live turn); the fresh prewarm
     * re-reads CompanionProfile.voice. Only BYO keys prewarm eagerly, so this is a
     * no-op for trial/subscribed (whose greet already cold-opens with the current voice).
     */
    fun refreshIdleVoice() {
        if (destroyed || conversationMode) return
        // silentDestroy skips LiveEvent.Closed, so close out any dangling
        // segment here too (e.g. a proactive line still speaking) - otherwise
        // its stuck-open segmentStartMs would inflate the NEXT socket's report.
        session?.silentDestroy()
        session = null
        prewarm()
    }

    /**
     * Talk tap (Cruise avatar / Lights Out long-press). A tap during an active
     * conversation stops it; a tap on a warm socket resumes instantly (mic opens,
     * no greeting); otherwise it connects a fresh conversation.
     */
    fun onTap(fromWakeWord: Boolean = false) {
        // Ticket 24: a tap (or a wake-word trigger, which calls this with fromWakeWord=true) IS
        // the genuine signal [shouldAutoReconnectAfterClose] waits for. Recorded unconditionally,
        // before any of the early returns below, so even a tap that bounces off "ON A CALL" or
        // "NO SIGNAL OUT HERE" still counts as someone being there - the socket staying warm a
        // while longer is the safe direction to be wrong in, not the storm this ticket exists to
        // stop.
        lastRealInteractionMs = System.currentTimeMillis()
        // Ticket 11: never let a dismissal armed by a previous turn survive into this one. If the
        // driver tapped stop, or the socket died, between the tool call and TurnComplete, the flag
        // was never consumed - and a stale one would hang up the NEXT conversation the instant the
        // assistant finished its first sentence, which would be indistinguishable from a bug.
        dismissAfterTurn = false
        val s = session
        // DIAGNOSTIC (B9/B12, remove once root-caused): entry state on every tap,
        // to catch a tap racing an in-flight proactive speakOnWarm() (session
        // neither inConversation nor isWarm mid-speech gets silently destroyed
        // and cold-restarted below) instead of resuming/opening the mic.
        android.util.Log.d(
            "LiveSessionController",
            "onTap: session=${s != null} inConversation=${s?.inConversation} isWarm=${s?.isWarm()}"
        )
        // Always allow stopping an active conversation.
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

        // A deliberate tap resets the prewarm backoff - the driver is actively
        // asking, so try now rather than honoring a long dead-zone cooldown.
        consecutivePrewarmFailures = 0

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
        // [shouldAutoReconnectAfterClose]'s doc), connected, opened the mic, and only THEN found out
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
        // speaking), or still cold-connecting. Tear it down before starting
        // fresh so a tap never leaves two live sessions running at once.
        if (s != null) {
            // silentDestroy skips Closed - close out any dangling segment
            // (e.g. the proactive speech this branch is interrupting) so it
            // doesn't leak into the fresh conversation's own reported total.
            s.silentDestroy()
            session = null
        }
        startConversation(fromWakeWord)
    }

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
    ) {
        // Ticket 24: a proactive raise that needs to speak is the third genuine signal
        // [shouldAutoReconnectAfterClose] recognises, alongside a tap and a wake-word trigger
        // (see [onTap]). It reconnects on its own cold path below regardless of the idle window
        // (startProactive / speakAndListen / the warm/mid-connect branches) - this timestamp only
        // extends how long the socket THEN stays willing to auto-reconnect on its own after that.
        lastRealInteractionMs = System.currentTimeMillis()
        // Mark BEFORE the line is spoken, and on every branch below, so a reply that arrives fast
        // still lands in a turn already flagged. See GeminiLiveSession.markTurnReadThrough.
        if (carriesReadThroughContent) session?.markTurnReadThrough()
        if (listensForReply) { speakAndListen(prompt, carriesReadThroughContent, userInitiated); return }
        val s = session
        android.util.Log.d("LiveSessionController", "requestSpeak branch: ${speakBranchLabel(s)}")
        when {
            // Mid-conversation: fold the line into the ongoing turn flow (rare).
            // The segment is already active from when the conversation started.
            //
            // sendText returns false rather than throwing when OkHttp's socket is already
            // closing/closed - the same shape handleToolCall's own `sent` check documents. Ignoring
            // that false is how a line vanished with nothing logged and nothing said.
            s != null && s.inConversation ->
                if (!s.sendText(prompt)) refuse(VoiceRefusal.SOCKET_GONE, userInitiated)
            // Warm socket already up: speak on it and stay warm. This is a
            // proactive line spoken on an already-connected socket - it never
            // fires LiveEvent.Connected, so start the billing segment here.
            s != null && s.isWarm() ->
                if (!s.speakOnWarm(prompt)) refuse(VoiceRefusal.SOCKET_GONE, userInitiated)
            // A socket is still connecting (prewarm): speak once it's up, warm. If that connect
            // never lands, the [LiveEvent.Closed] branch reports the dropped prompt - which is
            // what [pendingSpeakUserInitiated] is carried for.
            s != null -> {
                pendingAction = Pending.PROACTIVE_WARM
                pendingPrompt = prompt
                pendingSpeakUserInitiated = userInitiated
            }
            // Nothing live: spin up a short-lived speak-only session.
            else -> startProactive(prompt, userInitiated)
        }
    }

    /**
     * DIAGNOSTIC (B9, remove once root-caused): which branch a proactive line takes decides whether
     * the mic reopens after it (only the `inConversation` fold-in branch skips
     * `suppressMicNextTurn`), so a field report of "it listened after proactive speech" should show
     * "fold-in" here.
     *
     * Lifted out of [requestSpeak] 2026-09-07 - purely so that function stays under detekt's
     * cyclomatic-complexity ceiling once every branch of it started reporting its own failures.
     * The four cases and their wording are unchanged.
     */
    private fun speakBranchLabel(s: GeminiLiveSession?): String = when {
        s == null -> "cold startProactive (mic suppressed)"
        s.inConversation -> "fold-in (mic WILL reopen after, by design)"
        s.isWarm() -> "warm speakOnWarm (mic suppressed)"
        else -> "mid-connect PROACTIVE_WARM (mic suppressed)"
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

    /** Tears down the active session and the controller's scope. */
    fun destroy() {
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
        val isFirst = !CompanionProfile.isFirstSessionDone(appContext)
        // Ticket 02: a warm socket that resumes here is either a genuinely warm-parked
        // conversation (the common case - no loss, nothing to say) or a FRESH prewarmed
        // socket that replaced one that died since the driver was last talking (see
        // startConversation's Pending.NONE prewarm auto-reconnect in the Closed handler).
        // consumeThreadLossNotice() tells the two apart the only way that's actually
        // possible from here: whether the Closed handler flagged a real loss.
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
            // Ticket 10: THIS is the branch Kevin heard. A warm socket with nothing lost went
            // straight to LISTENING with a null prompt - silent by construction. Correct for a
            // tap, wrong for a voice trigger, where nothing on screen confirms it heard.
            fromWakeWord -> {
                set(Phase.THINKING, "...")
                s.beginConversation(WAKE_ACK_PROMPT)
            }
            else -> {
                set(Phase.LISTENING, "Listening...")
                s.beginConversation(null)
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
            s.silentDestroy()
            session = null
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
    private fun startConversation(fromWakeWord: Boolean = false) {
        val s = newSession()
        session = s
        pendingAction = Pending.CONVERSATION
        conversationMode = true
        connectedThisSession = false
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
            // Ticket 10: a wake-opened turn acknowledges rather than greets, cold or warm, so the
            // two doors do not sound different for no reason the driver can perceive. First run
            // and a lost thread still win - both are things he genuinely needs told.
            val opener = if (fromWakeWord) WAKE_ACK_PROMPT else GREETING_PROMPT
            pendingPrompt = when {
                isFirst -> firstGreetingOpener(appContext)
                lostThread -> THREAD_LOST_PROMPT
                live.isBlank() -> opener
                else -> "(Current context, use naturally if relevant:\n$live)\n\n$opener"
            }
            s.start(
                base, LiveToolbox.declarations(),
                vad = true, voiceName = CompanionProfile.voice(appContext),
                keepWarm = true, connectionMode = connectionMode,
                resumeHandle = sessionResumeHandle,
            )
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
        existing?.silentDestroy()
        val s = newSession()
        session = s
        // The mark in requestSpeak landed on the PREVIOUS session (or on nothing, cold). This one
        // is the session that will actually carry the turn.
        if (carriesReadThroughContent) s.markTurnReadThrough()
        ringListening = true
        pendingAction = Pending.PROACTIVE_COLD
        pendingPrompt = prompt
        conversationMode = true
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
    private fun startProactive(prompt: String, userInitiated: Boolean = false) {
        val s = newSession()
        session = s
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

    private fun handleEvent(event: LiveEvent) {
        when (event) {
            is LiveEvent.Connected -> {
                connectedThisSession = true
                consecutivePrewarmFailures = 0
                // A fresh connect that's about to actually talk starts the
                // billing segment here; Pending.NONE is a bare prewarm sitting
                // idle (no one spoke), so it must NOT start one.
                if (pendingAction != Pending.NONE) {
                }
                when (pendingAction) {
                    Pending.CONVERSATION -> {
                        // Commit the first-session flag only on a successful
                        // connect, so a tap that fails (no network, bad key)
                        // doesn't suppress the one-time intro.
                        CompanionProfile.markFirstSessionDone(appContext)
                        session?.beginConversation(pendingPrompt)
                        set(Phase.THINKING, "...")   // Zero is about to greet
                    }
                    Pending.PROACTIVE_COLD -> {
                        pendingPrompt?.let { session?.sendText(it) }
                        set(Phase.IDLE, IDLE_STATUS)
                    }
                    Pending.PROACTIVE_WARM -> {
                        pendingPrompt?.let { session?.speakOnWarm(it) }
                        set(Phase.IDLE, IDLE_STATUS)
                    }
                    Pending.NONE -> set(Phase.IDLE, IDLE_STATUS) // warm, ready to tap
                }
                pendingAction = Pending.NONE
                pendingPrompt = null
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
                // Ticket 02: a crisis teardown must not silently resume the very
                // conversation the crisis path exists to stop performing - the next chat
                // should start clean, not carry the interrupted turn's context forward.
                sessionResumeHandle = null
                pendingThreadLossNotice = false
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
                    // Drop any armed handover rather than carrying it. A speak-only proactive turn
                    // is not a conversation anyone can be handed over FROM, and an arm that
                    // survives this branch would sit there until the next real conversation's
                    // first TurnComplete and swap companion out of nowhere. Nothing changing is
                    // the safe direction to be wrong in; a surprise persona swap is not.
                    switchToProfileAfterTurn = null
                    set(Phase.IDLE, IDLE_STATUS)
                } else if (dismissAfterTurn) {
                    // Ticket 11: the sign-off has now actually been spoken. Hang up before the mic
                    // reopens, so the driver is not left with an open session he just dismissed -
                    // and so a dismissed conversation stops billing rather than idling warm.
                    dismissAfterTurn = false
                    // A dismissal outranks a handover. "Put Dorothy on, actually that will be all"
                    // ends the conversation; handing over to somebody the user just dismissed and
                    // leaving them connected would be the worse reading of both instructions.
                    switchToProfileAfterTurn = null
                    session?.stop()
                } else {
                    // The handover line has now actually been spoken, so the socket carrying the
                    // outgoing companion's voice can go. See [switchToProfileAfterTurn].
                    switchToProfileAfterTurn?.let { profileId ->
                        switchToProfileAfterTurn = null
                        performCompanionSwitch(profileId)
                    }
                }
            }
            // The mic has ACTUALLY started capturing - see [LiveEvent.MicOpened]'s doc for
            // why this, not TurnComplete, is what "Listening..." must be driven off. Not
            // gated on conversationMode: a bare tap-to-listen (beginConversation with no
            // opener) also lands here directly from the Connected branch's THINKING state,
            // and this is the only event that would otherwise ever move it off THINKING.
            is LiveEvent.MicOpened -> set(Phase.LISTENING, "Listening...")
            // No phase change: SpeakingStarted already covers the ordinary half-duplex-mute
            // close (fires effectively simultaneously, off the same server message), and a
            // session-teardown close is about to be followed by its own Idle/Closed event
            // that sets phase correctly. See [LiveEvent.MicClosed]'s doc.
            is LiveEvent.MicClosed -> {}
            // Ticket 02: persist the handle regardless of whether a conversation is even
            // active right now - a warm/prewarmed socket idling between chats can still
            // receive these, and the next real conversation is what benefits.
            is LiveEvent.ResumeHandleUpdated -> sessionResumeHandle = event.handle
            is LiveEvent.Idle -> {
                // Conversation went quiet but the socket is warm - ready for an
                // instant resume on the next tap. Pause billing here too (also
                // reached via parkWarm after a warm-socket proactive line, on top
                // of the TurnComplete pause above - pause() is a no-op if the
                // segment is already closed).
                conversationMode = false
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
            is LiveEvent.ToolCall -> handleToolCall(event)
            is LiveEvent.Closed -> {
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
                // A deliberate driver stop is not a drop to resume FROM - the next chat the
                // driver starts should be a new one, not a silent continuation of the one
                // they just chose to end.
                if (event.reason == "stopped") sessionResumeHandle = null
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

                // A prewarm socket (not a conversation) that never connected is a
                // failed connect - escalate the retry backoff.
                if (!everConnected && !userInitiated) consecutivePrewarmFailures++

                session = null
                pendingAction = Pending.NONE
                pendingPrompt = null
                pendingSpeakUserInitiated = false
                conversationMode = false
                connectedThisSession = false
                set(Phase.IDLE, IDLE_STATUS)

                // Re-establish a warm socket so the next tap is instant again, backing off after
                // repeated connect failures (dead zone / bad key) - UNLESS ticket 24's idle window
                // has lapsed, in which case we do nothing here and let the socket stay closed.
                //
                // This is the fix for the measured reconnect storm: the Live server closes an
                // idle prewarmed socket every ~153s on its own (see the ticket - resumption does
                // NOT discount the re-sent setup payload), and reconnecting on every one of those
                // closes forever is what turned an idle phone into ~565 reconnects and ~9.1M
                // estimated input tokens a day with nobody using the app. Past the idle window, a
                // close is left alone; the next tap, wake-word trigger, or proactive raise
                // reconnects instantly anyway through its own cold-start path ([onTap],
                // [startProactive]/[speakAndListen]) regardless of what happens here.
                //
                // The trade, stated rather than buried: the FIRST interaction after a long idle
                // stretch pays a cold connect - measured at roughly a second slower than a warm
                // resume - instead of the instant resume a still-warm socket would have given. That
                // cost is real and is paid once per idle stretch, not 565 times a day.
                if (!destroyed && shouldAutoReconnectAfterClose(lastRealInteractionMs, System.currentTimeMillis())) {
                    val failures = consecutivePrewarmFailures
                    if (failures > 0) {
                        scope.launch {
                            delay((30_000L * (1L shl failures.coerceAtMost(6))).coerceAtMost(300_000L))
                            // Re-check the idle window too, not just destroyed/session==null: the
                            // backoff delay itself can be long enough to carry the last real
                            // interaction outside the window, and a real tap/wake/raise during the
                            // delay already reconnects on its own via its own cold path.
                            if (!destroyed && session == null &&
                                shouldAutoReconnectAfterClose(lastRealInteractionMs, System.currentTimeMillis())
                            ) {
                                prewarm()
                            }
                        }
                    } else {
                        prewarm()
                    }
                }
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
     * Set by the `switch_companion` tool to the profile that should take over, consumed at the next
     * [LiveEvent.TurnComplete] (Kevin, 2026-09-10). Null when no handover is armed.
     *
     * **Armed rather than performed, for [dismissAfterTurn]'s exact reason and one more.** The
     * outgoing companion has not spoken its handover line when the tool returns, and switching
     * inside the handler would cut it off mid-word. The additional reason is structural: the
     * persona clause and the voice are both baked into the socket's setup message and cannot be
     * patched on an open socket, so a switch is a teardown and a fresh connect - roughly a second
     * of dead air that must land AFTER the line, not through it.
     */
    @Volatile private var switchToProfileAfterTurn: String? = null

    /**
     * The `switch_companion` tool (Kevin, 2026-09-10: "hey can i talk to dorothy" switches to
     * dorothy). Resolves the spoken name through [CompanionSwitch] and arms
     * [switchToProfileAfterTurn]; the socket rebuild is [performCompanionSwitch]'s job.
     *
     * **Every failure result says what did NOT happen, in words** (CLAUDE.md sec 7): "Nothing
     * changed" is on both refusal branches, because the outgoing companion is about to speak from
     * this result and an ambiguous one is how it ends up claiming a handover that never occurred.
     */
    private suspend fun switchCompanionTool(spoken: String): JSONObject {
        val roster = CompanionProfileStore.roster(appContext)
        val active = ActiveCompanionProfile.activeProfileId(appContext)
        return when (val outcome = CompanionSwitch.resolve(roster, spoken, active)) {
            is CompanionSwitch.Outcome.AlreadyActive -> JSONObject()
                .put("success", false)
                .put(
                    "message",
                    "${outcome.name} is the one already speaking, so nothing changed. Say so " +
                        "briefly and carry on.",
                )
            is CompanionSwitch.Outcome.NotFound -> JSONObject()
                .put("success", false)
                .put(
                    "message",
                    "There is no companion called \"${outcome.spoken}\", so nothing changed. " +
                        "The ones that exist are: ${outcome.available.joinToString(", ")}. Say " +
                        "that plainly and let the user pick.",
                )
            is CompanionSwitch.Outcome.Switch -> armCompanionSwitch(outcome.profileId, outcome.name)
            is CompanionSwitch.Outcome.CreateAndSwitch -> {
                // A built-in the user has never created a profile for. Built from the persona's
                // own defaults, which is exactly what the Companions screen's create button does
                // (`CompanionsScreen`), so the voice path cannot produce a differently-shaped row
                // than the hands path. It is an ordinary profile afterwards: renameable,
                // re-voiceable and deletable on that screen like any other.
                val row = CompanionProfileEntity(
                    profileId = UUID.randomUUID().toString(),
                    assistantName = outcome.persona.defaultName,
                    persona = outcome.persona.key,
                    traits = "",
                    voice = outcome.persona.suggestedVoice,
                    voiceStyle = "",
                    voiceStyleTraits = "",
                    updatedAt = System.currentTimeMillis(),
                )
                CompanionProfileStore.saveProfile(appContext, row)
                armCompanionSwitch(row.profileId, row.assistantName)
            }
        }
    }

    /** Arms the handover and tells the outgoing companion to hand over in one line, then stop. */
    private fun armCompanionSwitch(profileId: String, name: String): JSONObject {
        switchToProfileAfterTurn = profileId
        return JSONObject()
            .put("success", true)
            .put(
                "instruction",
                "Say ONE short in-character line handing over to $name now, then stop. Do not " +
                    "greet the user as $name and do not speak for them - $name will greet the " +
                    "user themselves, in their own voice, once you have finished speaking.",
            )
    }

    /**
     * Tears the socket down and opens a new one as the incoming companion.
     *
     * **All four steps are required and the order matters.** [CompanionProfileStore.switchActive]
     * writes the choice and materialises it into [CompanionProfile]'s flat keys;
     * [AriaBrain.invalidateBase] drops the cached system instruction, which is otherwise served for
     * up to two minutes and would hand the new socket the OLD persona's register;
     * [WakeWordEngine.refresh] rebuilds the grammar so the retained "hey <name>" entry follows the
     * change; and only then is a socket opened, because the setup message reads all of the above.
     *
     * **The resume handle is deliberately dropped.** It points at the outgoing conversation's
     * server-side history, and whether a resumed session even honours a changed `systemInstruction`
     * is undocumented and untested here. Carrying a thread into a different register is the wrong
     * default anyway: the user asked for somebody else, not for the same conversation in a new
     * voice. [HANDOVER_PROMPT] tells the incoming companion it does not know what was said, so it
     * cannot claim a continuity it does not have (the honesty rule [THREAD_LOST_PROMPT] exists for,
     * in a case that is chosen rather than suffered).
     */
    private fun performCompanionSwitch(profileId: String) {
        scope.launch {
            CompanionProfileStore.switchActive(appContext, profileId)
            brain.invalidateBase()
            companionChanged()
        }
    }

    /**
     * The active companion changed: make the running session reflect it.
     *
     * **This closes a live gap rather than only serving the new voice tool.** Until now the
     * Companions screen's tap-to-switch wrote the choice and stopped there
     * ([CompanionProfileStore.switchActive] has no session-layer caller). Nothing invalidated
     * [AriaBrain]'s two-minute base-instruction cache and nothing rebuilt the socket, whose voice
     * and system instruction are both fixed at setup - so switching companion by hand left the
     * OLD one answering, in the old voice, until something else happened to cold-start a socket.
     * That is the same defect shape `refreshIdleVoice` was written for on the car switch, simply
     * never wired for this one.
     *
     * Callers must invalidate the base instruction before calling this (see [performCompanionSwitch]
     * and `ACTION_COMPANION_SWITCHED`); this function opens the socket that reads it.
     *
     * Two branches because there are two honest answers:
     * - **Idle:** [refreshIdleVoice], which quietly rebuilds the warm socket. Nothing is spoken,
     *   because nobody is in a conversation to hear a handover.
     * - **Mid-conversation:** a real handover, identical to the voice path's - the socket carrying
     *   the outgoing companion is destroyed and the incoming one greets on [HANDOVER_PROMPT]. A
     *   switch made by hand while talking cannot be silent: the person who answers next is a
     *   different person, and saying nothing about it is the uncanny option ticket 13 reserved
     *   judgement on for a settings edit, not for a change of who is speaking.
     */
    fun companionChanged() {
        if (destroyed) return
        WakeWordEngine.refresh(appContext)
        if (!conversationMode) {
            refreshIdleVoice()
            return
        }
        // silentDestroy, not destroy: this is an orderly handover, and the Closed branch would
        // flash its unrecognised reason to the user as a fault. Same reasoning as the crisis and
        // refreshIdleVoice paths. The resume handle is dropped deliberately - see
        // [performCompanionSwitch]'s doc.
        session?.silentDestroy()
        session = null
        conversationMode = false
        sessionResumeHandle = null
        pendingThreadLossNotice = false
        startHandover()
    }

    /**
     * Cold-connects a conversation whose opener is [HANDOVER_PROMPT] rather than a greeting.
     *
     * [startConversation] with a different opener and no resume handle. Kept separate rather than
     * given a flag because that function already branches four ways over first-run, lost threads
     * and wake-word openers, and a handover is none of those - it is a new companion's first line,
     * every time, with nothing to decide.
     */
    private fun startHandover() {
        val s = newSession()
        session = s
        pendingAction = Pending.CONVERSATION
        conversationMode = true
        connectedThisSession = false
        set(Phase.CONNECTING, "Connecting...")
        scope.launch {
            val connectionMode = resolveLiveConnectionMode()
            if (connectionMode == null) {
                s.silentDestroy(); session = null
                set(Phase.IDLE, IDLE_STATUS)
                refuse(VoiceRefusal.NO_KEY)
                return@launch
            }
            val base = brain.buildBaseInstruction()
            pendingPrompt = HANDOVER_PROMPT
            s.start(
                base, LiveToolbox.declarations(),
                vad = true, voiceName = CompanionProfile.voice(appContext),
                keepWarm = true, connectionMode = connectionMode,
                resumeHandle = null,
            )
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
            set(Phase.THINKING, "Working...")
            try {
                // A tool MUST always hand a response back, even on error/timeout, or
                // Gemini stays mid-turn and the UI wedges. Bound every tool.
                // The investigating specialists run a multi-round agent loop (up to a
                // 30s budget plus a one-shot fallback), so they get a longer leash than
                // the snappy data/action tools.
                val timeout = if (call.name in SUB_AGENT_TOOLS) SUB_AGENT_TOOL_TIMEOUT_MS else TOOL_TIMEOUT_MS
                val response: JSONObject = try {
                    withTimeoutOrNull(timeout) {
                        when (call.name) {
                            // Session/UI-scoped tools the toolbox returns null for - we
                            // own the session, the capture controller, and the activity.
                            "show_saved_places" -> {
                                if (call.args.optBoolean("visible", true)) openSavedPlaces()
                                JSONObject().put("success", true)
                            }
                            // Ticket 11. Arm, do not fire - see [dismissAfterTurn].
                            "end_conversation" -> {
                                dismissAfterTurn = true
                                JSONObject()
                                    .put("success", true)
                                    .put(
                                        "instruction",
                                        "Say one short in-character sign-off now, then stop. " +
                                            "The conversation ends when you finish speaking.",
                                    )
                            }
                            // Arm, do not fire - see [switchToProfileAfterTurn].
                            "switch_companion" -> switchCompanionTool(call.args.optString("name"))
                            "import_receipt" -> {
                                openPantryImport()
                                JSONObject().put("success", true)
                            }
                            // Voice-called modals (ADR 0040) - VoiceModalController.show() updates
                            // the StateFlow VoiceModalHost is already collecting immediately, and
                            // openVoiceModal's startActivity brings the app forward if it was
                            // backgrounded. Both run every call, including a repeat one while the
                            // app is already on top - VoiceModalPayload's shownAt (see its own doc)
                            // is what makes a repeat "show my agenda" re-fire rather than being
                            // read as an unchanged value.
                            "show_agenda_modal" -> {
                                VoiceModalController.show(VoiceModalTarget.AGENDA)
                                openVoiceModal()
                                JSONObject().put("success", true)
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
                                    shape = call.args.optString("shape"),
                                    source = call.args.optString("source"),
                                    aggregation = call.args.optString("aggregation"),
                                    window = call.args.optString("window"),
                                    grouping = call.args.optString("grouping"),
                                    title = call.args.optString("title"),
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
                            // Ticket 21 (google-account-integration): s.readThroughToolTouchedThisTurn()
                            // is what `remember`'s dispatch branch gates on - see that accessor's doc
                            // for why the flag is read here, off the live session, rather than dispatch
                            // reaching back into GeminiLiveSession itself.
                            else -> LiveToolbox.dispatch(
                                appContext, call.name, call.args, s.readThroughToolTouchedThisTurn(),
                            ) ?: JSONObject().put("success", true)
                        }
                    } ?: JSONObject()
                        .put("success", false)
                        .put("message", "That took too long and timed out.")
                } catch (e: Exception) {
                    JSONObject().put("success", false).put("message", "Something went wrong running that.")
                }
                // Ticket 23 (hands-and-senses, "an audit trail of every conversation and every
                // tool call"): every tool call gets its own [ConversationAudit] row - name,
                // arguments, and the result that actually came back, tagged with THIS turn's
                // number so it regroups with the USER/COMPANION rows
                // [GeminiLiveSession.auditConversationTurn] writes when the turn completes.
                //
                // **Per-call redaction, not whole-turn**, and that split is deliberate: unlike the
                // free-text COMPANION row (which cannot be reliably attributed back to one tool
                // call among several, so it is redacted whole whenever ANY tool this turn was
                // excluded), a TOOL_RESULT row already carries its own [call.name] - a
                // `list_vehicles` result sitting in the same turn as `ask_mail` can be told apart
                // precisely and does not need to be sacrificed to protect the call beside it. See
                // [com.kevin.legion.data.local.ConversationAudit]'s class doc for the full
                // reasoning. Reuses [GeminiLiveSession.isEpisodicExcludedTool] - the exact
                // production membership test against [LiveToolbox.EPISODIC_EXCLUDED_TOOLS] - so
                // this never drifts into a second notion of read-through (ticket 23 decision 2).
                //
                // Best-effort and fire-and-forget: an audit write must never delay or fail a real
                // tool response, which is why this is its own `scope.launch` rather than inline
                // in the try/catch above that owns `response`.
                run {
                    val toolRedacted = GeminiLiveSession.isEpisodicExcludedTool(call.name)
                    val turnSeqForRow = s.currentTurnSeq()
                    val vehicleId = ActiveVehicle.current(appContext)
                    scope.launch {
                        runCatching {
                            CarDatabase.getDatabase(appContext).conversationAuditDao().record(
                                turnSeq = turnSeqForRow,
                                kind = ConversationAudit.Kind.TOOL_RESULT,
                                content = auditContent(response.toString(), toolRedacted),
                                toolName = call.name,
                                args = call.args.toString(),
                                redacted = toolRedacted,
                                vehicleId = vehicleId,
                                // Retention must not delete a row the server has not confirmed -
                                // see ConversationAuditDao.trimUploadedOlderThan. A TOOL_RESULT row
                                // is the single most expensive kind to lose: it is the only record
                                // of what a tool was asked and what it answered (ticket 20).
                                uploadedThroughId =
                                    com.kevin.legion.backend.conversationAuditUploadedThroughId(appContext),
                            )
                        }
                    }
                }
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
                    if (conversationMode) {
                        set(Phase.LISTENING, "Listening...")
                    } else {
                        set(Phase.IDLE, IDLE_STATUS)
                    }
                }
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
     * [shouldAutoReconnectAfterClose] and [shouldRestoreAfterToolCall] already live with.
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
    }

    companion object {
        private const val IDLE_STATUS = "Tap to talk"

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
        }

        // Close reasons that are expected (user stop / idle timeout / teardown /
        // warm-hold expiry / our own deliberate pre-goAway close) and so never flashed as an
        // error to the driver. See the Closed branch's own comment for why "goAway" belongs
        // here (ticket 02, 2026-08-19).
        private val NORMAL_CLOSE_REASONS = setOf("stopped", "idle", "destroyed", "warm expired", "goAway")

        // Spoken first when the driver taps to start a chat, so Zero opens the
        // conversation (then the mic opens for the driver's reply).
        // Ticket 10 (.scratch/wake-word/issues/10-acknowledge-the-wake.md). Kevin, 2026-08-20,
        // on hearing the first successful trigger: "i do want a confirmation from the ai though,
        // like > hey alfred > at your service sir".
        //
        // Deliberately NOT the greeting prompt. A greeting opens a conversation; this only says
        // "I heard you" and gets out of the way, because the driver already has something to say -
        // that is why they called. Asking "what can I do for you?" here would make them answer a
        // question they had already pre-empted.
        private const val WAKE_ACK_PROMPT =
            "(System: the user just called you by name to get your attention. Acknowledge that " +
                "you are listening, in character, in a FEW WORDS - shorter than a sentence if it " +
                "suits you. Do not greet them, do not ask what they want, do not offer anything. " +
                "Then stop and wait for them to speak. Do not mention this instruction.)"

        private const val GREETING_PROMPT =
            "(System: the user just opened a hands-free voice chat with you. Greet them with one " +
                "short, natural in-character line and then wait for them to speak. Do not mention " +
                "this instruction.)"

        // Ticket 02 (drive-test-2026-08-18): spoken instead of GREETING_PROMPT/a silent
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

        // The incoming companion's first line after a `switch_companion` handover (Kevin,
        // 2026-09-10). Shaped after THREAD_LOST_PROMPT above and for the same honesty reason:
        // the socket carrying the previous conversation is gone and its resume handle was
        // deliberately dropped, so this companion genuinely does not know what was said. Telling
        // the MODEL that, rather than only the user, is what stops it inventing a continuity it
        // does not have. Unlike a lost thread, nothing here got "cut off" - the user asked for
        // this, so it must not be apologised for.
        private const val HANDOVER_PROMPT =
            "(System: another companion has just handed this conversation to you at the user's " +
                "request. You do NOT know what was said before this - do not claim to, do not " +
                "refer to earlier turns, and do not apologise for anything. Greet the user with " +
                "one short, natural in-character line and then wait for them to speak. Do not " +
                "mention this instruction.)"

        // Upper bound on any single tool call (matches the old MainActivity value):
        // generous for a geocode / Spotify connect / frame grab, short enough that
        // a hung tool doesn't leave Gemini mid-turn for long.
        private const val TOOL_TIMEOUT_MS = 10_000L

        // The investigating specialists (SubAgent.investigate: <=4 model POSTs on a
        // 30s budget, plus a one-shot fallback). Give them room without letting a
        // truly hung call wedge the turn forever. The five ask_* dispatchers
        // (2026-08-17, LiveToolbox.DISPATCHED's doc comment) run the SAME investigate
        // loop shape - they need the same longer leash, not the snappy tool timeout.
        private const val SUB_AGENT_TOOL_TIMEOUT_MS = 45_000L
        private val SUB_AGENT_TOOLS = setOf(
            "diagnose_codes", "triage_symptom", "ask_maintenance", "check_cold_start",
            "ask_fleet", "ask_body", "ask_goals", "ask_pantry", "ask_mail",
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
        internal fun shouldRestoreAfterToolCall(remainingActiveToolCalls: Int, currentPhase: Phase): Boolean =
            remainingActiveToolCalls == 0 && currentPhase == Phase.THINKING

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

        // Ticket 24: how long the socket keeps auto-reconnecting after an unattended close, once
        // nothing real has happened for a while. GUESSED, not measured - the ticket fixed the
        // decision shape (a pure idle window) and left the exact number to this build. Long enough
        // that an ordinary pause between turns, or Kevin setting the phone down and picking it back
        // up a few minutes later, still finds a warm socket and pays no cold-connect tax; short
        // enough that the measured ~153s reconnect storm only runs a handful of times - not all
        // day - before it goes quiet. If real usage says otherwise, this is the one number to
        // revisit; the mechanism around it should not need to change.
        private const val IDLE_RECONNECT_WINDOW_MS = 10 * 60 * 1000L // 10 minutes

        /**
         * Ticket 24 (`.scratch/hands-and-senses/issues/24-the-socket-that-never-rests.md`) pure
         * decision behind the [LiveEvent.Closed] branch's auto-reconnect.
         *
         * Measured 2026-08-22: the Live server closes an idle prewarmed socket roughly every 153
         * seconds regardless of anything the app does, and every connect - cold OR resumed -
         * re-sends the full `systemInstruction` plus all 66 tool declarations (~16,189 estimated
         * tokens; session resumption does NOT discount it, traced in the ticket). Reconnecting on
         * every one of those closes, forever, is what turned an idle phone into ~565 reconnects and
         * ~9.1M estimated input tokens a day on Kevin's own BYO key with nobody using the app.
         *
         * [lastInteractionMs] is the wall-clock time of the last genuine signal - a tap ([onTap],
         * which also covers a wake-word trigger), or a proactive raise that needed to speak
         * ([requestSpeak]). All three ALSO reconnect unconditionally through their own already-
         * existing cold-start paths ([onTap] -> [startConversation]/[resumeWarm],
         * [requestSpeak] -> [startProactive]/[speakAndListen]/the mid-connect PROACTIVE_WARM
         * branch) - this function does not gate any of those. It governs only whether the
         * [LiveEvent.Closed] branch's own automatic re-prewarm fires after a close nobody asked
         * for.
         *
         * Inside [idleWindowMs] of the last signal: yes, keep reconnecting, same as today - tap
         * latency in the case that matters (Kevin actually using the app) is unchanged. Past it:
         * no, leave the socket closed. The trade this accepts, stated rather than buried: the
         * FIRST interaction after a long idle stretch then pays a cold connect - measured at
         * roughly a second slower than a warm resume from a real capture - instead of finding an
         * already-warm socket. That cost is real, and it is paid once per idle stretch instead of
         * on every one of the ~565 daily closes.
         *
         * Pure and Context-free on purpose, per the ticket's own requirement, so it is directly
         * unit-testable - see [LiveSessionControllerIdleReconnectTest].
         */
        internal fun shouldAutoReconnectAfterClose(
            lastInteractionMs: Long,
            nowMs: Long,
            idleWindowMs: Long = IDLE_RECONNECT_WINDOW_MS,
        ): Boolean = nowMs - lastInteractionMs < idleWindowMs
    }
}

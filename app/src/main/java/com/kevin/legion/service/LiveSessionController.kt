package com.kevin.legion.service

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.kevin.legion.ai.AriaBrain
import com.kevin.legion.ai.CompanionProfile
import com.kevin.legion.ai.GeminiKeyProvider
import com.kevin.legion.ai.KeyHealth
import com.kevin.legion.ai.firstGreetingOpener
import com.kevin.legion.car.CarProbeLog
import com.kevin.legion.ui.LegionRoute
import com.kevin.legion.ui.MainActivity
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
        if (s != null && s.inConversation) { s.stop(); return }

        // A deliberate tap resets the prewarm backoff - the driver is actively
        // asking, so try now rather than honoring a long dead-zone cooldown.
        consecutivePrewarmFailures = 0

        // Fast-fail with a visible reason instead of a silent 15s connect attempt.
        // Connected calls block a turn - the call owns the speakers. A RINGING phone does NOT
        // (2026-08-21): isInCall used to be set true on RINGING, so the one moment Kevin wants to
        // say "answer it" was the moment this returned early and showed "ON A CALL". Answering by
        // voice is impossible without this distinction.
        if (TelephonyController.isInCall) { CompanionPhase.showNotice("ON A CALL"); return }
        // BYO-key only, no tiers (commercial model retired 2026-07-31).
        if (!GeminiKeyProvider.hasKey()) {
            CompanionPhase.showNotice("ADD A GEMINI KEY IN SETUP TO TALK")
            return
        }
        if (!isOnline()) { CompanionPhase.showNotice("NO SIGNAL OUT HERE"); return }

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

    /** Proactive engine - voice [prompt] once, no mic; reuse the warm socket. */
    fun requestSpeak(prompt: String) {
        val s = session
        // DIAGNOSTIC (B9, remove once root-caused): which branch a proactive line
        // takes decides whether the mic reopens after (only the inConversation
        // fold-in branch skips suppressMicNextTurn) - a field report of "it
        // listened after proactive speech" should show "fold-in" here.
        val branch = when {
            s != null && s.inConversation -> "fold-in (mic WILL reopen after, by design)"
            s != null && s.isWarm() -> "warm speakOnWarm (mic suppressed)"
            s != null -> "mid-connect PROACTIVE_WARM (mic suppressed)"
            else -> "cold startProactive (mic suppressed)"
        }
        android.util.Log.d("LiveSessionController", "requestSpeak branch: $branch")
        when {
            // Mid-conversation: fold the line into the ongoing turn flow (rare).
            // The segment is already active from when the conversation started.
            s != null && s.inConversation -> s.sendText(prompt)
            // Warm socket already up: speak on it and stay warm. This is a
            // proactive line spoken on an already-connected socket - it never
            // fires LiveEvent.Connected, so start the billing segment here.
            s != null && s.isWarm() -> {
                s.speakOnWarm(prompt)
            }
            // A socket is still connecting (prewarm): speak once it's up, warm.
            s != null -> { pendingAction = Pending.PROACTIVE_WARM; pendingPrompt = prompt }
            // Nothing live: spin up a short-lived speak-only session.
            else -> startProactive(prompt)
        }
    }

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
                CompanionPhase.showNotice("VOICE PAUSED - SEE SETUP TO CONTINUE")
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

    /** Cold start a speak-only proactive session (no warm socket existed). */
    private fun startProactive(prompt: String) {
        val s = newSession()
        session = s
        pendingAction = Pending.PROACTIVE_COLD
        pendingPrompt = prompt
        conversationMode = false
        connectedThisSession = false
        scope.launch {
            val connectionMode = resolveLiveConnectionMode()
            if (connectionMode == null) { s.silentDestroy(); session = null; return@launch }
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
                            event.reason == "key rejected" -> {
                                KeyHealth.noteInvalid(); "KEY PROBLEM - CHECK SETUP"
                            }
                            event.reason == "quota" -> {
                                KeyHealth.noteRateLimited(); "KEY RATE-LIMITED - TRY AGAIN SOON"
                            }
                            event.reason.contains("microphone", ignoreCase = true) -> "MIC UNAVAILABLE"
                            !everConnected -> "NO CONNECTION - TAP TO RETRY"
                            else -> "CONNECTION LOST - TAP TO RETRY"
                        }
                    )
                }
                // A prewarm socket (not a conversation) that never connected is a
                // failed connect - escalate the retry backoff.
                if (!everConnected && !userInitiated) consecutivePrewarmFailures++

                session = null
                pendingAction = Pending.NONE
                pendingPrompt = null
                conversationMode = false
                connectedThisSession = false
                set(Phase.IDLE, IDLE_STATUS)

                // Re-establish a warm socket so the next tap is instant again,
                // backing off after repeated connect failures (dead zone / bad key).
                if (!destroyed) {
                    val failures = consecutivePrewarmFailures
                    if (failures > 0) {
                        scope.launch {
                            delay((30_000L * (1L shl failures.coerceAtMost(6))).coerceAtMost(300_000L))
                            if (!destroyed && session == null) prewarm()
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
                            "import_statement" -> {
                                openLedgerImport()
                                JSONObject().put("success", true)
                            }
                            "import_receipt" -> {
                                openPantryImport()
                                JSONObject().put("success", true)
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

    private fun openLedgerImport() {
        val intent = Intent(appContext, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_ROUTE, LegionRoute.MONEY_IMPORT)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        appContext.startActivity(intent)
    }

    private fun openPantryImport() {
        val intent = Intent(appContext, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_ROUTE, LegionRoute.MONEY_PANTRY_IMPORT)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        appContext.startActivity(intent)
    }

    companion object {
        private const val IDLE_STATUS = "Tap to talk"

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
    }
}

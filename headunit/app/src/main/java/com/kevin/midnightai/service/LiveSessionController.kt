package com.kevin.midnightai.service

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import com.kevin.midnightai.MidnightEvents
import com.kevin.midnightai.ai.AriaBrain
import com.kevin.midnightai.ai.CompanionProfile
import com.kevin.midnightai.ai.GeminiKeyProvider
import com.kevin.midnightai.ai.KeyHealth
import com.kevin.midnightai.ai.firstGreetingOpener
import com.kevin.midnightai.ai.EntitlementManager
import com.kevin.midnightai.ui.SavedPlacesActivity
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

    // Tracks ACTIVE conversation/speech time (mic open or Zero talking) across
    // the current socket's lifetime. Dormant since the entitlement broker was
    // removed (CLAUDE.md sec 2/8, 2026-07-16) - no reporting call to make - but
    // kept for a future local spend readout. See VoiceUsageMeter's doc for the
    // segment model; started/paused at the call sites below, read out in
    // LiveEvent.Closed.
    private val usageMeter = VoiceUsageMeter()

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

    // Proactive lines that could not be spoken when asked for (audit B8/B11/B12, 2026-10-15).
    // [foldIns] wait for the running or about-to-start conversation to reach a turn boundary;
    // [carried] survive a socket that failed to connect and are spoken on the next good connect.
    // Both are bounded and fresh-only, and every drop leaves a breadcrumb: see [HeldLines].
    private val foldIns = HeldLines(maxAgeMs = FOLD_IN_MAX_AGE_MS, capacity = 3)
    private val carried = HeldLines(maxAgeMs = CARRY_MAX_AGE_MS, capacity = 1, maxFailures = 1)

    // True from a warm-resume tap until the live context is sent and the mic is opened (B2): the
    // resume is asynchronous now, so a second tap or a proactive line must not start another.
    private var resumePending = false

    private val tapDebounce = TapDebounce()

    private fun dropped(prompt: String, why: String) {
        MidnightEvents.voiceTurnDecision("proactive_dropped: $why :: ${prompt.take(60)}")
    }

    // A failure the driver should see. When Midnight is not in front (the companion badge over
    // another app, the screen saver) nothing is collecting the notice flow, so it also goes out as
    // a Toast (B6, 2026-10-15). It must be a Toast and not only the flow: CruiseScreen's
    // collector is the only one and an unwatched replay-0 flow loses the message.
    private fun notice(text: String) {
        CompanionPhase.showNotice(text)
        if (!CompanionPhase.uiInFront) {
            try {
                Toast.makeText(appContext, text, Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Log.w(TAG, "background notice toast failed: ${e.message}")
            }
        }
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
        val s = GeminiLiveSession(appContext) { handleEvent(it) }
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
        usageMeter.pause(System.currentTimeMillis())
        session?.silentDestroy()
        session = null
        prewarm()
    }

    /**
     * Talk tap (Cruise avatar / Lights Out long-press). A tap during an active
     * conversation stops it; a tap on a warm socket resumes instantly (mic opens,
     * no greeting); otherwise it connects a fresh conversation.
     */
    fun onTap() {
        // A double-fired tap must not start a session and then stop it (B16, 2026-10-15). This is
        // the single entry point for Cruise and Lights Out (both arrive as ACTION_TALK), so the
        // debounce lives here rather than in each screen's click handler.
        if (!tapDebounce.accept(SystemClock.elapsedRealtime())) {
            MidnightEvents.voiceTurnDecision("tap ignored: inside the ${TapDebounce.DEFAULT_WINDOW_MS} ms debounce window")
            return
        }
        // A warm resume is still fetching live context (B2): that tap already owns the session.
        if (resumePending) return
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
        if (TelephonyController.isInCall) { notice("ON A CALL"); return }
        // Two tiers only (CLAUDE.md sec 2): a no-key install needs a key, full stop.
        if (!GeminiKeyProvider.hasKey() && !EntitlementManager.canStartVoice()) {
            notice("ADD A GEMINI KEY IN SETUP TO TALK")
            return
        }
        if (!isOnline()) { notice("NO SIGNAL OUT HERE"); return }

        if (s != null && s.isWarm()) {
            resumeWarm(s)
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
            usageMeter.pause(System.currentTimeMillis())
            s.silentDestroy()
            session = null
        }
        startConversation()
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
            resumePending || (s != null && pendingAction == Pending.CONVERSATION) ->
                "held for the conversation that is starting (B12)"
            s != null && s.inConversation -> "fold-in (mic WILL reopen after, by design)"
            s != null && s.isWarm() -> "warm speakOnWarm (mic suppressed)"
            s != null -> "mid-connect PROACTIVE_WARM (mic suppressed)"
            else -> "cold startProactive (mic suppressed)"
        }
        android.util.Log.d("LiveSessionController", "requestSpeak branch: $branch")
        val now = SystemClock.elapsedRealtime()
        when {
            // A conversation is about to start: a warm resume still fetching its context, or a
            // cold connect with the greeting queued. B12 (2026-10-15): the second case used to
            // overwrite pendingAction = CONVERSATION with PROACTIVE_WARM, so the driver's tap
            // became a mic-less proactive line. Hold it for the first turn boundary instead.
            resumePending || (s != null && pendingAction == Pending.CONVERSATION) ->
                foldIns.hold(prompt, now, onDropped = ::dropped)
            // Mid-conversation: fold the line into the ongoing turn flow (rare).
            // The segment is already active from when the conversation started. B11 (2026-10-15):
            // only while the mic is open and the model is not speaking (LISTENING). Sent mid-reply
            // it interrupted the answer the driver was hearing (the late Spotify report and the
            // DTC ASK both arrive this way); otherwise it waits for the next turnComplete.
            s != null && s.inConversation -> {
                if (_phase.value == Phase.LISTENING) {
                    s.sendText(prompt)
                    set(Phase.THINKING, "...")
                } else {
                    foldIns.hold(prompt, now, onDropped = ::dropped)
                }
            }
            // Warm socket already up: speak on it and stay warm. This is a
            // proactive line spoken on an already-connected socket - it never
            // fires LiveEvent.Connected, so start the billing segment here.
            s != null && s.isWarm() -> {
                usageMeter.startSegment(System.currentTimeMillis())
                if (!s.speakOnWarm(prompt)) {
                    // B15: the "warm" socket was dead and has closed itself. Keep the line for the
                    // fresh socket the Closed event prewarms, once; it was not spoken.
                    usageMeter.pause(System.currentTimeMillis())
                    carried.hold(prompt, now, failures = 1, onDropped = ::dropped)
                }
            }
            // A socket is still connecting (prewarm): speak once it's up, warm.
            s != null && pendingPrompt == null -> { pendingAction = Pending.PROACTIVE_WARM; pendingPrompt = prompt }
            // Two proactive lines for one connecting socket: only one slot exists, and the first
            // is already promised. Leave a trace rather than overwrite it silently.
            s != null -> dropped(prompt, "another proactive line already waits on this connect")
            // Nothing live: spin up a short-lived speak-only session.
            else -> startProactive(prompt)
        }
    }

    /** Sends one held fold-in line if the conversation is at a turn boundary (B11/B12). */
    private fun drainFoldIn() {
        val s = session ?: return
        val held = foldIns.takeFresh(SystemClock.elapsedRealtime(), ::dropped) ?: return
        if (s.sendText(held.prompt)) {
            set(Phase.THINKING, "...")
        } else {
            dropped(held.prompt, "socket refused the fold-in")
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
     * Resume a warm socket. On the very first session ever (flag in
     * [CompanionProfile]) the companion is asked to introduce itself and begin
     * the conversational setup; every subsequent resume opens the mic immediately
     * with no greeting round-trip.
     */
    private fun resumeWarm(s: GeminiLiveSession) {
        conversationMode = true
        val isFirst = !CompanionProfile.isFirstSessionDone(appContext)
        if (isFirst) {
            set(Phase.THINKING, "...")
            finishResume(s, s.beginConversation(firstGreetingOpener()), isFirst = true)
            return
        }
        // Not LISTENING yet: the mic is still shut until the context is in (the "showed LISTENING
        // but heard nothing" shape). finishResume flips the phase once the mic really opens.
        set(Phase.THINKING, "...")
        // The tap must stop Zero NOW, not after the fetch below (beginConversation does the same
        // flush, but it runs after the wait).
        s.interruptForTap()
        // B2 (2026-10-15): a warm resume used to open the mic with NO live context - only a cold
        // start's greeting carried now-playing, location, the date line and trouble codes - so
        // every tap after the first talked to a model that knew what was true at socket start.
        // Fetch it (bounded: a slow geocode must not hold the mic shut) and hand it over as a
        // non-completing client turn BEFORE the mic opens. If it times out or fails, the resume
        // goes ahead without it, as it always did, and a breadcrumb says so.
        resumePending = true
        scope.launch {
            val live = try {
                withTimeoutOrNull(LIVE_CONTEXT_TIMEOUT_MS) { brain.buildLiveContext() }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                MidnightEvents.recordError("warm_resume_context", e)
                null
            }
            resumePending = false
            // Superseded while the context was being built (socket closed, controller destroyed).
            if (destroyed || session !== s) return@launch
            val contextSent = when {
                live == null -> {
                    MidnightEvents.voiceTurnDecision("warm resume: live context skipped (timed out or failed)")
                    true
                }
                live.isBlank() -> true
                else -> s.sendContext(
                    "(Context update, no reply needed. Current car/driver context, use naturally if " +
                        "relevant:\n$live)",
                )
            }
            finishResume(s, contextSent && s.beginConversation(null), isFirst = false)
            // Lines that arrived while the context was being fetched wait for the first turn
            // boundary like any other fold-in (drainFoldIn on TurnComplete).
        }
    }

    private fun finishResume(s: GeminiLiveSession, ok: Boolean, isFirst: Boolean) {
        if (!ok) {
            // The "warm" socket was actually stale (send no-op'd). Silently tear it
            // down - cancelling its scope drops any Closed event OkHttp may still
            // post, which would otherwise race in and clobber the fresh session we
            // start below - then do a real cold connect instead of sitting silently
            // in LISTENING forever. silentDestroy skips Closed, so close out any
            // dangling segment (e.g. a proactive line that was mid-speech on this
            // socket) before the fresh conversation starts its own.
            usageMeter.pause(System.currentTimeMillis())
            s.silentDestroy()
            session = null
            startConversation()
            return
        }
        // Resuming a warm socket doesn't go through LiveEvent.Connected (it's
        // already connected), so this is the only place a resumed conversation's
        // active talk begins - only once the send actually landed (a stale-socket
        // failure above starts a fresh conversation instead, billed from its own
        // Connected event).
        usageMeter.startSegment(System.currentTimeMillis())
        // The mic has really been opened (beginConversation ran), so LISTENING is true now. The
        // first-session path shows THINKING instead: Zero is about to speak the intro.
        if (!isFirst) set(Phase.LISTENING, "Listening...")
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
    private fun startConversation() {
        val s = GeminiLiveSession(appContext) { handleEvent(it) }
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
                notice("VOICE PAUSED - SEE SETUP TO CONTINUE")
                return@launch
            }
            val base = brain.buildBaseInstruction()
            val live = brain.buildLiveContext()
            val isFirst = !CompanionProfile.isFirstSessionDone(appContext)
            pendingPrompt = when {
                isFirst -> firstGreetingOpener()
                live.isBlank() -> GREETING_PROMPT
                else -> "(Current car/driver context, use naturally if relevant:\n$live)\n\n$GREETING_PROMPT"
            }
            s.start(
                base, LiveToolbox.declarations(),
                vad = true, voiceName = CompanionProfile.voice(appContext),
                keepWarm = true, connectionMode = connectionMode,
            )
        }
    }

    /**
     * After a connect, speaks the line [carried] kept from a failed one (B8). [connectedFor] is
     * what the connect was for: a bare prewarm (NONE) speaks it on the warm socket; a
     * conversation connect folds it in at the first turn boundary; anything else is a connect that
     * already has its own line, so the carried one is dropped with a breadcrumb.
     */
    private fun speakCarriedLine(connectedFor: Pending) {
        val now = SystemClock.elapsedRealtime()
        val held = carried.takeFresh(now, ::dropped) ?: return
        val s = session
        when {
            s == null -> dropped(held.prompt, "no session after connect")
            connectedFor == Pending.NONE -> {
                usageMeter.startSegment(System.currentTimeMillis())
                if (!s.speakOnWarm(held.prompt)) {
                    usageMeter.pause(System.currentTimeMillis())
                    carried.hold(held.prompt, now, failures = held.failures + 1, onDropped = ::dropped)
                } else {
                    MidnightEvents.voiceTurnDecision("carried proactive spoken after reconnect")
                }
            }
            connectedFor == Pending.CONVERSATION -> foldIns.hold(held.prompt, now, onDropped = ::dropped)
            else -> dropped(held.prompt, "connect already had its own proactive line")
        }
    }

    /** Cold start a speak-only proactive session (no warm socket existed). */
    private fun startProactive(prompt: String) {
        val s = GeminiLiveSession(appContext) { handleEvent(it) }
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
                    usageMeter.startSegment(System.currentTimeMillis())
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
                val connectedFor = pendingAction
                pendingAction = Pending.NONE
                pendingPrompt = null
                speakCarriedLine(connectedFor)
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
                usageMeter.pause(System.currentTimeMillis())
                session?.silentDestroy()
                session = null
                conversationMode = false
                CompanionPhase.setCaption("")
                CompanionPhase.setCrisis()
                set(Phase.IDLE, IDLE_STATUS)
            }
            is LiveEvent.TurnComplete -> {
                // Conversation: the session reopened the mic, so wait for the
                // driver - still active talk time, segment stays open. Speak-only:
                // the proactive line just finished, nothing more to do - pause the
                // segment here (the socket may not fire a separate Idle for this
                // path, e.g. the cold speak-only session in startProactive).
                if (conversationMode) {
                    set(Phase.LISTENING, "Listening...")
                    // The turn boundary B11/B12 lines were waiting for.
                    drainFoldIn()
                } else {
                    usageMeter.pause(System.currentTimeMillis())
                    set(Phase.IDLE, IDLE_STATUS)
                }
            }
            is LiveEvent.Idle -> {
                // Conversation went quiet but the socket is warm - ready for an
                // instant resume on the next tap. Pause billing here too (also
                // reached via parkWarm after a warm-socket proactive line, on top
                // of the TurnComplete pause above - pause() is a no-op if the
                // segment is already closed).
                usageMeter.pause(System.currentTimeMillis())
                conversationMode = false
                set(Phase.IDLE, IDLE_STATUS)
                // A fold-in still waiting when the conversation went quiet is spoken now, on the
                // warm socket, rather than lost with the conversation.
                foldIns.takeFresh(SystemClock.elapsedRealtime(), ::dropped)?.let { requestSpeak(it.prompt) }
            }
            is LiveEvent.Subtitle -> {
                _subtitle.tryEmit(event.text)
                // Mirror to the process-global holder so the Cruise screen renders captions too.
                CompanionPhase.setCaption(event.text)
            }
            is LiveEvent.ToolCall -> handleToolCall(event)
            is LiveEvent.Closed -> {
                val userInitiated = conversationMode
                val everConnected = connectedThisSession
                // Only surface errors the driver kicked off (a tap), not a failed
                // background proactive opener. "stopped"/"idle"/"destroyed"/"warm
                // expired" are normal closes; anything else is a fault worth flashing.
                if (userInitiated && event.reason !in NORMAL_CLOSE_REASONS) {
                    notice(
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

                // Always close out the meter (folds in any still-open segment and
                // resets it for the next socket). No broker to report to (CLAUDE.md
                // sec 2/8: broker removed 2026-07-16) - the meter stays dormant,
                // kept for a future local spend readout.
                usageMeter.stopAndReportSeconds(System.currentTimeMillis())

                // B8 (2026-10-15): a proactive line still waiting on a socket that never connected
                // was dropped here with no trace (the late Spotify report was the visible case).
                // Keep it, once, for the next good connect; a second failure drops it, loudly.
                val unspoken = pendingPrompt
                if (unspoken != null && !everConnected &&
                    (pendingAction == Pending.PROACTIVE_WARM || pendingAction == Pending.PROACTIVE_COLD)
                ) {
                    carried.hold(unspoken, SystemClock.elapsedRealtime(), failures = 1, onDropped = ::dropped)
                } else if (unspoken != null && pendingAction != Pending.CONVERSATION) {
                    // (A CONVERSATION pending prompt is the greeting, not a proactive line.)
                    dropped(unspoken, "session closed (${event.reason}) before it was spoken")
                }
                foldIns.clear("session closed (${event.reason})", ::dropped)
                resumePending = false

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

    private fun handleToolCall(call: LiveEvent.ToolCall) {
        scope.launch {
            val s = session ?: return@launch
            // A tool MUST always hand a response back, even on error/timeout, or
            // Gemini stays mid-turn and the UI wedges. Bound every tool.
            // The investigating specialists run a multi-round agent loop (up to a
            // 30s budget plus a one-shot fallback), so they get a longer leash than
            // the snappy data/action tools.
            val timeout = toolTimeoutMs(call.name)
            val response: JSONObject = try {
                withTimeoutOrNull(timeout) {
                    when (call.name) {
                        // Session/UI-scoped tools the toolbox returns null for - we
                        // own the session, the capture controller, and the activity.
                        "show_saved_places" -> {
                            if (call.args.optBoolean("visible", true)) openSavedPlaces()
                            JSONObject().put("success", true)
                        }
                        else -> LiveToolbox.dispatch(appContext, call.name, call.args)
                            ?: JSONObject().put("success", true)
                    }
                } ?: JSONObject()
                    .put("success", false)
                    .put(
                        "message",
                        if (call.name == "ask_engine") {
                            // The outer cut fired before AskEngine could word its own outcome,
                            // so record it here or it never reaches Crashlytics.
                            com.kevin.midnightai.MidnightEvents.askEngineOutcome(EngineFailureClass.AgentTimeout.name, failed = true)
                            // Cut mid-flight: a write may already have landed, so do not imply it did not.
                            "That took too long and timed out. It cannot be confirmed whether the engine " +
                                "read or wrote anything; check before retrying."
                        } else {
                            "That took too long and timed out."
                        },
                    )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e // the controller is being torn down; that is not a tool fault
            } catch (e: Exception) {
                // B10 (2026-10-15): this was swallowed with no trace, so "Something went wrong" in
                // the car left nothing to find. Both a Crashlytics non-fatal and a log line now.
                Log.w(TAG, "tool ${call.name} threw: ${e.javaClass.simpleName}: ${e.message}", e)
                MidnightEvents.recordError("tool_${call.name}", e)
                JSONObject().put("success", false).put("message", "Something went wrong running that.")
            }
            // B1 (2026-10-15): a music tool that really ran means the driver (or the tool) now
            // owns the speakers; the "it was playing before this turn" snapshot used for the
            // post-turn resume is stale and must not undo it (a spoken "pause" resumed itself).
            if (response.optBoolean("success", false) && changesPlayback(call.name, call.args)) {
                s.forgetDuckedMusic()
            }
            // Sending can throw if the socket died mid-tool; the close path handles
            // recovery, so don't let it crash this scope.
            try {
                s.sendToolResponse(call.id, call.name, response)
            } catch (e: Exception) {
                android.util.Log.w("LiveSessionController", "sendToolResponse failed: ${e.message}")
            }
        }
    }

    private fun openSavedPlaces() {
        val intent = Intent(appContext, SavedPlacesActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        appContext.startActivity(intent)
    }

    companion object {
        private const val TAG = "LiveSessionController"
        private const val IDLE_STATUS = "Tap to talk"

        // Close reasons that are expected (user stop / idle timeout / teardown /
        // warm-hold expiry / the server's goAway on a parked socket) and so never flashed as an
        // error to the driver.
        private val NORMAL_CLOSE_REASONS = setOf("stopped", "idle", "destroyed", "warm expired", "go away")

        // How long a bounded warm-resume context fetch may hold the mic shut (B2).
        private const val LIVE_CONTEXT_TIMEOUT_MS = 1_500L
        // A held proactive line is only spoken while it still describes the present (HeldLines).
        private const val FOLD_IN_MAX_AGE_MS = 60_000L
        private const val CARRY_MAX_AGE_MS = 120_000L

        /**
         * The leash for one tool call (B3, 2026-10-15). Tools whose work is a blocking
         * `HttpURLConnection` that [withTimeoutOrNull] cannot interrupt (NHTSA vPIC, the Gemini
         * music and maintenance agents) were on the 10 s default: the cut fired, the tool kept
         * running, and its real result was discarded as "took too long". They get the 45 s tier.
         * play_music is deliberately NOT here: it manages its own budget inside the 10 s.
         */
        internal fun toolTimeoutMs(name: String): Long = when {
            name == "ask_engine" -> ENGINE_TOOL_TIMEOUT_MS
            name in SUB_AGENT_TOOLS || name in BLOCKING_NETWORK_TOOLS -> SUB_AGENT_TOOL_TIMEOUT_MS
            else -> TOOL_TIMEOUT_MS
        }

        private val PLAYBACK_ACTIONS = setOf("play", "pause", "next", "previous")
        private val SOURCE_TOOLS = setOf("set_music_source", "play_music", "play_mixtape", "open_music")

        /**
         * Whether a successful call to [name] changed what the speakers are doing, so a
         * post-turn resume nudge would fight it. For control_music only the transport actions
         * count; a like or a shuffle toggle leaves playback as it was.
         */
        internal fun changesPlayback(name: String, args: JSONObject): Boolean =
            name in SOURCE_TOOLS ||
                (name == "control_music" && args.optString("action").lowercase() in PLAYBACK_ACTIONS)

        // Spoken first when the driver taps to start a chat, so Zero opens the
        // conversation (then the mic opens for the driver's reply).
        private const val GREETING_PROMPT =
            "(System: the driver just opened a hands-free voice chat with you. Greet them with one " +
                "short, natural in-character line and then wait for them to speak. Do not mention " +
                "this instruction.)"

        // Upper bound on any single tool call (matches the old MainActivity value):
        // generous for a geocode / Spotify connect / frame grab, short enough that
        // a hung tool doesn't leave Gemini mid-turn for long.
        private const val TOOL_TIMEOUT_MS = 10_000L

        // The investigating specialists (SubAgent.investigate: <=4 model POSTs on a
        // 30s budget, plus a one-shot fallback). Give them room without letting a
        // truly hung call wedge the turn forever.
        private const val SUB_AGENT_TOOL_TIMEOUT_MS = 45_000L
        // ask_engine was falling into the 10s default above, which cannot hold even one warm
        // `tools/list` plus a Gemini investigate loop (40s budget), let alone a Cloud Run cold
        // start. Worst case: tools/list is tried twice, each attempt up to 15s connect + 30s
        // read = 2 x 45 = 90s, then the 40s loop = 130s; 150s leaves 20s so the layers below
        // time out first and say WHY. Known tail NOT covered: SubAgent.postOnce uses blocking
        // HttpURLConnection (15/30s) that withTimeoutOrNull cannot interrupt, so a hung Gemini
        // POST can overrun the loop budget by up to ~45s; the outer cut then fires anyway.
        private const val ENGINE_TOOL_TIMEOUT_MS = 150_000L
        private val SUB_AGENT_TOOLS =
            setOf("diagnose_codes", "triage_symptom", "ask_maintenance", "check_cold_start")
        // Not sub-agent loops, but the same shape of work: blocking HTTP that the 10 s cut cannot
        // interrupt. recommend_music runs the music sub-agent; check_recalls and lookup_vin call
        // NHTSA vPIC; register_vehicle now returns after its DB upsert (the maintenance lookup runs
        // detached) but is kept on the longer tier as a safety margin.
        private val BLOCKING_NETWORK_TOOLS =
            setOf("recommend_music", "register_vehicle", "check_recalls", "lookup_vin")
    }
}

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
import com.kevin.legion.ui.SavedPlacesActivity
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
        if (TelephonyController.isInCall) { CompanionPhase.showNotice("ON A CALL"); return }
        // BYO-key only, no tiers (commercial model retired 2026-07-31).
        if (!GeminiKeyProvider.hasKey()) {
            CompanionPhase.showNotice("ADD A GEMINI KEY IN SETUP TO TALK")
            return
        }
        if (!isOnline()) { CompanionPhase.showNotice("NO SIGNAL OUT HERE"); return }

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
     * Resume a warm socket. On the very first session ever (flag in
     * [CompanionProfile]) the companion is asked to introduce itself and begin
     * the conversational setup; every subsequent resume opens the mic immediately
     * with no greeting round-trip.
     */
    private fun resumeWarm(s: GeminiLiveSession) {
        conversationMode = true
        val isFirst = !CompanionProfile.isFirstSessionDone(appContext)
        val ok = if (isFirst) {
            set(Phase.THINKING, "...")
            s.beginConversation(firstGreetingOpener())
        } else {
            set(Phase.LISTENING, "Listening...")
            s.beginConversation(null)
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
            startConversation()
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
                CompanionPhase.showNotice("VOICE PAUSED - SEE SETUP TO CONTINUE")
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
                } else {
                    set(Phase.IDLE, IDLE_STATUS)
                }
            }
            is LiveEvent.Idle -> {
                // Conversation went quiet but the socket is warm - ready for an
                // instant resume on the next tap. Pause billing here too (also
                // reached via parkWarm after a warm-socket proactive line, on top
                // of the TurnComplete pause above - pause() is a no-op if the
                // segment is already closed).
                conversationMode = false
                set(Phase.IDLE, IDLE_STATUS)
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

    private fun handleToolCall(call: LiveEvent.ToolCall) {
        scope.launch {
            val s = session ?: return@launch
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
                        else -> LiveToolbox.dispatch(appContext, call.name, call.args)
                            ?: JSONObject().put("success", true)
                    }
                } ?: JSONObject()
                    .put("success", false)
                    .put("message", "That took too long and timed out.")
            } catch (e: Exception) {
                JSONObject().put("success", false).put("message", "Something went wrong running that.")
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
        private const val IDLE_STATUS = "Tap to talk"

        // Close reasons that are expected (user stop / idle timeout / teardown /
        // warm-hold expiry) and so never flashed as an error to the driver.
        private val NORMAL_CLOSE_REASONS = setOf("stopped", "idle", "destroyed", "warm expired")

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
        private val SUB_AGENT_TOOLS =
            setOf("diagnose_codes", "triage_symptom", "ask_maintenance", "check_cold_start")
    }
}

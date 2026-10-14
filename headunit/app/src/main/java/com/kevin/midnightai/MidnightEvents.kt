package com.kevin.midnightai

import com.google.firebase.crashlytics.FirebaseCrashlytics

/**
 * Thin wrapper around [FirebaseCrashlytics] for structured breadcrumb logging
 * and handled-error reporting.
 *
 * Every `log()` call appends a breadcrumb that appears in the Crashlytics crash
 * report timeline, giving context for what was happening at the time. This is
 * the primary production observability path since ADB logcat is blocked on the
 * head unit (vendor-locked dev menu). [recordError] records a non-fatal
 * exception visible in the Crashlytics dashboard without counting as a crash.
 *
 * Call [setCompanionName] / [setHasGeminiKey] once after the companion profile
 * loads so every subsequent crash report is tagged with that context.
 */
object MidnightEvents {
    private val c get() = FirebaseCrashlytics.getInstance()

    /**
     * Observability must never crash the app it's observing - doubly so on a
     * blind head unit where a crash in the crash reporter would be invisible.
     * Every call routes through here so a Firebase-not-ready state (e.g. the
     * placeholder google-services.json before real project setup, or a failed
     * init) silently no-ops instead of throwing into a hot path.
     */
    private inline fun safe(block: () -> Unit) {
        try { block() } catch (_: Throwable) {}
    }

    // --- Session identity keys -----------------------------------------------
    // These are attached to every crash report so triage doesn't need to
    // cross-reference logs to know which companion setup was active.

    /**
     * Tag every report with build/device context so dev-machine and emulator
     * noise is FILTERABLE in the dashboard rather than suppressed at the source.
     * (Collection used to be off in debug builds entirely, which silenced the
     * head unit - field builds are debug builds. See MidnightApplication.)
     */
    fun setBuildContext(buildType: String, deviceModel: String, isEmulator: Boolean) = safe {
        c.setCustomKey("build_type", buildType)
        c.setCustomKey("device_model", deviceModel)
        c.setCustomKey("is_emulator", isEmulator)
    }

    /** Tag the crash report with the companion name (e.g. "Midnight"). */
    fun setCompanionName(name: String) = safe { c.setCustomKey("companion_name", name) }

    /** Tag whether the user supplied their own Gemini key (vs. the BuildConfig key). */
    fun setHasGeminiKey(hasByo: Boolean) = safe { c.setCustomKey("has_byo_key", hasByo) }

    // --- Breadcrumb lifecycle events ------------------------------------------

    /** Gemini Live session socket opened and setup sent. */
    fun sessionStart() = safe { c.log("session_start") }

    /** Gemini Live session closed. [reason] is the close string from the socket. */
    fun sessionEnd(reason: String) = safe { c.log("session_end: $reason") }

    /** ELM327 OBD adapter connected at this Bluetooth MAC. */
    fun obdConnected(mac: String) = safe { c.log("obd_connected: $mac") }

    /** OBD adapter disconnected (annotates any crash that follows a disconnect). */
    fun obdDisconnected(reason: String) = safe { c.log("obd_disconnected: $reason") }

    /**
     * A PID read came back as a failure while the socket was still up (breadcrumb,
     * cheap). [rawResponse] is the exact adapter reply that tripped
     * [com.kevin.midnightai.vehicle.ObdResponseParser.isFailureResponse] - this is
     * how we learn what a dead K-line actually answers on the Cherokee, since ADB
     * logcat is blocked (drive-notes-2 ticket 02). Attached to the reinit non-fatal
     * below, so the timeline shows the exact failure strings leading up to it.
     */
    fun obdPidSilence(count: Int, rawResponse: String) = safe {
        c.log("obd_pid_silence[$count]: '${rawResponse.take(60).replace('\n', ' ')}'")
    }

    /**
     * The K-line reinit fired (3 consecutive silent PID reads). Recorded as a
     * NON-FATAL, not just a breadcrumb, so it's retrievable in the Crashlytics
     * dashboard on its own - one of these per drive tells us definitively whether
     * the reinit is firing and whether it recovered, which JVM tests can't prove
     * (drive-notes-2 ticket 02). [trigger] is the raw response that tripped it;
     * [recovered] is whether the post-reinit 0100 came back looking valid.
     */
    fun obdReinit(trigger: String, recovered: Boolean) = safe {
        c.recordException(
            RuntimeException(
                "[obd_reinit] recovered=$recovered trigger='${trigger.take(60).replace('\n', ' ')}'"
            )
        )
    }

    /**
     * REFRESH SCHEDULE in the logbook's DUE tab threw. Recorded as a NON-FATAL
     * because the driver only ever sees "couldn't reach the lookup" - that one
     * line covers a bad key, a dead connection, and a parser blowing up on an
     * odd Gemini response, and on the head unit there is no ADB to tell them
     * apart after the fact (CLAUDE.md sec 14). Without this the failure is
     * undiagnosable.
     */
    fun maintenanceRefreshFailed(e: Throwable) = safe {
        c.recordException(RuntimeException("[maintenance_refresh] ${e.javaClass.simpleName}: ${e.message}", e))
    }

    /** A voice tool was dispatched. Useful for tracing what tool ran before a crash. */
    fun toolDispatched(toolName: String) = safe { c.log("tool_dispatched: $toolName") }

    /**
     * The Live server cancelled a tool call it had asked for (`toolCallCancellation`, usually the
     * driver interrupting). Breadcrumb plus a rate-limited non-fatal `ToolCancelled(<tool>)`, so
     * a long ask_engine answer that never reached the driver is visible. Tool name only.
     */
    fun toolCancelled(toolName: String) = safe {
        c.log("tool_cancelled: $toolName")
        if (nonFatalLimiter.allow("tool_cancelled:$toolName")) c.recordException(ToolCancelled(toolName))
    }

    // --- ask_engine -----------------------------------------------------------
    // Kevin's "the voice agent can't call the engine" left nothing in Crashlytics but
    // `tool_dispatched: ask_engine`, because no outcome was ever recorded. These carry the
    // intent and a fixed outcome CLASS only - never the question or the engine's reply.

    private val nonFatalLimiter = NonFatalRateLimiter(NON_FATAL_WINDOW_MS)

    /** The intent an ask_engine call ran under, and whether the model declared it or it was
     * inferred from the question because the model left it out. */
    fun askEngineIntent(intent: String, declared: Boolean) = safe {
        c.log("ask_engine_intent: $intent (${if (declared) "declared" else "inferred"})")
    }

    /**
     * The ask_engine outcome class ("Success" or an EngineFailureClass name). A failure is also
     * recorded as a NON-FATAL `AskEngineFailure(<class>)` so it surfaces in the dashboard - a
     * breadcrumb alone is never pulled without a triggering event (see [silentMicTurn]) - rate
     * limited per class so a retry loop on a dead engine does not flood the quota.
     *
     * [detail] is fixed codes only ("HTTP 400, JSON-RPC -32602"), never the server's message
     * text (an SDK validation error can echo the request's own arguments). It rides in the
     * non-fatal's message so an engine refusal is diagnosable from the dashboard alone.
     */
    fun askEngineOutcome(outcomeClass: String, failed: Boolean, detail: String? = null) = safe {
        val label = if (detail.isNullOrBlank()) outcomeClass else "$outcomeClass: $detail"
        c.log("ask_engine_outcome: $label")
        if (failed && nonFatalLimiter.allow("ask_engine:$label")) {
            c.recordException(AskEngineFailure(label))
        }
    }

    /**
     * A completed voice turn: what Gemini heard, and how much mic audio we
     * actually forwarded (B9/B10/B12).
     *
     * **Why this is a breadcrumb and not just a Log.d.** These diagnostics existed
     * only in logcat, whose own comment said they were there "so a field-test log
     * pull shows which path fired" - on a head unit where §14 records that ADB
     * logcat is BLOCKED. They were unreadable exactly where the bug happens.
     * Kevin's 2026-07-16 drive ("showed listening, I spoke, nothing happened")
     * produced no evidence for that reason.
     *
     * `forwardedBytes` near zero on a turn the driver spoke into IS the diagnosis:
     * it means the mic was closed or muted, not that Gemini misheard. A healthy
     * turn forwards tens of KB.
     *
     * The transcript is the driver's own speech, which leaves the device here. It
     * goes to Kevin's Crashlytics, not to a car-data store, so §9's "no
     * Kevin-hosted car data" is not touched - but it is still the driver talking,
     * so this is capped and must stay debug-only. Do NOT ship it in release
     * without deciding that deliberately.
     */
    fun voiceTurn(transcript: String, forwardedBytes: Long) = safe {
        c.log("voice_turn: bytes=$forwardedBytes heard=\"${transcript.take(80)}\"")
    }

    /**
     * The "shows LISTENING but hears nothing" bug (B9/B10/B12), reported as a real non-fatal, not
     * just a breadcrumb. [voiceTurn] above logs every turn's forwarded-byte count, but a plain
     * `c.log()` breadcrumb is invisible in the Crashlytics dashboard unless SOME event (a crash or
     * a `recordException`) actually surfaces it - nothing ever did, which is exactly why Kevin's
     * 2026-07-16 AND 2026-07-19 drives both produced zero retrievable Crashlytics data for this
     * bug despite the breadcrumb already existing. Call this instead of/alongside [voiceTurn] when
     * a real conversational turn (mic opened for the driver, not a proactive/onboarding line)
     * forwards suspiciously few bytes - it force-surfaces as a Crashlytics non-fatal, carrying the
     * transcript and byte count so the NEXT drive's data is actually visible in the dashboard.
     */
    fun silentMicTurn(transcript: String, forwardedBytes: Long) = safe {
        c.log("silent_mic_turn: bytes=$forwardedBytes heard=\"${transcript.take(80)}\"")
        c.recordException(RuntimeException("Voice turn forwarded only $forwardedBytes bytes (B9/B10/B12)"))
    }

    /** Which branch the turn-complete state machine took, and the flags it decided from (B9/B10/B12). */
    fun voiceTurnDecision(detail: String) = safe { c.log("voice_turn_decision: $detail") }

    /** The mic was opened for the driver, or closed. The "shows LISTENING but hears nothing" tell. */
    fun micState(open: Boolean, why: String) = safe { c.log("mic_${if (open) "open" else "closed"}: $why") }

    // --- Voice playback (choppy-voice diagnostics, 2026-10-13) -----------------
    // One breadcrumb per model turn, plus custom keys that ride on every later report, plus a
    // rate-limited non-fatal so a glitchy drive uploads on its own. Read together:
    //   rebuffers/hal_underruns > 0, max_chunk_gap small -> device starved the track (CPU/HAL)
    //   rebuffers > 0 with a big max_chunk_gap            -> audio arrived late (network/server)
    //   interruptions with mic_open=true                  -> Zero cut off by what the mic heard
    //   drain gave up                                     -> mic opened over live playback

    /** How the model's audio arrived off the socket this turn. */
    fun voiceAudioArrival(chunks: Int, spanMs: Long, maxGapMs: Long, backlogAtTurnEndMs: Long) = safe {
        c.setCustomKey("voice_last_max_chunk_gap_ms", maxGapMs)
        c.setCustomKey("voice_last_backlog_at_turn_end_ms", backlogAtTurnEndMs)
        c.log("voice_arrival: chunks=$chunks span=${spanMs}ms max_gap=${maxGapMs}ms backlog_at_end=${backlogAtTurnEndMs}ms")
    }

    /** How one model turn played out of the AudioTrack. */
    fun voicePlaybackTurn(
        chunks: Int,
        audioMs: Long,
        rebuffers: Int,
        halUnderruns: Int,
        droppedBytes: Int,
        interrupted: Boolean,
        sessionRebuffers: Int,
    ) = safe {
        c.setCustomKey("voice_last_turn_rebuffers", rebuffers)
        c.setCustomKey("voice_last_turn_hal_underruns", halUnderruns)
        c.setCustomKey("voice_session_rebuffers", sessionRebuffers)
        val detail = "chunks=$chunks audio=${audioMs}ms rebuffers=$rebuffers hal_underruns=$halUnderruns " +
            "dropped=${droppedBytes}B interrupted=$interrupted"
        c.log("voice_playback: $detail")
        if ((rebuffers > 0 || halUnderruns > 0 || droppedBytes > 0) &&
            nonFatalLimiter.allow("voice_playback_glitch")
        ) {
            c.recordException(VoicePlaybackGlitch(detail))
        }
    }

    /** The server sent `interrupted` (barge-in). [micOpen] = our mic was streaming at the time. */
    fun voiceInterrupted(sessionCount: Int, speaking: Boolean, micOpen: Boolean) = safe {
        c.setCustomKey("voice_session_interruptions", sessionCount)
        val detail = "n=$sessionCount speaking=$speaking mic_open=$micOpen"
        c.log("voice_interrupted: $detail")
        if (nonFatalLimiter.allow("voice_interrupted")) c.recordException(VoiceInterrupted(detail))
    }

    /** The mic re-open stopped waiting for playback before it drained ([verdict] STALLED/CAPPED). */
    fun voiceDrainGaveUp(verdict: String, waitedMs: Long) = safe {
        c.log("voice_drain_gave_up: $verdict after ${waitedMs}ms")
        if (nonFatalLimiter.allow("voice_drain:$verdict")) {
            c.recordException(VoiceDrainGaveUp("$verdict after ${waitedMs}ms"))
        }
    }

    // --- Spotify ---------------------------------------------------------------
    // "Moose says Spotify isn't set up" left only `tool_dispatched: play_music` behind: nothing told
    // a missing app from a logged-out one from a consent never given. These keys and breadcrumbs
    // ride on every later report; the non-fatal below makes a connect failure upload on its own.

    // Failure classes already sent as a non-fatal this process, so a reconnect loop (onResume
    // fires on every foreground return) cannot flood the dashboard.
    private val reportedSpotifyFailures = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** "ClassName: message" (message capped at 60 chars), or "none". Pure. */
    internal fun spotifyFailureLabel(e: Throwable?): String {
        if (e == null) return "none"
        val msg = e.message?.take(60)?.replace('\n', ' ')
        return if (msg.isNullOrBlank()) e.javaClass.simpleName else "${e.javaClass.simpleName}: $msg"
    }

    /**
     * True the first time [failure]'s class is seen this process, false after (and for null).
     * Marks it as seen, so call it once per failure.
     */
    internal fun firstSpotifyFailureOfItsKind(failure: Throwable?): Boolean =
        failure != null && reportedSpotifyFailures.add(failure.javaClass.name)

    /** One App Remote connect attempt settled. */
    fun spotifyConnectFinished(connected: Boolean, showAuthView: Boolean, failure: Throwable?) {
        // Decided outside safe(): the rate-limit bookkeeping must not depend on Firebase being up.
        val report = !connected && firstSpotifyFailureOfItsKind(failure)
        safe {
            c.setCustomKey("spotify_app_remote_connected", connected)
            c.setCustomKey("spotify_last_connect_failure", spotifyFailureLabel(failure))
            c.log(
                "spotify_connect_finished: connected=$connected auth_view=$showAuthView " +
                    "failure=${spotifyFailureLabel(failure)}",
            )
            if (report && failure != null) {
                c.recordException(
                    RuntimeException("[spotify_app_remote_connect] ${spotifyFailureLabel(failure)}", failure),
                )
            }
        }
    }

    /** App Remote was torn down on purpose (CLEAR, or a disconnect). */
    fun spotifyDisconnected() = safe {
        c.setCustomKey("spotify_app_remote_connected", false)
        c.log("spotify_disconnected")
    }

    fun spotifyClientIdSet(set: Boolean) = safe { c.setCustomKey("spotify_client_id_set", set) }

    fun spotifyWebAuthorized(authorized: Boolean) = safe { c.setCustomKey("spotify_web_authorized", authorized) }

    /**
     * How play_music ended: an [outcome] class (e.g. `spotify_started`, `unavailable_NotLoggedInException`)
     * plus the driver's [query] (capped). Deliberately NOT the spoken line, which can carry closest-hit
     * lists and raw error bodies. [late] = settled after the tool had already returned.
     */
    fun playMusicResult(outcome: String, query: String, late: Boolean = false) = safe {
        c.log("play_music_result${if (late) "_late" else ""}: $outcome query=\"${query.take(60).replace('\n', ' ')}\"")
    }

    // --- Spotify app foregrounding + App Remote drops (2026-10-13 car report) -------------------
    // "Spotify came to the front on every voice tap, then Midnight restarted." These prove, on the
    // next drive, whether WE opened Spotify (and from where), or whether it surfaced on its own.

    /**
     * We started the Spotify app's activity (it comes to the front). [caller] is the code path
     * (pinned_tile, voice_open_music, app_tray...), [why] the reason. Also a non-fatal, rate
     * limited, so the report uploads without a crash.
     */
    fun spotifyAppLaunched(caller: String, why: String) = safe {
        c.setCustomKey("spotify_ui_last_launch", "$caller: $why")
        c.log("spotify_app_launched: caller=$caller why=$why voice_busy=${voiceBusy()}")
        if (nonFatalLimiter.allow("spotify_ui:$caller")) {
            c.recordException(SpotifyAppForegrounded("$caller: $why"))
        }
    }

    /** A play wanted the Spotify app's UI (no Connect device) but policy refused it. */
    fun spotifyAppLaunchRefused(caller: String, why: String) = safe {
        c.log("spotify_app_launch_refused: caller=$caller why=$why")
    }

    /** UI-less wake of the Spotify app (App Remote bind / media browser) before any launch. */
    fun spotifyUiLessWake(caller: String, browserFound: Boolean, deviceAppeared: Boolean) = safe {
        c.log("spotify_ui_less_wake: caller=$caller browser=$browserFound device_appeared=$deviceAppeared")
    }

    /** After opening Spotify we asked for Midnight back; [inFront] is whether it got there. */
    fun midnightReturn(caller: String, attempt: Int, inFront: Boolean) = safe {
        c.log("midnight_return: caller=$caller attempt=$attempt in_front=$inFront")
    }

    /**
     * A live App Remote connection dropped on its own (Spotify killed, restarted or closed the
     * link). [voiceBusy] = a voice turn was running, the "every voice turn disconnects" check.
     */
    fun spotifyAppRemoteLost(failure: Throwable?, voiceBusy: Boolean) = safe {
        c.setCustomKey("spotify_app_remote_connected", false)
        val label = "${spotifyFailureLabel(failure)} voice_busy=$voiceBusy"
        c.log("spotify_app_remote_lost: $label")
        if (nonFatalLimiter.allow("spotify_app_remote_lost")) c.recordException(SpotifyAppRemoteLost(label))
    }

    /** A silent App Remote reconnect after a drop was started (or skipped, with [why]). */
    fun spotifyReconnect(started: Boolean, why: String) = safe {
        c.log("spotify_reconnect: started=$started why=$why")
    }

    // --- Audio focus (voice turns) ---------------------------------------------------------------

    /**
     * One audio-focus step of a voice turn: request (with its result and what was playing),
     * abandon, a change delivered to us, or the post-turn resume. Breadcrumb only.
     */
    fun audioFocus(event: String) = safe {
        c.setCustomKey("audio_focus_last", event.take(100))
        c.log("audio_focus: $event")
    }

    /** VolumeSync was held for a voice turn, or released. */
    fun volumeSyncHold(held: Boolean) = safe { c.log("volume_sync_hold: $held") }

    /** VolumeSync's loop breaker tripped: something kept answering our re-assert. */
    fun volumeSyncBreakerTripped() = safe {
        c.log("volume_sync_breaker_tripped")
        if (nonFatalLimiter.allow("volume_sync_breaker")) c.recordException(VolumeSyncLoop("breaker tripped"))
    }

    /** A voice-session coroutine threw; the session was closed instead of crashing the launcher. */
    fun voiceSessionFault(where: String, e: Throwable) = safe {
        c.log("voice_session_fault: $where ${e.javaClass.simpleName}: ${e.message?.take(80)}")
        if (nonFatalLimiter.allow("voice_fault:$where:${e.javaClass.name}")) {
            c.recordException(RuntimeException("[voice_session_fault:$where] ${e.message}", e))
        }
    }

    /**
     * MainActivity left or regained the front. Paired with [spotifyAppLaunched]: a pause with no
     * launch breadcrumb before it means something other than our code put an app over Cruise.
     */
    fun launcherLifecycle(event: String) = safe {
        c.log("launcher_$event: voice_busy=${voiceBusy()}")
    }

    private fun voiceBusy(): Boolean = com.kevin.midnightai.service.ConversationState.isBusy

    // --- Handled errors -------------------------------------------------------
    // These show in the Crashlytics non-fatals section, not the crash count.
    // Use them for exceptions the app recovers from but that signal something wrong.

    /**
     * Records a handled (non-fatal) error under [tag] so it appears in the
     * Crashlytics non-fatals dashboard. The original exception is preserved as
     * the cause so the full stack trace is visible.
     */
    fun recordError(tag: String, e: Throwable) = safe {
        c.recordException(RuntimeException("[$tag] ${e.message}", e))
    }

    /**
     * Debug-only verification helper: drops a breadcrumb and records a test
     * non-fatal so real Firebase setup can be confirmed in the Crashlytics
     * console without force-crashing the app. Wired to a Setup dev-tools button.
     */
    fun testPing() = safe {
        c.log("test_ping breadcrumb")
        c.recordException(RuntimeException("Midnight AI Crashlytics test non-fatal (ignore)"))
    }

    /** At most one non-fatal per key per this window. */
    private const val NON_FATAL_WINDOW_MS = 10 * 60 * 1000L
}

/** The non-fatal recorded for an ask_engine failure; the class name is the whole payload. */
class AskEngineFailure(outcomeClass: String) : RuntimeException("AskEngineFailure($outcomeClass)")

/** The non-fatal recorded when the Live server cancels a pending tool call. */
class ToolCancelled(toolName: String) : RuntimeException("ToolCancelled($toolName)")

/** Non-fatal: we brought the Spotify app's activity to the front. */
class SpotifyAppForegrounded(detail: String) : RuntimeException("SpotifyAppForegrounded($detail)")

/** Non-fatal: a live App Remote connection dropped on its own. */
class SpotifyAppRemoteLost(detail: String) : RuntimeException("SpotifyAppRemoteLost($detail)")

/** Non-fatal: VolumeSync's re-assert loop breaker tripped. */
class VolumeSyncLoop(detail: String) : RuntimeException("VolumeSyncLoop($detail)")

/** Non-fatal: a model turn's playback underran or lost audio (choppy voice). */
class VoicePlaybackGlitch(detail: String) : RuntimeException("VoicePlaybackGlitch($detail)")

/** Non-fatal: the Live server interrupted Zero mid-reply. */
class VoiceInterrupted(detail: String) : RuntimeException("VoiceInterrupted($detail)")

/** Non-fatal: the mic re-opened before Zero's playback had drained. */
class VoiceDrainGaveUp(detail: String) : RuntimeException("VoiceDrainGaveUp($detail)")

/**
 * Lets one event per key through per [windowMs]; later ones in the window are dropped. Pure and
 * clock-injected so it is unit-testable without Firebase.
 */
class NonFatalRateLimiter(
    private val windowMs: Long,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val lastAllowed = HashMap<String, Long>()

    @Synchronized
    fun allow(key: String): Boolean {
        val t = now()
        val last = lastAllowed[key]
        if (last != null && t - last < windowMs) return false
        lastAllowed[key] = t
        return true
    }
}

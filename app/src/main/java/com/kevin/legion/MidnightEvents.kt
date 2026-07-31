package com.kevin.legion

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

    /**
     * Tag the device's raw `reqGlEsVersion` (e.g. "0x30000" for ES 3.0) so it's
     * readable from the Crashlytics dashboard without ADB. Embedded Mapbox nav
     * hard-requires ES 3.0; this is how a head unit's real capability gets
     * checked off-device. See `.scratch/mapbox-embedded-nav/issues/01-*`.
     */
    fun setGlEsVersion(hex: String) = safe { c.setCustomKey("gl_es_version", hex) }

    // --- Breadcrumb lifecycle events ------------------------------------------

    /** Gemini Live session socket opened and setup sent. */
    fun sessionStart() = safe { c.log("session_start") }

    /** Gemini Live session closed. [reason] is the close string from the socket. */
    fun sessionEnd(reason: String) = safe { c.log("session_end: $reason") }

    /** Navigation intent fired to a nav app with this destination query. */
    fun navStart(destination: String) = safe { c.log("nav_start: $destination") }

    /** ELM327 OBD adapter connected at this Bluetooth MAC. */
    fun obdConnected(mac: String) = safe { c.log("obd_connected: $mac") }

    /** OBD adapter disconnected (annotates any crash that follows a disconnect). */
    fun obdDisconnected(reason: String) = safe { c.log("obd_disconnected: $reason") }

    /**
     * A PID read came back as a failure while the socket was still up (breadcrumb,
     * cheap). [rawResponse] is the exact adapter reply that tripped
     * [com.kevin.legion.vehicle.ObdResponseParser.isFailureResponse] - this is
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
}

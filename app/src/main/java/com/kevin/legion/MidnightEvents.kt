package com.kevin.legion

import android.util.Log

/**
 * Structured breadcrumb logging. Backed by [Log.d] for now, NOT Crashlytics -
 * Firebase is deliberately not wired up in this repo yet (no `google-services.json`,
 * no Firebase dependency; see README.md). Every call keeps the same public
 * signature it had when this wrapped FirebaseCrashlytics in Midnight AI, so a
 * later Firebase setup only needs to change the bodies here, not any call site.
 *
 * Call [setCompanionName] / [setHasGeminiKey] once after the profile loads so
 * subsequent log lines carry that context (currently just logged, not tagged
 * onto anything persistent).
 */
object MidnightEvents {
    private const val TAG = "MidnightEvents"

    /**
     * Observability must never crash the app it's observing. Every call routes
     * through here so a bad state silently no-ops instead of throwing into a
     * hot path.
     */
    private inline fun safe(block: () -> Unit) {
        try { block() } catch (_: Throwable) {}
    }

    // --- Session identity keys -----------------------------------------------

    fun setBuildContext(buildType: String, deviceModel: String, isEmulator: Boolean) = safe {
        Log.d(TAG, "build_context: type=$buildType device=$deviceModel emulator=$isEmulator")
    }

    /** Tag context with the assistant's configured name. */
    fun setCompanionName(name: String) = safe { Log.d(TAG, "companion_name: $name") }

    /** Tag whether the user supplied their own Gemini key (vs. the BuildConfig key). */
    fun setHasGeminiKey(hasByo: Boolean) = safe { Log.d(TAG, "has_byo_key: $hasByo") }

    /** Tag the device's raw `reqGlEsVersion` (e.g. "0x30000" for ES 3.0). */
    fun setGlEsVersion(hex: String) = safe { Log.d(TAG, "gl_es_version: $hex") }

    // --- Breadcrumb lifecycle events ------------------------------------------

    /** Gemini Live session socket opened and setup sent. */
    fun sessionStart() = safe { Log.d(TAG, "session_start") }

    /** Gemini Live session closed. [reason] is the close string from the socket. */
    fun sessionEnd(reason: String) = safe { Log.d(TAG, "session_end: $reason") }

    /** Navigation intent fired to a nav app with this destination query. */
    fun navStart(destination: String) = safe { Log.d(TAG, "nav_start: $destination") }

    /** ELM327 OBD adapter connected at this Bluetooth MAC. */
    fun obdConnected(mac: String) = safe { Log.d(TAG, "obd_connected: $mac") }

    /** OBD adapter disconnected (annotates any crash that follows a disconnect). */
    fun obdDisconnected(reason: String) = safe { Log.d(TAG, "obd_disconnected: $reason") }

    /**
     * A PID read came back as a failure while the socket was still up. [rawResponse]
     * is the exact adapter reply that tripped
     * [com.kevin.legion.vehicle.ObdResponseParser.isFailureResponse].
     */
    fun obdPidSilence(count: Int, rawResponse: String) = safe {
        Log.d(TAG, "obd_pid_silence[$count]: '${rawResponse.take(60).replace('\n', ' ')}'")
    }

    /** The K-line reinit fired (3 consecutive silent PID reads). [trigger] is the raw
     * response that tripped it; [recovered] is whether the post-reinit 0100 looked valid. */
    fun obdReinit(trigger: String, recovered: Boolean) = safe {
        Log.w(TAG, "obd_reinit recovered=$recovered trigger='${trigger.take(60).replace('\n', ' ')}'")
    }

    /** REFRESH SCHEDULE in the logbook's DUE tab threw. */
    fun maintenanceRefreshFailed(e: Throwable) = safe {
        Log.w(TAG, "maintenance_refresh ${e.javaClass.simpleName}: ${e.message}", e)
    }

    /** A voice tool was dispatched. Useful for tracing what tool ran before a crash. */
    fun toolDispatched(toolName: String) = safe { Log.d(TAG, "tool_dispatched: $toolName") }

    /**
     * A completed voice turn: what Gemini heard, and how much mic audio we actually
     * forwarded. `forwardedBytes` near zero on a turn the driver spoke into IS the
     * diagnosis: it means the mic was closed or muted, not that Gemini misheard.
     * The transcript is the driver's own speech - debug-only, do not ship in release
     * without deciding that deliberately.
     */
    fun voiceTurn(transcript: String, forwardedBytes: Long) = safe {
        Log.d(TAG, "voice_turn: bytes=$forwardedBytes heard=\"${transcript.take(80)}\"")
    }

    /** A voice turn forwarded suspiciously few bytes - the "shows LISTENING but hears
     * nothing" symptom. */
    fun silentMicTurn(transcript: String, forwardedBytes: Long) = safe {
        Log.w(TAG, "silent_mic_turn: bytes=$forwardedBytes heard=\"${transcript.take(80)}\"")
    }

    /** Which branch the turn-complete state machine took, and the flags it decided from. */
    fun voiceTurnDecision(detail: String) = safe { Log.d(TAG, "voice_turn_decision: $detail") }

    /** The mic was opened for the driver, or closed. */
    fun micState(open: Boolean, why: String) = safe { Log.d(TAG, "mic_${if (open) "open" else "closed"}: $why") }

    // --- Handled errors -------------------------------------------------------

    /** Records a handled (non-fatal) error under [tag]. */
    fun recordError(tag: String, e: Throwable) = safe {
        Log.w(TAG, "[$tag] ${e.message}", e)
    }

    /** Debug-only verification helper. Wired to a Setup dev-tools button. */
    fun testPing() = safe {
        Log.d(TAG, "test_ping")
    }
}

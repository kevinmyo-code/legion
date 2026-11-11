package com.kevin.legion.service

import android.content.Context

/**
 * Whether the user has opted into the wake phrase ("hey <companion name>" - see [WakePhrases]).
 * Off by default (this object only stores the driver's own on/off choice),
 * supplements push-to-talk rather than replacing it. Mirrors [ProactivePreferences]'s
 * shape. No tier gating - the commercial model (billing/, RuntimeMode) was retired
 * in the 2026-07-31 pivot; every install is the same, BYO-key app.
 */
@Suppress("TooManyFunctions") // one getter and one setter per wake switch, nothing else
object WakeWordPreferences {
    private const val PREFS = "wake_word_preferences"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_TWO_STAGE = "two_stage"
    private const val KEY_MARKER = "two_stage_marker"
    private const val KEY_TRIPPED = "two_stage_tripped"
    private const val KEY_SAVE_SAMPLES = "save_live_samples"
    private const val KEY_RAW_CAPTURE = "raw_capture"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    /**
     * Ticket 18: true (default) runs the VAD, keyword-spotter, Vosk-confirm pipeline; false falls
     * back to the original Vosk-only path until the A25 run has proved the new one.
     */
    fun useTwoStage(context: Context): Boolean = prefs(context).getBoolean(KEY_TWO_STAGE, true)

    fun setUseTwoStage(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_TWO_STAGE, on).apply()
        if (on) setTwoStageTripped(context, false)
    }

    /**
     * Ticket 18 circuit breaker: set while the detector runs, cleared after clean decodes.
     * commit(), not apply(): the process may abort next.
     */
    fun twoStageMarker(context: Context): Boolean = prefs(context).getBoolean(KEY_MARKER, false)

    fun setTwoStageMarker(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_MARKER, on).commit()
    }

    /** True after the breaker turned the detector off; Settings says so in words. */
    fun twoStageTripped(context: Context): Boolean = prefs(context).getBoolean(KEY_TRIPPED, false)

    fun setTwoStageTripped(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_TRIPPED, on).commit()
    }

    /**
     * Ticket 20: save every wake utterance (audio and what the pipeline decided) for tuning. It
     * records his voice and the room, so it is off unless he turns it on.
     */
    fun saveLiveSamples(context: Context): Boolean = prefs(context).getBoolean(KEY_SAVE_SAMPLES, false)

    fun setSaveLiveSamples(context: Context, on: Boolean) =
        prefs(context).edit().putBoolean(KEY_SAVE_SAMPLES, on).apply()

    /**
     * Ticket 20: true captures with VOICE_RECOGNITION and no effects instead of the default
     * VOICE_COMMUNICATION with echo cancelling, noise suppression and gain, so the two can be
     * recorded and compared offline. A tuning switch, not a fix.
     */
    fun rawCapture(context: Context): Boolean = prefs(context).getBoolean(KEY_RAW_CAPTURE, false)

    fun setRawCapture(context: Context, on: Boolean) =
        prefs(context).edit().putBoolean(KEY_RAW_CAPTURE, on).apply()

    fun setEnabled(context: Context, on: Boolean) =
        prefs(context).edit().putBoolean(KEY_ENABLED, on).apply()

    /**
     * Which detector hears the wake phrase (Kevin 2026-11-10: "i rather openwakeword"). Unset reads
     * as [WakeEngineKind.DEFAULT], the one place the default lives.
     */
    fun engine(context: Context): WakeEngineKind =
        WakeEngineKind.fromKey(prefs(context).getString(KEY_ENGINE, null))

    fun setEngine(context: Context, kind: WakeEngineKind) =
        prefs(context).edit().putString(KEY_ENGINE, kind.key).apply()

    /** openWakeWord's score threshold, clamped to [WakeEngineKind.THRESHOLD_RANGE]. */
    fun owwThreshold(context: Context): Float =
        WakeEngineKind.clampThreshold(
            prefs(context).getFloat(KEY_OWW_THRESHOLD, OpenWakeWordDetector.DEFAULT_THRESHOLD),
        )

    fun setOwwThreshold(context: Context, value: Float) =
        prefs(context).edit().putFloat(KEY_OWW_THRESHOLD, WakeEngineKind.clampThreshold(value)).apply()

    private const val KEY_ENGINE = "engine"
    private const val KEY_OWW_THRESHOLD = "oww_threshold"
}

/**
 * The two wake detectors Settings chooses between. [CURRENT] is the VAD + keyword spotter + Vosk
 * confirm ([WakeTwoStageDetector], or Vosk alone when two-stage is off); [OPEN_WAKE_WORD] is
 * [OpenWakeWordDetector]. Stored by [key] so a rename never flips anyone's choice.
 */
enum class WakeEngineKind(val key: String, val label: String) {
    CURRENT("current", "Current (keyword spotter + confirm)"),
    OPEN_WAKE_WORD("openwakeword", "openWakeWord"),
    ;

    companion object {
        /**
         * THE default, in one place. CURRENT for the first build: openWakeWord is unproven on the
         * phone and has no real-voice numbers yet (report: openwakeword-1110.md). Flip this one
         * line once the phone checklist passes.
         */
        val DEFAULT = CURRENT

        val THRESHOLD_RANGE = 0.05f..0.95f

        fun fromKey(key: String?): WakeEngineKind = entries.firstOrNull { it.key == key } ?: DEFAULT

        fun clampThreshold(value: Float): Float =
            if (value.isNaN()) OpenWakeWordDetector.DEFAULT_THRESHOLD
            else value.coerceIn(THRESHOLD_RANGE.start, THRESHOLD_RANGE.endInclusive)
    }
}

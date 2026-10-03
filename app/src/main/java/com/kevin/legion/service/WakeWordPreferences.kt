package com.kevin.legion.service

import android.content.Context

/**
 * Whether the user has opted into the wake phrase ("excelsior" - see [WakePhrases]).
 * Off by default (this object only stores the driver's own on/off choice),
 * supplements push-to-talk rather than replacing it. Mirrors [ProactivePreferences]'s
 * shape. No tier gating - the commercial model (billing/, RuntimeMode) was retired
 * in the 2026-07-31 pivot; every install is the same, BYO-key app.
 */
object WakeWordPreferences {
    private const val PREFS = "wake_word_preferences"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_TWO_STAGE = "two_stage"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    /**
     * Ticket 18: true (default) runs the VAD, keyword-spotter, Vosk-confirm pipeline; false falls
     * back to the original Vosk-only path until the A25 run has proved the new one.
     */
    fun useTwoStage(context: Context): Boolean = prefs(context).getBoolean(KEY_TWO_STAGE, true)

    fun setUseTwoStage(context: Context, on: Boolean) =
        prefs(context).edit().putBoolean(KEY_TWO_STAGE, on).apply()

    fun setEnabled(context: Context, on: Boolean) =
        prefs(context).edit().putBoolean(KEY_ENABLED, on).apply()
}

package com.kevin.legion.service

import android.content.Context

/**
 * Whether the driver has opted into the custom wake word ("hey <assistant name>").
 * Off by default (this object only stores the driver's own on/off choice),
 * supplements push-to-talk rather than replacing it. Mirrors [ProactivePreferences]'s
 * shape. No tier gating - the commercial model (billing/, RuntimeMode) was retired
 * in the 2026-07-31 pivot; every install is the same, BYO-key app.
 */
object WakeWordPreferences {
    private const val PREFS = "wake_word_preferences"
    private const val KEY_ENABLED = "enabled"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, on: Boolean) =
        prefs(context).edit().putBoolean(KEY_ENABLED, on).apply()
}

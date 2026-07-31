package com.kevin.legion.service

import android.content.Context

/**
 * Whether the driver has opted into the custom wake word ("hey <companion name>",
 * see `.scratch/custom-wake-word/`). Off by default, paid-tier only (gated by
 * [com.kevin.legion.billing.RuntimeMode.BYO_KEY] at the call site, not here -
 * this object only stores the driver's own on/off choice), supplements push-to-talk
 * rather than replacing it. Mirrors [ProactivePreferences]'s shape.
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

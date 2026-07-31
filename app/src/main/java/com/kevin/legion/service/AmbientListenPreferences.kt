package com.kevin.legion.service

import android.content.Context

/**
 * Whether the driver has opted into ambient cabin listening ([AmbientListener],
 * 2026-07-22) - Moose transcribing cabin conversation locally and occasionally
 * reacting unprompted, "like having another person in the car." OFF BY DEFAULT,
 * and must stay that way: this picks up whoever's in the cabin, not just the
 * driver, so it needs a knowing, explicit opt-in rather than inheriting
 * [WakeWordPreferences]'s narrower consent (a fixed wake phrase discards
 * everything else; this keeps listening to ordinary conversation). Paid-tier
 * only, gated by [com.kevin.legion.billing.RuntimeMode.BYO_KEY] at the call
 * site, not here. Mirrors [WakeWordPreferences]'s shape.
 */
object AmbientListenPreferences {
    private const val PREFS = "ambient_listen_preferences"
    private const val KEY_ENABLED = "enabled"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, on: Boolean) =
        prefs(context).edit().putBoolean(KEY_ENABLED, on).apply()
}

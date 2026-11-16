package com.kevin.legion.service.testturn

import android.content.Context

/**
 * Settings, Assistant, Testing: "Allow test turns from a computer". Off by default and in every
 * build type (the phone runs `fast`, which is not debuggable, so a debug-only switch would be no
 * use). Off means the adb door answers `disabled` to everything but `status`.
 */
object TestTurnSettings {
    private const val PREFS = "test_turns"
    private const val KEY_ALLOWED = "allow_from_computer"

    fun allowed(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ALLOWED, false)

    fun setAllowed(context: Context, on: Boolean) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ALLOWED, on).apply()
        // Switched off mid-test: the test conversation ends now.
        if (!on) TestTurns.host?.end("setting_off")
    }
}

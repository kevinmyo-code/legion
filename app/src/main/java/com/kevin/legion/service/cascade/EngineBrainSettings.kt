package com.kevin.legion.service.cascade

import android.content.Context

/**
 * Settings > Assistant, "Answer on the engine" (engine-brain ticket 04, slice P1). Off by default
 * and in every build. Read once when a conversation opens, so a change applies to the next one.
 *
 * This switch and the phone's own loop are the named rollback for the engine brain: both go in
 * ticket 07, once the engine has answered every turn kind for long enough.
 */
object EngineBrainSettings {
    private const val PREFS = "engine_brain"
    private const val KEY_ON = "answer_on_engine"

    fun on(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ON, false)

    fun setOn(context: Context, on: Boolean) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ON, on).apply()
    }
}

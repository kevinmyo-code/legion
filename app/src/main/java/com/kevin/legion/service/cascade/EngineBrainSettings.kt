package com.kevin.legion.service.cascade

import android.content.Context

/**
 * "Answer on the engine" (engine-brain ticket 04, slice P1). Off by default and in every build.
 * Read once when a conversation opens, so a change applies to the next one.
 *
 * A DEVELOPER switch for now (review 1121, blocking A): no Settings row, because with the engine's
 * turn route still dark a visible switch would only make conversations worse. It is set over the
 * existing adb door (`TestTurnProvider`, shell or root only):
 *
 * `adb shell content call --uri content://com.kevin.legion.testturn --method engine_brain --arg on|off|status`
 *
 * When it becomes a Settings row, its words must say plainly that his voice AUDIO is sent to the
 * engine while it is on, and the engine keeps the transcript. The prefs file is excluded from
 * backups (`res/xml/backup_rules.xml`, `data_extraction_rules.xml`).
 *
 * This switch and the phone's own loop are the named rollback for the engine brain: both go in
 * ticket 07, once the engine has answered every turn kind for long enough.
 */
object EngineBrainSettings {
    private const val PREFS = "engine_brain"
    private const val KEY_ON = "answer_on_engine"
    const val ADB_METHOD = "engine_brain"

    fun on(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ON, false)

    fun setOn(context: Context, on: Boolean) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_ON, on).apply()
    }

    /** The adb door's `engine_brain` method: `on`, `off`, anything else reads it. */
    fun fromAdb(context: Context, arg: String?): Map<String, Any> {
        when (arg?.trim()?.lowercase()) {
            "on" -> setOn(context, true)
            "off" -> setOn(context, false)
            else -> Unit
        }
        return mapOf("ok" to true, "engine_brain" to on(context))
    }
}

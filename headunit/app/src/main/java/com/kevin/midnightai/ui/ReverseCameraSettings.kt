package com.kevin.midnightai.ui

import android.content.Context

/**
 * Persists the one-time reverse-camera handoff target - see
 * [ReverseCameraTarget]'s kdoc for why this has to be configured rather than
 * hardcoded. A single SharedPreferences string, encoded/decoded by
 * [ReverseCameraTargetCodec] so the parsing itself stays unit-testable.
 */
object ReverseCameraSettings {
    private const val PREFS = "reverse_camera"
    private const val KEY_TARGET = "target"

    fun get(context: Context): ReverseCameraTarget? =
        ReverseCameraTargetCodec.decode(
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TARGET, null),
        )

    fun set(context: Context, target: ReverseCameraTarget) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_TARGET, ReverseCameraTargetCodec.encode(target)).apply()
    }

    fun clear(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY_TARGET).apply()
    }
}

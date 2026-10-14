package com.kevin.midnightai.ui

import android.content.Context
import com.kevin.midnightai.service.LauncherSettings

/**
 * Whether this device's screen stays awake while Midnight AI is in front.
 *
 * **Why this only appeared 2026-07-25.** It never mattered: the Cherokee's head
 * unit is always on, powered by the ignition, and has no screen timeout worth
 * fighting. Running the app as a portable head unit on a phone in a cradle changes
 * that completely - a phone dims and locks after 30 seconds, which on a driving
 * surface means the driver reaches for it, and the whole point of the Cruise HUD is
 * that they do not.
 *
 * **The default is derived, not guessed.** An install that is the device's HOME app
 * is by definition acting as a head unit, so it defaults to staying awake; an
 * install that is just an app on someone's phone defaults to normal phone behaviour
 * and will not quietly hold their screen on. The explicit toggle overrides either
 * way, and once set it wins forever - so setting the app as launcher later does not
 * silently reverse a driver's deliberate "no".
 */
object ScreenAwakePreferences {
    private const val PREFS = "screen_awake"
    private const val KEY_ENABLED = "enabled"

    fun isEnabled(context: Context): Boolean {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.contains(KEY_ENABLED)) return prefs.getBoolean(KEY_ENABLED, false)
        return LauncherSettings.isDefaultLauncher(context)
    }

    /** True once the driver has answered for themselves, so the default stops applying. */
    fun isExplicit(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .contains(KEY_ENABLED)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).apply()
    }
}

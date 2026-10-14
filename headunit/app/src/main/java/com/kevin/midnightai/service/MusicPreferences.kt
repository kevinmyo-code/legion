package com.kevin.midnightai.service

import android.content.Context

/**
 * The driver's choice of whether [LiveToolbox]'s open_music/play_music tools
 * are allowed to launch a music app installed locally on the head unit (tier
 * 2), or should leave phone-Bluetooth streaming (tier 1) as the only path.
 * Plain app-global SharedPreferences.
 *
 * Off by default: most drivers stream from their phone over Bluetooth only,
 * and MUSIC_FALLBACKS tries Spotify first if anything is installed locally -
 * launching that unprompted could hijack playback away from the phone's
 * session onto a separate (possibly logged-out, possibly different-account)
 * local install the driver never asked Zero to touch.
 */
object MusicPreferences {
    private const val PREFS = "music_preferences"
    private const val KEY_HEAD_UNIT_APP_ENABLED = "head_unit_app_enabled"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun headUnitAppEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_HEAD_UNIT_APP_ENABLED, false)

    fun setHeadUnitAppEnabled(context: Context, enabled: Boolean) =
        prefs(context).edit().putBoolean(KEY_HEAD_UNIT_APP_ENABLED, enabled).apply()
}

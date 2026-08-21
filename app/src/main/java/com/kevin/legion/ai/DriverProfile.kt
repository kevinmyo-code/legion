package com.kevin.legion.ai

import android.content.Context

/**
 * The driver's own details, edited in the control panel ("About You") and fed
 * into every session's system instruction so Zero addresses them by name and
 * knows the context they chose to share. App-global (one driver), so it's a
 * small SharedPreferences store rather than a per-vehicle Room row - no
 * migration, and it survives switching cars.
 *
 * Distinct from long-term memories ([AriaBrain.remember]): those are facts Zero
 * picks up in conversation; this is the stable profile the driver sets by hand.
 */
object DriverProfile {
    private const val PREFS = "driver_profile"
    private const val KEY_NAME = "name"
    private const val KEY_ABOUT = "about"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun name(context: Context): String = prefs(context).getString(KEY_NAME, "").orEmpty()

    fun about(context: Context): String = prefs(context).getString(KEY_ABOUT, "").orEmpty()

    fun save(context: Context, name: String, about: String) {
        prefs(context).edit()
            .putString(KEY_NAME, name.trim())
            .putString(KEY_ABOUT, about.trim())
            .apply()
    }

    /** Wipes the driver's profile (used by the reset flows). */
    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }

    /**
     * A short prompt fragment describing the driver for the system instruction,
     * or null if nothing has been set (so the prompt stays clean when empty).
     */
    fun promptFragment(context: Context): String? {
        val name = name(context)
        val about = about(context)
        if (name.isBlank() && about.isBlank()) return null
        return buildString {
            if (name.isNotBlank()) append("The user's name is $name. ")
            if (about.isNotBlank()) append("Things the user wants you to know about them: $about")
        }.trim()
    }
}

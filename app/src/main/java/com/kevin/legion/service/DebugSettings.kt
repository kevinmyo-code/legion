package com.kevin.legion.service

import android.content.Context

/** Small toggles for debug/optional UI (currently the Zero subtitle overlay). */
object DebugSettings {
    private const val PREFS = "debug_settings"
    private const val KEY_SUBTITLES = "subtitles"
    private const val KEY_RECALL_ALERTS = "recall_alerts"
    private const val KEY_OBD_EMULATOR = "obd_emulator"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Show a Stardew-style subtitle of what Zero is saying under the floating button. */
    fun subtitlesEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_SUBTITLES, false)

    fun setSubtitles(context: Context, on: Boolean) =
        prefs(context).edit().putBoolean(KEY_SUBTITLES, on).apply()

    /** Announce open NHTSA recalls once at startup (off by default - it's a network call). */
    fun recallAlertsEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_RECALL_ALERTS, false)

    fun setRecallAlerts(context: Context, on: Boolean) =
        prefs(context).edit().putBoolean(KEY_RECALL_ALERTS, on).apply()

    /**
     * When on, [com.kevin.legion.vehicle.ObdBluetoothManager] connects to
     * an ELM327 emulator over TCP instead of scanning for a real Bluetooth
     * dongle - lets OBD parsing/PID/tool-call code be exercised without a car.
     * Debug builds only; the UI toggle for this is itself gated on
     * BuildConfig.DEBUG so it's unreachable in a release build regardless of
     * this stored value.
     */
    fun obdEmulatorEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_OBD_EMULATOR, false)

    fun setObdEmulatorEnabled(context: Context, on: Boolean) =
        prefs(context).edit().putBoolean(KEY_OBD_EMULATOR, on).apply()
}

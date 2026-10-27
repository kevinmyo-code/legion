package com.kevin.legion.service

import android.content.Context
import com.kevin.legion.ai.SubAgent

/** Small toggles for debug/optional UI (currently the Zero subtitle overlay). */
object DebugSettings {
    private const val PREFS = "debug_settings"
    private const val KEY_SUBTITLES = "subtitles"
    private const val KEY_RECALL_ALERTS = "recall_alerts"
    private const val KEY_OBD_EMULATOR = "obd_emulator"
    private const val KEY_ASK_ENGINE_MODEL = "ask_engine_model"

    /**
     * The two models ask_engine's sub-agent may run on (2026-10-27, latency comparison). 3.8 Flash
     * takes the same request body: investigate sends no generationConfig, so no thinkingConfig
     * and no temperature, which is what commit 29057645 found 3.8 requires.
     */
    const val ASK_ENGINE_MODEL_ALT = "gemini-3.8-flash"
    val ASK_ENGINE_MODELS = listOf(SubAgent.DEFAULT_MODEL, ASK_ENGINE_MODEL_ALT)

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

    /**
     * Which model serves ask_engine. Read in every build type; only the row that changes it is
     * debug-only (KeyScreen's debug section), so a value set on a debug build carries over when
     * `fast` is installed on top. Anything not in [ASK_ENGINE_MODELS] reads as the default.
     */
    fun askEngineModel(context: Context): String =
        prefs(context).getString(KEY_ASK_ENGINE_MODEL, null)
            ?.takeIf { it in ASK_ENGINE_MODELS } ?: SubAgent.DEFAULT_MODEL

    fun setAskEngineModel(context: Context, model: String) =
        prefs(context).edit().putString(KEY_ASK_ENGINE_MODEL, model).apply()
}

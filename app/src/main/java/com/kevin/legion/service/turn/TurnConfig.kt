package com.kevin.legion.service.turn

import android.content.Context

/** Where the conversation's audio goes, as far as turn-taking cares. */
internal enum class AudioRoute { PHONE_SPEAKER, WIRED_HEADSET, BLUETOOTH }

/**
 * How one session takes turns. Decided once when the socket opens ([manualActivity] lives in the
 * Live `setup` and cannot change mid-socket) and re-decided for barge-in when a conversation begins
 * (the route can change between a prewarm and a tap).
 */
internal data class TurnConfig(
    /** Server VAD off; the phone sends activityStart/activityEnd from [EndOfTurnPolicy]. */
    val manualActivity: Boolean,
    /** Mic stays open while Alfred speaks and [BargeInGate] may stop him. */
    val bargeIn: Boolean,
    /** Smart Turn may extend a turn; false is silence-only (still manual activity). */
    val smartTurn: Boolean,
    /** Why a part is off, for the log line. */
    val note: String,
) {
    companion object {
        val SERVER_VAD = TurnConfig(manualActivity = false, bargeIn = false, smartTurn = false, note = "server VAD")

        /**
         * Kevin's rulings, 2026-10-27: smart end of turn straight to on; call-style barge-in; both off
         * on car Bluetooth in v1 (those sessions keep today's half-duplex server VAD); a wired headset
         * keeps barge-in. Both need Silero: without it this is today's setup.
         */
        fun decide(
            smartSetting: Boolean,
            bargeSetting: Boolean,
            route: AudioRoute,
            sileroReady: Boolean,
            smartTurnReady: Boolean,
        ): TurnConfig = when {
            route == AudioRoute.BLUETOOTH -> SERVER_VAD.copy(note = "Bluetooth route: half-duplex, server VAD")
            !sileroReady -> SERVER_VAD.copy(note = "Silero unavailable: server VAD")
            else -> TurnConfig(
                manualActivity = smartSetting,
                bargeIn = bargeSetting,
                smartTurn = smartSetting && smartTurnReady,
                note = buildString {
                    append(if (smartSetting) "local end of turn" else "server VAD")
                    if (smartSetting && !smartTurnReady) append(" (Smart Turn unavailable: silence only)")
                    append(if (bargeSetting) ", barge-in on ${route.name.lowercase()}" else ", half-duplex")
                },
            )
        }
    }
}

/**
 * The two Settings > Assistant switches (hands path, ADR 0035). Read when a session starts; a change
 * applies to the next socket.
 */
object VoiceTurnSettings {
    private const val PREFS = "voice_turn_settings"
    private const val KEY_SMART_END = "smart_end_of_turn"
    private const val KEY_BARGE_IN = "barge_in"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Default ON (Kevin, 2026-10-27: "straight to on", no shadow mode). */
    fun smartEndOfTurn(context: Context): Boolean = prefs(context).getBoolean(KEY_SMART_END, true)
    fun setSmartEndOfTurn(context: Context, on: Boolean) = prefs(context).edit().putBoolean(KEY_SMART_END, on).apply()

    /** Default ON; never applies over Bluetooth ([TurnConfig.decide]). */
    fun bargeIn(context: Context): Boolean = prefs(context).getBoolean(KEY_BARGE_IN, true)
    fun setBargeIn(context: Context, on: Boolean) = prefs(context).edit().putBoolean(KEY_BARGE_IN, on).apply()
}

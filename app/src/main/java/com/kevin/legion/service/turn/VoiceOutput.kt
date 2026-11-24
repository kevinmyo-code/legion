package com.kevin.legion.service.turn

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Where his voice actually goes in a conversation, after headsets, Bluetooth and phone calls have their say. */
enum class VoiceOutlet { LOUDSPEAKER, EARPIECE, WIRED, BLUETOOTH, PHONE_CALL }

/**
 * How a conversation opened. Only a tap in the app (phone in hand, screen on) may use the earpiece:
 * a wake word from across the room, Brief me, a check-in, the incoming-call announcement, a car or
 * notification start would otherwise speak into an earpiece nobody is holding.
 */
internal enum class Opening { APP_TAP, WAKE_WORD, LINE, OTHER_TAP, TEST }

/**
 * What an open conversation's audio actually does, from the platform's readback: on the earpiece, or
 * [refused] (the earpiece was asked for inside call audio and the phone did not route to it).
 */
internal data class LiveOutput(val onEarpiece: Boolean, val refused: Boolean)

/** The pure decisions behind the earpiece: no Android types, so every case is a unit test. */
internal object VoiceOutputRule {

    /**
     * Headsets and Bluetooth win over the call; a phone call on the line means hands off. [call] is
     * THIS conversation's own intent (the Call button), never a saved setting: a plain orb tap is
     * always `false` and therefore always the loudspeaker.
     */
    fun outlet(call: Boolean, route: AudioRoute, inCall: Boolean, hasEarpiece: Boolean): VoiceOutlet = when {
        inCall -> VoiceOutlet.PHONE_CALL
        route == AudioRoute.BLUETOOTH -> VoiceOutlet.BLUETOOTH
        route == AudioRoute.WIRED_HEADSET -> VoiceOutlet.WIRED
        call && hasEarpiece -> VoiceOutlet.EARPIECE
        else -> VoiceOutlet.LOUDSPEAKER
    }

    /**
     * The opening kind from what the session knows: a test door, a line it was asked for first (Brief
     * me, a check-in, a proactive line, the incoming-call announcement), the wake word, a tap in the
     * app, else another tap (car, notification, media button).
     */
    fun opening(inHand: Boolean, fromWakeWord: Boolean, hasOpener: Boolean, test: Boolean): Opening = when {
        test -> Opening.TEST
        hasOpener -> Opening.LINE
        fromWakeWord -> Opening.WAKE_WORD
        inHand -> Opening.APP_TAP
        else -> Opening.OTHER_TAP
    }

    /** The earpiece applies only to a conversation he started by a tap in the app with the screen on. */
    fun earpieceAllowed(opening: Opening, screenOn: Boolean): Boolean = opening == Opening.APP_TAP && screenOn

    /**
     * Whether the conversation enters call audio ([CallAudioMode]): for barge-in as before, and for the
     * earpiece, which only exists as a communication route.
     */
    fun callAudio(barge: Boolean, earpiece: Boolean): Boolean = barge || earpiece

    /**
     * The communication device type to select from [available] types: a wired headset first, then the
     * earpiece when [earpiece] and the phone has one, else the loudspeaker. Null when none is offered.
     */
    fun device(available: List<Int>, earpiece: Boolean): Int? =
        available.firstOrNull { it in WIRED_TYPES }
            ?: available.firstOrNull { earpiece && it == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
            ?: available.firstOrNull { it == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }

    internal val WIRED_TYPES = setOf(
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_USB_HEADSET,
    )
}

/**
 * What the open conversation's audio does, and the in-call control that moves it.
 *
 * **There is no saved choice any more** (2026-11-24, the Call button): the earpiece is a per-call
 * intent passed to the session at construction and held by its own [com.kevin.legion.service.cascade.EarpieceRoute].
 * [routeChanges] is a CHANGE SIGNAL only, never the truth: the in-call Speaker / Earpiece control
 * asks here, the open conversation applies it, and a conversation that never hears an ask keeps the
 * intent it opened with. The old `voice_output` preference is deleted by [forgetSavedChoice], so a
 * phone that saved CALL does not send every orb tap to the earpiece after the toggle went.
 */
internal object VoiceOutput {
    private const val LEGACY_PREFS = "voice_output"

    private val asks = MutableStateFlow<Boolean?>(null)

    /** Asks the open conversation to move its route: true the earpiece, false the loudspeaker. */
    val routeChanges: StateFlow<Boolean?> = asks.asStateFlow()

    /** The in-call control: a conversation collecting [routeChanges] moves without restarting. */
    fun askRoute(earpiece: Boolean) {
        asks.value = earpiece
    }

    private val liveState = MutableStateFlow<LiveOutput?>(null)

    /** What the open conversation's audio does; null with none open (set by its earpiece follower). */
    val live: StateFlow<LiveOutput?> = liveState.asStateFlow()

    fun publish(output: LiveOutput?) {
        liveState.value = output
    }

    /** Drops the retired SPEAKER / CALL preference. Idempotent, so it runs at every start. */
    fun forgetSavedChoice(context: Context) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                context.applicationContext.deleteSharedPreferences(LEGACY_PREFS)
            }
        }
    }

    /** True when the phone has an earpiece to route to (false on a tablet). */
    fun hasEarpiece(audio: AudioManager): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audio.availableCommunicationDevices.any { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
        } else {
            audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
        }
    }.getOrDefault(false)
}

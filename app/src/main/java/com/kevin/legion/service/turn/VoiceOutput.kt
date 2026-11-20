package com.kevin.legion.service.turn

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Where Alfred's voice comes out when nothing plugged in decides it: the loudspeaker, or the earpiece
 * with the phone held to the ear like a call.
 */
internal enum class OutputChoice { SPEAKER, CALL }

/** Where his voice actually goes in a conversation, after headsets, Bluetooth and phone calls have their say. */
internal enum class VoiceOutlet { LOUDSPEAKER, EARPIECE, WIRED, BLUETOOTH, PHONE_CALL }

/**
 * The toggle beside the orb. [enabled] is false when a headset, Bluetooth or a phone call decides the
 * route; [label] then says which, so the toggle never claims a route it does not have.
 */
internal data class OutputToggle(
    val choice: OutputChoice,
    val outlet: VoiceOutlet,
    val enabled: Boolean,
    val label: String,
)

/** The pure decisions behind the earpiece toggle: no Android types, so every case is a unit test. */
internal object VoiceOutputRule {

    /** Headsets and Bluetooth win over the choice; a phone call on the line means hands off. */
    fun outlet(choice: OutputChoice, route: AudioRoute, inCall: Boolean, hasEarpiece: Boolean): VoiceOutlet = when {
        inCall -> VoiceOutlet.PHONE_CALL
        route == AudioRoute.BLUETOOTH -> VoiceOutlet.BLUETOOTH
        route == AudioRoute.WIRED_HEADSET -> VoiceOutlet.WIRED
        choice == OutputChoice.CALL && hasEarpiece -> VoiceOutlet.EARPIECE
        else -> VoiceOutlet.LOUDSPEAKER
    }

    /** The toggle's face; null hides it (a tablet: no earpiece to choose). */
    fun toggle(choice: OutputChoice, route: AudioRoute, inCall: Boolean, hasEarpiece: Boolean): OutputToggle? {
        if (!hasEarpiece) return null
        val outlet = outlet(choice, route, inCall, hasEarpiece)
        val label = when (outlet) {
            VoiceOutlet.LOUDSPEAKER -> "Speaker"
            VoiceOutlet.EARPIECE -> "Call"
            VoiceOutlet.WIRED -> "Headphones"
            VoiceOutlet.BLUETOOTH -> "Bluetooth"
            VoiceOutlet.PHONE_CALL -> "On a call"
        }
        val enabled = outlet == VoiceOutlet.LOUDSPEAKER || outlet == VoiceOutlet.EARPIECE
        return OutputToggle(choice, outlet, enabled, label)
    }

    /**
     * Whether the conversation enters call audio ([CallAudioMode]): for barge-in as before, and for the
     * earpiece, which only exists as a communication route.
     */
    fun callAudio(barge: Boolean, outlet: VoiceOutlet): Boolean = barge || outlet == VoiceOutlet.EARPIECE

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
 * The persisted choice (default SPEAKER) and its live value, shared by the toggle and an open
 * conversation, which follows a flip without restarting.
 */
internal object VoiceOutput {
    private const val PREFS = "voice_output"
    private const val KEY_CHOICE = "choice"

    private val state = MutableStateFlow(OutputChoice.SPEAKER)
    @Volatile private var loaded = false

    /** The live choice. Read [choice] once first so it holds the saved value. */
    val flow: StateFlow<OutputChoice> = state.asStateFlow()

    fun choice(context: Context): OutputChoice {
        if (!loaded) {
            val saved = prefs(context).getString(KEY_CHOICE, null)
            state.value = OutputChoice.entries.firstOrNull { it.name == saved } ?: OutputChoice.SPEAKER
            loaded = true
        }
        return state.value
    }

    fun set(context: Context, choice: OutputChoice) {
        prefs(context).edit().putString(KEY_CHOICE, choice.name).apply()
        loaded = true
        state.value = choice
    }

    /** True when the phone has an earpiece to route to (false on a tablet). */
    fun hasEarpiece(audio: AudioManager): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audio.availableCommunicationDevices.any { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
        } else {
            audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
        }
    }.getOrDefault(false)

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

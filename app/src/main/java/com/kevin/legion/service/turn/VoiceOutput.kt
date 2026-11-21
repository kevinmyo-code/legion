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
enum class OutputChoice { SPEAKER, CALL }

/** Where his voice actually goes in a conversation, after headsets, Bluetooth and phone calls have their say. */
enum class VoiceOutlet { LOUDSPEAKER, EARPIECE, WIRED, BLUETOOTH, PHONE_CALL }

/**
 * The toggle under the orb. [choice] is the choice a tap flips from, [outlet] where the voice
 * goes, [enabled] false when a headset, Bluetooth or a phone call decides the route; [label] and
 * [detail] (what TalkBack says) name the real route, so the toggle never claims one it does not have.
 */
data class OutputToggle(
    val choice: OutputChoice,
    val outlet: VoiceOutlet,
    val enabled: Boolean,
    val label: String,
    val detail: String,
)

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

    /** CALL applies only to a conversation he started by a tap in the app with the screen on. */
    fun earpieceAllowed(opening: Opening, screenOn: Boolean): Boolean = opening == Opening.APP_TAP && screenOn

    /**
     * The toggle's face; null hides it (a tablet: no earpiece to choose). With a conversation open
     * ([live] not null) it shows what the audio does: CALL that is not on the earpiece reads
     * "Earpiece unavailable" when the phone refused it, else "Speaker" (a conversation he did not
     * start by a tap, or one still waiting to move).
     */
    fun toggle(
        choice: OutputChoice,
        route: AudioRoute,
        inCall: Boolean,
        hasEarpiece: Boolean,
        live: LiveOutput? = null,
    ): OutputToggle? {
        if (!hasEarpiece) return null
        val outlet = outlet(choice, route, inCall, hasEarpiece)
        if (outlet == VoiceOutlet.EARPIECE && live != null && !live.onEarpiece) {
            return if (live.refused) {
                OutputToggle(OutputChoice.CALL, VoiceOutlet.LOUDSPEAKER, true, "Earpiece unavailable", REFUSED)
            } else {
                OutputToggle(OutputChoice.SPEAKER, VoiceOutlet.LOUDSPEAKER, true, "Speaker", SPEAKER_NOW)
            }
        }
        return when (outlet) {
            VoiceOutlet.LOUDSPEAKER -> OutputToggle(choice, outlet, true, "Speaker", SPEAKER)
            VoiceOutlet.EARPIECE -> OutputToggle(choice, outlet, true, "Call", EARPIECE)
            VoiceOutlet.WIRED -> OutputToggle(choice, outlet, false, "Headphones", WIRED)
            VoiceOutlet.BLUETOOTH -> OutputToggle(choice, outlet, false, "Bluetooth", BLUETOOTH)
            VoiceOutlet.PHONE_CALL -> OutputToggle(choice, outlet, false, "On a call", PHONE_CALL)
        }
    }

    /** What a tap on the toggle saves: the other choice from the one it shows. */
    fun flipped(shown: OutputChoice): OutputChoice =
        if (shown == OutputChoice.SPEAKER) OutputChoice.CALL else OutputChoice.SPEAKER

    /**
     * Whether the conversation enters call audio ([CallAudioMode]): for barge-in as before, and for the
     * earpiece, which only exists as a communication route.
     */
    fun callAudio(barge: Boolean, earpiece: Boolean): Boolean = barge || earpiece

    private const val SPEAKER = "Alfred's voice on the speaker. Tap for call mode, phone at your ear"
    private const val EARPIECE = "Alfred's voice on the earpiece, call mode. Tap for the speaker"
    private const val SPEAKER_NOW =
        "Alfred's voice on the speaker for this conversation. Tap to move it to the earpiece"
    private const val REFUSED =
        "Earpiece unavailable: this phone kept Alfred's voice on the speaker. Tap for speaker mode"
    private const val WIRED = "Alfred's voice on headphones. Speaker or call mode applies when they are unplugged"
    private const val BLUETOOTH = "Alfred's voice on Bluetooth. Speaker or call mode applies when it disconnects"
    private const val PHONE_CALL = "On a phone call. Speaker or call mode applies after it"

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

    private val tapCount = MutableStateFlow(0)

    /** Counts taps on the toggle: an open conversation applies the choice on each one, either way. */
    val taps: StateFlow<Int> = tapCount.asStateFlow()

    private val liveState = MutableStateFlow<LiveOutput?>(null)

    /** What the open conversation's audio does; null with none open (set by its earpiece follower). */
    val live: StateFlow<LiveOutput?> = liveState.asStateFlow()

    fun publish(output: LiveOutput?) {
        liveState.value = output
    }

    /** A tap on the toggle: saves the other choice from the one [shown], and tells the conversation. */
    fun flip(context: Context, shown: OutputChoice) {
        set(context, VoiceOutputRule.flipped(shown))
        tapCount.value += 1
    }

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

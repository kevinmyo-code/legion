package com.kevin.legion.service

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log

/**
 * Puts a connected call on the loudspeaker (2026-08-21, Kevin: *"i say yeah pick it up please and
 * put it on speaker"*).
 *
 * ### Why this is asked for as part of answering, not as its own tool
 *
 * That sentence is one intention with two actions, and the actions are **ordered**: there is no
 * call to route until one is answered. As two separate tools the live model could easily call the
 * speaker one first, against a call that does not exist yet, and get a failure that means nothing.
 * So `answer_call` takes a `speaker` flag and does them in the only order that works.
 *
 * ### Routing is REQUESTED, never assumed
 *
 * `LegionConnectionService`'s doc carries a standing warning from this project's own Android Auto
 * research: *"Never touch audio routing directly ... `setCommunicationDevice` and
 * `startBluetoothSco` are forbidden once Telecom owns the call."* That warning is about a call
 * LEGION itself owns through its `ConnectionService`, which is a different situation from a
 * cellular call owned by the system dialer - but it is close enough that assuming success here
 * would be exactly the kind of guess this codebase keeps getting burned by.
 *
 * So [toSpeaker] **reads the route back** and reports what actually happened. The dialer may
 * refuse, may override a moment later, and a Bluetooth headset or a car may legitimately win. All
 * three come back as "it did not switch" rather than as a claim that it did.
 *
 * There is a real gap even so, and it is stated rather than hidden: the readback is taken
 * immediately, so a dialer that re-routes a second later would not be caught. What that costs is
 * bounded - Kevin hears the wrong speaker and says so - and the alternative, polling audio state
 * for seconds while he is trying to talk to someone, is worse.
 */
object CallAudioRoute {

    private const val TAG = "CallAudioRoute"

    /** What happened to the audio route, in a form a tool result can state without overclaiming. */
    sealed class Result {
        data object OnSpeaker : Result()
        /** The request was made and the route did not end up on the built-in speaker. [detail]
         * says what it is on instead, when that is knowable. */
        data class NotSwitched(val detail: String) : Result()
    }

    private fun audioManager(context: Context): AudioManager? =
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    /**
     * Asks for the loudspeaker, then checks.
     *
     * Two APIs by version: `setCommunicationDevice` from API 31 (the supported one on the A25's
     * Android 16), and the deprecated `isSpeakerphoneOn` below that. Both are wrapped - a throw
     * here would take down a turn that is mid-call.
     */
    fun toSpeaker(context: Context): Result {
        val am = audioManager(context) ?: return Result.NotSwitched("no audio service")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val speaker = runCatching {
                am.availableCommunicationDevices
                    .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            }.getOrNull()
                ?: return Result.NotSwitched("this phone reports no built-in speaker for calls")

            val requested = runCatching { am.setCommunicationDevice(speaker) }.getOrElse {
                Log.d(TAG, "setCommunicationDevice threw: ${it.message}")
                false
            }
            if (!requested) return Result.NotSwitched("the system refused the speaker request")

            // The readback. This is the whole point of the class - see its doc.
            val current = runCatching { am.communicationDevice }.getOrNull()
            return if (current?.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
                Result.OnSpeaker
            } else {
                Result.NotSwitched(describeDevice(current))
            }
        }

        @Suppress("DEPRECATION")
        return runCatching {
            am.isSpeakerphoneOn = true
            if (am.isSpeakerphoneOn) Result.OnSpeaker
            else Result.NotSwitched("the system did not switch the route")
        }.getOrElse {
            Log.d(TAG, "setSpeakerphoneOn threw: ${it.message}")
            Result.NotSwitched("the system refused the speaker request")
        }
    }

    /** Names the route the call is actually on, so a failure says something useful. */
    private fun describeDevice(device: AudioDeviceInfo?): String = when (device?.type) {
        null -> "the call stayed on its current route"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ->
            "the call is on a Bluetooth device - a headset or the car - and that took priority"
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES ->
            "the call is on wired headphones"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "the call is still on the earpiece"
        else -> "the call stayed on its current route"
    }

    /** The clause appended to an answer result when the speaker was asked for. Pure and
     * unit-testable, and it must never phrase a failed route as a success. */
    fun describe(result: Result): String = when (result) {
        Result.OnSpeaker -> " It is on speaker."
        is Result.NotSwitched -> " It is NOT on speaker - ${result.detail}."
    }
}

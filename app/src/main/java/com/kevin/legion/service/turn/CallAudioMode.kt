package com.kevin.legion.service.turn

import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log

/**
 * Call-style audio for barge-in (Kevin, 2026-10-27: "sounds fine" on the speaker): while a
 * conversation with barge-in runs, the phone is in MODE_IN_COMMUNICATION with the voice-call route
 * (wired headset if plugged, else the loudspeaker), so the platform AEC has Alfred's audio as its
 * reference. The playback track switches to USAGE_VOICE_COMMUNICATION alongside it.
 *
 * Restored on every exit (park, close, destroy). [ownCommActive] is what lets dumb mode
 * (`DumbBounce`, ADR 0062) tell this mode from a real call. Never entered during a Telecom-owned call
 * (`LegionConnectionService`'s standing warning): the caller checks [route] and the call state.
 */
internal class CallAudioMode(private val audio: AudioManager) {
    private var active = false
    private var prevMode = AudioManager.MODE_NORMAL
    private var prevSpeaker = false
    private var bluetoothSco = false

    /** True while this conversation listens through a Bluetooth hands-free mic ([enterBluetooth]). */
    val onBluetoothMic: Boolean @Synchronized get() = active && bluetoothSco

    @Synchronized
    fun enter(): String {
        if (active) return "already active"
        return runCatching {
            prevMode = audio.mode
            @Suppress("DEPRECATION")
            prevSpeaker = audio.isSpeakerphoneOn
            active = true
            ownCommActive = true
            audio.mode = AudioManager.MODE_IN_COMMUNICATION
            val routed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val devices = audio.availableCommunicationDevices
                val target = devices.firstOrNull { it.type in WIRED }
                    ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                "device=${target?.type} ok=${target?.let { audio.setCommunicationDevice(it) } ?: false}"
            } else {
                @Suppress("DEPRECATION")
                audio.isSpeakerphoneOn = !hasWired(audio)
                "speakerphoneOn=${!hasWired(audio)}"
            }
            "entered prevMode=$prevMode mode=${audio.mode} $routed"
        }.getOrElse { "enter failed: ${it.message}" }.also { Log.i(TAG, it) }
    }

    /**
     * Call mode with a Bluetooth hands-free device as the communication device, so the
     * conversation's mic is the car's (or headset's) and Alfred's voice rides the same link
     * ([BluetoothMic] has the costs). Returns a log line; [onBluetoothMic] says whether it took.
     */
    @Synchronized
    fun enterBluetooth(): String {
        if (active) return "already active"
        return runCatching {
            prevMode = audio.mode
            @Suppress("DEPRECATION")
            prevSpeaker = audio.isSpeakerphoneOn
            audio.mode = AudioManager.MODE_IN_COMMUNICATION
            val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val target = pickHandsFree(audio)
                target != null && audio.setCommunicationDevice(target)
            } else {
                @Suppress("DEPRECATION")
                audio.startBluetoothSco()
                @Suppress("DEPRECATION")
                audio.isBluetoothScoOn = true
                true
            }
            active = true
            bluetoothSco = ok
            ownCommActive = true
            if (!ok) {
                // No hands-free device took: back to normal so the phone mic carries on as before.
                exit("bluetooth mic unavailable")
            }
            "bluetooth mic ok=$ok mode=${audio.mode}"
        }.getOrElse { "bluetooth enter failed: ${it.message}" }.also { Log.i(TAG, it) }
    }

    @Synchronized
    fun exit(why: String) {
        if (!active) return
        active = false
        ownCommActive = false
        val wasSco = bluetoothSco
        bluetoothSco = false
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audio.clearCommunicationDevice()
            } else if (wasSco) {
                @Suppress("DEPRECATION")
                audio.isBluetoothScoOn = false
                @Suppress("DEPRECATION")
                audio.stopBluetoothSco()
            } else {
                @Suppress("DEPRECATION")
                audio.isSpeakerphoneOn = prevSpeaker
            }
            audio.mode = prevMode
            Log.i(TAG, "exited ($why) mode=${audio.mode}")
        }.onFailure { Log.w(TAG, "exit failed ($why): ${it.message}") }
    }

    val isActive: Boolean @Synchronized get() = active

    companion object {
        private const val TAG = "CallAudioMode"

        /** True while THIS app holds MODE_IN_COMMUNICATION for a conversation, not for a call. */
        @Volatile
        var ownCommActive = false
            private set

        private val WIRED = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET,
        )
        private val BLUETOOTH = buildSet {
            add(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
            add(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
            add(AudioDeviceInfo.TYPE_HEARING_AID)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(AudioDeviceInfo.TYPE_BLE_HEADSET)
                add(AudioDeviceInfo.TYPE_BLE_SPEAKER)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(AudioDeviceInfo.TYPE_BLE_BROADCAST)
        }

        fun isBluetooth(type: Int): Boolean = type in BLUETOOTH

        /** A Bluetooth hands-free (HFP/SCO) device is available to listen through. */
        fun handsFreeAvailable(audio: AudioManager): Boolean =
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    audio.availableCommunicationDevices.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
                } else {
                    audio.getDevices(AudioManager.GET_DEVICES_INPUTS)
                        .any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
                }
            }.getOrDefault(false)

        /** True once the Bluetooth hands-free link is the live communication route. */
        fun handsFreeRouted(audio: AudioManager): Boolean =
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    audio.communicationDevice?.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                } else {
                    @Suppress("DEPRECATION")
                    audio.isBluetoothScoOn
                }
            }.getOrDefault(false)

        @androidx.annotation.RequiresApi(Build.VERSION_CODES.S)
        private fun pickHandsFree(audio: AudioManager): AudioDeviceInfo? {
            val devices = audio.availableCommunicationDevices
            val media = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .filter { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
                .mapNotNull { runCatching { it.address }.getOrNull() }
                .toSet()
            val candidates = devices.map {
                val handsFree = it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                BluetoothMic.Candidate(it.id, handsFree, runCatching { it.address }.getOrNull())
            }
            val chosen = BluetoothMic.pick(candidates, media) ?: return null
            return devices.firstOrNull { it.id == chosen.id }
        }

        private fun hasWired(audio: AudioManager): Boolean =
            audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in WIRED }

        /** Bluetooth wins (any car or headset over BT: barge-in off in v1), then a wired headset. */
        fun route(audio: AudioManager): AudioRoute {
            val outs = runCatching { audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.type } }
                .getOrDefault(emptyList())
            return when {
                outs.any { it in BLUETOOTH } -> AudioRoute.BLUETOOTH
                outs.any { it in WIRED } -> AudioRoute.WIRED_HEADSET
                else -> AudioRoute.PHONE_SPEAKER
            }
        }
    }
}

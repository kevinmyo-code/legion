package com.kevin.legion.service.cascade

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import com.kevin.legion.service.LiveLatency
import com.kevin.legion.service.TelephonyController
import com.kevin.legion.service.turn.BluetoothMic
import com.kevin.legion.service.turn.CallAudioMode
import com.kevin.legion.service.turn.TurnTaker
import com.kevin.legion.service.turn.VoiceTurnSettings
import kotlinx.coroutines.delay

/**
 * What the cascade does with the phone's audio routing that Live did and the laptop pipeline
 * (version 1) did not, ported when the cascade replaced Live (Kevin, 2026-11-15). Copies of
 * [com.kevin.legion.service.GeminiLiveSession]'s `prepareBluetoothMic` / `awaitBluetoothMicReady` /
 * `registerRouteWatch`, with the same rules:
 * - **The car's own mic** ([BluetoothMic], Kevin 2026-11-10): with a hands-free device on the
 *   route, the setting on and no phone call, the conversation listens through it and the first
 *   capture waits for the link.
 * - **A Bluetooth device appearing mid-conversation** turns barge-in off (ADR 0064's half-duplex
 *   rule for the car) and leaves call audio.
 */
internal class CascadeAudio(private val ctx: Context, private val audio: AudioManager) {
    @Volatile private var bluetoothMicPending = false
    private var routeCallback: AudioDeviceCallback? = null

    /**
     * Puts the hands-free link up when [BluetoothMic.wanted] says so (barge-in is off on Bluetooth,
     * so this never meets the barge-in call mode). Returns the call mode it entered, or null.
     */
    fun enterBluetoothMic(backend: String): CallAudioMode? {
        val route = CallAudioMode.route(audio)
        val wanted = BluetoothMic.wanted(
            settingOn = VoiceTurnSettings.bluetoothMic(ctx),
            route = route,
            inCall = TelephonyController.isInCall,
            handsFreeAvailable = CallAudioMode.handsFreeAvailable(audio),
        )
        if (!wanted) return null
        val mode = CallAudioMode(audio)
        val entered = mode.enterBluetooth()
        LiveLatency.log(TurnTaker.BARGE_TAG, "bluetooth mic: $entered route=$route (cascade, $backend)")
        if (!mode.onBluetoothMic) {
            if (mode.isActive) mode.exit("bluetooth mic did not take")
            return null
        }
        bluetoothMicPending = true
        return mode
    }

    /** Before the first capture: waits up to [BluetoothMic.SCO_SETTLE_MS] for the link to route. */
    suspend fun awaitBluetoothMic() {
        if (!bluetoothMicPending) return
        bluetoothMicPending = false
        val deadline = System.currentTimeMillis() + BluetoothMic.SCO_SETTLE_MS
        while (System.currentTimeMillis() < deadline && !CallAudioMode.handsFreeRouted(audio)) delay(POLL_MS)
        LiveLatency.log(TurnTaker.BARGE_TAG, "bluetooth mic routed=${CallAudioMode.handsFreeRouted(audio)} (cascade)")
    }

    /** Calls [onBluetooth] once when a Bluetooth sink appears while barge-in is on. */
    fun watchForBluetooth(onBluetooth: () -> Unit) {
        if (routeCallback != null) return
        val cb = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) {
                if (added.any { it.isSink && CallAudioMode.isBluetooth(it.type) }) onBluetooth()
            }
        }
        routeCallback = cb
        runCatching { audio.registerAudioDeviceCallback(cb, Handler(Looper.getMainLooper())) }
    }

    fun stopWatching() {
        routeCallback?.let { runCatching { audio.unregisterAudioDeviceCallback(it) } }
        routeCallback = null
    }

    companion object {
        private const val POLL_MS = 20L
    }
}

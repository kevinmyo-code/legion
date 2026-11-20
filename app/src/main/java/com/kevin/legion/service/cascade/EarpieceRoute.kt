package com.kevin.legion.service.cascade

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import com.kevin.legion.service.TelephonyController
import com.kevin.legion.service.turn.CallAudioMode
import com.kevin.legion.service.turn.VoiceOutlet
import com.kevin.legion.service.turn.VoiceOutput
import com.kevin.legion.service.turn.VoiceOutputRule
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * The conversation's side of the SPEAKER / CALL toggle beside the orb: which outlet applies now, a
 * flip or a headset plugged or pulled mid-conversation re-applied without restarting anything, and
 * the proximity wake lock (screen off against the ear, as a phone call does) held exactly while the
 * earpiece is the live route.
 *
 * It never sets the audio mode or a device itself: every route change goes through the session's
 * [CallAudioMode], so [CallAudioMode.ownCommActive] stays true for LEGION's own mode and the fillers'
 * and dumb mode's "another app holds the call mode" rule never mistakes this conversation for a call.
 * Bluetooth and a phone call on the line are left to the session's existing handling (hands off).
 */
internal class EarpieceRoute(
    private val ctx: Context,
    private val audio: AudioManager,
    private val inCall: () -> Boolean = { TelephonyController.isInCall },
) {
    private var job: Job? = null
    private var deviceWatch: AudioDeviceCallback? = null
    private var proximity: PowerManager.WakeLock? = null

    /** Where his voice goes now, from the saved choice and what is connected. */
    fun outlet(): VoiceOutlet = VoiceOutputRule.outlet(
        VoiceOutput.choice(ctx),
        CallAudioMode.route(audio),
        inCall(),
        VoiceOutput.hasEarpiece(audio),
    )

    /**
     * Until [stop]: toggle flips and headset changes re-apply the route on [loop]. [enterCallAudio]
     * is asked to enter call audio when the earpiece is wanted and the conversation is outside it
     * (barge-in off); the session decides when that is safe for the reply in flight.
     */
    fun follow(
        scope: CoroutineScope,
        loop: CoroutineDispatcher,
        callAudio: () -> CallAudioMode?,
        enterCallAudio: suspend () -> Unit,
    ) {
        if (job != null) return
        job = scope.launch(loop) { VoiceOutput.flow.drop(1).collect { reapply(callAudio, enterCallAudio) } }
        val watch = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = changed(added)
            override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = changed(removed)
            private fun changed(devices: Array<out AudioDeviceInfo>) {
                if (devices.none { it.isSink }) return
                scope.launch(loop) { reapply(callAudio, enterCallAudio) }
            }
        }
        deviceWatch = watch
        runCatching { audio.registerAudioDeviceCallback(watch, Handler(Looper.getMainLooper())) }
        sync(callAudio())
    }

    /**
     * One re-application: inside call audio a device switch (no track rebuild, the reply keeps
     * playing); outside it, entry only for the earpiece. Bluetooth and a phone call: hands off.
     */
    suspend fun reapply(callAudio: () -> CallAudioMode?, enterCallAudio: suspend () -> Unit) {
        val outlet = outlet()
        val mode = callAudio()
        when {
            outlet == VoiceOutlet.PHONE_CALL || outlet == VoiceOutlet.BLUETOOTH -> Unit
            mode != null && mode.isActive && !mode.onBluetoothMic -> mode.retarget(outlet == VoiceOutlet.EARPIECE)
            outlet == VoiceOutlet.EARPIECE -> enterCallAudio()
        }
        // Read again: an entry above made a new call audio, and the lock follows that one.
        sync(callAudio(), outlet)
    }

    /** Holds the proximity lock exactly while the earpiece is the live route; releases it otherwise. */
    @Synchronized
    fun sync(mode: CallAudioMode?, outlet: VoiceOutlet = outlet()) {
        val want = mode?.onEarpiece == true && outlet == VoiceOutlet.EARPIECE
        if (want && proximity == null) {
            proximity = newProximityLock()?.also { it.acquire(PROXIMITY_CAP_MS) }
            Log.i(TAG, "proximity lock ${if (proximity != null) "held" else "unsupported"}")
        } else if (!want) {
            releaseProximity()
        }
    }

    /** Every exit: the watch, the follower and the proximity lock go. Idempotent. */
    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
        deviceWatch?.let { runCatching { audio.unregisterAudioDeviceCallback(it) } }
        deviceWatch = null
        releaseProximity()
    }

    private fun releaseProximity() {
        val lock = proximity ?: return
        proximity = null
        // Waits for the phone to leave the ear before the screen comes back, as the dialer does.
        runCatching { if (lock.isHeld) lock.release(PowerManager.RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY) }
        Log.i(TAG, "proximity lock released")
    }

    private fun newProximityLock(): PowerManager.WakeLock? = runCatching {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) return@runCatching null
        pm.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "legion:earpiece").apply {
            setReferenceCounted(false)
        }
    }.getOrNull()

    companion object {
        private const val TAG = "EarpieceRoute"

        /** A ceiling, not a timer: [stop] releases on every exit; this bounds a lock nothing released. */
        private const val PROXIMITY_CAP_MS = 60 * 60 * 1000L
    }
}

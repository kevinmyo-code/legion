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
import com.kevin.legion.service.turn.LiveOutput
import com.kevin.legion.service.turn.Opening
import com.kevin.legion.service.turn.VoiceOutlet
import com.kevin.legion.service.turn.VoiceOutput
import com.kevin.legion.service.turn.VoiceOutputRule
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * The conversation's side of the earpiece: whether THIS conversation may use it, an in-call route
 * change or a headset plugged or pulled mid-conversation re-applied without restarting anything, the
 * readback published for the UI ([VoiceOutput.live]), and the proximity wake lock (screen off against
 * the ear, as a phone call does) held exactly while the platform routes to the earpiece.
 *
 * Which conversations: a CALL ([call], the Call button) that he started by a tap in the app with the
 * screen on ([VoiceOutputRule.earpieceAllowed]). Nothing is read from storage, so a plain orb tap is
 * on the loudspeaker whatever an earlier call did. A route change asked for in the call
 * ([VoiceOutput.routeChanges]) replaces that intent for the rest of the conversation. Speak-only
 * lines (a proactive line that does not listen) never get here: they enter no call audio, so they
 * play on the assistant usage through the loudspeaker, because nobody is holding the phone to the ear
 * for a line he did not ask for.
 *
 * It never sets the audio mode or a device itself: every route change goes through the session's
 * [CallAudioMode], so [CallAudioMode.ownCommActive] stays true for LEGION's own mode and the fillers'
 * and dumb mode's "another app holds the call mode" rule never mistakes this conversation for a call.
 * Outside a call it makes no audio or binder call at all: the speaker route is
 * exactly what it was before the Call button existed.
 */
internal class EarpieceRoute(
    private val ctx: Context,
    private val audio: AudioManager,
    /** This conversation's own earpiece intent: true when the Call button opened it. */
    call: Boolean = false,
    private val inCall: () -> Boolean = { TelephonyController.isInCall },
    private val screenOn: () -> Boolean = { isInteractive(ctx) },
) {
    private val work = SupervisorJob()
    private var opening = Opening.OTHER_TAP
    private var openingAllows: Boolean? = null
    @Volatile private var wantsCall = call
    @Volatile private var asked = false
    private var stopped = false
    private var following = false
    private var deviceWatch: AudioDeviceCallback? = null
    private var onDevices: (() -> Unit)? = null
    private var proximity: PowerManager.WakeLock? = null

    /** How this conversation opened; read when the earpiece is first asked about. */
    fun begin(opening: Opening) {
        this.opening = opening
        openingAllows = null
    }

    /**
     * True when this conversation should be on the earpiece now. Reads its own intent first, so a
     * conversation that is not a call costs no system call.
     */
    fun wantsEarpiece(): Boolean {
        if (!wantsCall) return false
        val allowed = asked || (openingAllows ?: VoiceOutputRule.earpieceAllowed(opening, screenOn()).also {
            openingAllows = it
        })
        return allowed && outlet(true) == VoiceOutlet.EARPIECE
    }

    private fun outlet(call: Boolean): VoiceOutlet =
        VoiceOutputRule.outlet(call, CallAudioMode.route(audio), inCall(), VoiceOutput.hasEarpiece(audio))

    /**
     * Until [stop]: an in-call route change and (once the earpiece is involved) headset changes
     * re-apply the route on [loop]. [enterCallAudio] is asked to enter call audio when the earpiece is wanted and the
     * conversation is outside it (barge-in off); the session decides when that is safe for audio in
     * flight.
     */
    fun follow(
        scope: CoroutineScope,
        loop: CoroutineDispatcher,
        callAudio: () -> CallAudioMode?,
        enterCallAudio: suspend () -> Unit,
    ) {
        synchronized(this) {
            if (following || stopped) return
            following = true
            onDevices = { scope.launch(loop + work) { reapply(callAudio, enterCallAudio) } }
        }
        scope.launch(loop + work) {
            // drop(1): the current value is whatever an EARLIER conversation asked for, never this one's.
            VoiceOutput.routeChanges.drop(1).collect { wanted ->
                if (wanted == null) return@collect
                askRoute(wanted)
                reapply(callAudio, enterCallAudio)
            }
        }
        if (callAudio()?.onEarpiece == true) watchDevices()
        sync(callAudio())
    }

    /**
     * An in-call route change (the call's Speaker / Earpiece control): it replaces the intent this
     * conversation opened with, for the rest of it, and lifts the opening test the way a mid-conversation
     * ask always has (he is plainly holding the phone if he just pressed the button).
     */
    fun askRoute(earpiece: Boolean) {
        wantsCall = earpiece
        asked = true
    }

    /**
     * One re-application. Nothing is touched unless the earpiece is involved (wanted now, or the route
     * is on it): then inside call audio a device switch (no track rebuild, the reply keeps playing),
     * outside it an entry. Bluetooth and a phone call: hands off.
     */
    suspend fun reapply(callAudio: () -> CallAudioMode?, enterCallAudio: suspend () -> Unit) {
        if (synchronized(this) { stopped }) return
        val mode = callAudio()
        val wanted = wantsEarpiece()
        if (wanted || mode?.onEarpiece == true) {
            watchDevices()
            val outlet = outlet(wantsCall)
            when {
                outlet == VoiceOutlet.PHONE_CALL || outlet == VoiceOutlet.BLUETOOTH -> Unit
                mode != null && mode.isActive && !mode.onBluetoothMic -> mode.retarget(wanted)
                wanted -> enterCallAudio()
            }
        }
        // Read again: an entry above made a new call audio, and the lock follows that one.
        sync(callAudio())
    }

    /**
     * Holds the proximity lock exactly while the platform routes to the earpiece and the earpiece is
     * wanted; publishes the readback for the UI. Never after [stop].
     */
    @Synchronized
    fun sync(mode: CallAudioMode?) {
        if (stopped) return
        val on = mode?.onEarpiece == true
        val wanted = wantsEarpiece()
        if (!on || !wanted) {
            releaseProximity()
        } else if (proximity == null) {
            proximity = newProximityLock()?.also { it.acquire(PROXIMITY_CAP_MS) }
            Log.i(TAG, "proximity lock ${if (proximity != null) "held" else "unsupported"}")
        }
        val inCallAudio = mode != null && mode.isActive && !mode.onBluetoothMic
        VoiceOutput.publish(LiveOutput(onEarpiece = on, refused = wanted && inCallAudio && !on))
    }

    /** Every exit: the follower, its device jobs, the watch and the proximity lock go. Idempotent. */
    @Synchronized
    fun stop() {
        stopped = true
        work.cancel()
        onDevices = null
        deviceWatch?.let { runCatching { audio.unregisterAudioDeviceCallback(it) } }
        deviceWatch = null
        releaseProximity()
        VoiceOutput.publish(null)
    }

    /** Registered only once the earpiece is involved, so a speaker conversation registers nothing. */
    @Synchronized
    private fun watchDevices() {
        if (stopped || deviceWatch != null) return
        val watch = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = changed(added)
            override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = changed(removed)
            private fun changed(devices: Array<out AudioDeviceInfo>) {
                if (devices.any { it.isSink }) synchronized(this@EarpieceRoute) { onDevices }?.invoke()
            }
        }
        deviceWatch = watch
        runCatching { audio.registerAudioDeviceCallback(watch, Handler(Looper.getMainLooper())) }
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

        private fun isInteractive(ctx: Context): Boolean =
            runCatching { (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive }
                .getOrDefault(false)
    }
}

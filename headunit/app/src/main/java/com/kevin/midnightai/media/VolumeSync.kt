package com.kevin.midnightai.media

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.kevin.midnightai.diag.InputProbe
import com.kevin.midnightai.diag.ProbeFormat

/**
 * Watches STREAM_MUSIC for changes the app did not make (hardware / steering-wheel
 * volume keys) and re-applies them through [VolumeController.reassert], the same
 * AudioManager call the app's own volume keys end in. See [ExternalVolumeTracker]
 * for the field report and why this is a guess.
 *
 * Two sources, because head units differ in which one fires: the (hidden but
 * long-stable) `VOLUME_CHANGED_ACTION` broadcast, and a [ContentObserver] on
 * [Settings.System], where AudioService persists stream volumes. Both just re-read
 * the live index; the tracker de-duplicates. Process-lifetime, started once from
 * [com.kevin.midnightai.MidnightApplication].
 */
object VolumeSync {
    private const val VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION"
    private const val EXTRA_VOLUME_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE"

    private val main = Handler(Looper.getMainLooper())
    private val RECHECK_TOKEN = Any()
    private var started = false

    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        // Seed the tracker so the first real key press is recognised as a change.
        check(app, "start")

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val stream = intent.getIntExtra(EXTRA_VOLUME_STREAM_TYPE, AudioManager.STREAM_MUSIC)
                if (stream == AudioManager.STREAM_MUSIC) check(app, "broadcast")
            }
        }
        ContextCompat.registerReceiver(
            app, receiver, IntentFilter(VOLUME_CHANGED_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        app.contentResolver.registerContentObserver(
            Settings.System.CONTENT_URI, true,
            object : ContentObserver(main) {
                override fun onChange(selfChange: Boolean) = check(app, "observer")
            },
        )
    }

    /**
     * A voice turn took ([held] true) or released audio focus. Index changes while held (and
     * just after) are the system ducking for Zero, not the driver's keys, so they are left
     * alone; see [ExternalVolumeTracker.setHeld]. Any thread.
     */
    fun holdForVoice(held: Boolean) {
        VolumeController.tracker.setHeld(held, SystemClock.uptimeMillis())
        com.kevin.midnightai.MidnightEvents.volumeSyncHold(held)
    }

    /** [via] = which signal fired (start, broadcast, observer, recheck), for the probe line. */
    private fun check(app: Context, via: String) {
        val now = SystemClock.uptimeMillis()
        val tracker = VolumeController.tracker
        val tripsBefore = tracker.breakerTrips
        val index = try {
            VolumeController.index(app)
        } catch (e: Exception) {
            return
        }
        val verdict = tracker.classify(index, now)
        val decision = verdict.decision
        // HU-1 T2 probe: every index change and the branch it took, into the drive log.
        if (ProbeFormat.shouldLogObservation(verdict.reason.name)) {
            val max = runCatching { VolumeController.maxIndex(app) }.getOrDefault(-1)
            InputProbe.volume(ProbeFormat.observedLine(via, index, max, decision.name, verdict.reason.name))
        }
        if (tracker.breakerTrips != tripsBefore) com.kevin.midnightai.MidnightEvents.volumeSyncBreakerTripped()
        when (decision) {
            ExternalVolumeTracker.Decision.REASSERT -> try {
                VolumeController.reassert(app)
            } catch (e: Exception) {
                // An OEM AudioService that throws on a volume write must not take the launcher down.
            }
            ExternalVolumeTracker.Decision.DEFER -> {
                // A key pressed during our own write's echo window would otherwise be
                // missed: look again once the window closes.
                main.removeCallbacksAndMessages(RECHECK_TOKEN)
                main.postAtTime(
                    { check(app, "recheck") }, RECHECK_TOKEN,
                    now + VolumeController.tracker.quietRemaining(now) + 1,
                )
            }
            ExternalVolumeTracker.Decision.IGNORE -> Unit
        }
    }
}

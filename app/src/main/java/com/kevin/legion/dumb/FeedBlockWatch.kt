package com.kevin.legion.dumb

import android.Manifest
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner

/**
 * The "Feed blocker is off" notice (ADR 0066, 2026-11-02). Turning the LEGION blocker accessibility
 * service off in Android settings silently kills the feed block; this says so, ongoing, with a tap to
 * Accessibility settings, until the service is back on or the switch is off.
 *
 * Checked, never polled: when the service unbinds (the moment it is turned off) and connects, each time
 * the app comes to the foreground, and when a settings screen with the switch resumes. Friction, not a
 * lock: it says the block is off and nothing more.
 */
class FeedBlockWatch(private val app: Application, private val feedBlock: FeedBlockSwitch) {
    @Volatile
    private var watching = false

    /** Re-checks on every app foreground. Call once, on the main thread. */
    fun watchAppStarts() {
        if (watching) return
        watching = true
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    check()
                }
            },
        )
    }

    /** Posts or clears the notice from the switch and the live accessibility state. */
    fun check() = sync(DumbSetup.accessibilityOn(app))

    /** [accessibilityOn] passed in for the service's own unbind, where the system may not have caught up. */
    fun sync(accessibilityOn: Boolean) {
        runCatching {
            if (FeedBlockWatchRule.warn(feedBlock.get(), accessibilityOn)) post() else clear()
        }.onFailure { Log.w(TAG, "Could not update the feed blocker notice", it) }
    }

    private fun clear() = NotificationManagerCompat.from(app).cancel(NOTIFICATION_ID)

    private fun post() {
        if (!mayPost()) return
        ensureChannel()
        val open = PendingIntent.getActivity(
            app, NOTIFICATION_ID,
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(app, CHANNEL_ID)
            .setContentTitle(TITLE)
            .setContentText(BODY)
            .setStyle(NotificationCompat.BigTextStyle().bigText(BODY))
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentIntent(open)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        NotificationManagerCompat.from(app).notify(NOTIFICATION_ID, notification)
    }

    private fun mayPost(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(CHANNEL_ID, "Feed blocker", NotificationManager.IMPORTANCE_DEFAULT)
            .apply { description = "Says when the feed blocker is switched on but cannot block anything." }
        app.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "feed_blocker_off"
        const val NOTIFICATION_ID = 0x0066_0FF
        const val TITLE = "Feed blocker is off"
        const val BODY = "LEGION blocker accessibility service was turned off. Tap to open Accessibility settings."
        private const val TAG = "FeedBlockWatch"
    }
}

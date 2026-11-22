package com.kevin.legion.clock

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import com.kevin.legion.ui.clock.RingActivity

/**
 * The ring's one notification (id [ClockNotifier.RING_ID], channel [ClockNotifier.CHANNEL_RING]),
 * shown by [RingService] for the newest ring. Built here, apart from the service, so a test can read
 * it without starting one.
 *
 * **Never silent** (phantom alarm fix, 2026-11-21). `setSilent` put it in NotificationCompat's
 * "silent" group with summary-only alerting, which stops the heads-up and the full-screen intent:
 * the emergency ring played with nothing on screen. The channel has no sound of its own (the
 * service plays it), so alerting adds the banner and the ring screen, never a second sound.
 *
 * - **Stop on every kind**, one stop-everything action ([RingControl.stopAll]); Snooze stays on a
 *   clock alarm, +1 min on a timer.
 * - **A swipe stops the sound** (the delete intent). Kept ongoing as well, but Android 14 lets a
 *   person swipe a foreground service's notification away anyway, so ongoing alone cannot be relied
 *   on; a swipe that left the sound playing with nothing on screen was the bug.
 * - **The ring screen over the lock screen where Android allows it** ([mayFullScreen]); when the
 *   full-screen permission is refused it arrives heads-up with its buttons, and a tap opens the
 *   ring screen either way.
 * - **Shown at once**: a foreground service's notification may otherwise wait up to 10 seconds.
 */
object RingNotification {

    fun build(context: Context, r: RingRequest, fullScreen: Boolean = mayFullScreen(context)): Notification {
        ClockNotifier.ensureChannels(context)
        val screen = RingActivity.pendingIntent(context)
        val b = NotificationCompat.Builder(context, ClockNotifier.CHANNEL_RING)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(r.kind.title)
            .setContentText(r.label)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(screen)
            .setDeleteIntent(ClockNotifier.action(context, ClockActionReceiver.ACTION_RING_SWIPED, r.id))
        if (fullScreen) b.setFullScreenIntent(screen, true)
        when (r.kind) {
            RingKind.ALARM ->
                b.addAction(0, "Snooze", ClockNotifier.action(context, ClockActionReceiver.ACTION_SNOOZE, r.id))
            RingKind.TIMER ->
                b.addAction(0, "+1 min", ClockNotifier.action(context, ClockActionReceiver.ACTION_TIMER_ADD, r.id))
            RingKind.ALERT -> Unit
        }
        b.addAction(0, "Stop", ClockNotifier.action(context, ClockActionReceiver.ACTION_STOP_RING, r.id))
        return b.build()
    }

    /** False only when Android 14+ says the full-screen permission is refused; unreadable is allowed. */
    private fun mayFullScreen(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
            runCatching { context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent() }
                .getOrDefault(true)
}

package com.kevin.legion.clock

import android.app.Notification
import android.content.Context
import androidx.core.app.NotificationCompat
import com.kevin.legion.ui.clock.RingActivity

/**
 * The ring's one notification (id [ClockNotifier.RING_ID], channel [ClockNotifier.CHANNEL_RING]),
 * shown by [RingService] for the newest ring. Built here, apart from the service, so a test can read
 * it without starting one.
 */
object RingNotification {

    fun build(context: Context, r: RingRequest, fullScreen: Boolean = true): Notification {
        ClockNotifier.ensureChannels(context)
        val full = RingActivity.pendingIntent(context)
        val b = NotificationCompat.Builder(context, ClockNotifier.CHANNEL_RING)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(r.kind.title)
            .setContentText(r.label)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(full)
            .setFullScreenIntent(full, true)
        if (r.kind == RingKind.ALARM) {
            b.addAction(0, "Snooze", ClockNotifier.action(context, ClockActionReceiver.ACTION_SNOOZE, r.id))
            b.addAction(0, "Dismiss", ClockNotifier.action(context, ClockActionReceiver.ACTION_DISMISS, r.id))
        } else if (r.kind == RingKind.TIMER) {
            b.addAction(0, "+1 min", ClockNotifier.action(context, ClockActionReceiver.ACTION_TIMER_ADD, r.id))
            b.addAction(0, "Stop", ClockNotifier.action(context, ClockActionReceiver.ACTION_TIMER_STOP, r.id))
        }
        return b.build()
    }
}

package com.kevin.legion.claude

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.kevin.legion.ui.LegionRoute
import com.kevin.legion.ui.MainActivity
import java.time.Duration
import java.time.Instant

/**
 * The one ongoing "Claude: 2 running" notification (Kevin 2026-11-03), with the newest running
 * task's current step as its text. Its own channel, `IMPORTANCE_LOW` with no badge: it sits in the
 * shade and never rings or buzzes. Tapping it opens the Claude session screen, where every task can
 * be read and answered (the hands path, ADR 0035). A finished task's result still arrives the way it
 * always did ([ClaudeResultPolling]: spoken, or the proactive notification), so a task moving to
 * `needs_confirmation` keeps that alert; this one only adds "1 needs you" to its title.
 *
 * With POST_NOTIFICATIONS refused (Android 13+) nothing is posted and [showing] stays false, so the
 * watcher does not keep polling for a notification nobody can see.
 *
 * It times out by itself after [STALE_AFTER] unless re-posted: when nothing is reading the engine
 * (assistant off and the app closed), the count it shows can no longer be confirmed, so it goes.
 */
class ClaudeWatchNotifier(context: Context) : ClaudeLivePoster {
    private val app = context.applicationContext

    override fun show(title: String, text: String, at: Instant) {
        if (!mayPost()) return
        ensureChannel()
        val notification = NotificationCompat.Builder(app, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(openScreen())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(true)
            .setWhen(at.toEpochMilli())
            .setTimeoutAfter(STALE_AFTER.toMillis())
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        NotificationManagerCompat.from(app).notify(NOTIFICATION_ID, notification)
    }

    override fun clear() {
        NotificationManagerCompat.from(app).cancel(NOTIFICATION_ID)
    }

    override fun showing(): Boolean =
        runCatching {
            app.getSystemService(NotificationManager::class.java)
                ?.activeNotifications?.any { it.id == NOTIFICATION_ID } == true
        }.getOrDefault(false)

    private fun openScreen(): PendingIntent {
        val intent = Intent(app, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_ROUTE, LegionRoute.CLAUDE_SESSION)
        }
        return PendingIntent.getActivity(
            app,
            NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun mayPost(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(CHANNEL_ID, "Claude tasks running", NotificationManager.IMPORTANCE_LOW)
            .apply {
                description = "One quiet line while a Claude session task runs on your laptop."
                setShowBadge(false)
                enableVibration(false)
                setSound(null, null)
            }
        app.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "claude_watch_channel"
        private const val NOTIFICATION_ID = 0x0C1A0DE

        /** How long a posted count stands with no new read behind it. */
        val STALE_AFTER: Duration = Duration.ofMinutes(10)
    }
}

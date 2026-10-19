package com.kevin.legion.games.race

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

/**
 * The local notification for a Tetris invite (ADR 0060: the phone has no push channel, so
 * [InvitePoller] asks the engine and this raises what it finds). Both the notification and its
 * Accept action open the match screen, and the screen does the accepting: a notification action
 * that talked to the network from a receiver could report an outcome nobody observed.
 *
 * With POST_NOTIFICATIONS refused the post is a silent no-op at the OS level, the same trade
 * `ReminderAlarmReceiver` makes; the invite is still on the match screen if the user opens it.
 */
class GameInviteNotifier(context: Context) {
    private val app = context.applicationContext

    fun raise(invite: PendingInvite) {
        if (!mayPost()) return
        ensureChannel()
        val open = openMatch(invite.matchId, REQUEST_OPEN)
        val accept = openMatch(invite.matchId, REQUEST_ACCEPT)
        val notification = NotificationCompat.Builder(app, CHANNEL_ID)
            .setContentTitle("${invite.fromName} invited you to Tetris")
            .setContentText("A three minute score race. The invite lasts two minutes.")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(open)
            .addAction(0, "Accept", accept)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SOCIAL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        NotificationManagerCompat.from(app).notify(notificationId(invite.matchId), notification)
    }

    /** Takes down the notification of an invite that is no longer pending. */
    fun retract(matchId: String) {
        NotificationManagerCompat.from(app).cancel(notificationId(matchId))
    }

    private fun openMatch(matchId: String, requestKind: Int): PendingIntent {
        val intent = Intent(app, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_ROUTE, LegionRoute.tetrisMatch(matchId))
        }
        return PendingIntent.getActivity(
            app,
            notificationId(matchId) * REQUEST_KINDS + requestKind,
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
        val channel = NotificationChannel(CHANNEL_ID, "Tetris invites", NotificationManager.IMPORTANCE_HIGH)
            .apply { description = "A household member asking you to race them at Tetris." }
        app.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun notificationId(matchId: String): Int = matchId.hashCode() and ID_MASK

    companion object {
        const val CHANNEL_ID = "game_invites_channel"
        private const val REQUEST_OPEN = 0
        private const val REQUEST_ACCEPT = 1
        private const val REQUEST_KINDS = 2
        private const val ID_MASK = 0x3FFFFFFF // keeps id * REQUEST_KINDS inside Int
    }
}

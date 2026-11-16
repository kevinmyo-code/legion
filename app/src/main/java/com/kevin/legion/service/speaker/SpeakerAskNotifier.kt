package com.kevin.legion.service.speaker

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
import com.kevin.legion.service.AriaForegroundService

/**
 * The unsure band's listening prompt: the wake phrase was heard but the voice scored between
 * [SpeakerPolicy.IGNORE_BELOW] and [SpeakerPolicy.OPEN_AT], so nothing opens by itself. A heads-up
 * "Was that you? Tap to talk." whose tap is the ordinary talk action, which never passes through the
 * speaker check: a tap always opens. It goes away on its own after [SpeakerPolicy.ASK_TIMEOUT_MS].
 * With notifications refused nothing shows; the log says so and the tap-to-talk button still works.
 */
object SpeakerAskNotifier {
    private const val CHANNEL_ID = "wake_was_that_you"
    private const val NOTIFICATION_ID = 0x5EA4

    fun post(context: Context) {
        val app = context.applicationContext
        if (!mayPost(app)) {
            SpeakerVerifier.logLocal("speaker_ask shown=false reason=notifications_off")
            return
        }
        ensureChannel(app)
        val talk = Intent(app, AriaForegroundService::class.java)
            .setAction(AriaForegroundService.ACTION_TALK)
            .putExtra(AriaForegroundService.EXTRA_FROM_WAKE_WORD, true)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val pending = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PendingIntent.getForegroundService(app, NOTIFICATION_ID, talk, flags)
        } else {
            PendingIntent.getService(app, NOTIFICATION_ID, talk, flags)
        }
        val n = NotificationCompat.Builder(app, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Heard the wake word")
            .setContentText("Was that you? Tap to talk.")
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setTimeoutAfter(SpeakerPolicy.ASK_TIMEOUT_MS)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        NotificationManagerCompat.from(app).notify(NOTIFICATION_ID, n)
        SpeakerVerifier.logLocal("speaker_ask shown=true")
    }

    private fun mayPost(app: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun ensureChannel(app: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        // High so it shows as a heads-up, but silent: a ding on every unsure wake from a TV would be
        // the false-trigger noise this feature exists to stop.
        val channel = NotificationChannel(CHANNEL_ID, "Wake word: was that you?", NotificationManager.IMPORTANCE_HIGH)
            .apply {
                description = "Asks for a tap when the wake word heard a voice it was not sure was yours."
                setSound(null, null)
                enableVibration(false)
            }
        app.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}

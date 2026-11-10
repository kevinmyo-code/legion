package com.kevin.legion.claude

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.kevin.legion.ui.claudesession.ClaudeFileActivity

/**
 * "Your files are here" when a Claude task's files land while LEGION is not in front (files to
 * phone, ADR 0080). Tapping it opens them ([ClaudeFileActivity]). Its own channel, default
 * importance: Kevin asked for these files, so this is the delivery he is waiting on, not a raise.
 *
 * **The count, never a file name.** A delivered file may be a scanned ID, and a notification shows
 * on the lock screen; the name is on the viewer behind the tap. `VISIBILITY_PRIVATE` hides even the
 * count on a secure lock screen. With POST_NOTIFICATIONS refused nothing is posted, and the files
 * still wait on the Claude session screen.
 */
object ClaudeFilesNotifier {
    const val CHANNEL_ID = "claude_files_channel"
    private const val ID_BASE = 0x0C1F_0000

    /** The notification's text, pure for a test: never a name. */
    fun text(taskId: Int, count: Int): String =
        (if (count == 1) "1 file from task $taskId is" else "$count files from task $taskId are") +
            " on your phone. Tap to open."

    fun build(context: Context, taskId: Int, count: Int): Notification =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("From your Claude session")
            .setContentText(text(taskId, count))
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentIntent(ClaudeFileActivity.pendingIntent(context, taskId))
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

    fun post(context: Context, taskId: Int, count: Int) {
        val app = context.applicationContext
        if (count <= 0 || !mayPost(app)) return
        ensureChannel(app)
        NotificationManagerCompat.from(app).notify(ID_BASE + taskId, build(app, taskId, count))
    }

    private fun mayPost(app: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun ensureChannel(app: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(CHANNEL_ID, "Files from Claude", NotificationManager.IMPORTANCE_DEFAULT)
            .apply { description = "Files you asked your Claude session to send to this phone." }
        app.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}

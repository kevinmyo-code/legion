package com.kevin.midnightai.diag

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The service-side half of the drive log (2026-10-15): the periodic logcat snapshot and the two
 * automatic upload triggers. Kept out of `AriaForegroundService` so that file takes two small
 * calls rather than the logic.
 *
 * - [run] - once at service start: wait [STARTUP_DELAY_MS] for the network, upload whatever the
 *   last run left behind (`startup`), then snapshot logcat every [SNAPSHOT_INTERVAL_MS] for as
 *   long as the service lives. The ring buffer is small, so the snapshot is what makes the
 *   minutes before a problem still readable later.
 * - [uploadAfterDrive] - called by the drive monitor when a sustained stop ends a drive
 *   (`drive_end`).
 *
 * Offline is not an error here: a failed upload leaves the batch on disk and the next trigger
 * (the next drive end, the next start, or the Settings button) retries it.
 */
object DriveLogLoop {
    private const val STARTUP_DELAY_MS = 60_000L
    private const val SNAPSHOT_INTERVAL_MS = 3 * 60_000L

    suspend fun run(context: Context) {
        val app = context.applicationContext
        delay(STARTUP_DELAY_MS)
        runCatching { DriveLogUploader.create(app)?.upload(UploadReason.STARTUP) }
        var saidUnreadable = false
        while (true) { // ends when the service scope cancels: delay() throws
            val added = withContext(Dispatchers.IO) { DriveLog.snapshotLogcat() }
            // Unreadable and empty are different sentences: a ROM that hands the app no logcat
            // would otherwise upload batches that look like "nothing happened". Said once per
            // process, into the log itself, so the reader of the batch knows.
            if (added < 0 && !saidUnreadable) {
                saidUnreadable = true
                DriveLog.breadcrumb("logcat unreadable on this unit; only breadcrumbs follow")
            }
            delay(SNAPSHOT_INTERVAL_MS)
        }
    }

    fun uploadAfterDrive(scope: CoroutineScope, context: Context) {
        val app = context.applicationContext
        scope.launch {
            runCatching { DriveLogUploader.create(app)?.upload(UploadReason.DRIVE_END) }
        }
    }
}

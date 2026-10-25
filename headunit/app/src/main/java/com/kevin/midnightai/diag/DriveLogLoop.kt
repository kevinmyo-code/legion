package com.kevin.midnightai.diag

import android.content.Context
import com.kevin.midnightai.fleetupload.FleetUpload
import com.kevin.midnightai.fleetupload.FleetUploadReason
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
 *   last run left behind (`startup`), then snapshot logcat every [SNAPSHOT_INTERVAL_MS] and upload
 *   every [UPLOAD_INTERVAL_MS] (`periodic`) for as long as the service lives. The ring buffer is
 *   small, so the snapshot is what makes the minutes before a problem still readable later.
 * - [uploadAfterDrive] - called by the drive monitor when a sustained stop ends a drive
 *   (`drive_end`).
 *
 * **Why periodic (2026-10-24, Kevin).** All 47 batches the engine held were `startup`; `drive_end`
 * had never once arrived. The ignition is cut the moment the car stops, so the sustained-stop
 * check never finishes, and with no GPS or speed a drive end cannot be told apart from a light.
 * A drive's log therefore sat on the unit until the next start. Now at most [UPLOAD_INTERVAL_MS]
 * of it is lost at the ignition cut (still on disk, sent at the next start).
 *
 * Car data (OBD samples and DTC events) is uploaded on the same three triggers plus a size-or-time
 * flush on the snapshot tick (`fleetupload/FleetUpload`).
 *
 * Offline is not an error here: a failed upload leaves the batch on disk and the next trigger
 * (the next drive end, the next start, or the Settings button) retries it.
 */
object DriveLogLoop {
    private const val STARTUP_DELAY_MS = 60_000L
    private const val SNAPSHOT_INTERVAL_MS = 60_000L
    private const val UPLOAD_INTERVAL_MS = 3 * 60_000L
    private const val TICKS_PER_UPLOAD = (UPLOAD_INTERVAL_MS / SNAPSHOT_INTERVAL_MS).toInt()

    suspend fun run(context: Context) {
        val app = context.applicationContext
        delay(STARTUP_DELAY_MS)
        runCatching { DriveLogUploader.create(app)?.upload(UploadReason.STARTUP) }
        // Car data rides the same triggers (2026-10-17): no second scheduler.
        runCatching { FleetUpload.create(app).upload(FleetUploadReason.STARTUP) }
        var saidUnreadable = false
        var tick = 0
        while (true) { // ends when the service scope cancels: delay() throws
            val added = withContext(Dispatchers.IO) { DriveLog.snapshotLogcat() }
            // Unreadable and empty are different sentences: a ROM that hands the app no logcat
            // would otherwise upload batches that look like "nothing happened". Said once per
            // process, into the log itself, so the reader of the batch knows.
            if (added < 0 && !saidUnreadable) {
                saidUnreadable = true
                DriveLog.breadcrumb("logcat unreadable on this unit; only breadcrumbs follow")
            }
            // Mid-drive flush of OBD samples and code events, on a size-or-time threshold.
            runCatching { FleetUpload.flushIfDue(app) }
            // Offline is fine: a failed upload leaves the batch on disk for the next tick.
            if (++tick % TICKS_PER_UPLOAD == 0) {
                runCatching { DriveLogUploader.create(app)?.upload(UploadReason.PERIODIC) }
            }
            delay(SNAPSHOT_INTERVAL_MS)
        }
    }

    fun uploadAfterDrive(scope: CoroutineScope, context: Context) {
        val app = context.applicationContext
        scope.launch {
            runCatching { DriveLogUploader.create(app)?.upload(UploadReason.DRIVE_END) }
            runCatching { FleetUpload.create(app).upload(FleetUploadReason.DRIVE_END) }
        }
    }
}

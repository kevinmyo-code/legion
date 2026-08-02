package com.kevin.legion.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.kevin.legion.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Hosts [IngestScanner] for the ledger folder scan (ticket 05 resolution
 * §1's actual intent - a minutes-long operation needs a foreground service's
 * priority and an honest notification), but as its OWN service rather than
 * inside [AriaForegroundService] - see that class's doc comment (where
 * `ingestScanner` used to live) for why. Short version: [AriaForegroundService]'s
 * `onCreate()` unconditionally boots the entire voice assistant (spoken
 * opener, mic prewarm, OBD Bluetooth, GPS, wake word) the instant it is
 * created, bind or start, with no gate on [AssistantIgnition]. `AssistantIgnition`
 * promises ledger is unaffected by that toggle, which is OFF on a fresh
 * install (ticket 07 resolution §1) - so opening the Ledger tab and connecting
 * a folder must never make Zero start talking. This service declares only
 * `dataSync` (the app already holds `FOREGROUND_SERVICE_DATA_SYNC` for the
 * other service, so no new permission), does none of that setup, and exists
 * for exactly as long as the ledger UI is bound to it.
 *
 * Lifecycle is bind-driven, not start-driven: the ledger UI
 * `bindService(..., BIND_AUTO_CREATE)`s this on entering the ledger tab and
 * unbinds on leaving it. [onUnbind] tears the foreground state down and
 * stops the service once nothing is bound, so this never lingers as a
 * permanent background notification the way [AriaForegroundService] does -
 * there is no equivalent "ignition" concept here, a scan is a bounded,
 * user-requested operation, not a standing presence.
 */
class LedgerIngestService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    lateinit var ingestScanner: IngestScanner
        private set

    inner class LocalBinder : Binder() {
        val service: LedgerIngestService get() = this@LedgerIngestService
    }
    private val binder = LocalBinder()

    /**
     * Starts a scan on the SERVICE's scope, not the caller's.
     *
     * The ledger tab must never launch `ingestScanner.scan(...)` from a
     * `rememberCoroutineScope()`: that scope dies when the composable leaves
     * composition, so glancing at another bottom-nav tab would cancel a
     * running scan mid-batch - abandoning any Gemini call already paid for.
     * It would also make [onUnbind]'s wait-for-Finished grace period
     * meaningless, since the thing it waits for would already be dead.
     *
     * Promoting to the foreground happens HERE rather than in [onCreate], so
     * the "reading bank statements" notification only exists while a scan
     * actually runs. Binding alone (opening the tab to read the transaction
     * list) must not claim work that is not happening.
     */
    fun startScan(treeUri: Uri) {
        startForegroundCompat()
        serviceScope.launch { ingestScanner.scan(treeUri) }
    }

    // Bumped on every onBind() - lets a delayed stop queued by onUnbind()
    // (see below) recognise a rebind that happened while it was waiting and
    // back off, rather than stopping the service (and tearing down the
    // notification) out from under a client that reconnected in the meantime.
    @Volatile private var bindGeneration = 0

    override fun onCreate() {
        super.onCreate()
        ingestScanner = IngestScanner(this)
        createNotificationChannel()
        // Deliberately NOT startForegroundCompat() here - see startScan. A
        // bind means the ledger tab is open, which is not the same as a scan
        // running, and an ongoing "scanning" notification for an idle service
        // claims work that isn't happening.
    }

    override fun onBind(intent: Intent?): IBinder {
        bindGeneration++
        return binder
    }

    /**
     * Everything that binds here (the ledger tab) unbinds on leaving the
     * ledger tab, e.g. tapping another bottom-nav item - a much more frequent
     * event than "the scan is actually done". Stopping immediately would kill
     * a scan the instant the driver glances at another tab; ticket 05's
     * "killed = re-run, not resumed" makes that merely wasteful rather than
     * unsafe, but it is still bad behaviour worth avoiding cheaply. If
     * [IngestScanner.state] is mid-scan, this waits for it to reach
     * [ScanState.Finished] before actually stopping - guarded by
     * [bindGeneration] so a rebind that starts a second scan while the wait
     * is still pending doesn't get its notification torn down out from under
     * it. If already [ScanState.Idle] or [ScanState.Finished], there's
     * nothing to wait for. No `onRebind` override needed - a fresh
     * [LocalBinder] read from [onBind] is enough for a re-bind after this.
     */
    override fun onUnbind(intent: Intent?): Boolean {
        val generationAtUnbind = bindGeneration
        val current = ingestScanner.state.value
        val active = current !is ScanState.Idle && current !is ScanState.Finished
        if (active) {
            serviceScope.launch {
                ingestScanner.state.first { it is ScanState.Finished }
                if (bindGeneration == generationAtUnbind) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        } else {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return false
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startForegroundCompat() {
        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Ledger statement scan",
                NotificationManager.IMPORTANCE_LOW,
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_ROUTE, "ledger")
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Reading bank statements")
            .setContentText("Scanning your connected folder for new statements.")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "ledger_ingest_channel"
        private const val NOTIFICATION_ID = 2 // distinct from AriaForegroundService's NOTIFICATION_ID = 1
    }
}

package com.kevin.legion.update

import android.Manifest
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.kevin.legion.MidnightEvents
import com.kevin.legion.backend.engine.EngineConfig
import com.kevin.legion.backend.engine.EngineHttp
import com.kevin.legion.service.ConversationState
import com.kevin.legion.service.ProactiveBus
import com.kevin.legion.service.ProactiveCategory
import com.kevin.legion.service.ProactiveRaise
import com.kevin.legion.service.rearmAfterRestart
import com.kevin.legion.ui.LegionRoute
import com.kevin.legion.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The updater's object graph, built by hand until Hilt lands (as `DumbModeGraph` is). One
 * [UpdateController] per process; the Updates screen, the `app_update` voice tool, the
 * notification's Install button and the daily worker all reach the same one (ADR 0035).
 */
object UpdateGraph {
    @Volatile private var controller: UpdateController? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun controller(context: Context): UpdateController =
        controller ?: synchronized(this) {
            controller ?: build(context.applicationContext as Application).also { controller = it }
        }

    private fun build(app: Application): UpdateController = UpdateController(
        backend = DjangoUpdateBackend(EngineHttp(EngineConfig(app))),
        downloader = OkHttpApkDownloader(),
        installer = AndroidPackageInstallerGateway(app),
        platform = AndroidUpdatePlatform(app),
        notifier = AndroidUpdateNotifier(app),
        prefs = SharedUpdatePrefs(app),
        downloadDir = File(app.cacheDir, "updates"),
    )

    /**
     * App start (MidnightApplication.onCreate): one check, the daily worker, and the watch on the
     * conversation, so a yes given mid-conversation installs when it ends and the spoken ask waits
     * for a pause.
     */
    fun start(app: Application) {
        val c = controller(app)
        UpdateCheckWorker.schedule(app)
        scope.launch {
            runCatching { c.check(); c.maybeAnnounce() }
                .onFailure { MidnightEvents.appStartWorkFailed("update_check", it) }
        }
        scope.launch {
            ConversationState.busy.drop(1).collectLatest { busy ->
                if (busy) return@collectLatest
                delay(AFTER_CONVERSATION_MS)
                runCatching { c.onConversationIdle() }
                delay(ANNOUNCE_PAUSE_MS)
                runCatching { c.maybeAnnounce() }
            }
        }
    }

    /** Runs [block] on the graph's scope (a receiver's goAsync work). */
    internal fun launch(block: suspend () -> Unit) = scope.launch { runCatching { block() } }

    private const val AFTER_CONVERSATION_MS = 3_000L
    private const val ANNOUNCE_PAUSE_MS = 60_000L
}

/** The ask on the phone: a notification with Install and Later, and Alfred's one spoken line. */
class AndroidUpdateNotifier(private val app: Application) : UpdateNotifier {

    override fun ready(release: ReleaseInfo) {
        post(
            title = "LEGION update ready",
            text = "${UpdateRules.describe(release)}. Install?",
            actions = true,
        )
    }

    override fun refused(sentence: String) {
        post(title = "LEGION update", text = sentence, actions = false)
    }

    override fun clear() {
        NotificationManagerCompat.from(app).cancel(NOTIFICATION_ID)
    }

    override suspend fun speak(release: ReleaseInfo): Boolean {
        val facts = "An update to the LEGION app is downloaded and checked: ${UpdateRules.describe(release)}."
        val raise = ProactiveRaise(
            ruleId = "app_update_ready_${release.versionCode}",
            category = ProactiveCategory.UPDATES,
            reason = facts,
            facts = facts,
            prompt = "$facts Tell the user in one short sentence, in your own words, and ask whether to " +
                "install it now. Installing closes LEGION for about a minute. Call app_update with " +
                "action=install and confirmed=true only if they say yes in their reply; otherwise say " +
                "nothing more about it. Mention no other subject.",
            callerPostsItsOwnNotification = true,
            listensForReply = true,
            listenHoldMs = LISTEN_HOLD_MS,
        )
        return ProactiveBus.speakIfAllowed(app, raise) is ProactiveBus.RaiseOutcome.Raised
    }

    /** Android's own confirm, for the first update after an adb install. */
    fun confirm(intent: Intent) {
        val pending = PendingIntent.getActivity(
            app,
            NOTIFICATION_ID + 1,
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        post(title = "LEGION update", text = "Tap to let Android finish the install.", actions = false, content = pending)
    }

    private fun post(title: String, text: String, actions: Boolean, content: PendingIntent? = null) {
        if (!mayPost()) return
        ensureChannel()
        val builder = NotificationCompat.Builder(app, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(content ?: openScreen())
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
        if (actions) {
            builder.addAction(0, "Install", action(UpdateActionReceiver.ACTION_INSTALL))
            builder.addAction(0, "Later", action(UpdateActionReceiver.ACTION_LATER))
        }
        NotificationManagerCompat.from(app).notify(NOTIFICATION_ID, builder.build())
    }

    private fun action(name: String): PendingIntent = PendingIntent.getBroadcast(
        app,
        name.hashCode(),
        Intent(app, UpdateActionReceiver::class.java).setAction(name),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun openScreen(): PendingIntent = PendingIntent.getActivity(
        app,
        NOTIFICATION_ID,
        Intent(app, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_ROUTE, LegionRoute.SETTINGS_UPDATES)
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun mayPost(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(CHANNEL_ID, "App updates", NotificationManager.IMPORTANCE_DEFAULT)
            .apply { description = "A LEGION update is ready to install, and asks first." }
        app.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "app_updates_channel"
        private const val NOTIFICATION_ID = 0x0AD7E
        /** How long the spoken ask listens for the yes, as Claude task questions do. */
        const val LISTEN_HOLD_MS = 20_000L
    }
}

/** The notification's Install and Later. Fired only by this app's own PendingIntents. */
class UpdateActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as Application
        when (intent.action) {
            ACTION_INSTALL -> {
                val pending = goAsync()
                UpdateGraph.launch {
                    try {
                        val outcome = UpdateGraph.controller(app).install(Consent.TAP)
                        if (!outcome.started) AndroidUpdateNotifier(app).refused(outcome.sentence)
                    } finally {
                        pending.finish()
                    }
                }
            }
            ACTION_LATER -> AndroidUpdateNotifier(app).clear()
        }
    }

    companion object {
        const val ACTION_INSTALL = "com.kevin.legion.update.INSTALL"
        const val ACTION_LATER = "com.kevin.legion.update.LATER"
    }
}

/**
 * PackageInstaller's answer. Success is never seen here (the process is replaced and
 * [PackageReplacedReceiver] runs instead). Pending user action is the first update after an adb
 * install: Android shows its own confirm. A failure says Android's reason in words.
 */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_STATUS) return
        val app = context.applicationContext as Application
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        when (status) {
            PackageInstaller.STATUS_SUCCESS -> Unit
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                runCatching { app.startActivity(Intent(confirm).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                AndroidUpdateNotifier(app).confirm(confirm)
            }
            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                    ?.takeIf { it.isNotBlank() } ?: "status $status"
                UpdateGraph.controller(app).onInstallFailed(message)
            }
        }
    }

    companion object {
        const val ACTION_STATUS = "com.kevin.legion.update.INSTALL_STATUS"
    }
}

/**
 * The app was just replaced by an update. Android stops the old process; this restarts what the
 * user had on (the assistant service and its wake word, alarms, geofences), exactly as a reboot
 * does ([com.kevin.legion.service.BootReceiver]), and clears the downloaded APK.
 */
class PackageReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val app = context.applicationContext
        val pending = goAsync()
        UpdateGraph.launch {
            try {
                rearmAfterRestart(app, "update")
                runCatching { File(app.cacheDir, "updates").deleteRecursively() }
            } finally {
                pending.finish()
            }
        }
    }
}

/** Once a day: check, download on Wi-Fi, verify, ask. All the logic is [UpdateController]'s. */
class UpdateCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val controller = UpdateGraph.controller(applicationContext)
        controller.check()
        controller.maybeAnnounce()
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "legion-update-check"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(1, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}

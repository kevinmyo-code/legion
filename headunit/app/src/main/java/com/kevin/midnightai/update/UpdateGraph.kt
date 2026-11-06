package com.kevin.midnightai.update

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.kevin.midnightai.backend.engine.EngineConfig
import com.kevin.midnightai.backend.engine.EngineHttp
import com.kevin.midnightai.location.LocationController
import com.kevin.midnightai.service.BeaconService
import com.kevin.midnightai.service.ConversationState
import com.kevin.midnightai.service.DeviceRole
import com.kevin.midnightai.ui.MainActivity
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The updater's object graph, built by hand like the phone's `UpdateGraph`. One
 * [UpdateController] per process; the Cruise chip, the Settings leaf and the service loop all
 * reach the same one (ADR 0035). No WorkManager: this unit only runs while the ignition is on, so
 * the check rides [AriaForegroundService][com.kevin.midnightai.service.AriaForegroundService]
 * ([run]) at every start and every [CHECK_EVERY_MS] after.
 */
object UpdateGraph {
    @Volatile private var controller: UpdateController? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun controller(context: Context): UpdateController =
        controller ?: synchronized(this) {
            controller ?: build(context.applicationContext as Application).also { controller = it }
        }

    fun platform(context: Context): AndroidUpdatePlatform =
        AndroidUpdatePlatform(context.applicationContext as Application)

    private fun build(app: Application): UpdateController = UpdateController(
        backend = DjangoUpdateBackend(EngineHttp(EngineConfig(app))),
        downloader = OkHttpApkDownloader(),
        installer = AndroidPackageInstallerGateway(app),
        platform = AndroidUpdatePlatform(app),
        prefs = SharedUpdatePrefs(app),
        downloadDir = File(app.cacheDir, "updates"),
    )

    /**
     * The service's loop. Feeds [DrivingState] from location fixes, holds a mid-conversation tap
     * until the conversation ends, and checks shortly after start (a verified file from an earlier
     * drive is back on the Cruise chip within seconds) and then every [CHECK_EVERY_MS].
     */
    suspend fun run(context: Context) = coroutineScope {
        val c = controller(context)
        launch {
            LocationController.state.collect { loc ->
                if (loc != null && loc.hasSpeed()) DrivingState.note(loc.speed * MPS_TO_KMH, System.currentTimeMillis())
            }
        }
        launch {
            ConversationState.busy.drop(1).collectLatest { busy ->
                if (busy) return@collectLatest
                delay(AFTER_CONVERSATION_MS)
                runCatching { c.onConversationIdle() }
            }
        }
        delay(FIRST_CHECK_DELAY_MS)
        while (isActive) {
            runCatching { c.check() }.onFailure { Log.w(TAG, "update check failed: ${it.message}") }
            delay(CHECK_EVERY_MS)
        }
    }

    /**
     * A tap on Install (the Cruise chip or Settings). Without the "Install unknown apps" grant it
     * opens that Android screen instead and says so; otherwise it is [UpdateController.install].
     */
    suspend fun tapInstall(context: Context): InstallOutcome {
        if (!platform(context).mayInstallPackages()) {
            openInstallPermission(context)
            return InstallOutcome(
                false,
                false,
                "Not installed yet: switch on \"Allow from this source\" for ${UpdateRules.APP}, come back, and tap Install again.",
            )
        }
        return controller(context).install()
    }

    /** Android's per-app "Install unknown apps" screen (API 26+), or the security screen before it. */
    fun openInstallPermission(context: Context) {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
        } else {
            Intent(Settings.ACTION_SECURITY_SETTINGS)
        }
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure {
                // A vendor ROM without the per-app screen: the app's own details page is the next door.
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }
    }

    /** Runs [block] on the graph's scope (a receiver's goAsync work, a UI tap). */
    fun launch(block: suspend () -> Unit) = scope.launch { runCatching { block() } }

    private const val TAG = "MidnightUpdate"
    private const val MPS_TO_KMH = 3.6
    private const val FIRST_CHECK_DELAY_MS = 20_000L
    private const val CHECK_EVERY_MS = 6 * 60 * 60_000L
    private const val AFTER_CONVERSATION_MS = 3_000L
}

/**
 * PackageInstaller's answer. Success is never seen here (the process is replaced and
 * [PackageReplacedReceiver] runs instead). Pending user action means Android wants its own confirm
 * (the conditions for a silent self-update were not all met on this ROM): it is shown at once,
 * because it only ever follows Kevin's tap. A failure says Android's reason in words.
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
                    .onFailure { UpdateGraph.controller(app).onInstallFailed("its install confirm could not open (${it.message})") }
            }
            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                    ?.takeIf { it.isNotBlank() } ?: "status $status"
                UpdateGraph.controller(app).onInstallFailed(message)
            }
        }
    }

    companion object {
        const val ACTION_STATUS = "com.kevin.midnightai.update.INSTALL_STATUS"
    }
}

/**
 * The app was just replaced by an update. Android stops the old process; this brings back what a
 * boot brings back ([com.kevin.midnightai.service.BootReceiver]'s two branches) and deletes the
 * downloaded APK. As the default HOME app the system usually relaunches Cruise on its own; the
 * activity start here is the same belt-and-braces BootReceiver uses, and singleTask keeps one.
 */
class PackageReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val app = context.applicationContext
        runCatching { File(app.cacheDir, "updates").deleteRecursively() }
        if (DeviceRole.current(app) == DeviceRole.Role.GPS_BEACON) {
            runCatching { BeaconService.start(app) }
            return
        }
        runCatching {
            app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}

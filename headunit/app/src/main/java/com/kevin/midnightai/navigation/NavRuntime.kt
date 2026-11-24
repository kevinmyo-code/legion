package com.kevin.midnightai.navigation

import android.app.ActivityManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import com.kevin.midnightai.link.CarLink
import com.kevin.midnightai.link.LinkKeyStore
import com.kevin.midnightai.location.LocationController
import com.kevin.midnightai.ui.cruise.MiddlePanel
import com.mapbox.common.MapboxOptions
import kotlinx.coroutines.launch

/**
 * Process-wide wiring for the head unit's navigation (HU-1 T5). Nothing Mapbox is created at app
 * start: the SDK and the map are built only when the NAV face first needs them, and only with a
 * token the phone handed over the car link (never one baked into the build).
 *
 * The map must never take the launcher down: [NavController] catches SDK failures, and the crash
 * guard here catches a NATIVE crash (which no catch can): a flag is set before the map view is
 * built and cleared once its style loads (or it reports an error, or it is closed). If the process
 * dies in between, the next start finds the flag, keeps the map off and says so; TRY MAPS AGAIN
 * clears it. So a map that kills the process cannot loop the HOME launcher. The EGL capability
 * probe has a guard of its own ([GlProbeGuard]), because it is native driver work that runs BEFORE
 * any map view exists.
 */
object NavRuntime {
    val feed = NavMapFeed()
    lateinit var controller: NavController
        private set
    lateinit var provider: BeaconLocationProvider
        private set

    /** The Mapbox public token from the phone, or null. Read off the main thread. */
    @Volatile var token: String? = null
        private set

    private lateinit var guard: SharedPreferences
    private lateinit var app: Context
    private val main = Handler(Looper.getMainLooper())

    val isInitialised: Boolean get() = ::controller.isInitialized

    fun init(context: Context) {
        app = context.applicationContext
        guard = app.getSharedPreferences(GUARD_PREFS, Context.MODE_PRIVATE)
        provider = BeaconLocationProvider(LocationController.navFixes)
        controller = NavController(
            sdkFactory = {
                val t = token ?: error("no map token")
                quietMapbox()
                MapboxOptions.accessToken = t
                MapboxNavSdk(app, provider, feed)
            },
            hasToken = { token != null },
            availability = { unavailableReason() },
            position = { NavFix.freshOrigin(LocationController.navFixes.value, android.os.SystemClock.elapsedRealtimeNanos()) },
            onTripStarted = {
                LocationController.startNavMode(app) // 1 s fixes from the phone while guiding
                MiddlePanel.tripStarted()
            },
            onTripEnded = {
                LocationController.stopNavMode()
                MiddlePanel.tripEnded()
            },
            schedule = { ms, block -> main.postDelayed(block, ms) },
            positionFresh = {
                NavFix.freshOrigin(LocationController.navFixes.value, android.os.SystemClock.elapsedRealtimeNanos()) != null
            },
        )
        // Every navigation fix: an arrival held back while the position was stale may land now.
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main).launch {
            LocationController.navFixes.collect { if (it != null) controller.onFreshFix() }
        }
        refreshToken()
        CarLink.addListener { refreshToken() }
    }

    /** Re-reads the handed-over map token (Keystore: off the main thread), then refreshes the face. */
    fun refreshToken() {
        kotlin.concurrent.thread(name = "nav-token", isDaemon = true) {
            val read = runCatching { (CarLink.store as? LinkKeyStore)?.mapboxTokenRead() }.getOrNull()
                ?: TokenRead.Unreadable
            val before = token
            // Review C-1: a failed read keeps the token in use; only a real absence or a different
            // token changes it.
            val decision = NavTokenDecision.of(before, read)
            if (decision != NavTokenDecision.KEEP) token = (read as? TokenRead.Present)?.token
            main.post {
                // Review item 6: Forget (or a pairing with another token) drops the old token from
                // this process as far as the SDK allows: the navigator built with it is destroyed,
                // a trip ends in words, and the global access token is cleared. What stays until the
                // app restarts: Mapbox's native libraries and its on-disk tile cache.
                if (decision == NavTokenDecision.DROP) {
                    controller.dropSdk()
                    runCatching { MapboxOptions.accessToken = token ?: "" }
                }
                controller.refresh()
            }
        }
    }

    /** Null when the map may run here, else why not, in words. */
    fun unavailableReason(): String? {
        if (::guard.isInitialized && guard.getBoolean(KEY_MAP_STARTING, false)) {
            return "the map stopped this app the last time it opened. Tap TRY MAPS AGAIN to retry"
        }
        // An ActivityManager that cannot be read says nothing either way, so the map is left alone
        // and NOTHING is cached: the next open asks again rather than freezing a fallback in for
        // the life of the process.
        val declared = declaredGlEs() ?: return null
        // Review F2: the EGL probe is native work in a vendor driver and no catch covers that, so it
        // has its own guard. A probe that did not come back last time is not run again.
        if (declared < NavGles.GLES_3 && glProbe?.crashedBefore() == true) {
            return "the graphics check stopped this app the last time it opened. Tap TRY MAPS AGAIN to retry"
        }
        // Otherwise asked once per process: neither the ROM's claim nor the driver can change
        // while it runs, and the NAV face calls this on every open and every token refresh.
        val decision = es3 ?: NavGles.decide(declared, probeEs3Guarded()).also {
            es3 = it
            com.kevin.midnightai.diag.DriveLog.breadcrumb(
                "NAVGLES declared=${NavGles.version(declared)} decision=$it",
            )
        }
        return NavGles.words(declared, decision)
    }

    /** Cached [NavGles.Es3] for this process; see [unavailableReason]. */
    @Volatile private var es3: NavGles.Es3? = null

    /** The EGL probe's own crash guard, null only before [init] (then the probe runs unguarded). */
    private val glProbe: GlProbeGuard? get() = if (::guard.isInitialized) GlProbeGuard(guard) else null

    private fun probeEs3Guarded(): Boolean? =
        glProbe?.guarded { NavGles.probeEs3Context() } ?: NavGles.probeEs3Context()

    /**
     * Before any Mapbox object is built (review NAV-3): Mapbox's own logging down to errors
     * (`LogConfiguration.setLoggingLevel(LoggingLevel.ERROR)`, javap-checked); its lines are kept
     * out of the drive log anyway (`LogcatSnapshot`). Native: a unit that cannot load it is told
     * apart elsewhere, so a failure here is ignored.
     */
    internal fun quietMapbox() {
        runCatching { com.mapbox.common.LogConfiguration.setLoggingLevel(com.mapbox.common.LoggingLevel.ERROR) }
        // Review NAV-3c: Mapbox telemetry OFF, through the SDK's own supported switch
        // (`TelemetryUtils.setEventsCollectionState(false, callback)`, common 24.32.0, javap-checked;
        // the phone app's MapboxTelemetry uses the same call). The SDK keeps the state itself.
        runCatching { com.mapbox.common.TelemetryUtils.setEventsCollectionState(false) { } }
    }

    /** The guard is armed only while the native map is being brought up. */
    fun mapStarting() {
        quietMapbox()
        MapboxOptions.accessToken = token ?: return
        guard.edit().putBoolean(KEY_MAP_STARTING, true).commit()
    }

    fun mapStarted() {
        guard.edit().remove(KEY_MAP_STARTING).commit()
    }

    /** TRY MAPS AGAIN after a guarded crash, of the map or of the graphics check (review F2). */
    fun tryAgain() {
        mapStarted()
        glProbe?.clear()
        es3 = null
        controller.refresh()
    }

    /**
     * What the unit says its OpenGL ES version is - the ROM's own claim, not the driver's. Null when
     * ActivityManager could not be read, which is not an answer and so never takes the map away.
     */
    private fun declaredGlEs(): Int? = runCatching {
        val am = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        am.deviceConfigurationInfo.reqGlEsVersion
    }.getOrNull()

    private const val GUARD_PREFS = "nav_guard"
    private const val KEY_MAP_STARTING = "map_starting"
}

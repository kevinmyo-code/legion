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

/**
 * Process-wide wiring for the head unit's navigation (HU-1 T5). Nothing Mapbox is created at app
 * start: the SDK and the map are built only when the NAV face first needs them, and only with a
 * token the phone handed over the car link (never one baked into the build).
 *
 * The map must never take the launcher down: [NavController] catches SDK failures, and the crash
 * guard here catches a NATIVE crash (which no catch can): a flag is set before the map view is
 * built and cleared once its style loads (or it reports an error, or it is closed). If the process
 * dies in between, the next start finds the flag, keeps the map off and says so; TRY MAPS AGAIN
 * clears it. So a map that kills the process cannot loop the HOME launcher.
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
        )
        refreshToken()
        CarLink.addListener { refreshToken() }
    }

    /** Re-reads the handed-over map token (Keystore: off the main thread), then refreshes the face. */
    fun refreshToken() {
        kotlin.concurrent.thread(name = "nav-token", isDaemon = true) {
            val read = runCatching { (CarLink.store as? LinkKeyStore)?.mapboxToken() }.getOrNull()
            val before = token
            token = read
            main.post {
                // Review item 6: Forget (or a pairing with another token) drops the old token from
                // this process as far as the SDK allows: the navigator built with it is destroyed,
                // a trip ends in words, and the global access token is cleared. What stays until the
                // app restarts: Mapbox's native libraries and its on-disk tile cache.
                if (before != null && before != read) {
                    controller.dropSdk()
                    runCatching { MapboxOptions.accessToken = read ?: "" }
                }
                controller.refresh()
            }
        }
    }

    /** Null when the map may run here, else why not, in words. */
    fun unavailableReason(): String? = when {
        ::guard.isInitialized && guard.getBoolean(KEY_MAP_STARTING, false) ->
            "the map stopped this app the last time it opened. Tap TRY MAPS AGAIN to retry"
        !hasGles3() -> "its graphics chip is older than OpenGL ES 3, which the map needs"
        else -> null
    }

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

    /** TRY MAPS AGAIN after a guarded crash. */
    fun tryAgain() {
        mapStarted()
        controller.refresh()
    }

    private fun hasGles3(): Boolean = runCatching {
        val am = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        am.deviceConfigurationInfo.reqGlEsVersion >= GLES_3
    }.getOrDefault(true)

    private const val GUARD_PREFS = "nav_guard"
    private const val KEY_MAP_STARTING = "map_starting"
    private const val GLES_3 = 0x30000
}

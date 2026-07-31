package com.kevin.legion.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tracks the driver's current GPS location. Two modes:
 * - Normal (30s / 100m): background default. Battery-friendly.
 * - Fast (1s / 1m): activated via [startNavMode] while the Cruise screen is up so
 *   the live speed readout tracks in real time. Reverted via [stopNavMode] on exit
 *   so we don't drain battery in the background.
 *
 * Two sources feed the same [state], deliberately: this device's own GPS, and a
 * phone acting as a [BeaconServer] over the hotspot (see
 * [com.kevin.legion.service.DeviceRole] for why the Cherokee's head unit needs
 * one). Every consumer reads the same `StateFlow<Location?>` and cannot tell which
 * source produced a fix, which is what let the beacon land without touching any of
 * them.
 */
object LocationController {
    private val _state = MutableStateFlow<Location?>(null)
    val state: StateFlow<Location?> = _state.asStateFlow()

    /** Provider name stamped on beacon fixes, so a log or a bug report can tell them apart. */
    const val BEACON_PROVIDER = "midnight-beacon"

    private const val NAV_INTERVAL_MS = 1_000L
    private const val NORMAL_INTERVAL_MS = 30_000L

    /**
     * How long a real on-device fix suppresses beacon fixes. Long enough that a
     * device with working GPS ignores the beacon entirely between its own updates
     * at the 1s nav cadence, short enough that losing the internal signal (tunnel,
     * disconnected antenna) hands over within one normal reporting cycle.
     */
    private const val INTERNAL_FRESH_MS = 10_000L

    private var initialized = false

    /**
     * How many callers currently want the fast cadence, not a bare on/off flag.
     *
     * There are now two independent kinds of caller: the UI (Cruise and the nav
     * screen, via `DisposableEffect`) and [BeaconServer], which raises the rate
     * while a head unit is asking it for 1 Hz fixes. A phone in beacon role is a
     * full install and can have its own Cruise screen open at the same time, so with
     * a plain boolean whichever one released first would silently drop the other
     * back to the 30s background cadence.
     */
    private var navModeHolders = 0
    private val navModeActive: Boolean get() = navModeHolders > 0
    private var locationManager: LocationManager? = null
    private val navListener = LocationListener { location -> onInternalFix(location) }
    private val normalListener = LocationListener { location -> onInternalFix(location) }

    /**
     * Elapsed-realtime of the last LIVE fix from this device's own GPS, or null if
     * it has never produced one.
     *
     * Null rather than a 0 sentinel on purpose: `elapsedRealtime()` counts from
     * boot, not from process start, so "0" is a real timestamp - the instant the
     * device booted. An app process that starts within [INTERNAL_FRESH_MS] of boot
     * (plausible on a head unit that powers up with the ignition) would read
     * `now - 0 < 10s` as "the internal GPS just reported" and silently drop every
     * beacon fix for that window, on a device whose GPS has never reported anything.
     */
    @Volatile private var lastInternalAtMs: Long? = null

    /**
     * The reporting rate a [BeaconClient] should ask its phone for right now.
     * Reads the same nav-mode flag the internal providers use, so the existing
     * `startNavMode`/`stopNavMode` call sites drive the beacon's rate for free.
     */
    val desiredIntervalMs: Long
        get() = if (navModeActive) NAV_INTERVAL_MS else NORMAL_INTERVAL_MS

    fun hasPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    /** Safe to call repeatedly - retries until location permission is granted. */
    @SuppressLint("MissingPermission")
    fun init(context: Context) {
        if (initialized) return
        if (!hasPermission(context)) return

        val appContext = context.applicationContext
        val lm = appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        locationManager = lm

        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            if (!lm.isProviderEnabled(provider)) continue
            // Seeded into state but deliberately NOT counted as a live internal fix.
            // getLastKnownLocation can hand back something hours old, and stamping
            // lastInternalAtMs with "now" for it would claim the GPS is reporting
            // right now - suppressing genuinely current beacon fixes in favour of a
            // cached position from yesterday's drive.
            if (_state.value == null) lm.getLastKnownLocation(provider)?.let { _state.value = it }
            lm.requestLocationUpdates(provider, NORMAL_INTERVAL_MS, 100f, normalListener, Looper.getMainLooper())
        }
        initialized = true
    }

    /**
     * Switches to 1s / 1m updates for map-follow and nav-progress tracking.
     * Call from a Compose [DisposableEffect] so [stopNavMode] is guaranteed on exit.
     */
    @SuppressLint("MissingPermission")
    @Synchronized
    fun startNavMode(context: Context) {
        // Count the request BEFORE the `initialized` bail-out. On a head unit with no
        // usable GPS of its own, init() never completes - but nav mode still has to
        // raise [desiredIntervalMs], because that is what tells the beacon phone to
        // sample at 1 Hz. Bailing first would leave the map crawling at the 30s
        // background cadence on exactly the device that depends on the beacon.
        navModeHolders++
        if (navModeHolders > 1) return
        if (!initialized) return
        val lm = locationManager ?: return
        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            if (!lm.isProviderEnabled(provider)) continue
            lm.requestLocationUpdates(provider, NAV_INTERVAL_MS, 1f, navListener, Looper.getMainLooper())
        }
    }

    /** Releases one nav-mode request. Safe to call if this caller never took one. */
    @Synchronized
    fun stopNavMode() {
        if (navModeHolders == 0) return
        navModeHolders--
        if (navModeHolders > 0) return
        locationManager?.removeUpdates(navListener)
    }

    private fun onInternalFix(location: Location) {
        lastInternalAtMs = SystemClock.elapsedRealtime()
        _state.value = location
    }

    /**
     * Publishes a fix that arrived from a phone beacon.
     *
     * **This device's own GPS wins while it is producing fixes.** Right now that
     * branch never fires on the Cherokee's head unit (its GPS antenna cannot be
     * connected without dropping WiFi/BT), but writing the priority in means that
     * repairing the antenna later needs no code change - the beacon simply stops
     * being used - instead of leaving a silent conflict between two live sources.
     *
     * **The receipt clocks are stamped here, not carried on the wire, and that is
     * load-bearing.** `elapsedRealtimeNanos` is measured from device boot, so the
     * phone's value is meaningless on the head unit - they booted at different
     * times. Consumers (and Mapbox) use that field to judge staleness, so copying
     * the sender's would make every fix look wildly stale or wildly future-dated
     * depending on which device booted first. Wall-clock `time` is stamped locally
     * for the same reason, one NTP skew removed. The values are honest because the
     * transport only ever carries a live fix - see [BeaconProtocol].
     */
    fun acceptExternal(fix: BeaconFix) {
        val lastInternal = lastInternalAtMs
        if (lastInternal != null && SystemClock.elapsedRealtime() - lastInternal < INTERNAL_FRESH_MS) return
        _state.value = Location(BEACON_PROVIDER).apply {
            latitude = fix.lat
            longitude = fix.lng
            fix.accuracyM?.let { accuracy = it }
            fix.bearingDeg?.let { bearing = it }
            fix.speedMps?.let { speed = it }
            fix.altitudeM?.let { altitude = it }
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        }
    }
}

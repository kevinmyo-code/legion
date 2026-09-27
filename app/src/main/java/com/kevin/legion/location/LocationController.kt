package com.kevin.legion.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tracks the user's current location. Two modes:
 * - Normal (30s / 100m): background default, **network and passive providers only, never GPS**.
 * - Fast (1s / 1m): activated via [startFastMode] while a screen needs live speed/position
 *   tracking. Adds GPS. Reverted via [stopFastMode] on exit.
 *
 * **Normal mode used to request GPS and called itself "battery-friendly". It was not
 * (2026-09-27).** A 100 m distance filter does not let the GPS chip sleep, because the chip has to
 * compute a fix to find out whether you moved 100 m. On the A25 `dumpsys location` showed LEGION
 * as the sole reason the GPS radio was on around the clock (`mFixInterval=30000`, HIGH_ACCURACY),
 * while every other app asked passively. That was a head-unit habit, from when the car paid for
 * the power.
 *
 * Background now uses [BACKGROUND_PROVIDERS]: network location (Wi-Fi and cell, nearly free) plus
 * passive, which takes any fix another app already paid for at no cost to us. Kevin accepted the
 * trade-off in writing: place-arrival triggers run on network accuracy (~20-100 m in town)
 * against 150 m arrival zones, and may lag or miss somewhere with no Wi-Fi nearby.
 *
 * Phone-only app, so this is just the device's own GPS - no beacon/relay logic (that existed
 * only for Midnight AI's head-unit hardware, which had no working GPS of its own).
 */
object LocationController {
    private val _state = MutableStateFlow<Location?>(null)
    val state: StateFlow<Location?> = _state.asStateFlow()

    private const val FAST_INTERVAL_MS = 1_000L
    private const val NORMAL_INTERVAL_MS = 30_000L

    private var initialized = false

    /** How many callers currently want the fast cadence, not a bare on/off flag - so two
     * independent callers (e.g. two screens) don't have one release drop the other back
     * to the 30s background cadence. */
    private var fastModeHolders = 0
    private var locationManager: LocationManager? = null
    private val fastListener = LocationListener { location -> _state.value = location }
    private val normalListener = LocationListener { location -> _state.value = location }

    fun hasPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * True if GPS or network location is switched on system-wide, independent of whether THIS
     * app has been granted permission to use it. Exists so callers - specifically
     * `get_current_location`'s "why is this null" branching - can tell a driver who granted the
     * permission but left Location off in Android's quick settings apart from a driver who is
     * simply still acquiring a fix, two situations [init] itself doesn't need to distinguish
     * (it just skips a disabled provider and moves on) but a spoken answer does. Reads off
     * whichever [LocationManager] [init] already resolved when possible so this doesn't need its
     * own permission check to ask the system service for one; falls back to a fresh lookup for a
     * caller that runs before [init] ever succeeded (e.g. permission was granted but init hasn't
     * been re-run yet in this process).
     */
    fun anyProviderEnabled(context: Context): Boolean {
        val lm = locationManager
            ?: context.applicationContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return false
        return lm.isProviderEnabled(LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    /** Safe to call repeatedly - retries until location permission is granted. */
    @SuppressLint("MissingPermission")
    fun init(context: Context) {
        if (initialized) return
        if (!hasPermission(context)) return

        val appContext = context.applicationContext
        val lm = appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        locationManager = lm

        // Seeding from a cached fix is free on every provider, GPS included - it reads what is
        // already there and does not power the chip.
        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            if (_state.value == null && lm.isProviderEnabled(provider)) {
                lm.getLastKnownLocation(provider)?.let { _state.value = it }
            }
        }
        for (provider in BACKGROUND_PROVIDERS) {
            if (!lm.isProviderEnabled(provider)) continue
            lm.requestLocationUpdates(provider, NORMAL_INTERVAL_MS, 100f, normalListener, Looper.getMainLooper())
        }
        initialized = true
    }

    /** Never GPS - see this object's class doc. Pinned by `LocationControllerBackgroundTest`. */
    internal val BACKGROUND_PROVIDERS = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)

    /** Test seam: forget the one-time [init] so a test can run it against a fresh shadow. */
    internal fun resetForTest() {
        locationManager?.removeUpdates(normalListener)
        locationManager?.removeUpdates(fastListener)
        locationManager = null
        initialized = false
        fastModeHolders = 0
        _state.value = null
    }

    /**
     * Switches to 1s / 1m updates for any screen that needs live speed/position.
     * Call from a Compose `DisposableEffect` so [stopFastMode] is guaranteed on exit.
     */
    @SuppressLint("MissingPermission")
    @Synchronized
    fun startFastMode(context: Context) {
        fastModeHolders++
        if (fastModeHolders > 1) return
        if (!initialized) return
        val lm = locationManager ?: return
        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            if (!lm.isProviderEnabled(provider)) continue
            lm.requestLocationUpdates(provider, FAST_INTERVAL_MS, 1f, fastListener, Looper.getMainLooper())
        }
    }

    /** Releases one fast-mode request. Safe to call if this caller never took one. */
    @Synchronized
    fun stopFastMode() {
        if (fastModeHolders == 0) return
        fastModeHolders--
        if (fastModeHolders > 0) return
        locationManager?.removeUpdates(fastListener)
    }
}

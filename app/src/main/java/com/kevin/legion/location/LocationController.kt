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
 * Tracks the driver's current GPS location. Two modes:
 * - Normal (30s / 100m): background default. Battery-friendly.
 * - Fast (1s / 1m): activated via [startNavMode] while a screen needs live speed/position
 *   tracking. Reverted via [stopNavMode] on exit so we don't drain battery in the background.
 *
 * Phone-only app, so this is just the device's own GPS - no beacon/relay logic (that existed
 * only for Midnight AI's head-unit hardware, which had no working GPS of its own).
 */
object LocationController {
    private val _state = MutableStateFlow<Location?>(null)
    val state: StateFlow<Location?> = _state.asStateFlow()

    private const val NAV_INTERVAL_MS = 1_000L
    private const val NORMAL_INTERVAL_MS = 30_000L

    private var initialized = false

    /** How many callers currently want the fast cadence, not a bare on/off flag - so two
     * independent callers (e.g. two screens) don't have one release drop the other back
     * to the 30s background cadence. */
    private var navModeHolders = 0
    private var locationManager: LocationManager? = null
    private val navListener = LocationListener { location -> _state.value = location }
    private val normalListener = LocationListener { location -> _state.value = location }

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
            if (_state.value == null) lm.getLastKnownLocation(provider)?.let { _state.value = it }
            lm.requestLocationUpdates(provider, NORMAL_INTERVAL_MS, 100f, normalListener, Looper.getMainLooper())
        }
        initialized = true
    }

    /**
     * Switches to 1s / 1m updates for map-follow and nav-progress tracking.
     * Call from a Compose `DisposableEffect` so [stopNavMode] is guaranteed on exit.
     */
    @SuppressLint("MissingPermission")
    @Synchronized
    fun startNavMode(context: Context) {
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
}

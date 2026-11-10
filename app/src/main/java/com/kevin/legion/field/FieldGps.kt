package com.kevin.legion.field

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.GeomagneticField
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** GPS state in the field: the provider's own facts, never a network or fused guess. */
sealed interface GpsState {
    data object NoPermission : GpsState

    data object Off : GpsState

    data object Searching : GpsState

    data class Fix(val location: Location) : GpsState
}

/**
 * GPS only (round 5: "work from GPS only, with no network location"): [LocationManager.GPS_PROVIDER]
 * directly, not the fused provider, so a fix means satellites, and no fix means no fix. The last
 * GPS fix the phone kept is shown at once and marked by its age until a fresh one arrives.
 */
class FieldGps(private val context: Context) : LocationListener {
    private val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager?
    private val _state = MutableStateFlow<GpsState>(GpsState.Searching)
    val state: StateFlow<GpsState> = _state.asStateFlow()

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission") // checked by hasPermission() on the line before
    fun start() {
        val m = manager
        when {
            !hasPermission() -> _state.value = GpsState.NoPermission
            m == null || !m.isProviderEnabled(LocationManager.GPS_PROVIDER) -> _state.value = GpsState.Off
            else -> {
                m.getLastKnownLocation(LocationManager.GPS_PROVIDER)?.let { _state.value = GpsState.Fix(it) }
                    ?: run { _state.value = GpsState.Searching }
                m.requestLocationUpdates(LocationManager.GPS_PROVIDER, INTERVAL_MS, 0f, this, Looper.getMainLooper())
            }
        }
    }

    fun stop() {
        manager?.removeUpdates(this)
    }

    override fun onLocationChanged(location: Location) {
        _state.value = GpsState.Fix(location)
    }

    override fun onProviderDisabled(provider: String) {
        _state.value = GpsState.Off
    }

    override fun onProviderEnabled(provider: String) {
        if (_state.value == GpsState.Off) _state.value = GpsState.Searching
    }

    private companion object {
        const val INTERVAL_MS = 1_000L
    }
}

/** Magnetic declination (east positive) from Android's built-in World Magnetic Model, offline. */
object Declination {
    fun at(lat: Double, lon: Double, altitudeM: Double, timeMs: Long): Double =
        GeomagneticField(lat.toFloat(), lon.toFloat(), altitudeM.toFloat(), timeMs).declination.toDouble()
}

package com.kevin.legion.field

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlin.math.abs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Which sensor the heading comes from. */
enum class CompassSource { ROTATION_VECTOR, ACCEL_MAG, NONE }

/** The magnetometer's own accuracy report, the four levels Android gives, plus "no compass". */
enum class CompassAccuracy(val words: String) {
    HIGH("high"),
    MEDIUM("medium"),
    LOW("low: calibrate"),
    UNRELIABLE("unreliable: calibrate"),
    NO_SENSOR("no compass sensor on this phone"),
    ;

    val needsCalibration: Boolean get() = this == LOW || this == UNRELIABLE

    companion object {
        fun of(status: Int): CompassAccuracy = when (status) {
            SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> HIGH
            SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> MEDIUM
            SensorManager.SENSOR_STATUS_ACCURACY_LOW -> LOW
            else -> UNRELIABLE
        }
    }
}

/** One smoothed compass reading: magnetic azimuth of the phone's top edge (or its back, held up). */
data class CompassReading(
    val magneticDeg: Double,
    val accuracy: CompassAccuracy,
    val source: CompassSource,
    val upright: Boolean,
)

/**
 * The phone's compass (field navigation, round 5): the fused rotation-vector sensor when the phone
 * has one, else the accelerometer and magnetometer through [SensorManager.getRotationMatrix].
 * Smoothed with [HeadingSmoother]. Works with no signal: nothing here touches the network.
 *
 * **Held flat** the heading is where the phone's top edge points. **Held upright** (tilted past
 * about 50 degrees, sighting along the camera) the axes are remapped so the heading is where the
 * BACK of the phone points, the way a sighting compass is used.
 *
 * **Accuracy** is the magnetometer's own report (`onAccuracyChanged`), listened to even when the
 * heading comes from the rotation vector, because the fused sensor's accuracy field is not reported
 * by every phone. Low or unreliable means the figure-8 prompt. None of this can be checked in a unit
 * test; the arithmetic around it can ([HeadingSmoother], [FieldMath]), and the rest is a phone step.
 */
class CompassSensor(context: Context) : SensorEventListener {
    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager?
    private val rotation = manager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val accel = manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val magnet = manager?.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

    val source: CompassSource = when {
        rotation != null -> CompassSource.ROTATION_VECTOR
        accel != null && magnet != null -> CompassSource.ACCEL_MAG
        else -> CompassSource.NONE
    }

    private val _reading = MutableStateFlow<CompassReading?>(null)
    val reading: StateFlow<CompassReading?> = _reading.asStateFlow()

    private val smoother = HeadingSmoother()
    private var accuracy = if (source == CompassSource.NONE) CompassAccuracy.NO_SENSOR else CompassAccuracy.MEDIUM
    private val gravity = FloatArray(3)
    private val geomagnetic = FloatArray(3)
    private var haveGravity = false
    private var haveMagnet = false
    private val r = FloatArray(9)
    private val remapped = FloatArray(9)
    private val orientation = FloatArray(3)

    fun start() {
        val m = manager ?: return
        when (source) {
            CompassSource.ROTATION_VECTOR -> m.registerListener(this, rotation, SensorManager.SENSOR_DELAY_UI)
            CompassSource.ACCEL_MAG -> m.registerListener(this, accel, SensorManager.SENSOR_DELAY_UI)
            CompassSource.NONE -> return
        }
        // The magnetometer is registered in both modes: its accuracy is the calibration signal.
        magnet?.let { m.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
    }

    fun stop() {
        manager?.unregisterListener(this)
        smoother.reset()
    }

    override fun onAccuracyChanged(sensor: Sensor, status: Int) {
        if (sensor.type == Sensor.TYPE_MAGNETIC_FIELD) {
            accuracy = CompassAccuracy.of(status)
            _reading.value = _reading.value?.copy(accuracy = accuracy)
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                SensorManager.getRotationMatrixFromVector(r, event.values)
                publish()
            }
            Sensor.TYPE_ACCELEROMETER -> {
                event.values.copyInto(gravity, endIndex = 3)
                haveGravity = true
                if (source == CompassSource.ACCEL_MAG && haveMagnet &&
                    SensorManager.getRotationMatrix(r, null, gravity, geomagnetic)
                ) {
                    publish()
                }
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                event.values.copyInto(geomagnetic, endIndex = 3)
                haveMagnet = true
                // A first accuracy report can arrive only with the events on some phones.
                if (event.accuracy != accuracyStatusUnknown) accuracy = CompassAccuracy.of(event.accuracy)
            }
        }
    }

    private fun publish() {
        // r[8] is the z component of gravity in device axes: near +-1 flat, near 0 upright.
        val upright = abs(r[8]) < UPRIGHT_COS
        val matrix = if (upright) {
            SensorManager.remapCoordinateSystem(r, SensorManager.AXIS_X, SensorManager.AXIS_Z, remapped)
            remapped
        } else {
            r
        }
        SensorManager.getOrientation(matrix, orientation)
        val deg = FieldMath.normalize(Math.toDegrees(orientation[0].toDouble()))
        _reading.value = CompassReading(smoother.update(deg), accuracy, source, upright)
    }

    private companion object {
        /** cos(50 deg): tilted further than this from flat counts as held upright. */
        const val UPRIGHT_COS = 0.64f
        const val accuracyStatusUnknown = -1
    }
}

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

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

    private companion object {
        const val INTERVAL_MS = 1_000L
    }
}

/** Magnetic declination (east positive) from Android's built-in World Magnetic Model, offline. */
object Declination {
    fun at(lat: Double, lon: Double, altitudeM: Double, timeMs: Long): Double =
        GeomagneticField(lat.toFloat(), lon.toFloat(), altitudeM.toFloat(), timeMs).declination.toDouble()
}

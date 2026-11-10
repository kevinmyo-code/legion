@file:Suppress("MagicNumber") // sensor vector and matrix sizes (3 and 9) fixed by the Android API

package com.kevin.legion.field

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
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
                if (event.accuracy != ACCURACY_UNKNOWN) accuracy = CompassAccuracy.of(event.accuracy)
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
        const val ACCURACY_UNKNOWN = -1
    }
}

/**
 * Smooths compass headings without the 359-to-0 jump: an exponential average of the unit vector,
 * not of the angle. [alpha] is the weight of each new reading (0.15 settles in about half a second
 * at UI sensor rates).
 */
class HeadingSmoother(private val alpha: Double = DEFAULT_ALPHA) {
    private var x = 0.0
    private var y = 0.0
    private var primed = false

    fun update(deg: Double): Double {
        val r = deg * PI / HALF_TURN_DEG
        if (!primed) {
            x = cos(r)
            y = sin(r)
            primed = true
        } else {
            x += alpha * (cos(r) - x)
            y += alpha * (sin(r) - y)
        }
        return FieldMath.normalize(atan2(y, x) * HALF_TURN_DEG / PI)
    }

    fun reset() {
        primed = false
    }

    private companion object {
        const val DEFAULT_ALPHA = 0.15
        const val HALF_TURN_DEG = 180.0
    }
}

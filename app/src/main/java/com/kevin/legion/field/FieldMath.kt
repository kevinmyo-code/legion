package com.kevin.legion.field

import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The field screen's arithmetic, pure and offline (round 5): bearing and distance on the sphere,
 * the back-azimuth, the arrow's angle against the compass, smoothing that survives 359-to-0, and
 * every number put into words. Offline-hiking ticket 16's rules: great-circle initial bearing,
 * straight-line distance said as straight line, declination from Android's own model.
 */
object FieldMath {
    private const val EARTH_RADIUS_M = 6_371_008.8
    private const val DEG = PI / 180.0
    private const val M_PER_MILE = 1609.344
    private const val FT_PER_M = 3.28084

    private val POINTS_16 = arrayOf(
        "N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
        "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW",
    )
    private val WORDS_8 = arrayOf(
        "north", "northeast", "east", "southeast", "south", "southwest", "west", "northwest",
    )

    fun normalize(deg: Double): Double {
        val x = deg % 360.0
        return if (x < 0) x + 360.0 else x
    }

    /** Great-circle initial bearing from point 1 to point 2, degrees true, 0 to 360. */
    fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = lat1 * DEG
        val p2 = lat2 * DEG
        val dl = (lon2 - lon1) * DEG
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return normalize(atan2(y, x) / DEG)
    }

    /** Haversine distance in metres (mean Earth radius; about 0.5% from the ellipsoid at worst). */
    fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dp = (lat2 - lat1) * DEG
        val dl = (lon2 - lon1) * DEG
        val a = sin(dp / 2) * sin(dp / 2) + cos(lat1 * DEG) * cos(lat2 * DEG) * sin(dl / 2) * sin(dl / 2)
        return 2 * EARTH_RADIUS_M * asin(min(1.0, sqrt(a)))
    }

    fun backAzimuth(deg: Double): Double = normalize(deg + 180.0)

    /** True from magnetic: true = magnetic + declination (east positive, Android's sign). */
    fun trueFromMagnetic(magneticDeg: Double, declinationDeg: Double): Double =
        normalize(magneticDeg + declinationDeg)

    fun magneticFromTrue(trueDeg: Double, declinationDeg: Double): Double =
        normalize(trueDeg - declinationDeg)

    /** Where the arrow points on screen: the target's bearing less the heading, both true. */
    fun arrowDeg(targetTrueDeg: Double, headingTrueDeg: Double): Double =
        normalize(targetTrueDeg - headingTrueDeg)

    fun point16(deg: Double): String = POINTS_16[((normalize(deg) + 11.25) / 22.5).toInt() % 16]

    fun word8(deg: Double): String = WORDS_8[((normalize(deg) + 22.5) / 45.0).toInt() % 8]

    fun degText(deg: Double): String = "%03d".format(Locale.US, normalize(deg).roundToInt() % 360)

    /** "17 deg 34 min east" style, for the declination line. */
    fun declinationText(deg: Double): String {
        val side = if (deg >= 0) "east" else "west"
        return "%.1f° %s".format(Locale.US, abs(deg), side)
    }

    /** "29.97012 N, 98.70011 W". */
    fun latLonText(lat: Double, lon: Double): String =
        "%.5f %s, %.5f %s".format(
            Locale.US, abs(lat), if (lat >= 0) "N" else "S", abs(lon), if (lon >= 0) "E" else "W",
        )

    /** "420 m (0.3 mi)" up to a kilometre, then "4.2 km (2.6 mi)". */
    fun distanceText(m: Double): String {
        val miles = m / M_PER_MILE
        return if (m < 1000) {
            "%d m (%.1f mi)".format(Locale.US, m.roundToInt(), miles)
        } else {
            "%.1f km (%.1f mi)".format(Locale.US, m / 1000, miles)
        }
    }

    fun altitudeText(m: Double?): String =
        m?.let { "%d m (%d ft)".format(Locale.US, it.roundToInt(), (it * FT_PER_M).roundToInt()) }
            ?: "unknown (no altitude in this fix)"

    /** GPS horizontal accuracy (68% radius) in words a person can act on. */
    fun accuracyWords(m: Float?): String {
        if (m == null || m <= 0f) return "accuracy unknown"
        val r = m.roundToInt()
        return when {
            m <= 5f -> "excellent, within about $r m"
            m <= 15f -> "good, within about $r m"
            m <= 50f -> "fair, within about $r m"
            m <= 200f -> "poor, could be off by $r m"
            else -> "very poor, could be off by $r m or more; wait for a better fix"
        }
    }
}

/**
 * Smooths compass headings without the 359-to-0 jump: an exponential average of the unit vector,
 * not of the angle. [alpha] is the weight of each new reading (0.15 settles in about half a second
 * at UI sensor rates).
 */
class HeadingSmoother(private val alpha: Double = 0.15) {
    private var x = 0.0
    private var y = 0.0
    private var primed = false

    fun update(deg: Double): Double {
        val r = deg * PI / 180.0
        if (!primed) {
            x = cos(r)
            y = sin(r)
            primed = true
        } else {
            x += alpha * (cos(r) - x)
            y += alpha * (sin(r) - y)
        }
        return FieldMath.normalize(atan2(y, x) * 180.0 / PI)
    }

    fun reset() {
        primed = false
    }
}

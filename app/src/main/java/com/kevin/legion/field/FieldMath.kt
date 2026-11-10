@file:Suppress("MagicNumber") // spherical trigonometry and compass-point arithmetic

package com.kevin.legion.field

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
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
}

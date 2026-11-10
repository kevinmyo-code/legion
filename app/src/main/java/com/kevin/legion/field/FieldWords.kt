@file:Suppress("MagicNumber") // unit conversions and accuracy bands, each named in its own words

package com.kevin.legion.field

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** The field screen's numbers in words: degrees, distances, altitude, GPS accuracy (round 5). */
object FieldWords {
    private const val M_PER_MILE = 1609.344
    private const val FT_PER_M = 3.28084

    fun degText(deg: Double): String = "%03d".format(Locale.US, FieldMath.normalize(deg).roundToInt() % 360)

    /** "3.2 deg east", for the declination line. */
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

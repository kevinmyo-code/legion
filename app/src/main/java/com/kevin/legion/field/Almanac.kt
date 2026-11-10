@file:Suppress("MagicNumber") // Meeus lunar-theory coefficients and solar constants

package com.kevin.legion.field

import com.kevin.legion.navigation.SolarDay
import com.kevin.legion.navigation.SunTimes
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/** Sun times for the solar day around now, epoch ms; null when the sun never reaches that altitude
 * (or never leaves it) today, said in [polarNote]. */
data class SunDay(
    val sunrise: Long?,
    val sunset: Long?,
    val civilDawn: Long?,
    val civilDusk: Long?,
    val nauticalDawn: Long?,
    val nauticalDusk: Long?,
    val polarNote: String?,
)

/** Moonrise and moonset inside the window asked for (null: none in it), and the phase now. */
data class MoonDay(
    val rise: Long?,
    val set: Long?,
    val illumination: Double,
    val phaseName: String,
    val waxing: Boolean,
)

enum class TurnaroundStatus { AHEAD, PAST_TURNAROUND, AFTER_SUNSET, NO_SUNSET }

data class Turnaround(val atMs: Long?, val marginMin: Int, val status: TurnaroundStatus)

/**
 * Sun and moon on the phone, offline (field navigation, round 5).
 *
 * - **Sun:** [SunTimes] (the nav map's own sunrise equation, `navigation/MapLight.kt`) at -0.833,
 *   -6 and -12 degrees. Minutes-accurate, which is what a turnaround needs.
 * - **Moon:** a truncated lunar theory (Meeus, *Astronomical Algorithms*, 2nd ed., ch. 47: the six
 *   largest longitude terms, four latitude and four distance terms), written for LEGION. Rise and
 *   set are found by scanning the moon's altitude every [STEP_MS] and interpolating the crossing of
 *   the standard horizon (refraction 34', the moon's semi-diameter and its horizontal parallax).
 *   `AlmanacTest` holds it within five minutes of an independent ephemeris (PyEphem).
 * - **Illumination** from the sun-moon elongation, corrected for the moon's distance.
 */
object Almanac {
    private const val DEG = PI / 180.0
    private const val MS_PER_DAY = 86_400_000.0
    private const val UNIX_EPOCH_JD = 2_440_587.5
    private const val J2000 = 2_451_545.0
    private const val OBLIQUITY = 23.4397 * DEG
    private const val EARTH_RADIUS_KM = 6378.14
    private const val MOON_RADIUS_KM = 1737.4
    private const val SUN_DISTANCE_KM = 149_598_000.0
    private const val REFRACTION_DEG = 0.5667
    const val STEP_MS = 10 * 60_000L
    const val DAY_MS = 86_400_000L

    fun sunDay(nowMs: Long, lat: Double, lon: Double): SunDay {
        val main = SunTimes.solarDay(nowMs, lat, lon)
        val civil = SunTimes.solarDay(nowMs, lat, lon, SunTimes.CIVIL_TWILIGHT_DEG)
        val nautical = SunTimes.solarDay(nowMs, lat, lon, SunTimes.NAUTICAL_TWILIGHT_DEG)
        val note = when (main) {
            SolarDay.AlwaysUp -> "The sun does not set today at this latitude."
            SolarDay.AlwaysDown -> "The sun does not rise today at this latitude."
            is SolarDay.Normal -> null
        }
        return SunDay(
            sunrise = (main as? SolarDay.Normal)?.riseMs,
            sunset = (main as? SolarDay.Normal)?.setMs,
            civilDawn = (civil as? SolarDay.Normal)?.riseMs,
            civilDusk = (civil as? SolarDay.Normal)?.setMs,
            nauticalDawn = (nautical as? SolarDay.Normal)?.riseMs,
            nauticalDusk = (nautical as? SolarDay.Normal)?.setMs,
            polarNote = note,
        )
    }

    /** Turnaround = sunset less [marginMin]; its status against [nowMs]. */
    fun turnaround(nowMs: Long, sunsetMs: Long?, marginMin: Int): Turnaround {
        if (sunsetMs == null) return Turnaround(null, marginMin, TurnaroundStatus.NO_SUNSET)
        val at = sunsetMs - marginMin * 60_000L
        val status = when {
            nowMs >= sunsetMs -> TurnaroundStatus.AFTER_SUNSET
            nowMs >= at -> TurnaroundStatus.PAST_TURNAROUND
            else -> TurnaroundStatus.AHEAD
        }
        return Turnaround(at, marginMin, status)
    }

    /** Moonrise and moonset in [startMs, startMs + windowMs), and the phase at [nowMs]. */
    fun moonDay(nowMs: Long, startMs: Long, lat: Double, lon: Double, windowMs: Long = DAY_MS): MoonDay {
        var rise: Long? = null
        var set: Long? = null
        var t0 = startMs
        var h0 = moonHorizonDelta(t0, lat, lon)
        while (t0 < startMs + windowMs && (rise == null || set == null)) {
            val t1 = t0 + STEP_MS
            val h1 = moonHorizonDelta(t1, lat, lon)
            if (h0 < 0 && h1 >= 0 && rise == null) rise = cross(t0, h0, t1, h1)
            if (h0 >= 0 && h1 < 0 && set == null) set = cross(t0, h0, t1, h1)
            t0 = t1
            h0 = h1
        }
        val phase = phase(nowMs)
        return MoonDay(rise, set, phase.first, phase.second, phase.third)
    }

    /** (illuminated fraction 0..1, phase name, waxing) at [ms]. */
    fun phase(ms: Long): Triple<Double, String, Boolean> {
        val d = days(ms)
        val moon = moonEcliptic(d)
        val sunLon = sunEclipticLon(d)
        val cosPsi = cos(moon.lat) * cos(moon.lon - sunLon)
        val psi = kotlin.math.acos(cosPsi.coerceIn(-1.0, 1.0))
        val inc = atan2(SUN_DISTANCE_KM * sin(psi), moon.distKm - SUN_DISTANCE_KM * cos(psi))
        val fraction = (1 + cos(inc)) / 2
        val elongation = FieldMath.normalize((moon.lon - sunLon) / DEG)
        return Triple(fraction, phaseName(elongation), elongation < 180.0)
    }

    fun phaseName(elongationDeg: Double): String = when (FieldMath.normalize(elongationDeg)) {
        in 0.0..<22.5, in 337.5..360.0 -> "New moon"
        in 22.5..<67.5 -> "Waxing crescent"
        in 67.5..<112.5 -> "First quarter"
        in 112.5..<157.5 -> "Waxing gibbous"
        in 157.5..<202.5 -> "Full moon"
        in 202.5..<247.5 -> "Waning gibbous"
        in 247.5..<292.5 -> "Last quarter"
        else -> "Waning crescent"
    }

    // ------------------------------------------------------------------ internals

    private data class Ecliptic(val lon: Double, val lat: Double, val distKm: Double)

    private fun days(ms: Long): Double = ms / MS_PER_DAY + UNIX_EPOCH_JD - J2000

    private fun moonEcliptic(d: Double): Ecliptic {
        val l = (218.316 + 13.176396 * d) * DEG
        val mMoon = (134.963 + 13.064993 * d) * DEG
        val mSun = (357.5291 + 0.98560028 * d) * DEG
        val f = (93.272 + 13.229350 * d) * DEG
        val e = (297.8502 + 12.19074912 * d) * DEG
        val lon = l + DEG * (
            6.289 * sin(mMoon) + 1.274 * sin(2 * e - mMoon) + 0.658 * sin(2 * e) +
                0.214 * sin(2 * mMoon) - 0.186 * sin(mSun) - 0.114 * sin(2 * f)
            )
        val lat = DEG * (
            5.128 * sin(f) + 0.281 * sin(mMoon + f) + 0.278 * sin(mMoon - f) + 0.173 * sin(2 * e - f)
            )
        val dist = 385_001.0 - 20_905.0 * cos(mMoon) - 3_699.0 * cos(2 * e - mMoon) - 2_956.0 * cos(2 * e)
        return Ecliptic(lon, lat, dist)
    }

    private fun sunEclipticLon(d: Double): Double {
        val m = (357.5291 + 0.98560028 * d) * DEG
        val c = DEG * (1.9148 * sin(m) + 0.02 * sin(2 * m) + 0.0003 * sin(3 * m))
        return m + c + 102.9372 * DEG + PI
    }

    /** Geocentric altitude of the moon's centre less the altitude at which it rises or sets. */
    private fun moonHorizonDelta(ms: Long, lat: Double, lon: Double): Double {
        val d = days(ms)
        val moon = moonEcliptic(d)
        val ra = atan2(sin(moon.lon) * cos(OBLIQUITY) - tan(moon.lat) * sin(OBLIQUITY), cos(moon.lon))
        val dec = asin(sin(moon.lat) * cos(OBLIQUITY) + cos(moon.lat) * sin(OBLIQUITY) * sin(moon.lon))
        val lst = (280.46061837 + 360.98564736629 * d + lon) * DEG
        val h = lst - ra
        val phi = lat * DEG
        val alt = asin(sin(phi) * sin(dec) + cos(phi) * cos(dec) * cos(h)) / DEG
        val parallax = asin(EARTH_RADIUS_KM / moon.distKm) / DEG
        val semi = asin(MOON_RADIUS_KM / moon.distKm) / DEG
        return alt - (parallax - REFRACTION_DEG - semi)
    }

    private fun cross(t0: Long, h0: Double, t1: Long, h1: Double): Long =
        t0 + ((t1 - t0) * (h0 / (h0 - h1))).toLong()
}

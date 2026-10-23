package com.kevin.legion.navigation

import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sin

/** The nav map's setting (mapbox-plus ticket 01, ruling 2): follow the sun, or pin one look. */
enum class MapStyleMode(val label: String) {
    AUTO("Auto"),
    DAY("Day"),
    NIGHT("Night"),
    ;

    /** Auto, then Day, then Night, then round again: one tap target on the nav screen. */
    fun next(): MapStyleMode = entries[(ordinal + 1) % entries.size]
}

/**
 * The look the map draws in. [preset] is the Mapbox Standard style's `lightPreset` value. Dawn and
 * dusk exist in the style, but the ruling is day from sunrise to sunset and night otherwise, so only
 * these two are used.
 */
enum class MapLight(val preset: String) { DAY("day"), NIGHT("night") }

/** Where the sun is relative to the horizon on one solar day; see [SunTimes.solarDay]. */
sealed interface SolarDay {
    /** The sun rises at [riseMs] and sets at [setMs] (epoch millis, riseMs < setMs). */
    data class Normal(val riseMs: Long, val setMs: Long) : SolarDay

    /** Polar day: the sun does not set on this solar day. */
    data object AlwaysUp : SolarDay

    /** Polar night: the sun does not rise on this solar day. */
    data object AlwaysDown : SolarDay
}

/**
 * Sunrise and sunset from latitude, longitude and a moment, with no network and no library (the
 * sunrise equation, accurate to a few minutes, which is all a map style needs). Pure, so the cases
 * that bite (across midnight, the date line, polar day and night) are unit-tested.
 */
object SunTimes {
    private const val J2000 = 2_451_545.0
    private const val UNIX_EPOCH_JD = 2_440_587.5
    private const val MS_PER_DAY = 86_400_000.0
    private const val DEG = PI / 180.0
    private const val OBLIQUITY_DEG = 23.4397
    private const val SUN_ALTITUDE_DEG = -0.833
    private const val DEG_PER_CIRCLE = 360.0
    private const val HALF_CIRCLE_DEG = 180.0
    private const val ANOMALY_BASE_DEG = 357.5291
    private const val ANOMALY_RATE_DEG = 0.98560028
    private const val CENTER_1 = 1.9148
    private const val CENTER_2 = 0.02
    private const val CENTER_3 = 0.0003
    private const val PERIHELION_DEG = 102.9372
    private const val TRANSIT_A = 0.0053
    private const val TRANSIT_B = 0.0069

    /** Multipliers of the second and third harmonic terms in the equation of the centre. */
    private const val SECOND = 2
    private const val THIRD = 3

    /**
     * The solar day (noon to noon, centred on the local solar noon nearest [nowMs]) that contains
     * [nowMs]. Centring on noon means sunrise and sunset of one solar day never straddle a date
     * boundary, which is what makes "across midnight" a non-event.
     */
    fun solarDay(nowMs: Long, latitudeDeg: Double, longitudeDeg: Double): SolarDay {
        val julianNow = nowMs / MS_PER_DAY + UNIX_EPOCH_JD
        val dayNumber = (julianNow - J2000 + longitudeDeg / DEG_PER_CIRCLE).roundToLong().toDouble()
        val approxNoon = dayNumber - longitudeDeg / DEG_PER_CIRCLE
        val meanAnomaly = (ANOMALY_BASE_DEG + ANOMALY_RATE_DEG * approxNoon).mod(DEG_PER_CIRCLE)
        val m = meanAnomaly * DEG
        val center = CENTER_1 * sin(m) + CENTER_2 * sin(SECOND * m) + CENTER_3 * sin(THIRD * m)
        val eclipticLongitude =
            (meanAnomaly + center + HALF_CIRCLE_DEG + PERIHELION_DEG).mod(DEG_PER_CIRCLE) * DEG
        val transit = J2000 + approxNoon + TRANSIT_A * sin(m) - TRANSIT_B * sin(SECOND * eclipticLongitude)
        val sinDec = sin(eclipticLongitude) * sin(OBLIQUITY_DEG * DEG)
        val cosDec = cos(asin(sinDec))
        val lat = latitudeDeg * DEG
        val cosHour = (sin(SUN_ALTITUDE_DEG * DEG) - sin(lat) * sinDec) / (cos(lat) * cosDec)
        return when {
            cosHour > 1.0 -> SolarDay.AlwaysDown
            cosHour < -1.0 -> SolarDay.AlwaysUp
            else -> {
                val hourAngleDays = acos(cosHour) / DEG / DEG_PER_CIRCLE
                SolarDay.Normal(toMs(transit - hourAngleDays), toMs(transit + hourAngleDays))
            }
        }
    }

    private fun toMs(julian: Double): Long = ((julian - UNIX_EPOCH_JD) * MS_PER_DAY).roundToLong()
}

/**
 * Which look the nav map draws in (mapbox-plus ticket 01, rulings 1 and 2). Auto follows the sun at
 * the current fix; with no fix it follows the system dark theme, and says so nowhere (it is a style,
 * not a fact). Day and Night pin the look whatever the clock says.
 */
object MapLightPicker {
    fun choose(
        mode: MapStyleMode,
        nowMs: Long,
        latitudeDeg: Double?,
        longitudeDeg: Double?,
        systemDark: Boolean,
    ): MapLight = when (mode) {
        MapStyleMode.DAY -> MapLight.DAY
        MapStyleMode.NIGHT -> MapLight.NIGHT
        MapStyleMode.AUTO ->
            if (latitudeDeg == null || longitudeDeg == null) {
                if (systemDark) MapLight.NIGHT else MapLight.DAY
            } else {
                when (val day = SunTimes.solarDay(nowMs, latitudeDeg, longitudeDeg)) {
                    SolarDay.AlwaysUp -> MapLight.DAY
                    SolarDay.AlwaysDown -> MapLight.NIGHT
                    is SolarDay.Normal -> if (nowMs in day.riseMs..day.setMs) MapLight.DAY else MapLight.NIGHT
                }
            }
    }
}

package com.kevin.legion.field

import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToInt

/** GPS state as the screen shows it. */
enum class GpsStatus { NO_PERMISSION, OFF, SEARCHING, FIX }

/** The compass, ready to draw. True figures are null until a GPS fix gives a declination. */
data class HeadingUi(
    val magneticDeg: Double,
    val trueDeg: Double?,
    /** Where the phone points, as a 16-point compass word (true when known, else magnetic). */
    val point: String,
    val backAzimuthMagDeg: Double,
    val backAzimuthTrueDeg: Double?,
    /** Where true north is, relative to the phone's heading: 0 straight ahead, 90 to the right. */
    val northArrowDeg: Double?,
    /** True when held upright (sighting along the back of the phone), false when flat. */
    val upright: Boolean,
)

/** The position block. Times are epoch ms; [fixAgeMs] is how old the fix was when built. */
data class PositionUi(
    val lat: Double,
    val lon: Double,
    val latLonText: String,
    val utmText: String?,
    val mgrsText: String?,
    val altitudeM: Double?,
    val altitudeText: String,
    val accuracyM: Float?,
    val accuracyText: String,
    val fixTimeMs: Long,
    val fixAgeMs: Long,
)

/** One waypoint, measured from the current fix. [arrowDeg] is relative to the heading (null with
 * no compass reading); bearings are great-circle initial bearings; distance is straight line. */
data class WaypointUi(
    val label: String,
    val lat: Double,
    val lon: Double,
    val bearingTrueDeg: Double,
    val bearingMagDeg: Double?,
    val distanceM: Double,
    val distanceText: String,
    val arrowDeg: Double?,
    /** Still only on this phone; joins saved places when there is signal. */
    val pending: Boolean,
)

/** Sun times for today's solar day, epoch ms (null: not today at this latitude). */
data class SunUi(
    val sunriseMs: Long?,
    val sunsetMs: Long?,
    val civilDawnMs: Long?,
    val civilDuskMs: Long?,
    val nauticalDawnMs: Long?,
    val nauticalDuskMs: Long?,
    val polarNote: String?,
)

data class MoonUi(
    val riseMs: Long?,
    val setMs: Long?,
    val illuminationPct: Int,
    val phaseName: String,
    val waxing: Boolean,
)

data class TurnaroundUi(val atMs: Long?, val marginMin: Int, val status: TurnaroundStatus)

/**
 * The Field screen's whole state (round 5). Data only, so the Typeset restyle (`feat/typeset-ui`)
 * swaps the composables and keeps this. Everything here is computed on the phone with no network.
 */
data class FieldUiState(
    val heading: HeadingUi? = null,
    val compassSource: CompassSource = CompassSource.NONE,
    val compassAccuracy: CompassAccuracy = CompassAccuracy.MEDIUM,
    /** Show the figure-8 prompt: the magnetometer reports low or unreliable accuracy. */
    val needsCalibration: Boolean = false,
    /** Magnetic declination at the fix, east positive (Android's World Magnetic Model). */
    val declinationDeg: Double? = null,
    val declinationText: String? = null,
    val gps: GpsStatus = GpsStatus.SEARCHING,
    val position: PositionUi? = null,
    val waypoints: List<WaypointUi> = emptyList(),
    val sun: SunUi? = null,
    val moon: MoonUi? = null,
    val turnaround: TurnaroundUi? = null,
    val turnaroundMarginMin: Int = DEFAULT_MARGIN_MIN,
    /** The last mark's sentence, or a problem in words. */
    val message: String? = null,
    val markBusy: Boolean = false,
) {
    companion object {
        const val DEFAULT_MARGIN_MIN = 60
    }
}

/** A GPS fix as plain numbers, so the state builds in a JVM test. */
data class FixInput(
    val lat: Double,
    val lon: Double,
    val altitudeM: Double?,
    val accuracyM: Float?,
    val timeMs: Long,
)

/**
 * Builds [FieldUiState] from the sensors' latest values ([FieldVoice] words it). Pure:
 * declination is passed in as a function so a test can fix it, and the phone passes
 * [Declination.at].
 */
object FieldReadout {

    @Suppress("LongParameterList") // one input per sensor; a holder class would only rename them
    fun build(
        compass: CompassReading?,
        compassSource: CompassSource,
        gps: GpsStatus,
        fix: FixInput?,
        waypoints: List<Waypoint>,
        nowMs: Long,
        zone: ZoneId,
        marginMin: Int,
        declination: (Double, Double, Double, Long) -> Double,
    ): FieldUiState {
        val decl = fix?.let { declination(it.lat, it.lon, it.altitudeM ?: 0.0, it.timeMs) }
        val accuracy = compass?.accuracy
            ?: if (compassSource == CompassSource.NONE) CompassAccuracy.NO_SENSOR else CompassAccuracy.MEDIUM
        val heading = compass?.let { headingOf(it, decl) }
        val position = fix?.let { positionOf(it, nowMs) }
        val rows = if (fix == null) {
            emptyList()
        } else {
            waypoints.map { w -> waypointOf(w, fix, decl, heading) }.sortedBy { it.distanceM }
        }
        val sky = fix?.let { skyOf(it, nowMs, zone, marginMin) }
        return FieldUiState(
            heading = heading,
            compassSource = compassSource,
            compassAccuracy = accuracy,
            needsCalibration = accuracy.needsCalibration,
            declinationDeg = decl,
            declinationText = decl?.let { FieldWords.declinationText(it) },
            gps = if (fix != null) GpsStatus.FIX else gps,
            position = position,
            waypoints = rows,
            sun = sky?.sun,
            moon = sky?.moon,
            turnaround = sky?.turnaround,
            turnaroundMarginMin = marginMin,
        )
    }

    private fun headingOf(c: CompassReading, decl: Double?): HeadingUi {
        val trueDeg = decl?.let { FieldMath.trueFromMagnetic(c.magneticDeg, it) }
        return HeadingUi(
            magneticDeg = c.magneticDeg,
            trueDeg = trueDeg,
            point = FieldMath.point16(trueDeg ?: c.magneticDeg),
            backAzimuthMagDeg = FieldMath.backAzimuth(c.magneticDeg),
            backAzimuthTrueDeg = trueDeg?.let { FieldMath.backAzimuth(it) },
            northArrowDeg = trueDeg?.let { FieldMath.arrowDeg(0.0, it) },
            upright = c.upright,
        )
    }

    private data class Sky(val sun: SunUi, val moon: MoonUi, val turnaround: TurnaroundUi)

    private fun skyOf(f: FixInput, nowMs: Long, zone: ZoneId, marginMin: Int): Sky {
        val d = Almanac.sunDay(nowMs, f.lat, f.lon)
        val sun = SunUi(d.sunrise, d.sunset, d.civilDawn, d.civilDusk, d.nauticalDawn, d.nauticalDusk, d.polarNote)
        val dayStart = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate().atStartOfDay(zone)
            .toInstant().toEpochMilli()
        val m = Almanac.moonDay(nowMs, dayStart, f.lat, f.lon)
        val moon = MoonUi(m.rise, m.set, (m.illumination * PERCENT).roundToInt(), m.phaseName, m.waxing)
        val t = Almanac.turnaround(nowMs, d.sunset, marginMin)
        return Sky(sun, moon, TurnaroundUi(t.atMs, t.marginMin, t.status))
    }

    private const val PERCENT = 100

    private fun positionOf(f: FixInput, nowMs: Long): PositionUi {
        val utm = Grid.utm(f.lat, f.lon)
        return PositionUi(
            lat = f.lat,
            lon = f.lon,
            latLonText = FieldWords.latLonText(f.lat, f.lon),
            utmText = utm?.text(),
            mgrsText = Grid.mgrs(f.lat, f.lon),
            altitudeM = f.altitudeM,
            altitudeText = FieldWords.altitudeText(f.altitudeM),
            accuracyM = f.accuracyM,
            accuracyText = FieldWords.accuracyWords(f.accuracyM),
            fixTimeMs = f.timeMs,
            fixAgeMs = (nowMs - f.timeMs).coerceAtLeast(0L),
        )
    }

    private fun waypointOf(w: Waypoint, fix: FixInput, decl: Double?, heading: HeadingUi?): WaypointUi {
        val bearing = FieldMath.bearingDeg(fix.lat, fix.lon, w.lat, w.lon)
        val distance = FieldMath.distanceM(fix.lat, fix.lon, w.lat, w.lon)
        val headingTrue = heading?.trueDeg
        val arrow = when {
            headingTrue != null -> FieldMath.arrowDeg(bearing, headingTrue)
            heading != null && decl == null -> FieldMath.arrowDeg(bearing, heading.magneticDeg)
            else -> null
        }
        return WaypointUi(
            label = w.label,
            lat = w.lat,
            lon = w.lon,
            bearingTrueDeg = bearing,
            bearingMagDeg = decl?.let { FieldMath.magneticFromTrue(bearing, it) },
            distanceM = distance,
            distanceText = FieldWords.distanceText(distance),
            arrowDeg = arrow,
            pending = w.pending,
        )
    }
}

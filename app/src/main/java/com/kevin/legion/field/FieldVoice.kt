package com.kevin.legion.field

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/**
 * [FieldUiState] in words: the screen's clock and age lines, and the field readout spoken through
 * `get_current_location` (round 5 voice path, `service/FieldToolbox.kt`). Every figure is the
 * phone's own; with no fix or no compass it says so rather than guessing. The voice path takes the
 * app's usual fix (`LocationController`, which may be fused); the Field screen is GPS only.
 */
object FieldVoice {
    private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
    private const val SECOND_MS = 1_000L
    private const val MINUTE_MS = 60_000L
    private const val HOUR_MS = 3_600_000L
    private const val AHEAD_DEG = 5
    private const val BEHIND_FROM = 175
    private const val BEHIND_TO = 185
    private const val HALF_TURN = 180
    private const val FULL_TURN = 360

    fun clock(ms: Long?, zone: ZoneId): String =
        ms?.let { CLOCK.format(Instant.ofEpochMilli(it).atZone(zone)) } ?: "none today"

    fun ageText(ms: Long): String = when {
        ms < MINUTE_MS -> "${ms / SECOND_MS} s old"
        ms < HOUR_MS -> "${ms / MINUTE_MS} min old"
        else -> "${ms / HOUR_MS} h old: an old fix, not where you are now"
    }

    /** Which way north is, in words a person turns by: "straight ahead", "40 deg to your left". */
    fun northWords(arrowDeg: Double): String {
        val a = arrowDeg.roundToInt().mod(FULL_TURN)
        return when {
            a <= AHEAD_DEG || a >= FULL_TURN - AHEAD_DEG -> "straight ahead"
            a in BEHIND_FROM..BEHIND_TO -> "behind you"
            a < HALF_TURN -> "$a° to your right"
            else -> "${FULL_TURN - a}° to your left"
        }
    }

    /** The whole spoken readout. */
    fun voiceText(state: FieldUiState, zone: ZoneId, maxWaypoints: Int = 5): String = listOf(
        positionWords(state),
        compassWords(state),
        skyWords(state, zone),
        waypointWords(state, maxWaypoints),
        "Heading, grid, sun and moon are worked out on the phone, no network.",
    ).filter { it.isNotBlank() }.joinToString(" ")

    private fun positionWords(state: FieldUiState): String {
        val p = state.position ?: return "Field: no GPS fix, so no grid, sun or waypoint figures."
        val grid = p.mgrsText?.let { "MGRS $it" } ?: "MGRS not available at this latitude"
        return "Grid: $grid. Altitude ${p.altitudeText}. GPS ${p.accuracyText}, ${ageText(p.fixAgeMs)}."
    }

    private fun compassWords(state: FieldUiState): String = buildString {
        val h = state.heading
        val trueDeg = h?.trueDeg
        when {
            h == null -> append("Compass: no reading.")
            trueDeg != null -> append(
                "Compass: the phone's ${if (h.upright) "back" else "top"} points " +
                    "${FieldWords.degText(trueDeg)}° true (${h.point}), " +
                    "${FieldWords.degText(h.magneticDeg)}° magnetic; true north is " +
                    "${northWords(h.northArrowDeg ?: 0.0)}. Back azimuth " +
                    "${FieldWords.degText(h.backAzimuthTrueDeg ?: 0.0)}° true.",
            )
            else -> append(
                "Compass: ${FieldWords.degText(h.magneticDeg)}° magnetic (${h.point}); true north " +
                    "unknown without a GPS fix.",
            )
        }
        if (state.needsCalibration) {
            append(" Compass accuracy is ${state.compassAccuracy.words}: move the phone in a figure 8.")
        }
        state.declinationText?.let { append(" Declination $it.") }
    }

    private fun skyWords(state: FieldUiState, zone: ZoneId): String = buildString {
        state.sun?.let { s ->
            s.polarNote?.let { append("$it ") }
            append("Sun: sunrise ${clock(s.sunriseMs, zone)}, sunset ${clock(s.sunsetMs, zone)}, ")
            append("civil dusk ${clock(s.civilDuskMs, zone)}, nautical dusk ${clock(s.nauticalDuskMs, zone)}.")
        }
        state.turnaround?.let { t -> append(turnaroundWords(t, zone)) }
        state.moon?.let { m ->
            append(" Moon: ${m.phaseName}, ${m.illuminationPct}% lit, rises ${clock(m.riseMs, zone)}, ")
            append("sets ${clock(m.setMs, zone)}.")
        }
    }

    private fun turnaroundWords(t: TurnaroundUi, zone: ZoneId): String = when (t.status) {
        TurnaroundStatus.NO_SUNSET -> ""
        TurnaroundStatus.AFTER_SUNSET -> " The sun has set."
        TurnaroundStatus.PAST_TURNAROUND ->
            " Turnaround ${clock(t.atMs, zone)} (sunset less ${t.marginMin} min), already passed."
        TurnaroundStatus.AHEAD -> " Turnaround ${clock(t.atMs, zone)} (sunset less ${t.marginMin} min)."
    }

    private fun waypointWords(state: FieldUiState, maxWaypoints: Int): String {
        if (state.waypoints.isEmpty()) return ""
        val rows = state.waypoints.take(maxWaypoints).joinToString("; ") { w ->
            val magnetic = w.bearingMagDeg?.let { " (${FieldWords.degText(it)}° magnetic)" } ?: ""
            val pending = if (w.pending) ", not synced yet" else ""
            "${w.label} ${w.distanceText}, bearing ${FieldWords.degText(w.bearingTrueDeg)}° true$magnetic, " +
                FieldMath.word8(w.bearingTrueDeg) + pending
        }
        return "Saved places by straight-line distance: $rows."
    }
}

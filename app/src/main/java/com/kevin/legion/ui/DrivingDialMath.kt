package com.kevin.legion.ui

import com.kevin.legion.data.local.Drive
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.round

/**
 * The pure layer under [DrivingModeScreen]'s cockpit instruments (drive-ui ticket 04 rebuild,
 * direction approved by Kevin 2026-08-16 off a reference dashboard photo - "look at the bars and
 * shit. retro futuristic vibes. think akira, think evangelion"). No Compose import in this file,
 * on purpose, matching [com.kevin.legion.ui.common.DeckChartData.kt]'s split between "pure" and
 * "Canvas" - segment counts, redline placement, scale ticks, and unit conversions below are plain
 * functions of already-fetched readings, unit-testable in `DrivingDialMathTest` with no
 * Robolectric and no Canvas.
 *
 * **The arc dial is GONE** (ticket 04 answer Q8/Q11): it spent 260dp on one number and does not
 * graduate to a shared primitive because nothing else ever called it and it is being replaced.
 * The instrument form now is a **segmented column** - discrete blocks, not a swept needle - for
 * both RPM and speed, plus a **fader** (a continuous filled track) for coolant. A segmented
 * display is discrete by construction, which is why it stops needing to apologise for the ~2 Hz
 * bus ceiling ([research/01-bus-reality.md]): a column of blocks cannot imply a value between two
 * readings the way a swept needle can.
 *
 * **Redline is a scale marking, not a state signal**, carried over unchanged from the arc-dial
 * era. [REDLINE_START_FRACTION] paints a fixed band near the top of the scale's own 0..max range,
 * the same way a physical tach has its redline printed on the glass regardless of what the needle
 * (now: the segment stack) is doing right now - it does not change position or presence based on
 * the current reading. Ticket 03's "amber = data, green = good, red = needs you" contract (see
 * [com.kevin.legion.ui.theme.DeckChrome]'s own doc, and [com.kevin.legion.ui.common.DeckTagStyle]'s
 * enum-shape guard against a red STATE tag) governs a VERDICT on a value: quarantined, crisis-tier,
 * failed-gate. A gauge's printed scale is not a verdict on anything the app observed this drive;
 * it is annotation baked into the instrument face, unconditionally present whether the reading is
 * at 1000 RPM or 5200. That is why [DrivingModeScreen] draws the redline SEGMENTS with the raw
 * [com.kevin.legion.ui.theme.DeckChrome] token directly rather than routing through
 * [com.kevin.legion.ui.theme.LocalLegionSemantics.quarantined] - that semantic role means "a
 * document that failed the gate", and painting it onto an unrelated meaning (a static scale
 * marking) would be the wrong kind of reuse, not the right one.
 */

// --------------------------------------------------------------- scale fraction

/**
 * Clamps [value] into `[0, scaleMax]` and expresses it as the 0f..1f fraction of the instrument's
 * full range that value fills - the shared input to both the segment count below and a fader's
 * fill width. A non-positive [scaleMax] is a caller bug (every real scale constant in
 * [DrivingModeScreen] is positive) rather than a value ever seen from a PID reading, so it floors
 * to `0f` rather than dividing by zero or a negative span.
 */
fun dialFraction(value: Float, scaleMax: Float): Float {
    if (scaleMax <= 0f) return 0f
    return (value / scaleMax).coerceIn(0f, 1f)
}

// ------------------------------------------------------------------- segments

/**
 * How many of a segmented column's [totalSegments] blocks are lit for a given fill [fraction].
 * Rounds to the nearest whole segment rather than flooring, so a reading that has just crossed a
 * segment boundary lights it rather than waiting for the next whole unit - the same rounding a
 * physical LED bargraph's comparator ladder does. [fraction] is expected to already be clamped
 * (see [dialFraction]) but is re-clamped defensively here too, since this is the function motion
 * calls every animated frame: an out-of-range input must never desync the lit count from the
 * segment array's bounds.
 */
fun litSegmentCount(fraction: Float, totalSegments: Int): Int {
    if (totalSegments <= 0) return 0
    val clamped = fraction.coerceIn(0f, 1f)
    return round(clamped * totalSegments).toInt().coerceIn(0, totalSegments)
}

/**
 * The 0-based index of the first segment (counting from the bottom, segment 0) that falls inside
 * the redline band. `ceil`, not `round` or `floor`: the redline is a printed MINIMUM guarantee -
 * "the top ~15% of the scale is redline" must never round away to a smaller zone than promised,
 * so a fractional segment boundary always rounds the zone's start DOWN the index list (i.e. the
 * zone grows to cover it) rather than up. A caller compares a segment's own index against this
 * with [isRedlineSegment].
 */
fun redlineSegmentStartIndex(totalSegments: Int, redlineStartFraction: Float = REDLINE_START_FRACTION): Int {
    if (totalSegments <= 0) return 0
    return ceil(redlineStartFraction * totalSegments).toInt().coerceIn(0, totalSegments)
}

/** Whether segment [index] (0-based, from the bottom) sits inside the redline band that starts at [redlineStartIndex]. */
fun isRedlineSegment(index: Int, redlineStartIndex: Int): Boolean = index >= redlineStartIndex

/**
 * The redline's start, as a fraction of the scale (unchanged from the arc-dial era) - the top
 * ~15% of the instrument's own printed range. See the file doc for why this is a fixed scale
 * annotation, never a function of the current reading.
 */
const val REDLINE_START_FRACTION = 0.85f

// ------------------------------------------------------------------------ scale ticks

/**
 * Evenly spaced tick values from `0` to [scaleMax] (inclusive of both ends when [step] divides
 * [scaleMax] cleanly), each paired with its own position as a `0f..1f` fraction of the scale -
 * exactly what a caller draws as the printed grid behind a segment column or fader (ticket 04:
 * "scale ticks drawn BEHIND the readout on a grid, not as a separate axis strip"). Built by
 * multiplying an integer step count rather than repeatedly adding [step] to a running float, so
 * floating-point drift can never push the last tick just past (or just short of) [scaleMax] -
 * `scaleTicks(120f, 40f)` must return exactly four ticks, not three or five depending on rounding.
 * A non-positive [scaleMax] or [step] returns an empty list rather than looping forever or
 * dividing by zero.
 */
fun scaleTicks(scaleMax: Float, step: Float): List<Pair<Float, Float>> {
    if (scaleMax <= 0f || step <= 0f) return emptyList()
    val lastStepIndex = floor(scaleMax / step + 1e-4f).toInt()
    return (0..lastStepIndex).map { i ->
        val value = i * step
        (value / scaleMax) to value
    }
}

// -------------------------------------------------------------- conversions

/**
 * km/h -> mph, the same 0.621371 factor already used at
 * [com.kevin.legion.vehicle.CarToolbelt]'s own speed-to-mph conversion for the voice tool surface.
 * Pulled out as its own pure function here (rather than inlining the literal a second time) so
 * `DrivingDialMathTest` pins the exact factor once and the speed column's math reads it by name.
 * Speed stays imperial (ticket 07 answer #3) even though coolant moved to Celsius - a mixed
 * system is deliberate, matching the odometer, DRIVES, and a US driver.
 */
fun kmhToMph(kmh: Float): Float = kmh * 0.621371f

// ------------------------------------------------------------------ trip block (last drive)

/**
 * [DrivingModeScreen]'s trip block (layout ticket 08 Q26, trip content ticket 05 Q13) only ever
 * has a FINISHED drive to read - `drives` gets a row from
 * [com.kevin.legion.vehicle.TelemetryRecorder.finalizeDrive] on the way OUT of a drive, never on
 * the way in, so there is no in-progress row to poll toward. [lastDriveSummary] is the pure
 * "what does the trip block say" decision for that one finished row (or its absence): `null` in,
 * `null` out - a fresh install or a first drive still underway has nothing to show, and the caller
 * renders a worded empty state rather than this function inventing a placeholder pair.
 *
 * **Never touches [Drive.gallons]**, including when it is `null` - mpg is withheld
 * ([com.kevin.legion.vehicle.MpgTrust.SHOW_MPG] is `false`, ticket 09's ~1.7x integration error),
 * so gallons plays no part in what this screen prints regardless of whether it was ever measured.
 */
internal data class TripSummary(val elapsedText: String, val distanceText: String)

/** See [TripSummary]'s own doc - the null-in/null-out decision for the trip block. */
internal fun lastDriveSummary(drive: Drive?): TripSummary? {
    if (drive == null) return null
    return TripSummary(
        elapsedText = formatElapsedMinutes(drive.startedAt, drive.endedAt),
        distanceText = formatTripMiles(drive.miles),
    )
}

/**
 * A finished drive's [startedAtMs]..[endedAtMs] span (both epoch ms, [Drive]'s own fields), as
 * "N MIN" under an hour or "HH MM" (hour digit, then zero-padded minutes) at or past one - short
 * enough to stay legible in [TripStat]'s tile at the 11sp value size a bracket panel that width can
 * actually hold. A negative span (defensive only - [endedAtMs] should never precede [startedAtMs])
 * clamps to zero rather than printing a negative duration.
 */
internal fun formatElapsedMinutes(startedAtMs: Long, endedAtMs: Long): String {
    val totalMinutes = ((endedAtMs - startedAtMs) / 60_000L).coerceAtLeast(0L)
    return if (totalMinutes >= 60L) {
        "%dH %02dM".format(totalMinutes / 60L, totalMinutes % 60L)
    } else {
        "%d MIN".format(totalMinutes)
    }
}

/** A finished drive's [Drive.miles], to one decimal place, matching this screen's imperial-distance convention (ticket 07 answer #3 - speed stays imperial, and so does this). */
internal fun formatTripMiles(miles: Double): String = "%.1f MI".format(miles)

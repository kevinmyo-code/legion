package com.kevin.legion.ui

/**
 * The pure layer under [DrivingModeScreen]'s cockpit dial (cyberdeck-ui ticket 20 rebuild,
 * mock approved by Kevin 2026-08-08). No Compose import in this file, on purpose, matching
 * [com.kevin.legion.ui.common.DeckChartData.kt]'s split between "pure" and "Canvas" - the dial
 * source selection, scale clamping, and unit conversions below are plain functions of already-
 * fetched readings, unit-testable in `DrivingDialMathTest` with no Robolectric and no Canvas.
 *
 * **Redline is a scale marking, not a state signal.** [REDLINE_START_FRACTION] paints a fixed
 * band near the top of the DIAL'S OWN 0..max SCALE, the same way a physical tach has its redline
 * printed on the glass regardless of what the needle is doing right now - it does not change
 * colour, position, or presence based on the current reading. Ticket 03's "amber = data, green =
 * good, red = needs you" contract (see [com.kevin.legion.ui.theme.DeckChrome]'s own doc, and
 * [com.kevin.legion.ui.common.DeckTagStyle]'s enum-shape guard against a red STATE tag) governs a
 * VERDICT on a value: quarantined, crisis-tier, failed-gate. A gauge's printed scale is not a
 * verdict on anything the app observed this drive; it is annotation baked into the instrument
 * face, unconditionally present whether the needle is at 1000 RPM or 7500. That is why
 * [DrivingModeScreen] draws it with the raw [com.kevin.legion.ui.theme.DeckChrome] token directly
 * rather than routing it through [com.kevin.legion.ui.theme.LocalLegionSemantics.quarantined] -
 * that semantic role means "a document that failed the gate", and painting it onto an unrelated
 * meaning (a static scale marking) would be the wrong kind of reuse, not the right one.
 */

// --------------------------------------------------------------- dial source

/**
 * Which reading the center dial shows. [RPM] leads by default (ticket 11 answer §3); [SPEED] is
 * RPM's fallback for an install that has never recorded 010C; [NONE] means neither PID has ever
 * been logged for this vehicle, and the dial draws a dead track with "NO LINK" center text.
 */
enum class DialSource { RPM, SPEED, NONE }

/**
 * Picks [DialSource] from whether this vehicle has EVER recorded each PID (`hasRpm`/`hasSpeed`),
 * not from whether the OBD link is live right now - a stale last-known RPM still counts as
 * "has RPM" and drives the dial (muted, per [DrivingModeScreen]'s stale-rendering rule), because
 * the alternative (falling back to speed the moment the link drops) would make the dial's chosen
 * readout flicker between RPM and speed on every disconnect, which is exactly the kind of motion
 * ticket 04 answer #5's "no theatre" rules out for this screen.
 */
fun selectDialSource(hasRpm: Boolean, hasSpeed: Boolean): DialSource = when {
    hasRpm -> DialSource.RPM
    hasSpeed -> DialSource.SPEED
    else -> DialSource.NONE
}

// ------------------------------------------------------------------- scale

/**
 * Clamps [value] into `[0, scaleMax]` and expresses it as the 0f..1f fraction of the dial's
 * sweep (or a pod's mini-bar) that value fills. A non-positive [scaleMax] is a caller bug (every
 * real scale constant in [DrivingModeScreen] is positive) rather than a value ever seen from a
 * PID reading, so it floors to `0f` rather than dividing by zero or a negative span.
 */
fun dialFraction(value: Float, scaleMax: Float): Float {
    if (scaleMax <= 0f) return 0f
    return (value / scaleMax).coerceIn(0f, 1f)
}

/** The dial's fixed 270-degree sweep (approved mock), open at the bottom - see [DIAL_START_ANGLE_DEG]'s doc for the geometry that produces the opening. */
const val DIAL_SWEEP_DEG = 270f

/**
 * The sweep's start angle in [androidx.compose.ui.graphics.drawscope.DrawScope.drawArc]'s own
 * convention (0 degrees = 3 o'clock, sweeping clockwise as the angle increases, since Canvas y
 * grows downward). Starting at 135 degrees and sweeping 270 degrees clockwise ends at 45 degrees,
 * which leaves the missing 90-degree slice centered on 90 degrees - Canvas's 6-o'clock, i.e. the
 * bottom of the circle. That is the whole mechanism behind "open at the bottom" in the approved
 * mock; no separate "gap" parameter exists because the gap is just whatever the sweep doesn't
 * cover.
 */
const val DIAL_START_ANGLE_DEG = 135f

/**
 * The redline's start, as a fraction of the SWEEP (equivalently, of the scale, since the sweep
 * maps linearly onto `0..scaleMax`) - the top ~15% of the dial's own printed range. See the file
 * doc for why this is a fixed scale annotation, never a function of the current reading.
 */
const val REDLINE_START_FRACTION = 0.85f

/** The sweep angle (degrees) that fills the value arc for a given fraction, clamped defensively - a caller that passed an out-of-range fraction (should never happen; [dialFraction] already clamps) still gets a legal arc rather than a Canvas exception. */
fun sweepAngleDegrees(fraction: Float): Float = DIAL_SWEEP_DEG * fraction.coerceIn(0f, 1f)

// -------------------------------------------------------------- conversions

/**
 * km/h -> mph, the same 0.621371 factor already used at
 * [com.kevin.legion.vehicle.CarToolbelt]'s own speed-to-mph conversion for the voice tool surface.
 * Pulled out as its own pure function here (rather than inlining the literal a second time) so
 * `DrivingDialMathTest` pins the exact factor once and the dial/pod math reads it by name.
 */
fun kmhToMph(kmh: Float): Float = kmh * 0.621371f

/**
 * Celsius -> Fahrenheit, the same `c * 9 / 5 + 32` formula already used at
 * [com.kevin.legion.service.AriaForegroundService] and [com.kevin.legion.service.LiveToolbox] for
 * the same reading. OBD PID 0105 (coolant temperature) reports Celsius; the approved mock's
 * COOLANT pod is Fahrenheit, so this is the one conversion between the DB's stored unit and the
 * pod's displayed unit.
 */
fun celsiusToFahrenheit(celsius: Float): Float = celsius * 9f / 5f + 32f

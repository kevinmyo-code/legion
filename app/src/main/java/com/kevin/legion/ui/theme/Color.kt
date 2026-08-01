package com.kevin.legion.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * LEGION's palette. Direction "Instrument" (wayfinder ticket 02, decided
 * 2026-08-01): the assistant as a readout. Numbers are the hero, chrome gets
 * out of the way, and colour means state rather than decoration.
 *
 * These are raw values. Nothing outside [LegionTheme] should reference them
 * directly - read them through `MaterialTheme.colorScheme` or
 * [LocalLegionSemantics] so a future retheme has one place to change.
 *
 * Deliberately NOT city-pop. Midnight AI's amber-phosphor-on-black night
 * palette is retired along with the rest of that design language, and the
 * signal colour here is cool specifically so nothing reads as a callback to it.
 */

// ---- Instrument, dark (the committed default) ----------------------------

/** Page ground. Near-black with a slight cool bias, not pure #000. */
val InstrumentGround = Color(0xFF0B0D0E)

/** Raised surface: cards, sheets, the body of a list. */
val InstrumentSurface = Color(0xFF141719)

/** Recessed surface: section headers, account bars, the bar above a list. */
val InstrumentSurfaceSunken = Color(0xFF101416)

/** Structural hairline. Section boundaries, top-level rules. */
val InstrumentRule = Color(0xFF23292C)

/** Softer hairline for repeating row separators, so long lists do not stripe. */
val InstrumentRuleFaint = Color(0xFF191E21)

/** Primary reading text. */
val InstrumentInk = Color(0xFFE6E9EA)

/** Secondary text: descriptions beside a number that is the real content. */
val InstrumentInkDim = Color(0xFFC4CCD0)

/** Tertiary text: dates, units, provenance tags. Present but never competing. */
val InstrumentInkFaint = Color(0xFF7C8589)

/** Quaternary text: status bars, disabled states. */
val InstrumentInkGhost = Color(0xFF59636A)

/** The signal. Selection, focus, the active aspect. Used sparingly, on purpose. */
val InstrumentSignal = Color(0xFF4FA8C5)

/** Signal at rest, for large fills where full saturation would shout. */
val InstrumentSignalSunken = Color(0xFF16333D)

// ---- Instrument, light ---------------------------------------------------
// Instrument is a dark-committed direction, but a phone app that cannot render
// in daylight is broken rather than opinionated. These keep the same structure
// (hairlines, mono numerals, colour-means-state) on a light ground.

val InstrumentGroundLight = Color(0xFFFAFBFB)
val InstrumentSurfaceLight = Color(0xFFFFFFFF)
val InstrumentSurfaceSunkenLight = Color(0xFFF1F4F5)
val InstrumentRuleLight = Color(0xFFD4DADD)
val InstrumentRuleFaintLight = Color(0xFFE6EAEC)
val InstrumentInkLight = Color(0xFF0E1214)
val InstrumentInkDimLight = Color(0xFF2E3639)
val InstrumentInkFaintLight = Color(0xFF5C666B)
val InstrumentInkGhostLight = Color(0xFF8B959B)
val InstrumentSignalLight = Color(0xFF1D7A99)
val InstrumentSignalSunkenLight = Color(0xFFDCEDF3)

// ---- Semantic roles ------------------------------------------------------
// Material 3's ColorScheme has no vocabulary for money or for provenance, so
// these live in LegionSemantics instead of being forced into `tertiary` and
// friends. See LocalLegionSemantics for why that separation is deliberate.

/** A credit. The only money colour, because most rows are debits. */
val MoneyPositive = Color(0xFF5FBE8A)
val MoneyPositiveLight = Color(0xFF1E7A4F)

/**
 * A value the source document never stated: pantry macros, a cost projection.
 * CLAUDE.md §4 rule five requires these to read as estimates, and this colour
 * is half of how that rule is enforced in the UI (the label is the other half).
 */
val ValueEstimated = Color(0xFFC8A44D)
val ValueEstimatedLight = Color(0xFF8A6D1F)

/** A document that failed the reconciliation gate and was written nowhere. */
val StateQuarantined = Color(0xFFD9635C)
val StateQuarantinedLight = Color(0xFFB23A32)

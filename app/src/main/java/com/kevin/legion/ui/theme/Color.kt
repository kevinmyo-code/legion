package com.kevin.legion.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * LEGION's palette. Direction "VACUUM with a tinge of SENTRY" (mission-control
 * ticket 01, decided 2026-08-14, superseding MILSPEC - cyberdeck-ui ticket 01,
 * 2026-08-07, itself superseding the 2026-08-01 "Instrument" call recorded in
 * `memory/library/decisions.md`): pure-black OLED ground, mint data readouts,
 * red-orange chrome, amber highlights, ticket 09's four reference photos rather
 * than a single avionics-console read.
 *
 * These are raw values. Nothing outside [LegionTheme] should reference them
 * directly - read them through `MaterialTheme.colorScheme` or
 * [LocalLegionSemantics] so a future retheme has one place to change. That
 * mechanism is exactly why this retheme touches only this file and Theme.kt:
 * no screen file references a raw token below.
 *
 * The palette is now genuinely two-hue (mission-control ticket 01's 2026-08-14
 * revision, from ticket 06's `dataviz` validator run): mint is every value,
 * amber is every highlight, red-orange is chrome. Green is gone - see the
 * removed-token note below.
 */

// ---- VACUUM/SENTRY, dark (the only scheme - see Theme.kt's dark-only note) -

/**
 * Screen ground. Pure `#000000` - on an OLED phone that is genuinely unlit
 * pixels, the strongest possible starting point for daylight contrast (ticket
 * 01's "ground question", settled).
 */
val DeckGround = Color(0xFF000000)

/**
 * Pane fill. SENTRY's own ground (`#05070C`, a navy-black) demoted one tier so
 * it separates a pane from the void rather than being the whole screen - the
 * move that makes the VACUUM/SENTRY splice work (ticket 01).
 */
val DeckPanel = Color(0xFF05070C)

/** Primary reading text: merchant names, debit descriptions. Spliced neutral, pulled off full saturation so it reads as chosen rather than default grey (ticket 01). */
val DeckInk = Color(0xFFE4E9EF)

/** Labels, units, provenance. The tier daylight-contrast has to be checked FIRST (ticket 01 note, ticket 10 verifies on-device). */
val DeckFaint = Color(0xFF8E97A3)

/**
 * Timestamps, gaps, disabled. Its own raw token now, not an alpha of
 * [DeckFaint] - ticket 01's token table lists it as a distinct spliced value,
 * and [LegionSemantics.ghost] reads it directly rather than deriving it.
 */
val DeckGhost = Color(0xFF58606C)

/**
 * Pill outline, bezel ticks, alarm border, alarm fill. One of chrome's three
 * tiers (ticket 01 sub-answer #1) - reserved for pill outlines, bezel ticks and
 * alarm, NOT spent on every pane edge. Full-strength red on every pane outline
 * turns the screen into a grid of alarms; that is what [DeckChromeDim] is for.
 */
val DeckChrome = Color(0xFFFF5330)

/** Pill label, section-rule label. The brightest of the three chrome tiers, reserved for text riding on [DeckChrome] fills so it stays legible against them. */
val DeckChromeText = Color(0xFFFF8A6B)

/**
 * Bezel line, pane outline. The STRUCTURAL chrome tier (ticket 01 sub-answer
 * #1) - this is the one nearly every pane edge in the app actually uses.
 * [DeckChrome] full-strength is reserved for pills, ticks and alarms alone.
 */
val DeckChromeDim = Color(0xFF5A2317)

/** Section boundary. */
val DeckRule = Color(0xFF1E2530)

/** Row separator, meter track, chart gridline - the fainter of the two structural lines. */
val DeckRuleFaint = Color(0xFF141A22)

/**
 * Every value. Mint, pulled back off VACUUM's full saturation (ticket 01).
 * Ticket 01 sub-answer #2: there is no separate "dim mint" token - ticks, axes
 * and units read as [DeckFaint]/[DeckGhost], never as a diluted [DeckData],
 * because mint means "this is a value" and diluting it dilutes that claim.
 */
val DeckData = Color(0xFF57EFC6)

/**
 * Highlights, active nav key, target line, estimate tag. Ticket 03's contract
 * carries over unmodified: an estimate is still data, just unverified data, so
 * it sits at amber rather than inventing a fourth signal colour - the WORDED
 * label (CLAUDE.md §4 rule 5) carries "unverified", not the hue.
 */
val DeckAmber = Color(0xFFFFBA1F)

/**
 * Chart endpoint and typed markers. Distinct from [DeckAmber] by value, but
 * only just (ticket 01 sub-answer #3) - typed markers are meant to differ from
 * each other by SHAPE, not by hue; this is a nudge, not a second signal
 * colour. Carried forward to ticket 06 (chart-kit recolour) as a constraint.
 */
val DeckMarker = Color(0xFFFFD84A)

/**
 * The sunken surface a QUARANTINE state sits on - `errorContainer`. A distinct,
 * warm-shifted dark value rather than a reuse of [DeckPanel], for two reasons at
 * once: (1) the L11 `contentColorFor`-by-value mechanics documented on
 * [Theme.kt]'s `DarkScheme` - `errorContainer` must not collide with any other
 * container role or the wrong `on*` colour resolves silently - and (2) a
 * quarantined document should not look pixel-identical to an ordinary panel even
 * before you read the word QUARANTINED on it.
 */
val DeckPanelQuarantine = Color(0xFF170604)

// ---- Semantic roles --------------------------------------------------------
// Material 3's ColorScheme has no vocabulary for money or for provenance, so
// these live in LegionSemantics instead of being forced into `tertiary` and
// friends. See LocalLegionSemantics for why that separation is deliberate.
//
// Re-cut to the VACUUM/SENTRY palette, mission-control ticket 01 (2026-08-14,
// revised same day from ticket 06's validator run). The field NAMES on
// LegionSemantics are unchanged from the MILSPEC/Instrument era on purpose -
// every screen file reads them through LocalLegionSemantics.current.<name>, so
// keeping the names stable and only recolouring the values is what lets this
// retheme land without touching a single screen. See Theme.kt's
// LegionSemantics doc for the full per-field mapping.
//
// GREEN IS REMOVED. Ticket 06 ran the dataviz palette validator against
// ticket 01's original `good` #7BE86A: it fails separation against mint on
// normal vision (dE 10.4, floor 15) AND against amber under deuteranopia
// (dE 5.5, floor 8). Four alternative greens were tested; all fail both -
// green is geometrically squeezed between mint and amber with no value that
// clears either. There is no replacement hue: a credit is mint with a leading
// `+` and the word CREDIT, same value as an ordinary debit. MoneyPositive,
// ValueEstimated and StateQuarantined (the old standalone semantic-colour
// aliases) are dropped with it - LegionSemantics reads DeckData/DeckAmber/
// DeckChrome directly now, see Theme.kt.

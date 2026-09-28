package com.kevin.legion.ui.theme.soft

import androidx.compose.ui.graphics.Color

/**
 * The soft-Material palette (home-launcher ticket 02, ADR 0050, superseding VACUUM/SENTRY -
 * `ui/theme/Color.kt` - surface by surface, starting here: HOME, Lists and the shell chrome per
 * ticket 01's "how far the look reaches now"). Exact hex values are ticket 02's own token table,
 * lifted from the clickable prototype canvas (`research/prototype-canvas/Main.dc.html`) that ticket
 * 01 was resolved against - these are not a redesign of that prototype, they are its literal values.
 *
 * Referenced directly (`SoftColors.card`, not a `MaterialTheme.colorScheme` role) by every
 * composable this ticket restyles - unlike the old VACUUM/SENTRY convention
 * (`Color.kt`: "nothing outside LegionTheme should reference [DeckGround] and friends directly"),
 * this palette has no `LocalLegionSemantics`-equivalent CompositionLocal wrapping it. [SoftTheme]
 * separately maps these same values onto a real `ColorScheme` (`darkColorScheme`) so any stock M3
 * component drawn inside it - `Surface`, `IconButton`'s ripple, a default-coloured `Text` - still
 * resolves to a value from this same palette rather than M3's own baseline tones; the two paths
 * are meant to coexist, not compete.
 */
object SoftColors {
    /** Screen background. */
    val ground = Color(0xFF121317)

    /** Talk bar surface - one tier up from [ground], separating the bar from the screen behind it. */
    val barLow = Color(0xFF16181D)

    /** Hairline above the talk bar. */
    val barRule = Color(0xFF23262D)

    /** Tiles, cards. */
    val card = Color(0xFF1C1E24)

    /** Chips, inputs. */
    val cardHigh = Color(0xFF24272E)

    /** Menus, progress tracks. */
    val cardHighest = Color(0xFF2A2D34)

    /** Dashed "new" card, input borders. */
    val outline = Color(0xFF3A3E47)

    /** Primary text. */
    val text = Color(0xFFE6E7EC)

    /** Secondary text, statuses. */
    val text2 = Color(0xFFA9ADB8)

    /** Tertiary text: ticked text, placeholders. */
    val text3 = Color(0xFF8A8F9B)

    /** Accents. */
    val primary = Color(0xFFFFB4A1)

    /** Talk pill, FAB, primary buttons. */
    val primaryContainer = Color(0xFF733423)

    /** Text on [primaryContainer]. */
    val onPrimaryContainer = Color(0xFFFFDBD1)

    /** Over budget, overdue, destructive. */
    val alertContainer = Color(0xFF4A1F24)

    /** Text on [alertContainer]; also alert-coloured text sitting directly on [card]. */
    val onAlert = Color(0xFFFFB4AB)

    /** Estimates, refusals, not synced, mic blocked. */
    val caution = Color(0xFFFFC857)

    /** Synced dot. */
    val good = Color(0xFF7EDBA5)

    /** Record button dot. */
    val recordDot = Color(0xFFFF6B5E)

    /** Record button while recording. */
    val recordingContainer = Color(0xFF8C1D18)

    /** Ticked checkbox fill. */
    val tickedBox = Color(0xFF5E636E)
}

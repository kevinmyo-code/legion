package com.kevin.legion.ui.theme.soft

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * The phone's palette roles, one value per role. The role names are the soft-Material ones (ADR
 * 0051, home-launcher ticket 02) because 1,100 call sites already read them; the VALUES are
 * whatever the theme around the call site provides through [LocalSoftPalette].
 *
 * Before ADR 0081 these were constants on [SoftColors], read directly, so no theme could reach
 * them (Matrix mode could not, `ui/theme/MatrixMode.kt` said so). Behind a CompositionLocal the
 * same `SoftColors.card` call now resolves through the nearest provider.
 */
@Immutable
data class SoftPalette(
    /** Screen background. */
    val ground: Color,
    /** Talk bar surface - one tier up from [ground], separating the bar from the screen behind it. */
    val barLow: Color,
    /** Hairline above the talk bar. */
    val barRule: Color,
    /** Tiles, cards. */
    val card: Color,
    /** Chips, inputs. */
    val cardHigh: Color,
    /** Menus, progress tracks. */
    val cardHighest: Color,
    /** Dashed "new" card, input borders. */
    val outline: Color,
    /** Primary text. */
    val text: Color,
    /** Secondary text, statuses. */
    val text2: Color,
    /** Tertiary text: ticked text, placeholders. */
    val text3: Color,
    /** Accents. */
    val primary: Color,
    /** Talk pill, FAB, primary buttons. */
    val primaryContainer: Color,
    /** Text on [primaryContainer]. */
    val onPrimaryContainer: Color,
    /** Over budget, overdue, destructive. */
    val alertContainer: Color,
    /** Text on [alertContainer]; also alert-coloured text sitting directly on [card]. */
    val onAlert: Color,
    /** Estimates, refusals, not synced, mic blocked. */
    val caution: Color,
    /** Synced dot. */
    val good: Color,
    /** Record button dot. */
    val recordDot: Color,
    /** Record button while recording. */
    val recordingContainer: Color,
    /** Ticked checkbox fill. */
    val tickedBox: Color,
)

/**
 * The soft-Material dark values (home-launcher ticket 02's token table, lifted from the prototype
 * canvas `research/prototype-canvas/Main.dc.html`). The default of [LocalSoftPalette], so a call
 * site outside any theme still resolves to the values it always had.
 */
val SoftDarkPalette = SoftPalette(
    ground = Color(0xFF121317),
    barLow = Color(0xFF16181D),
    barRule = Color(0xFF23262D),
    card = Color(0xFF1C1E24),
    cardHigh = Color(0xFF24272E),
    cardHighest = Color(0xFF2A2D34),
    outline = Color(0xFF3A3E47),
    text = Color(0xFFE6E7EC),
    text2 = Color(0xFFA9ADB8),
    text3 = Color(0xFF8A8F9B),
    primary = Color(0xFFFFB4A1),
    primaryContainer = Color(0xFF733423),
    onPrimaryContainer = Color(0xFFFFDBD1),
    alertContainer = Color(0xFF4A1F24),
    onAlert = Color(0xFFFFB4AB),
    caution = Color(0xFFFFC857),
    good = Color(0xFF7EDBA5),
    recordDot = Color(0xFFFF6B5E),
    recordingContainer = Color(0xFF8C1D18),
    tickedBox = Color(0xFF5E636E),
)

/**
 * The palette in force. Static: it changes only when a whole theme changes (day to night), and
 * then recomposing the subtree is right.
 */
val LocalSoftPalette = staticCompositionLocalOf { SoftDarkPalette }

/**
 * Reads the palette in force, by role: `SoftColors.card`. Each getter is `@ReadOnlyComposable`,
 * the same shape as `MaterialTheme.colorScheme`, so it is only readable in composition. Code that
 * needs a colour outside composition (a `drawBehind` lambda, a top-level value) reads it into a
 * local first, in the composable around it.
 */
object SoftColors {
    val ground: Color @Composable @ReadOnlyComposable get() = LocalSoftPalette.current.ground
    val barLow: Color @Composable @ReadOnlyComposable get() = LocalSoftPalette.current.barLow
    val barRule: Color @Composable @ReadOnlyComposable get() = LocalSoftPalette.current.barRule
    val card: Color @Composable @ReadOnlyComposable get() = LocalSoftPalette.current.card
    val cardHigh: Color @Composable @ReadOnlyComposable get() = LocalSoftPalette.current.cardHigh
    val cardHighest: Color @Composable @ReadOnlyComposable get() = LocalSoftPalette.current.cardHighest
    val outline: Color @Composable @ReadOnlyComposable get() = LocalSoftPalette.current.outline
    val text: Color @Composable @ReadOnlyComposable get() = LocalSoftPalette.current.text
    val text2: Color @Composable @ReadOnlyComposable get() = LocalSoftPalette.current.text2
    val text3: Color @Composable @ReadOnlyComposable get() = LocalSoftPalette.current.text3
    val primary: Color @Composable @ReadOnlyComposable get() = LocalSoftPalette.current.primary
    val primaryContainer: Color @Composable @ReadOnlyComposable get() = LocalSoftPalette.current.primaryContainer
    val onPrimaryContainer: Color @Composable @ReadOnlyComposable get() = LocalSoftPalette.current.onPrimaryContainer
    val alertContainer: Color @Composable @ReadOnlyComposable get() = LocalSoftPalette.current.alertContainer
    val onAlert: Color @Composable @ReadOnlyComposable get() = LocalSoftPalette.current.onAlert
    val caution: Color @Composable @ReadOnlyComposable get() = LocalSoftPalette.current.caution
    val good: Color @Composable @ReadOnlyComposable get() = LocalSoftPalette.current.good
    val recordDot: Color @Composable @ReadOnlyComposable get() = LocalSoftPalette.current.recordDot
    val recordingContainer: Color @Composable @ReadOnlyComposable get() = LocalSoftPalette.current.recordingContainer
    val tickedBox: Color @Composable @ReadOnlyComposable get() = LocalSoftPalette.current.tickedBox
}

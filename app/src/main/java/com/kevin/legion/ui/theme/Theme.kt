package com.kevin.legion.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * LEGION's theme. Direction "Instrument" on Material 3's machinery, decided by
 * Kevin 2026-08-01 (wayfinder ticket 02, filed to `memory/library/decisions.md`).
 *
 * **The shape of the decision:** Material 3 is a component library plus a token
 * layer. Instrument is almost entirely a retuning of that token layer - colour
 * roles, a shape scale flattened to near-zero, and a monospace type role for
 * anything numeric. Everything M3 gives for free (component behaviour, touch
 * targets, accessibility semantics, dynamic type) is kept. That is a far
 * cheaper route to this look than building a token system from scratch, which
 * is what Midnight AI's `AriaColors`/`AriaType`/`AriaDimens` did.
 *
 * **No dynamic colour, on purpose.** `dynamicColorScheme` would hand the signal
 * hue to the user's wallpaper, and the signal is the identity here. It is also
 * why this file never calls `dynamicDarkColorScheme`, despite the API being
 * available at the compileSdk in use.
 *
 * Wrap every screen in this. Nothing should read [InstrumentGround] and friends
 * directly.
 */

private val DarkScheme = darkColorScheme(
    primary = InstrumentSignal,
    onPrimary = InstrumentGround,
    primaryContainer = InstrumentSignalSunken,
    onPrimaryContainer = InstrumentSignal,

    // Secondary and tertiary are deliberately NOT a second and third accent.
    // Instrument spends its boldness in one place; everything else is neutral.
    secondary = InstrumentInkFaint,
    onSecondary = InstrumentGround,
    secondaryContainer = InstrumentSurfaceSunken,
    onSecondaryContainer = InstrumentInkDim,
    tertiary = InstrumentInkFaint,
    onTertiary = InstrumentGround,

    background = InstrumentGround,
    onBackground = InstrumentInk,
    surface = InstrumentSurface,
    onSurface = InstrumentInk,
    surfaceVariant = InstrumentSurfaceSunken,
    onSurfaceVariant = InstrumentInkFaint,
    surfaceContainerLowest = InstrumentGround,
    surfaceContainerLow = InstrumentSurfaceSunken,
    surfaceContainer = InstrumentSurface,
    surfaceContainerHigh = InstrumentSurface,
    surfaceContainerHighest = InstrumentSurface,

    outline = InstrumentRule,
    outlineVariant = InstrumentRuleFaint,

    error = StateQuarantined,
    onError = InstrumentGround,
    errorContainer = InstrumentSurface,
    onErrorContainer = StateQuarantined,
)

private val LightScheme = lightColorScheme(
    primary = InstrumentSignalLight,
    onPrimary = Color.White,
    primaryContainer = InstrumentSignalSunkenLight,
    onPrimaryContainer = InstrumentSignalLight,

    secondary = InstrumentInkFaintLight,
    onSecondary = Color.White,
    secondaryContainer = InstrumentSurfaceSunkenLight,
    onSecondaryContainer = InstrumentInkDimLight,
    tertiary = InstrumentInkFaintLight,
    onTertiary = Color.White,

    background = InstrumentGroundLight,
    onBackground = InstrumentInkLight,
    surface = InstrumentSurfaceLight,
    onSurface = InstrumentInkLight,
    surfaceVariant = InstrumentSurfaceSunkenLight,
    onSurfaceVariant = InstrumentInkFaintLight,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = InstrumentGroundLight,
    surfaceContainer = InstrumentSurfaceSunkenLight,
    surfaceContainerHigh = InstrumentSurfaceSunkenLight,
    surfaceContainerHighest = InstrumentSurfaceSunkenLight,

    outline = InstrumentRuleLight,
    outlineVariant = InstrumentRuleFaintLight,

    error = StateQuarantinedLight,
    onError = Color.White,
    errorContainer = InstrumentSurfaceSunkenLight,
    onErrorContainer = StateQuarantinedLight,
)

/**
 * Colour roles Material 3 has no vocabulary for.
 *
 * These are NOT forced into `tertiary`/`errorContainer`/etc. Money and
 * provenance are orthogonal to M3's accent system: an amount being a credit has
 * nothing to do with emphasis level, and squatting on a Material role would
 * both lie about the role's meaning and break the moment a component reads it
 * for its own purposes.
 *
 * [debit] resolving to plain `onSurface` is a real decision, not an oversight.
 * Most rows in a bank statement are debits; colouring them all red turns the
 * signal into noise and makes the rare credit harder to find, which is the
 * opposite of what a ledger is for.
 */
@Immutable
data class LegionSemantics(
    /** A credit. The only coloured money in the app. */
    val credit: Color,
    /** A debit. Deliberately the same as ordinary text - see the class doc. */
    val debit: Color,
    /**
     * A value the source document never stated: pantry macros, a cost
     * projection. CLAUDE.md §4 rule five requires these to read as estimates;
     * this colour is half of that, an explicit label is the other half. Colour
     * alone is never sufficient - it fails for colour-blind users and in
     * greyscale, and the rule is a guardrail, not styling.
     */
    val estimated: Color,
    /** A document that failed the gate and was written nowhere. */
    val quarantined: Color,
    /** Structural hairline: section boundaries. */
    val rule: Color,
    /** Row separator inside a long list, softer so lists do not stripe. */
    val ruleFaint: Color,
    /** Dates, units, provenance tags. Present, never competing. */
    val faint: Color,
    /** Status bars, disabled states. */
    val ghost: Color,
)

private val DarkSemantics = LegionSemantics(
    credit = MoneyPositive,
    debit = InstrumentInk,
    estimated = ValueEstimated,
    quarantined = StateQuarantined,
    rule = InstrumentRule,
    ruleFaint = InstrumentRuleFaint,
    faint = InstrumentInkFaint,
    ghost = InstrumentInkGhost,
)

private val LightSemantics = LegionSemantics(
    credit = MoneyPositiveLight,
    debit = InstrumentInkLight,
    estimated = ValueEstimatedLight,
    quarantined = StateQuarantinedLight,
    rule = InstrumentRuleLight,
    ruleFaint = InstrumentRuleFaintLight,
    faint = InstrumentInkFaintLight,
    ghost = InstrumentInkGhostLight,
)

/**
 * Access the semantic roles: `LocalLegionSemantics.current.credit`.
 *
 * `staticCompositionLocalOf` rather than `compositionLocalOf` because the value
 * changes only when the whole theme changes, at which point recomposing the
 * entire tree is correct and cheaper than tracking reads.
 */
val LocalLegionSemantics = staticCompositionLocalOf { DarkSemantics }

/**
 * @param darkTheme defaults to dark **regardless of the system setting**.
 *   Instrument is a dark-committed direction and the light scheme exists so the
 *   app is not broken in daylight, not because following the system was chosen.
 *   Whether to honour [isSystemInDarkTheme] instead is an open question - see
 *   the ticket 02 answer - and switching is this one default.
 */
@Composable
fun LegionTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    val scheme = if (darkTheme) DarkScheme else LightScheme
    val semantics = if (darkTheme) DarkSemantics else LightSemantics

    CompositionLocalProvider(LocalLegionSemantics provides semantics) {
        MaterialTheme(
            colorScheme = scheme,
            typography = LegionTypography,
            shapes = LegionShapes,
            content = content,
        )
    }
}

/**
 * Convenience for previews and for any future setting that follows the OS.
 * Not the default - see [LegionTheme]'s parameter doc.
 */
@Composable
fun LegionThemeFollowingSystem(content: @Composable () -> Unit) {
    LegionTheme(darkTheme = isSystemInDarkTheme(), content = content)
}

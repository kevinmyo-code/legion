// Palette hex literals, as SoftColors; this file holds the palette plus its two vals.
@file:Suppress("MagicNumber", "MatchingDeclarationName")

package com.kevin.legion.ui.dumb

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import com.kevin.legion.ui.theme.LegionSemantics

/**
 * The Matrix colour scheme for LEGION's own screens while Matrix mode is on (Kevin, 2026-10-23,
 * matrix batch item 2): the greens on black that [DumbColors] already holds, poured into Material 3's
 * roles and into [LegionSemantics] so a screen under it turns green with no call-site edit.
 *
 * **Not yet applied anywhere.** Swapping the root `MaterialTheme` reaches only screens under
 * [com.kevin.legion.ui.theme.LegionTheme]; the soft screens read `SoftColors` constants directly and
 * nest their own `SoftTheme`, so how the swap reaches them is an open question for Kevin. This file
 * is the half both answers share: the palette and its contrast gate (`MatrixSchemeTest`).
 *
 * **Alerts are never green.** Error, warning and unverified each get their own non-green hue: red,
 * amber, violet. On a screen where every ordinary value is green, a green warning would read as an
 * ordinary value, which is the failure CLAUDE.md §4 rule 7 exists to prevent. The words still carry
 * the meaning; the hue only reinforces.
 *
 * Every background, surface and container role holds its own raw value (the L11 rule, `Theme.kt`'s
 * audit: `contentColorFor` matches by value). Placeholders are nudged +1 in one channel off a real
 * token, the same technique `SoftTheme.kt` uses.
 */
@Suppress("MagicNumber") // palette hex literals, the same shape as SoftColors
internal object MatrixColors {
    val ground = DumbColors.ground
    val field = DumbColors.field
    val rule = DumbColors.rule
    val outline = DumbColors.outline

    /** Headings and accents: the rain's own green. */
    val text = DumbColors.text

    /** Body text: the paler green, easiest to read in long runs. */
    val body = DumbColors.body

    /** Labels, units, secondary lines. */
    val dim = DumbColors.dim

    /** A card one tier up from [field]. */
    val card = Color(0xFF071F0E)

    /** Chips, inputs: one more tier up. */
    val cardHigh = Color(0xFF0B2914)

    /** Errors, overdue, quarantine. Red, never green. */
    val alert = Color(0xFFFF6B5E)

    /** Fill behind [alert] text. */
    val alertContainer = Color(0xFF2A0806)

    /** Warnings: refusals, not synced, mic blocked. Amber, never green. */
    val warning = Color(0xFFFFC857)

    /** Unverified and estimated figures. Violet, never green, never amber. */
    val unverified = Color(0xFFC9A6FF)
}

internal val MatrixColorScheme = darkColorScheme(
    primary = MatrixColors.text,
    onPrimary = MatrixColors.ground,
    primaryContainer = MatrixColors.rule,
    onPrimaryContainer = MatrixColors.body,
    // Placeholder: nothing reaches for an inverse accent. Nudged off `outline` (+1 blue).
    inversePrimary = Color(0xFF0F8A2D),

    // Secondary is a muted tier, not a second accent (the same posture as Theme.kt and SoftTheme.kt).
    secondary = MatrixColors.dim,
    onSecondary = MatrixColors.ground,
    // Placeholder, nudged off `card` (+1 blue).
    secondaryContainer = Color(0xFF071F0F),
    onSecondaryContainer = MatrixColors.dim,

    // Tertiary is the warning hue, as SoftTheme's tertiary is `caution`.
    tertiary = MatrixColors.warning,
    onTertiary = MatrixColors.ground,
    // Placeholder, nudged off `cardHigh` (+1 blue).
    tertiaryContainer = Color(0xFF0B2915),
    onTertiaryContainer = MatrixColors.warning,

    background = MatrixColors.ground,
    onBackground = MatrixColors.body,
    surface = MatrixColors.field,
    onSurface = MatrixColors.body,
    surfaceVariant = MatrixColors.card,
    onSurfaceVariant = MatrixColors.dim,
    surfaceTint = MatrixColors.text,

    inverseSurface = MatrixColors.body,
    inverseOnSurface = MatrixColors.ground,

    // `error` and `onErrorContainer` share one token on purpose, as in SoftTheme.kt.
    error = MatrixColors.alert,
    onError = MatrixColors.ground,
    errorContainer = MatrixColors.alertContainer,
    onErrorContainer = MatrixColors.alert,

    outline = MatrixColors.outline,
    outlineVariant = Color(0xFF0A3D15),
    // Placeholder, nudged off `ground`.
    scrim = Color(0xFF000001),
    surfaceBright = MatrixColors.cardHigh,
    surfaceDim = Color(0xFF02100A),
    surfaceContainerLowest = Color(0xFF000100),
    surfaceContainerLow = Color(0xFF04140B),
    surfaceContainer = Color(0xFF071F10),
    surfaceContainerHigh = Color(0xFF0B2916),
    surfaceContainerHighest = Color(0xFF0E3019),
)

/** [LegionSemantics] in Matrix colours; same field names, so a screen needs no edit to turn green. */
internal val MatrixSemantics = LegionSemantics(
    credit = MatrixColors.text,
    debit = MatrixColors.body,
    estimated = MatrixColors.unverified,
    quarantined = MatrixColors.alert,
    rule = MatrixColors.rule,
    ruleFaint = MatrixColors.cardHigh,
    faint = MatrixColors.dim,
    ghost = MatrixColors.dim,
    chrome = MatrixColors.text,
    chromeText = MatrixColors.body,
    chromeDim = MatrixColors.outline,
    marker = MatrixColors.text,
    data = MatrixColors.body,
)

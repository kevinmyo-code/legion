package com.kevin.legion.ui.theme.soft

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import com.kevin.legion.ui.theme.LegionSemantics

/**
 * Typeset (ADR 0081): paper and ink, one accent for flags. The token values are the approved
 * prototype's (`proto-typeset.html`, `typeset.css` `:root` and its night block):
 *
 * | Prototype | Day | Night | Roles here |
 * |---|---|---|---|
 * | `--bg` paper | `#faf8f3` | `#14120f` | ground, barLow |
 * | `--ink` | `#1d1a16` | `#f1ece2` | text, primary, primaryContainer, tickedBox |
 * | `--meta` | `#6b655b` | `#a39b8d` | text2, text3 |
 * | `--rule` | `#dcd6ca` | `#2c2823` | barRule, cardHighest |
 * | `--frame` | `#cfc8b9` | `#2c2823` | outline |
 * | `--wash` | `#f1ede4` | `#1c1915` | card |
 * | `--accent` | `#8a2d1e` | `#e0907a` | onAlert, caution, recordDot, recordingContainer |
 * | `--good` | `#1f6b45` | `#86d1a8` | good |
 *
 * **The accent is for flags only** (a watch item, an alert, an estimate, the gate). Buttons, bars
 * and the talk control are ink: the prototype's main button is ink on paper. So `primary` and
 * `primaryContainer` are ink, never accent. An estimate and an alert share the one accent; the
 * words beside them tell them apart (CLAUDE.md section 4 rules 5 and 7 already require the words).
 *
 * Three values have no prototype token and are derived, one step from a real one: `cardHigh`
 * (a step past wash, for a chip or an input still drawn as a filled box until its screen is
 * restyled), `alertContainer` (an accent tint, for the few filled alert tags), and the night
 * `outline` (the night frame equals the night rule, so it is lifted one step to stay visible as a
 * border).
 */
val TypesetDayPalette = SoftPalette(
    ground = Color(0xFFFAF8F3),
    barLow = Color(0xFFFAF8F3),
    barRule = Color(0xFFDCD6CA),
    card = Color(0xFFF1EDE4),
    cardHigh = Color(0xFFEBE6DB),
    cardHighest = Color(0xFFDCD6CA),
    outline = Color(0xFFCFC8B9),
    text = Color(0xFF1D1A16),
    text2 = Color(0xFF6B655B),
    text3 = Color(0xFF6B655B),
    primary = Color(0xFF1D1A16),
    primaryContainer = Color(0xFF1D1A16),
    onPrimaryContainer = Color(0xFFFAF8F3),
    alertContainer = Color(0xFFF3E1DA),
    onAlert = Color(0xFF8A2D1E),
    caution = Color(0xFF8A2D1E),
    good = Color(0xFF1F6B45),
    recordDot = Color(0xFF8A2D1E),
    recordingContainer = Color(0xFF8A2D1E),
    tickedBox = Color(0xFF1D1A16),
)

/** The night edition: the same page set in cream on warm black. */
val TypesetNightPalette = SoftPalette(
    ground = Color(0xFF14120F),
    barLow = Color(0xFF14120F),
    barRule = Color(0xFF2C2823),
    card = Color(0xFF1C1915),
    cardHigh = Color(0xFF23201B),
    cardHighest = Color(0xFF2C2823),
    outline = Color(0xFF3D3830),
    text = Color(0xFFF1ECE2),
    text2 = Color(0xFFA39B8D),
    text3 = Color(0xFFA39B8D),
    primary = Color(0xFFF1ECE2),
    primaryContainer = Color(0xFFF1ECE2),
    onPrimaryContainer = Color(0xFF14120F),
    alertContainer = Color(0xFF3A1F18),
    onAlert = Color(0xFFE0907A),
    caution = Color(0xFFE0907A),
    good = Color(0xFF86D1A8),
    recordDot = Color(0xFFE0907A),
    recordingContainer = Color(0xFFE0907A),
    tickedBox = Color(0xFFF1ECE2),
)

/**
 * The day Material scheme, so a stock M3 component inside `SoftTheme` lands on paper and ink.
 *
 * **The L11 trap** (`SoftTheme.kt`'s scheme doc): `contentColorFor` matches by value, so every
 * role below holds its own raw value. Where two roles want the same token, the second is nudged
 * by one step in the blue channel (`#1D1A16` ink, `#1D1A17` primaryContainer, `#1D1A18`
 * inverseSurface; `#FAF8F3` paper, `#FAF8F4` surface). `SoftThemeTest` checks both editions.
 * `surfaceTint` is paper so a raised surface is not tinted grey by ink; it is never a
 * `contentColorFor` input. Secondary and tertiary are placeholders (meta and good): nothing here
 * spends a second accent.
 */
internal val TypesetDayScheme = lightColorScheme(
    primary = Color(0xFF1D1A16),
    onPrimary = Color(0xFFFAF8F3),
    primaryContainer = Color(0xFF1D1A17),
    onPrimaryContainer = Color(0xFFFAF8F3),
    inversePrimary = Color(0xFFF1ECE2),
    secondary = Color(0xFF6B655B),
    onSecondary = Color(0xFFFAF8F3),
    secondaryContainer = Color(0xFFEBE6DC),
    onSecondaryContainer = Color(0xFF1D1A16),
    tertiary = Color(0xFF1F6B45),
    onTertiary = Color(0xFFFAF8F3),
    tertiaryContainer = Color(0xFFE3EFE7),
    onTertiaryContainer = Color(0xFF1F6B45),
    background = Color(0xFFFAF8F3),
    onBackground = Color(0xFF1D1A16),
    surface = Color(0xFFFAF8F4),
    onSurface = Color(0xFF1D1A16),
    surfaceVariant = Color(0xFFEBE6DB),
    onSurfaceVariant = Color(0xFF6B655B),
    surfaceTint = Color(0xFFFAF8F3),
    inverseSurface = Color(0xFF1D1A18),
    inverseOnSurface = Color(0xFFFAF8F3),
    error = Color(0xFF8A2D1E),
    onError = Color(0xFFFAF8F3),
    errorContainer = Color(0xFFF3E1DA),
    onErrorContainer = Color(0xFF8A2D1E),
    outline = Color(0xFFCFC8B9),
    outlineVariant = Color(0xFFDCD6CA),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFFFBF9F5),
    surfaceDim = Color(0xFFECE8DF),
    surfaceContainerLowest = Color(0xFFFDFCF9),
    surfaceContainerLow = Color(0xFFF7F4EE),
    surfaceContainer = Color(0xFFF4F1EA),
    surfaceContainerHigh = Color(0xFFF1EDE5),
    surfaceContainerHighest = Color(0xFFEBE6DD),
)

/** The night Material scheme. Same rules as [TypesetDayScheme], cream on warm black. */
internal val TypesetNightScheme = darkColorScheme(
    primary = Color(0xFFF1ECE2),
    onPrimary = Color(0xFF14120F),
    primaryContainer = Color(0xFFF1ECE3),
    onPrimaryContainer = Color(0xFF14120F),
    inversePrimary = Color(0xFF1D1A16),
    secondary = Color(0xFFA39B8D),
    onSecondary = Color(0xFF14120F),
    secondaryContainer = Color(0xFF23201C),
    onSecondaryContainer = Color(0xFFF1ECE2),
    tertiary = Color(0xFF86D1A8),
    onTertiary = Color(0xFF14120F),
    tertiaryContainer = Color(0xFF1F2A23),
    onTertiaryContainer = Color(0xFF86D1A8),
    background = Color(0xFF14120F),
    onBackground = Color(0xFFF1ECE2),
    surface = Color(0xFF141210),
    onSurface = Color(0xFFF1ECE2),
    surfaceVariant = Color(0xFF23201B),
    onSurfaceVariant = Color(0xFFA39B8D),
    surfaceTint = Color(0xFF14120F),
    inverseSurface = Color(0xFFF1ECE4),
    inverseOnSurface = Color(0xFF14120F),
    error = Color(0xFFE0907A),
    onError = Color(0xFF14120F),
    errorContainer = Color(0xFF3A1F18),
    onErrorContainer = Color(0xFFE0907A),
    outline = Color(0xFF3D3830),
    outlineVariant = Color(0xFF2C2823),
    scrim = Color(0xFF000000),
    surfaceBright = Color(0xFF2A2620),
    surfaceDim = Color(0xFF100E0C),
    surfaceContainerLowest = Color(0xFF0F0D0B),
    surfaceContainerLow = Color(0xFF171511),
    surfaceContainer = Color(0xFF1C1915),
    surfaceContainerHigh = Color(0xFF211E19),
    surfaceContainerHighest = Color(0xFF26231D),
)

/**
 * The Typeset palette poured into [LegionSemantics], for the shared primitives that read
 * `LocalLegionSemantics`. Structure (`chrome`, `chromeText`, `marker`) is ink, the hairlines are
 * the rules, and the one accent carries both `estimated` and `quarantined`: words beside the
 * number tell them apart.
 */
internal fun typesetSemantics(p: SoftPalette) = LegionSemantics(
    credit = p.good,
    debit = p.text,
    estimated = p.caution,
    quarantined = p.onAlert,
    rule = p.barRule,
    ruleFaint = p.cardHigh,
    faint = p.text2,
    ghost = p.text3,
    chrome = p.text,
    chromeText = p.text,
    chromeDim = p.outline,
    marker = p.text,
    data = p.text,
)

/** [typesetSemantics] over each edition, built once. */
internal val TypesetDaySemantics = typesetSemantics(TypesetDayPalette)
internal val TypesetNightSemantics = typesetSemantics(TypesetNightPalette)

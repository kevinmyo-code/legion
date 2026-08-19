package com.kevin.legion.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * LEGION's theme. Direction "VACUUM with a tinge of SENTRY" on Material 3's
 * machinery (mission-control ticket 01, decided by Kevin 2026-08-14),
 * superseding MILSPEC (cyberdeck-ui ticket 01, 2026-08-07 - see
 * `memory/library/decisions.md` for the earlier entries).
 *
 * **The shape of the decision is unchanged from MILSPEC's and Instrument's**:
 * Material 3 is a component library plus a token layer, and this retheme is
 * almost entirely a retuning of that token layer - colour roles, a shape scale
 * flattened to near-zero, a bundled monospace type scale. Everything M3 gives
 * for free (component behaviour, touch targets, accessibility semantics) is
 * kept. This is why the retheme touches only [Color.kt], this file, [Type.kt],
 * [Shape.kt], and [DeckPanels] - no screen file references a raw token, every
 * one of them reads [MaterialTheme.colorScheme] or [LocalLegionSemantics].
 *
 * **Dark-only, as a stated decision (mission-control map, "Notes", carried
 * over from cyberdeck-ui)**: the OS light/dark toggle stops mattering.
 * Instrument's `LightScheme`, `LegionThemeFollowingSystem`, and the
 * `darkTheme` parameter this composable used to take are all REMOVED, not
 * merely unused. In exchange for dropping the light scheme, the palette
 * itself carries a hard daylight-readability rule: bright foregrounds, no
 * dim-gray-on-black body text. [DeckFaint] (`#8E97A3`) is the tier to watch
 * and is verified on-device (ticket 10), not here - Compose previews cannot
 * simulate outdoor glare.
 *
 * **No dynamic colour, on purpose**, same reasoning as every earlier
 * direction: the accent is the identity here and `dynamicColorScheme` would
 * hand it to the user's wallpaper.
 *
 * Wrap every screen in this. Nothing should read [DeckGround] and friends
 * directly.
 */

/**
 * **L11 audit (2026-08-08, MILSPEC retheme; re-run 2026-08-14 for
 * VACUUM/SENTRY, mission-control ticket 13).** `ColorScheme.contentColorFor`
 * (`androidx.compose.material3.ColorScheme.kt`) is a `when (backgroundColor)`
 * chain matched BY VALUE, in this fixed order: `primary, secondary, tertiary,
 * background, error, primaryContainer, secondaryContainer, tertiaryContainer,
 * errorContainer, inverseSurface, surface, surfaceVariant, [surfaceContainer*
 * -> onSurface uniformly]`. Any two of the first twelve roles sharing a raw
 * value means the LATER one silently resolves to the EARLIER one's `on*`
 * colour - this is exactly the mechanism that put quarantine-red body text on
 * every screen on 2026-08-02 (`surface` and `errorContainer` collided).
 *
 * The twelve roles below were checked pairwise after writing this scheme and
 * are held to a HARD invariant: no two of `{primary, secondary, tertiary,
 * background, error, primaryContainer, secondaryContainer, tertiaryContainer,
 * errorContainer, inverseSurface, surface, surfaceVariant}` may share a raw
 * Color value, even where two of them would happen to want the same `on*`
 * colour anyway. Everything past `surface`/`surfaceVariant` in the chain
 * (`surfaceBright`, all five `surfaceContainer*` tiers) has no arm of its own,
 * so those are free to reuse [DeckPanel]/[DeckGround] - there is no
 * `onSurfaceContainerHigh` role for a collision to corrupt. One precision
 * (senior review of ee201c3, carried over from the MILSPEC audit): a reused
 * value does not resolve "to onSurface regardless" - it resolves via
 * whichever of the twelve EARLIER arms holds that value first.
 * `surfaceContainerLowest` shares [DeckGround], so it resolves through the
 * `background` arm to `onBackground`. Harmless here because `onBackground`
 * and `onSurface` are both [DeckInk], but the mechanism is first-match, not
 * fallback.
 *
 * Three of the twelve are near-identical dark tones by design (this palette
 * has one accent hue per role slot; secondary/tertiary containers exist only
 * because M3's API requires them, nothing in this app reaches for
 * `secondaryContainer` on purpose yet) - those are [DeckPanel] nudged by 1 in
 * the blue channel each (`#05070D`/`#05070E`/`#05070F`, plus
 * `surfaceVariant`'s own nudge to `#050710`), a value delta far below anything
 * visible on a near-black ground, purely to keep the `when` chain honest.
 * [DeckPanelQuarantine] is a REAL, visible departure rather than a nudge, for
 * the reason on its own doc comment.
 *
 * **The twelve values, confirmed pairwise distinct (mission-control ticket
 * 13):** `primary` amber `#FFBA1F`, `secondary` faint `#8E97A3`, `tertiary`
 * chromeText `#FF8A6B`, `background` ground `#000000`, `error` chrome
 * `#FF5330`, `primaryContainer` `#05070D`, `secondaryContainer` `#05070E`,
 * `tertiaryContainer` `#05070F`, `errorContainer` panelAlarm `#170604`,
 * `inverseSurface` ink `#E4E9EF`, `surface` panel `#05070C`, `surfaceVariant`
 * `#050710`. No two of these twelve match.
 */
private val DarkScheme = darkColorScheme(
    primary = DeckAmber,
    onPrimary = DeckGround,
    // Nudged +1 in blue from DeckPanel (see the audit doc above) - the app
    // spends its one accent on `primary` alone, so this container is not a
    // meaningfully different SURFACE, just a value the `when` chain must be
    // able to tell apart from plain `surface`.
    primaryContainer = Color(0xFF05070D),
    onPrimaryContainer = DeckAmber,

    // Secondary and tertiary are deliberately NOT a second and third accent,
    // same posture as every earlier direction. Tertiary borrows
    // [DeckChromeText] rather than [DeckFaint] specifically so it differs by
    // VALUE from `secondary` (both would otherwise be faint, colliding in the
    // audited chain above).
    secondary = DeckFaint,
    onSecondary = DeckGround,
    secondaryContainer = Color(0xFF05070E),
    onSecondaryContainer = DeckFaint,
    tertiary = DeckChromeText,
    onTertiary = DeckGround,
    tertiaryContainer = Color(0xFF05070F),
    onTertiaryContainer = DeckChromeText,

    background = DeckGround,
    onBackground = DeckInk,
    surface = DeckPanel,
    onSurface = DeckInk,
    // Nudged +1 in blue again from `tertiaryContainer`'s nudge (see audit
    // doc) - MUST differ from plain `surface` (DeckPanel) or
    // `onSurfaceVariant` (faint) never resolves; a caller asking for
    // surfaceVariant's content colour would silently get onSurface (ink)
    // instead.
    surfaceVariant = Color(0xFF050710),
    onSurfaceVariant = DeckFaint,
    surfaceContainerLowest = DeckGround,
    surfaceContainerLow = DeckPanel,
    surfaceContainer = DeckPanel,
    surfaceContainerHigh = DeckPanel,
    surfaceContainerHighest = DeckPanel,
    // Inverted surface: the fill under DeckTag's INVERTED_* styles and any M3
    // component (Snackbar, tooltip) that reaches for it. Ink-on-ground with the
    // polarity flipped - light fill, dark content - already distinct from every
    // other role above by raw value.
    inverseSurface = DeckInk,
    inverseOnSurface = DeckGround,

    outline = DeckRule,
    // outlineVariant has no `on*` counterpart in the contentColorFor chain, so
    // it carries no collision risk - [DeckRuleFaint] is ticket 01's own
    // distinct fainter-hairline token now, not an alpha derivative.
    outlineVariant = DeckRuleFaint,

    error = DeckChrome,
    onError = DeckGround,
    errorContainer = DeckPanelQuarantine,
    onErrorContainer = DeckChrome,
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
 * **Re-cut to the VACUUM/SENTRY palette, mission-control ticket 01
 * (2026-08-14, revised same day by ticket 06).** Field NAMES are UNCHANGED
 * from the MILSPEC/Instrument era on purpose - every screen file reads
 * `LocalLegionSemantics.current.<name>`, and keeping the names stable while
 * only recolouring the values is what lets this retheme land without touching
 * a single screen file.
 *
 * [credit] and [debit] now hold the SAME raw value ([DeckData], mint). This is
 * intended, not an oversight: ticket 06's `dataviz` validator found every
 * candidate green fails hue separation against mint on normal vision and
 * against amber under deuteranopia, with no value that clears both, so green
 * is dropped from the palette entirely. A credit still reads as distinct
 * information - the call site prints a leading `+` and the word `CREDIT` - but
 * that distinction is now carried by the label, not the hue, and the field
 * still documents that intent even though [credit] and [debit] resolve
 * identically. [debit] resolving to an ordinary value colour (now [DeckData]
 * rather than the old plain [DeckInk]) is unchanged in spirit from every
 * earlier direction: a debit is NEVER accented as an alarm colour, and most
 * bank rows are debits, so colouring them would turn the one alarm hue into
 * noise.
 */
@Immutable
data class LegionSemantics(
    /** A credit: "money coming in". Now the same value as [debit] - see the class doc for why - distinguished by the `+`/`CREDIT` label at the call site, not by hue. */
    val credit: Color,
    /** A debit. An ordinary value, mint, like every other value in the app - see the class doc. */
    val debit: Color,
    /**
     * A value the source document never stated: pantry macros, a cost
     * projection, a provisional balance. CLAUDE.md §4 rule five requires these
     * to read as estimates, and rule seven requires the same of any figure
     * containing an `UNRECONCILED` row; the WORDED label carries that meaning
     * (`ESTIMATED`, `UNRECONCILED`) - colour is reinforcement only, and that
     * colour is amber, same as any other highlight, because an estimate is
     * still data, just unverified data.
     */
    val estimated: Color,
    /** A document that failed the gate and was written nowhere. Provisional per ticket 01 - ticket 04 owns the final semantic-colour treatment. */
    val quarantined: Color,
    /** Structural hairline: section boundaries. Maps to [DeckRule]. */
    val rule: Color,
    /** Row separator inside a long list, softer so lists do not stripe. Maps to [DeckRuleFaint] - the fainter hairline, for dashed row rules, meter tracks and chart gridlines. */
    val ruleFaint: Color,
    /** Labels, units, provenance. Present, never competing. Maps to [DeckFaint]. Ticket 10 checks this tier FIRST for daylight contrast. */
    val faint: Color,
    /** Timestamps, gaps, disabled. Maps to [DeckGhost] - its own raw token now, not an alpha of [faint]. */
    val ghost: Color,
    /** Pill outline, bezel ticks, alarm border/fill. One of chrome's three tiers - see [DeckChrome]'s own doc for why full-strength red is reserved rather than spent on every pane edge. */
    val chrome: Color,
    /** Pill label, section-rule label. Maps to [DeckChromeText] - the brightest chrome tier, for text riding on a [chrome] fill. */
    val chromeText: Color,
    /** Bezel line, pane outline. Maps to [DeckChromeDim] - the STRUCTURAL chrome tier that nearly every pane edge in the app actually uses. */
    val chromeDim: Color,
    /** Chart endpoint and typed markers. Maps to [DeckMarker] - a deliberate near-amber nudge, not a second signal colour; typed markers differ by SHAPE, not by this hue (ticket 01 sub-answer #3). */
    val marker: Color,
    /** Every ordinary value. Maps to [DeckData] - mint, pulled back off full saturation so it reads as "this is a value" without competing with [chrome]/[amber]. */
    val data: Color,
)

private val DarkSemantics = LegionSemantics(
    credit = DeckData,
    debit = DeckData,
    estimated = DeckAmber,
    quarantined = DeckChrome,
    rule = DeckRule,
    ruleFaint = DeckRuleFaint,
    faint = DeckFaint,
    ghost = DeckGhost,
    chrome = DeckChrome,
    chromeText = DeckChromeText,
    chromeDim = DeckChromeDim,
    marker = DeckMarker,
    data = DeckData,
)

/**
 * Access the semantic roles: `LocalLegionSemantics.current.credit`.
 *
 * `staticCompositionLocalOf` rather than `compositionLocalOf` because the value
 * changes only when the whole theme changes, at which point recomposing the
 * entire tree is correct and cheaper than tracking reads. Being dark-only
 * removes the one case (light/dark toggle) that used to change this value at
 * runtime, but the mechanism itself stays - it is still "one place to change"
 * if a future retheme happens.
 */
val LocalLegionSemantics = staticCompositionLocalOf { DarkSemantics }

/**
 * VACUUM/SENTRY is dark-only (mission-control map, "Notes", carried over from
 * cyberdeck-ui - a stated decision, not a gap). This composable used to take a
 * `darkTheme` Boolean and there used to be a `LegionThemeFollowingSystem` twin
 * that read [androidx.compose.foundation.isSystemInDarkTheme]; both are gone.
 * Grep confirmed no caller outside this file's own (now rewritten) previews
 * ever passed `darkTheme = false`.
 */
@Composable
fun LegionTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalLegionSemantics provides DarkSemantics) {
        MaterialTheme(
            colorScheme = DarkScheme,
            typography = LegionTypography,
            shapes = LegionShapes,
            content = content,
        )
    }
}

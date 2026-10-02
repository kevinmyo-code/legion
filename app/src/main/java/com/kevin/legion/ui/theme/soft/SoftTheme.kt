package com.kevin.legion.ui.theme.soft

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.kevin.legion.ui.theme.LegionSemantics
import com.kevin.legion.ui.theme.LocalLegionSemantics

/**
 * Corner radii ticket 02 names: small 10dp, medium 16dp, large 20dp, extraLarge 24dp. `extraSmall`
 * is left at Material 3's own default (4dp) - nothing named by the ticket reaches for it, and this
 * ticket restyles the shell chrome, not the whole shape scale.
 */
private val SoftShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

/**
 * The `darkColorScheme` ticket 02 asks [SoftTheme] to build from [SoftColors]. Every composable
 * this ticket writes reads [SoftColors] directly rather than `MaterialTheme.colorScheme` (see that
 * object's own doc) - this scheme exists so a stock M3 component drawn inside [SoftTheme] (a
 * `Surface`'s default fill, an `IconButton`'s ripple, a `Text` with no explicit colour) still lands
 * on a value from THIS palette rather than M3's own baseline purple-grey tones.
 *
 * **The L11 trap** (`ui/theme/Theme.kt`'s own `DarkScheme` audit, 2026-08-02: `surface` and
 * `errorContainer` shared a raw value and `contentColorFor` - a `when (backgroundColor)` chain
 * matched BY VALUE - silently resolved every body `Text` to the wrong `on*` colour). That audit
 * measured exactly twelve roles as the chain's real inputs on the M3 version it checked
 * (`primary, secondary, tertiary, background, error, primaryContainer, secondaryContainer,
 * tertiaryContainer, errorContainer, inverseSurface, surface, surfaceVariant`), with every
 * `surfaceContainer*` tier falling through to one shared `-> onSurface` arm regardless of value.
 * Ticket 02 asks for a broader guarantee than that ("every background/surface/container slot...
 * `surfaceVariant` must NOT equal `surfaceContainerHigh`") without pinning it to one M3 release, so
 * rather than re-deriving which roles today's `contentColorFor` actually branches on, EVERY
 * background/surface/container role below is given its own distinct raw value - a strictly stronger
 * guarantee than "the twelve inputs differ", true for any version's chain and cheap to hold, checked
 * by `SoftThemeTest`.
 *
 * `internal`, not `private` - `SoftThemeTest` (`src/test/java`) reads it directly, the same
 * cross-source-set visibility `MainActivity.kt`'s `internal fun formatShellStatusLine` already
 * relies on for `ShellStatusLineTest`.
 *
 * Two same-value pairs are kept ON PURPOSE, each commented at its own line below, matching
 * `Theme.kt`'s own `credit`/`debit` precedent ("intended, not an oversight"): `error`/
 * `onErrorContainer` (one token, [SoftColors.onAlert], deliberately serves both jobs) and
 * `surfaceTint`/`primary` (the ordinary M3 convention, not a hazard - `surfaceTint` is never itself
 * a `contentColorFor` input).
 *
 * Every role with no real design intent yet (`secondary`/`tertiary` and their containers,
 * `inversePrimary`, `scrim`, the `surfaceContainer*` ladder beyond `surfaceDim`/`surfaceBright`) is a
 * PLACEHOLDER: Material 3's factory function requires a value, nothing in this ticket's screens
 * reaches for that role on purpose, and each is either a reused real token or a tiny, clearly
 * distinct nudge off one - the same collision-breaking technique `Theme.kt`'s own `DarkScheme`
 * already uses ("nudged +1 in blue"), just applied to more slots because this scheme carries a
 * larger, newer surface-container ladder than VACUUM/SENTRY's did.
 */
internal val SoftColorScheme = darkColorScheme(
    primary = SoftColors.primary,
    onPrimary = SoftColors.ground,
    primaryContainer = SoftColors.primaryContainer,
    onPrimaryContainer = SoftColors.onPrimaryContainer,
    // Placeholder - nothing in this ticket reaches for an inverse accent. Nudged off `outline`
    // (+1 in blue: 0x3A3E47 -> 0x3A3E48) purely to stay distinct from every role below.
    inversePrimary = Color(0xFF3A3E48),

    // Secondary is deliberately NOT a second accent (mirrors `Theme.kt`'s own `secondary = DeckFaint`
    // posture) - a muted neutral, since nothing named by ticket 02 spends a second hue.
    secondary = SoftColors.text2,
    onSecondary = SoftColors.ground,
    // Placeholder, manufactured (no real token behind it) - only required to exist and differ from
    // every other role here.
    secondaryContainer = Color(0xFF242730),
    onSecondaryContainer = SoftColors.text2,

    // Tertiary borrows `caution` as its placeholder value - a real token, reused here only because
    // M3 requires SOME third accent and nothing in this ticket spends one on purpose.
    tertiary = SoftColors.caution,
    onTertiary = SoftColors.ground,
    // Placeholder, manufactured, same posture as `secondaryContainer` above.
    tertiaryContainer = Color(0xFF2A2D37),
    onTertiaryContainer = SoftColors.caution,

    background = SoftColors.ground,
    onBackground = SoftColors.text,

    surface = SoftColors.card,
    onSurface = SoftColors.text,

    surfaceVariant = SoftColors.cardHigh,
    onSurfaceVariant = SoftColors.text2,

    // M3 convention, not a hazard: `surfaceTint` is never itself queried as a `contentColorFor`
    // input, so mirroring `primary` here (the standard M3 baseline behaviour) is intentional reuse,
    // not a collision.
    surfaceTint = SoftColors.primary,

    // Inverted surface: light fill, dark content - already distinct from every dark role above by
    // raw value.
    inverseSurface = SoftColors.text,
    inverseOnSurface = SoftColors.ground,

    // `error` and `onErrorContainer` deliberately share ONE token (`onAlert`) - see this token's own
    // doc comment on `SoftColors`: "text on [alertContainer]; also alert-coloured text sitting
    // directly on card". Both jobs are the same physical colour by design, matching `Theme.kt`'s
    // own `credit`/`debit` precedent for an intentional, documented reuse.
    error = SoftColors.onAlert,
    onError = SoftColors.ground,
    errorContainer = SoftColors.alertContainer,
    onErrorContainer = SoftColors.onAlert,

    outline = SoftColors.outline,
    // Reused, not a placeholder: `barRule` is already a hairline colour by design (the rule above
    // the talk bar), and `outlineVariant` is never itself a `contentColorFor` input.
    outlineVariant = SoftColors.barRule,

    // Placeholder - scrim is rarely rendered by anything this ticket builds. Nudged off
    // `cardHighest` (+1 in blue) to stay distinct.
    scrim = Color(0xFF2A2D35),

    // The lightest/dimmest ends of the card ladder map onto M3's bright/dim roles directly - both
    // are real tokens, not manufactured.
    surfaceBright = SoftColors.cardHighest,
    surfaceDim = SoftColors.barLow,

    // The five-tier surfaceContainer ladder: nothing this ticket builds reaches for these roles by
    // name (StatusLine/AssistantStrip read `SoftColors` tokens directly), so each is a nudge (+1 in
    // blue) off the ladder's own five natural tones, distinct from the un-nudged values used above
    // (`ground`/`barLow`/`card`/`barRule`/`cardHigh`) and from each other.
    surfaceContainerLowest = Color(0xFF121318),
    surfaceContainerLow = Color(0xFF16181E),
    surfaceContainer = Color(0xFF1C1E25),
    surfaceContainerHigh = Color(0xFF23262E),
    surfaceContainerHighest = Color(0xFF24272F),
)


/**
 * True only inside [SoftTheme] ([com.kevin.legion.ui.theme.LegionTheme] resets it to false). The
 * shared `ui/common` primitives branch on this ONE flag, never on comparing colour values, to pick
 * their soft or mission-control rendering. Static: it changes only when a whole theme does.
 */
val LocalSoftActive = staticCompositionLocalOf { false }

/**
 * The soft palette poured into [LegionSemantics]'s existing field names, so every screen that reads
 * `LocalLegionSemantics.current.<name>` turns soft the moment it sits under [SoftTheme] with no call
 * site edit. Mapping, and why:
 * - `data` = [SoftColors.text]: values are no longer mint, a value is just primary text. `debit` the
 *   same (a debit is never accented). `credit` = [SoftColors.good]; the `+` and the word at the call
 *   site still carry the meaning, hue only reinforces.
 * - `estimated` = [SoftColors.caution]; `quarantined` = [SoftColors.onAlert] (alert-coloured text on
 *   a card; the filled form is [SoftColors.alertContainer], drawn by `QuarantineTag`). Both are
 *   reinforcement only: CLAUDE.md section 4 rules 5 and 7 still require the words on screen.
 * - `faint` = [SoftColors.text2], `ghost` = [SoftColors.text3].
 * - `rule` = [SoftColors.barRule], `ruleFaint` = [SoftColors.cardHigh]: quiet hairlines and tracks.
 * - `chrome` = [SoftColors.primary], `chromeText` = [SoftColors.onPrimaryContainer] (brighter tier for
 *   text), `chromeDim` = [SoftColors.primaryContainer] (structural line or border).
 * - `marker` = [SoftColors.primary], deliberately NOT [SoftColors.caution] so a chart marker and an
 *   estimate stay different hues, as the mission-control pair did.
 * `internal` so `SoftThemeTest` can read it.
 */
internal val SoftSemantics = LegionSemantics(
    credit = SoftColors.good,
    debit = SoftColors.text,
    estimated = SoftColors.caution,
    quarantined = SoftColors.onAlert,
    rule = SoftColors.barRule,
    ruleFaint = SoftColors.cardHigh,
    faint = SoftColors.text2,
    ghost = SoftColors.text3,
    chrome = SoftColors.primary,
    chromeText = SoftColors.onPrimaryContainer,
    chromeDim = SoftColors.primaryContainer,
    marker = SoftColors.primary,
    data = SoftColors.text,
)

/**
 * Wraps the chrome ticket 02 converted (`StatusLine`, `AssistantStrip`), HOME, Lists, Apps, and every
 * drill-down screen as it is converted. Screens not yet converted keep
 * [com.kevin.legion.ui.theme.LegionTheme] (ADR 0051: "surface by surface"). Nested INSIDE that outer
 * theme at each converted call site, not a replacement for it at the root - `MaterialTheme` and the
 * locals below are CompositionLocal providers, so a nested one overrides for its own subtree only.
 *
 * Also provides [LocalLegionSemantics] = [SoftSemantics] and [LocalSoftActive] = true, which is what
 * turns the shared `ui/common` primitives (`DeckPane`, `DeckScreenHeader`, `DeckRow`, ...) and
 * `LegionType.amount/reading/stamp` soft.
 *
 * **Converting a drill-down screen (how-to for screen builders):**
 * 1. In the screen's own file wrap its content in `SoftTheme { ... }`, the way `ChecklistsScreen.kt`
 *    does. Not at the nav host: Settings and Driving mode stay on LegionTheme for now.
 * 2. Open with `DeckScreenHeader(title, onBack, accent = AreaAccent.X)`. The chip glyph defaults to
 *    `accent.icon` (HOME's tile glyph); pass `iconRes` only to override. Calendar=CALENDAR,
 *    Money and Pantry=MONEY, Body=BODY, Fleet=FLEET, Recordings=RECORDINGS, News=NEWS,
 *    Reports and Ask=REPORTS.
 * 3. Replace raw `Deck*` colour constants from `ui/theme/Color.kt` (`DeckData`, `DeckAmber`, ...) with
 *    `LocalLegionSemantics.current.<field>` or `SoftColors`. Those constants stay mission-control.
 * 4. Rewrite caller-supplied stamp strings ("MONTH", "NOT BUILT") in sentence case. Primitives skip
 *    their own `.uppercase()` under soft but never alter a string the caller passed in.
 * 5. Use `MsIcon` with `res/drawable/ms_*` for glyphs, not "<" text arrows.
 * 6. Trust wording stays in words: estimate, UNRECONCILED/unverified and quarantine render as text
 *    beside the number, never colour alone, never collapsed behind a `HelpRow`.
 * 7. Hand-built cards and chips use `MaterialTheme.shapes` (16/20dp) and `SoftColors.card/cardHigh`,
 *    not 1dp borders or bezels.
 */
@Composable
fun SoftTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalLegionSemantics provides SoftSemantics,
        LocalSoftActive provides true,
    ) {
        MaterialTheme(
            colorScheme = SoftColorScheme,
            typography = SoftTypography,
            shapes = SoftShapes,
            content = content,
        )
    }
}

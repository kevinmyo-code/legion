package com.kevin.legion.ui.theme.soft

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.kevin.legion.R

/**
 * Figtree, bundled as four static TTFs (home-launcher ticket 02: minSdk is 24, and a variable-font
 * axis needs 26 - CLAUDE.md §7's "assets are bundled, never fetched at runtime" is satisfied the
 * same way [com.kevin.legion.ui.theme.Type]'s Martian Mono Condensed already is, three/four
 * `res/font/` files rather than one variable one). Licence text is
 * `assets/licenses/figtree-OFL.txt`, matching that file's own precedent
 * (`third_party/martian-mono/`) for where a bundled font's licence lives.
 *
 * A SEPARATE [FontFamily] from [com.kevin.legion.ui.theme.LegionTypography]'s Martian Mono - the two
 * type systems coexist deliberately, same as the two colour systems ([SoftColors] beside
 * `ui/theme/Color.kt`), because [com.kevin.legion.ui.theme.LegionTheme] keeps running on every
 * screen this ticket does not convert (ADR 0051: "surface by surface").
 */
internal val Figtree = FontFamily(
    Font(R.font.figtree_regular, FontWeight.Normal),
    Font(R.font.figtree_medium, FontWeight.Medium),
    Font(R.font.figtree_semibold, FontWeight.SemiBold),
    Font(R.font.figtree_bold, FontWeight.Bold),
)

/**
 * The ten roles ticket 02 names, sizes/weights exactly as specified, plus the display (Large/Medium/
 * Small), headlineLarge and headlineMedium roles the drill-down conversion added
 * (see the comment at the first of them). Before
 * that addition those five were left at Material 3's own baseline; this comment said so, and it no
 * longer holds.
 */
val SoftTypography = Typography(
    // display* / headlineLarge / headlineMedium were left at M3's baseline (Roboto) by the shell
    // tickets because nothing they built read them. Drill-downs do (hero readouts, screen titles),
    // so under SoftTheme they resolve to Figtree too rather than silently falling back to the
    // system face. Sizes follow the ladder already above, not a new taste call.
    displayLarge = TextStyle(
        fontFamily = Figtree, fontWeight = FontWeight.Bold, fontSize = 36.sp, lineHeight = 42.sp,
    ),
    displayMedium = TextStyle(
        fontFamily = Figtree, fontWeight = FontWeight.Bold, fontSize = 30.sp, lineHeight = 36.sp,
    ),
    displaySmall = TextStyle(
        fontFamily = Figtree, fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 32.sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = Figtree, fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 34.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 30.sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = Figtree, fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 32.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = Figtree, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 22.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = Figtree, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = Figtree, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, lineHeight = 18.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = Figtree, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp,
    ),
)

/**
 * Soft equivalents of [com.kevin.legion.ui.theme.LegionType]'s three extra roles, picked by that
 * object's getters when [LocalSoftActive] is true so no call site changes. Figtree is proportional,
 * so the tabular `tnum` OpenType feature stands in for the monospace that kept money and reading
 * columns aligned under Martian Mono (same technique [com.kevin.legion.ui.common.StatusLine]'s clock
 * already uses). No letterSpacing stamp: sentence case needs none.
 */
internal object SoftLegionType {
    val amount = TextStyle(
        fontFamily = Figtree, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp,
        fontFeatureSettings = "tnum",
    )
    val reading = TextStyle(
        fontFamily = Figtree, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 20.sp,
        fontFeatureSettings = "tnum",
    )
    val stamp = TextStyle(
        fontFamily = Figtree, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp,
        fontFeatureSettings = "tnum",
    )
}

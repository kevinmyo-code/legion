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
private val Figtree = FontFamily(
    Font(R.font.figtree_regular, FontWeight.Normal),
    Font(R.font.figtree_medium, FontWeight.Medium),
    Font(R.font.figtree_semibold, FontWeight.SemiBold),
    Font(R.font.figtree_bold, FontWeight.Bold),
)

/**
 * The ten roles ticket 02 names, sizes/weights exactly as specified. Every OTHER [Typography] role
 * (display*, headlineLarge/Medium) is left at Material 3's own baseline - this ticket restyles the
 * shell chrome, not a full type ramp, and nothing built so far reads a role this file does not
 * override.
 */
val SoftTypography = Typography(
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

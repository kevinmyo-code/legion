package com.kevin.legion.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * LEGION's type scale. Two roles, split by what the text IS:
 *
 * - **Monospace** for anything a reader compares down a column - amounts,
 *   balances, dates, units, machine tags. [FontFamily.Monospace] is inherently
 *   tabular, which is the entire reason this direction uses it: Compose has no
 *   `font-variant-numeric: tabular-nums`, so picking a mono family IS how
 *   digits are made to line up.
 * - **Sans** for anything read as language - merchant names, headings, prose.
 *
 * No font file is bundled. Both families resolve to the platform's own, which
 * keeps CLAUDE.md §7's bundle-never-fetch rule satisfied trivially and costs
 * nothing in APK size. If a licensed face is ever added it slots in here and
 * nowhere else.
 */

/** Numerals, units, dates, tags. Read [LegionType.mono] rather than this. */
private val Mono = FontFamily.Monospace
private val Sans = FontFamily.SansSerif

val LegionTypography = Typography(
    // Big readouts: an account balance, a headline figure.
    displayLarge = TextStyle(
        fontFamily = Mono,
        fontWeight = FontWeight.Normal,
        fontSize = 40.sp,
        lineHeight = 46.sp,
        letterSpacing = (-0.5).sp,
    ),
    displayMedium = TextStyle(
        fontFamily = Mono,
        fontWeight = FontWeight.Normal,
        fontSize = 30.sp,
        lineHeight = 36.sp,
        letterSpacing = (-0.4).sp,
    ),
    displaySmall = TextStyle(
        fontFamily = Mono,
        fontWeight = FontWeight.Normal,
        fontSize = 24.sp,
        lineHeight = 30.sp,
        letterSpacing = (-0.3).sp,
    ),

    // Screen and section headings. Language, so sans.
    headlineMedium = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.2).sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 24.sp,
        letterSpacing = (-0.1).sp,
    ),
    titleMedium = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 20.sp,
    ),

    // Body. Merchant names, descriptions, prose.
    bodyLarge = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 21.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Normal,
        fontSize = 13.5.sp,
        lineHeight = 19.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = Sans,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 17.sp,
    ),

    // Eyebrows and machine labels: mono, uppercase at the call site, tracked out.
    labelLarge = TextStyle(
        fontFamily = Mono,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.6.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = Mono,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        letterSpacing = 1.4.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = Mono,
        fontWeight = FontWeight.Normal,
        fontSize = 9.5.sp,
        lineHeight = 13.sp,
        letterSpacing = 1.2.sp,
    ),
)

/**
 * Type roles Material 3's [Typography] has no slot for. Amounts are the one
 * that matters: an amount is not `bodyMedium` that happens to be mono, it is
 * its own role, and every list of money in this app must use it or the columns
 * stop lining up.
 */
object LegionType {
    /** A transaction amount in a list. Tabular by construction. */
    val amount = TextStyle(
        fontFamily = Mono,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    )

    /** A secondary figure: a live PID value, an item count, a macro number. */
    val reading = TextStyle(
        fontFamily = Mono,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 20.sp,
    )

    /** A date or unit sitting beneath a description. */
    val stamp = TextStyle(
        fontFamily = Mono,
        fontWeight = FontWeight.Normal,
        fontSize = 10.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.5.sp,
    )
}

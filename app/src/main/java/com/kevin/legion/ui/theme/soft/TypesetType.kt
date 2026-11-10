package com.kevin.legion.ui.theme.soft

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.kevin.legion.R

/**
 * Source Serif 4 (ADR 0081), bundled as six static TTFs from Adobe's 4.005 release: minSdk is 24
 * and a variable-font axis needs 26, the same reason Figtree went in as statics. Licence:
 * `assets/licenses/source-serif-4-OFL.txt` (SIL OFL 1.1, Reserved Font Name "Source").
 *
 * Two optical sizes, because the prototype set `font-optical-sizing: auto` on the variable font:
 * [SourceSerif] is the Text cut (12 to 34 on the scale), [SourceSerifDisplay] the Display cut
 * that the same automatic sizing picks at 64 and 92.
 */
internal val SourceSerif = FontFamily(
    Font(R.font.source_serif_4_regular, FontWeight.Normal),
    Font(R.font.source_serif_4_italic, FontWeight.Normal, FontStyle.Italic),
    Font(R.font.source_serif_4_semibold, FontWeight.SemiBold),
    Font(R.font.source_serif_4_semibold_italic, FontWeight.SemiBold, FontStyle.Italic),
)

/** The Display optical size, for the two big figures (64 and 92). */
internal val SourceSerifDisplay = FontFamily(
    Font(R.font.source_serif_4_display_regular, FontWeight.Normal),
    Font(R.font.source_serif_4_display_semibold, FontWeight.SemiBold),
)

/** Lining, tabular figures: numbers in a column line up, as the prototype's `lining-nums tabular-nums`. */
private const val FIGURES = "lnum, tnum"

/**
 * The one type scale (prototype `typeset.css`: 12 / 14 / 17 / 21 / 34 / 64 / 92), as named styles
 * for the Typeset components. Colour is left unset: the caller passes ink or meta.
 */
object TypesetType {
    /** Small-caps labels: 12, semibold, tracked. The caller upper-cases the words. */
    val caps = TextStyle(
        fontFamily = SourceSerif, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 16.sp,
        letterSpacing = 0.09.em,
    )

    /** Metadata and secondary lines: 14. */
    val small = TextStyle(fontFamily = SourceSerif, fontSize = 14.sp, lineHeight = 20.sp)

    /** Italic metadata: dateline, "as of", status words. */
    val smallItalic = small.copy(fontStyle = FontStyle.Italic)

    /** Body: 17. */
    val body = TextStyle(fontFamily = SourceSerif, fontSize = 17.sp, lineHeight = 24.sp)

    /** Body, semibold: a row's name, a figure's label. */
    val bodyStrong = body.copy(fontWeight = FontWeight.SemiBold)

    /** The lead: 21, semibold. A status line while talking, a hero label. */
    val lead = TextStyle(
        fontFamily = SourceSerif, fontWeight = FontWeight.SemiBold, fontSize = 21.sp, lineHeight = 26.sp,
    )

    /** A figure: 34, semibold, lining tabular numbers. */
    val fig = TextStyle(
        fontFamily = SourceSerif, fontWeight = FontWeight.SemiBold, fontSize = 34.sp, lineHeight = 36.sp,
        letterSpacing = (-0.01).em, fontFeatureSettings = FIGURES,
    )

    /** The hero number: 64, Display cut. */
    val hero = TextStyle(
        fontFamily = SourceSerifDisplay, fontSize = 64.sp, lineHeight = 64.sp,
        letterSpacing = (-0.02).em, fontFeatureSettings = "lnum",
    )

    /** The time, the page's headline: 92, Display cut. */
    val time = TextStyle(
        fontFamily = SourceSerifDisplay, fontSize = 92.sp, lineHeight = 92.sp,
        letterSpacing = (-0.035).em, fontFeatureSettings = "lnum",
    )
}

/**
 * The Material roles mapped onto the same scale, so every screen not yet restyled reads Source
 * Serif at the nearest prototype size (Figtree's 36, 30 and 28 to 34; 26, 24 and 22 to 21; 16 to 17;
 * 15, 14 and 13 to 14). The two big sizes, 64 and 92, have no Material role a legacy screen reads;
 * they live in [TypesetType] for the components built for them.
 */
val SoftTypography = Typography(
    displayLarge = TypesetType.fig.copy(fontFeatureSettings = null),
    displayMedium = TypesetType.fig.copy(fontFeatureSettings = null),
    displaySmall = TypesetType.fig.copy(fontFeatureSettings = null),
    headlineLarge = TypesetType.fig.copy(fontFeatureSettings = null),
    headlineMedium = TypesetType.lead,
    headlineSmall = TypesetType.lead,
    titleLarge = TypesetType.lead,
    titleMedium = TypesetType.bodyStrong,
    titleSmall = TypesetType.small.copy(fontWeight = FontWeight.SemiBold),
    bodyLarge = TypesetType.body,
    bodyMedium = TypesetType.small,
    bodySmall = TypesetType.small,
    labelLarge = TypesetType.small.copy(fontWeight = FontWeight.SemiBold),
    labelMedium = TypesetType.caps,
    labelSmall = TypesetType.caps.copy(fontWeight = FontWeight.Normal, letterSpacing = 0.02.em),
)

/**
 * Typeset equivalents of [com.kevin.legion.ui.theme.LegionType]'s three extra roles: an amount and
 * a reading at body size with lining tabular figures, a stamp at the small size.
 */
internal object SoftLegionType {
    val amount = TypesetType.bodyStrong.copy(fontFeatureSettings = FIGURES)
    val reading = TypesetType.body.copy(fontFeatureSettings = FIGURES)
    val stamp = TypesetType.small.copy(fontFeatureSettings = FIGURES)
}

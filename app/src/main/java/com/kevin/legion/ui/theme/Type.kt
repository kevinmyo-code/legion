package com.kevin.legion.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.kevin.legion.R

/**
 * LEGION's type scale. Mono everywhere - headers, labels and body all read as
 * instrument output, not just the tabular columns (MILSPEC direction,
 * cyberdeck-ui ticket 01, carried unchanged into mission-control).
 *
 * **Bundled face as of mission-control ticket 02/13: Martian Mono Condensed**
 * (OFL 1.1, Evil Martians), three statics - Regular/W400, Medium/W500,
 * Bold/W700 - in `res/font/`, licence text in `third_party/martian-mono/`.
 * This replaces the earlier [FontFamily.Monospace] placeholder (whatever mono
 * the device happened to ship, an unknown quantity on Kevin's Oppo A17K) that
 * cyberdeck-ui's build ticket deferred on purpose. CLAUDE.md §7's
 * bundle-never-fetch rule stays satisfied the same way it always did: the
 * three files ship as Android assets/resources, nothing is fetched at runtime.
 *
 * **Every size below steps down roughly 10% from the pre-ticket-13 scale**
 * (research ticket 02: Martian Condensed's cap height measures 0.800em against
 * the platform mono the old sizes were tuned for). This is not a taste pass -
 * carrying the old sizes over unchanged renders visibly larger than intended.
 * Two roles are held at a floor rather than stepped down: `bodySmall` at 11sp
 * and `labelSmall` at 9sp (ticket 05's daylight-legibility floors for body and
 * label text) - both would otherwise round to 10sp/8sp under a straight 10%
 * cut, and ticket 05 fixed those two sizes as a hard minimum, not a target.
 *
 * Tabular alignment for money/PID columns still comes free the same way it did
 * under every earlier direction: Compose has no `font-variant-numeric:
 * tabular-nums`, so a monospace family IS the mechanism, not a side effect.
 * Martian Condensed's digits share one fixed advance (research ticket 02,
 * measured from `hmtx`), so this holds under the new face too.
 */

private val MartianMonoCondensed = FontFamily(
    Font(R.font.martian_mono_condensed_regular, FontWeight.Normal),
    Font(R.font.martian_mono_condensed_medium, FontWeight.Medium),
    Font(R.font.martian_mono_condensed_bold, FontWeight.Bold),
)

private val Mono = MartianMonoCondensed

/** Stencil-caps header ~0.2em tracked, per ticket 01's panel-language spec. Callers `.uppercase()` the string - this style does not transform case itself, matching every other role below and CLAUDE.md's "money/labels formatted by the call site" posture. */
private val HeaderLetterSpacing = 0.2.em

val LegionTypography = Typography(
    // Hero readouts: an account balance, a headline figure, a live PID value
    // blown up to fill a panel. Large and bold - "numbers stay the hero" (map
    // "Notes") is the literal instruction this role exists to satisfy.
    displayLarge = TextStyle(
        fontFamily = Mono,
        fontWeight = FontWeight.Bold,
        fontSize = 36.sp,
        lineHeight = 39.6.sp,
        letterSpacing = (-0.45).sp,
    ),
    displayMedium = TextStyle(
        fontFamily = Mono,
        fontWeight = FontWeight.Bold,
        fontSize = 27.sp,
        lineHeight = 30.6.sp,
        letterSpacing = (-0.36).sp,
    ),
    displaySmall = TextStyle(
        fontFamily = Mono,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 25.7.sp,
        letterSpacing = (-0.275).sp,
    ),

    // Screen and panel headers: `DeckPane`'s header row, screen titles. Small,
    // bold, caps, tracked - the "stencil caps" register from ticket 01. Smaller
    // than the old Instrument-era headline roles on purpose (avionics headers
    // are labels, not hero copy).
    headlineMedium = TextStyle(
        fontFamily = Mono,
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp,
        lineHeight = 17.5.sp,
        letterSpacing = HeaderLetterSpacing,
    ),
    headlineSmall = TextStyle(
        fontFamily = Mono,
        fontWeight = FontWeight.Bold,
        fontSize = 13.sp,
        lineHeight = 16.7.sp,
        letterSpacing = HeaderLetterSpacing,
    ),
    // SemiBold is not a bundled static (only Regular/Medium/Bold ship in
    // res/font/) - Medium here rather than asking Android to synthesise a
    // weight the face doesn't have.
    titleMedium = TextStyle(
        fontFamily = Mono,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.6.sp,
        letterSpacing = 0.05.em,
    ),

    // Body: merchant names, descriptions, prose. Sized down slightly per the
    // build brief - a dense console reads more text at once than a
    // conventional list screen.
    bodyLarge = TextStyle(
        fontFamily = Mono,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 17.6.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = Mono,
        fontWeight = FontWeight.Normal,
        fontSize = 11.5.sp,
        lineHeight = 15.6.sp,
    ),
    // HELD at the ticket 05 daylight-legibility floor - 11sp is the minimum
    // body size, not a target the 10% pass-down gets to undercut.
    bodySmall = TextStyle(
        fontFamily = Mono,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        lineHeight = 15.sp,
    ),

    // Labels: caps, letterspaced, muted-intent (callers pair these with
    // `LegionSemantics.faint`/`ghost`, not a fixed colour baked in here - see
    // ticket 03's "label carries the meaning, colour reinforces it" rule).
    labelLarge = TextStyle(
        fontFamily = Mono,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.7.sp,
        letterSpacing = HeaderLetterSpacing,
    ),
    labelMedium = TextStyle(
        fontFamily = Mono,
        fontWeight = FontWeight.Normal,
        fontSize = 9.sp,
        lineHeight = 12.6.sp,
        letterSpacing = HeaderLetterSpacing,
    ),
    // HELD at the ticket 05 daylight-legibility floor - 9sp is the minimum
    // label size, not a target the 10% pass-down gets to undercut.
    labelSmall = TextStyle(
        fontFamily = Mono,
        fontWeight = FontWeight.Normal,
        fontSize = 9.sp,
        lineHeight = 12.sp,
        letterSpacing = HeaderLetterSpacing,
    ),
)

/**
 * Type roles Material 3's [Typography] has no slot for. Amounts are the one
 * that matters: an amount is not `bodyMedium` that happens to be mono, it is
 * its own role, and every list of money in this app must use it or the columns
 * stop lining up. Same 10% pass-down as the roles above (ticket 02/13) - these
 * three carry no held floor, ticket 05's floors apply to `bodySmall` and
 * `labelSmall` only.
 */
object LegionType {
    /** A transaction amount in a list. Tabular by construction. */
    val amount = TextStyle(
        fontFamily = Mono,
        fontWeight = FontWeight.Bold,
        fontSize = 12.sp,
        lineHeight = 16.6.sp,
    )

    /** A secondary figure: a live PID value, an item count, a macro number. */
    val reading = TextStyle(
        fontFamily = Mono,
        fontWeight = FontWeight.Normal,
        fontSize = 13.5.sp,
        lineHeight = 18.sp,
    )

    /** A date or unit sitting beneath a description. */
    val stamp = TextStyle(
        fontFamily = Mono,
        fontWeight = FontWeight.Normal,
        fontSize = 9.sp,
        lineHeight = 12.6.sp,
        letterSpacing = 0.45.sp,
    )
}

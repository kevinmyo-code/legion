package com.kevin.midnightai.ui.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The non-color half of Aria's design tokens (palette lives in [AriaColors]).
 * Type roles, spacing/border dimensions, and the UI-sound catalog that together
 * make the retro-futuristic / Showa city-pop language concrete and reusable.
 *
 * Two type "voices" matching the two surfaces:
 *  - **Instrument** (monospace) for neon/amber readouts and labels on the night
 *    surface (also Material's default Typography in [AriaTheme]).
 *  - **Logbook** (serif) for the aged-paper parked-mode pages.
 *
 * Custom fonts are intentionally NOT bundled yet - the families below fall back to
 * the platform monospace/serif so this compiles and reads correctly today. When we
 * add display faces (a retro/city-pop display + a typewriter or warm serif for the
 * logbook) to res/font, swap the [FontFamily] references here in one place.
 */
object AriaType {
    /** Amber/neon instrument readout (gauge values, status). */
    val readout = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 18.sp,
        letterSpacing = 2.sp,
    )

    /** Wide, all-caps city-pop section label (neon header feel). */
    val neonLabel = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 13.sp,
        letterSpacing = 4.sp,
    )

    /** Logbook page heading (aged-paper surface). */
    val logbookHeading = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        letterSpacing = 0.5.sp,
    )

    /** Logbook page body / entries. */
    val logbookBody = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    )

    /** Rubber-stamp style (dates, "PAID", "DUE"), pairs with AriaColors.StampRed. */
    val stamp = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp,
        letterSpacing = 3.sp,
    )
}

/** Spacing, border, and touch-target tokens. Retro favors crisp hairline/bevel borders over soft shadows. */
object AriaDimens {
    val hairline = 1.dp
    val border = 1.5.dp
    val panelBorder = 2.dp
    val pageCorner = 2.dp

    /** Minimum touch target in driving mode - big and glanceable for use at speed. */
    val driveTouchMin = 56.dp

    // --- spacing scale: one rhythm, 4dp base -------------------------------
    // Added 2026-07-29 (UI coherence pass): this object existed with only
    // border widths + a touch-target minimum, and was referenced ZERO times
    // across ui/ - so CLAUDE.md sec 12's "use AriaDimens tokens" rule was
    // literally unfollowable, which is how Cruise ended up with 34 distinct
    // inline dp values with no shared rhythm. This scale is the fix: every
    // new spacing decision should land on one of these five stops rather
    // than inventing another one-off dp.
    val s1 = 4.dp    // tight pairs (label to value)
    val s2 = 8.dp    // within a component
    val s3 = 12.dp   // component gap, COMPACT screen edge
    val s4 = 20.dp   // section gap, EXPANDED screen edge
    val s5 = 32.dp   // major separation
}

/**
 * Catalog of UI-interaction sounds (the Stardew-style tactile feedback) — these
 * play on *interface* actions (a logbook page flip, logging an entry, unlocking
 * the spend gate), NOT on car actions like music/volume (whose effect is already
 * its own feedback). Each name is a res/raw resource resolved at runtime by name,
 * mirroring how the voice-sample clips are loaded.
 *
 * The WAVs themselves are a one-time asset task and are NOT bundled yet (like the
 * voice samples were initially); until then a player should no-op gracefully on a
 * missing resource. Keep these short, warm, and analog (clicks, soft chimes,
 * cassette/paper textures) — never harsh digital beeps.
 */
object AriaSounds {
    const val TAP = "ui_tap"                 // generic button / selection
    const val TOGGLE = "ui_toggle"           // switch / settings toggle
    const val PAGE_FLIP = "ui_page_flip"     // logbook page turn (signature sound)
    const val LOG_CONFIRM = "ui_log_confirm" // an entry committed (build sheet)
    const val UNLOCK = "ui_unlock"           // spend gate opened
    const val LOCK_DENY = "ui_lock_deny"     // wrong passphrase / locked
    const val ERROR = "ui_error"             // something failed

    /** All catalogued sounds, for a one-shot preload / asset-presence check. */
    val ALL = listOf(TAP, TOGGLE, PAGE_FLIP, LOG_CONFIRM, UNLOCK, LOCK_DENY, ERROR)
}

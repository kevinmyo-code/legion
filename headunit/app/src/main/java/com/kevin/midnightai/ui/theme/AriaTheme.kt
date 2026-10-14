package com.kevin.midnightai.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Aria's design language: **retro-futuristic, Showa-era "city pop"** (late-'70s to
 * early-'90s Japanese motoring culture) — see the design-language note. One world,
 * two surfaces:
 *
 *  - **Night / neon** (driving overlay + app chrome): a city-pop night-drive sky,
 *    lit by neon magenta (active) and amber phosphor (instrument). Spare and
 *    glanceable while moving.
 *  - **Aged paper / chrome** (parked-mode data surfaces, esp. the logbook): warm
 *    cream pages, sepia ink, rubber-stamp accents, embossed metal binder hardware.
 *    Rich and tactile, used where the car is stopped.
 *
 * The legacy field names (Background/Panel/ScanRed/Amber/...) are kept so existing
 * screens re-skin in place; their VALUES moved from the old Knight-Rider black/red
 * to this palette. New tokens (neon set, paper set, chrome) are added below, with
 * type/shape/dimens/sound tokens in AriaTokens.kt.
 */
object AriaColors {
    // --- Night / neon surface — delegates to the active AriaPalette preset --
    // All getters read AriaPalette.current (a Compose mutableState), so any
    // composable that reads these properties recomposes live on a palette switch.
    val Background: Color get() = AriaPalette.active.background
    val Panel: Color get() = AriaPalette.active.panel
    val PanelBorder: Color get() = AriaPalette.active.panelBorder

    val ScanRed: Color get() = AriaPalette.active.magenta
    val ScanRedDim: Color get() = AriaPalette.active.magentaDim
    val Amber: Color get() = AriaPalette.active.amber
    val AmberDim: Color get() = AriaPalette.active.amberDim

    val TextPrimary: Color get() = AriaPalette.active.textPrimary
    val TextDim: Color get() = AriaPalette.active.textDim

    // --- Extended city-pop neon set ---------------------------------------
    val NeonMagenta: Color get() = ScanRed
    val NeonCyan: Color get() = AriaPalette.active.cyan
    val NeonViolet: Color get() = AriaPalette.active.violet
    val SunsetOrange: Color get() = AriaPalette.active.sunsetOrange
    val SunsetGold: Color get() = AriaPalette.active.sunsetGold

    /**
     * Colour for TEXT drawn in an accent role (status labels, stamps, link actions).
     * On a light palette whose accents can't carry text (SUMMER_COAST) this is the ink;
     * elsewhere it is [accent] unchanged. Non-text accents (rings, fills) don't use this.
     */
    fun accentText(accent: Color): Color = AriaPalette.active.accentText(accent)

    // --- Aged-paper / logbook surface (constant — paper is paper) ----------
    val Paper = Color(0xFFF3E7CE)
    val PaperAged = Color(0xFFE3CFA4)
    val PaperMasthead = Color(0xFFF7F5EE)   // near-white paper: FM-STATION masthead band, polaroid card
    val Ink = Color(0xFF3A2E22)
    val InkFaded = Color(0xFF6E5C46)
    val StampRed = Color(0xFF9E3B2E)
    val Patina = Color(0xFF2E7D6B)

    // --- Chrome / embossed binder hardware ---------------------------------
    val Chrome = Color(0xFFC9CCD6)
    val ChromeShadow = Color(0xFF6E7080)

    // --- Desk / drawer (Mixtape Manager's MIX tab, constant like paper) ----
    val DeskWood = Color(0xFF6B4A38)
    val DeskWoodDark = Color(0xFF4A3226)
}

private val AriaTypography = Typography().let { base ->
    base.copy(
        headlineLarge = base.headlineLarge.copy(
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            letterSpacing = 4.sp,
        ),
        titleMedium = base.titleMedium.copy(fontFamily = FontFamily.Monospace, letterSpacing = 1.sp),
        titleSmall = base.titleSmall.copy(fontFamily = FontFamily.Monospace),
        labelSmall = base.labelSmall.copy(fontFamily = FontFamily.Monospace, letterSpacing = 2.sp),
        bodySmall = base.bodySmall.copy(fontFamily = FontFamily.Monospace),
        bodyMedium = base.bodyMedium.copy(fontFamily = FontFamily.Monospace),
    )
}

// Mechanical/instrument feel: small corner radii, not soft rounded blobs.
private val AriaShapes = Shapes(
    extraSmall = RoundedCornerShape(2.dp),
    small = RoundedCornerShape(3.dp),
    medium = RoundedCornerShape(4.dp),
    large = RoundedCornerShape(6.dp),
    extraLarge = RoundedCornerShape(8.dp),
)

@Composable
fun AriaTheme(content: @Composable () -> Unit) {
    val colorScheme = darkColorScheme(
        primary = AriaColors.ScanRed,
        onPrimary = Color.Black,
        secondary = AriaColors.Amber,
        onSecondary = Color.Black,
        tertiary = AriaColors.NeonCyan,
        onTertiary = Color.Black,
        background = AriaColors.Background,
        onBackground = AriaColors.TextPrimary,
        surface = AriaColors.Panel,
        onSurface = AriaColors.TextPrimary,
        surfaceVariant = AriaColors.Panel,
        onSurfaceVariant = AriaColors.TextDim,
        outline = AriaColors.PanelBorder,
        error = AriaColors.SunsetOrange,
        onError = Color.Black,
    )
    MaterialTheme(
        colorScheme = colorScheme,
        typography = AriaTypography,
        shapes = AriaShapes,
        content = content,
    )
}

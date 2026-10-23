package com.kevin.legion.ui.dumb

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import com.kevin.legion.dumb.rain.RainPalette

/**
 * Dumb phone mode's palette and type (ADR 0062, Kevin 2026-10-22): Matrix green on black. This was
 * grayscale (prototype A's `.scratch/dumb-phone/research/Main.dc.html`) for a few hours; grayscale was
 * dropped and the green values replace it. The text colours live in [RainPalette] as plain ints so
 * a unit test can check their contrast against the brightest rain under the scrim. Text is never
 * below 13sp and every target is at least 44dp.
 */
@Suppress("MagicNumber") // palette hex literals, the same shape as SoftColors
internal object DumbColors {
    val ground = Color(0xFF000000)
    val text = Color(0xFF000000 or RainPalette.TEXT_RGB.toLong())
    val body = Color(0xFF000000 or RainPalette.BODY_RGB.toLong())
    val dim = Color(0xFF000000 or RainPalette.DIM_RGB.toLong())
    val rule = Color(0xFF0A3D14)
    val outline = Color(0xFF0F8A2C)
    val field = Color(0xFF04140A)

    /** Black laid over the rain behind the text so the list stays readable. */
    val scrim = ground.copy(alpha = RainPalette.SCRIM_ALPHA)
}

internal val DumbMono = FontFamily.Monospace

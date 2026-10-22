package com.kevin.legion.ui.dumb

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily

/**
 * Dumb phone mode's palette and type (prototype A, `.scratch/dumb-phone/research/Main.dc.html`).
 * Grayscale on purpose: the screen should look as dull as the mode is meant to feel. Text is never
 * below 13sp and every target is at least 44dp.
 */
@Suppress("MagicNumber") // palette hex literals, the same shape as SoftColors
internal object DumbColors {
    val ground = Color(0xFF0E0E0E)
    val text = Color(0xFFEDEDED)
    val body = Color(0xFFB5B5B5)
    val dim = Color(0xFF9A9A9A)
    val rule = Color(0xFF2A2A2A)
    val outline = Color(0xFF3A3A3A)
    val field = Color(0xFF1A1A1A)
}

internal val DumbMono = FontFamily.Monospace

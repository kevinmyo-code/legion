package com.kevin.midnightai.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Curated neon swatches for Lights Out customization. A driver can lock the
 * night HUD's numerals or trim to one of these, independent of the active
 * [AriaPalette] — so switching the app-wide palette later doesn't drag Lights
 * Out along with it. Fixed hexes, not palette-derived, same "never a free
 * color wheel" rule as AriaPalette: on-aesthetic swatches only, no RGB picker.
 * CRT_GREEN is the odd one out on purpose — old VFD/LED gauge-cluster green
 * (Jeep XJ dash lighting, monochrome terminal phosphor), not a city-pop tone.
 */
enum class LightsOutSwatch(val label: String, val color: Color) {
    AMBER("AMBER", Color(0xFFFFB02E)),
    CRT_GREEN("CRT GREEN", Color(0xFF39FF6E)),
    MAGENTA("MAGENTA", Color(0xFFFF2E97)),
    CYAN("CYAN", Color(0xFF35E0E6)),
    VIOLET("VIOLET", Color(0xFF8B5CF6)),
    ORANGE("ORANGE", Color(0xFFFF7A3D)),
    GOLD("GOLD", Color(0xFFFFC24B)),
}

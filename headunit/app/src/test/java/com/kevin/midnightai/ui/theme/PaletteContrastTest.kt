package com.kevin.midnightai.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * WCAG 2.x contrast for the text roles the Cruise screen draws, against the surfaces they
 * sit on: the palette background (wallpaper stand-in), the solid panel, and the panel at the
 * deck card's alpha composited over the background (MusicDeck.deckCardColor).
 */
class PaletteContrastTest {

    private fun channel(c: Float): Double =
        if (c <= 0.03928f) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)

    private fun luminance(c: Color): Double =
        0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)

    private fun contrast(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    private fun over(fg: Color, alpha: Float, bg: Color) = Color(
        red = fg.red * alpha + bg.red * (1 - alpha),
        green = fg.green * alpha + bg.green * (1 - alpha),
        blue = fg.blue * alpha + bg.blue * (1 - alpha),
    )

    private fun surfaces(p: NightPalette, cardAlpha: Float) = mapOf(
        "background" to p.background,
        "panel" to p.panel,
        "card" to over(p.panel, cardAlpha, p.background),
    )

    private fun assertReadable(p: NightPalette, cardAlpha: Float, role: String, text: Color) {
        surfaces(p, cardAlpha).forEach { (name, ground) ->
            val r = contrast(text, ground)
            assertTrue("${p.key} $role on $name is ${"%.2f".format(r)}:1", r >= 4.5)
        }
    }

    @Test fun summerCoastTextIsReadable() {
        val p = AriaPalette.SUMMER_COAST
        assertReadable(p, 0.86f, "textPrimary", p.textPrimary)
        assertReadable(p, 0.86f, "textDim", p.textDim)
    }

    @Test fun summerCoastAccentTextFallsBackToInk() {
        val p = AriaPalette.SUMMER_COAST
        listOf(p.magenta, p.amber, p.cyan, p.sunsetOrange).forEach { accent ->
            assertEquals(p.textPrimary, p.accentText(accent))
            assertReadable(p, 0.86f, "accentText", p.accentText(accent))
        }
    }

    @Test fun summerNightIsPurpleAndReadable() {
        val p = AriaPalette.SUMMER_NIGHT
        // Purple: blue and red channels both above green in the ground and the hot accent.
        listOf(p.background, p.panel, p.magenta).forEach { c ->
            assertTrue("${p.key} not purple: $c", c.blue > c.green && c.red > c.green)
        }
        // Light text on dark.
        assertTrue(luminance(p.textPrimary) > luminance(p.background))
        assertReadable(p, 0.78f, "textPrimary", p.textPrimary)
        assertReadable(p, 0.78f, "textDim", p.textDim)
        // Accent-role text keeps the accent at night, so every accent must carry text.
        listOf("magenta" to p.magenta, "amber" to p.amber, "cyan" to p.cyan, "sunsetOrange" to p.sunsetOrange)
            .forEach { (role, accent) ->
                assertEquals(accent, p.accentText(accent))
                assertReadable(p, 0.78f, role, accent)
            }
    }

    @Test fun nightModeSelectsSummerNight() {
        // AriaColors reads `active`; night must resolve to the purple palette whatever is picked.
        AriaPalette.ALL.forEach { picked ->
            assertEquals(AriaPalette.SUMMER_NIGHT, AriaPalette.resolve(night = true, picked = picked))
            assertEquals(picked, AriaPalette.resolve(night = false, picked = picked))
        }
    }

    @Test fun darkPalettesKeepTheirAccentText() {
        val p = AriaPalette.SHOWA_PURPLE
        assertEquals(p.magenta, p.accentText(p.magenta))
    }
}

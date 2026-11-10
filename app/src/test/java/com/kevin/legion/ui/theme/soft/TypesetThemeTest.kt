package com.kevin.legion.ui.theme.soft

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import com.kevin.legion.ui.dumb.MatrixColorScheme
import com.kevin.legion.ui.dumb.MatrixSemantics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/**
 * Typeset (ADR 0081): both editions' Material schemes hold the L11 invariant (every role its own
 * raw value, since `contentColorFor` matches by value), the text tiers read on paper and on the
 * night page, the accent never fills a button, and the edition follows the phone and bedtime.
 */
class TypesetThemeTest {

    private fun roles(s: ColorScheme) = listOf(
        "primary" to s.primary, "secondary" to s.secondary, "tertiary" to s.tertiary,
        "background" to s.background, "error" to s.error, "primaryContainer" to s.primaryContainer,
        "secondaryContainer" to s.secondaryContainer, "tertiaryContainer" to s.tertiaryContainer,
        "errorContainer" to s.errorContainer, "inverseSurface" to s.inverseSurface, "surface" to s.surface,
        "surfaceVariant" to s.surfaceVariant, "outline" to s.outline, "outlineVariant" to s.outlineVariant,
        "scrim" to s.scrim, "surfaceBright" to s.surfaceBright, "surfaceDim" to s.surfaceDim,
        "surfaceContainerLowest" to s.surfaceContainerLowest, "surfaceContainerLow" to s.surfaceContainerLow,
        "surfaceContainer" to s.surfaceContainer, "surfaceContainerHigh" to s.surfaceContainerHigh,
        "surfaceContainerHighest" to s.surfaceContainerHighest, "inversePrimary" to s.inversePrimary,
    )

    private fun assertDistinct(label: String, s: ColorScheme) {
        val seen = mutableMapOf<Color, String>()
        for ((name, color) in roles(s)) {
            val clash = seen[color]
            assertTrue("$label: '$name' collides with '$clash' (the L11 bug)", clash == null)
            seen[color] = name
        }
    }

    @Test fun `day scheme roles are pairwise distinct`() = assertDistinct("day", TypesetDayScheme)

    @Test fun `night scheme roles are pairwise distinct`() = assertDistinct("night", TypesetNightScheme)

    @Test
    fun `text tiers clear 4_5 on the card tier in both editions`() {
        for ((label, p) in listOf("day" to TypesetDayPalette, "night" to TypesetNightPalette)) {
            for ((name, c) in listOf("text" to p.text, "text2" to p.text2, "text3" to p.text3, "onAlert" to p.onAlert,
                "caution" to p.caution, "good" to p.good)) {
                assertContrast("$label $name on card", c, p.card)
                assertContrast("$label $name on ground", c, p.ground)
            }
            assertContrast("$label onPrimaryContainer", p.onPrimaryContainer, p.primaryContainer)
            assertContrast("$label onAlert on alertContainer", p.onAlert, p.alertContainer)
        }
    }

    @Test
    fun `the accent is for flags only, never a button or the talk control`() {
        for (p in listOf(TypesetDayPalette, TypesetNightPalette)) {
            assertEquals(p.text, p.primary)
            assertEquals(p.text, p.primaryContainer)
            assertTrue(p.primaryContainer != p.onAlert)
        }
    }

    @Test
    fun `the prototype's tokens are the values`() {
        assertEquals(Color(0xFFFAF8F3), TypesetDayPalette.ground)
        assertEquals(Color(0xFF1D1A16), TypesetDayPalette.text)
        assertEquals(Color(0xFF6B655B), TypesetDayPalette.text2)
        assertEquals(Color(0xFFDCD6CA), TypesetDayPalette.barRule)
        assertEquals(Color(0xFF8A2D1E), TypesetDayPalette.onAlert)
        assertEquals(Color(0xFF14120F), TypesetNightPalette.ground)
        assertEquals(Color(0xFFF1ECE2), TypesetNightPalette.text)
        assertEquals(Color(0xFFE0907A), TypesetNightPalette.onAlert)
    }

    @Test
    fun `automatic edition follows the phone, and bedtime makes it night`() {
        assertEquals(TypesetEdition.DAY, automaticEdition(systemDark = false, bedtimeActive = false))
        assertEquals(TypesetEdition.NIGHT, automaticEdition(systemDark = true, bedtimeActive = false))
        assertEquals(TypesetEdition.NIGHT, automaticEdition(systemDark = false, bedtimeActive = true))
        assertEquals(TypesetEdition.NIGHT, automaticEdition(systemDark = true, bedtimeActive = true))
    }

    @Test
    fun `a tap switches the edition, and holds while the automatic answer holds`() {
        val sw = EditionSwitch()
        assertEquals(TypesetEdition.DAY, sw.resolve(TypesetEdition.DAY))
        sw.toggle(TypesetEdition.DAY)
        assertEquals(TypesetEdition.NIGHT, sw.resolve(TypesetEdition.DAY))
        assertEquals(TypesetEdition.NIGHT, sw.resolve(TypesetEdition.DAY))
    }

    @Test
    fun `the override ends when the automatic answer changes`() {
        val sw = EditionSwitch()
        sw.toggle(TypesetEdition.DAY) // forced night in the day
        assertEquals(TypesetEdition.NIGHT, sw.resolve(TypesetEdition.NIGHT)) // phone went dark: auto wins
        sw.settle(TypesetEdition.NIGHT) // the host settles on every change of the automatic answer
        assertEquals(TypesetEdition.DAY, sw.resolve(TypesetEdition.DAY)) // and the old tap is gone
    }

    @Test
    fun `a second tap returns to the automatic edition and clears the override`() {
        val sw = EditionSwitch()
        sw.toggle(TypesetEdition.NIGHT)
        assertEquals(TypesetEdition.DAY, sw.resolve(TypesetEdition.NIGHT))
        sw.toggle(TypesetEdition.NIGHT)
        assertEquals(TypesetEdition.NIGHT, sw.resolve(TypesetEdition.NIGHT))
        assertNull(sw.version.value)
    }

    @Test
    fun `a stale override does not survive a round trip of the automatic answer`() {
        val sw = EditionSwitch()
        sw.toggle(TypesetEdition.NIGHT) // day at bedtime
        sw.settle(TypesetEdition.DAY) // bedtime ended
        sw.settle(TypesetEdition.NIGHT) // next bedtime
        assertEquals(TypesetEdition.NIGHT, sw.resolve(TypesetEdition.NIGHT))
    }

    @Test
    fun `soft look is the edition outside Matrix, and today's soft-dark look under Matrix`() {
        assertEquals(TypesetDayPalette, softLook(matrix = false, edition = TypesetEdition.DAY).palette)
        assertEquals(TypesetDayScheme, softLook(matrix = false, edition = TypesetEdition.DAY).scheme)
        assertEquals(TypesetNightPalette, softLook(matrix = false, edition = TypesetEdition.NIGHT).palette)
        assertEquals(TypesetNightScheme, softLook(matrix = false, edition = TypesetEdition.NIGHT).scheme)
        for (e in TypesetEdition.entries) {
            val m = softLook(matrix = true, edition = e)
            assertEquals(SoftDarkPalette, m.palette)
            assertEquals(MatrixColorScheme, m.scheme)
            assertEquals(MatrixSemantics, m.semantics)
        }
    }

    @Test
    fun `an estimate and an alert share the one accent, told apart by words`() {
        for (p in listOf(TypesetDayPalette, TypesetNightPalette)) {
            val s = typesetSemantics(p)
            assertEquals(p.onAlert, s.estimated)
            assertEquals(s.estimated, s.quarantined)
            assertTrue("a marker is ink, not the accent", s.marker != s.estimated)
        }
    }

    @Test
    fun `Typeset sets Source Serif 4 on the one scale, Matrix keeps Figtree`() {
        val day = softLook(matrix = false, edition = TypesetEdition.DAY).typography
        assertEquals(SourceSerif, day.bodyLarge.fontFamily)
        assertEquals(SoftTypography, softLook(matrix = false, edition = TypesetEdition.NIGHT).typography)
        assertEquals(SoftFigtreeTypography, softLook(matrix = true, edition = TypesetEdition.DAY).typography)
        val scale = listOf(TypesetType.caps, TypesetType.small, TypesetType.body, TypesetType.lead,
            TypesetType.fig, TypesetType.hero, TypesetType.time).map { it.fontSize.value }
        assertEquals(listOf(12f, 14f, 17f, 21f, 34f, 64f, 92f), scale)
        val sizes = with(day) {
            listOf(displayLarge, headlineMedium, titleLarge, titleMedium, bodyLarge, bodyMedium, labelMedium)
        }.map { it.fontSize.value }.toSet()
        assertTrue("every Material role sits on the scale: $sizes", sizes.all { it in scale })
        assertEquals(SourceSerifDisplay, TypesetType.time.fontFamily)
    }

    private fun lum(c: Color): Double {
        fun ch(v: Float): Double = if (v <= 0.03928f) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        return 0.2126 * ch(c.red) + 0.7152 * ch(c.green) + 0.0722 * ch(c.blue)
    }

    private fun assertContrast(label: String, fg: Color, bg: Color, min: Double = 4.5) {
        val a = lum(fg)
        val b = lum(bg)
        val ratio = (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
        assertTrue("$label: contrast %.2f < %.1f".format(ratio, min), ratio >= min)
    }
}

package com.kevin.legion.ui.dumb

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.pow

/**
 * The gate for [MatrixColorScheme] (matrix batch item 2), in the shape of `SoftThemeTest`: every
 * background/surface/container role pairwise distinct (L11), every text/background pair at 4.5:1 or
 * better (WCAG 2.1, luminance computed here, no library), and the three alert hues kept off green and
 * apart from each other.
 */
class MatrixSchemeTest {

    private val s = MatrixColorScheme

    @Test
    fun `every background, surface and container role is pairwise distinct`() {
        // Deliberate same-value pairs, excluded as SoftThemeTest excludes them: surfaceTint mirrors
        // primary (never a contentColorFor input) and error/onErrorContainer share one token.
        val roles = linkedMapOf(
            "primary" to s.primary,
            "secondary" to s.secondary,
            "tertiary" to s.tertiary,
            "background" to s.background,
            "error" to s.error,
            "primaryContainer" to s.primaryContainer,
            "secondaryContainer" to s.secondaryContainer,
            "tertiaryContainer" to s.tertiaryContainer,
            "errorContainer" to s.errorContainer,
            "inverseSurface" to s.inverseSurface,
            "surface" to s.surface,
            "surfaceVariant" to s.surfaceVariant,
            "outline" to s.outline,
            "outlineVariant" to s.outlineVariant,
            "scrim" to s.scrim,
            "surfaceBright" to s.surfaceBright,
            "surfaceDim" to s.surfaceDim,
            "surfaceContainerLowest" to s.surfaceContainerLowest,
            "surfaceContainerLow" to s.surfaceContainerLow,
            "surfaceContainer" to s.surfaceContainer,
            "surfaceContainerHigh" to s.surfaceContainerHigh,
            "surfaceContainerHighest" to s.surfaceContainerHighest,
            "inversePrimary" to s.inversePrimary,
        )
        val seen = mutableMapOf<Color, String>()
        for ((name, color) in roles) {
            val clash = seen[color]
            assertTrue("role '$name' ($color) collides with '$clash' (the L11 bug)", clash == null)
            seen[color] = name
        }
        assertEquals(roles.size, seen.size)
    }

    @Test
    fun `every on-colour clears 4_5 against its own role`() {
        val pairs = listOf(
            Triple("onPrimary/primary", s.onPrimary, s.primary),
            Triple("onPrimaryContainer/primaryContainer", s.onPrimaryContainer, s.primaryContainer),
            Triple("onSecondary/secondary", s.onSecondary, s.secondary),
            Triple("onSecondaryContainer/secondaryContainer", s.onSecondaryContainer, s.secondaryContainer),
            Triple("onTertiary/tertiary", s.onTertiary, s.tertiary),
            Triple("onTertiaryContainer/tertiaryContainer", s.onTertiaryContainer, s.tertiaryContainer),
            Triple("onBackground/background", s.onBackground, s.background),
            Triple("onSurface/surface", s.onSurface, s.surface),
            Triple("onSurfaceVariant/surfaceVariant", s.onSurfaceVariant, s.surfaceVariant),
            Triple("inverseOnSurface/inverseSurface", s.inverseOnSurface, s.inverseSurface),
            Triple("onError/error", s.onError, s.error),
            Triple("onErrorContainer/errorContainer", s.onErrorContainer, s.errorContainer),
        )
        pairs.forEach { (name, fg, bg) -> assertContrastAtLeast(fg, bg, name) }
    }

    @Test
    fun `every text colour clears 4_5 on every surface it can sit on`() {
        val grounds = mapOf(
            "background" to s.background,
            "surface" to s.surface,
            "surfaceVariant" to s.surfaceVariant,
            "surfaceBright" to s.surfaceBright,
            "surfaceContainer" to s.surfaceContainer,
            "surfaceContainerHigh" to s.surfaceContainerHigh,
            "surfaceContainerHighest" to s.surfaceContainerHighest,
        )
        val texts = mapOf(
            "onSurface" to s.onSurface,
            "onSurfaceVariant" to s.onSurfaceVariant,
            "primary" to s.primary,
            "secondary" to s.secondary,
            "error" to s.error,
            "tertiary (warning)" to s.tertiary,
            "credit" to MatrixSemantics.credit,
            "debit" to MatrixSemantics.debit,
            "data" to MatrixSemantics.data,
            "estimated (unverified)" to MatrixSemantics.estimated,
            "quarantined" to MatrixSemantics.quarantined,
            "faint" to MatrixSemantics.faint,
            "ghost" to MatrixSemantics.ghost,
            "chrome" to MatrixSemantics.chrome,
            "chromeText" to MatrixSemantics.chromeText,
            "marker" to MatrixSemantics.marker,
        )
        for ((t, fg) in texts) for ((g, bg) in grounds) assertContrastAtLeast(fg, bg, "$t on $g")
    }

    @Test
    fun `alert text clears 4_5 on the alert container`() {
        assertContrastAtLeast(MatrixColors.alert, MatrixColors.alertContainer, "alert on alertContainer")
        assertContrastAtLeast(MatrixColors.warning, MatrixColors.alertContainer, "warning on alertContainer")
        assertContrastAtLeast(MatrixColors.unverified, MatrixColors.alertContainer, "unverified on alertContainer")
    }

    @Test
    fun `error, warning and unverified never read as green`() {
        val green = hue(MatrixColors.text)
        mapOf(
            "error" to s.error,
            "warning" to s.tertiary,
            "unverified" to MatrixSemantics.estimated,
            "quarantined" to MatrixSemantics.quarantined,
        ).forEach { (name, c) ->
            val d = hueDistance(hue(c), green)
            assertTrue("$name sits ${"%.0f".format(d)} degrees from Matrix green; needs 60+", d >= MIN_FROM_GREEN)
        }
    }

    @Test
    fun `error, warning and unverified stay apart from each other`() {
        val alerts = listOf("error" to s.error, "warning" to s.tertiary, "unverified" to MatrixSemantics.estimated)
        for (i in alerts.indices) for (j in i + 1 until alerts.size) {
            val d = hueDistance(hue(alerts[i].second), hue(alerts[j].second))
            val label = "${alerts[i].first} and ${alerts[j].first} are only ${"%.0f".format(d)} degrees apart"
            assertTrue(label, d >= MIN_APART)
        }
    }

    @Test
    fun `quarantine is the error hue and an estimate is not`() {
        assertEquals(s.error, MatrixSemantics.quarantined)
        assertTrue(MatrixSemantics.estimated != s.error && MatrixSemantics.estimated != s.tertiary)
    }

    private fun assertContrastAtLeast(fg: Color, bg: Color, label: String) {
        val ratio = contrastRatio(fg, bg)
        assertTrue("contrast %.2f below 4.5 for %s".format(ratio, label), ratio >= MIN_CONTRAST)
    }

    private companion object {
        const val MIN_CONTRAST = 4.5
        const val MIN_FROM_GREEN = 60.0
        const val MIN_APART = 30.0
    }
}

private fun contrastRatio(a: Color, b: Color): Double {
    val la = relativeLuminance(a)
    val lb = relativeLuminance(b)
    return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
}

private fun relativeLuminance(color: Color): Double {
    fun channel(c: Float): Double {
        val cs = c.toDouble()
        return if (cs <= 0.03928) cs / 12.92 else ((cs + 0.055) / 1.055).pow(2.4)
    }
    return 0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)
}

/** HSV hue in degrees, 0 until 360. */
private fun hue(c: Color): Double {
    val r = c.red.toDouble()
    val g = c.green.toDouble()
    val b = c.blue.toDouble()
    val max = maxOf(r, g, b)
    val delta = max - minOf(r, g, b)
    if (delta == 0.0) return 0.0
    val h = when (max) {
        r -> 60 * (((g - b) / delta).mod(6.0))
        g -> 60 * ((b - r) / delta + 2)
        else -> 60 * ((r - g) / delta + 4)
    }
    return (h + 360) % 360
}

private fun hueDistance(a: Double, b: Double): Double {
    val d = abs(a - b) % 360
    return if (d > 180) 360 - d else d
}

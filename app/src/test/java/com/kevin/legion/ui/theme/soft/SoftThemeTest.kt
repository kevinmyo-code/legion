package com.kevin.legion.ui.theme.soft

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/**
 * The L11 gate for the soft scheme (home-launcher ticket 02, ADR 0051). `ColorScheme.contentColorFor`
 * resolves by VALUE (`ui/theme/Theme.kt`'s own `DarkScheme` audit has the full mechanism and its
 * 2026-08-02 failure: `surface` and `errorContainer` shared a raw value and every screen drew its
 * body text in quarantine red). That audit measured exactly twelve roles as the real input chain on
 * the M3 version it checked; ticket 02 asks for a version-independent guarantee instead ("every
 * background/surface/container slot... `surfaceVariant` must NOT equal `surfaceContainerHigh`"), so
 * this test checks the WHOLE roster [SoftColorScheme] assigns rather than trusting that audit's
 * twelve-role list still matches whatever M3 release this build resolves - see [SoftColorScheme]'s
 * own doc comment for the full reasoning.
 *
 * Also asserts WCAG 2.1 contrast (relative luminance computed here, no library - ticket 02's own
 * instruction) for the on-colour/container pairs and the three text tiers against [SoftColors.card],
 * exactly the pairs the ticket names.
 */
class SoftThemeTest {

    // ---- L11: every background/surface/container role is pairwise distinct ----

    @Test
    fun `every background, surface and container role in the scheme is pairwise distinct`() {
        // Two roles are DELIBERATELY excluded, both documented at their own assignment in
        // SoftTheme.kt: `surfaceTint` mirrors `primary` (ordinary M3 convention - surfaceTint is
        // never itself a contentColorFor input), and `error`/`onErrorContainer` deliberately share
        // ONE token (SoftColors.onAlert serves both jobs by design, same posture as
        // `ui/theme/Theme.kt`'s own credit/debit precedent, "intended, not an oversight").
        val roles = linkedMapOf(
            "primary" to SoftColorScheme.primary,
            "secondary" to SoftColorScheme.secondary,
            "tertiary" to SoftColorScheme.tertiary,
            "background" to SoftColorScheme.background,
            "error" to SoftColorScheme.error,
            "primaryContainer" to SoftColorScheme.primaryContainer,
            "secondaryContainer" to SoftColorScheme.secondaryContainer,
            "tertiaryContainer" to SoftColorScheme.tertiaryContainer,
            "errorContainer" to SoftColorScheme.errorContainer,
            "inverseSurface" to SoftColorScheme.inverseSurface,
            "surface" to SoftColorScheme.surface,
            "surfaceVariant" to SoftColorScheme.surfaceVariant,
            "outline" to SoftColorScheme.outline,
            "outlineVariant" to SoftColorScheme.outlineVariant,
            "scrim" to SoftColorScheme.scrim,
            "surfaceBright" to SoftColorScheme.surfaceBright,
            "surfaceDim" to SoftColorScheme.surfaceDim,
            "surfaceContainerLowest" to SoftColorScheme.surfaceContainerLowest,
            "surfaceContainerLow" to SoftColorScheme.surfaceContainerLow,
            "surfaceContainer" to SoftColorScheme.surfaceContainer,
            "surfaceContainerHigh" to SoftColorScheme.surfaceContainerHigh,
            "surfaceContainerHighest" to SoftColorScheme.surfaceContainerHighest,
            "inversePrimary" to SoftColorScheme.inversePrimary,
        )
        val firstNameForColor = mutableMapOf<Color, String>()
        for ((name, color) in roles) {
            val clash = firstNameForColor[color]
            assertTrue(
                "role '$name' ($color) collides with role '$clash' - contentColorFor resolves by " +
                    "value, so two roles sharing one raw colour is the L11 bug (CLAUDE.md, 2026-08-02)",
                clash == null,
            )
            firstNameForColor[color] = name
        }
        assertEquals(
            "expected ${roles.size} distinct values, found ${firstNameForColor.size}",
            roles.size,
            firstNameForColor.size,
        )
    }

    // ---- WCAG contrast >= 4.5:1, ticket 02's named pairs ----

    @Test
    fun `onPrimaryContainer against primaryContainer clears 4_5`() {
        assertContrastAtLeast(SoftColors.onPrimaryContainer, SoftColors.primaryContainer)
    }

    @Test
    fun `onAlert against alertContainer clears 4_5 (this is also onErrorContainer against errorContainer)`() {
        assertContrastAtLeast(SoftColors.onAlert, SoftColors.alertContainer)
    }

    @Test
    fun `text against card clears 4_5`() {
        assertContrastAtLeast(SoftColors.text, SoftColors.card)
    }

    @Test
    fun `text2 against card clears 4_5`() {
        assertContrastAtLeast(SoftColors.text2, SoftColors.card)
    }

    @Test
    fun `text3 against card clears 4_5`() {
        assertContrastAtLeast(SoftColors.text3, SoftColors.card)
    }

    @Test
    fun `caution against card clears 4_5`() {
        assertContrastAtLeast(SoftColors.caution, SoftColors.card)
    }

    @Test
    fun `onAlert against card clears 4_5`() {
        assertContrastAtLeast(SoftColors.onAlert, SoftColors.card)
    }

    // ---- SoftSemantics: the soft LegionSemantics the shared drill-down primitives read ----

    @Test
    fun `soft semantics keeps its deliberate same-value pair and no other`() {
        // `data` and `debit` share SoftColors.text on purpose: a value is ordinary text and a debit is
        // never accented (the mission-control `credit`/`debit` precedent, intended not accidental).
        // Every OTHER field must differ from every other, or a state that is meant to read differently
        // (estimate vs quarantine vs credit vs marker, faint vs ghost) collapses into one hue.
        val sem = SoftSemantics
        assertEquals(sem.data, sem.debit)
        val fields = linkedMapOf(
            "credit" to sem.credit,
            "debit" to sem.debit,
            "estimated" to sem.estimated,
            "quarantined" to sem.quarantined,
            "rule" to sem.rule,
            "ruleFaint" to sem.ruleFaint,
            "faint" to sem.faint,
            "ghost" to sem.ghost,
            "chrome" to sem.chrome,
            "chromeText" to sem.chromeText,
            "chromeDim" to sem.chromeDim,
            "marker" to sem.marker,
        )
        val seen = mutableMapOf<Color, String>()
        for ((name, color) in fields) {
            val clash = seen[color]
            // `chrome` and `marker` are both SoftColors.primary: chrome is structure/accent, marker is a
            // chart point; neither is ever compared against the other for meaning. Allowed.
            val allowed = (name == "marker" && clash == "chrome")
            assertTrue("semantics '$name' ($color) collides with '$clash'", clash == null || allowed)
            seen[color] = name
        }
    }

    @Test
    fun `estimate and quarantine stay different hues so neither can stand in for the other`() {
        assertTrue(SoftSemantics.estimated != SoftSemantics.quarantined)
        assertTrue(SoftSemantics.estimated != SoftSemantics.credit)
        assertTrue(SoftSemantics.quarantined != SoftSemantics.credit)
    }

    @Test
    fun `every semantic text colour clears 4_5 against card`() {
        val sem = SoftSemantics
        for ((name, color) in listOf(
            "data" to sem.data, "credit" to sem.credit, "estimated" to sem.estimated,
            "quarantined" to sem.quarantined, "faint" to sem.faint, "ghost" to sem.ghost,
            "chromeText" to sem.chromeText, "chrome" to sem.chrome, "marker" to sem.marker,
        )) {
            assertContrastAtLeast(color, SoftColors.card, label = name)
        }
    }

    @Test
    fun `estimate and quarantine text also clear 4_5 against the ground and the alert container`() {
        assertContrastAtLeast(SoftSemantics.estimated, SoftColors.ground, label = "estimated on ground")
        assertContrastAtLeast(
            SoftSemantics.quarantined, SoftColors.alertContainer, label = "quarantined on alertContainer",
        )
    }

    @Test
    fun `soft semantics is not the mission-control semantics`() {
        // Guards the switch itself: if SoftSemantics ever fell back to the old values, SoftTheme
        // would look soft in chrome only and mint everywhere a screen reads a semantic colour.
        assertTrue(SoftSemantics.data != com.kevin.legion.ui.theme.DeckData)
        assertTrue(SoftSemantics.faint != com.kevin.legion.ui.theme.DeckFaint)
        assertTrue(SoftSemantics.chrome != com.kevin.legion.ui.theme.DeckChrome)
    }

    @Test
    fun `every area has its own home icon`() {
        val icons = AreaAccent.entries.map { it.icon }
        assertTrue(icons.all { it != 0 })
        assertEquals("two areas share one icon", icons.size, icons.toSet().size)
    }

    private fun assertContrastAtLeast(foreground: Color, background: Color, minimum: Double = 4.5, label: String = "") {
        val ratio = contrastRatio(foreground, background)
        val message = "contrast %.2f is below the WCAG minimum %.1f for %s against %s"
            .format(ratio, minimum, foreground, background) + if (label.isEmpty()) "" else " ($label)"
        assertTrue(message, ratio >= minimum)
    }
}

/**
 * WCAG 2.1 contrast ratio, computed here rather than pulled from a library (ticket 02's own
 * instruction: "Compute relative luminance in the test; no library"). `(L1 + 0.05) / (L2 + 0.05)`,
 * L1 the lighter of the two relative luminances.
 */
private fun contrastRatio(a: Color, b: Color): Double {
    val la = relativeLuminance(a)
    val lb = relativeLuminance(b)
    val lighter = maxOf(la, lb)
    val darker = minOf(la, lb)
    return (lighter + 0.05) / (darker + 0.05)
}

/** WCAG relative luminance: sRGB-decode each channel, then the standard 0.2126/0.7152/0.0722 weights. */
private fun relativeLuminance(color: Color): Double {
    fun channel(c: Float): Double {
        val cs = c.toDouble()
        return if (cs <= 0.03928) cs / 12.92 else ((cs + 0.055) / 1.055).pow(2.4)
    }
    return 0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)
}

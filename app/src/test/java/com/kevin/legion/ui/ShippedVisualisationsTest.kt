package com.kevin.legion.ui

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Fails when a shipped visualisation loses its renderer.
 *
 * **The failure this exists for is unlike any other guard in this repo.** `quant-viz` ticket 17
 * found two charts that **shipped, were QA'd on a phone, and then silently disappeared** when a
 * later effort rebuilt the screens around them. Not code that never existed, not documentation that
 * lagged - code that worked and was removed by accident. Nothing caught it: the pure layers still
 * compute, are still unit-tested, and now feed nothing. It was found months later by a manual sweep.
 *
 * A unit test cannot render Compose, so this scans SOURCE for the call site - the same shape as
 * `PromptRoleNamingTest`, which caught 183 real leaks by reading files rather than running them.
 *
 * ### What it can and cannot catch, stated plainly
 *
 * **Catches:** a renderer deleted, or a computed field left with no reader. That is exactly what
 * happened twice.
 *
 * **Does NOT catch:** a chart that renders but is bound to the wrong data, or renders nothing
 * because its input is empty. **Presence is the cheap half**, and it is the half that failed here.
 * Screenshot tests would catch more and were rejected in ticket 17's resolution: they are famously
 * noisy on a one-developer project with no CI, and a noisy guard gets disabled - which is worse
 * than no guard, because it still looks like coverage.
 *
 * ### Adding a chart means adding a row here
 *
 * Deliberate. A visualisation nobody registered is one nobody notices losing.
 */
class ShippedVisualisationsTest {

    /**
     * One shipped visualisation: the file that must render it, and the symbol proving it does.
     *
     * [knownMissing] marks the two ticket 17 found. They are **still broken today** - this test
     * asserts they are still missing rather than pretending otherwise, so it can ship without
     * blocking the build on a restoration nobody has done yet. **When someone restores one, this
     * test fails and forces the flag to be flipped**, which is the point: a known gap that quietly
     * heals is a gap nobody records closing.
     */
    private data class Viz(
        val screen: String,
        val symbol: String,
        val what: String,
        val knownMissing: Boolean = false,
    )

    private val registry = listOf(
        // --- the two ticket 17 found, both still absent ---
        Viz(
            "ui/FleetScreen.kt", "DeckMeter",
            "the maintenance due meter - added by quant-viz ticket 05 (7c6a5ca), dropped by the " +
                "mission-control rebuild (a09aa68). DueRowView.fraction still computes and is still " +
                "unit-tested, feeding nothing.",
            knownMissing = true,
        ),
        Viz(
            "ui/FleetScreen.kt", "milesSparkline",
            "the DRIVES miles sparkline - buildMilesSparkline still computes into " +
                "FleetUiState.milesSparkline and no composable reads it.",
            knownMissing = true,
        ),
        // --- visualisations that ARE rendered, and must stay that way ---
        Viz(
            "ui/TodayScreen.kt", "DeckMeter",
            "the INTAKE hero's calorie-pace meter - deliberately dropped by command-center ticket " +
                "01 (`.scratch/command-center/issues/01-home-command-center.md`), which demotes " +
                "INTAKE from Today's hero pane to a plain hero/caption HALF tile (buildIntakeTile), " +
                "the same shape BodyScreen's own demoted INTAKE/SLEEP tiles already use with no " +
                "meter of their own. The meter itself is unharmed - BudgetSection.kt still renders " +
                "one for the ledger pace tick, and DeckMeter's own composable is untouched - only " +
                "Today stopped being one of its call sites.",
            knownMissing = true,
        ),
    )

    private fun sourceRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "app/src/main/java/com/kevin/legion")
            if (candidate.isDirectory) return candidate
            val here = File(dir, "src/main/java/com/kevin/legion")
            if (here.isDirectory) return here
            dir = dir.parentFile
        }
        fail("Could not locate the main source tree - this test must not silently pass.")
        error("unreachable")
    }

    /** True when [symbol] appears on a non-comment line of [screen]. A symbol named only in a
     * comment explaining its absence is exactly the situation here, so comments cannot count. */
    private fun renders(root: File, screen: String, symbol: String): Boolean {
        val f = File(root, screen)
        if (!f.exists()) fail("registered screen does not exist: $screen")
        return f.readLines().any { line ->
            val t = line.trimStart()
            val isComment = t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
            !isComment && line.contains(symbol)
        }
    }

    @Test
    fun `every registered visualisation still has a renderer`() {
        val root = sourceRoot()
        val lost = registry.filter { !it.knownMissing && !renders(root, it.screen, it.symbol) }
        assertTrue(
            "A shipped visualisation lost its renderer. This is the exact failure quant-viz ticket " +
                "17 found twice, months late, by hand:\n" +
                lost.joinToString("\n") { "  ${it.screen} no longer references ${it.symbol} - ${it.what}" },
            lost.isEmpty(),
        )
    }

    @Test
    fun `the two known-missing charts are still missing - flip the flag when restored`() {
        val root = sourceRoot()
        val restored = registry.filter { it.knownMissing && renders(root, it.screen, it.symbol) }
        assertTrue(
            "A chart marked knownMissing is now rendered again. That is good - set knownMissing = " +
                "false so it is guarded from here on, and close quant-viz ticket 17's entry for it:\n" +
                restored.joinToString("\n") { "  ${it.screen} now references ${it.symbol}" },
            restored.isEmpty(),
        )
    }

    @Test
    fun `the registry is not empty and names real files`() {
        // A guard that scans nothing passes trivially - CLAUDE.md §4 rule 6's shape, one layer up.
        assertTrue("registry must not be empty", registry.isNotEmpty())
        val root = sourceRoot()
        registry.forEach {
            assertTrue("registered screen missing: ${it.screen}", File(root, it.screen).exists())
        }
    }
}

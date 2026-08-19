package com.kevin.legion.ui.fleet

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards against the exact failure mode `.scratch/quant-viz/issues/17-silent-regressions.md`
 * documents: `DueRowView.fraction`/`ScheduleRowView.fraction` are pure and have real unit coverage
 * in [FleetRowsTest], so a screen rebuild that stops RENDERING them (rather than stops COMPUTING
 * them) leaves every existing test green. [FleetRowsTest] cannot catch that class of bug by
 * construction - it never touches a `@Composable` - and this codebase has no Compose UI test
 * harness (`CLAUDE.md`'s ui/ section: Compose previews are the closest thing there is, and they are
 * not run in CI or `testDebugUnitTest`). The honest substitute is a plain source-text assertion: read
 * the actual screen file off disk and check that the composable which renders each row type still
 * feeds `.fraction` into a [com.kevin.legion.ui.common.DeckMeter] call, scoped to that composable's
 * own body so a rename or reformat does not spuriously pass - only genuine removal (or moving the
 * call out of the function it belongs to) can fail this.
 *
 * This cannot catch every UI regression - it is a targeted tripwire for these two fields
 * specifically, not a general "did the screen render" check - but a targeted tripwire that can
 * actually fail beats a passing test that was never able to.
 */
class FleetDrilldownsMeterRenderTest {

    /** Module root when Gradle runs `testDebugUnitTest`, same convention [ledger]'s fixture-reading tests use. */
    private val drilldownsSource: String by lazy {
        File("src/main/java/com/kevin/legion/ui/fleet/FleetDrilldowns.kt").readText()
    }

    /**
     * Isolates one top-level function's body by brace-counting from its first `{` - robust to
     * unrelated edits elsewhere in the file, and to the function being reformatted or reordered,
     * but not to the `DeckMeter` call being deleted or moved outside the braces it currently sits
     * inside.
     */
    private fun functionBody(source: String, signature: String): String {
        val start = source.indexOf(signature)
        assertTrue("expected to find `$signature` in FleetDrilldowns.kt - has it been renamed or moved to another file?", start >= 0)
        val openBrace = source.indexOf('{', start)
        var depth = 0
        var i = openBrace
        while (i < source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(openBrace, i + 1)
                }
            }
            i++
        }
        throw AssertionError("unbalanced braces scanning `$signature` - could not find its closing brace")
    }

    @Test
    fun `MaintenanceDrilldownScreen still draws a meter under each due row's fraction`() {
        val body = functionBody(drilldownsSource, "fun MaintenanceDrilldownScreen(")
        assertTrue(
            "DueRowView.fraction is computed and unit-tested (FleetRowsTest) but MaintenanceDrilldownScreen " +
                "no longer feeds it into a DeckMeter - this is the exact regression " +
                ".scratch/quant-viz/issues/17-silent-regressions.md documents. Restore `DeckMeter(row.fraction, ...)` " +
                "inside the due-rows `items(dueRows, ...)` block.",
            Regex("""DeckMeter\(\s*row\.fraction""").containsMatchIn(body),
        )
    }

    @Test
    fun `ScheduleRow still draws a meter under each schedule row's fraction`() {
        val body = functionBody(drilldownsSource, "private fun ScheduleRow(row: ScheduleRowView")
        assertTrue(
            "ScheduleRowView.fraction is computed (buildScheduleRows/toScheduleRow) but ScheduleRow no longer " +
                "feeds it into a DeckMeter. Restore `DeckMeter(row.fraction, ...)` inside ScheduleRow.",
            Regex("""DeckMeter\(\s*row\.fraction""").containsMatchIn(body),
        )
    }
}

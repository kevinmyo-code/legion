package com.kevin.legion.ui.ask

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * ADR 0035 for `show_generated_view`, enforced instead of remembered.
 *
 * **Why this test exists, and it is a specific near-miss rather than a principle.** Until
 * 2026-09-10, `GeneratedViewQueryRunner.run` had exactly ONE production call site in the whole tree:
 * a `DeckPane` welded inside `ui/MetersScreen.kt`. one-home ticket 03b deletes that file. Nothing
 * would have failed - not the compiler, not the suite - and `show_generated_view` would have become
 * a voice-only capability, which ADR 0035 says is not a finished capability. **A grep was the only
 * thing standing between a UI tidy-up and an ADR violation**, and a grep is not a gate.
 *
 * So the invariant is written down where the build can see it: **the query runner is reachable by
 * hand.** It is deliberately a source-tree scan rather than an assertion about a composable. What
 * ADR 0035 protects is not that a particular screen exists - screens move, and this one just did -
 * but that SOME hands path reaches the capability. That is a statement about the codebase, and the
 * codebase is what this reads.
 *
 * The scan is the idiom `ai/PromptRoleNamingTest.kt` and `engine/EngineBoundaryTest.kt` already
 * establish here, including [sourceRoot]'s two-candidate walk so the test works from the repo root
 * and from `app/` alike.
 *
 * **It cannot pass by finding nothing.** An empty source tree fails, a missing runner fails, and
 * zero UI callers fails - CLAUDE.md §4 rule 6's shape ("a check that passes when nothing parsed is
 * not a gate") applied to a structural check rather than to an ingestion one.
 */
class GeneratedViewHandsPathTest {

    private val runnerCall = "GeneratedViewQueryRunner.run("

    @Test
    fun `the generated-view query runner is reachable from a hands path, not only from voice`() {
        val root = sourceRoot()
        val sources = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

        // Rule 6's shape: this check must be unsatisfiable by an empty scan.
        assertTrue(
            "Found ${sources.size} Kotlin sources under $root - the scan found essentially nothing, " +
                "which means this test is not looking at the codebase and would pass no matter what.",
            sources.size > 100,
        )

        val callers = sources.filter { it.readText().contains(runnerCall) }
        assertTrue(
            "No file calls $runnerCall at all. Either the runner was renamed - in which case fix " +
                "this test's `runnerCall` - or the capability was deleted outright.",
            callers.isNotEmpty(),
        )

        val handsCallers = handsCallersAmong(callers.map { it.path })

        if (handsCallers.isEmpty()) {
            fail(
                "ADR 0035: `show_generated_view` has no hands path. " +
                    "$runnerCall is called from ${callers.map { it.name }} but from nothing under " +
                    "`ui/`, so the capability is reachable by voice alone. This is exactly the state " +
                    "deleting `ui/MetersScreen.kt` would have produced before one-home ticket 02 " +
                    "moved the picker to `ui/ask/AskScreen.kt`. Give it a screen again rather than " +
                    "relaxing this test.",
            )
        }
    }

    @Test
    fun `the hands path builds its query from closed enums, never from free text`() {
        // ADR 0035's "not screen parity, and not a second implementation": the hand path may not be
        // able to express anything the voice path could not also be asked to build. The old pane had
        // no free-text field for that reason and the moved one must not grow one - a text box here
        // would be a second query language with no validator behind it.
        val ask = File(sourceRoot(), "ui/ask/AskScreen.kt")
        assertTrue(
            "ui/ask/AskScreen.kt is gone. If the ASK surface moved again, repoint this test; the " +
                "test above is the one that proves a hands path still exists at all.",
            ask.isFile,
        )
        val text = ask.readText()
        for (field in listOf("TextField", "BasicTextField", "OutlinedTextField")) {
            assertTrue(
                "AskScreen has grown a $field. The picker is closed enums by design so it cannot " +
                    "express a query the voice tool would refuse - see this file's own doc comment.",
                !text.contains(field),
            )
        }
        // And it really is driving the shared runner rather than a local copy of the logic.
        assertTrue(
            "AskScreen no longer calls $runnerCall - if it stopped using the shared runner, that is " +
                "the 'second implementation' ADR 0035 forbids.",
            text.contains(runnerCall),
        )
    }

    /**
     * The callers that count as a hands path, given every file that calls the runner.
     *
     * Pulled out as a pure function on purpose. The two tests above read the real tree, so they can
     * only ever demonstrate the CURRENT answer - they would still pass if this predicate were wrong
     * in a way that happened to say yes today. The negative-proof test below feeds it a codebase
     * that does not exist and pins the answer this file exists to give.
     *
     * `ui/` is what "by hand" means: a screen a person can reach and tap. The runner's own package
     * (`service/`) is where the VOICE path lives, so a caller there proves nothing about ADR 0035
     * and is excluded deliberately rather than incidentally.
     */
    private fun handsCallersAmong(paths: List<String>): List<String> =
        paths.filter { it.replace('\\', '/').contains("/com/kevin/legion/ui/") }

    @Test
    fun `the predicate rejects a voice-only tree, so the checks above can actually fail`() {
        // The negative proof, and it is here because the obvious one does not work: deleting the
        // real screen to watch this fail stops the tree COMPILING (MainActivity registers the
        // route), so nothing gets as far as running, and a compile error is a different guarantee
        // from the one claimed here. Tried on 2026-09-10; the predicate is exercised directly
        // instead.
        val voiceOnly = listOf(
            "C:/x/app/src/main/java/com/kevin/legion/service/LiveSessionController.kt",
            "C:/x/app/src/main/java/com/kevin/legion/service/GeneratedViewQueryRunner.kt",
        )
        assertTrue(
            "A tree where only service/ calls the runner must report NO hands path - that is the " +
                "state ADR 0035 forbids, and if this predicate says otherwise the checks above " +
                "cannot fail for the reason they claim.",
            handsCallersAmong(voiceOnly).isEmpty(),
        )

        val withScreen = voiceOnly + "C:/x/app/src/main/java/com/kevin/legion/ui/ask/AskScreen.kt"
        assertTrue("a ui/ caller is a hands path", handsCallersAmong(withScreen).size == 1)

        // Both separators: this suite runs on Windows, where File.path comes back with backslashes,
        // so a forward-slash-only predicate would report "no hands path" on the machine it runs on.
        val windows = listOf("C:\\x\\app\\src\\main\\java\\com\\kevin\\legion\\ui\\ask\\AskScreen.kt")
        assertTrue("backslash paths must resolve too", handsCallersAmong(windows).size == 1)
    }

    private fun sourceRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "app/src/main/java/com/kevin/legion")
            if (candidate.isDirectory) return candidate
            val here = File(dir, "src/main/java/com/kevin/legion")
            if (here.isDirectory) return here
            dir = dir.parentFile
        }
        fail(
            "Could not locate the main source tree from ${System.getProperty("user.dir")} - this " +
                "test cannot silently pass, see its doc comment.",
        )
        error("unreachable")
    }
}

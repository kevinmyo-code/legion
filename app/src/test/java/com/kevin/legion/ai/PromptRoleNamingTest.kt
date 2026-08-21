package com.kevin.legion.ai

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Fails when a string literal the MODEL can see calls the user "the driver".
 *
 * **Why a source-scanning test rather than a constant check.** `AriaBrainHonestyClauseTest` guards
 * one clause by reading one constant, which works because there is one clause. The concierge frame
 * is different: it is a property of EVERY string the model reads, spread over dozens of files, and
 * on 2026-08-20 commit `557c436` renamed 45 literals across three files and believed that was the
 * whole problem. `service/LiveToolbox.kt` was never opened. It held **183 more**, ~149 of them in
 * non-fleet tools - meals, sleep, budget, music, reminders - and tool descriptions go to the model
 * in the system context on EVERY turn, immediately contradicting [ASSISTANT_FRAME].
 *
 * A count is not a check. This is the check.
 *
 * **The allowlist is per-file and every entry carries its reason**, because "driver" is genuinely
 * correct in a few places and a blanket ban would be a lie. Adding a file here is a decision, not
 * housekeeping - if a new prompt surface lands and this test fails, the fix is almost always the
 * rename, not a new entry.
 *
 * Deliberately fails loudly when it cannot find the source tree (CLAUDE.md §4 rule 6: a check that
 * passes when nothing parsed is not a check).
 */
class PromptRoleNamingTest {

    /** File path suffix -> why "driver" inside a literal is correct there. */
    private val allowed = mapOf(
        "vehicle/PidSpec.kt" to
            "OBD-II signal names from the standard itself (\"Driver's demanded engine torque\") - " +
            "these describe whoever is physically driving the car, not the app's user.",
        "ai/MemoryConsolidator.kt" to
            "\"driver\" is a STORED memory-category value (car_anchored/driver/relationship) - " +
            "renaming it in the prompt would orphan every row already written with it.",
        "ai/ReflectionEngine.kt" to
            "Same stored memory-category value as MemoryConsolidator.",
        "data/local/CompanionMemory.kt" to
            "Same stored category value, on the entity that persists it.",
        "data/local/EpisodicTurn.kt" to
            "Role enum value persisted in Room.",
        "vehicle/VehicleController.kt" to
            "A Log.d string. Never reaches the model.",
        "ui/fleet/CarRows.kt" to
            "A @Preview name. Never reaches the model.",
        "ai/OnboardingFlow.kt" to
            "DEFERRED, not accepted: this whole flow is car-framed (\"the driver is at the wheel\", " +
            "\"you are their car\") and needs a rewrite rather than a rename. Its own onboarding " +
            "ticket owns it; see .scratch/proactive-mode/issues/11-reframe-missed-the-toolbox.md.",
        "ai/PersonaTraits.kt" to
            "ORPHANED - CLAUDE.md §10: assemblePersona() has no production caller. Renaming copy " +
            "nothing reads would be noise.",
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
        fail("Could not locate the main source tree from ${System.getProperty("user.dir")} - this " +
            "test cannot silently pass, see its doc comment.")
        error("unreachable")
    }

    /** Character spans of [line] that sit inside a Kotlin string literal. */
    private fun stringSpans(line: String): List<IntRange> {
        val spans = mutableListOf<IntRange>()
        var i = 0
        while (i < line.length) {
            if (line.startsWith("//", i)) break
            if (line[i] == '"') {
                if (line.startsWith("\"\"\"", i)) {
                    val end = line.indexOf("\"\"\"", i + 3)
                    if (end == -1) {
                        spans += i + 3 until line.length
                        break
                    }
                    spans += i + 3 until end
                    i = end + 3
                    continue
                }
                var j = i + 1
                while (j < line.length) {
                    if (line[j] == '\\') { j += 2; continue }
                    if (line[j] == '"') break
                    j++
                }
                spans += i + 1 until minOf(j, line.length)
                i = j + 1
                continue
            }
            i++
        }
        return spans
    }

    private fun isCommentLine(line: String): Boolean {
        val t = line.trimStart()
        return t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
    }

    private val driverWord = Regex("""\bdrivers?\b""", RegexOption.IGNORE_CASE)

    @Test
    fun `no model-visible string calls the user the driver`() {
        val root = sourceRoot()
        val sources = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue(
            "Walked ${root.absolutePath} and found almost no Kotlin - the scan is not running.",
            sources.size > 100,
        )

        val offenders = mutableListOf<String>()
        for (file in sources) {
            val relative = file.absolutePath.replace('\\', '/').substringAfter("com/kevin/legion/")
            if (allowed.keys.any { relative.endsWith(it) }) continue
            file.readLines().forEachIndexed { index, line ->
                if (isCommentLine(line)) return@forEachIndexed
                for (span in stringSpans(line)) {
                    val segment = line.substring(span.first, span.last + 1)
                    if (driverWord.containsMatchIn(segment)) {
                        offenders += "$relative:${index + 1}: ${segment.trim()}"
                    }
                }
            }
        }

        assertTrue(
            "These string literals call the user \"the driver\". The assistant is a concierge " +
                "(CLAUDE.md §1) and every one of these is read by the model, so each one pulls " +
                "the answer back toward driving:\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }
}

package com.kevin.legion.engine

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Enforces backend-erp ticket 18's ruling ("RULED 2026-08-28: the engine SURVIVES, scoped to
 * user-created aspects"): `engine/` is no longer how a BUILT-IN aspect stores its data - places,
 * pantry, fleet, notes, ledger and dates all repointed off it across tickets 15-17 - it is now
 * only the create-an-aspect layer ([com.kevin.legion.service.EngineToolbox], the generated
 * list/detail/form screens, the widget pager) plus a handful of named migration tools.
 *
 * **This is the test that would have caught step 2's near-miss** (a built-in aspect's controller
 * quietly kept reading `RecordStore` after its own doc comment claimed otherwise) and it is the
 * whole point of ticket 18's follow-up: a boundary stated in a ruling and a boundary a test
 * enforces are different things until something like this exists.
 *
 * **Why a source-scanning test rather than a grep.** CLAUDE.md L10: grep is not case-insensitive
 * to intent - several files in the packages below say, IN A DOC COMMENT, that they "no longer
 * touch `RecordStore`" (that sentence itself contains the string `RecordStore`), so a naive grep
 * for the token would flag files that are already correct and teach nobody to trust the result.
 * [stripComments] removes `//` and block-comment spans (KDoc included) before the pattern search
 * runs, so a doc comment describing the old behaviour never trips this test - only a live
 * reference in code does. **Stated limitation, not hidden:** this stripper does not understand
 * string literals, so a string constant that happened to contain the literal text "RecordStore"
 * would also be stripped as if it were a comment, but only if it followed a `//` or a
 * block-comment opener - it does not special-case quotes at all. No file in the scanned packages
 * has ever needed one, checked by hand against every
 * offender this test currently allows (see the allowlist below), and a false pass here would still
 * be caught by [com.kevin.legion.ai.PromptRoleNamingTest]'s much narrower, hand-verified sibling
 * approach the day someone actually adds one.
 *
 * **The allowlist is per-file and every entry carries its reason**, matching
 * [com.kevin.legion.ai.PromptRoleNamingTest]'s own posture: an allowlist entry is a claim, not
 * housekeeping, and a future addition has to justify itself in writing rather than just appending
 * a path.
 */
class EngineBoundaryTest {

    /**
     * Built-in-aspect packages, relative to `com/kevin/legion/`, per ticket 18's own worked list.
     * Deliberately NOT `engine/` in general - `engine/migration/` (one-time copiers) and the rest
     * of `engine/` (RecordStore, the generated UI, EngineToolbox's plumbing) are the layer this
     * test protects, not code it polices. `backend/` (the five reconciles) is excluded the same
     * way: those are configured-transition migration tools, not a built-in aspect's own data path,
     * and ticket 18's ruling names them as a separate, accepted category of engine consumer.
     */
    private val scannedPackages = listOf(
        "location", "pantry", "vehicle", "notes", "ledger", "calendar", "engine/dates",
    )

    /** Relative path suffix -> why a live (non-comment) engine reference there is allowed. */
    private val allowed = mapOf(
        "vehicle/FleetEngineStore.kt" to
            "Genuine live exception, not a migration tool - checked and RULED 2026-08-27/28 " +
            "(backend-erp ticket 16, \"the vehicles half was checked and needed nothing\"). " +
            "ServiceHistory/MaintenanceSchedule in this same file WERE repointed off the engine " +
            "onto legacy tables; only Vehicle identity create/update/delete still dual-writes the " +
            "engine record alongside the legacy mirror, because backend/FleetReconcile.kt (an " +
            "allowed reconcile) has no other source for a Vehicle's origin_guid - fleet has no " +
            "configured write path of its own (ticket 14: fleet is a projection), so leaving the " +
            "engine Vehicle row stale would silently stop new cars reaching Postgres. Flagged in " +
            "ticket 18's own follow-up as a gap in that ticket's \"no built-in aspect reads or " +
            "writes it\" summary, which named the five reconciles but not the write this file " +
            "performs to keep one of them fed.",
        "engine/dates/DatesAgenda.kt" to
            "Reads the engine's dueAt scan for every aspect EXCEPT Dates and Notes " +
            "(EXCLUDED_ENGINE_ASPECTS, see this file's own class doc point 2) - Dates' OWN event " +
            "data lives entirely in the local `events` table since backend-erp ticket 17's " +
            "repoint. What remains is the cross-aspect agenda merge ticket 19 point 3 always " +
            "promised (\"every record's dueAt column, not just Dates\"), which is exactly the " +
            "engine's surviving job under ticket 18's ruling: surfacing a USER-CREATED aspect's " +
            "due dates. Not Dates reading its own data back through the engine.",
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

    /**
     * Removes `//` line comments and block comments (KDoc included) from [text], keeping
     * newlines so any reported line number still lines up with the original file. Does not
     * understand string literals - see this class's own doc comment for why that is a stated,
     * checked limitation rather than a silent gap.
     */
    private fun stripComments(text: String): String {
        val sb = StringBuilder(text.length)
        var i = 0
        var inBlock = false
        while (i < text.length) {
            if (inBlock) {
                if (text.startsWith("*/", i)) {
                    inBlock = false
                    i += 2
                } else {
                    if (text[i] == '\n') sb.append('\n')
                    i++
                }
                continue
            }
            if (text.startsWith("/*", i)) {
                inBlock = true
                i += 2
                continue
            }
            if (text.startsWith("//", i)) {
                val nl = text.indexOf('\n', i)
                if (nl == -1) break
                i = nl // leave the newline itself to be appended on the next iteration
                continue
            }
            sb.append(text[i])
            i++
        }
        return sb.toString()
    }

    private val enginePatterns = listOf(
        "RecordStore" to Regex("""\bRecordStore\b"""),
        "engineRecordDao()" to Regex("""\bengineRecordDao\s*\("""),
        "PayloadCodec" to Regex("""\bPayloadCodec\b"""),
    )

    @Test
    fun `no built-in aspect's production code touches the engine outside the allowlist`() {
        val root = sourceRoot()
        val files = scannedPackages.flatMap { pkg ->
            File(root, pkg).walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        }
        assertTrue(
            "Walked ${scannedPackages.joinToString()} under ${root.absolutePath} and found no " +
                "Kotlin files at all - the scan is not running.",
            files.size > 20,
        )

        val offenders = mutableListOf<String>()
        for (file in files) {
            val relative = file.absolutePath.replace('\\', '/').substringAfter("com/kevin/legion/")
            if (allowed.keys.any { relative.endsWith(it) }) continue

            val code = stripComments(file.readText())
            for ((name, pattern) in enginePatterns) {
                if (pattern.containsMatchIn(code)) {
                    val lineNumber = code.substring(0, pattern.find(code)!!.range.first).count { it == '\n' } + 1
                    offenders += "$relative:$lineNumber references $name in code (not a comment)"
                }
            }
        }

        assertTrue(
            "These built-in-aspect files still touch the engine in live code, contradicting " +
                "backend-erp ticket 18's ruling that no built-in aspect reads or writes it. Either " +
                "the reference is a genuine exception that belongs in this test's allowlist with a " +
                "reason, or it is a real regression:\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }
}

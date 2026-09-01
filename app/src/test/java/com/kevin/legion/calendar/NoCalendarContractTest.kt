package com.kevin.legion.calendar

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Enforces one-today ticket 01's "done means": *"Deleting the redundant fetches is the proof, not
 * a follow-up."* `calendar/CalendarProvider.kt` (the last live `android.provider.CalendarContract`
 * reader/writer) was deleted in the same pass this test was added in - this is what keeps it
 * deleted, the same "a stated boundary and a boundary a test enforces are different things" posture
 * [com.kevin.legion.engine.EngineBoundaryTest] already established for the engine-retirement
 * rulings.
 *
 * **Comment-only mentions of the retired class are expected and allowed** - several doc comments
 * across the calendar-adjacent files explain what USED to call `CalendarContract` and why it no
 * longer does, which is exactly the kind of sentence [stripComments] exists to see past (same
 * reasoning as [com.kevin.legion.engine.EngineBoundaryTest]'s own class doc: a grep for the bare
 * token would flag files that are already correct).
 */
class NoCalendarContractTest {

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

    /** Same comment stripper as [com.kevin.legion.engine.EngineBoundaryTest] - `//` and block
     * comments (KDoc included) removed, newlines kept so a reported line number still lines up. */
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
                i = nl
                continue
            }
            sb.append(text[i])
            i++
        }
        return sb.toString()
    }

    private val pattern = Regex("""\bCalendarContract\b""")

    @Test
    fun `no production Kotlin file references CalendarContract in live code`() {
        val root = sourceRoot()
        val files = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue(
            "Walked ${root.absolutePath} and found no Kotlin files at all - the scan is not running.",
            files.size > 100,
        )

        val offenders = mutableListOf<String>()
        for (file in files) {
            val code = stripComments(file.readText())
            if (pattern.containsMatchIn(code)) {
                val relative = file.absolutePath.replace('\\', '/').substringAfter("com/kevin/legion/")
                val lineNumber = code.substring(0, pattern.find(code)!!.range.first).count { it == '\n' } + 1
                offenders += "$relative:$lineNumber"
            }
        }

        assertTrue(
            "Live CalendarContract reference(s) found - one-today ticket 01 (\"cut Google " +
                "entirely\") deleted the only file that was allowed to touch it " +
                "(calendar/CalendarProvider.kt). Either it is back, or something new reaches for " +
                "it directly instead of the local `events` table:\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }
}

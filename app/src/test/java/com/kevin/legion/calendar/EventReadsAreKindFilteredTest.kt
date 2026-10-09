package com.kevin.legion.calendar

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Why a suggestion can never be read as a plan on the phone (2026-10-09): every reader of the
 * `events` table asks for a kind by name. The two unfiltered reads, `EventDao.getAll()` and
 * `getAllActive()`, are allowed only where every kind genuinely belongs - sync's row matching, and
 * the done-divergence sweep, which filters to tasks itself. A new caller fails here and has to
 * decide, in this list, whether a suggestion belongs in what it reads.
 */
class EventReadsAreKindFilteredTest {

    private val allowed = setOf(
        "backend/EventsSync.kt",
        "backend/EventsDoneDivergenceSweep.kt",
    )

    private fun sourceRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            listOf("app/src/main/java/com/kevin/legion", "src/main/java/com/kevin/legion")
                .map { File(dir, it) }
                .firstOrNull { it.isDirectory }
                ?.let { return it }
            dir = dir.parentFile
        }
        fail("Could not locate the main source tree; this test must not silently pass.")
        error("unreachable")
    }

    @Test
    fun `only the allowed files read events without naming a kind`() {
        val root = sourceRoot()
        val files = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("the scan found no sources", files.size > 100)
        val unfiltered = Regex("""eventDao\(\)\s*\.\s*getAll(Active)?\(""")
        val offenders = files
            .filter { f -> unfiltered.containsMatchIn(stripComments(f.readText())) }
            .map { it.relativeTo(root).invariantSeparatorsPath }
            .filter { it !in allowed }
            .sorted()
        assertEquals("unfiltered events reads outside the allowlist", emptyList<String>(), offenders)
    }

    private fun stripComments(text: String): String =
        text.replace(Regex("""/\*[\s\S]*?\*/"""), "").replace(Regex("""//[^\n]*"""), "")
}

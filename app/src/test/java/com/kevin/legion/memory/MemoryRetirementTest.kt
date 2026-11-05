package com.kevin.legion.memory

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * memory-and-vault ticket 10: the phone stopped distilling memories itself once the engine's
 * `consolidate_memories` job took over (ADR 0075), so nothing is distilled twice. The consolidator
 * and reflection objects stay in the tree (their own tests still run), uncalled; this fails the build
 * if a call, an episodic capture, or the legacy pull comes back.
 */
class MemoryRetirementTest {

    private fun source(relative: String): String =
        (
            File("src/main/java/com/kevin/legion/$relative").takeIf { it.exists() }
                ?: File("app/src/main/java/com/kevin/legion/$relative")
            ).readText()

    /** Code only: comment lines may name what was retired. */
    private fun code(relative: String): String =
        source(relative).lines().filterNot { it.trimStart().let { l -> l.startsWith("//") || l.startsWith("*") } }
            .joinToString("\n")

    @Test
    fun `the consolidator and reflection are not scheduled`() {
        val service = code("service/AriaForegroundService.kt")
        assertFalse(service.contains("MemoryConsolidator.consolidatePending"))
        assertFalse(service.contains("ReflectionEngine.reflectIfDue"))
    }

    @Test
    fun `episodic turns are no longer captured`() {
        assertFalse(code("service/GeminiLiveSession.kt").contains("EpisodicTurn("))
        assertFalse(code("service/TypedTurnRecorder.kt").contains("EpisodicTurn("))
        assertFalse(code("service/GeminiLiveSession.kt").contains("captureEpisodicTurn("))
    }

    @Test
    fun `the legacy memory tables are no longer pulled back into Room`() {
        assertFalse(code("ui/MainActivity.kt").contains("MemorySync.maybeAutoPull"))
        assertFalse(code("MidnightApplication.kt").contains("MemoryRealtime.bind("))
        val main = code("ui/MainActivity.kt")
        assertTrue(main.contains("MemoryController.forContext(applicationContext).refreshIfStale()"))
    }

    @Test
    fun `nothing writes the flat memories table through remember anymore`() {
        assertFalse(code("ai/AriaBrain.kt").contains("MemoryWriteThrough.addMemoryEntry"))
        assertFalse(code("ui/companions/MemoryScreen.kt").contains("MemoryWriteThrough"))
    }
}

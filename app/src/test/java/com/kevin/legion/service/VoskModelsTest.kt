package com.kevin.legion.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The Vosk model is about 70 MB of native memory and has no finalizer: a `Model` dropped without
 * `close()` is never freed. The wake engine loaded a fresh one on every start and dropped it on
 * every stop, and the local command gate loaded a second copy. One process-wide model fixes both.
 */
class VoskModelsTest {

    @Test
    fun `the model loads once however many times it is asked for`() {
        var loads = 0
        val once = OnceModel { dir: File -> loads++; Any() }
        val dir = File("vosk-model")

        val first = once.get(dir)
        val second = once.get(dir)
        val third = once.get(dir)

        assertEquals(1, loads)
        assertSame(first, second)
        assertSame(first, third)
    }

    @Test
    fun `a load that throws is not remembered and the next ask tries again`() {
        var loads = 0
        val once = OnceModel { _: File ->
            loads++
            if (loads == 1) error("model not copied yet") else Any()
        }
        val dir = File("vosk-model")

        val failed = runCatching { once.get(dir) }
        val loaded = once.get(dir)

        assertTrue(failed.isFailure)
        assertEquals(2, loads)
        assertSame(loaded, once.get(dir))
    }

    @Test
    fun `only VoskModels constructs a Vosk model`() {
        val root = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
        val constructs = Regex("""\bModel\(""")
        val offenders = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name != "VoskModels.kt" }
            .filter { f ->
                val text = f.readText()
                text.contains("import org.vosk.Model") && constructs.containsMatchIn(text)
            }
            .map { it.name }
            .toList()
        assertEquals("files that build their own Vosk Model", emptyList<String>(), offenders)
    }
}

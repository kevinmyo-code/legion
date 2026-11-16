package com.kevin.legion.service.cascade

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The scripted lines' audio kept on disk, so "Yes?" is instant even on the first tap after a restart. */
class KeptLineAudioTest {
    private val dir: File = Files.createTempDirectory("kept").toFile()

    @Test fun `a kept line survives the process`() {
        val pcm = ByteArray(960) { it.toByte() }
        KeptLineAudio({ dir })["laptop|model|voice|24000|Yes?"] = pcm
        val restarted = KeptLineAudio({ dir })
        assertArrayEquals(pcm, restarted["laptop|model|voice|24000|Yes?"])
        assertNull(restarted["laptop|model|other-voice|24000|Yes?"])
        dir.deleteRecursively()
    }

    @Test fun `the store stays inside its bounds, least recently played first out`() {
        val store = KeptLineAudio({ dir }, maxFiles = 3, maxBytes = 10_000)
        for (i in 0 until 5) {
            store["k$i"] = ByteArray(1_000)
            File(dir, KeptLineAudio.name("k$i")).setLastModified(1_000_000L + i * 10_000L)
        }
        val left = dir.listFiles { f -> f.name.endsWith(".pcm") }!!.map { it.name }.toSet()
        assertEquals(3, left.size)
        assertTrue(KeptLineAudio.name("k4") in left)
        assertTrue(KeptLineAudio.name("k0") !in left)
        dir.deleteRecursively()
    }

    @Test fun `no directory yet is memory only, never a crash`() {
        val store = KeptLineAudio({ null })
        store["a"] = ByteArray(10)
        assertEquals(10, store["a"]!!.size)
        assertNull(KeptLineAudio({ null })["a"])
    }
}

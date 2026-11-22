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
    @Test fun `with nothing of his own, the APK's clip answers, and is never written to disk`() {
        val shipped = ByteArray(480) { 3 }
        val store = KeptLineAudio({ dir }, bundled = { k -> shipped.takeIf { k == "cloud|m|v|24000|Mm." } })
        assertArrayEquals(shipped, store["cloud|m|v|24000|Mm."])
        assertNull(store["laptop|model|voice|24000|Mm."])
        assertTrue(dir.listFiles().isNullOrEmpty())
        dir.deleteRecursively()
    }

    @Test fun `a render of his own wins over the APK's clip, from memory and from disk`() {
        val shipped = ByteArray(480) { 3 }
        val own = ByteArray(960) { 7 }
        val bundled = { _: String -> shipped }
        KeptLineAudio({ dir }, bundled = bundled)["cloud|m|v|24000|Mm."] = own
        val restarted = KeptLineAudio({ dir }, bundled = bundled)
        assertArrayEquals("from disk, after a restart", own, restarted["cloud|m|v|24000|Mm."])
        val live = KeptLineAudio({ dir }, bundled = bundled)
        assertArrayEquals(shipped, live["cloud|m|v|24000|Other."])
        live["cloud|m|v|24000|Other."] = own
        assertArrayEquals("a fresh render replaces the APK's clip in memory", own, live["cloud|m|v|24000|Other."])
        dir.deleteRecursively()
    }

    @Test fun `the old cache folder moves into app storage once, newer files kept`() {
        val old = File(dir, "cache").apply { mkdirs() }
        val new = File(dir, "files").apply { mkdirs() }
        File(old, "a.pcm").writeBytes(byteArrayOf(1))
        File(old, "b.pcm").writeBytes(byteArrayOf(2))
        File(new, "b.pcm").writeBytes(byteArrayOf(9))
        KeptLineAudio.moveOnce(old, new)
        assertArrayEquals(byteArrayOf(1), File(new, "a.pcm").readBytes())
        assertArrayEquals("the file already in app storage wins", byteArrayOf(9), File(new, "b.pcm").readBytes())
        assertTrue(!old.exists())
        KeptLineAudio.moveOnce(old, new)
        assertEquals(2, new.listFiles()!!.size)
        dir.deleteRecursively()
    }
}

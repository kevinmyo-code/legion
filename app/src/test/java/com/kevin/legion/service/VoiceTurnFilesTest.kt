package com.kevin.legion.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** "Save my turns": the file caps, as a JVM test over a temp folder. Invented audio only (a flat tone). */
class VoiceTurnFilesTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun pcm(ms: Long): ByteArray = ByteArray((VoiceTurnFiles.SAMPLE_RATE * ms / 1000 * 2).toInt()) { 7 }

    private fun wavs(dir: File) = dir.listFiles { f -> f.extension == "wav" }!!.toList()

    @Test
    fun `a turn is written as a 16 kHz wav`() {
        val dir = tmp.newFolder("voice-turns")
        val f = VoiceTurnFiles.write(dir, pcm(1_000), now = 1_000_000L)
        assertNotNull(f)
        val bytes = f!!.readBytes()
        assertEquals("RIFF", String(bytes, 0, 4))
        assertEquals(44 + VoiceTurnFiles.SAMPLE_RATE * 2, bytes.size)
    }

    @Test
    fun `a cough-length turn is not kept`() {
        val dir = tmp.newFolder("voice-turns")
        assertNull(VoiceTurnFiles.write(dir, pcm(VoiceTurnFiles.MIN_MS - 100), now = 1L))
        assertEquals(0, VoiceTurnFiles.count(dir))
    }

    @Test
    fun `old turns go first when over the count cap`() {
        val dir = tmp.newFolder("voice-turns")
        val now = 10_000_000L
        (1..5).forEach { i ->
            File(dir, "t$i.wav").apply { writeBytes(ByteArray(100)); setLastModified(now - (10 - i) * 1_000L) }
        }
        VoiceTurnFiles.prune(dir, now, maxFiles = 3, maxBytes = Long.MAX_VALUE)
        assertEquals(setOf("t3.wav", "t4.wav", "t5.wav"), wavs(dir).map { it.name }.toSet())
    }

    @Test
    fun `the byte cap holds too`() {
        val dir = tmp.newFolder("voice-turns")
        val now = 10_000_000L
        (1..4).forEach { i ->
            File(dir, "t$i.wav").apply { writeBytes(ByteArray(1_000)); setLastModified(now - (10 - i) * 1_000L) }
        }
        VoiceTurnFiles.prune(dir, now, maxFiles = 100, maxBytes = 2_500)
        assertEquals(setOf("t3.wav", "t4.wav"), wavs(dir).map { it.name }.toSet())
    }

    @Test
    fun `turns older than the age cap are deleted`() {
        val dir = tmp.newFolder("voice-turns")
        val now = VoiceTurnFiles.MAX_AGE_MS * 2
        File(dir, "old.wav").apply { writeBytes(ByteArray(10)); setLastModified(now - VoiceTurnFiles.MAX_AGE_MS - 1) }
        File(dir, "new.wav").apply { writeBytes(ByteArray(10)); setLastModified(now - 1_000) }
        VoiceTurnFiles.prune(dir, now)
        assertEquals(listOf("new.wav"), wavs(dir).map { it.name })
    }

    @Test
    fun `delete all empties the folder`() {
        val dir = tmp.newFolder("voice-turns")
        VoiceTurnFiles.write(dir, pcm(1_000), now = 5L)
        VoiceTurnFiles.deleteAll(dir)
        assertEquals(0, VoiceTurnFiles.count(dir))
        assertTrue(dir.exists())
    }
}

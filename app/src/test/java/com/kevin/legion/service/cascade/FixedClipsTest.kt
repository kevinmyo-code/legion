package com.kevin.legion.service.cascade

import com.kevin.legion.service.Farewells
import com.kevin.legion.service.ListenCue
import java.io.File
import java.security.MessageDigest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The cloud voice's clips shipped in the APK (`assets/fixed_clips/`, `tools/render_fixed_clips.py`):
 * every file is the one its manifest pins (a changed clip fails here and shows in review), the set is
 * exactly the phone's own lists, and each clip is in the model, voice and format the phone plays and
 * passes the length gate the phone applies. Robolectric only for `org.json`.
 */
@RunWith(RobolectricTestRunner::class)
class FixedClipsTest {
    private val dir = listOf("src/main/assets/${BundledLines.DIR}", "app/src/main/assets/${BundledLines.DIR}")
        .map(::File).first { it.isDirectory }
    private val manifest = JSONObject(File(dir, "manifest.json").readText())
    private val clips = manifest.getJSONArray("clips").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }

    private fun pcmOf(c: JSONObject): ByteArray = ListenCue.wavPcm(File(dir, c.getString("file")).readBytes())!!

    @Test fun `every clip matches its pinned sha256`() {
        for (c in clips) {
            val bytes = File(dir, c.getString("file")).readBytes()
            val got = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            assertEquals(c.getString("file"), c.getString("sha256"), got)
        }
        val listed = clips.map { it.getString("file") }.toSet()
        val onDisk = dir.listFiles { f -> f.name.endsWith(".wav") }!!.map { it.name }.toSet()
        assertEquals("a clip on disk the manifest does not pin, or the reverse", listed, onDisk)
    }

    @Test fun `rendered in the model, voice and rate the cloud voice speaks`() {
        assertEquals(CloudSpeechWire.TTS_MODEL, manifest.getString("model"))
        assertEquals(CloudSpeechWire.VOICE, manifest.getString("voice"))
        assertEquals(CloudSpeechWire.OUTPUT_RATE, manifest.getInt("rate"))
        for (c in clips) {
            val pcm = ListenCue.wavPcm(File(dir, c.getString("file")).readBytes())
            assertNotNull("${c.getString("file")} is not 24 kHz mono 16-bit", pcm)
        }
    }

    @Test fun `exactly the phone's acknowledgements, wait lines, goodbyes, missed and voice-lost lines`() {
        val want = (Backchannel.LINES.flatMap { l -> (0 until Backchannel.TAKES).map { l to it } } +
            WaitFillers.LINES.flatMap { l -> (0 until WaitFillers.TAKES).map { l to it } } +
            Farewells.SPOKEN.map { it to 0 } + (ScriptedLines.MISSED to 0) + (ScriptedLines.VOICE_LOST to 0)).toSet()
        assertEquals(want, clips.map { it.getString("line") to it.getInt("take") }.toSet())
        assertEquals("one file per line and take", want.size, clips.size)
        for ((line, _) in want) {
            assertTrue("$line is in the kept lines", line in ScriptedLines.KEEP)
            // The voice is asked for exactly this text, so the key the phone looks up is the manifest's.
            assertEquals(line, SpokenText.forSpeech(line))
            assertEquals(line, TtsTags.forSpeech(line, TtsTags.Budget(), plain = true))
        }
    }

    @Test fun `each acknowledgement and wait line passes the phone's own length gate`() {
        for (c in clips) {
            val rate = CloudSpeechWire.OUTPUT_RATE
            val ms = Backchannel.trimSilence(pcmOf(c), rate).size * 1_000L / (rate * 2)
            when (c.getString("line")) {
                in Backchannel.LINES -> assertTrue("${c.getString("file")} $ms ms", Backchannel.clipFits(ms))
                in WaitFillers.LINES -> assertTrue("${c.getString("file")} $ms ms", WaitFillers.clipFits(ms))
                else -> assertTrue("${c.getString("file")} is silent", ms > 0)
            }
        }
    }

    @Test fun `the APK carries well under two megabytes of them`() {
        val bytes = dir.listFiles()!!.sumOf { it.length() }
        assertTrue("$bytes bytes", bytes < 2L * 1024 * 1024)
    }

    @Test fun `the manifest files each clip under the key the cloud voice looks up`() {
        val keys = BundledLines.parse(manifest.toString())
        assertEquals(clips.size, keys.size)
        fun keyFor(voice: String) =
            BundledLines.cloudKey(CloudSpeechWire.TTS_MODEL, voice, CloudSpeechWire.OUTPUT_RATE, "Mm.", 1)
        val key = keyFor(CloudSpeechWire.VOICE)
        assertEquals("ack_mm_1.wav", keys[key])
        val store = BundledLines { path -> File(dir.parentFile, path).takeIf { it.isFile }?.readBytes() }
        assertNotNull(store[key])
        assertEquals("another voice never plays these", null, store[keyFor("Puck")])
    }
}

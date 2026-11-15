@file:Suppress("MaxLineLength", "MagicNumber") // a by-hand measurement: one-line prints

package com.kevin.legion.service.cascade

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * NOT part of the suite: a by-hand measurement of the cloud fallback's speech against real Gemini,
 * on a key read from the file named by `LEGION_GEMINI_KEY_FILE` (never printed). Skipped unless
 * that is set. Costs a fraction of a cent: three short TTS clips and three transcriptions of them.
 *
 * Prints, per invented sentence: the voice request shape that worked, TTS first-audio and total
 * time, audio length and token counts, then the transcription's time, tokens and text. The STT input
 * is the TTS output resampled to 16 kHz, so it measures the request, not a real microphone.
 */
@RunWith(RobolectricTestRunner::class)
class CloudVoiceLiveCheck {

    @Test
    fun `measure Gemini speech for the cloud fallback`() = runBlocking<Unit> {
        val keyFile = System.getenv("LEGION_GEMINI_KEY_FILE").orEmpty()
        assumeTrue("LEGION_GEMINI_KEY_FILE not set: skipped", keyFile.isNotBlank())
        val key = File(keyFile).readText().trim()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val transport = OkHttpGemini(key = { key })
        // The cost-audit lines too, so G1 confirms the usageMetadata field names (counts only).
        val tts = GeminiTts(transport, scope, log = { println("LIVECHECK $it") }, usage = { println("LIVECHECK ${it.line()}") })
        val stt = GeminiStt(transport, scope, log = { println("LIVECHECK $it") }, usage = { println("LIVECHECK ${it.line()}") })
        try {
            for (line in SENTENCES) {
                val t0 = System.currentTimeMillis()
                val clip = tts.speak(line)
                val pcm = ByteArrayOutputStream()
                var first = -1L
                for (f in clip.frames) {
                    if (first < 0) first = System.currentTimeMillis() - t0
                    pcm.write(f)
                }
                val o = clip.outcome()
                println("LIVECHECK tts outcome=$o first_audio_ms=$first total_ms=${System.currentTimeMillis() - t0} bytes=${pcm.size()}")
                if (pcm.size() == 0) continue
                val audio16k = downsample24to16(pcm.toByteArray())
                val t1 = System.currentTimeMillis()
                val r = stt.transcribe(audio16k)
                println("LIVECHECK stt ms=${System.currentTimeMillis() - t1} result=$r")
            }
        } finally {
            scope.cancel()
        }
    }

    /** 24 kHz to 16 kHz PCM16 mono by linear interpolation: good enough for a request-time check. */
    private fun downsample24to16(pcm: ByteArray): ByteArray {
        val n = pcm.size / 2
        val samples = ShortArray(n) { ((pcm[2 * it].toInt() and 0xff) or (pcm[2 * it + 1].toInt() shl 8)).toShort() }
        val outN = n * 2 / 3
        val out = ByteArray(outN * 2)
        for (i in 0 until outN) {
            val src = i * 1.5
            val a = src.toInt().coerceAtMost(n - 1)
            val b = (a + 1).coerceAtMost(n - 1)
            val frac = src - a
            val v = (samples[a] * (1 - frac) + samples[b] * frac).toInt()
            out[2 * i] = (v and 0xff).toByte()
            out[2 * i + 1] = ((v shr 8) and 0xff).toByte()
        }
        return out
    }

    private companion object {
        val SENTENCES = listOf(
            "Listening.",
            "Added oat milk to the grocery list.",
            "The invented parcel from Testland arrives on the fourteenth at half past two.",
        )
    }
}

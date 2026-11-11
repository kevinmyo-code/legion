package com.kevin.legion.service.cascade

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.math.PI
import kotlin.math.sin

/**
 * NOT part of the suite: a by-hand check of this client against a laptop voice service running on
 * this machine. Skipped unless `LEGION_VOICE_URL` is set (e.g. `http://127.0.0.1:8765`); the token
 * is read from `LEGION_VOICE_TOKEN_FILE` and never printed. Sends invented audio (a tone and
 * silence) and one invented sentence, and prints the timings.
 */
@RunWith(RobolectricTestRunner::class)
class LaptopServiceLiveCheck {

    @Test
    fun `measure against a running service`() = runBlocking<Unit> {
        val url = System.getenv("LEGION_VOICE_URL").orEmpty()
        val tokenFile = System.getenv("LEGION_VOICE_TOKEN_FILE").orEmpty()
        assumeTrue("LEGION_VOICE_URL not set: skipped", url.isNotBlank() && tokenFile.isNotBlank())
        val config = LaptopVoiceConfig(url, File(tokenFile).readText().trim())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val probe = LaptopProbe.probe(config, 3_000)
            println("LIVECHECK probe=${probe.token} ${(probe as? LaptopProbe.Result.Ready)?.ms ?: ""}")
            val c = LaptopVoiceConnection(OkHttpWire(), config, "legion-phone/livecheck", scope)
            val t0 = System.currentTimeMillis()
            val open = c.open(5_000)
            println("LIVECHECK open=$open open_ms=${System.currentTimeMillis() - t0} end_of_turn=${c.endsTurns} text_streaming=${c.streamsText} rate=${c.sampleRate}")
            if (open !is LaptopVoiceConnection.OpenResult.Ready) return@runBlocking

            // STT: 1.2 s of an invented 220 Hz tone then 1.5 s of silence, in 100 ms chunks, real time.
            var serviceEnded = false
            val u = c.begin { serviceEnded = true }
            for (i in 0 until 27) {
                u.audio(chunk(i, tone = i < 12), 0, 3_200)
                kotlinx.coroutines.delay(100)
            }
            val endAt = System.currentTimeMillis()
            if (!serviceEnded) u.end()
            val stt = u.result(8_000)
            println("LIVECHECK stt=$stt service_ended=$serviceEnded final_after_end_ms=${System.currentTimeMillis() - endAt}")

            // TTS: one sentence, then (if offered) the same text streamed.
            val s0 = System.currentTimeMillis()
            val clip = c.speak("Testing one two, this is an invented sentence.")
            var first = -1L
            var bytes = 0
            withTimeout(15_000) {
                for (f in clip.frames) {
                    if (first < 0) first = System.currentTimeMillis() - s0
                    bytes += f.size
                }
            }
            println("LIVECHECK tts=${clip.outcome()} first_audio_ms=$first audio_ms=${bytes / 2 * 1000L / c.sampleRate} total_ms=${System.currentTimeMillis() - s0}")
            if (c.streamsText) {
                val st0 = System.currentTimeMillis()
                val sc = c.openStream()
                for (w in "Streaming words one at a time. ".split(" ")) sc.append("$w ")
                sc.finish()
                var sFirst = -1L
                withTimeout(15_000) { for (f in sc.frames) if (sFirst < 0) sFirst = System.currentTimeMillis() - st0 }
                println("LIVECHECK tts_stream=${sc.outcome()} first_audio_ms=$sFirst")
            }
            // Cancel mid-clip.
            val cc = c.speak("A longer invented sentence that will be cut off part of the way through, on purpose.")
            withTimeout(10_000) { cc.frames.receive() }
            cc.cancel()
            println("LIVECHECK cancel=${cc.outcome()}")
            c.close()
        } finally {
            scope.cancel()
        }
    }

    private fun chunk(i: Int, tone: Boolean): ByteArray {
        val out = ByteArray(3_200)
        if (!tone) return out
        for (n in 0 until 1_600) {
            val v = (sin(2 * PI * 220 * (i * 1_600 + n) / 16_000.0) * 6_000).toInt()
            out[2 * n] = (v and 0xff).toByte()
            out[2 * n + 1] = ((v shr 8) and 0xff).toByte()
        }
        return out
    }
}

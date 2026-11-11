@file:Suppress("MaxLineLength", "MagicNumber", "LongMethod", "TooGenericExceptionCaught")

package com.kevin.legion.service.cascade

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.RandomAccessFile

/**
 * By-hand end-to-end check of THIS client (the same [LaptopVoiceConnection], [LaptopProbe] and
 * [ReplySpeaker] the phone runs) against a laptop voice service on this machine. Not a test: run
 * with a plain JVM (no Gradle, so the service has the memory) as
 *
 *   java -cp <classes + kotlin/coroutines/okhttp/okio/org.json jars> \
 *     com.kevin.legion.service.cascade.LaptopLiveCheckMainKt <url> <token file> <wav dir> [n]
 *
 * Sends invented 16 kHz speech (the spike's synthetic utterances) paced in real time, then a
 * canned reply through the voice, a cancel, and a fallback probe. Prints `LIVECHECK` lines. Never
 * prints the token.
 */
fun main(args: Array<String>) = runBlocking {
    val url = args[0]
    val token = File(args[1]).readText().trim()
    val wavs = File(args[2]).listFiles { f -> f.name.endsWith(".wav") && !f.name.contains("noisy") }!!.sortedBy { it.name }
    val n = args.getOrNull(3)?.toInt() ?: 6
    val config = LaptopVoiceConfig(url, token)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    fun out(s: String) = println("LIVECHECK $s")
    try {
        // 1. Probe and open, with the phone's own timeouts.
        val probe = LaptopProbe.probe(config, CascadeTimings.PROBE_TIMEOUT_MS)
        out("probe=${probe.token} ms=${(probe as? LaptopProbe.Result.Ready)?.ms} timeout_ms=${CascadeTimings.PROBE_TIMEOUT_MS}")
        val c = LaptopVoiceConnection(OkHttpWire(), config, "legion-phone/livecheck", scope)
        val t0 = System.currentTimeMillis()
        val open = c.open(CascadeTimings.OPEN_TIMEOUT_MS)
        out("open=$open open_ms=${System.currentTimeMillis() - t0} end_of_turn=${c.endsTurns} text_streaming=${c.streamsText} rate=${c.sampleRate}")
        if (open !is LaptopVoiceConnection.OpenResult.Ready) return@runBlocking

        // 2. Turns: invented speech in real time, then silence until the service ends the turn
        //    (or the phone's 2.5 s cap, as the session would).
        val eots = mutableListOf<Long>()
        val finals = mutableListOf<Long>()
        for (wav in wavs.take(n)) {
            val pcm = readPcm(wav)
            var serviceEndAt = 0L
            val u = c.begin { serviceEndAt = System.currentTimeMillis() }
            var i = 0
            while (i < pcm.size) {
                val len = minOf(3_200, pcm.size - i)
                u.audio(pcm, i, len)
                i += len
                delay(100)
            }
            val speechEnd = System.currentTimeMillis()
            val silence = ByteArray(3_200)
            var source = "service"
            while (serviceEndAt == 0L) {
                if (System.currentTimeMillis() - speechEnd >= 2_500) {
                    source = "phone_cap"
                    u.end()
                    break
                }
                u.audio(silence, 0, silence.size)
                delay(100)
            }
            val decided = if (serviceEndAt > 0) serviceEndAt else System.currentTimeMillis()
            val r = u.result(CascadeTimings.STT_FINAL_TIMEOUT_MS)
            val finalAt = System.currentTimeMillis()
            eots.add(decided - speechEnd)
            finals.add(finalAt - speechEnd)
            out("turn file=${wav.name} eot_source=$source eot_after_speech_ms=${decided - speechEnd} final_after_speech_ms=${finalAt - speechEnd} result=$r")
        }
        out("eot_after_speech_ms median=${median(eots)} max=${eots.maxOrNull()} final_after_speech_ms median=${median(finals)} n=${eots.size}")

        // 3. One live utterance at a time: he speaks again before the last final arrived.
        run {
            val a = readPcm(wavs[0])
            val b = readPcm(wavs[1])
            val u1 = c.begin()
            var i = 0
            while (i < a.size) { u1.audio(a, i, minOf(3_200, a.size - i)); i += 3_200; delay(100) }
            u1.end()
            val u2 = c.begin()
            i = 0
            while (i < b.size) { u2.audio(b, i, minOf(3_200, b.size - i)); i += 3_200; delay(100) }
            u2.end()
            val r1 = u1.result(CascadeTimings.STT_FINAL_TIMEOUT_MS)
            val r2 = u2.result(CascadeTimings.STT_FINAL_TIMEOUT_MS)
            out("back_to_back first=$r1 second=$r2")
        }

        // 4. A canned reply through the phone's own ReplySpeaker (streamed text when offered).
        suspend fun speakCanned(text: String, interruptAfterFirst: Boolean): String {
            var bytes = 0L
            var firstAt = 0L
            val start = System.currentTimeMillis()
            lateinit var sp: ReplySpeaker
            sp = ReplySpeaker(
                tts = c,
                scope = scope,
                play = { pcm -> bytes += pcm.size; delay(pcm.size * 1000L / 2 / c.sampleRate); true },
                firstFrameTimeoutMs = CascadeTimings.TTS_FIRST_AUDIO_TIMEOUT_MS,
                onFirstAudio = { firstAt = System.currentTimeMillis() },
                onClipStarted = {},
            )
            for (w in text.split(" ")) { sp.text("$w "); delay(30) } // about the model's streaming pace
            sp.finishText()
            if (interruptAfterFirst) {
                while (firstAt == 0L && System.currentTimeMillis() - start < 5_000) delay(10)
                delay(300)
                val cut = System.currentTimeMillis()
                sp.interrupt()
                val o = withTimeoutOrNull(3_000) { sp.await() }
                return "outcome=$o first_audio_ms=${if (firstAt > 0) firstAt - start else -1} stop_ms=${System.currentTimeMillis() - cut} heard=\"${sp.spoken()}\""
            }
            val o = withTimeoutOrNull(30_000) { sp.await() }
            val audioMs = bytes / 2 * 1000 / c.sampleRate
            return "outcome=$o first_audio_ms=${if (firstAt > 0) firstAt - start else -1} audio_ms=$audioMs total_ms=${System.currentTimeMillis() - start}"
        }
        out("reply " + speakCanned("Twelve minutes, running. I'll tell you when it's done.", false))
        out("reply_long " + speakCanned(List(12) { "This is invented sentence number ${it + 1} of a long answer." }.joinToString(" "), false))
        out("reply_cancel " + speakCanned("A longer invented answer that will be cut off part of the way through, on purpose, to time the stop.", true))

        // 5. One sentence by tts.speak, as the per-sentence path.
        run {
            val s0 = System.currentTimeMillis()
            val clip = c.speak("Your next oil change is due in about eleven hundred miles.")
            var first = -1L
            withTimeoutOrNull(10_000) { for (f in clip.frames) if (first < 0) first = System.currentTimeMillis() - s0 }
            out("speak_one outcome=${clip.outcome()} first_audio_ms=$first")
        }
        c.close()

        // 6. Fallback: nothing listening (Tailscale off). The phone's probe answers and gives up.
        val dead = LaptopProbe.probe(LaptopVoiceConfig("http://127.0.0.1:1", token), CascadeTimings.PROBE_TIMEOUT_MS)
        out("fallback_probe=${dead.token} refusal=${CascadeRules.openRefusal(true, true, dead)} words=\"${VoiceEngineSettings.statusWords(dead.token)}\"")
    } finally {
        scope.cancel()
    }
}

private fun median(xs: List<Long>): Long? = xs.sorted().let { if (it.isEmpty()) null else it[it.size / 2] }

/** 16 kHz mono PCM16 WAV data (skips the RIFF header). */
private fun readPcm(f: File): ByteArray {
    RandomAccessFile(f, "r").use { raf ->
        val bytes = ByteArray(raf.length().toInt())
        raf.readFully(bytes)
        var p = 12
        while (p + 8 <= bytes.size) {
            val id = String(bytes, p, 4)
            val size = (bytes[p + 4].toInt() and 0xff) or ((bytes[p + 5].toInt() and 0xff) shl 8) or
                ((bytes[p + 6].toInt() and 0xff) shl 16) or ((bytes[p + 7].toInt() and 0xff) shl 24)
            if (id == "data") return bytes.copyOfRange(p + 8, minOf(bytes.size, p + 8 + size))
            p += 8 + size
        }
        error("no data chunk in ${f.name}")
    }
}

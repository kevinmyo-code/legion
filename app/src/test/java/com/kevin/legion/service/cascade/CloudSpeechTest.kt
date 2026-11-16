@file:Suppress("MaxLineLength", "ReturnCount", "EmptyFunctionBlock") // test fixtures: invented JSON and wire frames read best on one line

package com.kevin.legion.service.cascade

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * The cloud fallback's speech ([GeminiStt], [GeminiTts], [CloudSpeech], [CloudSpeechWire]) against a
 * fake Gemini transport. Invented speech and text only. Robolectric only for `org.json`.
 */
@RunWith(RobolectricTestRunner::class)
class CloudSpeechTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** A transport that answers each POST through [answer] and records model and body. */
    private class FakeGemini(
        val answer: suspend (model: String, body: JSONObject, onEvent: (JSONObject) -> Unit) -> StreamOutcome,
    ) : GeminiStreamTransport {
        val calls = Collections.synchronizedList(mutableListOf<Pair<String, JSONObject>>())
        override suspend fun stream(model: String, body: JSONObject, onEvent: (JSONObject) -> Unit): StreamOutcome {
            calls.add(model to JSONObject(body.toString()))
            return answer(model, body, onEvent)
        }
    }

    private fun textEv(t: String) = JSONObject().put(
        "candidates",
        JSONArray().put(JSONObject().put("content", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", t))))),
    )

    private fun audioEv(vararg chunks: ByteArray) = JSONObject().put(
        "candidates",
        JSONArray().put(
            JSONObject().put(
                "content",
                JSONObject().put(
                    "parts",
                    JSONArray().apply {
                        chunks.forEach { put(JSONObject().put("inlineData", JSONObject().put("mimeType", "audio/L16;codec=pcm;rate=24000").put("data", it.toByteString().base64()))) }
                    },
                ),
            ),
        ),
    )

    private fun usageEv(prompt: Int, out: Int) =
        JSONObject().put("usageMetadata", JSONObject().put("promptTokenCount", prompt).put("candidatesTokenCount", out))

    private fun pcm(ms: Int) = ByteArray(ms * 32) { (it % 7).toByte() }

    private suspend fun collect(clip: SpeechClip): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        for (f in clip.frames) out.write(f)
        return out.toByteArray()
    }

    // --- request shapes -------------------------------------------------------------------------

    @Test
    fun `the transcription request carries the utterance as an inline WAV and an instruction to only transcribe`() {
        val audio = pcm(500)
        val body = CloudSpeechWire.transcribe(audio)
        val part = body.getJSONArray("contents").getJSONObject(0).getJSONArray("parts").getJSONObject(0).getJSONObject("inlineData")
        assertEquals("audio/wav", part.getString("mimeType"))
        val wav = part.getString("data").decodeBase64()!!.toByteArray()
        assertEquals("RIFF", String(wav, 0, 4, Charsets.US_ASCII))
        assertEquals(44 + audio.size, wav.size)
        assertEquals(44, CloudSpeechWire.wavHeaderLength(wav))
        // 16 kHz in the header, little-endian.
        assertEquals(16_000, (wav[24].toInt() and 0xff) or ((wav[25].toInt() and 0xff) shl 8) or ((wav[26].toInt() and 0xff) shl 16))
        assertArrayEquals(audio, wav.copyOfRange(44, wav.size))
        val instruction = body.getJSONObject("systemInstruction").getJSONArray("parts").getJSONObject(0).getString("text")
        assertTrue(instruction.contains("never answer"))
        // Never tools or a cache on a transcription: nothing can run from it.
        assertFalse(body.has("tools"))
        assertFalse(body.has("cachedContent"))
    }

    @Test
    fun `the voice request asks for audio in Puck, in the 3_8 shape or the older one`() {
        val now = CloudSpeechWire.speak("Invented line.", legacyVoice = false)
        val cfg = now.getJSONObject("generationConfig")
        assertEquals("AUDIO", cfg.getJSONArray("responseModalities").getString(0))
        assertEquals("Puck", cfg.getJSONObject("speechConfig").getJSONObject("voiceConfig").getString("voice"))
        val old = CloudSpeechWire.speak("Invented line.", legacyVoice = true)
        assertEquals(
            "Puck",
            old.getJSONObject("generationConfig").getJSONObject("speechConfig").getJSONObject("voiceConfig")
                .getJSONObject("prebuiltVoiceConfig").getString("voiceName"),
        )
        assertEquals("Invented line.", now.getJSONArray("contents").getJSONObject(0).getJSONArray("parts").getJSONObject(0).getString("text"))
    }

    @Test
    fun `a transcript loses wrapping quotes and whitespace, nothing else`() {
        assertEquals("add oat milk", CloudSpeechWire.cleanTranscript("  \"add oat milk\"\n"))
        assertEquals("it's fine", CloudSpeechWire.cleanTranscript("it's fine"))
        assertEquals("", CloudSpeechWire.cleanTranscript("   "))
    }

    // --- speech-to-text -------------------------------------------------------------------------

    @Test
    fun `an utterance is transcribed once, when the phone ends the turn`() = runBlocking<Unit> {
        val logs = Collections.synchronizedList(mutableListOf<String>())
        val metered = Collections.synchronizedList(mutableListOf<String>())
        val fake = FakeGemini { _, _, on ->
            on(textEv("What is the capital "))
            on(textEv("of Testland?"))
            on(usageEv(800, 9))
            StreamOutcome.Ok
        }
        val stt = GeminiStt(fake, scope, meter = { m, _ -> metered.add(m) }, log = { logs.add(it) })
        assertFalse(stt.endsTurns)
        val u = stt.begin()
        u.audio(pcm(300), 0, 300 * 32)
        u.audio(pcm(300), 0, 300 * 32)
        assertEquals("nothing is sent before the end", 0, fake.calls.size)
        u.end()
        val r = u.result(2_000) as SttResult.Text
        assertEquals("What is the capital of Testland?", r.text)
        assertEquals(600L, r.audioMs)
        assertEquals(1, fake.calls.size)
        assertEquals(CloudSpeechWire.STT_MODEL, fake.calls[0].first)
        val sent = fake.calls[0].second.getJSONArray("contents").getJSONObject(0).getJSONArray("parts").getJSONObject(0)
            .getJSONObject("inlineData").getString("data").decodeBase64()!!.toByteArray()
        assertEquals(44 + 600 * 32, sent.size)
        assertEquals(listOf(CloudSpeechWire.STT_MODEL), metered)
        assertTrue(logs.single().startsWith("stt_cloud_ms="))
        assertTrue(logs.single().contains("prompt=800 out=9"))
    }

    @Test
    fun `a blip too short to be words is not paid for`() = runBlocking<Unit> {
        val fake = FakeGemini { _, _, _ -> error("must not be called") }
        val u = GeminiStt(fake, scope).begin()
        u.audio(pcm(100), 0, 100 * 32)
        u.end()
        assertEquals("", (u.result(1_000) as SttResult.Text).text)
        assertEquals(0, fake.calls.size)
    }

    @Test
    fun `a refused or unreachable transcription is a failure with a token, never an exception`() = runBlocking<Unit> {
        val refused = GeminiStt(FakeGemini { _, _, _ -> StreamOutcome.HttpError(500, "invented") }, scope).begin()
        refused.audio(pcm(400), 0, 400 * 32)
        refused.end()
        assertEquals(SttResult.Failed("http_500"), refused.result(1_000))
        val offline = GeminiStt(FakeGemini { _, _, _ -> StreamOutcome.Network("UnknownHostException") }, scope).begin()
        offline.audio(pcm(400), 0, 400 * 32)
        offline.end()
        assertEquals(SttResult.Failed("network_UnknownHostException"), offline.result(1_000))
    }

    @Test
    fun `a dropped or server-failed request is tried once more, a refused one is not`() = runBlocking<Unit> {
        var sttCalls = 0
        val flaky = FakeGemini { _, _, on ->
            if (sttCalls++ == 0) {
                StreamOutcome.Network("SocketTimeoutException")
            } else {
                on(textEv("Invented words."))
                StreamOutcome.Ok
            }
        }
        val u = GeminiStt(flaky, scope).begin()
        u.audio(pcm(400), 0, 400 * 32)
        u.end()
        assertEquals("Invented words.", (u.result(2_000) as SttResult.Text).text)
        assertEquals(2, flaky.calls.size)

        val refused = FakeGemini { _, _, _ -> StreamOutcome.HttpError(403, "invented") }
        val r = GeminiStt(refused, scope).begin()
        r.audio(pcm(400), 0, 400 * 32)
        r.end()
        assertEquals(SttResult.Failed("http_403"), r.result(2_000))
        assertEquals("a 4xx would fail again: no retry", 1, refused.calls.size)

        var ttsCalls = 0
        val flakyVoice = FakeGemini { _, _, on ->
            if (ttsCalls++ == 0) {
                StreamOutcome.HttpError(503, "invented")
            } else {
                on(audioEv(byteArrayOf(5, 6)))
                StreamOutcome.Ok
            }
        }
        assertArrayEquals(byteArrayOf(5, 6), collect(GeminiTts(flakyVoice, scope).speak("Invented.")))
        assertEquals(2, flakyVoice.calls.size)
    }

    @Test
    fun `a slow transcription times out, and its request is cancelled`() = runBlocking<Unit> {
        val cancelled = CompletableDeferred<Unit>()
        val fake = FakeGemini { _, _, _ ->
            try {
                awaitCancellation()
            } finally {
                cancelled.complete(Unit)
            }
        }
        val u = GeminiStt(fake, scope).begin()
        u.audio(pcm(400), 0, 400 * 32)
        u.end()
        assertEquals(SttResult.Failed("timeout"), u.result(150))
        withTimeout(2_000) { cancelled.await() }
    }

    @Test
    fun `closing the cloud voice ends a pending transcription at once`() = runBlocking<Unit> {
        val fake = FakeGemini { _, _, _ -> awaitCancellation() }
        val cloud = CloudSpeech.create(scope, fake)
        val u = cloud.stt.begin()
        u.audio(pcm(400), 0, 400 * 32)
        u.end()
        delay(50)
        cloud.close("test")
        val r = withTimeout(1_000) { u.result(10_000) }
        assertTrue("got $r", r is SttResult.Failed)
    }

    // --- text-to-speech -------------------------------------------------------------------------

    @Test
    fun `a clip plays its streamed audio in order, whole samples only`() = runBlocking<Unit> {
        val logs = Collections.synchronizedList(mutableListOf<String>())
        val fake = FakeGemini { _, _, on ->
            on(audioEv(byteArrayOf(1, 2, 3)))
            on(audioEv(byteArrayOf(4, 5), byteArrayOf(6)))
            on(usageEv(12, 100))
            StreamOutcome.Ok
        }
        val tts = GeminiTts(fake, scope, log = { logs.add(it) })
        assertEquals(24_000, tts.sampleRate)
        assertFalse(tts.streamsText)
        val clip = tts.speak("Invented line.")
        val frames = mutableListOf<ByteArray>()
        for (f in clip.frames) frames.add(f)
        assertTrue("every frame is whole samples", frames.all { it.size % 2 == 0 })
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6), frames.fold(ByteArray(0)) { a, b -> a + b })
        assertTrue(clip.outcome() is TtsOutcome.Done)
        assertEquals(CloudSpeechWire.TTS_MODEL, fake.calls.single().first)
        assertTrue(logs.single().startsWith("tts_cloud first_ms="))
    }

    @Test
    fun `a WAV header at the start of a clip is never played`() = runBlocking<Unit> {
        val body = byteArrayOf(9, 8, 7, 6)
        val fake = FakeGemini { _, _, on ->
            on(audioEv(CloudSpeechWire.wav(body, 24_000)))
            StreamOutcome.Ok
        }
        assertArrayEquals(body, collect(GeminiTts(fake, scope).speak("Invented.")))
    }

    @Test
    fun `a refused voice shape is retried once in the older shape, then kept`() = runBlocking<Unit> {
        val fake = FakeGemini { _, body, on ->
            val vc = body.getJSONObject("generationConfig").getJSONObject("speechConfig").getJSONObject("voiceConfig")
            if (vc.has("voice")) {
                StreamOutcome.HttpError(400, "Invalid JSON payload received. Unknown name \"voice\" at 'generation_config.speech_config.voice_config'")
            } else {
                on(audioEv(byteArrayOf(1, 2)))
                StreamOutcome.Ok
            }
        }
        val tts = GeminiTts(fake, scope)
        assertArrayEquals(byteArrayOf(1, 2), collect(tts.speak("First.")))
        assertArrayEquals(byteArrayOf(1, 2), collect(tts.speak("Second.")))
        assertEquals("one refusal, then the older shape for good", 3, fake.calls.size)
    }

    @Test
    fun `an error or a silent answer fails the clip with a token`() = runBlocking<Unit> {
        val err = GeminiTts(FakeGemini { _, _, _ -> StreamOutcome.HttpError(503, "invented") }, scope).speak("Invented.")
        assertEquals(0, collect(err).size)
        assertEquals(TtsOutcome.Failed("http_503"), err.outcome())
        val silent = GeminiTts(FakeGemini { _, _, _ -> StreamOutcome.Ok }, scope).speak("Invented.")
        collect(silent)
        assertEquals(TtsOutcome.Failed("no_audio"), silent.outcome())
    }

    @Test
    fun `cancelling a clip stops its request`() = runBlocking<Unit> {
        val cancelled = CompletableDeferred<Unit>()
        val fake = FakeGemini { _, _, on ->
            on(audioEv(byteArrayOf(1, 2)))
            try {
                awaitCancellation()
            } finally {
                cancelled.complete(Unit)
            }
        }
        val clip = GeminiTts(fake, scope).speak("Invented.")
        withTimeout(1_000) { clip.frames.receive() }
        clip.cancel()
        assertEquals(TtsOutcome.Cancelled, clip.outcome())
        withTimeout(1_000) { cancelled.await() }
    }

    @Test
    fun `at most two clips are asked for at once`() = runBlocking<Unit> {
        val inFlight = AtomicInteger(0)
        val most = AtomicInteger(0)
        val fake = FakeGemini { _, _, on ->
            most.accumulateAndGet(inFlight.incrementAndGet(), ::maxOf)
            delay(60)
            on(audioEv(byteArrayOf(1, 2)))
            inFlight.decrementAndGet()
            StreamOutcome.Ok
        }
        val tts = GeminiTts(fake, scope)
        val clips = (1..4).map { tts.speak("Sentence $it.") }
        clips.forEach { collect(it) }
        assertEquals(2, most.get())
        assertEquals(4, fake.calls.size)
    }

    @Test
    fun `a scripted line is paid for once, then played from memory`() = runBlocking<Unit> {
        val kept = ConcurrentHashMap<String, ByteArray>()
        val fake = FakeGemini { _, _, on ->
            on(audioEv(byteArrayOf(3, 4, 5, 6)))
            StreamOutcome.Ok
        }
        val tts = GeminiTts(fake, scope, keepLines = setOf("Listening."), kept = kept)
        assertArrayEquals(byteArrayOf(3, 4, 5, 6), collect(tts.speak("Listening.")))
        assertArrayEquals(byteArrayOf(3, 4, 5, 6), collect(tts.speak("Listening.")))
        assertEquals(1, fake.calls.size)
        // A reply sentence is never kept.
        collect(tts.speak("Invented reply."))
        collect(tts.speak("Invented reply."))
        assertEquals(3, fake.calls.size)
    }

    @Test
    fun `warming fetches a kept line once, so its first use is already in hand`() = runBlocking<Unit> {
        val kept = ConcurrentHashMap<String, ByteArray>()
        val fake = FakeGemini { _, _, on ->
            on(audioEv(byteArrayOf(7, 8)))
            StreamOutcome.Ok
        }
        val tts = GeminiTts(fake, scope, keepLines = setOf("Hold on."), kept = kept)
        assertFalse(tts.inHand("Hold on."))
        // Only kept lines are warmed; a line outside the set and a blank one cost nothing.
        assertEquals(1, tts.warm(listOf("Hold on.", "Hold on.", "Invented reply.", " ")))
        withTimeout(2_000) { while (!tts.inHand("Hold on.")) delay(5) }
        assertEquals(1, fake.calls.size)
        assertEquals("already kept: nothing more to fetch", 0, tts.warm(listOf("Hold on.")))
        assertArrayEquals(byteArrayOf(7, 8), collect(tts.speak("Hold on.")))
        assertEquals(1, fake.calls.size)
        assertFalse("a reply sentence is never in hand", tts.inHand("Invented reply."))
    }

    @Test
    fun `a reply speaks sentence by sentence through the cloud voice, in order`() = runBlocking<Unit> {
        val fake = FakeGemini { _, body, on ->
            val text = body.getJSONArray("contents").getJSONObject(0).getJSONArray("parts").getJSONObject(0).getString("text")
            // The first sentence is the slowest: order must still hold.
            if (text.startsWith("One")) delay(80)
            on(audioEv(text.toByteArray()))
            StreamOutcome.Ok
        }
        val played = Collections.synchronizedList(mutableListOf<String>())
        val speaker = ReplySpeaker(
            tts = GeminiTts(fake, scope),
            scope = scope,
            play = { pcm -> played.add(String(pcm)); true },
            firstFrameTimeoutMs = CascadeTimings.CLOUD_TTS_FIRST_AUDIO_TIMEOUT_MS,
            onFirstAudio = { _, _ -> },
            onClipStarted = {},
            normalise = { it },
        )
        speaker.text("One sentence here. Two sen")
        speaker.text("tences now. Three.")
        speaker.finishText()
        assertEquals(ReplySpeaker.Outcome.Played, withTimeout(3_000) { speaker.await() })
        assertEquals(listOf("One sentence here.", "Two sentences now.", "Three."), played.map { it.trim() })
    }

    // --- the seam ---------------------------------------------------------------------------------

    @Test
    fun `the cloud backend has no end of turn of its own and its own deadlines`() {
        val cloud = CloudSpeech.create(scope, FakeGemini { _, _, _ -> StreamOutcome.Ok })
        assertEquals(SpeechBackend.Kind.CLOUD, cloud.kind)
        assertFalse(cloud.stt.endsTurns)
        assertEquals(CascadeTimings.CLOUD_STT_FINAL_TIMEOUT_MS, cloud.sttFinalTimeoutMs)
        assertEquals(CascadeTimings.CLOUD_TTS_FIRST_AUDIO_TIMEOUT_MS, cloud.ttsFirstAudioTimeoutMs)
        assertTrue(cloud.sttFinalTimeoutMs > CascadeTimings.STT_FINAL_TIMEOUT_MS)
    }

    @Test
    fun `a laptop utterance keeps its audio for the cloud, capped, and a cloud one keeps none`() {
        val sent = mutableListOf<Int>()
        val inner = object : SttUtterance {
            override val id = 1
            override fun audio(pcm: ByteArray, offset: Int, length: Int) { sent.add(length) }
            override fun end() {}
            override fun cancel() {}
            override suspend fun result(timeoutMs: Long): SttResult = SttResult.Failed("dropped_1006")
        }
        val backend = object : SpeechBackend {
            override val kind = SpeechBackend.Kind.LAPTOP
            override val stt get() = error("unused")
            override val tts get() = error("unused")
            override val sttFinalTimeoutMs = 1L
            override val ttsFirstAudioTimeoutMs = 1L
            override fun close(reason: String) {}
        }
        val kept = TurnUtterance(inner, backend, keepAudio = true, capBytes = 10)
        kept.audio(byteArrayOf(1, 2, 3, 4, 5, 6), 0, 6)
        kept.audio(byteArrayOf(7, 8, 9, 10, 11, 12), 2, 4)
        assertEquals(listOf(6, 4), sent)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6, 9, 10, 11, 12), kept.keptAudio())
        kept.audio(byteArrayOf(13, 14), 0, 2)
        assertEquals("capped", 10, kept.keptAudio()!!.size)
        assertEquals(1, kept.id)
        assertNull(TurnUtterance(inner, backend, keepAudio = false).keptAudio())
    }
}

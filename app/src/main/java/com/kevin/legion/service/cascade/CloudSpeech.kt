package com.kevin.legion.service.cascade

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import okio.ByteString.Companion.decodeBase64
import okio.ByteString.Companion.toByteString
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * The laptop pipeline's cloud fallback (Kevin, 2026-11-15): Gemini speech-to-text, then the same
 * text model and tools the laptop path uses, then Gemini TTS in Puck, the voice Live speaks with, so
 * the fallback sounds familiar. Cheaper than Live's speech-to-speech model; see
 * `.legion/reports/cascade-cloud-fallback-1115.md` (private) for the per-minute comparison.
 *
 * Both halves run over [GeminiStreamTransport] (`streamGenerateContent?alt=sse` on the user's own
 * key), the same transport the text loop uses, so a test fakes all three with one fake.
 */
internal class CloudSpeech(
    override val stt: GeminiStt,
    override val tts: GeminiTts,
    private val ownScope: CoroutineScope,
) : SpeechBackend {
    override val kind = SpeechBackend.Kind.CLOUD
    override val sttFinalTimeoutMs = CascadeTimings.CLOUD_STT_FINAL_TIMEOUT_MS
    override val ttsFirstAudioTimeoutMs = CascadeTimings.CLOUD_TTS_FIRST_AUDIO_TIMEOUT_MS
    private val closed = AtomicBoolean(false)

    /** Cancels every request in flight; every pending wait ends as a failure or a cancel. */
    override fun close(reason: String) {
        if (closed.compareAndSet(false, true)) ownScope.cancel()
    }

    companion object {
        /**
         * One cloud backend for one conversation. Its requests run in a child of [parent], so the
         * session's crash handler still sees anything thrown, and [close] cancels only them.
         */
        fun create(
            parent: CoroutineScope,
            transport: GeminiStreamTransport,
            meter: (model: String, usage: JSONObject?) -> Unit = { _, _ -> },
            log: (String) -> Unit = {},
            keepLines: Set<String> = ScriptedLines.KEEP,
            voice: String = CloudSpeechWire.VOICE,
            usage: (CallUsage) -> Unit = {},
        ): CloudSpeech {
            val own = CoroutineScope(
                parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]) + Dispatchers.IO,
            )
            return CloudSpeech(
                stt = GeminiStt(transport, own, meter, log, usage = usage),
                tts = GeminiTts(transport, own, meter, log, keepLines = keepLines, voice = voice, usage = usage),
                ownScope = own,
            )
        }
    }
}

/**
 * The request and response shapes of Gemini speech, pure and in one place: the model ids, the voice
 * and the body shapes come from Google's docs (speech-generation, fetched 2026-11-10 and 2026-11-15)
 * and are not yet confirmed by a live call from this code, so this is the one file to change.
 */
internal object CloudSpeechWire {
    /**
     * Speech-to-text runs on the cascade's own text model, with the audio inline: $0.30 per 1M audio
     * tokens (pricing page, 2026-11-15) against `gemini-3.5-transcribe`'s $2.00, and on
     * `generateContent`, whose shape this code already speaks (the transcribe model is documented
     * only on the Interactions API).
     */
    const val STT_MODEL = CascadeSession.MODEL

    /** Flash, not Flash-Lite TTS: closer to Live's Puck; Flash-Lite TTS is one constant away. */
    const val TTS_MODEL = "gemini-3.8-flash-tts"

    /**
     * Puck, the voice Live spoke in; the session passes the companion's own voice setting (Live's
     * 30 voice names are Gemini TTS's prebuilt voices), so the cloud voice sounds like Live did.
     */
    const val VOICE = "Puck"

    /** Gemini TTS streams headerless 16-bit PCM, 24 kHz mono, by default (speech-generation doc). */
    const val OUTPUT_RATE = 24_000
    const val INPUT_RATE = 16_000
    const val STT_MAX_OUTPUT_TOKENS = 512

    const val STT_INSTRUCTION =
        "You are a speech-to-text engine. Write down exactly what the speaker says in the audio, in the " +
            "language they speak, with ordinary punctuation. Output only the spoken words: never answer, " +
            "comment, translate, add quotes or add labels. If there is no intelligible speech, output nothing."
    private const val STT_ASK = "Transcribe this audio."

    /** The transcription request: the utterance as a WAV, inline. */
    fun transcribe(pcm16k: ByteArray): JSONObject = JSONObject()
        .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", STT_INSTRUCTION))))
        .put(
            "contents",
            JSONArray().put(
                JSONObject().put("role", "user").put(
                    "parts",
                    JSONArray()
                        .put(
                            JSONObject().put(
                                "inlineData",
                                JSONObject().put("mimeType", "audio/wav").put("data", base64(wav(pcm16k, INPUT_RATE))),
                            ),
                        )
                        .put(JSONObject().put("text", STT_ASK)),
                ),
            ),
        )
        .put("generationConfig", JSONObject().put("maxOutputTokens", STT_MAX_OUTPUT_TOKENS))

    /**
     * One clip. [legacyVoice] is the older `prebuiltVoiceConfig.voiceName` shape (what
     * `tools/render_listen_cues.py` sent); the 3.8 doc's single-speaker shape is `voiceConfig.voice`.
     */
    fun speak(text: String, legacyVoice: Boolean, voiceName: String = VOICE): JSONObject {
        val voice = if (legacyVoice) {
            JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", voiceName))
        } else {
            JSONObject().put("voice", voiceName)
        }
        return JSONObject()
            .put(
                "contents",
                JSONArray().put(
                    JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", text))),
                ),
            )
            .put(
                "generationConfig",
                JSONObject()
                    .put("responseModalities", JSONArray().put("AUDIO"))
                    .put("speechConfig", JSONObject().put("voiceConfig", voice)),
            )
    }

    /** A dropped request or a server error: worth one more try. A 4xx is not (it would fail again). */
    fun worthRetry(o: StreamOutcome): Boolean =
        o is StreamOutcome.Network || (o is StreamOutcome.HttpError && o.code >= HTTP_SERVER_ERROR)

    /** A short log token for an outcome. */
    fun token(o: StreamOutcome): String = when (o) {
        is StreamOutcome.HttpError -> "http_${o.code}"
        is StreamOutcome.Network -> "network_${o.reason}"
        StreamOutcome.Ok -> "ok"
    }

    /** A 400 that names the voice field: worth one retry with the other shape. */
    fun isVoiceShapeRejected(e: StreamOutcome.HttpError): Boolean =
        e.code == HTTP_BAD_REQUEST && VOICE_MARKERS.any { e.body.contains(it, ignoreCase = true) }

    /** Every inline audio part in one streamed event, base64 still encoded, in order. */
    fun audioParts(ev: JSONObject): List<String> {
        val parts = ev.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
            ?: return emptyList()
        val out = mutableListOf<String>()
        for (i in 0 until parts.length()) {
            val data = parts.optJSONObject(i)?.optJSONObject("inlineData")?.optString("data").orEmpty()
            if (data.isNotEmpty()) out.add(data)
        }
        return out
    }

    /** A transcript as the model wrote it, made into his words: trimmed, outer quotes dropped. */
    fun cleanTranscript(raw: String): String {
        var t = raw.trim()
        if (t.length >= 2 && t.first() in QUOTES && t.last() in QUOTES) t = t.substring(1, t.length - 1).trim()
        return t
    }

    /** 16-bit mono PCM in a 44-byte RIFF/WAVE header. */
    @Suppress("MagicNumber") // the RIFF header's byte layout, spelled out
    fun wav(pcm: ByteArray, rate: Int): ByteArray {
        val out = ByteArray(WAV_HEADER + pcm.size)
        fun ascii(at: Int, s: String) = s.forEachIndexed { i, c -> out[at + i] = c.code.toByte() }
        fun le32(at: Int, v: Int) { for (i in 0 until 4) out[at + i] = ((v shr (8 * i)) and 0xff).toByte() }
        fun le16(at: Int, v: Int) { for (i in 0 until 2) out[at + i] = ((v shr (8 * i)) and 0xff).toByte() }
        ascii(0, "RIFF")
        le32(4, 36 + pcm.size)
        ascii(8, "WAVE")
        ascii(12, "fmt ")
        le32(16, 16)
        le16(20, 1) // PCM
        le16(22, 1) // mono
        le32(24, rate)
        le32(28, rate * 2)
        le16(32, 2)
        le16(34, 16)
        ascii(36, "data")
        le32(40, pcm.size)
        System.arraycopy(pcm, 0, out, WAV_HEADER, pcm.size)
        return out
    }

    /**
     * How many leading bytes of a first chunk are a WAV header (the unary default; streaming is
     * documented as headerless, but a header must never be played as sound), or 0.
     */
    @Suppress("MagicNumber", "ReturnCount") // the RIFF layout
    fun wavHeaderLength(b: ByteArray): Int {
        if (b.size < WAV_HEADER || String(b, 0, 4, Charsets.US_ASCII) != "RIFF") return 0
        var at = 12
        while (at + 8 <= b.size) {
            val id = String(b, at, 4, Charsets.US_ASCII)
            val len = (b[at + 4].toInt() and 0xff) or ((b[at + 5].toInt() and 0xff) shl 8) or
                ((b[at + 6].toInt() and 0xff) shl 16) or ((b[at + 7].toInt() and 0xff) shl 24)
            if (id == "data") return at + 8
            at += 8 + len
        }
        return WAV_HEADER
    }

    fun base64(b: ByteArray): String = b.toByteString().base64()

    fun unbase64(s: String): ByteArray? = s.decodeBase64()?.toByteArray()

    /** Gemini meters audio at 32 tokens a second (audio understanding doc); for the log line only. */
    const val AUDIO_TOKENS_PER_SECOND = 32

    private const val WAV_HEADER = 44
    private const val HTTP_BAD_REQUEST = 400
    private const val HTTP_SERVER_ERROR = 500
    private val VOICE_MARKERS = listOf("voice", "speech_config", "speechConfig")
    private val QUOTES = setOf('"', '\'', '“', '”')
}

/**
 * Speech-to-text on Gemini: the phone keeps the utterance's audio and, when the phone's own end of
 * turn fires ([SttUtterance.end]), sends it whole for a transcript. There is no streaming upload, so
 * the added wait is one request (`stt_cloud_ms=` in the log). [endsTurns] is false: the phone's
 * detector (Silero, Smart Turn) decides every end.
 */
internal class GeminiStt(
    private val transport: GeminiStreamTransport,
    private val scope: CoroutineScope,
    private val meter: (model: String, usage: JSONObject?) -> Unit = { _, _ -> },
    private val log: (String) -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
    private val minAudioBytes: Int = MIN_AUDIO_BYTES,
    private val capBytes: Int = TurnUtterance.KEEP_CAP_BYTES,
    /** Every request's counts, for the cost audit ([CallUsage]). */
    private val usage: (CallUsage) -> Unit = {},
) : SpeechToText {
    override val endsTurns = false
    private val ids = AtomicInteger(-1)

    override fun begin(onServiceEnd: () -> Unit): SttUtterance = Utterance(ids.addAndGet(2))

    /**
     * One transcript for [pcm] (16 kHz PCM16 mono). Never throws except to cancel. [report] gets
     * each request's counts (a speculative draft holds them until it is kept or dropped).
     */
    @Suppress("ReturnCount")
    suspend fun transcribe(pcm: ByteArray, report: (CallUsage) -> Unit = usage): SttResult {
        val audioMs = pcm.size / BYTES_PER_MS
        if (pcm.size < minAudioBytes) return SttResult.Text("", null, audioMs.toLong(), 0)
        val body = CloudSpeechWire.transcribe(pcm)
        val started = now()
        var (outcome, turn) = attempt(body, audioMs.toLong(), report)
        if (CloudSpeechWire.worthRetry(outcome)) {
            log("stt_cloud_retry after=${CloudSpeechWire.token(outcome)}")
            val again = attempt(body, audioMs.toLong(), report)
            outcome = again.first
            turn = again.second
        }
        val ms = now() - started
        when (outcome) {
            is StreamOutcome.HttpError -> return SttResult.Failed("http_${outcome.code}")
            is StreamOutcome.Network -> return SttResult.Failed("network_${outcome.reason}")
            StreamOutcome.Ok -> Unit
        }
        meter(CloudSpeechWire.STT_MODEL, turn.usage)
        log(
            "stt_cloud_ms=$ms audio_ms=$audioMs prompt=${turn.usage?.optInt("promptTokenCount") ?: -1} " +
                "out=${turn.usage?.optInt("candidatesTokenCount") ?: -1}",
        )
        if (turn.blockReason != null) return SttResult.Failed("blocked")
        if (turn.error != null && turn.text.isEmpty()) return SttResult.Failed("model_error")
        return SttResult.Text(CloudSpeechWire.cleanTranscript(turn.text.toString()), null, audioMs.toLong(), ms)
    }

    /** One request, its usage reported whatever happens (a cancel reports what came, as discarded). */
    private suspend fun attempt(
        body: JSONObject,
        audioMs: Long,
        report: (CallUsage) -> Unit,
    ): Pair<StreamOutcome, StreamedTurn> {
        val turn = StreamedTurn()
        val o = try {
            transport.stream(CloudSpeechWire.STT_MODEL, body) { turn.onEvent(it) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            report(CallUsage.of(CallUsage.STT, CloudSpeechWire.STT_MODEL, turn.usage, audioMs, discarded = true))
            throw e
        }
        val ok = o == StreamOutcome.Ok
        report(
            CallUsage.of(CallUsage.STT, CloudSpeechWire.STT_MODEL, turn.usage.takeIf { ok }, audioMs, discarded = !ok)
                .copy(failed = !ok),
        )
        return o to turn
    }

    private inner class Utterance(override val id: Int) : SttUtterance {
        private val buf = ByteArrayOutputStream()
        private val result = CompletableDeferred<SttResult>()
        private val ended = AtomicBoolean(false)
        @Volatile private var job: Job? = null

        override fun audio(pcm: ByteArray, offset: Int, length: Int) {
            if (ended.get() || length <= 0) return
            synchronized(buf) {
                val room = capBytes - buf.size()
                if (room > 0) buf.write(pcm, offset, minOf(length, room))
            }
        }

        override fun end() {
            if (!ended.compareAndSet(false, true) || result.isCompleted) return
            val audio = synchronized(buf) { buf.toByteArray() }
            job = scope.launch {
                val r = try {
                    transcribe(audio)
                } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) SttResult.Failed("cancelled")
                    else SttResult.Failed("exception_${e.javaClass.simpleName}")
                }
                result.complete(r)
            }.also { j ->
                // A backend closed under it: the wait ends now, not at its timeout.
                j.invokeOnCompletion { result.complete(SttResult.Failed("closed")) }
            }
        }

        override fun cancel() {
            ended.set(true)
            job?.cancel()
            result.complete(SttResult.Failed("cancelled"))
        }

        override suspend fun result(timeoutMs: Long): SttResult {
            val r = withTimeoutOrNull(timeoutMs) { result.await() }
            if (r == null) {
                job?.cancel()
                result.complete(SttResult.Failed("timeout"))
            }
            return r ?: SttResult.Failed("timeout")
        }
    }

    companion object {
        private const val BYTES_PER_MS = 32

        /** Under 200 ms of audio is not a request worth paying for: nothing was said. */
        const val MIN_AUDIO_BYTES = 200 * BYTES_PER_MS
    }
}

/**
 * Text-to-speech on Gemini TTS in [voice] (the companion's voice; Puck by default). Sentence by
 * sentence ([streamsText] is false, so [ReplySpeaker] splits with [SentenceSplitter]): each [speak]
 * starts its own streamed
 * request at once (at most [maxParallel] at a time), so the first sentence plays while the model is
 * still writing the rest and the next one is already on its way.
 *
 * The scripted opening lines ([keepLines]) are kept for the process once spoken: the same short line
 * is not paid for twice.
 */
internal class GeminiTts(
    private val transport: GeminiStreamTransport,
    private val scope: CoroutineScope,
    private val meter: (model: String, usage: JSONObject?) -> Unit = { _, _ -> },
    private val log: (String) -> Unit = {},
    private val maxParallel: Int = MAX_PARALLEL,
    private val keepLines: Set<String> = emptySet(),
    private val kept: MutableMap<String, ByteArray> = KEPT,
    private val now: () -> Long = System::currentTimeMillis,
    private val voice: String = CloudSpeechWire.VOICE,
    /** Every request's counts, for the cost audit ([CallUsage]). */
    private val usage: (CallUsage) -> Unit = {},
) : TextToSpeech {
    override val sampleRate = CloudSpeechWire.OUTPUT_RATE
    override val streamsText = false
    private val gate = Semaphore(maxParallel)
    private val ids = AtomicInteger(0)

    /** Flipped once if Google refuses the 3.8 voice shape; the rest of the conversation uses the other. */
    @Volatile private var legacyVoice = false

    override fun speak(text: String): SpeechClip {
        val clip = Clip(ids.addAndGet(2), text)
        val key = keyOf(text)
        val cached = if (text in keepLines) kept[key] else null
        when {
            text.isBlank() -> clip.finish(TtsOutcome.Done(0, null))
            cached != null -> {
                clip.push(cached)
                clip.finish(TtsOutcome.Done(cached.size.toLong() / BYTES_PER_MS, 0))
            }
            else -> clip.start(key)
        }
        return clip
    }

    /** Never called ([streamsText] is false); a refused stream makes [ReplySpeaker] use sentences. */
    override fun openStream(): StreamingSpeechClip = Clip(ids.addAndGet(2), "").also {
        it.finish(TtsOutcome.Failed("bad_request"))
    }

    private inner class Clip(override val id: Int, override val text: String) : StreamingSpeechClip {
        override val frames = Channel<ByteArray>(Channel.UNLIMITED)
        private val outcome = CompletableDeferred<TtsOutcome>()
        private var job: Job? = null
        private var odd: Byte? = null
        private var first = true
        private var bytes = 0L
        private var firstAudioMs: Long? = null
        private val whole = ByteArrayOutputStream()

        fun start(key: String) {
            job = scope.launch {
                gate.withPermit {
                    if (outcome.isCompleted) return@withPermit
                    val asked = now()
                    var o = request(asked)
                    val shapeRefused = o is StreamOutcome.HttpError && CloudSpeechWire.isVoiceShapeRejected(o)
                    if (shapeRefused && !legacyVoice && bytes == 0L) {
                        legacyVoice = true
                        log("tts_voice_shape legacy=true")
                        o = request(asked)
                    }
                    // One retry for a dropped request or a server error, only before any audio
                    // played (a retry after that would say the start of the sentence twice).
                    if (CloudSpeechWire.worthRetry(o) && bytes == 0L) {
                        log("tts_cloud_retry after=${CloudSpeechWire.token(o)}")
                        o = request(asked)
                    }
                    finish(
                        when (o) {
                            is StreamOutcome.HttpError -> TtsOutcome.Failed("http_${o.code}")
                            is StreamOutcome.Network -> TtsOutcome.Failed("network_${o.reason}")
                            StreamOutcome.Ok ->
                                if (bytes == 0L) {
                                    TtsOutcome.Failed("no_audio")
                                } else {
                                    if (text in keepLines) kept[key] = synchronized(whole) { whole.toByteArray() }
                                    TtsOutcome.Done(bytes / BYTES_PER_MS, firstAudioMs)
                                }
                        },
                    )
                }
            }.also { j -> j.invokeOnCompletion { finish(TtsOutcome.Cancelled) } }
        }

        private suspend fun request(asked: Long): StreamOutcome {
            var usage: JSONObject? = null
            val bytesBefore = bytes
            fun counts(u: JSONObject?, discarded: Boolean) = CallUsage.of(
                CallUsage.TTS,
                CloudSpeechWire.TTS_MODEL,
                u,
                audioMs = (bytes - bytesBefore) / BYTES_PER_MS,
                chars = text.length,
                discarded = discarded,
                defaultOut = "AUDIO",
            )
            val o = try {
                transport.stream(CloudSpeechWire.TTS_MODEL, CloudSpeechWire.speak(text, legacyVoice, voice)) { ev ->
                    ev.optJSONObject("usageMetadata")?.let { usage = it }
                    for (data in CloudSpeechWire.audioParts(ev)) {
                        val pcm = CloudSpeechWire.unbase64(data) ?: continue
                        if (firstAudioMs == null) firstAudioMs = now() - asked
                        push(pcm)
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Cut off (barge-in, a tap, the reply dropped): what it had made is still counted.
                this@GeminiTts.usage(counts(usage, discarded = true))
                throw e
            }
            this@GeminiTts.usage(
                if (o == StreamOutcome.Ok) counts(usage, false) else counts(null, true).copy(failed = true),
            )
            if (o == StreamOutcome.Ok) {
                meter(CloudSpeechWire.TTS_MODEL, usage)
                val prompt = usage?.optInt("promptTokenCount") ?: -1
                val out = usage?.optInt("candidatesTokenCount") ?: -1
                log(
                    "tts_cloud first_ms=${firstAudioMs ?: -1} audio_ms=${bytes / BYTES_PER_MS} chars=${text.length} " +
                        "prompt=$prompt out=$out",
                )
            }
            return o
        }

        /** Whole 16-bit samples only: an odd byte waits for the next chunk. A WAV header is dropped. */
        fun push(chunk: ByteArray) {
            if (outcome.isCompleted) return
            var b = chunk
            if (first) {
                first = false
                val skip = CloudSpeechWire.wavHeaderLength(b)
                if (skip > 0) b = b.copyOfRange(skip, b.size)
            }
            odd?.let { carried ->
                b = byteArrayOf(carried) + b
                odd = null
            }
            if (b.size % 2 != 0) {
                odd = b[b.size - 1]
                b = b.copyOf(b.size - 1)
            }
            if (b.isEmpty()) return
            bytes += b.size
            if (text in keepLines) synchronized(whole) { whole.write(b) }
            frames.trySend(b)
        }

        fun finish(o: TtsOutcome) {
            if (outcome.complete(o)) frames.close()
        }

        override fun append(text: String) = Unit

        override fun finish() = Unit

        override fun cancel() {
            if (outcome.isCompleted) return
            finish(TtsOutcome.Cancelled)
            job?.cancel()
        }

        override suspend fun outcome(): TtsOutcome = outcome.await()
    }

    private fun keyOf(text: String) = "${CloudSpeechWire.TTS_MODEL}|$voice|$text"

    companion object {
        /** 24 kHz PCM16 mono. */
        private const val BYTES_PER_MS = 48

        /** The sentence playing and the next one; more would only queue at Google. */
        const val MAX_PARALLEL = 2

        /** The scripted lines' audio, for the process. Eight short lines: a few hundred KB at most. */
        private val KEPT: MutableMap<String, ByteArray> = ConcurrentHashMap()
    }
}

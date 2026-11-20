package com.kevin.legion.service.cascade

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
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
            /** The STT vocabulary line ([SttGuard.hint]), read per request; "" for none. */
            hint: () -> String = { "" },
            /** The STT instruction ([SpokenLanguages.sttInstruction]), read per request. */
            instruction: () -> String = { CloudSpeechWire.STT_INSTRUCTION },
        ): CloudSpeech {
            val own = CoroutineScope(
                parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]) + Dispatchers.IO,
            )
            return CloudSpeech(
                stt = GeminiStt(transport, own, meter, log, usage = usage, hint = hint, instruction = instruction),
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
     * The default cloud voice for Alfred: the en-GB library voice "Authoritative Advisor 3" (Kevin,
     * 2026-11-17: "this is my choice Authoritative Advisor 3 / en-gb-advisor-3"; checked live in this
     * request shape: accepted, tags accepted, ~7% slower than Puck, so the 1.15x speed default stays).
     * Settings > Voice engine > Cloud voice sets Puck or another id ([VoiceEngineSettings.cloudVoice]);
     * the cloud voice no longer follows the Live voice setting.
     */
    const val VOICE = "en-gb-advisor-3"

    /** If Google refuses the chosen voice by name (a 400), the conversation goes on in Puck, never silent. */
    const val FALLBACK_VOICE = "Puck"

    /** Gemini TTS streams headerless 16-bit PCM, 24 kHz mono, by default (speech-generation doc). */
    const val OUTPUT_RATE = 24_000
    const val INPUT_RATE = 16_000
    const val STT_MAX_OUTPUT_TOKENS = 512

    /**
     * Pinned to English (2026-11-17: "in the language they speak" let English speech come back as
     * Spanish and Chinese fragments). [SttGuard] still drops whatever is not plausibly English.
     *
     * No-speech audio (2026-11-17, real-model run, `.legion/reports/phone-voice-fixes-1117pm/`): with
     * "if there is no intelligible speech, output nothing" flash-lite invented a command on 20 of 21
     * silence/noise/hum clips ("Set a timer for five minutes."). Told that the audio may hold no voice,
     * never to guess, and to answer [NO_SPEECH] instead: 0 of 21. [cleanTranscript] turns that into "".
     * No examples here, ever: a transcriber writes examples back as speech.
     */
    const val STT_INSTRUCTION =
        "You are a speech-to-text engine for an American English speaker talking to his voice assistant. " +
            "Write down exactly the English words he says, with ordinary punctuation. Never write another " +
            "language or script, and never guess: the audio may be silence, noise, music or a hum with no voice " +
            "in it, and then there are no words. Output only words you actually hear spoken: never answer, " +
            "comment, translate, add quotes or add labels. If no voice speaks words, output exactly <none> and " +
            "nothing else."

    /** What the transcriber answers for audio with no voice in it ([STT_INSTRUCTION]). */
    const val NO_SPEECH = "<none>"
    private const val STT_ASK = "Transcribe this audio."

    /** The transcription request: the utterance as a WAV, inline; [hint] ([SttGuard.hint]) after the rules. */
    fun transcribe(
        pcm16k: ByteArray,
        hint: String = "",
        instruction: String = STT_INSTRUCTION,
    ): JSONObject = JSONObject()
        .put(
            "systemInstruction",
            JSONObject().put(
                "parts",
                JSONArray().put(JSONObject().put("text", if (hint.isBlank()) instruction else "$instruction $hint")),
            ),
        )
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

    /** One clip in [voiceName], in the 3.8 doc's single-speaker shape (`voiceConfig.voice`, answered 200). */
    fun speak(text: String, voiceName: String = VOICE): JSONObject {
        val voice = JSONObject().put("voice", voiceName)
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

    /**
     * Google's 400 for a voice it will not speak: "No matching speaker voice found for name: ..." or
     * "Received empty voice name." (measured 2026-11-18). Any other 400 (empty text, a bad tag) is
     * not about the voice, so it never moves the reply to Puck.
     */
    fun isVoiceRefused(o: StreamOutcome): Boolean =
        o is StreamOutcome.HttpError && o.code == HTTP_BAD_REQUEST &&
            VOICE_REFUSALS.any { o.body.contains(it, ignoreCase = true) }

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
        // No voice in the audio: silence, never words.
        if (t.trimEnd('.').equals(NO_SPEECH, ignoreCase = true)) return ""
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
    private val VOICE_REFUSALS = listOf("speaker voice", "voice name")
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
    private val hint: () -> String = { "" },
    /** The instruction for his languages and the conversation's ([SpokenLanguages.sttInstruction]). */
    private val instruction: () -> String = { CloudSpeechWire.STT_INSTRUCTION },
    /** A request with no answer by then is abandoned and asked again, once ([attemptTimeoutFor]). */
    private val attemptTimeoutMs: (audioMs: Long) -> Long = { attemptTimeoutFor(it) },
    /** A request still out by then gets a second, identical one beside it ([SttHedge.hedgeAfterFor]). */
    private val hedgeAfterMs: (audioMs: Long) -> Long = { SttHedge.hedgeAfterFor(it) },
) : SpeechToText {
    override val endsTurns = false
    private val ids = AtomicInteger(-1)

    override fun begin(onServiceEnd: () -> Unit): SttUtterance = Utterance(ids.addAndGet(2))

    /**
     * One transcript for [pcm] (16 kHz PCM16 mono). Never throws except to cancel. [report] gets
     * each request's counts (a speculative draft holds them until it is kept or dropped).
     */
    @Suppress("ReturnCount", "CyclomaticComplexMethod") // one exit per outcome; the hung-request retry
    suspend fun transcribe(
        pcm: ByteArray,
        report: (CallUsage) -> Unit = usage,
        /** False for a speculative draft: its final is transcribed anyway, so a slow draft is not hedged. */
        hedged: Boolean = true,
    ): SttResult {
        val audioMs = pcm.size / BYTES_PER_MS
        if (pcm.size < minAudioBytes) return SttResult.Text("", null, audioMs.toLong(), 0)
        val body = CloudSpeechWire.transcribe(
            pcm,
            runCatching(hint).getOrDefault(""),
            runCatching(instruction).getOrDefault(CloudSpeechWire.STT_INSTRUCTION),
        )
        val started = now()
        // 2026-11-17: three of 34 requests hung 10 to 17 s (every good one took 2.4 s or less) and the
        // turn waited out the 8 s timeout. A request that has not answered in its deadline (longer for
        // longer audio) is abandoned and the same audio asked again on a fresh call, once. Since
        // 2026-11-19 the second request also starts beside a first that is merely slow ([SttHedge]).
        val deadline = attemptTimeoutMs(audioMs.toLong())
        val hedge = if (hedged) hedgeAfterMs(audioMs.toLong()) else NO_HEDGE_MS
        val race = SttHedge.race(
            hedgeAfterMs = hedge,
            attemptTimeoutMs = deadline,
            attempt = { attempt(body, audioMs.toLong(), report) },
            good = { it.first == StreamOutcome.Ok },
            worthRetry = { CloudSpeechWire.worthRetry(it.first) },
            onSecond = { why, got ->
                when (why) {
                    SttHedge.Why.SLOW -> log("stt_cloud_hedge after_ms=$hedge")
                    SttHedge.Why.HUNG -> log("stt_cloud_retry after=hung_$deadline")
                    SttHedge.Why.FAILED -> log("stt_cloud_retry after=${got?.let { CloudSpeechWire.token(it.first) }}")
                }
            },
        )
        val (outcome, turn) = when (race) {
            is SttHedge.Result.Won -> {
                if (race.request == 1) log("stt_cloud_hedge_won second")
                race.value
            }
            is SttHedge.Result.Lost -> race.last ?: return SttResult.Failed("timeout")
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
        return SttResult.Text(
            CloudSpeechWire.cleanTranscript(turn.text.toString()),
            null,
            audioMs.toLong(),
            ms,
            voicedMs = SttGuard.voicedMs(pcm),
        )
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

        /**
         * Every answered request on 2026-11-17 took 0.96 to 2.35 s (31 finals, 25 drafts); the three
         * that hung took 10 s or more. A longer upload needs longer (2026-11-18 review B5): 3.5 s up
         * to 4 s of audio, 0.5 s more for each further second, never over [ATTEMPT_CAP_MS]. Two tries
         * at the cap fit inside [CascadeTimings.CLOUD_STT_FINAL_TIMEOUT_MS].
         */
        fun attemptTimeoutFor(audioMs: Long): Long {
            val extraSeconds = ((audioMs - ATTEMPT_BASE_AUDIO_MS).coerceAtLeast(0) + MS_PER_S - 1) / MS_PER_S
            return (ATTEMPT_TIMEOUT_MS + extraSeconds * ATTEMPT_PER_SECOND_MS).coerceAtMost(ATTEMPT_CAP_MS)
        }

        /** Longer than any attempt: the second request starts only on a failure or a hang. */
        private const val NO_HEDGE_MS = Long.MAX_VALUE

        const val ATTEMPT_TIMEOUT_MS = 3_500L
        const val ATTEMPT_CAP_MS = 8_000L
        private const val ATTEMPT_BASE_AUDIO_MS = 4_000L
        private const val ATTEMPT_PER_SECOND_MS = 500L
        private const val MS_PER_S = 1_000L
    }
}

/**
 * Text-to-speech on Gemini TTS in [voice] (Settings' cloud voice; [CloudSpeechWire.VOICE] by default). Sentence by
 * sentence ([streamsText] is false, so [ReplySpeaker] splits with [SentenceSplitter]): each [speak]
 * starts its own streamed
 * request at once (at most [maxParallel] at a time), so the first sentence plays while the model is
 * still writing the rest and the next one is already on its way.
 *
 * The scripted lines ([keepLines]) are kept once spoken, in memory and on disk ([KeptLineAudio]): the
 * same short line is not paid for, or waited for, twice.
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
    /** False only for Settings' test of a voice id, which must fail on a bad id rather than fall back. */
    private val allowFallback: Boolean = true,
    /** A request with no audio by now is abandoned and asked again, once ([FIRST_AUDIO_MS]). */
    private val firstAudioDeadlineMs: Long = FIRST_AUDIO_MS,
) : TextToSpeech {
    override val sampleRate = CloudSpeechWire.OUTPUT_RATE
    override val streamsText = false
    private val gate = Semaphore(maxParallel)

    /**
     * Warm-up fetches go one at a time on their own gate, never through [gate]: a reply's clips are
     * never queued behind them (2026-11-17 review: ~46 warm requests ahead of the first answer timed it
     * out and closed the conversation).
     */
    private val warmGate = Semaphore(1)

    /** Lines being warmed now, so a second warm of the same line is not sent twice. */
    private val warming = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())
    private val ids = AtomicInteger(0)

    /**
     * Flipped once if Google refuses the voice by name: the rest of the conversation is in Puck, and
     * conversations for a while after start in Puck too ([refusedVoice]), so the 400 is not paid again.
     */
    @Volatile private var fallbackVoice = allowFallback && refusedRecently(voice, now())

    private val voiceNow: String get() = if (fallbackVoice) CloudSpeechWire.FALLBACK_VOICE else voice

    /** Fallback allowed (not Settings' voice test) and the clip was not already sent in Puck. */
    private fun mayRetryInPuck(sentIn: String): Boolean = allowFallback && sentIn != CloudSpeechWire.FALLBACK_VOICE

    override val performsTags = true

    override fun speak(text: String): SpeechClip = speakTake(text, 0)

    override fun speakTake(text: String, take: Int): SpeechClip {
        val clip = Clip(ids.addAndGet(2), text, take = take)
        val key = keyOf(text, take)
        val cached = if (text in keepLines) kept[key] else null
        when {
            text.isBlank() -> clip.finish(TtsOutcome.Done(0, null))
            cached != null -> {
                clip.push(cached)
                clip.finish(TtsOutcome.Done(cached.size.toLong() / BYTES_PER_MS, 0))
            }
            else -> clip.start()
        }
        return clip
    }

    /** A kept line: its audio is in memory, or on disk and now loaded into memory ([KeptLineAudio]). */
    override fun inHand(text: String): Boolean = inHandTake(text, 0)

    override fun inHandTake(text: String, take: Int): Boolean = text in keepLines && kept[keyOf(text, take)] != null

    /**
     * Renders each kept line in [lines] that has no audio yet, so its first use plays from memory
     * (2026-11-16: the first filler cost a TTS round trip, 805 ms measured). Nobody listens to these
     * clips; a finished one stores its audio in [kept] as a spoken one would.
     */
    override fun warm(lines: Collection<String>): Int = warmTakes(lines, 0)

    override fun warmTakes(lines: Collection<String>, take: Int): Int {
        var asked = 0
        for (text in lines.distinct()) {
            val due = text.isNotBlank() && text in keepLines && kept[keyOf(text, take)] == null
            if (!due || !warming.add(keyOf(text, take))) continue
            Clip(ids.addAndGet(2), text, warmUp = true, take = take).start()
            asked++
        }
        if (asked > 0) log("tts_warm lines=$asked")
        return asked
    }

    /** Never called ([streamsText] is false); a refused stream makes [ReplySpeaker] use sentences. */
    override fun openStream(): StreamingSpeechClip = Clip(ids.addAndGet(2), "").also {
        it.finish(TtsOutcome.Failed("bad_request"))
    }

    private inner class Clip(
        override val id: Int,
        override val text: String,
        /** A warm-up fetch: its own gate, behind every reply ([warmGate]). */
        private val warmUp: Boolean = false,
        /** Which render of a kept line this is ([speakTake]); 0 is the line's own. */
        private val take: Int = 0,
    ) : StreamingSpeechClip {
        override val frames = Channel<ByteArray>(Channel.UNLIMITED)
        private val outcome = CompletableDeferred<TtsOutcome>()
        private var job: Job? = null
        private var odd: Byte? = null
        private var first = true
        @Volatile private var bytes = 0L
        private var firstAudioMs: Long? = null
        private val whole = ByteArrayOutputStream()

        @Suppress("CyclomaticComplexMethod") // voice fallback, one retry: each once
        fun start() {
            job = scope.launch {
                (if (warmUp) warmGate else gate).withPermit {
                    if (outcome.isCompleted) return@withPermit
                    val asked = now()
                    val sentIn = voiceNow
                    var o = request(asked)
                    // A voice Google refuses by name: this clip again in Puck at once, whatever another
                    // clip already flipped, and later conversations start in Puck for a while.
                    if (CloudSpeechWire.isVoiceRefused(o) && bytes == 0L && mayRetryInPuck(sentIn)) {
                        if (!fallbackVoice) {
                            fallbackVoice = true
                            refusedVoice = voice to now()
                            log("tts_voice_fallback from=$voice to=${CloudSpeechWire.FALLBACK_VOICE}")
                        }
                        o = request(asked)
                    }
                    // One retry for a dropped, hung or server-failed request, only before any audio
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
                                    // Filed under the voice that actually spoke it (a fallback may have changed it).
                                    if (text in keepLines) {
                                        kept[keyOf(text, take)] = synchronized(whole) { whole.toByteArray() }
                                    }
                                    TtsOutcome.Done(bytes / BYTES_PER_MS, firstAudioMs)
                                }
                        },
                    )
                }
            }.also { j ->
                j.invokeOnCompletion {
                    if (warmUp) warming.remove(keyOf(text, take))
                    finish(TtsOutcome.Cancelled)
                }
            }
        }

        /**
         * One request, abandoned if no audio arrives within [firstAudioDeadlineMs] of its start (counted
         * once the permit is held): a hung call ends as `network_first_audio_timeout`, retried once.
         */
        private suspend fun request(asked: Long): StreamOutcome = coroutineScope {
            val stalled = AtomicBoolean(false)
            val call = async { requestOnce(asked) }
            val watch = launch {
                delay(firstAudioDeadlineMs)
                if (bytes == 0L) {
                    stalled.set(true)
                    call.cancel()
                }
            }
            try {
                call.await()
            } catch (e: kotlinx.coroutines.CancellationException) {
                if (!stalled.get()) throw e
                log("tts_first_audio_timeout ms=$firstAudioDeadlineMs")
                StreamOutcome.Network(FIRST_AUDIO_TIMEOUT)
            } finally {
                watch.cancel()
            }
        }

        private suspend fun requestOnce(asked: Long): StreamOutcome {
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
                transport.stream(CloudSpeechWire.TTS_MODEL, CloudSpeechWire.speak(text, voiceNow)) { ev ->
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

    // The voice actually speaking is in the key: a Puck clip is never replayed as another voice, or the reverse.
    private fun keyOf(text: String, take: Int = 0) =
        "cloud|${CloudSpeechWire.TTS_MODEL}|$voiceNow|$sampleRate|$text" + if (take > 0) "#take$take" else ""

    companion object {
        /** 24 kHz PCM16 mono. */
        private const val BYTES_PER_MS = 48

        /** The sentence playing and the next one; more would only queue at Google. */
        const val MAX_PARALLEL = 2

        /** No audio from a TTS request by now: abandoned and asked again, once (2026-11-18 review B1). */
        const val FIRST_AUDIO_MS = 3_000L
        private const val FIRST_AUDIO_TIMEOUT = "first_audio_timeout"

        /** A voice Google refused, and when: later conversations start in Puck for [REFUSED_FOR_MS]. */
        @Volatile private var refusedVoice: Pair<String, Long>? = null
        private const val REFUSED_FOR_MS = 6L * 60 * 60 * 1000

        private fun refusedRecently(voice: String, now: Long): Boolean =
            refusedVoice?.let { (v, at) -> v == voice && now - at in 0 until REFUSED_FOR_MS } == true

        /** Test seam. */
        internal fun forgetRefusedVoiceForTest() {
            refusedVoice = null
        }

        /** The scripted lines' audio: in memory, and on disk across app starts ([KeptLineAudio]). */
        private val KEPT: MutableMap<String, ByteArray> get() = KeptLineAudio.SHARED
    }
}

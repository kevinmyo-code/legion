package com.kevin.legion.service.cascade

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** One WebSocket, as the protocol needs it. [OkHttpWire] in production, an in-memory fake in tests. */
interface WireSocket {
    fun sendText(text: String): Boolean
    fun sendBinary(bytes: ByteArray): Boolean
    fun close(code: Int, reason: String)
}

interface WireListener {
    fun onText(text: String)
    fun onBinary(bytes: ByteArray)

    /** The socket is gone, cleanly ([code] 1000/1001) or not. Called at most once. */
    fun onClosed(code: Int, reason: String)
}

fun interface WireConnector {
    fun connect(url: String, headers: List<Pair<String, String>>, listener: WireListener): WireSocket
}

/**
 * The phone side of the laptop voice service's wire contract, protocol 1: one WebSocket per
 * conversation, JSON control frames, binary audio frames with an 8-byte header.
 *
 * Implements both [SpeechToText] and [TextToSpeech]. STT ids are odd and TTS ids even, so an
 * `error` frame naming an id is never ambiguous (the contract keeps the two id spaces separate
 * and lets the phone choose them).
 *
 * Every wait here is bounded and never throws: a dropped socket completes every pending utterance
 * and clip with a `dropped` failure at once, so the session above can hand the conversation to
 * Live instead of waiting out a timeout. [onDrop] fires once, with the reason.
 */
class LaptopVoiceConnection(
    private val connector: WireConnector,
    private val config: LaptopVoiceConfig,
    private val clientName: String,
    private val scope: CoroutineScope,
    private val voice: String? = null,
    private val pingEveryMs: Long = PING_EVERY_MS,
    private val onDrop: (String) -> Unit = {},
) : SpeechToText, TextToSpeech {

    sealed interface OpenResult {
        data object Ready : OpenResult
        data class Failed(val reason: String) : OpenResult
    }

    @Volatile private var socket: WireSocket? = null
    private val ready = CompletableDeferred<OpenResult>()
    private val dropped = AtomicBoolean(false)
    @Volatile private var dropReason: String? = null
    private val nextStt = AtomicInteger(-1)
    private val nextTts = AtomicInteger(0)
    private val utterances = ConcurrentHashMap<Int, Utterance>()
    private val clips = ConcurrentHashMap<Int, Clip>()
    private var pinger: Job? = null

    @Volatile override var sampleRate: Int = DEFAULT_TTS_RATE
        private set

    /**
     * From `ready.tts.text_streaming` (contract 4a). False until `ready`, and false again if the
     * service refuses a stream (`bad_request` on `tts.stream.*`), so the caller falls back to
     * [speak] per sentence.
     */
    @Volatile override var streamsText: Boolean = false
        private set

    /**
     * The service's own end of turn: advertised in `ready.stt` (`end_of_turn`, `endpointing` or
     * `endpoint` true; the contract names the `stt.endpoint` event but not yet a flag), or proven
     * by the first `stt.endpoint` event this connection receives.
     */
    @Volatile override var endsTurns: Boolean = false
        private set

    val isDropped: Boolean get() = dropped.get()

    /** Connects, says hello and waits for `ready`. Never throws. */
    suspend fun open(timeoutMs: Long): OpenResult {
        val s = try {
            connector.connect(config.conversationUrl, config.requestHeaders(), listener)
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            return OpenResult.Failed("connect_${e.javaClass.simpleName}")
        }
        socket = s
        val hello = JSONObject().put("type", "hello").put("protocol", PROTOCOL).put("client", clientName)
            .put("lang", "auto")
        voice?.let { hello.put("voice", it) }
        if (!s.sendText(hello.toString())) return fail("hello_not_sent")
        val result = withTimeoutOrNull(timeoutMs) { ready.await() } ?: return fail("ready_timeout")
        if (result is OpenResult.Failed) {
            close(result.reason)
            return result
        }
        if (!dropped.get()) {
            pinger = scope.launch {
                var t = 0
                while (isActive && !dropped.get()) {
                    delay(pingEveryMs)
                    send(JSONObject().put("type", "ping").put("t", ++t))
                }
            }
        }
        return result
    }

    private fun fail(reason: String): OpenResult {
        close(reason)
        return OpenResult.Failed(reason)
    }

    /** Says bye and closes. Idempotent. Pending waits complete as `closed`. */
    fun close(reason: String = "bye") {
        pinger?.cancel()
        val s = socket
        if (s != null && !dropped.get()) {
            runCatching { s.sendText(JSONObject().put("type", "bye").toString()) }
            runCatching { s.close(NORMAL_CLOSE, reason.take(MAX_CLOSE_REASON)) }
        }
        markDropped("closed")
    }

    // --- SpeechToText --------------------------------------------------------------------------

    override fun begin(onEndpoint: (prob: Float) -> Unit): SttUtterance {
        val id = nextStt.addAndGet(2)
        val u = Utterance(id, onEndpoint)
        utterances[id] = u
        if (dropped.get()) {
            u.result.complete(SttResult.Failed(dropReason ?: "dropped"))
        } else {
            send(JSONObject().put("type", "stt.start").put("id", id))
        }
        return u
    }

    private inner class Utterance(override val id: Int, val onEndpoint: (Float) -> Unit) : SttUtterance {
        val result = CompletableDeferred<SttResult>()
        val ended = AtomicBoolean(false)

        override fun audio(pcm: ByteArray, offset: Int, length: Int) {
            if (ended.get() || result.isCompleted || length <= 0) return
            val even = length - (length % 2)
            if (even == 0) return
            socket?.sendBinary(frame(KIND_MIC, id, pcm, offset, even))
        }

        override fun end() {
            if (ended.compareAndSet(false, true) && !result.isCompleted) {
                send(JSONObject().put("type", "stt.end").put("id", id))
            }
        }

        override fun cancel() {
            ended.set(true)
            if (!result.isCompleted) send(JSONObject().put("type", "stt.cancel").put("id", id))
            result.complete(SttResult.Failed("cancelled"))
            utterances.remove(id)
        }

        override suspend fun result(timeoutMs: Long): SttResult {
            val r = withTimeoutOrNull(timeoutMs) { result.await() } ?: SttResult.Failed("timeout")
            utterances.remove(id)
            return r
        }
    }

    // --- TextToSpeech --------------------------------------------------------------------------

    override fun speak(text: String): SpeechClip {
        val id = nextTts.addAndGet(2)
        val clip = Clip(id, streamed = false)
        clip.sent.append(text)
        clips[id] = clip
        when {
            dropped.get() -> clip.finish(TtsOutcome.Failed(dropReason ?: "dropped"))
            text.isBlank() -> clip.finish(TtsOutcome.Done(0, null))
            else -> {
                val msg = JSONObject().put("type", "tts.speak").put("id", id).put("text", text.take(MAX_TTS_CHARS))
                voice?.let { msg.put("voice", it) }
                if (!send(msg)) clip.finish(TtsOutcome.Failed("not_sent"))
            }
        }
        return clip
    }

    override fun openStream(): StreamingSpeechClip {
        val id = nextTts.addAndGet(2)
        val clip = Clip(id, streamed = true)
        clips[id] = clip
        if (dropped.get()) {
            clip.finish(TtsOutcome.Failed(dropReason ?: "dropped"))
        } else {
            val msg = JSONObject().put("type", "tts.stream.start").put("id", id)
            voice?.let { msg.put("voice", it) }
            if (!send(msg)) clip.finish(TtsOutcome.Failed("not_sent"))
        }
        return clip
    }

    private inner class Clip(override val id: Int, val streamed: Boolean) : StreamingSpeechClip {
        override val frames = Channel<ByteArray>(Channel.UNLIMITED)
        private val outcome = CompletableDeferred<TtsOutcome>()
        val cancelled = AtomicBoolean(false)
        private val finished = AtomicBoolean(false)
        val sent = StringBuffer()

        override val text: String get() = sent.toString()

        override fun append(text: String) {
            if (!streamed || text.isEmpty() || finished.get() || outcome.isCompleted) return
            sent.append(text)
            send(JSONObject().put("type", "tts.stream.text").put("id", id).put("text", text))
        }

        override fun finish() {
            if (!streamed || !finished.compareAndSet(false, true) || outcome.isCompleted) return
            if (sent.isBlank()) {
                // Nothing was said into it: close it without asking for silence.
                send(JSONObject().put("type", "tts.cancel").put("id", id))
                finish(TtsOutcome.Done(0, null))
            } else {
                send(JSONObject().put("type", "tts.stream.end").put("id", id))
            }
        }

        fun push(pcm: ByteArray) {
            if (!cancelled.get() && !outcome.isCompleted) frames.trySend(pcm)
        }

        fun finish(o: TtsOutcome) {
            if (outcome.complete(o)) frames.close()
            clips.remove(id)
        }

        override fun cancel() {
            if (outcome.isCompleted) return
            cancelled.set(true)
            send(JSONObject().put("type", "tts.cancel").put("id", id))
            finish(TtsOutcome.Cancelled)
        }

        override suspend fun outcome(): TtsOutcome = outcome.await()
    }

    // --- wire ----------------------------------------------------------------------------------

    private fun send(msg: JSONObject): Boolean {
        val s = socket ?: return false
        if (dropped.get()) return false
        return runCatching { s.sendText(msg.toString()) }.getOrDefault(false)
    }

    private fun markDropped(reason: String) {
        if (!dropped.compareAndSet(false, true)) return
        dropReason = reason
        pinger?.cancel()
        ready.complete(OpenResult.Failed(reason))
        utterances.values.forEach { it.result.complete(SttResult.Failed(reason)) }
        utterances.clear()
        clips.values.toList().forEach { it.finish(TtsOutcome.Failed(reason)) }
        if (reason != "closed") onDrop(reason)
    }

    private val listener = object : WireListener {
        override fun onText(text: String) {
            val m = runCatching { JSONObject(text) }.getOrNull() ?: return
            val id = if (m.has("id") && !m.isNull("id")) m.optInt("id") else null
            when (m.optString("type")) {
                "ready" -> {
                    val proto = m.optInt("protocol", -1)
                    if (proto != PROTOCOL) {
                        ready.complete(OpenResult.Failed("protocol_$proto"))
                        return
                    }
                    val tts = m.optJSONObject("tts")
                    tts?.optInt("sample_rate", DEFAULT_TTS_RATE)?.takeIf { it > 0 }?.let { sampleRate = it }
                    streamsText = tts?.optBoolean("text_streaming", false) == true
                    val stt = m.optJSONObject("stt")
                    endsTurns = stt != null && EOT_FLAGS.any { stt.optBoolean(it, false) }
                    ready.complete(OpenResult.Ready)
                }
                "stt.endpoint" -> {
                    endsTurns = true
                    val u = id?.let { utterances[it] } ?: return
                    if (!u.ended.get()) u.onEndpoint(m.optDouble("prob", 1.0).toFloat())
                }
                "stt.final" -> {
                    val u = id?.let { utterances[it] } ?: return
                    val r = if (m.optBoolean("cancelled", false)) {
                        SttResult.Failed("cancelled")
                    } else {
                        SttResult.Text(
                            text = m.optString("text").trim(),
                            lang = m.optString("lang").takeIf { it.isNotBlank() && it != "null" },
                            audioMs = m.optLong("audio_ms", -1L).takeIf { it >= 0 },
                            decodeMs = m.optLong("decode_ms", -1L).takeIf { it >= 0 },
                        )
                    }
                    u.result.complete(r)
                }
                "tts.done" -> {
                    val c = id?.let { clips[it] } ?: return
                    c.finish(
                        if (m.optBoolean("cancelled", false)) {
                            TtsOutcome.Cancelled
                        } else {
                            TtsOutcome.Done(
                                m.optLong("audio_ms", -1L).takeIf { it >= 0 },
                                m.optLong("first_audio_ms", -1L).takeIf { it >= 0 },
                            )
                        },
                    )
                }
                "error" -> onError(id, m.optString("code").ifBlank { "internal" })
                else -> Unit // pong, stt.partial, and anything a later protocol adds
            }
        }

        override fun onBinary(bytes: ByteArray) {
            if (bytes.size < HEADER_BYTES || bytes[0] != KIND_TTS) return
            val id = readId(bytes)
            val clip = clips[id] ?: return // cancelled or finished: late frames are dropped
            clip.push(bytes.copyOfRange(HEADER_BYTES, bytes.size))
        }

        override fun onClosed(code: Int, reason: String) {
            markDropped(if (code == BUSY_CLOSE) "busy" else "dropped_$code")
        }
    }

    private fun onError(id: Int?, code: String) {
        if (id == null) {
            if (code == "protocol") {
                ready.complete(OpenResult.Failed("protocol"))
                markDropped("protocol")
            }
            return
        }
        if (id % 2 != 0) {
            utterances[id]?.result?.complete(SttResult.Failed(code))
        } else {
            val clip = clips[id]
            // A service whose voice cannot take streamed text refuses tts.stream.* (contract 4a):
            // stop offering it for the rest of this connection.
            if (clip != null && clip.streamed && code == "bad_request") streamsText = false
            clip?.finish(TtsOutcome.Failed(code))
        }
    }

    companion object {
        const val PROTOCOL = 1
        const val DEFAULT_TTS_RATE = 24_000
        const val HEADER_BYTES = 8
        const val KIND_MIC: Byte = 0x01
        const val KIND_TTS: Byte = 0x02
        const val MAX_TTS_CHARS = 1_000

        /** `ready.stt` flags read as "the service ends turns itself". */
        val EOT_FLAGS = listOf("end_of_turn", "endpointing", "endpoint")
        const val NORMAL_CLOSE = 1000
        const val BUSY_CLOSE = 1013

        /** Under the service's 120 s idle close, with room for a slow network. */
        const val PING_EVERY_MS = 30_000L
        private const val MAX_CLOSE_REASON = 60
        private const val BYTE_MASK = 0xff

        /** `[kind, 0, 0, 0, id LE uint32, pcm...]`. */
        fun frame(kind: Byte, id: Int, pcm: ByteArray, offset: Int = 0, length: Int = pcm.size): ByteArray {
            val out = ByteArray(HEADER_BYTES + length)
            out[0] = kind
            out[4] = (id and BYTE_MASK).toByte()
            out[5] = ((id shr 8) and BYTE_MASK).toByte()
            out[6] = ((id shr 16) and BYTE_MASK).toByte()
            out[7] = ((id shr 24) and BYTE_MASK).toByte()
            System.arraycopy(pcm, offset, out, HEADER_BYTES, length)
            return out
        }

        fun readId(bytes: ByteArray): Int =
            (bytes[4].toInt() and BYTE_MASK) or
                ((bytes[5].toInt() and BYTE_MASK) shl 8) or
                ((bytes[6].toInt() and BYTE_MASK) shl 16) or
                ((bytes[7].toInt() and BYTE_MASK) shl 24)
    }
}

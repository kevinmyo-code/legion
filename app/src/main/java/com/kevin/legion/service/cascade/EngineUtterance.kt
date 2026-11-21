package com.kevin.legion.service.cascade

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One of his turns spoken to the engine: the [SttUtterance] the cascade feeds, so the phone's own
 * end of turn, noise rule, stop words and its own crisis check run on it unchanged ([CascadeSession.runTurn]).
 *
 * - [audio] streams `A` frames to `POST /api/agents/turn/stream` while he speaks (the `M` frame went
 *   at [begin]), and keeps the clip (28 s cap) for the batch route.
 * - [end] is the phone's end of turn. Noise is decided first, from the audio alone
 *   ([SttGuard.voicedMs]): a clip with no voice is dropped and nothing runs on the engine (no `E`).
 *   Otherwise the `E` frame with the byte total, and the body ends.
 * - A stream refusal the contract names (`audio_gap`, the stream switch off, a broken upload) sends
 *   the held clip to `POST /api/agents/turn` in the same turn ([EngineBrainRules.streamFallsBack]).
 * - [result] is the engine's `heard` words. The rest of the stream is kept for the turn ([takeTurn]);
 *   the engine's own crisis line comes after `heard`, so the turn sees it ([EngineTurnSink.crisis]).
 */
internal class EngineUtterance(
    override val id: Int,
    private val wire: EngineWire,
    private val scope: CoroutineScope,
    private val meta: JSONObject,
    streamed: Boolean,
    private val onSwitchedOff: (streamOnly: Boolean) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
) : SttUtterance {
    private val clip = ByteArrayOutputStream()
    private var upload: EngineUpload? = if (streamed) wire.openStream(meta) else null
    @Volatile private var sentBytes = 0L
    @Volatile private var uploadBroken = false
    private val answer = CompletableDeferred<WireReply>()
    private val ended = AtomicBoolean(false)
    private val claimed = AtomicBoolean(false)

    /** What the turn needs after [result]: the events read so far, and the rest of the stream. */
    @Volatile private var stream: WireReply.Events? = null
    private val prelude = mutableListOf<EngineEvent>()
    @Volatile private var turnId: String? = null
    @Volatile private var finished = false

    /** A refusal where the turn's stream was due (a 429 cap, a 409), for the words he hears. */
    @Volatile var refusal: WireReply.Json? = null
        private set

    /** The route this turn took, for the timing line: `stream`, `batch`, or `none` (noise, cancel). */
    @Volatile var route = if (streamed) ROUTE_STREAM else ROUTE_BATCH
        private set

    /** Timing marks (epoch ms), for [EngineTimings]. */
    @Volatile var endAtMs = 0L
        private set
    @Volatile var postedAtMs = 0L
        private set
    @Volatile var firstEventAtMs = 0L
        private set
    @Volatile var heardAtMs = 0L
        private set

    override fun audio(pcm: ByteArray, offset: Int, length: Int) {
        if (ended.get() || length <= 0) return
        synchronized(clip) {
            val n = minOf(length, MAX_AUDIO_BYTES - clip.size())
            if (n > 0) {
                clip.write(pcm, offset, n)
                val up = upload
                if (up != null && EngineFrames.audio(pcm, offset, n).all { up.send(it) }) {
                    sentBytes += n
                } else if (up != null) {
                    uploadBroken = true
                }
            }
        }
    }

    override fun end() {
        if (!ended.compareAndSet(false, true)) return
        endAtMs = now()
        val pcm = synchronized(clip) { clip.toByteArray() }
        if (SttGuard.voicedMs(pcm) < SttGuard.MIN_VOICED_MS) {
            upload?.abort()
            route = ROUTE_NONE
            answer.complete(NOISE)
            return
        }
        scope.launch { answer.complete(send(pcm)) }
    }

    private suspend fun send(pcm: ByteArray): WireReply {
        val up = upload
        val streamed = if (up != null && !uploadBroken && up.send(EngineFrames.end(sentBytes))) {
            up.finish()
            postedAtMs = now()
            up.reply()
        } else {
            up?.abort()
            null
        }
        if (streamed != null && !EngineBrainRules.streamFallsBack(streamed)) return streamed
        if (streamed != null && EngineBrainRules.switchedOff(streamed)) onSwitchedOff(true)
        route = ROUTE_BATCH
        val fields = mutableMapOf("kind" to "user", "client" to meta.optString("client", "phone"))
        fields["contract_version"] = EngineBrainRules.CONTRACT_VERSION.toString()
        meta.optString("conversation_id").takeIf { it.isNotBlank() }?.let { fields["conversation_id"] = it }
        postedAtMs = now()
        val batch = wire.turnClip(fields, CloudSpeechWire.wav(pcm, SAMPLE_RATE))
        if (EngineBrainRules.switchedOff(batch)) onSwitchedOff(false)
        return batch
    }

    override fun cancel() {
        if (ended.compareAndSet(false, true)) {
            upload?.abort()
            route = ROUTE_NONE
            answer.complete(CANCELLED)
        }
    }

    /**
     * The engine's words for this turn. Waits for the answer and then for `heard`, each at most
     * [EngineBrainRules.FIRST_EVENT_MS] (client contract section 9); [timeoutMs] is the cloud
     * transcriber's and too short for a turn that also crosses the network.
     */
    override suspend fun result(timeoutMs: Long): SttResult {
        val reply = withTimeoutOrNull(EngineBrainRules.FIRST_EVENT_MS) { answer.await() }
        refusal = reply as? WireReply.Json
        val voiced = SttGuard.voicedMs(synchronized(clip) { clip.toByteArray() })
        return when {
            reply === NOISE -> SttResult.Text("", null, null, null, voiced)
            reply === CANCELLED -> SttResult.Failed("cancelled")
            reply is WireReply.Events -> heard(reply, voiced)
            reply is WireReply.Json && EngineBrainRules.switchedOff(reply) -> SttResult.Failed(SWITCHED_OFF)
            else -> SttResult.Failed(UNREACHABLE)
        }
    }

    private suspend fun heard(reply: WireReply.Events, voiced: Long): SttResult {
        stream = reply
        var outcome: SttResult? = null
        while (outcome == null) {
            val next = withTimeoutOrNull(EngineBrainRules.FIRST_EVENT_MS) { reply.events.receiveCatching() }
            val event = next?.getOrNull()
            if (event != null && firstEventAtMs == 0L) firstEventAtMs = now()
            if (event != null) prelude.add(event)
            outcome = when (event) {
                null -> SttResult.Failed(UNREACHABLE)
                is EngineEvent.Turn -> {
                    turnId = event.turnId
                    null
                }
                is EngineEvent.Heard -> heardNow(event.text, voiced)
                is EngineEvent.Done -> heardNow("", voiced).also { finished = true }
                is EngineEvent.Failed -> SttResult.Failed(MISSED)
                else -> null
            }
        }
        return outcome
    }

    private fun heardNow(text: String, voiced: Long): SttResult {
        heardAtMs = now()
        return SttResult.Text(text, null, null, null, voiced)
    }

    /**
     * The rest of this turn's stream (everything read so far first), for the one turn that answers
     * it; null when there is none or it was already taken.
     */
    fun takeTurn(): WireReply.Events? {
        val s = stream
        if (s == null || !claimed.compareAndSet(false, true)) return null
        val out = Channel<EngineEvent>(Channel.UNLIMITED)
        prelude.forEach { out.trySend(it) }
        scope.launch {
            for (e in s.events) out.send(e)
            out.close()
        }
        return WireReply.Events(out) { s.close() }
    }

    /**
     * This turn will not be answered by the engine (a stop, noise, the phone loop took it): the
     * engine's turn is cancelled and its stream closed. Nothing happens once [takeTurn] claimed it.
     */
    fun release() {
        if (!claimed.compareAndSet(false, true)) return
        val s = stream
        scope.launch {
            val id = turnId ?: s?.let { firstTurnId(it) }
            s?.close()
            if (id != null && !finished) wire.cancel(id)
        }
    }

    private suspend fun firstTurnId(s: WireReply.Events): String? =
        withTimeoutOrNull(EngineBrainRules.FIRST_EVENT_MS) {
            var id: String? = null
            for (e in s.events) {
                if (e is EngineEvent.Turn) {
                    id = e.turnId
                    break
                }
            }
            id
        }

    companion object {
        const val UNREACHABLE = "engine_unreachable"
        const val SWITCHED_OFF = "engine_switched_off"
        const val MISSED = "engine_missed"
        const val ROUTE_STREAM = "stream"
        const val ROUTE_BATCH = "batch"
        const val ROUTE_NONE = "none"
        private const val SAMPLE_RATE = 16_000

        /** The engine's `MAX_UTTERANCE_SECONDS` (28 s) of 16 kHz PCM16. */
        const val MAX_AUDIO_BYTES = 28 * 32_000
        private val NOISE = WireReply.Unreachable("noise")
        private val CANCELLED = WireReply.Unreachable("cancelled")
    }
}

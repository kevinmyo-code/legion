package com.kevin.legion.service.cascade

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/** What one [EngineUtterance] needs from its conversation. */
internal data class UtteranceSetup(
    val wire: EngineWire,
    /** The session's scope: the turn's own work. */
    val scope: CoroutineScope,
    /** A scope that outlives the session by a bounded time: cancels that must reach the engine. */
    val tail: CoroutineScope,
    val meta: JSONObject,
    val streamed: Boolean,
    /** The last cancel still on its way: the new stream opens after it (or after a short cap). */
    val gate: Job?,
)

/**
 * One of his turns spoken to the engine: the [SttUtterance] the cascade feeds, so the phone's own
 * end of turn, noise rule, stop words and its own crisis check run on it unchanged ([CascadeSession.runTurn]).
 *
 * - The stream (`POST /api/agents/turn/stream`, `M` frame first) opens once the previous turn's
 *   cancel has gone (review B-11: one stream per member), at most [GATE_MS] after he starts; audio
 *   captured meanwhile is held and sent first. Then [audio] streams `A` frames while he speaks.
 * - [end] is the phone's end of turn. A clip with no voice at all sends nothing (no `E`: nothing
 *   runs on the engine). Otherwise the `E` frame with the byte total, and the body ends.
 * - A stream refusal the contract names (`audio_gap`, the stream switch off, `stream_busy`, a broken
 *   upload BEFORE `E`) sends the held clip to `POST /api/agents/turn` in the same turn. A lost
 *   answer AFTER `E` is never re-posted (review B-7: the engine may be running that turn).
 * - [result] is the engine's `heard` words. The rest of the stream is kept for the turn ([takeTurn]);
 *   the engine's own crisis line comes after `heard`, so the turn sees it ([EngineTurnSink.crisis]).
 */
internal class EngineUtterance(
    override val id: Int,
    private val setup: UtteranceSetup,
    private val onSwitchedOff: (streamOnly: Boolean) -> Unit,
    /** A cancel this utterance sent on its way ([release]): the next stream waits for it. */
    private val onCancelling: (Job) -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
) : SttUtterance {
    private val wire = setup.wire
    private val clip = ByteArrayOutputStream()
    @Volatile private var upload: EngineUpload? = null
    @Volatile private var dropped = false
    @Volatile private var sentBytes = 0L
    @Volatile private var uploadBroken = false
    private val opening: Job? = if (setup.streamed) setup.scope.launch { open() } else null
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
    @Volatile var route = if (setup.streamed) ROUTE_STREAM else ROUTE_BATCH
        private set

    /** Timing marks (epoch ms), for [EngineTimings]. [postedAtMs] > 0: the engine may be running it. */
    @Volatile var endAtMs = 0L
        private set
    @Volatile var postedAtMs = 0L
        private set
    @Volatile var firstEventAtMs = 0L
        private set
    @Volatile var heardAtMs = 0L
        private set

    private suspend fun open() {
        setup.gate?.let { g -> withTimeoutOrNull(GATE_MS) { g.join() } }
        synchronized(clip) {
            if (dropped) return
            val up = wire.openStream(setup.meta)
            upload = up
            val held = clip.toByteArray()
            val sent = EngineFrames.audio(held, 0, held.size).all { up.send(it) }
            if (sent) sentBytes = held.size.toLong() else uploadBroken = true
        }
    }

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
        // No voice at all: nothing goes to the engine. Anything voiced goes (the engine gates noise
        // too); the phone's one-word rule needs words, which only the engine has now.
        if (SttGuard.voicedMs(pcm) == 0L) {
            synchronized(clip) { dropped = true }
            opening?.cancel()
            upload?.abort()
            route = ROUTE_NONE
            answer.complete(NOISE)
            return
        }
        setup.scope.launch { answer.complete(send(pcm)) }
    }

    private suspend fun send(pcm: ByteArray): WireReply {
        opening?.join()
        val up = upload
        val endSent = up != null && !uploadBroken && up.send(EngineFrames.end(sentBytes))
        val streamed = if (endSent) {
            up?.finish()
            postedAtMs = now()
            up?.reply()
        } else {
            up?.abort()
            null
        }
        // After `E`, a lost answer is the engine's to finish: never posted again (review B-7).
        if (streamed != null && (streamed is WireReply.Unreachable || !EngineBrainRules.streamFallsBack(streamed))) {
            return streamed
        }
        if (streamed != null && EngineBrainRules.switchedOff(streamed)) onSwitchedOff(true)
        return postClip(pcm)
    }

    /** The whole clip on `POST /api/agents/turn` (multipart), the stream route's fallback. */
    private suspend fun postClip(pcm: ByteArray): WireReply {
        route = ROUTE_BATCH
        val fields = mutableMapOf("kind" to "user", "client" to setup.meta.optString("client", "phone"))
        fields["contract_version"] = EngineBrainRules.CONTRACT_VERSION.toString()
        setup.meta.optString("conversation_id").takeIf { it.isNotBlank() }?.let { fields["conversation_id"] = it }
        postedAtMs = now()
        val reply = wire.turnClip(fields, CloudSpeechWire.wav(pcm, SAMPLE_RATE))
        if (EngineBrainRules.switchedOff(reply)) onSwitchedOff(false)
        return reply
    }

    override fun cancel() {
        if (ended.compareAndSet(false, true)) {
            synchronized(clip) { dropped = true }
            opening?.cancel()
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
                is EngineEvent.Heard -> SttResult.Text(event.text, null, null, null, voiced)
                is EngineEvent.Done -> SttResult.Text("", null, null, null, voiced).also { finished = true }
                is EngineEvent.Failed -> SttResult.Failed(MISSED)
                else -> null
            }
        }
        if (outcome is SttResult.Text) heardAtMs = now()
        return outcome
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
        setup.scope.launch {
            try {
                var next = s.events.receiveCatching().getOrNull()
                while (next != null) {
                    out.send(next)
                    next = s.events.receiveCatching().getOrNull()
                }
            } finally {
                out.close()
            }
        }
        return WireReply.Events(out) { s.close() }
    }

    /**
     * This turn will not be answered by the engine (a stop, noise, the phone loop took it): the
     * engine's turn is cancelled and its stream closed, on the tail scope so a close of the whole
     * conversation does not lose it (review B-8). Nothing happens once [takeTurn] claimed it.
     */
    fun release() {
        if (!claimed.compareAndSet(false, true)) return
        val job = setup.tail.launch {
            withTimeoutOrNull(EngineBrainRules.TAIL_MS) {
                // The answer may still be on its way: wait for it, so its turn is cancelled too.
                val came = stream ?: withTimeoutOrNull(EngineBrainRules.FIRST_EVENT_MS) { answer.await() }
                val s = came as? WireReply.Events
                val id = turnId ?: s?.let { firstTurnId(it) }
                s?.close()
                if (id != null && !finished) wire.cancel(id)
            }
        }
        onCancelling(job)
    }

    private suspend fun firstTurnId(s: WireReply.Events): String? =
        withTimeoutOrNull(EngineBrainRules.FIRST_EVENT_MS) {
            var id: String? = null
            var next = s.events.receiveCatching().getOrNull()
            while (id == null && next != null) {
                if (next is EngineEvent.Turn) id = next.turnId
                next = if (id == null) s.events.receiveCatching().getOrNull() else null
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

        /** The longest a new stream waits for the last turn's cancel to go (review B-11). */
        const val GATE_MS = 1_500L

        /** The engine's `MAX_UTTERANCE_SECONDS` (28 s) of 16 kHz PCM16. */
        const val MAX_AUDIO_BYTES = 28 * 32_000
        private val NOISE = WireReply.Unreachable("noise")
        private val CANCELLED = WireReply.Unreachable("cancelled")
    }
}

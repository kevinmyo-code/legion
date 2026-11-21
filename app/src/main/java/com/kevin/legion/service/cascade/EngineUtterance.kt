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
    /**
     * The turn's metadata, built when the request goes (so a cut-off reply's `heard_until`, noted
     * at the barge-in, is in it): `client`, `contract_version`, `conversation_id`, `speak: false`,
     * `client_facts`. This utterance adds its own `turn_id` and `go: false`.
     */
    val meta: () -> JSONObject,
    val streamed: Boolean,
    /** The last cancel still on its way: the new stream opens after it (or after a short cap). */
    val gate: Job?,
)

/**
 * One of his turns spoken to the engine: the [SttUtterance] the cascade feeds, so the phone's own
 * end of turn, noise rule, stop words and its own crisis check run on it unchanged ([CascadeSession.runTurn]).
 *
 * - Every request carries this phone's own `turn_id` (a re-post never runs twice: 409 `duplicate`)
 *   and `go: false` (Contract v2 hold): after `heard` nothing acts on the engine until the phone
 *   posts [go]; a turn the phone keeps or drops is taken back ([release], `take`).
 * - The stream (`POST /api/agents/turn/stream`, `M` frame first) opens once the previous turn's
 *   cancel has gone (review B-11: one stream per member), at most [GATE_MS] after he starts; audio
 *   captured meanwhile is held and sent first. Then [audio] streams `A` frames while he speaks.
 * - [end] is the phone's end of turn. A clip with no voice at all sends nothing (no `E`: nothing
 *   runs on the engine). Otherwise the `E` frame with the byte total, and the body ends.
 * - A stream refusal the contract names (`audio_gap`, the stream switch off, `stream_busy` after
 *   300 ms, a broken upload) sends the held clip to `POST /api/agents/turn` in the same turn, with
 *   the same `turn_id`: an answer lost after `E` is posted again only that way, and the engine
 *   answers `duplicate` if it had the turn (review B-7). Held, it acted on nothing.
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

    /** This phone's id for the turn: the engine's turn row is made with it. */
    val turnId: String = java.util.UUID.randomUUID().toString()
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
            val up = wire.openStream(setup.meta().put("turn_id", turnId).put("go", false))
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
        // This device's brain is the phone (409 `brain_phone`): the conversation goes back to it.
        val brainPhone = (streamed as? WireReply.Json)?.code == BRAIN_PHONE
        if (brainPhone) onSwitchedOff(false)
        if (streamed != null && (brainPhone || !EngineBrainRules.streamFallsBack(streamed))) return streamed
        if (streamed != null && EngineBrainRules.switchedOff(streamed)) onSwitchedOff(true)
        // The engine waited 1.5 s for the last stream already: once more after 300 ms, on the batch route.
        if ((streamed as? WireReply.Json)?.code == "stream_busy") kotlinx.coroutines.delay(BUSY_RETRY_MS)
        return postClip(pcm)
    }

    /** The whole clip on `POST /api/agents/turn` (multipart), the stream route's fallback. */
    private suspend fun postClip(pcm: ByteArray): WireReply {
        route = ROUTE_BATCH
        val meta = setup.meta()
        val fields = mutableMapOf("kind" to "user", "turn_id" to turnId, "go" to "false", "speak" to "false")
        for (key in listOf("client", "contract_version", "conversation_id")) {
            meta.opt(key)?.let { fields[key] = it.toString() }
        }
        meta.optJSONObject("client_facts")?.let { fields["client_facts"] = it.toString() }
        postedAtMs = now()
        val wav = CloudSpeechWire.wav(pcm, SAMPLE_RATE)
        var reply = wire.turnClip(fields, wav)
        if ((reply as? WireReply.Json)?.code == "conversation_busy") {
            kotlinx.coroutines.delay(BUSY_RETRY_MS)
            reply = wire.turnClip(fields, wav)
        }
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
                is EngineEvent.Heard -> SttResult.Text(event.text, null, null, null, voiced)
                is EngineEvent.Done -> SttResult.Text("", null, null, null, voiced).also { finished = true }
                is EngineEvent.Failed -> SttResult.Failed(MISSED)
                else -> null
            }
        }
        if (outcome is SttResult.Text) heardAtMs = now()
        return outcome
    }

    /** The engine answers this turn: its held writes and relays may act from now (Contract v2). */
    suspend fun go(): WireReply = wire.decide(turnId, go = true)

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
        val sent = postedAtMs > 0
        val job = setup.tail.launch {
            withTimeoutOrNull(EngineBrainRules.TAIL_MS) {
                stream?.close()
                // Held: a take means nothing acts. Before its row exists the engine answers 404; then
                // once more when the answer comes (and the engine's own 5 s silence covers the rest).
                val taken = if (sent && !finished) wire.decide(turnId, go = false) else null
                val early = (taken as? WireReply.Json)?.status == NOT_FOUND
                val came = if (early) withTimeoutOrNull(EngineBrainRules.FIRST_EVENT_MS) { answer.await() } else null
                (came as? WireReply.Events)?.close()
                if (came is WireReply.Events) wire.decide(turnId, go = false)
            }
        }
        onCancelling(job)
    }

    companion object {
        const val UNREACHABLE = "engine_unreachable"
        const val SWITCHED_OFF = "engine_switched_off"
        const val MISSED = "engine_missed"
        const val ROUTE_STREAM = "stream"
        const val ROUTE_BATCH = "batch"
        const val ROUTE_NONE = "none"
        private const val SAMPLE_RATE = 16_000

        private const val NOT_FOUND = 404
        private const val BRAIN_PHONE = "brain_phone"

        /** After the engine's own 1.5 s wait, `stream_busy` / `conversation_busy` once more after this. */
        const val BUSY_RETRY_MS = 300L

        /** The longest a new stream waits for the last turn's cancel to go (review B-11). */
        const val GATE_MS = 1_500L

        /** The engine's `MAX_UTTERANCE_SECONDS` (28 s) of 16 kHz PCM16. */
        const val MAX_AUDIO_BYTES = 28 * 32_000
        private val NOISE = WireReply.Unreachable("noise")
        private val CANCELLED = WireReply.Unreachable("cancelled")
    }
}

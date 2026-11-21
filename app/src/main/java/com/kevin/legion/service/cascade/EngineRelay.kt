package com.kevin.legion.service.cascade

import com.kevin.legion.service.cascade.EngineBrainRules.AfterPost
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/** What the relay tells the turn as it goes: every event, and each relayed call around its run. */
internal interface EngineRelaySink {
    fun event(event: EngineEvent)

    /** A round's calls are about to run, in order (the tool-aware filler is chosen from them). */
    fun toolsStarting(calls: List<EngineCall>)

    fun toolEnded(call: EngineCall, outcome: CallOutcome)
}

/** How an engine turn ended, for the phone's own end of turn. [words] is a line code says, or null. */
internal data class EngineTurnEnd(
    val status: String,
    val turnId: String?,
    val tainted: Boolean = false,
    val end: Boolean = false,
    val writesRan: Boolean = false,
    val words: String? = null,
    val deviceActions: List<EngineCall> = emptyList(),
) {
    companion object {
        const val FAILED = "failed"
    }
}

/**
 * One result post (relay contract section 4), with the contract's retries: a 429 `throttled`
 * after `Retry-After` (at most three times), a 503 `no_key` once after 5 s.
 */
internal class EngineResultPoster(
    private val wire: EngineWire,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    suspend fun post(turnId: String, call: EngineCall, outcome: CallOutcome): WireReply {
        val body = JSONObject()
            .put("call_id", call.callId)
            .put("name", call.name)
            .put("outcome", outcome.outcome)
            .put("words", outcome.words.ifBlank { "No words." }.take(EngineBrainRules.MAX_WORDS_CHARS))
            .put("ms", outcome.ms)
        outcome.result?.let { body.put("result", it) }
        var reply = wire.toolResult(turnId, body)
        var tries = 0
        var wait = EngineBrainRules.retryWaitMs(reply, tries)
        while (wait != null) {
            pause(wait)
            reply = wire.toolResult(turnId, body)
            tries++
            wait = EngineBrainRules.retryWaitMs(reply, tries)
        }
        return reply
    }
}

/**
 * One engine turn as the phone sees it (relay contract, engine report section 11): read the stream;
 * on `done {awaiting_limb}` run the round's calls one at a time, in order, posting each result at
 * once; follow the continuation the last post opens; recover once from a dropped stream with
 * `GET /api/agents/turn/<id>`; cancel between hops with every unstarted call posted `not_run`.
 */
internal class EngineRelay(
    private val wire: EngineWire,
    private val calls: EngineCalls,
    private val sink: EngineRelaySink,
    private val poster: EngineResultPoster = EngineResultPoster(wire),
) {
    @Volatile var turnId: String? = null
        private set

    /** The current round's calls whose result is not posted yet (what a cancel answers for). */
    private val unposted = java.util.concurrent.CopyOnWriteArrayList<EngineCall>()
    @Volatile private var over = false

    private class Read(val requests: List<EngineCall>, val done: EngineEvent.Done?, val failure: String?)

    private sealed interface Hop {
        class Go(val reply: WireReply, val waitMs: Long, val recovered: Boolean) : Hop
        class End(val end: EngineTurnEnd) : Hop
    }

    /** Follows [first] (the turn's first answer) to the turn's end. */
    suspend fun follow(first: WireReply, firstEventMs: Long = EngineBrainRules.FIRST_EVENT_MS): EngineTurnEnd {
        var hop: Hop = Hop.Go(first, firstEventMs, recovered = false)
        while (hop is Hop.Go) hop = next(hop)
        return (hop as Hop.End).end
    }

    private suspend fun next(go: Hop.Go): Hop {
        val stream = go.reply as? WireReply.Events ?: return Hop.End(ended(EngineBrainRules.wordsFor(go.reply)))
        val read = read(stream, go.waitMs)
        val done = read.done
        return when {
            done == null -> afterDrop(read, go.recovered)
            done.status != EngineEvent.AWAITING_LIMB -> {
                over = true
                Hop.End(
                    EngineTurnEnd(
                        done.status, turnId, done.tainted, done.end, done.writesRan, read.failure, done.deviceActions,
                    ),
                )
            }
            else -> {
                val order = done.calls
                val round = read.requests.sortedBy { r ->
                    order.indexOf(r.callId).takeIf { it >= 0 } ?: Int.MAX_VALUE
                }
                Hop.Go(round(round), waitAfter(round), go.recovered)
            }
        }
    }

    /** The stream ended with no `done`: an `error` ends the turn; a drop is recovered once. */
    private suspend fun afterDrop(read: Read, recovered: Boolean): Hop {
        val gaveUp = read.failure != null || recovered || turnId == null
        val again = if (gaveUp) null else recover()
        val lost = if (turnId == null) EngineBrainRules.UNREACHABLE_LINE else EngineBrainRules.STOPPED_LINE
        val words = read.failure ?: lost
        return if (again == null) Hop.End(ended(words)) else Hop.Go(again.first, again.second, recovered = true)
    }

    private suspend fun read(stream: WireReply.Events, firstMs: Long): Read {
        val requests = mutableListOf<EngineCall>()
        var done: EngineEvent.Done? = null
        var failure: String? = null
        try {
            var next = withTimeoutOrNull(firstMs) { stream.events.receiveCatching() }
            while (next != null) {
                val event = next.getOrNull() ?: break
                when (event) {
                    is EngineEvent.Turn -> turnId = event.turnId.ifBlank { turnId }
                    is EngineEvent.Request -> requests.add(event.call)
                    is EngineEvent.Done -> done = event
                    is EngineEvent.Failed -> failure = event.words.ifBlank { null }
                    else -> Unit
                }
                sink.event(event)
                next = stream.events.receiveCatching()
            }
        } finally {
            stream.close()
        }
        return Read(requests, done, failure)
    }

    /** Runs [round] one call at a time and posts each result; the last answer is the round's. */
    private suspend fun round(round: List<EngineCall>): WireReply {
        unposted.clear()
        unposted.addAll(round)
        sink.toolsStarting(round)
        var last: WireReply = WireReply.Json(EngineBrainRules.HTTP_ACCEPTED, JSONObject())
        for (call in round) {
            if (EngineBrainRules.afterPost(last) != AfterPost.WAITING) break
            val outcome = calls.result(call)
            sink.toolEnded(call, outcome)
            last = poster.post(turnId ?: call.turnId, call, outcome)
            unposted.remove(call)
        }
        // 202 after the round's last result: the stored result of each call still waited for, once.
        val waiting = EngineEventParse.strings((last as? WireReply.Json)?.body?.optJSONArray("waiting_for")).toSet()
        for (call in round.filter { it.callId in waiting }) {
            if (EngineBrainRules.afterPost(last) != AfterPost.WAITING) break
            last = poster.post(turnId ?: call.turnId, call, calls.stored(call.callId) ?: calls.result(call))
        }
        return last
    }

    /** A dropped stream, once: the calls still waited for, run (or re-posted from the ledger) again. */
    private suspend fun recover(): Pair<WireReply, Long>? {
        val id = turnId ?: return null
        val body = (wire.status(id) as? WireReply.Json)?.takeIf { it.status in OK_RANGE }?.body
        val waiting = EngineEventParse.strings(body?.optJSONArray("waiting_for")).toSet()
        val asked = body?.optJSONArray("calls")
        val round = if (body?.optString("status") != EngineEvent.AWAITING_LIMB || asked == null) {
            emptyList()
        } else {
            (0 until asked.length()).mapNotNull { asked.optJSONObject(it) }
                .map { EngineEventParse.call(it, afterReply = false) }
                .filter { it.callId in waiting }
        }
        return if (round.isEmpty()) null else round(round) to waitAfter(round)
    }

    private fun waitAfter(round: List<EngineCall>): Long =
        (round.maxOfOrNull { it.deadlineMs } ?: 0L) + EngineBrainRules.CONTINUATION_GRACE_MS

    /**
     * Stop or barge-in between hops (relay contract 8): the cancel, then every call of the round not
     * posted yet: its result if it finished (or once it does, within its deadline), else `not_run`.
     */
    suspend fun cancel() {
        val id = turnId
        if (over || id == null) return
        over = true
        wire.cancel(id)
        for (call in unposted.toList()) {
            val running = calls.inFlight(call.callId)
            val outcome = calls.stored(call.callId)
                ?: running?.let { withTimeoutOrNull(call.deadlineMs) { it.await() } }
                ?: CallOutcome(
                    if (running != null && call.writes) CallOutcome.TIMED_OUT else CallOutcome.NOT_RUN,
                    null,
                    "Stopped before ${call.name} finished on the phone.",
                    0,
                )
            calls.record(call.callId, outcome)
            poster.post(id, call, outcome)
            unposted.remove(call)
        }
    }

    /**
     * `done.device_actions` (K1, empty until the owner rules): each runs once, after the reply
     * played, through the phone's own after-read guard ([calls]' runner passes the turn's mark), and
     * its result is posted like any call (the engine answers 200 `late`).
     */
    suspend fun runAfterReply(actions: List<EngineCall>, run: Boolean = true) {
        for (call in actions) {
            val outcome = if (run) {
                calls.result(call).also { sink.toolEnded(call, it) }
            } else {
                CallOutcome(CallOutcome.NOT_RUN, null, "The reply was cut off: ${call.name} was not run.", 0)
                    .also { calls.record(call.callId, it) }
            }
            poster.post(turnId ?: call.turnId, call, outcome)
        }
    }

    private fun ended(words: String?): EngineTurnEnd {
        over = true
        return EngineTurnEnd(status = EngineTurnEnd.FAILED, turnId = turnId, words = words)
    }

    private companion object {
        val OK_RANGE = 200..299
    }
}

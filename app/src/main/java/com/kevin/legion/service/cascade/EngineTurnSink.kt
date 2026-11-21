package com.kevin.legion.service.cascade

import com.kevin.legion.service.TextFailure
import org.json.JSONObject

/**
 * An engine turn's events into the cascade's own turn ([GeminiTextLoop.Listener]), so everything
 * after the model in `answerTurn` (the reply speaker, the failure line, history, the record, the
 * fillers) runs as it does for the phone loop:
 * - `sentence`: to [GeminiTextLoop.Listener.onText] as it arrives, so speech starts on the first;
 * - a relayed round: [GeminiTextLoop.Listener.onToolCalls] and `onToolsStarting` (the tool-aware
 *   filler), then `onToolResult` per call;
 * - `working` and engine-side `tool` events: the tool's name only (`onToolCalls`), so a turn that
 *   ran tools is known to have.
 * Captions come from `sentence` only: `done.final_text` is a stub after an outside read.
 */
internal class EngineTurnSink(
    private val listener: GeminiTextLoop.Listener,
    private val now: () -> Long,
) : EngineRelaySink {
    private val text = StringBuilder()
    private val toolsRan = mutableListOf<String>()
    private val writesDone = mutableListOf<String>()

    @Volatile var conversationId: String? = null
        private set
    @Volatile var firstSentenceAtMs = 0L
        private set
    @Volatile var hops = 0
        private set
    @Volatile var relayed = 0
        private set

    /** The engine's crisis check fired (`crisis` event): the phone tears down as on its own check. */
    @Volatile var crisis = false
        private set

    override fun event(event: EngineEvent) {
        when (event) {
            is EngineEvent.Turn -> {
                event.conversationId?.let { conversationId = it }
                if (event.streamNo > 1) hops++
            }
            is EngineEvent.Sentence -> sentence(event.text)
            is EngineEvent.Crisis -> crisis = true
            is EngineEvent.Working -> listener.onToolsStarting(listOf(event.tool to JSONObject()))
            is EngineEvent.Tool -> {
                synchronized(toolsRan) { toolsRan.add(event.name) }
                listener.onToolCalls(listOf(event.name))
            }
            else -> Unit
        }
    }

    private fun sentence(said: String) {
        val line = said.trim()
        if (line.isEmpty()) return
        if (firstSentenceAtMs == 0L) firstSentenceAtMs = now()
        val delta = if (text.isEmpty()) line else " $line"
        text.append(delta)
        listener.onText(delta)
    }

    override fun toolsStarting(calls: List<EngineCall>) {
        relayed += calls.size
        listener.onToolCalls(calls.map { it.name })
        listener.onToolsStarting(calls.map { it.name to it.args })
    }

    override fun toolEnded(call: EngineCall, outcome: CallOutcome) {
        val ok = outcome.outcome == CallOutcome.OK
        synchronized(toolsRan) { toolsRan.add(call.name) }
        if (ok && call.writes) synchronized(writesDone) { writesDone.add(call.name) }
        val response = (outcome.result ?: JSONObject()).let { JSONObject(it.toString()) }
            .put("success", ok)
            .put("message", outcome.words)
        listener.onToolResult(call.name, call.args, response, outcome.ms, ok && call.writes)
    }

    /** The turn as the phone loop's result: what was said, and code's words when the engine had none. */
    fun result(end: EngineTurnEnd): GeminiTextLoop.Result {
        val words = end.words?.takeIf { it.isNotBlank() }
        return GeminiTextLoop.Result(
            text = text.toString(),
            failure = if (words != null) TextFailure.NO_TEXT else null,
            failureWords = words,
            writesDone = synchronized(writesDone) { writesDone.toList() },
            toolsRan = synchronized(toolsRan) { toolsRan.toList() },
            readThrough = end.tainted,
            usage = GeminiTextLoop.Usage(),
        )
    }
}

/**
 * The engine turn's timing line for the bench and the audit (`LIVE_LATENCY`), numbers and tokens
 * only, never words (client contract section 11's keys): `turn_post_ms` (his end of turn to the
 * request's end: the `E` frame, or the batch clip posted), `first_event_ms`, `heard_ms` and
 * `first_sentence_ms` (each from that post). First audio is the cascade's own `eot_reply_ms`.
 */
internal object EngineTimings {
    fun line(utt: EngineUtterance?, sink: EngineTurnSink, status: String, hasTurn: Boolean): String {
        val posted = utt?.postedAtMs ?: 0L
        fun since(at: Long) = if (posted > 0 && at > 0) at - posted else -1
        return "engine_turn route=${utt?.route ?: "text"}" +
            " turn_post_ms=${if (utt != null && posted > 0) posted - utt.endAtMs else -1}" +
            " first_event_ms=${since(utt?.firstEventAtMs ?: 0L)}" +
            " heard_ms=${since(utt?.heardAtMs ?: 0L)}" +
            " first_sentence_ms=${since(sink.firstSentenceAtMs)}" +
            " hops=${sink.hops} relayed=${sink.relayed} status=${token(status)} turn=${if (hasTurn) 1 else 0}"
    }

    private fun token(s: String) = s.filter { it.isLetterOrDigit() || it == '_' }.take(MAX_TOKEN)

    private const val MAX_TOKEN = 24
}

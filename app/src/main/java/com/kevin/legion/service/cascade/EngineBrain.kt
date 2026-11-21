package com.kevin.legion.service.cascade

import com.kevin.legion.service.ReadThroughWriteGuard
import com.kevin.legion.service.TextToolExecutor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

/**
 * The engine as Alfred's brain for one conversation (engine-brain ticket 04, slice P1), behind
 * Settings > Assistant "Answer on the engine" ([EngineBrainSettings], read at open, default off).
 * With the setting off nothing here is built: the cascade is untouched.
 *
 * What stays on the phone (plan 1119 section 1): the mic, its end of turn, the noise rule, stop and
 * goodbye, the crisis check (as well as the engine's), barge-in, the fillers, speech (the phone's own
 * voice says the engine's sentences), the confirm gates, and every relayed tool through the ONE
 * tool runner with its guards and `TestTurnPolicy` (the turn's own [TextToolExecutor]).
 *
 * What moves: speech-to-text (streamed while he speaks, [EngineUtterance]) and the model with its
 * tools. A turn the phone must keep ([EngineBrainRules.phoneKeeps]) goes to the phone loop.
 *
 * The engine also speaks every audio turn (its `audio` events): the stream route has no `speak`
 * switch yet, so that audio is not decoded here and the phone's voice says the sentences, as the
 * cascade does today. A known cost, listed in the P1 report.
 */
internal class EngineBrain(
    private val wire: EngineWire,
    private val scope: CoroutineScope,
    private val deviceState: () -> EngineBrainRules.DeviceState,
    private val log: (String) -> Unit,
    openedWithLine: Boolean,
    private val now: () -> Long = System::currentTimeMillis,
) {
    enum class State { ATTACHING, ON, OFF, UNREACHABLE }

    @Volatile var state = State.ATTACHING
        private set
    @Volatile private var streamOff = false
    @Volatile private var conversationId: String? = null
    @Volatile private var afterPhoneLine = openedWithLine
    @Volatile private var executor: TextToolExecutor? = null
    @Volatile private var relay: EngineRelay? = null
    @Volatile private var deviceActions: List<EngineCall> = emptyList()
    @Volatile private var crisis = false
    private val ids = AtomicInteger()
    private val calls = EngineCalls(scope, ::runCall, ::postLate, now)
    private val poster = EngineResultPoster(wire)

    /**
     * `POST /api/agents/limb` with the [manifest] it builds, in the background (the chime never waits). The
     * engine's answer decides: `brain: engine` turns it on; `phone`, a switched-off engine (404) or
     * any refusal turns it off silently (the phone loop answers); unreachable follows
     * [EngineBrainRules.UNREACHABLE_AT_OPEN] (K3) and calls [onUnreachable] to say so and close.
     */
    fun attach(manifest: () -> JSONObject, onUnreachable: () -> Unit) {
        val started = now()
        scope.launch {
            val reply = withTimeoutOrNull(EngineBrainRules.ATTACH_MS) { wire.attach(manifest()) }
                ?: WireReply.Unreachable("timeout")
            val lost = reply is WireReply.Unreachable ||
                (reply is WireReply.Json && reply.status >= EngineBrainRules.HTTP_SERVER_ERROR)
            val brain = (reply as? WireReply.Json)?.takeIf { it.status in OK_RANGE }?.body?.optString("brain")
            state = when {
                brain == "engine" -> State.ON
                lost && EngineBrainRules.UNREACHABLE_AT_OPEN == EngineBrainRules.WhenUnreachable.SAY_AND_CLOSE ->
                    State.UNREACHABLE
                else -> State.OFF
            }
            val why = (reply as? WireReply.Json)?.let { "http_${it.status}" } ?: (reply as WireReply.Unreachable).reason
            val ms = now() - started
            log("engine_attach state=${state.name.lowercase()} brain=${brain ?: "none"} why=$why ms=$ms")
            if (state == State.UNREACHABLE) onUnreachable()
        }
    }

    /**
     * His next utterance, spoken to the engine (cloud voice only: the laptop voice keeps its own
     * transcriber, which ends turns itself, and its words go to the engine as a transcript), or null
     * when the phone transcribes it.
     */
    fun utterance(cloudVoice: Boolean): EngineUtterance? {
        val on = state == State.ON || state == State.ATTACHING
        return if (!on || !cloudVoice) {
            null
        } else {
            EngineUtterance(ids.incrementAndGet(), wire, scope, meta(), !streamOff, ::switchedOff, now)
        }
    }

    private fun meta(): JSONObject = JSONObject()
        .put("client", "phone")
        .put("contract_version", EngineBrainRules.CONTRACT_VERSION)
        .apply { conversationId?.let { put("conversation_id", it) } }

    private fun switchedOff(streamOnly: Boolean) {
        streamOff = true
        if (!streamOnly) state = State.OFF
        log("engine_switched_off route=${if (streamOnly) "stream" else "turn"}")
    }

    /**
     * Who answers this turn: the engine's turn (its first answer, opened by [utt] or posted now as a
     * transcript), or null for the phone loop. A turn the phone keeps releases [utt] (the engine's
     * turn is cancelled).
     */
    fun route(utt: EngineUtterance?, words: String, facts: RouteFacts): (suspend () -> WireReply)? {
        val why = when {
            state != State.ON && utt == null -> "off"
            state == State.OFF || state == State.UNREACHABLE -> "off"
            else -> EngineBrainRules.phoneKeeps(words, facts.with(afterPhoneLine))
        }
        // Only a phone PROMPT (a greeting, an opener, a check-in) holds his next turn on the phone: a
        // turn of his the phone kept is his own words, and a mail turn's follow-up is held by its carry.
        afterPhoneLine = why == "prompt"
        if (why != null) {
            utt?.release()
            if (why != "off") log("engine_route brain=phone reason=$why")
        }
        return when {
            why != null -> null
            utt != null -> suspend { utt.takeTurn() ?: utt.refusal ?: WireReply.Unreachable("no_stream") }
            else -> suspend { wire.turnText(meta().put("kind", "user").put("transcript", words)) }
        }
    }

    /** What [route] needs from the session besides the words. */
    data class RouteFacts(val test: Boolean, val fromUser: Boolean, val carryMarked: Boolean) {
        fun with(afterPhoneLine: Boolean) = EngineBrainRules.Facts(test, fromUser, carryMarked, afterPhoneLine)
    }

    /**
     * The engine answers the turn [start] opened: its sentences to [listener] as they arrive (the
     * reply speaker says each), its relayed calls through [executor] (the phone's guards in the path),
     * as the phone loop's own result. Cancelled (a tap, barge-in, a merge): the engine's turn is
     * cancelled and the round's unstarted calls are posted `not_run`.
     */
    suspend fun answer(
        start: suspend () -> WireReply,
        utt: EngineUtterance?,
        executor: TextToolExecutor,
        listener: GeminiTextLoop.Listener,
    ): GeminiTextLoop.Result {
        this.executor = executor
        val turn = EngineTurnSink(listener, now)
        val r = EngineRelay(wire, calls, turn, poster)
        relay = r
        val end = try {
            r.follow(start(), EngineBrainRules.FIRST_EVENT_MS)
        } catch (e: CancellationException) {
            withContext(NonCancellable) { r.cancel() }
            log(EngineTimings.line(utt, turn, "cancelled", r.turnId != null))
            throw e
        }
        turn.conversationId?.let { conversationId = it }
        crisis = turn.crisis
        if (end.tainted) listener.onReadThrough(READ_THROUGH_SOURCE)
        // The engine closed the conversation (`done.end`): armed as the model's own end is.
        if (end.end) executor.run(END_CONVERSATION, JSONObject(), false)
        deviceActions = end.deviceActions
        log(EngineTimings.line(utt, turn, end.status, end.turnId != null))
        return turn.result(end)
    }

    /** The last turn's engine crisis check fired: true once, then false. */
    fun takeCrisis(): Boolean = crisis.also { crisis = false }

    /** `done.device_actions` of the last turn, once its reply played (K1; empty until he rules). */
    suspend fun runDeviceActions() {
        val actions = deviceActions
        deviceActions = emptyList()
        if (actions.isNotEmpty()) relay?.runAfterReply(actions)
    }

    /**
     * One relayed call on this phone: a write in a tainted turn is refused (relay contract 3), a
     * `needs` the phone cannot meet is refused at once, else the turn's own executor runs it (the
     * one tool runner: the read-through guard, `TestTurnPolicy`, the lone-word guard, the audit).
     */
    private suspend fun runCall(call: EngineCall): CallOutcome {
        val started = now()
        val refused = when {
            call.tainted && call.writes -> ReadThroughWriteGuard.WRITE_REFUSAL.format(call.name)
            else -> EngineBrainRules.needsRefusal(call.needs, deviceState())
        }
        val run = executor
        val outcome = when {
            refused != null -> CallOutcome(CallOutcome.REFUSED, null, refused.take(EngineBrainRules.MAX_WORDS_CHARS), 0)
            run == null -> CallOutcome(CallOutcome.NOT_RUN, null, "No turn to run ${call.name} in.", 0)
            else -> EngineBrainRules.outcomeOf(
                // After an outside read (a tainted turn, or a device action after the reply) the phone's
                // own after-read guard decides, as on the phone loop.
                run.run(call.name, call.args, call.tainted || call.afterReply),
                now() - started,
            )
        }
        log("engine_relay tool=${call.name} outcome=${outcome.outcome} tool_result_ms=${outcome.ms}")
        return outcome
    }

    /** A write reported `timed_out` landed after all: `ok` for the same call id (relay contract 7). */
    private suspend fun postLate(call: EngineCall, outcome: CallOutcome) {
        poster.post(call.turnId, call, outcome)
        log("engine_relay tool=${call.name} outcome=late_ok")
    }

    companion object {
        const val READ_THROUGH_SOURCE = "engine"
        private const val END_CONVERSATION = "end_conversation"
        private val OK_RANGE = 200..299

        /** The words for a turn the engine could not answer at all ([EngineUtterance] lost it). */
        fun lostWords(utt: EngineUtterance): String =
            utt.refusal?.let { EngineBrainRules.wordsFor(it) } ?: EngineBrainRules.UNREACHABLE_LINE
    }
}

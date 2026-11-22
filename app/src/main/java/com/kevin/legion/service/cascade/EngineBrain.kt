package com.kevin.legion.service.cascade

import com.kevin.legion.service.TextToolExecutor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

/**
 * The engine as Alfred's brain for one conversation (engine-brain ticket 04, slice P1), behind the
 * developer switch [EngineBrainSettings] (read at open, default off). With it off nothing here is
 * built: the cascade is untouched. With it on and the engine not the brain (dark, `brain: phone`,
 * unreachable at attach), every turn is the phone's own, exactly as with it off.
 *
 * What stays on the phone (plan 1119 section 1): the mic, its end of turn, the noise rule, stop and
 * goodbye, the crisis check (as well as the engine's), barge-in, the fillers, speech (the phone's own
 * voice says the engine's sentences), the confirm gates, and every relayed tool through the ONE
 * tool runner with its guards and `TestTurnPolicy` (the turn's own [TextToolExecutor]), after the
 * phone's own gate on what it offered ([EngineCallGate]).
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
    /** Outlives the session by [EngineBrainRules.TAIL_MS]: cancels that must reach the engine. */
    private val tail: CoroutineScope = TAIL,
) {
    enum class State { ATTACHING, ON, OFF }

    @Volatile var state = State.ATTACHING
        private set
    @Volatile private var gate: EngineCallGate? = null
    @Volatile private var streamOff = false
    @Volatile private var conversationId: String? = null
    @Volatile private var executor: TextToolExecutor? = null
    @Volatile private var listener: GeminiTextLoop.Listener? = null
    @Volatile private var relay: EngineRelay? = null
    @Volatile private var deviceActions: List<EngineCall> = emptyList()
    @Volatile private var crisis = false
    @Volatile private var lastCancel: Job? = null
    private val failures = AtomicInteger()
    private val early: MutableSet<EngineUtterance> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /**
     * Turns whose go went early and that no turn has read yet (review round 4). A stop, a goodbye and
     * every close release them all: each is cancelled on the engine, never spoken, never recorded.
     */
    val earlyTurns: Set<EngineUtterance> get() = early.toSet()
    private val ids = AtomicInteger()
    private val now: () -> Long = System::currentTimeMillis
    private val calls = EngineCalls(scope, ::runCall, ::postLate, now)
    private val poster = EngineResultPoster(wire)

    /** What the engine is told about the turns the phone answered ([EnginePhoneContext]). */
    val context = EnginePhoneContext(now)

    /**
     * `POST /api/agents/limb` with the [offer] it builds, in the background (the chime never waits).
     * Only `brain: engine` makes the engine this conversation's brain; anything else (`phone`, a
     * switched-off engine, a refusal, no answer in [EngineBrainRules.ATTACH_MS]) leaves every turn
     * on the phone, silently (logged). K3 never fires here: the engine was never the brain.
     */
    fun attach(offer: () -> EngineLimbManifest.Offer) {
        val started = now()
        scope.launch {
            val o = offer()
            val reply = withTimeoutOrNull(EngineBrainRules.ATTACH_MS) { wire.attach(o.json) }
                ?: WireReply.Unreachable("timeout")
            val answer = (reply as? WireReply.Json)?.takeIf { it.status in OK_RANGE }?.body
            val brain = answer?.optString("brain")
            // The engine's own refusals (Contract v2 12.5c): never offered, so never run here either.
            val dropped = answer?.optJSONArray("dropped")
                ?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("name") } }.orEmpty()
            if (dropped.isNotEmpty()) {
                val names = dropped.joinToString(",") { EngineCallGate.safeName(it) }
                log("engine_attach dropped=${dropped.size} tools=$names")
            }
            val offered = EngineLimbManifest.Offer(o.json, o.tools - dropped.toSet(), o.params)
            if (brain == "engine") gate = EngineCallGate(offered)
            state = if (brain == "engine") State.ON else State.OFF
            val why = (reply as? WireReply.Json)?.let { "http_${it.status}" } ?: (reply as WireReply.Unreachable).reason
            val ms = now() - started
            log("engine_attach state=${state.name.lowercase()} brain=${brain ?: "none"} why=$why ms=$ms")
        }
    }

    /**
     * His next utterance, spoken to the engine, or null when the phone transcribes it: the engine not
     * (yet) the brain, or the laptop voice (its own transcriber ends turns). Every engine-heard turn is
     * held until the phone's go ([EngineUtterance]).
     */
    fun utterance(cloudVoice: Boolean, awaits: String? = null): EngineUtterance? =
        if (state != State.ON || !cloudVoice || awaits != null) {
            null
        } else {
            val setup = UtteranceSetup(wire, scope, tail, ::meta, !streamOff, lastCancel, early)
            EngineUtterance(ids.incrementAndGet(), setup, ::switchedOff, { lastCancel = it })
        }

    /** Every turn's metadata: the phone speaks with its own voice (`speak: false`), and its facts. */
    private fun meta(): JSONObject = JSONObject()
        .put("client", "phone")
        .put("contract_version", EngineBrainRules.CONTRACT_VERSION)
        .put("speak", false)
        .put("client_facts", context.facts())
        .apply { conversationId?.let { put("conversation_id", it) } }

    private fun switchedOff(streamOnly: Boolean) {
        streamOff = true
        if (!streamOnly) state = State.OFF
        log("engine_switched_off route=${if (streamOnly) "stream" else "turn"}")
    }

    /**
     * Who answers this turn: the engine's turn (its first answer, opened by [utt] or posted now as a
     * transcript), or null for the phone loop. A turn the phone keeps releases [utt] (the engine's
     * turn is cancelled). A turn the phone transcribed itself ([RouteFacts.engineHeard] false) is
     * always the phone's: its drafts and loop run exactly as with the switch off.
     */
    fun route(utt: EngineUtterance?, words: String, facts: RouteFacts): (suspend () -> WireReply)? {
        context.newTurn()
        // A turn already taken back (it waited behind another, review R2) is the phone's.
        val live = utt?.takeIf { !it.released }
        val why = when {
            utt?.released == true -> EngineBrainRules.TAKEN
            // A go already posted when his words came (review round 3, R2) is final.
            live?.goPosted == true -> null
            else -> EngineBrainRules.phoneKeeps(words, facts.facts(engineHeard = live != null, on = state == State.ON))
        }
        // His mail exchange never reaches the engine but as the placeholder (review R3).
        why?.let { context.phoneKept(it, words) }
        if (why != null) {
            // A turn the phone keeps is taken back: held, nothing of it acts on the engine.
            utt?.release()
            context.lastEngineTurn = null
            if (why in EngineBrainRules.LOGGED_KEEPS) log("engine_route brain=phone reason=$why")
        }
        return when {
            why != null -> null
            live != null -> suspend { live.go() }
            else -> suspend {
                val id = java.util.UUID.randomUUID().toString()
                wire.turnText(meta().put("kind", "user").put("transcript", words).put("turn_id", id))
            }
        }
    }

    /** What [route] needs from the session besides the words. */
    data class RouteFacts(
        val test: Boolean,
        val fromUser: Boolean,
        /** The engine heard this turn (streamed audio), or the laptop voice's words may go to it. */
        val engineHeard: Boolean,
        /** The phone was waiting on his answer when he began ([EngineBrainRules.phoneAwaits]). */
        val awaits: String? = null,
    ) {
        /** [engineHeard]: an utterance streamed to the engine; [on]: the engine is the brain. */
        fun facts(engineHeard: Boolean, on: Boolean) =
            EngineBrainRules.Facts(test, fromUser, engineHeard || this.engineHeard, on, awaits)
    }

    /**
     * The engine answers the turn [start] opened: its sentences to [listener] as they arrive (the
     * reply speaker says each), its relayed calls through [EngineCallGate] then [executor] (the phone's
     * guards in the path), as the phone loop's own result. Cancelled (a tap, barge-in, a merge): the
     * engine's turn is cancelled on the tail scope and the round's unstarted calls are posted
     * `not_run`; a turn that had touched outside text is marked read-through first (review B-13).
     */
    suspend fun answer(
        start: suspend () -> WireReply,
        utt: EngineUtterance?,
        executor: TextToolExecutor,
        listener: GeminiTextLoop.Listener,
    ): GeminiTextLoop.Result {
        // Device actions from a turn whose reply never played: answered `not_run`, never run late.
        val unplayed = deviceActions
        deviceActions = emptyList()
        val previous = relay
        if (previous != null && unplayed.isNotEmpty()) tail.launch { previous.runAfterReply(unplayed, run = false) }
        this.executor = executor
        this.listener = listener
        gate?.newTurn()
        val turn = EngineTurnSink(listener, now)
        val r = EngineRelay(wire, calls, turn, poster)
        relay = r
        context.engineAnswering()
        val end = try {
            r.follow(start(), EngineBrainRules.FIRST_EVENT_MS)
        } catch (e: CancellationException) {
            context.lastEngineTurn = r.turnId ?: utt?.turnId
            if (turn.touchedOutside) listener.onReadThrough(READ_THROUGH_SOURCE)
            lastCancel = tail.launch { withTimeoutOrNull(EngineBrainRules.TAIL_MS) { r.cancel() } }
            log(EngineTimings.line(utt, turn, "cancelled", r.turnId != null))
            throw e
        }
        turn.conversationId?.let { conversationId = it }
        context.lastEngineTurn = end.turnId ?: utt?.turnId
        crisis = turn.crisis
        if (end.words == null) failures.set(0) else failures.incrementAndGet()
        if (end.tainted) listener.onReadThrough(READ_THROUGH_SOURCE)
        // The engine closed the conversation (`done.end`): armed as the model's own end is.
        if (end.end) executor.run(END_CONVERSATION, JSONObject(), false)
        deviceActions = end.deviceActions
        log(EngineTimings.line(utt, turn, end.status, end.turnId != null))
        return turn.result(end)
    }

    /**
     * An engine turn failed before it could answer (no words came). True when the conversation
     * closes now (K3, after [EngineBrainRules.FAILURE_BUDGET] in a row); false: say so and listen.
     * A transcript the engine missed is not a failure of the engine.
     */
    fun failed(missed: Boolean): Boolean {
        if (missed) return false
        val inARow = failures.incrementAndGet()
        return inARow >= EngineBrainRules.FAILURE_BUDGET &&
            EngineBrainRules.UNREACHABLE_AT_OPEN == EngineBrainRules.WhenUnreachable.SAY_AND_CLOSE
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
     * One relayed call: the phone's own gate first ([EngineCallGate]: offered tools only, its own
     * flags, argument schema, taint across the turn, `needs`), then the turn's own executor (the one
     * tool runner: the read-through guard, `TestTurnPolicy`, the lone-word guard, the audit).
     */
    private suspend fun runCall(call: EngineCall): CallOutcome {
        val started = now()
        val run = executor
        val decision = gate?.decide(call, deviceState())
            ?: EngineCallGate.Decision.Refuse("${EngineCallGate.NOT_OFFERED}: this phone offered no tools.")
        val outcome = when {
            decision is EngineCallGate.Decision.Refuse -> CallOutcome(CallOutcome.REFUSED, null, decision.words, 0)
            run == null -> CallOutcome(CallOutcome.NOT_RUN, null, "No turn to run ${call.name} in.", 0)
            else -> {
                val go = decision as EngineCallGate.Decision.Run
                // Outside text is on its way: the turn is marked now, before any cut-off (review B-13).
                if (go.outside) listener?.onReadThrough(READ_THROUGH_SOURCE)
                EngineBrainRules.outcomeOf(run.run(go.name, go.args, go.readThrough), now() - started)
            }
        }
        val name = EngineCallGate.safeName(call.name)
        log("engine_relay tool=$name outcome=${outcome.outcome} tool_result_ms=${outcome.ms}")
        return outcome
    }

    /** A write reported `timed_out` landed after all: `ok` for the same call id (relay contract 7). */
    private suspend fun postLate(call: EngineCall, outcome: CallOutcome) {
        poster.post(call.turnId, call, outcome)
        log("engine_relay tool=${EngineCallGate.safeName(call.name)} outcome=late_ok")
    }

    companion object {
        const val READ_THROUGH_SOURCE = "engine"
        private const val END_CONVERSATION = "end_conversation"
        private val OK_RANGE = 200..299

        /** Process-wide: a cancel must outlive the session that sent it, for a bounded time. */
        private val TAIL = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /**
         * The words for a turn the engine could not answer at all ([EngineUtterance] lost it), true
         * as said: nothing reached it, or it went and no answer came (it may still finish).
         */
        fun lostWords(utt: EngineUtterance): String = utt.refusal?.let { EngineBrainRules.wordsFor(it) }
            ?: if (utt.postedAtMs > 0) EngineBrainRules.NO_ANSWER_LINE else EngineBrainRules.UNREACHABLE_LINE
    }
}

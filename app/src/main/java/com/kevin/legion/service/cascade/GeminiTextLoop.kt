package com.kevin.legion.service.cascade

import com.kevin.legion.ai.AgentProtocol
import com.kevin.legion.ai.AgentResult
import com.kevin.legion.ai.GeminiRest
import com.kevin.legion.ai.RestOutcome
import com.kevin.legion.ai.VerbatimSpeech
import com.kevin.legion.ai.WebReadMark
import com.kevin.legion.service.TextChatEngine
import com.kevin.legion.service.TextFailure
import com.kevin.legion.service.TextToolExecutor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/** A write's facts from its call (name, args) and result: `ok` it went through, `confirmed` a result came back. */
typealias WriteFacts = (name: String, args: JSONObject, response: JSONObject, ok: Boolean, confirmed: Boolean) ->
    ScriptedLines.WriteFact

/**
 * The cascade's middle: one spoken turn on a Gemini TEXT model with function calling, streamed.
 *
 * What it keeps from [TextChatEngine] (typed chat): the round cap with a tool-free backstop round,
 * the verbatim echo of the model turn (thought signatures survive), the per-turn read-through flag
 * raised when a read-through tool is CALLED, the [WebReadMark] carry, and the rule that a write is
 * "done" only on an explicit `success: true`. Its failure wording ([TextChatEngine.failureWords])
 * is reused as is.
 *
 * What it adds:
 * - **Streaming** (`streamGenerateContent?alt=sse`): text reaches [Listener.onText] as it arrives,
 *   so the first sentence can be spoken while the model is still writing.
 * - **Parallel tools**: every call of a round runs at once, and all results go back in one turn.
 * - **Explicit cache** ([ContextCacheStore]) with an inline fallback: a cache that is not ready yet
 *   or that a request reports broken costs one inline request, never a failed turn.
 * - **Google Search** with function calling (`includeServerSideToolInvocations`); a reply grounded
 *   on search marks the turn read-through. If Google rejects the combination, search is dropped
 *   for the rest of this loop's life and the round is retried without it.
 * - **Cancel**: cancelling the caller stops the stream at once. A tool already running finishes
 *   (it runs [NonCancellable]): a write is reported, never cut in half (ADR 0064's rule).
 *
 * One instance per conversation (the search fallback is per conversation).
 */
@Suppress("TooManyFunctions") // one turn's loop: rounds, one tool, its write's facts and line, verbatim, results
class GeminiTextLoop(
    private val transport: GeminiStreamTransport,
    private val cache: ContextCacheStore?,
    private val classify: (RestOutcome.HttpError) -> AgentResult = GeminiRest::classify,
    private val isMutating: (name: String, args: JSONObject) -> Boolean,
    /** Whether a call (as the model sent it, with its arguments) reads outside text. */
    private val isReadThrough: (name: String, args: JSONObject) -> Boolean,
    private val toolTimeoutMs: (String) -> Long = { TextChatEngine.DEFAULT_TOOL_TIMEOUT_MS },
    private val meter: (model: String, usage: JSONObject?) -> Unit = { _, _ -> },
    /**
     * A round cut off before it finished (the turn was interrupted or merged): what it had used so
     * far, for the cost audit. [usage] is null when no `usageMetadata` had arrived yet.
     */
    private val discarded: (model: String, usage: JSONObject?, chars: Int) -> Unit = { _, _, _ -> },
    private val maxRounds: Int = TextChatEngine.MAX_ROUNDS,
    private val budgetMs: Long = BUDGET_MS,
    private val now: () -> Long = System::currentTimeMillis,
    /**
     * A write's facts from its result ([ScriptedLines.factOf]). Given, every write that went
     * through is said by code after its round ([Listener.onCodeLine], 2026-11-19); null, none is.
     */
    private val writeFact: WriteFacts? = null,
) {
    interface Listener {
        /** Spoken text, as it streams. */
        fun onText(delta: String) {}

        /** The model asked for tools this round (before they run). */
        fun onToolCalls(names: List<String>) {}

        /**
         * The same round's calls with their arguments, as they start (after [onToolCalls]): what the
         * tool-aware holding line ([ToolFillers]) is chosen from.
         */
        fun onToolsStarting(calls: List<Pair<String, JSONObject>>) {}

        /** One tool finished. [writeDone] is true only for a write that came back `success: true`. */
        fun onToolResult(name: String, args: JSONObject, response: JSONObject, ms: Long, writeDone: Boolean) {}

        /** The turn became read-through, and why (tool name, `fleet_web`, `grounding`). */
        fun onReadThrough(source: String) {}

        /** One round's first text or call part arrived, [ms] after its request was sent. */
        fun onFirstPart(round: Int, ms: Long) {}

        /** A write finished: its facts, done or failed (only when the loop was given `writeFact`). */
        fun onWriteFact(fact: ScriptedLines.WriteFact) {}

        /**
         * Code's line for the writes of a round that went through ([ScriptedLines.doneLine]), to be
         * said after the model's text so far and before its next. Part of [Result.text]; never
         * passed to [onText] (it is not the model's).
         */
        fun onCodeLine(text: String, facts: List<ScriptedLines.WriteFact>) {}
    }

    /** Token use over the whole turn, for the per-turn log line. */
    data class Usage(
        val prompt: Int = 0,
        val cached: Int = 0,
        val output: Int = 0,
        val thoughts: Int = 0,
        val rounds: Int = 0,
        val cachedRounds: Int = 0,
        val inlineRounds: Int = 0,
    ) {
        fun plus(u: JSONObject?, viaCache: Boolean): Usage = copy(
            prompt = prompt + (u?.optInt("promptTokenCount") ?: 0),
            cached = cached + (u?.optInt("cachedContentTokenCount") ?: 0),
            output = output + (u?.optInt("candidatesTokenCount") ?: 0),
            thoughts = thoughts + (u?.optInt("thoughtsTokenCount") ?: 0),
            rounds = rounds + 1,
            cachedRounds = cachedRounds + if (viaCache) 1 else 0,
            inlineRounds = inlineRounds + if (viaCache) 0 else 1,
        )
    }

    data class Result(
        /** Everything the model wrote for speech, across rounds. */
        val text: String,
        val failure: TextFailure?,
        /** The failure in words (what did and did not happen), or null on success. */
        val failureWords: String?,
        val writesDone: List<String>,
        val toolsRan: List<String>,
        val readThrough: Boolean,
        val usage: Usage,
        /**
         * The turn's tool round ended on filler only and was asked once more ([FillerTurn]): 0 or 1.
         * With [fillerAgain] the second answer was filler too, and the turn failed as [TextFailure.NO_TEXT].
         */
        val fillerContinued: Int = 0,
        val fillerAgain: Boolean = false,
        /** A tool's own words were spoken verbatim this turn ([VerbatimSpeech]). */
        val verbatimSpoken: Boolean = false,
        /** Model sentences dropped after that because they carried a money amount ([MoneyFilter]). */
        val moneyDropped: Int = 0,
        /** Model sentences dropped after a diagnostics verbatim answer: they restated it ([DiagFilter]). */
        val diagDropped: Int = 0,
        /** Filler-only sentences dropped after real content ([TrailingFiller]). */
        val trailingFillerDropped: Int = 0,
        /** Code lines said this turn for writes that went through ([Listener.onCodeLine]). */
        val codeLines: Int = 0,
    )

    @Volatile private var searchRejected = false

    private class TurnState {
        val text = StringBuilder()
        val writesDone = mutableListOf<String>()
        val writesUnconfirmed = mutableListOf<String>()
        val toolsRan = mutableListOf<String>()
        var readThrough = false
        var usage = Usage()
        var fillerContinued = 0
        var fillerAgain = false
        var verbatim = false
        var verbatimFrom: String? = null
        var moneyDropped = 0
        var diagDropped = 0
        var trailingFillerDropped = 0

        /** The calls whose write code already said ([Listener.onCodeLine]), and how many lines. */
        val writesSaid = mutableListOf<String>()
        var codeLines = 0
    }

    /** One call's result, and its write's facts when it was a write. */
    private class Ran(val result: AgentProtocol.ToolResult, val fact: ScriptedLines.WriteFact?)

    /**
     * Answers the user's turn. [contents] is the whole conversation so far INCLUDING the new user
     * turn (the context preamble rides in its first user turn); it is copied, never changed.
     * Cancellation propagates (the caller interrupted); everything else ends as a [Result].
     */
    suspend fun answer(
        prefix: CascadePrefix,
        contents: JSONArray,
        executor: TextToolExecutor,
        listener: Listener,
        /**
         * A speculative draft's gate ([SpeculativeAnswer]): awaited before any tool call of a round is
         * announced or run, so nothing is executed before the turn is confirmed. Null: no gate.
         */
        hold: (suspend () -> Unit)? = null,
    ): Result {
        val state = TurnState()
        val result = try {
            withTimeoutOrNull(budgetMs) { loop(prefix, contents, executor, listener, state, hold) }
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
            fail(TextFailure.MODEL_ERROR, state)
        }
        return result ?: fail(TextFailure.TIMED_OUT, state)
    }

    @Suppress(
        "CyclomaticComplexMethod",
        "LongMethod",
        "ReturnCount",
        "NestedBlockDepth",
        "LoopWithTooManyJumpStatements",
    )
    private suspend fun loop(
        basePrefix: CascadePrefix,
        initial: JSONArray,
        executor: TextToolExecutor,
        listener: Listener,
        state: TurnState,
        hold: (suspend () -> Unit)?,
    ): Result {
        val contents = JSONArray()
        for (i in 0 until initial.length()) contents.put(initial.get(i))
        var cacheBroken = false
        var round = 0
        // The one extra round after a filler-only answer ([FillerTurn]) is tool-free.
        var toolFree = false
        while (true) {
            round++
            val prefix = if (searchRejected) basePrefix.copy(search = false) else basePrefix
            val force = round > maxRounds || toolFree
            val cacheName = if (!force && !cacheBroken && !searchRejected) cache?.peek(prefix) else null
            val body = if (cacheName != null) {
                CascadeRequests.cached(cacheName, contents)
            } else {
                CascadeRequests.inline(prefix, contents, force)
            }
            val turn = StreamedTurn()
            val sentAt = now()
            var firstSeen = false
            // After a tool ran, a line that may be filler is held back until it is known not to be.
            val held = if (state.toolsRan.isNotEmpty()) FillerTurn.Hold() else null
            // After the phone spoke a balance itself, no money sentence of the model's is spoken; after a
            // diagnostics answer, no sentence restating its codes, counts or a verdict.
            val money: SentenceFilter? = when {
                !state.verbatim -> null
                state.verbatimFrom == VerbatimSpeech.DIAGNOSTICS -> DiagFilter()
                else -> MoneyFilter()
            }
            // A filler-only sentence after real content is never spoken ([TrailingFiller]).
            val trail = TrailingFiller { hasContent(state) }
            fun pass(t: String) = emit(trail.feed(money?.feed(t) ?: t), state, listener)
            val outcome = try {
                transport.stream(prefix.model, body) { ev ->
                    val before = turn.text.length + turn.calls.size
                    turn.onEvent(ev) { delta ->
                        pass(held?.feed(delta) ?: delta)
                    }
                    if (!firstSeen && turn.text.length + turn.calls.size > before) {
                        firstSeen = true
                        listener.onFirstPart(round, now() - sentAt)
                    }
                }
            } catch (e: CancellationException) {
                discarded(prefix.model, turn.usage, turn.text.length)
                throw e
            }
            when (outcome) {
                is StreamOutcome.HttpError -> {
                    if (cacheName != null && isCacheError(outcome)) {
                        cache?.invalidate(cacheName)
                        cacheBroken = true
                        round--
                        continue
                    }
                    if (!force && prefix.search && isSearchRejected(outcome)) {
                        searchRejected = true
                        round--
                        continue
                    }
                    val verdict = classify(RestOutcome.HttpError(outcome.code, outcome.body))
                    return fail(TextChatEngine.failureFor(verdict), state)
                }
                is StreamOutcome.Network -> return fail(TextFailure.OFFLINE, state)
                StreamOutcome.Ok -> Unit
            }
            meter(prefix.model, turn.usage)
            state.usage = state.usage.plus(turn.usage, cacheName != null)
            held?.end()?.let { pass(it) }
            money?.let {
                emit(trail.feed(it.flush()), state, listener)
                if (it is DiagFilter) state.diagDropped += it.dropped else state.moneyDropped += it.dropped
            }
            emit(trail.flush(), state, listener)
            state.trailingFillerDropped += trail.dropped
            if (turn.blockReason != null) return fail(TextFailure.BLOCKED, state)
            if (turn.error != null && !turn.hasParts()) return fail(TextFailure.MODEL_ERROR, state)
            if (turn.grounded && !state.readThrough) {
                state.readThrough = true
                listener.onReadThrough(READ_THROUGH_GROUNDING)
            }
            val calls = turn.calls
            if (calls.isEmpty() || force) {
                // A tool turn that ended on filler: never said as the answer; asked once more, tool-free.
                // Not after a verbatim answer: that turn already said what was asked, so it just ends.
                // Nor after a write code said: that line is the answer, and the model has nothing to add.
                val fillerOnly = !state.verbatim && state.codeLines == 0 && (
                    held?.filler == true ||
                        (held != null && turn.text.isBlank() && FillerTurn.isFillerOnly(state.text.toString()))
                    )
                if (fillerOnly && state.fillerContinued == 0) {
                    state.fillerContinued = 1
                    toolFree = true
                    contents.put(turn.modelContent().takeIf { turn.hasParts() } ?: fillerModelContent(state))
                    contents.put(FillerTurn.noteContent())
                    continue
                }
                if (fillerOnly) {
                    state.fillerAgain = true
                    return fail(TextFailure.NO_TEXT, state)
                }
                return if (state.text.isBlank()) fail(TextFailure.NO_TEXT, state) else reply(state)
            }
            // A draft waits here for its turn to be confirmed: no tool is announced or run on a guess.
            hold?.invoke()
            contents.put(turn.modelContent())
            listener.onToolCalls(calls.map { it.name })
            listener.onToolsStarting(calls.map { it.name to it.args })
            val ran = coroutineScope {
                calls.map { call -> async { runOne(call, executor, listener, state) } }.awaitAll()
            }
            sayWrites(ran, state, listener)
            val results = ran.map { it.result }
            speakVerbatim(results, state, listener)
            val nudge = if (round + 1 >= maxRounds) "Answer now with what you have." else null
            contents.put(AgentProtocol.functionResponseContent(results, nudge))
        }
    }

    /**
     * One tool call, bounded, never throwing, and never cut in half: it runs [NonCancellable], so an
     * interrupt waits for a write already started and it is reported, the same rule the voice path
     * keeps for a write under a barge-in.
     */
    private suspend fun runOne(
        call: AgentProtocol.FunctionCall,
        executor: TextToolExecutor,
        listener: Listener,
        state: TurnState,
    ): Ran = withContext(NonCancellable) {
        // ADR 0090: the executor is handed the turn's mark as it stood before this call's OWN mark.
        // A read-through tool that also acts (email reads mail for a recipient, browser opens a
        // page) must not refuse itself; a mark from an earlier read still reaches it.
        val ownMark = isReadThrough(call.name, call.args)
        val markedBefore = synchronized(state) { state.readThrough.also { if (ownMark) state.readThrough = true } }
        if (ownMark) listener.onReadThrough(call.name)
        val started = now()
        var confirmed = false
        val webRead = WebReadMark()
        val readThroughNow = if (ownMark) markedBefore else synchronized(state) { state.readThrough }
        val response = try {
            withContext(webRead) {
                withTimeoutOrNull(toolTimeoutMs(call.name)) { executor.run(call.name, call.args, readThroughNow) }
            }?.also { confirmed = it.has("success") }
                ?: JSONObject().put("success", false).put("message", "That took too long and timed out.")
        } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
            JSONObject().put("success", false).put("message", "Something went wrong running that.")
        }
        if (webRead.touched) {
            synchronized(state) { state.readThrough = true }
            listener.onReadThrough(READ_THROUGH_FLEET_WEB)
        }
        var writeDone = false
        val write = isMutating(call.name, call.args)
        synchronized(state) {
            state.toolsRan.add(call.name)
            if (write) {
                when {
                    confirmed && response.optBoolean("success", false) -> {
                        state.writesDone.add(call.name)
                        writeDone = true
                    }
                    !confirmed -> state.writesUnconfirmed.add(call.name)
                }
            }
        }
        listener.onToolResult(call.name, call.args, response, now() - started, writeDone)
        val fact = factOf(call, response, write, writeDone, confirmed, listener)
        Ran(AgentProtocol.ToolResult(call.name, call.id, response), fact)
    }

    /** The write's facts, told to [listener]; null for a read or when the loop says no writes. */
    @Suppress("LongParameterList")
    private fun factOf(
        call: AgentProtocol.FunctionCall,
        response: JSONObject,
        write: Boolean,
        writeDone: Boolean,
        confirmed: Boolean,
        listener: Listener,
    ): ScriptedLines.WriteFact? {
        if (!write) return null
        val fact = writeFact?.invoke(call.name, call.args, response, writeDone, confirmed)
        return fact?.also { listener.onWriteFact(it) }
    }

    /**
     * The round's writes that went through are said by code, from their results (2026-11-19: a write
     * was done and never said). One line for the round, after the model's text so far; each of those
     * results tells the model it was said, so its next words need not say it again. Nothing here
     * reads what the model wrote.
     */
    private fun sayWrites(ran: List<Ran>, state: TurnState, listener: Listener) {
        val said = ran.filter { it.fact?.said == true }
        val line = ScriptedLines.doneLine(said.mapNotNull { it.fact }) ?: return
        for (r in said) r.result.response.put(SAID_ALOUD, line).put(SAID_ALOUD_NOTE, SAID_NOTE)
        if (state.text.isNotEmpty() && !state.text.last().isWhitespace()) state.text.append(' ')
        state.text.append(line).append(' ')
        state.writesSaid += said.map { it.result.name }
        state.codeLines++
        listener.onCodeLine(line, said.mapNotNull { it.fact })
    }

    /**
     * A result on [VerbatimSpeech.TOOLS] carries words the phone speaks itself: passed straight on to
     * be spoken (and so to the transcript and the audit lines, which see what is spoken), never
     * through the model. Once per turn.
     */
    private fun speakVerbatim(results: List<AgentProtocol.ToolResult>, state: TurnState, listener: Listener) {
        if (state.verbatim) return
        val hit = results.firstOrNull { VerbatimSpeech.of(it.response) != null } ?: return
        val words = VerbatimSpeech.of(hit.response).orEmpty()
        if (state.text.isNotEmpty() && !state.text.last().isWhitespace()) emit(" ", state, listener)
        // A trailing space, so a sentence of the model's after it is never glued on (the result is trimmed).
        emit("$words ", state, listener)
        state.verbatim = true
        state.verbatimFrom = VerbatimSpeech.fromOf(hit.response)
    }

    /** Something real was said this turn: text that is not only filler. */
    private fun hasContent(state: TurnState): Boolean {
        val t = state.text.toString()
        return t.isNotBlank() && !FillerTurn.isFillerOnly(t) && !TrailingFiller.isFiller(t)
    }

    /** Model text as it is let through: kept for the turn and passed on to be spoken. */
    private fun emit(text: String, state: TurnState, listener: Listener) {
        if (text.isEmpty()) return
        state.text.append(text)
        listener.onText(text)
    }

    /** The model's empty last round as history: its earlier filler text, so the note follows a model turn. */
    private fun fillerModelContent(state: TurnState) = JSONObject().put("role", "model")
        .put("parts", JSONArray().put(JSONObject().put("text", state.text.toString().trim().ifEmpty { "..." })))

    // The result is what is stored and compared (history, records, checks): never a tag ([TtsTags]).
    private fun reply(state: TurnState) = Result(
        text = TtsTags.strip(state.text.toString()).trim(),
        failure = null,
        failureWords = null,
        writesDone = state.writesDone.toList(),
        toolsRan = state.toolsRan.toList(),
        readThrough = state.readThrough,
        usage = state.usage,
        fillerContinued = state.fillerContinued,
        fillerAgain = state.fillerAgain,
        verbatimSpoken = state.verbatim,
        moneyDropped = state.moneyDropped,
        diagDropped = state.diagDropped,
        trailingFillerDropped = state.trailingFillerDropped,
        codeLines = state.codeLines,
    )

    private fun fail(failure: TextFailure, state: TurnState) = Result(
        text = TtsTags.strip(state.text.toString()).trim(),
        failure = failure,
        failureWords = failureWords(failure, state),
        writesDone = state.writesDone.toList(),
        toolsRan = state.toolsRan.toList(),
        readThrough = state.readThrough,
        usage = state.usage,
        fillerContinued = state.fillerContinued,
        fillerAgain = state.fillerAgain,
        verbatimSpoken = state.verbatim,
        moneyDropped = state.moneyDropped,
        diagDropped = state.diagDropped,
        trailingFillerDropped = state.trailingFillerDropped,
        codeLines = state.codeLines,
    )

    /**
     * What a failed turn says about its writes: a write code already said is not said again, and
     * "Nothing was changed" is never said after one (the line said it was).
     */
    private fun failureWords(failure: TextFailure, state: TurnState): String {
        val unsaid = state.writesDone.toMutableList().apply { state.writesSaid.forEach { remove(it) } }
        return when {
            state.writesSaid.isEmpty() ->
                TextChatEngine.failureWords(failure, state.writesDone, state.writesUnconfirmed)
            unsaid.isEmpty() && state.writesUnconfirmed.isEmpty() -> failure.lead
            else -> TextChatEngine.failureWords(failure, unsaid, state.writesUnconfirmed)
        }
    }

    companion object {
        /** A spoken turn should not wait as long as a typed one; above the 45 s specialist ceiling. */
        const val BUDGET_MS = 60_000L
        const val READ_THROUGH_GROUNDING = "grounding"
        const val READ_THROUGH_FLEET_WEB = "fleet_web"

        /** On a write's result once code said it: the line, and what the model is told. */
        const val SAID_ALOUD = "said_aloud"
        const val SAID_ALOUD_NOTE = "said_aloud_note"
        const val SAID_NOTE = "The phone already said this aloud to him. Do not say it again; say only " +
            "anything else he still needs, or nothing."

        /** A cached request that failed because of the cache (gone, expired, not usable): retry inline. */
        internal fun isCacheError(e: StreamOutcome.HttpError): Boolean =
            e.code in CACHE_ERROR_CODES && e.body.contains("cache", ignoreCase = true)

        /** Google rejected Search together with function declarations. */
        internal fun isSearchRejected(e: StreamOutcome.HttpError): Boolean =
            e.code == HTTP_BAD_REQUEST && SEARCH_MARKERS.any { e.body.contains(it, ignoreCase = true) }

        private const val HTTP_BAD_REQUEST = 400
        private val CACHE_ERROR_CODES = setOf(400, 403, 404)
        private val SEARCH_MARKERS = listOf("google_search", "googleSearch", "server_side_tool", "built-in tools")
    }
}

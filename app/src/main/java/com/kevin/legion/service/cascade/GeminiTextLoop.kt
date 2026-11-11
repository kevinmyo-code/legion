package com.kevin.legion.service.cascade

import com.kevin.legion.ai.AgentProtocol
import com.kevin.legion.ai.AgentResult
import com.kevin.legion.ai.GeminiRest
import com.kevin.legion.ai.RestOutcome
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
class GeminiTextLoop(
    private val transport: GeminiStreamTransport,
    private val cache: ContextCacheStore?,
    private val classify: (RestOutcome.HttpError) -> AgentResult = GeminiRest::classify,
    private val isMutating: (name: String, args: JSONObject) -> Boolean,
    private val isReadThrough: (String) -> Boolean,
    private val toolTimeoutMs: (String) -> Long = { TextChatEngine.DEFAULT_TOOL_TIMEOUT_MS },
    private val meter: (model: String, usage: JSONObject?) -> Unit = { _, _ -> },
    private val maxRounds: Int = TextChatEngine.MAX_ROUNDS,
    private val budgetMs: Long = BUDGET_MS,
    private val now: () -> Long = System::currentTimeMillis,
) {
    interface Listener {
        /** Spoken text, as it streams. */
        fun onText(delta: String) {}

        /** The model asked for tools this round (before they run). */
        fun onToolCalls(names: List<String>) {}

        /** One tool finished. [writeDone] is true only for a write that came back `success: true`. */
        fun onToolResult(name: String, args: JSONObject, response: JSONObject, ms: Long, writeDone: Boolean) {}

        /** The turn became read-through, and why (tool name, `fleet_web`, `grounding`). */
        fun onReadThrough(source: String) {}

        /** One round's first text or call part arrived, [ms] after its request was sent. */
        fun onFirstPart(round: Int, ms: Long) {}
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
    )

    @Volatile private var searchRejected = false

    private class TurnState {
        val text = StringBuilder()
        val writesDone = mutableListOf<String>()
        val writesUnconfirmed = mutableListOf<String>()
        val toolsRan = mutableListOf<String>()
        var readThrough = false
        var usage = Usage()
    }

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
    ): Result {
        val state = TurnState()
        val result = try {
            withTimeoutOrNull(budgetMs) { loop(prefix, contents, executor, listener, state) }
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
    ): Result {
        val contents = JSONArray()
        for (i in 0 until initial.length()) contents.put(initial.get(i))
        var cacheBroken = false
        var round = 0
        while (true) {
            round++
            val prefix = if (searchRejected) basePrefix.copy(search = false) else basePrefix
            val force = round > maxRounds
            val cacheName = if (!force && !cacheBroken && !searchRejected) cache?.peek(prefix) else null
            val body = if (cacheName != null) {
                CascadeRequests.cached(cacheName, contents)
            } else {
                CascadeRequests.inline(prefix, contents, force)
            }
            val turn = StreamedTurn()
            val sentAt = now()
            var firstSeen = false
            val outcome = transport.stream(prefix.model, body) { ev ->
                val before = turn.text.length + turn.calls.size
                turn.onEvent(ev) { delta ->
                    state.text.append(delta)
                    listener.onText(delta)
                }
                if (!firstSeen && turn.text.length + turn.calls.size > before) {
                    firstSeen = true
                    listener.onFirstPart(round, now() - sentAt)
                }
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
            if (turn.blockReason != null) return fail(TextFailure.BLOCKED, state)
            if (turn.error != null && !turn.hasParts()) return fail(TextFailure.MODEL_ERROR, state)
            if (turn.grounded && !state.readThrough) {
                state.readThrough = true
                listener.onReadThrough(READ_THROUGH_GROUNDING)
            }
            val calls = turn.calls
            if (calls.isEmpty() || force) {
                return if (state.text.isBlank()) fail(TextFailure.NO_TEXT, state) else reply(state)
            }
            contents.put(turn.modelContent())
            listener.onToolCalls(calls.map { it.name })
            val results = coroutineScope {
                calls.map { call -> async { runOne(call, executor, listener, state) } }.awaitAll()
            }
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
    ): AgentProtocol.ToolResult = withContext(NonCancellable) {
        if (isReadThrough(call.name)) {
            synchronized(state) { state.readThrough = true }
            listener.onReadThrough(call.name)
        }
        val started = now()
        var confirmed = false
        val webRead = WebReadMark()
        val readThroughNow = synchronized(state) { state.readThrough }
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
        synchronized(state) {
            state.toolsRan.add(call.name)
            if (isMutating(call.name, call.args)) {
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
        AgentProtocol.ToolResult(call.name, call.id, response)
    }

    private fun reply(state: TurnState) = Result(
        text = state.text.toString().trim(),
        failure = null,
        failureWords = null,
        writesDone = state.writesDone.toList(),
        toolsRan = state.toolsRan.toList(),
        readThrough = state.readThrough,
        usage = state.usage,
    )

    private fun fail(failure: TextFailure, state: TurnState) = Result(
        text = state.text.toString().trim(),
        failure = failure,
        failureWords = TextChatEngine.failureWords(failure, state.writesDone, state.writesUnconfirmed),
        writesDone = state.writesDone.toList(),
        toolsRan = state.toolsRan.toList(),
        readThrough = state.readThrough,
        usage = state.usage,
    )

    companion object {
        /** A spoken turn should not wait as long as a typed one; above the 45 s specialist ceiling. */
        const val BUDGET_MS = 60_000L
        const val READ_THROUGH_GROUNDING = "grounding"
        const val READ_THROUGH_FLEET_WEB = "fleet_web"

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

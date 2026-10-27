package com.kevin.legion.service

import com.kevin.legion.ai.AgentProtocol
import com.kevin.legion.ai.AgentResult
import com.kevin.legion.ai.GeminiRest
import com.kevin.legion.ai.RestOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/** One line of the shared panel conversation, as history for the text model. */
data class ChatTurn(val fromUser: Boolean, val text: String)

/**
 * Runs one tool call for the text engine. In production this is
 * [LiveSessionController]'s own tool runner, the same function the voice path calls, so no tool has
 * a second implementation (ADR 0035). [readThrough] is the turn's read-through flag, which
 * `remember` gates on exactly as it does for voice.
 */
fun interface TextToolExecutor {
    suspend fun run(name: String, args: JSONObject, readThrough: Boolean): JSONObject
}

/** What one typed turn produced. */
sealed class TextTurnResult {
    /** Tool names whose write came back `success: true` in THIS turn. The only writes that may be called done. */
    abstract val writesDone: List<String>

    /** Whether a read-through tool ([LiveToolbox.EPISODIC_EXCLUDED_TOOLS]) ran this turn. */
    abstract val readThroughTouched: Boolean

    data class Reply(
        val text: String,
        override val writesDone: List<String>,
        override val readThroughTouched: Boolean,
    ) : TextTurnResult()

    /** Nothing usable came back. [words] says what did not happen, including which writes did. */
    data class Failed(
        val failure: TextFailure,
        val words: String,
        override val writesDone: List<String>,
        override val readThroughTouched: Boolean,
    ) : TextTurnResult()
}

enum class TextFailure(val lead: String) {
    KEY_INVALID("Not answered: Gemini rejected the key. Check it in Setup."),
    RATE_LIMITED("Not answered: Gemini's rate limit or quota was reached. Try again in a minute, or see Setup."),
    OVERLOADED("Not answered: Gemini is overloaded right now. Try again in a moment."),
    OFFLINE("Not answered: couldn't reach Gemini. Check the connection and try again."),
    MODEL_ERROR("Not answered: Gemini returned an error for that message."),
    BLOCKED("Not answered: Gemini declined to answer that."),
    TIMED_OUT("Not answered: that took too long and was stopped."),
    NO_TEXT("No written reply came back for that."),
}

/**
 * Typed chat on a normal text model (Kevin, 2026-10-27: "typed chat should go to a normal text llm
 * instead of the voice model"). Voice stays on Gemini Live; this never touches the Live socket.
 *
 * One `generateContent` function-calling loop on the user's own key, over [GeminiRest] (the same
 * metered HTTP boundary every [com.kevin.legion.ai.SubAgent] call uses) and the
 * [AgentProtocol] JSON pieces [com.kevin.legion.ai.SubAgent.investigate] already relies on. What it
 * adds over `investigate` is what a conversation needs: prior turns as history, an external tool
 * executor (the voice path's own), the read-through flag, and a per-turn account of which writes
 * actually succeeded.
 *
 * **Non-streaming.** The REST layer is `HttpURLConnection` with no SSE path, and a streamed
 * function-calling turn interleaves partial calls with text. The panel shows "Replying" with no text
 * until the whole reply lands.
 *
 * **Model knobs.** `gemini-3.8-flash` rejects `thinkingConfig` and `temperature` (commit 29057645),
 * so the body carries only systemInstruction, contents, tools and, on the backstop round, toolConfig.
 * No `googleSearch`: it cannot be mixed with function declarations on Flash (SubAgent's header), so
 * the text-mode note tells the model it has no web search here.
 *
 * **Round cap and budget.** Tools stay enabled for [maxRounds] POSTs, the model is nudged to wrap up
 * on the last of them, and only if it is still calling tools after that does one more POST force a
 * tool-free answer (`investigate`'s "forced-answer trap" fix, copied). The whole turn is bounded by
 * [budgetMs], which is longer than the 45 s sub-agent tool ceiling so one slow specialist cannot
 * consume it alone.
 */
class TextChatEngine(
    private val transport: suspend (JSONObject) -> RestOutcome = { GeminiRest.post(MODEL, it) },
    private val classify: (RestOutcome.HttpError) -> AgentResult = GeminiRest::classify,
    private val isMutating: (name: String, args: JSONObject) -> Boolean = LiveToolbox::isMutatingCall,
    private val isReadThrough: (String) -> Boolean = { GeminiLiveSession.isEpisodicExcludedTool(it) },
    private val toolTimeoutMs: (String) -> Long = { DEFAULT_TOOL_TIMEOUT_MS },
    private val maxRounds: Int = MAX_ROUNDS,
    private val budgetMs: Long = BUDGET_MS,
) {
    /** Per-turn state, kept outside the budget timeout so a timed-out turn can still report its writes. */
    private class TurnState {
        val writesDone = mutableListOf<String>()
        val writesUnconfirmed = mutableListOf<String>()
        var readThrough = false
    }

    /**
     * Answers [message] given [history] (oldest first, already capped by the caller with
     * [capHistory]). [systemParts] become the system instruction's parts in order. [onToolResult]
     * fires once per completed tool call, for the panel's tool line.
     */
    suspend fun answer(
        systemParts: List<String>,
        history: List<ChatTurn>,
        message: String,
        declarations: JSONArray,
        executor: TextToolExecutor,
        onToolResult: (name: String, response: JSONObject) -> Unit = { _, _ -> },
    ): TextTurnResult {
        val state = TurnState()
        val result = try {
            withTimeoutOrNull(budgetMs) {
                loop(systemParts, history, message, declarations, executor, onToolResult, state)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
            // A malformed response must end as a worded failure, never a crash in the panel's scope.
            fail(TextFailure.MODEL_ERROR, state)
        }
        return result ?: fail(TextFailure.TIMED_OUT, state)
    }

    @Suppress("LongParameterList", "ReturnCount") // one exit per way a round can end
    private suspend fun loop(
        systemParts: List<String>,
        history: List<ChatTurn>,
        message: String,
        declarations: JSONArray,
        executor: TextToolExecutor,
        onToolResult: (String, JSONObject) -> Unit,
        state: TurnState,
    ): TextTurnResult {
        val contents = historyContents(history).put(userContent(message))
        val tools = JSONArray().put(JSONObject().put("functionDeclarations", declarations))
        var round = 0
        while (true) {
            round++
            val forceAnswer = round > maxRounds
            val body = requestBody(systemParts, contents, tools, forceAnswer)
            val json = when (val o = transport(body)) {
                is RestOutcome.Ok -> o.json
                is RestOutcome.HttpError -> return fail(failureFor(classify(o)), state)
                RestOutcome.Network -> return fail(TextFailure.OFFLINE, state)
            }
            if (AgentProtocol.blockReason(json) != null) return fail(TextFailure.BLOCKED, state)
            val calls = AgentProtocol.functionCalls(json)
            if (calls.isEmpty() || forceAnswer) {
                val text = AgentProtocol.answerText(json)?.trim()
                return if (text.isNullOrEmpty()) {
                    fail(TextFailure.NO_TEXT, state)
                } else {
                    TextTurnResult.Reply(text, state.writesDone.toList(), state.readThrough)
                }
            }
            // Echo the model turn verbatim: parts may carry a thoughtSignature that must survive.
            AgentProtocol.modelContent(json)?.let { contents.put(it) }
            val results = calls.map { call ->
                val response = runOne(call, executor, state)
                onToolResult(call.name, response)
                AgentProtocol.ToolResult(call.name, call.id, response)
            }
            val nudge = if (round + 1 >= maxRounds) "Answer now with what you have." else null
            contents.put(AgentProtocol.functionResponseContent(results, nudge))
        }
    }

    /**
     * One tool call. The read-through flag is raised when the call ARRIVES, before it runs, which is
     * when the voice path raises it too. A write counts as done only on an explicit `success: true`;
     * an explicit `success: false` did not run; a timeout, a throw, or a result with no `success`
     * field is unconfirmed, because the action may or may not have happened.
     */
    private suspend fun runOne(
        call: AgentProtocol.FunctionCall,
        executor: TextToolExecutor,
        state: TurnState,
    ): JSONObject {
        if (isReadThrough(call.name)) state.readThrough = true
        var confirmedOutcome = false
        val response = try {
            withTimeoutOrNull(toolTimeoutMs(call.name)) {
                executor.run(call.name, call.args, state.readThrough)
            }?.also { confirmedOutcome = it.has("success") }
                ?: JSONObject().put("success", false).put("message", "That took too long and timed out.")
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception) {
            // The voice path's own wording for a thrown tool; the model is told in words.
            JSONObject().put("success", false).put("message", "Something went wrong running that.")
        }
        if (isMutating(call.name, call.args)) {
            when {
                confirmedOutcome && response.optBoolean("success", false) -> state.writesDone.add(call.name)
                !confirmedOutcome -> state.writesUnconfirmed.add(call.name)
            }
        }
        return response
    }

    private fun fail(failure: TextFailure, state: TurnState) = TextTurnResult.Failed(
        failure,
        failureWords(failure, state.writesDone, state.writesUnconfirmed),
        state.writesDone.toList(),
        state.readThrough,
    )

    companion object {
        /** The text model for typed chat. Same id voice notes use; see the class doc for its knobs. */
        const val MODEL = "gemini-3.8-flash"

        /** Per-tool ceiling when the caller supplies none; the controller passes its own. */
        const val DEFAULT_TOOL_TIMEOUT_MS = 10_000L

        /** Tool-enabled POSTs per typed turn, before the one tool-free backstop. */
        const val MAX_ROUNDS = 6

        /** Whole-turn ceiling. Above the 45 s sub-agent tool timeout on purpose. */
        const val BUDGET_MS = 120_000L

        /** History cap: the newest lines whose text fits in this many chars (about 2,000 tokens at chars/4). */
        const val HISTORY_MAX_CHARS = 8_000

        /** And never more than this many lines, however short. */
        const val HISTORY_MAX_TURNS = 30

        /**
         * The newest turns that fit [maxChars] and [maxTurns], oldest first, starting on a user
         * line (a conversation handed to Gemini opens with the user), with consecutive same-role
         * lines merged. A single line longer than the cap is dropped rather than cut mid-sentence.
         */
        fun capHistory(
            turns: List<ChatTurn>,
            maxChars: Int = HISTORY_MAX_CHARS,
            maxTurns: Int = HISTORY_MAX_TURNS,
        ): List<ChatTurn> {
            val kept = ArrayDeque<ChatTurn>()
            var chars = 0
            for (t in turns.asReversed().filter { it.text.isNotBlank() }) {
                if (kept.size >= maxTurns || chars + t.text.length > maxChars) break
                kept.addFirst(t)
                chars += t.text.length
            }
            while (kept.isNotEmpty() && !kept.first().fromUser) kept.removeFirst()
            val merged = mutableListOf<ChatTurn>()
            for (t in kept) {
                val last = merged.lastOrNull()
                if (last != null && last.fromUser == t.fromUser) {
                    merged[merged.lastIndex] = last.copy(text = last.text + "\n" + t.text)
                } else {
                    merged.add(t)
                }
            }
            return merged
        }

        /**
         * The worded failure: the lead sentence, then what did and did not change. A write is named
         * as completed only when its own result said `success: true` in this turn (CLAUDE.md sec 7);
         * one whose outcome is unknown is named as unconfirmed, never as done and never as undone.
         */
        fun failureWords(
            failure: TextFailure,
            writesDone: List<String>,
            writesUnconfirmed: List<String>,
        ): String = buildString {
            append(failure.lead)
            if (writesDone.isEmpty() && writesUnconfirmed.isEmpty()) {
                append(" Nothing was changed.")
            }
            if (writesDone.isNotEmpty()) {
                append(" Completed before it stopped: ")
                append(writesDone.distinct().joinToString(", ") { humanToolName(it) }).append('.')
            }
            if (writesUnconfirmed.isNotEmpty()) {
                append(" Not confirmed: ")
                append(writesUnconfirmed.distinct().joinToString(", ") { humanToolName(it) })
                append(". Check before trying again.")
            }
        }

        internal fun failureFor(r: AgentResult): TextFailure = when (r) {
            AgentResult.KeyInvalid -> TextFailure.KEY_INVALID
            AgentResult.RateLimited -> TextFailure.RATE_LIMITED
            AgentResult.Overloaded -> TextFailure.OVERLOADED
            AgentResult.Offline -> TextFailure.OFFLINE
            else -> TextFailure.MODEL_ERROR
        }

        internal fun historyContents(history: List<ChatTurn>): JSONArray {
            val arr = JSONArray()
            for (t in history) {
                arr.put(
                    JSONObject()
                        .put("role", if (t.fromUser) "user" else "model")
                        .put("parts", JSONArray().put(JSONObject().put("text", t.text))),
                )
            }
            return arr
        }

        private fun userContent(text: String): JSONObject = JSONObject()
            .put("role", "user")
            .put("parts", JSONArray().put(JSONObject().put("text", text)))

        internal fun requestBody(
            systemParts: List<String>,
            contents: JSONArray,
            tools: JSONArray,
            forceAnswer: Boolean,
        ): JSONObject = JSONObject().apply {
            val parts = JSONArray()
            systemParts.filter { it.isNotBlank() }.forEach { parts.put(JSONObject().put("text", it)) }
            if (parts.length() > 0) put("systemInstruction", JSONObject().put("parts", parts))
            put("contents", contents)
            put("tools", tools)
            if (forceAnswer) {
                put("toolConfig", JSONObject().put("functionCallingConfig", JSONObject().put("mode", "NONE")))
            }
        }
    }
}

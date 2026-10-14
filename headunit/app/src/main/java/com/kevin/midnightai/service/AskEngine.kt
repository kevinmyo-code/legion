package com.kevin.midnightai.service

import com.kevin.midnightai.ai.AgentResult
import com.kevin.midnightai.ai.AgentTool
import com.kevin.midnightai.backend.engine.EngineMcpClient
import com.kevin.midnightai.backend.engine.McpCallOutcome
import com.kevin.midnightai.backend.engine.McpFailure
import com.kevin.midnightai.backend.engine.McpListResult
import com.kevin.midnightai.backend.engine.McpTool
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * Why an `ask_engine` call did not succeed, as a fixed class with no user content, so it can be
 * reported (Crashlytics) and so each cause gets its own spoken sentence instead of one catch-all.
 */
enum class EngineFailureClass {
    /** No engine address or token on this device. */
    NotSignedIn,
    /** The engine (or the data signal) could not be reached, or answered with a server error. */
    Unreachable,
    /** The engine answered but its MCP endpoint is off (or it lists no tools). */
    SwitchedOff,
    /** The engine refused this device's token. */
    AuthRefused,
    /** The engine answered and refused the request itself (a 4xx other than 401/403/404, e.g. the
     * MCP SDK's 400 for a malformed envelope). Not [Unreachable]: the engine is up, and retrying
     * the same request will not help. [AskEngineAnswer.detail] carries the HTTP and JSON-RPC codes. */
    EngineRefused,
    /** The sub-agent loop ran past its own budget. */
    AgentTimeout,
    /** The Gemini side failed: HTTP error, rate limit, bad key, blocked or unparseable reply. */
    AgentError,
    /** A write did not land: the engine refused it, nothing was committed, or it was sent as "ask". */
    WriteRefused,
}

/** What `ask_engine` hands back to the Live session: the same `{success, message}` shape every
 * other tool returns. [failure] is set on every non-success and never crosses to the model.
 * [detail] is fixed codes only ("HTTP 400, JSON-RPC -32602"), no user content, for Crashlytics;
 * the server's own sentence is already in [message], which is what the model hears. */
data class AskEngineAnswer(
    val success: Boolean,
    val message: String,
    val failure: EngineFailureClass? = null,
    val detail: String? = null,
) {
    fun toJson(): JSONObject = JSONObject().put("success", success).put("message", message)
}

/**
 * The `ask_engine` bridge (engine-mcp ticket 11, ruled by ticket 04 option C): ONE Gemini
 * declaration, and behind it a cheap one-shot sub-agent that is handed the engine's own tool list
 * (`tools/list` on `/mcp`, fetched at call time) as its tools, picks one, calls it, and reports.
 * The declaration's cost to Live is constant however many tools the engine grows.
 *
 * ## What holds, and where
 *
 * - **Engine down, MCP off, token refused: a sentence, never an empty answer.** [EngineMcpClient]
 *   words each of these starting with what did NOT happen; [ask] returns that sentence as a
 *   failure, and the agent is never started, so the model has nothing to answer around.
 * - **A bridged write is reported done only on a successful result in that turn** (section 7).
 *   The loop's own `mutatingToolsCalled` counts any tool that RAN, `isError` or not, so it is not
 *   trusted here: this class keeps its own [Tally] of writes whose result was not an error. A write
 *   that came back `isError` makes the answer a FAILURE carrying the server's own sentence, over
 *   whatever the model said, because the model's prose is the thing that would say "done".
 * - **No mail gate, deliberately.** The phone app's version refuses a turn that read mail
 *   (`touchedReadThroughTool`) so third-party content never reaches the server. This app has no mail
 *   tool and no such flag, so that branch was dropped rather than faked; if a read-through tool is
 *   ever added here, the gate must come back with it.
 * - **Car-local data stays local.** [CAR_LOCAL_TABLES] are the car's own tables, which this app
 *   keeps and corrects on the device; the bridge keeps them out of the model's enum and refuses a
 *   direct naming, so ask_engine cannot read or rewrite what the local car code owns.
 *
 * Plain class with injected collaborators so the whole of it runs in a JVM test with a mock
 * transport and a fake agent.
 */
class AskEngine(
    private val client: EngineMcpClient,
    private val runAgent: suspend (
        systemInstruction: String,
        question: String,
        tools: List<AgentTool>,
    ) -> AgentResult,
    private val identityClause: () -> String,
    private val nowText: () -> String,
    private val onAgentResult: (AgentResult) -> Unit = {},
    /** Own bound on a WRITE call; a test shrinks it. Must stay below the write tool's
     * AgentTool.timeoutMs so this one always fires first (see [run]). */
    private val writeTimeoutMs: Long = WRITE_CALL_TIMEOUT_MS,
    /** Own bound on the whole sub-agent loop, so a budget overrun is told apart from a model
     * error (SubAgent.investigate maps both to AgentResult.Failed). The caller must give the loop
     * a LONGER budget than this so this one fires first; a test shrinks it. */
    private val agentBudgetMs: Long = AGENT_BUDGET_MS,
) {

    /** Writes tracked by [ask]: what committed, and what the server refused or failed. */
    private class Tally(val wantsWrite: Boolean) {
        val committed = mutableListOf<String>()
        val failed = mutableListOf<String>()
        /** Writes whose fate is UNKNOWN (timed out): they may have landed. Distinct from [failed]
         * because "the engine refused" is certain and this is not, and the wording must differ. */
        val unknown = mutableListOf<String>()
        /** Writes the bridge itself refused because the call was declared "ask". Nothing was sent. */
        val gated = mutableListOf<String>()
        var anyCalled = false
    }

    @Suppress("ReturnCount") // guard clauses, each a distinct refusal worded for its own cause
    /**
     * [intentDeclared] is false when the model left `intent` out and [wantsWrite] was INFERRED by
     * [EngineIntent]. An inferred record that commits nothing is answered with the agent's own
     * read answer, since the guess may have been wrong; only a DECLARED record with no committed
     * write is a WriteRefused failure.
     */
    suspend fun ask(question: String, wantsWrite: Boolean, intentDeclared: Boolean = true): AskEngineAnswer {
        if (question.isBlank()) {
            return AskEngineAnswer(
                false,
                "Nothing was read or written - there was no question to send the engine.",
                EngineFailureClass.AgentError,
            )
        }

        val tools = when (val listed = client.listTools()) {
            is McpListResult.Failed ->
                return AskEngineAnswer(
                    false,
                    listed.failure.sentence,
                    classOf(listed.failure),
                    detailOf(listed.failure),
                )
            is McpListResult.Listed -> listed.tools
        }
        if (tools.isEmpty()) {
            return AskEngineAnswer(
                false,
                "The engine lists no tools, so nothing was read or written.",
                EngineFailureClass.SwitchedOff,
            )
        }

        val tally = Tally(wantsWrite)
        val agentTools = tools.map { toAgentTool(it, tally) }
        val outcome = withTimeoutOrNull(agentBudgetMs) {
            runAgent(systemInstruction(wantsWrite), question, agentTools)
        } ?: return AskEngineAnswer(false, timedOut(tally), EngineFailureClass.AgentTimeout)
        onAgentResult(outcome)
        return when (outcome) {
            is AgentResult.Success -> judge(outcome.text, wantsWrite && intentDeclared, tally)
            AgentResult.RateLimited ->
                AskEngineAnswer(
                    false,
                    "The Gemini key just hit its rate limit, and nothing was sent to the engine. Give it a minute.",
                    EngineFailureClass.AgentError,
                )
            AgentResult.KeyInvalid ->
                AskEngineAnswer(
                    false,
                    "Something is wrong with the Gemini key, so the engine was not asked. Worth checking in Setup.",
                    EngineFailureClass.AgentError,
                )
            AgentResult.Offline ->
                AskEngineAnswer(
                    false,
                    "No data signal, so the engine question did not complete. " +
                        "Nothing is known to have been read or written.",
                    EngineFailureClass.Unreachable,
                )
            AgentResult.Failed, AgentResult.Overloaded -> modelErrored(tally)
        }
    }

    /** The outcome-verb rule applied to the finished loop. A refused or errored write beats the
     * model's prose; a `record` intent with no committed write is a failure, not a summary. */
    @Suppress("ReturnCount") // distinct verdicts, flattened is clearer than a nested if/else
    private fun judge(text: String, mustCommit: Boolean, tally: Tally): AskEngineAnswer {
        if (tally.unknown.isNotEmpty() && tally.committed.isEmpty()) {
            return AskEngineAnswer(false, unknownSentence(tally), EngineFailureClass.Unreachable)
        }
        if (tally.failed.isNotEmpty() && tally.committed.isEmpty()) {
            return AskEngineAnswer(
                false,
                "That was not written. The engine refused it and said: ${tally.failed.joinToString(" ")}",
                EngineFailureClass.WriteRefused,
            )
        }
        if (tally.gated.isNotEmpty() && tally.committed.isEmpty()) {
            // The sub-agent reached for a write on a call declared "ask". Say so in a way the Live
            // model can act on, instead of passing on prose like "I can't add that".
            return AskEngineAnswer(
                false,
                "Nothing was written: this went to the engine as a question (intent ask), so " +
                    "${tally.gated.joinToString()} was not called. If the driver asked to add or " +
                    "change something, call ask_engine again with intent record.",
                EngineFailureClass.WriteRefused,
            )
        }
        if (mustCommit && tally.committed.isEmpty()) {
            return AskEngineAnswer(
                false,
                "That did not get written down - the engine committed nothing. Try again and I will check it landed.",
                EngineFailureClass.WriteRefused,
            )
        }
        var note = if (tally.failed.isNotEmpty()) {
            " Part of it did not go through. The engine said: ${tally.failed.joinToString(" ")}"
        } else {
            ""
        }
        if (tally.unknown.isNotEmpty()) note += " Part of it timed out and may or may not have been written; check before retrying."
        return AskEngineAnswer(true, text + note)
    }

    private fun unknownSentence(tally: Tally) =
        "That timed out, so it may have been written (${tally.unknown.joinToString()}). " +
            "Check before retrying; do not assume it did not go through."

    /** What any calls already made say, which outranks the generic cause: a possibly-landed or
     * committed write must be named, and an engine refusal carries the engine's own words. */
    private fun afterCalls(tally: Tally): String? = when {
        tally.unknown.isNotEmpty() -> unknownSentence(tally)
        tally.failed.isNotEmpty() ->
            "That did not work. The engine refused it and said: ${tally.failed.joinToString(" ")}"
        tally.committed.isNotEmpty() ->
            "Something was written (${tally.committed.joinToString()}) but I could not finish the answer. Check it before relying on it."
        else -> null
    }

    /** The loop ran past [agentBudgetMs]. */
    private fun timedOut(tally: Tally): String = afterCalls(tally)
        ?: if (tally.anyCalled) {
            // A call may have been in flight when the loop was cut, so do not claim nothing landed.
            "The engine question took too long and was cut off part way. It cannot be confirmed " +
                "whether anything was written; check before retrying."
        } else {
            "The engine question took too long and was cut off before an answer came back. " +
                "Nothing was read or written. Try asking again."
        }

    /** Gemini errored (HTTP 5xx/other 4xx, blocked, or no usable answer). */
    private fun modelErrored(tally: Tally): AskEngineAnswer = AskEngineAnswer(
        false,
        afterCalls(tally)
            ?: "The AI model handling the engine question returned an error, so it did not finish. " +
            "Nothing was read or written.",
        if (tally.failed.isNotEmpty()) EngineFailureClass.WriteRefused else EngineFailureClass.AgentError,
    )

    private fun classOf(failure: McpFailure): EngineFailureClass = when (failure) {
        is McpFailure.NotSignedIn -> EngineFailureClass.NotSignedIn
        is McpFailure.Unreachable -> EngineFailureClass.Unreachable
        is McpFailure.SwitchedOff -> EngineFailureClass.SwitchedOff
        is McpFailure.AuthRefused -> EngineFailureClass.AuthRefused
        // A 4xx the engine chose to send (protocol/validation refusal): it is up and said no.
        is McpFailure.Refused -> if (failure.status in CLIENT_ERROR_RANGE && failure.status != THROTTLED) {
            EngineFailureClass.EngineRefused
        } else {
            // A 5xx, throttle or non-JSON-RPC reply to tools/list: the engine is not usable right now.
            EngineFailureClass.Unreachable
        }
    }

    private fun detailOf(failure: McpFailure): String? = (failure as? McpFailure.Refused)?.let { refused ->
        refused.status?.let { status ->
            "HTTP $status" + (refused.rpcCode?.let { ", JSON-RPC $it" } ?: "")
        }
    }

    private fun toAgentTool(tool: McpTool, tally: Tally): AgentTool {
        val translated = GeminiSchema.translate(tool.inputSchema, hide = HIDDEN_ENUM_VALUES)
        return AgentTool(
            name = tool.name,
            description = tool.description + translated.suffix,
            params = translated.properties,
            required = translated.required,
            // A write gets MORE than its inner bound so the inner one always fires first and can
            // record "unknown": if SubAgent's own withTimeoutOrNull cancelled first, the tally
            // would stay empty and the answer would claim nothing was committed.
            timeoutMs = if (tool.writes) writeTimeoutMs + WRITE_OUTER_SLACK_MS else CALL_TIMEOUT_MS,
        ) { rawArgs ->
            run(tool, translated, rawArgs, tally)
        }
    }

    @Suppress("ReturnCount") // guard clauses, each a distinct refusal worded for its own cause
    private suspend fun run(
        tool: McpTool,
        translated: GeminiSchema.Translated,
        rawArgs: JSONObject,
        tally: Tally,
    ): String {
        tally.anyCalled = true
        if (tool.writes && !tally.wantsWrite) {
            tally.gated += tool.name
            // The model's own declared intent is the gate (same as the other dispatchers): a call
            // that said "ask" cannot change anything. Nothing is sent, and the refusal tells the
            // sub-agent so, rather than leaving it to guess why the write vanished.
            return "Nothing was written. This was asked as a question, not as an instruction to " +
                "record or change anything, so ${tool.name} was not called."
        }
        val args = translated.restore(rawArgs)
        val table = args.optString("table")
        if (table in CAR_LOCAL_TABLES) {
            return "Nothing was ${if (tool.writes) "written" else "read"}. $table is the car's own " +
                "data, kept on this device, so it is not available through the engine."
        }
        val outcome = if (tool.writes) {
            withTimeoutOrNull(writeTimeoutMs) { client.callTool(tool.name, args, writes = true) }
        } else {
            client.callTool(tool.name, args, writes = false)
        }
        if (outcome == null) {
            // Cancelled mid-flight: the request may already have been applied server-side.
            tally.unknown += tool.name
            return "FAILED, outcome unknown: ${tool.name} timed out and it may have been written. " +
                "Do not call this tool again; tell the user to check before retrying."
        }
        return when (outcome) {
            is McpCallOutcome.Failed -> {
                val f = outcome.failure
                if (tool.writes && f is McpFailure.Unreachable && f.timedOut) {
                    tally.unknown += tool.name
                    "FAILED, outcome unknown: ${f.sentence} Do not call this tool again."
                } else {
                    if (tool.writes) tally.failed += f.sentence
                    f.sentence
                }
            }
            is McpCallOutcome.Called -> {
                val result = outcome.result
                if (tool.writes) {
                    if (result.isError) tally.failed += result.text else tally.committed += tool.name
                }
                // An error is handed back marked, so the sub-agent cannot read it as data.
                val body = capped(result.text)
                if (result.isError) "FAILED, not done: $body" else body
            }
        }
    }

    private fun capped(text: String): String =
        if (text.length <= RESULT_CAP) {
            text
        } else {
            text.take(RESULT_CAP) + " [cut: ${text.length - RESULT_CAP} more characters not shown]"
        }

    private fun systemInstruction(wantsWrite: Boolean): String =
        identityClause() +
            " You are answering one question from the user's own engine, the household server " +
            "that holds their events, checklists, places, body, ledger, pantry and fleet records. " +
            "The tools you are given ARE the engine; call the one that fits, with exactly the " +
            "arguments it describes, then answer in plain spoken text, no markdown. Now: " +
            "${nowText().trimEnd('.', ' ')}. " +
            "Rules that never bend. (1) Say only what a tool result says. If a result is empty it " +
            "is a real empty result; if it failed, nothing is known - say that, never 'you have " +
            "nothing'. (2) A result beginning FAILED means it did not happen; never report it as " +
            "done. (3) Say a row is done, added, ticked or deleted only after a write tool returned " +
            "a successful result in this conversation. (4) Repeat any UNVERIFIED or ESTIMATES " +
            "wording a row carries; never present such a figure as fact. (5) Money is integer " +
            "cents; speak it as currency. " +
            if (wantsWrite) {
                "The user asked for something to be recorded or changed, so use a write tool."
            } else {
                "The user only wants information; do not call a tool that writes."
            }

    companion object {
        private const val CALL_TIMEOUT_MS = 20_000L
        const val WRITE_CALL_TIMEOUT_MS = 20_000L
        private const val WRITE_OUTER_SLACK_MS = 5_000L
        /** The sub-agent loop's budget as seen by [AskEngine] (see [agentBudgetMs]). */
        const val AGENT_BUDGET_MS = 40_000L
        /** Give SubAgent.investigate AGENT_BUDGET_MS + this, so AskEngine's own bound fires first
         * and the overrun is reported as AgentTimeout rather than a generic failure. */
        const val AGENT_LOOP_SLACK_MS = 5_000L
        private const val RESULT_CAP = 8_000
        private val CLIENT_ERROR_RANGE = 400..499
        private const val THROTTLED = 429

        /** Synced tables the engine's `read_records`/`write_record` enums expose but that are the
         * CAR's own data. This app keeps chassis quirks and drive reassignments locally with their
         * own handling (a drive correction must be a RULE, never a re-key - see CLAUDE.md section
         * 5), so the engine path must not touch them. Both names are real registry tables
         * (`server/api/fleet.py` `table = ...`, checked 2026-10-10), so the enum filter bites. */
        val CAR_LOCAL_TABLES = setOf("chassis_quirks", "drive_reassignments")

        private val HIDDEN_ENUM_VALUES = CAR_LOCAL_TABLES

        /** The one declaration, in the same shape as the five `ask_*` dispatchers. */
        const val DESCRIPTION = "Read or change the driver's personal life in their own LEGION " +
            "engine: grocery/shopping lists and every other personal checklist, to-dos, calendar " +
            "events, voice notes, weight, meals, sleep, workout plan, personal memory, plus " +
            "ledger, pantry and places. Call it for any add, tick, read-back or question about " +
            "these; never say you can't see or add to a list without calling it. If the engine is " +
            "unreachable it says nothing was read or written. Answer comes back as text to speak."

        /** Compact on purpose: this is re-billed every Live turn. */
        const val INTENT_DESCRIPTION = "Required. \"record\" for ANY add, tick, log, create, save, " +
            "change or delete (a grocery item, an event, a note, a weight, a meal). \"ask\" only " +
            "to read. Under \"ask\" nothing can be written."
    }
}

/**
 * Whether an `ask_engine` call may write. The declared `intent` decides; it is required in the
 * schema, but a model can still omit it, and an omitted intent used to mean "ask", which silently
 * refused every write ("add milk to groceries" -> "I can't add that"). So a MISSING intent falls
 * back to a plain verb check on the question. An explicit "ask" is honoured as-is: the model said
 * read-only, and the bridge's write refusal then tells it to retry as "record".
 */
object EngineIntent {
    const val RECORD = "record"
    const val ASK = "ask"

    fun wantsWrite(intent: String?, question: String): Boolean = when (intent?.trim()?.lowercase()) {
        RECORD -> true
        ASK -> false
        else -> looksLikeWrite(question)
    }

    /** A request phrased as a question ("what's on my list", "did I log my weight") reads, unless
     * it is a polite command ("can you add milk"). So does one carrying a read signal anywhere
     * ("groceries, did I add milk", "remind me what's on my list"). Otherwise any write verb makes
     * it a record. Only used when `intent` is missing, and an inferred record that commits nothing
     * still returns the read answer (see [AskEngine.ask]), so a wrong guess costs little. */
    fun looksLikeWrite(question: String): Boolean {
        val q = question.trim().lowercase()
        if (q.isEmpty()) return false
        if (QUESTION_START.containsMatchIn(q) && !POLITE_COMMAND.containsMatchIn(q)) return false
        if (READ_SIGNAL.containsMatchIn(q)) return false
        return WRITE_VERB.containsMatchIn(q)
    }

    private val QUESTION_START = Regex(
        "^(what|what's|whats|when|where|which|who|how|why|did|do|does|is|are|am|was|were|have|has|" +
            "show|list|read|tell|any|anything)\\b",
    )
    private val POLITE_COMMAND = Regex("^(can|could|would|will) you\\b|^please\\b")
    private val READ_SIGNAL = Regex(
        "\\bremind me (what|what's|whats|when|where|which|who|how)\\b|\\b(did|do|does|have|has) i\\b",
    )
    // `logged` is past tense ("what I logged"), a read; `update` only as a verb on an object,
    // never the noun ("give me an update on my weight").
    private val WRITE_VERB = Regex(
        "\\b(add|put|log|tick|check off|cross off|mark|create|make a|save|record|note down|" +
            "write down|jot|remind me|schedule|book|delete|remove|update (my|the|it)|change|set)\\b",
    )
}

/**
 * MCP input schema (JSON Schema 2020-12) to the OpenAPI subset Gemini accepts, for the sub-agent's
 * `functionDeclarations`. Only the keys Gemini documents survive (`type`, `description`, `enum`,
 * `items`, `properties`, `required`, `minimum`, `maximum`, `nullable`); `format: uuid` and
 * `additionalProperties`, which the API rejects, are dropped.
 *
 * **An `object` with no declared properties becomes a JSON STRING.** Gemini refuses an OBJECT
 * schema with empty `properties`, and `write_record(fields)` is exactly that. The sub-agent is told
 * to pass the object as a JSON string and [Translated.restore] parses it back before the call, so
 * the engine still receives an object. A string that is not valid JSON is left a string and the
 * server's own schema check then refuses it in words.
 */
object GeminiSchema {

    class Translated(
        val properties: JSONObject,
        val required: List<String>,
        private val objectAsString: Set<String>,
        val suffix: String,
    ) {
        /** The arguments as the engine's schema wants them. */
        fun restore(args: JSONObject): JSONObject {
            val out = JSONObject(args.toString())
            for (name in objectAsString) {
                val raw = out.opt(name)
                if (raw is String) {
                    runCatching { JSONObject(raw) }.getOrNull()?.let { out.put(name, it) }
                }
            }
            return out
        }
    }

    fun translate(inputSchema: JSONObject, hide: Set<String> = emptySet()): Translated {
        val props = inputSchema.optJSONObject("properties") ?: JSONObject()
        val out = JSONObject()
        val objectAsString = mutableSetOf<String>()
        for (name in props.keys()) {
            val node = props.optJSONObject(name) ?: continue
            out.put(name, convert(node, hide, name, objectAsString, top = true))
        }
        val required = inputSchema.optJSONArray("required")?.let { array ->
            (0 until array.length()).map { array.getString(it) }
        } ?: emptyList()
        val suffix = if (objectAsString.isEmpty()) {
            ""
        } else {
            " Pass ${objectAsString.joinToString()} as a JSON object written out as a string."
        }
        return Translated(out, required, objectAsString, suffix)
    }

    private fun withoutHidden(values: JSONArray, hide: Set<String>): JSONArray {
        val kept = JSONArray()
        for (i in 0 until values.length()) {
            val value = values.get(i)
            if (value.toString() !in hide) kept.put(value)
        }
        return kept
    }

    private fun convertAll(nested: JSONObject, hide: Set<String>, objectAsString: MutableSet<String>): JSONObject {
        val converted = JSONObject()
        for (key in nested.keys()) {
            nested.optJSONObject(key)?.let {
                converted.put(key, convert(it, hide, key, objectAsString, top = false))
            }
        }
        return converted
    }

    private fun convert(
        node: JSONObject,
        hide: Set<String>,
        name: String,
        objectAsString: MutableSet<String>,
        top: Boolean,
    ): JSONObject {
        val out = JSONObject()
        val type = node.optString("type", "string")
        val bareObject = type == "object" && node.optJSONObject("properties")?.length().let { it == null || it == 0 }
        if (bareObject) {
            out.put("type", "string")
            if (top) objectAsString += name
        } else {
            out.put("type", type)
        }
        node.optString("description").takeIf { it.isNotBlank() }?.let { out.put("description", it) }
        node.optJSONArray("enum")?.let { out.put("enum", withoutHidden(it, hide)) }
        if (node.has("minimum")) out.put("minimum", node.get("minimum"))
        if (node.has("maximum")) out.put("maximum", node.get("maximum"))
        node.optJSONObject("items")?.let { out.put("items", convert(it, hide, name, objectAsString, top = false)) }
        node.optJSONObject("properties")?.takeIf { it.length() > 0 }?.let { nested ->
            out.put("properties", convertAll(nested, hide, objectAsString))
            node.optJSONArray("required")?.let { out.put("required", it) }
        }
        return out
    }
}

package com.kevin.legion.service

import com.kevin.legion.ai.AgentResult
import com.kevin.legion.ai.AgentTool
import com.kevin.legion.backend.engine.EngineMcpClient
import com.kevin.legion.backend.engine.McpCallOutcome
import com.kevin.legion.backend.engine.McpListResult
import com.kevin.legion.backend.engine.McpTool
import org.json.JSONArray
import org.json.JSONObject

/** What `ask_engine` hands back to the Live session: the same `{success, message}` shape every
 * other tool returns. */
data class AskEngineAnswer(val success: Boolean, val message: String) {
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
 * - **A turn that touched mail sends nothing.** `touchedReadThroughTool` (the same flag `remember`
 *   gates on) refuses the whole call before any request goes out: the question is the model's
 *   paraphrase of the turn and may BE mail content, and an MCP argument is a write to a server that
 *   persists it (CLAUDE.md section 7, third-party content). Limit, stated: the flag is set when the
 *   mail functionCall arrives, so a mail tool called AFTER `ask_engine` in one turn is not seen.
 * - **No screenless data (ADR 0035).** [SCREENLESS_TABLES] are synced tables the app has no screen
 *   for and no voice tool of its own; the bridge keeps them out of the model's enum and refuses a
 *   direct naming, so ask_engine adds no capability the hands cannot reach.
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
) {

    /** Writes tracked by [ask]: what committed, and what the server refused or failed. */
    private class Tally(val wantsWrite: Boolean) {
        val committed = mutableListOf<String>()
        val failed = mutableListOf<String>()
        var anyCalled = false
    }

    @Suppress("ReturnCount") // guard clauses, each a distinct refusal worded for its own cause
    suspend fun ask(question: String, wantsWrite: Boolean, touchedReadThroughTool: Boolean): AskEngineAnswer {
        if (question.isBlank()) {
            return AskEngineAnswer(false, "Nothing was read or written - there was no question to send the engine.")
        }
        if (touchedReadThroughTool) return AskEngineAnswer(false, MAIL_REFUSAL)

        val tools = when (val listed = client.listTools()) {
            is McpListResult.Failed -> return AskEngineAnswer(false, listed.failure.sentence)
            is McpListResult.Listed -> listed.tools
        }
        if (tools.isEmpty()) {
            return AskEngineAnswer(
                false,
                "The engine lists no tools, so nothing was read or written.",
            )
        }

        val tally = Tally(wantsWrite)
        val agentTools = tools.map { toAgentTool(it, tally) }
        val outcome = runAgent(systemInstruction(wantsWrite), question, agentTools)
        onAgentResult(outcome)
        return when (outcome) {
            is AgentResult.Success -> judge(outcome.text, wantsWrite, tally)
            AgentResult.RateLimited ->
                AskEngineAnswer(
                    false,
                    "The Gemini key just hit its rate limit, and nothing was sent to the engine. Give it a minute.",
                )
            AgentResult.KeyInvalid ->
                AskEngineAnswer(
                    false,
                    "Something is wrong with the Gemini key, so the engine was not asked. Worth checking in Setup.",
                )
            AgentResult.Offline ->
                AskEngineAnswer(
                    false,
                    "No data signal, so the engine question did not complete. " +
                        "Nothing is known to have been read or written.",
                )
            AgentResult.Failed, AgentResult.Overloaded ->
                AskEngineAnswer(false, failedAfterCalls(tally))
        }
    }

    /** The outcome-verb rule applied to the finished loop. A refused or errored write beats the
     * model's prose; a `record` intent with no committed write is a failure, not a summary. */
    @Suppress("ReturnCount") // three distinct verdicts, flattened is clearer than a nested if/else
    private fun judge(text: String, wantsWrite: Boolean, tally: Tally): AskEngineAnswer {
        if (tally.failed.isNotEmpty() && tally.committed.isEmpty()) {
            return AskEngineAnswer(
                false,
                "That was not written. The engine said: ${tally.failed.joinToString(" ")}",
            )
        }
        if (wantsWrite && tally.committed.isEmpty()) {
            return AskEngineAnswer(
                false,
                "That did not get written down - the engine committed nothing. Try again and I will check it landed.",
            )
        }
        val note = if (tally.failed.isNotEmpty()) {
            " Part of it did not go through. The engine said: ${tally.failed.joinToString(" ")}"
        } else {
            ""
        }
        return AskEngineAnswer(true, text + note)
    }

    private fun failedAfterCalls(tally: Tally): String = when {
        tally.failed.isNotEmpty() ->
            "That did not work. The engine said: ${tally.failed.joinToString(" ")}"
        tally.committed.isNotEmpty() ->
            "Something was written (${tally.committed.joinToString()}) but I could not finish the answer. Check it before relying on it."
        else -> "I could not work out how to ask the engine that, and nothing was read or written."
    }

    private fun toAgentTool(tool: McpTool, tally: Tally): AgentTool {
        val translated = GeminiSchema.translate(tool.inputSchema, hide = HIDDEN_ENUM_VALUES)
        return AgentTool(
            name = tool.name,
            description = tool.description + translated.suffix,
            params = translated.properties,
            required = translated.required,
            timeoutMs = CALL_TIMEOUT_MS,
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
            // The model's own declared intent is the gate (same as the other dispatchers): a call
            // that said "ask" cannot change anything. Nothing is sent, and the refusal tells the
            // sub-agent so, rather than leaving it to guess why the write vanished.
            return "Nothing was written. This was asked as a question, not as an instruction to " +
                "record or change anything, so ${tool.name} was not called."
        }
        val args = translated.restore(rawArgs)
        val table = args.optString("table")
        if (table in SCREENLESS_TABLES) {
            return "Nothing was ${if (tool.writes) "written" else "read"}. $table is internal sync " +
                "plumbing the app has no screen for, so it is not available by voice."
        }
        return when (val outcome = client.callTool(tool.name, args, writes = tool.writes)) {
            is McpCallOutcome.Failed -> {
                if (tool.writes) tally.failed += outcome.failure.sentence
                outcome.failure.sentence
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
            "arguments it describes, then answer in plain spoken text, no markdown. Now: ${nowText()}. " +
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
        private const val RESULT_CAP = 8_000

        const val MAIL_REFUSAL = "I will not take anything from this conversation to the engine while it " +
            "involves your mail - mail is read and dropped, never stored. Nothing was read or " +
            "written. Ask again on its own, in your own words."

        /** Synced tables with no screen and no voice tool of their own (ADR 0035). Looked up
         * 2026-10-02: `chassis_quirks` and `drive_reassignments` appear in no `ui/` file. */
        val SCREENLESS_TABLES = setOf("chassis_quirks", "drive_reassignments")

        private val HIDDEN_ENUM_VALUES = SCREENLESS_TABLES

        /** The one declaration, in the same shape as the five `ask_*` dispatchers. */
        const val DESCRIPTION = "Ask or change records only the household ENGINE (the server) " +
            "holds: what is due, checklists, places, body, ledger, pantry, fleet history. Use " +
            "when no named tool or other ask_* fits. If the engine is unreachable it says " +
            "nothing was read or written. Answer comes back as text to speak."

        /** Compact on purpose: this is re-billed every Live turn (LiveSetupPayloadSizeTest). */
        const val INTENT_DESCRIPTION = "\"record\" to write or change something, \"ask\" to only " +
            "read. A \"record\" call that writes nothing is reported as a failure."
    }
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

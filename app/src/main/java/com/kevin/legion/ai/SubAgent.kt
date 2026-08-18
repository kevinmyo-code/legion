package com.kevin.legion.ai

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * A focused Gemini worker the realtime voice loop delegates a single domain
 * task to. Where [com.kevin.legion.service.GeminiLiveSession] owns the
 * speech-to-speech conversation (and is the only thing that talks to the
 * driver), a SubAgent runs `generateContent` off-conversation and hands back a
 * tight chunk of text the Live model then speaks in character.
 *
 * Two modes:
 *  - [ask]: one-shot. Given [systemInstruction] + a caller-assembled context
 *    block + a question, return text (or null on failure). Optionally search-
 *    grounded via [useSearch]. Used for describeCodes, structuredQuery, and as
 *    the `web_lookup` backend.
 *  - [investigate]: a bounded function-calling loop. The worker is handed a set
 *    of read-only [AgentTool]s and pulls exactly the car data its reasoning
 *    needs, instead of the caller guessing what to pre-inject. Returns a typed
 *    [AgentResult] so the caller can phrase the right failure (rate limit vs.
 *    bad key vs. offline). NOTE: google_search cannot be mixed with function
 *    declarations on Flash-class models, so investigate never attaches it -
 *    web search is exposed as a `web_lookup` tool backed by a nested [ask].
 *
 * Stays entirely within the one Google Generative Language API (same key, same
 * REST endpoint as the maintenance-schedule lookup that used to live in
 * [AriaBrain.structuredQuery], which now delegates here).
 */
/**
 * [SubAgent.askWithUsage]'s result: [ask]'s text plus the measured token
 * counts Gemini billed for that call, when the response reports them. See
 * [SubAgent.askWithUsage]'s doc comment.
 */
data class AskOutcome(val text: String?, val promptTokens: Int?, val candidatesTokens: Int?)

/**
 * Requests Gemini's structured-output mode on one [SubAgent.askTyped] call: `generationConfig`
 * carries `responseMimeType = "application/json"` paired with [responseSchema], the OpenAPI-3.0-
 * SUBSET schema object the Generative Language API actually accepts (NOT full JSON Schema - no
 * `$ref`, no `oneOf`; the supported field set is `type`/`format`/`description`/`nullable`/`enum`/
 * `items`/`properties`/`required`/`propertyOrdering`, and `type` values are the proto `Type`
 * enum's names - `"STRING"`/`"OBJECT"`/`"ARRAY"`, uppercase, not JSON Schema's lowercase).
 *
 * Optional and off by default (ticket 21): [SubAgent.askTyped]'s `structuredOutput` parameter of
 * this type defaults to null, so [SubAgent]'s three other production callers
 * ([com.kevin.legion.ai.MemoryConsolidator], [com.kevin.legion.ai.ReflectionEngine],
 * [com.kevin.legion.service.AmbientListener]) - none of which pass one - see byte-identical
 * request bodies to before this ticket. Only [com.kevin.legion.advisor.AdvisorAgent] supplies one,
 * from [com.kevin.legion.advisor.AdvisorAnswer.responseSchema].
 */
data class StructuredOutputRequest(val responseSchema: JSONObject)

class SubAgent(
    private val systemInstruction: String = "",
    private val useSearch: Boolean = true,
    private val model: String = DEFAULT_MODEL,
) {
    /**
     * One-shot worker. [context] is the domain data block the caller assembled;
     * [question] is what to answer about it. Returns the worker's text, or null
     * on any failure so the caller can speak a fallback rather than a half-answer.
     *
     * [imageBytes], when set, attaches an inline image part alongside the text
     * (Gemini's `generateContent` accepts `inlineData` and `text` parts in the
     * same turn) - added for [com.kevin.legion.pantry.PantryReceiptAgent],
     * which needs vision to read a photographed receipt. No other caller uses
     * this yet; kept optional and off by default so nothing else changes shape.
     */
    suspend fun ask(
        context: String,
        question: String,
        imageBytes: ByteArray? = null,
        imageMimeType: String = "image/jpeg",
    ): String? = withContext(Dispatchers.IO) {
        val body = buildAskBody(context, question, imageBytes, imageMimeType)
        when (val o = postRaw(body)) {
            is HttpOutcome.Ok -> extractText(o.json)
            else -> null
        }
    }

    /**
     * [ask]'s result plus the token counts Gemini reports on every
     * `generateContent` call via `usageMetadata` - previously parsed by
     * nothing at all (`.scratch/ledger-drive-ingestion/issues/06-llm-spend-gate.md`
     * §6: "FACT: SubAgent does not parse usageMetadata"). Added for the
     * ledger LLM-spend gate, which needs a MEASURED count instead of a
     * reasoned one once at least one real call has run. Purely additive:
     * [ask]/[askTyped]/[investigate] are untouched, so pantry and the vehicle
     * agents that already call them see no behavior change.
     * [promptTokens]/[candidatesTokens] are null when the call never reached
     * a response (offline, HTTP error) or the field is absent from it.
     */
    suspend fun askWithUsage(
        context: String,
        question: String,
        imageBytes: ByteArray? = null,
        imageMimeType: String = "image/jpeg",
    ): AskOutcome = withContext(Dispatchers.IO) {
        val body = buildAskBody(context, question, imageBytes, imageMimeType)
        when (val o = postRaw(body)) {
            is HttpOutcome.Ok -> {
                val (promptTokens, candidatesTokens) = parseUsageMetadata(o.json)
                AskOutcome(extractText(o.json), promptTokens, candidatesTokens)
            }
            else -> AskOutcome(text = null, promptTokens = null, candidatesTokens = null)
        }
    }

    /**
     * Shared request body for [ask], [askWithUsage], and [askTyped] - same shape, different
     * response handling. [structuredOutput], when non-null, adds a `generationConfig` carrying
     * `responseMimeType = "application/json"` plus the caller's `responseSchema` (ticket 21).
     * Defaulted to null so [ask]/[askWithUsage] - neither of which passes one - are byte-identical
     * to before this parameter existed. `internal` (not private) so [SubAgentStructuredOutputTest]
     * can assert the built body's shape directly, the same pattern [userParts] and
     * [parseUsageMetadata] already use for network-free coverage.
     */
    internal fun buildAskBody(
        context: String,
        question: String,
        imageBytes: ByteArray?,
        imageMimeType: String,
        structuredOutput: StructuredOutputRequest? = null,
    ): JSONObject {
        val userText = buildString {
            if (context.isNotBlank()) append(context).append("\n\n")
            append(question)
        }
        return JSONObject().apply {
            if (systemInstruction.isNotBlank()) {
                put("systemInstruction", JSONObject().put(
                    "parts", JSONArray().put(JSONObject().put("text", systemInstruction))))
            }
            put("contents", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("parts", userParts(userText, imageBytes, imageMimeType))
            }))
            if (useSearch) {
                put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
            }
            if (structuredOutput != null) {
                put("generationConfig", JSONObject().apply {
                    put("responseMimeType", "application/json")
                    put("responseSchema", structuredOutput.responseSchema)
                })
            }
        }
    }

    /**
     * One-shot worker that returns a typed [AgentResult] instead of [ask]'s
     * String?, so the caller can phrase rate-limit vs. bad-key vs. offline
     * distinctly and record KeyHealth - the same error typing [investigate]
     * gives, at a single POST's cost. Use this (not [investigate]) for a
     * specialist whose reasoning doesn't actually need to pull tools adaptively:
     * pre-assemble the data into [context] and answer in one round. When
     * [useSearch] is set, google_search grounds the answer in this same POST
     * (allowed here because there are no function declarations to conflict with,
     * unlike in [investigate]).
     *
     * [structuredOutput], when supplied, asks Gemini's own structured-output mode to enforce the
     * caller's `responseSchema` (ticket 21 - see [StructuredOutputRequest]'s doc comment for the
     * accepted schema shape). Defaults to null: a caller that doesn't pass one gets the exact same
     * request body this method sent before this parameter existed - shared with [buildAskBody],
     * which is what actually assembles the body now (removes what used to be a second, drifting
     * copy of the same JSON-shape logic).
     */
    suspend fun askTyped(
        context: String,
        question: String,
        imageBytes: ByteArray? = null,
        imageMimeType: String = "image/jpeg",
        structuredOutput: StructuredOutputRequest? = null,
    ): AgentResult = withContext(Dispatchers.IO) {
        val body = buildAskBody(context, question, imageBytes, imageMimeType, structuredOutput)
        when (val o = postRaw(body)) {
            is HttpOutcome.Ok -> extractText(o.json)?.let { AgentResult.Success(it) } ?: AgentResult.Failed
            is HttpOutcome.HttpError -> classify(o)
            HttpOutcome.Network -> AgentResult.Offline
        }
    }

    /**
     * Builds the `parts` array for a user turn: text, plus an inline image part
     * if supplied. Internal (not private) so [SubAgentPartsTest] can verify the
     * JSON shape directly, without a real network call.
     */
    internal fun userParts(text: String, imageBytes: ByteArray?, imageMimeType: String): JSONArray {
        val parts = JSONArray().put(JSONObject().put("text", text))
        if (imageBytes != null) {
            parts.put(JSONObject().put("inlineData", JSONObject().apply {
                put("mimeType", imageMimeType)
                put("data", android.util.Base64.encodeToString(imageBytes, android.util.Base64.NO_WRAP))
            }))
        }
        return parts
    }

    /**
     * Tool-using investigation loop. Runs up to [maxModelCalls] model POSTs, within a whole-loop
     * [budgetMs] deadline. Between rounds it executes the [AgentTool]s the model requested and
     * feeds the results back. Returns a typed [AgentResult].
     *
     * [mutatingToolNames] (2026-08-17) names, among [tools], the ones that WRITE - the caller's own
     * knowledge, since [AgentTool] itself carries no such flag (see [AgentTool]'s doc comment: it
     * was written for the read-only case and never grew one). Defaulted to `emptySet()` so every
     * caller that predates this parameter (every one as of this fix) is byte-identical: nothing is
     * tracked, [AgentResult.Success.mutatingToolsCalled] comes back empty either way. The loop
     * itself never decides whether a write was REQUIRED - see [AgentResult.Success]'s doc comment
     * for why that decision stays with the caller.
     *
     * Round budgeting (2026-08-17 fix, "the forced-answer trap"): the OLD shape forced
     * `functionCallingConfig.mode = "NONE"` on round [maxModelCalls] itself, so a loop that had
     * spent its whole budget on read tools physically could not call a write tool on its last
     * round - it could only ever answer in prose, and prose claiming "logged it" is exactly what
     * reached the driver with nothing written. Tools now stay enabled through round
     * [maxModelCalls] (nudged, same as before, to wrap up); ONLY if the model still isn't done by
     * then does round `maxModelCalls + 1` force a tool-free answer as the true backstop. This
     * spends at most one extra POST, only when the model is still mid-call at the old cutoff -
     * a normal answer at or before round [maxModelCalls] costs exactly what it always did.
     */
    suspend fun investigate(
        context: String,
        question: String,
        tools: List<AgentTool>,
        maxModelCalls: Int = 4,
        budgetMs: Long = 30_000,
        mutatingToolNames: Set<String> = emptySet(),
    ): AgentResult = withContext(Dispatchers.IO) {
        withTimeoutOrNull(budgetMs) {
            runInvestigation(context, question, tools, maxModelCalls, mutatingToolNames)
        } ?: AgentResult.Failed
    }

    private suspend fun runInvestigation(
        context: String,
        question: String,
        tools: List<AgentTool>,
        maxModelCalls: Int,
        mutatingToolNames: Set<String>,
    ): AgentResult {
        val toolsByName = tools.associateBy { it.name }
        val declarations = AgentProtocol.declarations(tools)
        // The loop's own record of which mutating tools it actually dispatched, in call order -
        // never read from MidnightEvents or any other side channel (this file's own state, per
        // the brief: the loop is the one place that KNOWS a call was really made, not merely
        // requested by the model or claimed in its prose).
        val mutatingToolsCalled = mutableListOf<String>()

        val contents = JSONArray().put(
            JSONObject()
                .put("role", "user")
                .put("parts", JSONArray().put(JSONObject().put("text", buildString {
                    if (context.isNotBlank()) append(context).append("\n\n")
                    append(question)
                }))),
        )

        var postNumber = 0
        while (true) {
            postNumber++
            // See this fun's doc comment: the hard tool-free cutoff is now maxModelCalls + 1, not
            // maxModelCalls, so a write that was still outstanding at the old cutoff gets one more
            // real chance to run instead of being forced straight to prose.
            val forceAnswer = postNumber > maxModelCalls

            val body = JSONObject().apply {
                if (systemInstruction.isNotBlank()) {
                    put("systemInstruction", JSONObject().put(
                        "parts", JSONArray().put(JSONObject().put("text", systemInstruction))))
                }
                put("contents", contents)
                // NEVER google_search here - unsupported alongside function
                // declarations on Flash; web search is the web_lookup tool.
                put("tools", JSONArray().put(declarations))
                if (forceAnswer) {
                    put("toolConfig", JSONObject().put(
                        "functionCallingConfig", JSONObject().put("mode", "NONE")))
                }
            }

            val json = when (val o = postRaw(body)) {
                is HttpOutcome.Ok -> o.json
                is HttpOutcome.HttpError -> return classify(o)
                HttpOutcome.Network -> return AgentResult.Offline
            }

            if (AgentProtocol.blockReason(json) != null) return AgentResult.Failed

            val calls = AgentProtocol.functionCalls(json)
            if (calls.isEmpty() || forceAnswer) {
                val text = AgentProtocol.answerText(json)
                return if (text != null) {
                    AgentResult.Success(text, mutatingToolsCalled = mutatingToolsCalled.toList())
                } else {
                    AgentResult.Failed
                }
            }

            // Echo the model turn verbatim (parts may carry thoughtSignature).
            AgentProtocol.modelContent(json)?.let { contents.put(it) }

            Log.d(TAG, "investigate round $postNumber: called ${calls.map { it.name }}")

            val results = mutableListOf<AgentProtocol.ToolResult>()
            for (call in calls) {
                val tool = toolsByName[call.name]
                val response = if (tool == null) {
                    JSONObject().put("error", "unknown tool")
                } else {
                    try {
                        val out = withTimeoutOrNull(tool.timeoutMs) { tool.run(call.args) }
                        if (out != null) {
                            // Only recorded on a completed, non-throwing run - a timeout below
                            // falls into the "timed out" branch, never marked as called.
                            if (call.name in mutatingToolNames) mutatingToolsCalled.add(call.name)
                            JSONObject().put("result", out)
                        } else {
                            JSONObject().put("error", "timed out")
                        }
                    } catch (e: Exception) {
                        JSONObject().put("error", e.message ?: "tool failed")
                    }
                }
                results.add(AgentProtocol.ToolResult(call.name, call.id, response))
            }

            // Unchanged timing from before this fix: the nudge starts feeding into round
            // maxModelCalls - what used to BE the forced, tool-free round and is now instead the
            // last tool-enabled round (see this fun's doc comment). The model still hears "wrap
            // up" at the same point it always did; it just isn't physically barred from using
            // that round to actually finish an outstanding write.
            val nudge = if ((postNumber + 1) >= maxModelCalls) "Answer now with what you have." else null
            contents.put(AgentProtocol.functionResponseContent(results, nudge))
        }
    }

    private sealed class HttpOutcome {
        class Ok(val json: String) : HttpOutcome()
        class HttpError(val code: Int, val body: String) : HttpOutcome()
        object Network : HttpOutcome()
    }

    /**
     * POST the body, retrying once after a 1s pause on a transport failure.
     * NEVER retries a cancelled call: [postOnce] rethrows [kotlinx.coroutines.CancellationException]
     * (it does not fall into the generic catch below, since it isn't an `Exception` subtype path we
     * swallow inside [postOnce] - see that fun's own comment), so this loop unwinds via the
     * suspend-cancellation machinery before `attempt == 0` can ever re-enter with a fresh, doomed
     * connection opened on a job that's already dead.
     */
    private suspend fun postRaw(body: JSONObject): HttpOutcome {
        var attempt = 0
        while (true) {
            val outcome = postOnce(body)
            if (outcome is HttpOutcome.Network && attempt == 0) {
                attempt++
                delay(1_000)
                continue
            }
            return outcome
        }
    }

    /**
     * The actual HTTP round-trip. `HttpURLConnection` I/O is blocking Java, not a suspend fun, so a
     * coroutine parked in `connection.outputStream`/`.responseCode`/`.inputStream` is deaf to
     * cancellation - the thread only notices once the socket itself returns, which without this fix
     * meant every timeout wrapping this call ([AgentTool.timeoutMs], [investigate]'s [budgetMs],
     * and [com.kevin.legion.service.LiveSessionController.handleToolCall]'s 45s tool-call ceiling)
     * was inert down to the raw connect/read timeouts below. Two fixes make cancellation real:
     *
     * 1. `withContext(Dispatchers.IO)` moves the blocking calls onto the IO dispatcher and, more
     *    importantly, makes this suspend point cancellable - `withContext` checks for cancellation
     *    on entry/exit and rethrows [kotlinx.coroutines.CancellationException] at ITS OWN boundary,
     *    never swallowed by the `catch (e: Exception)` inside the block below (`CancellationException`
     *    thrown BY withContext itself, after the block returns, is outside that catch's scope).
     * 2. `invokeOnCompletion` on this call's own [kotlinx.coroutines.Job] forces the blocked socket
     *    read to unblock the moment the coroutine is cancelled, by calling `connection.disconnect()`
     *    from whatever thread cancels it. That makes the parked `responseCode`/`inputStream` call
     *    throw immediately instead of waiting out the full 30s read timeout. The disconnect races
     *    ordinary completion, so the handle is always disposed in `finally` regardless of which side
     *    won - a completed call disposing a no-op handle is harmless, a cancelled call disposing an
     *    already-fired one is also harmless.
     *
     * Socket-level `connectTimeout`/`readTimeout` stay as the backstop for a network stall that
     * happens with NO caller timeout at all (there is none such today, but it's cheap insurance);
     * they are no longer the thing actually bounding a stuck call in practice.
     */
    private suspend fun postOnce(body: JSONObject): HttpOutcome = withContext(Dispatchers.IO) {
        val url = URL("$API_URL/$model:generateContent?key=${GeminiKeyProvider.key()}")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            doOutput = true
            connectTimeout = 15000
            readTimeout = 30000
        }
        // Cancellation fires disconnect() on whatever thread cancels this coroutine, so the
        // blocked write/read below throws instead of sitting out the full socket timeout.
        val cancelHandle = coroutineContext.job.invokeOnCompletion { cause ->
            if (cause != null) runCatching { connection.disconnect() }
        }
        try {
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            if (code >= 400) {
                val err = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                Log.e(TAG, "SubAgent error $code: $err")
                HttpOutcome.HttpError(code, err)
            } else {
                HttpOutcome.Ok(connection.inputStream.bufferedReader().use { it.readText() })
            }
        } catch (e: Exception) {
            Log.e(TAG, "SubAgent request failed: ${e.message}", e)
            HttpOutcome.Network
        } finally {
            cancelHandle.dispose()
            connection.disconnect()
        }
    }

    /** Map an HTTP error to a typed result (mirrors [GeminiKeyValidator]'s key check). */
    private fun classify(e: HttpOutcome.HttpError): AgentResult = when {
        e.code == 429 -> AgentResult.RateLimited
        e.code == 401 || e.code == 403 -> AgentResult.KeyInvalid
        e.code == 400 && e.body.contains("API_KEY_INVALID") -> AgentResult.KeyInvalid
        e.code == 500 || e.code == 503 -> AgentResult.Overloaded
        else -> AgentResult.Failed
    }

    /**
     * Pulls `usageMetadata.promptTokenCount`/`candidatesTokenCount` out of a
     * `generateContent` response. Internal (not private), same pattern as
     * [userParts], so a unit test can verify the parse against a fabricated
     * response body without a network call. Returns nulls (not zeros) when
     * the field is absent, so a caller can distinguish "measured zero" from
     * "not reported" - Gemini omits `usageMetadata` entirely on some error
     * shapes even inside an otherwise-200 response.
     */
    internal fun parseUsageMetadata(json: String): Pair<Int?, Int?> = try {
        val usage = JSONObject(json).optJSONObject("usageMetadata")
        val prompt = usage?.takeIf { it.has("promptTokenCount") }?.optInt("promptTokenCount")
        val candidates = usage?.takeIf { it.has("candidatesTokenCount") }?.optInt("candidatesTokenCount")
        prompt to candidates
    } catch (e: Exception) {
        null to null
    }

    /**
     * Pulls the concatenated text out of a (non-streaming) generateContent
     * response. A single response can carry several `parts` (especially with
     * google_search grounding), so concatenate the text of all of them.
     */
    private fun extractText(json: String): String? {
        return try {
            val parts = JSONObject(json)
                .optJSONArray("candidates")
                ?.optJSONObject(0)
                ?.optJSONObject("content")
                ?.optJSONArray("parts")
                ?: return null

            val sb = StringBuilder()
            for (i in 0 until parts.length()) {
                sb.append(parts.optJSONObject(i)?.optString("text").orEmpty())
            }
            sb.toString().ifBlank { null }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse response: ${e.message}")
            null
        }
    }

    companion object {
        private const val TAG = "SubAgent"
        private const val API_URL = "https://generativelanguage.googleapis.com/v1beta/models"

        // Fast, cheap text model for delegated domain work. The voice turn runs
        // on the Live model (see GeminiLiveSession); workers don't need to be
        // conversational, just correct and quick - latency matters because the
        // driver is waiting mid-conversation while a worker runs. Bumped
        // 2026-07-22 from gemini-3.1-flash-lite to the newer 3.5 generation,
        // same Flash-Lite tier (not the pricier full 3.6 Flash - that would
        // trade this class's whole cost/latency reason for existing).
        const val DEFAULT_MODEL = "gemini-3.5-flash-lite"
    }
}

package com.kevin.legion.ai

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
class SubAgent(
    private val systemInstruction: String = "",
    private val useSearch: Boolean = true,
    private val model: String = DEFAULT_MODEL,
) {
    /**
     * One-shot worker. [context] is the domain data block the caller assembled;
     * [question] is what to answer about it. Returns the worker's text, or null
     * on any failure so the caller can speak a fallback rather than a half-answer.
     */
    suspend fun ask(context: String, question: String): String? = withContext(Dispatchers.IO) {
        val userText = buildString {
            if (context.isNotBlank()) append(context).append("\n\n")
            append(question)
        }

        val body = JSONObject().apply {
            if (systemInstruction.isNotBlank()) {
                put("systemInstruction", JSONObject().put(
                    "parts", JSONArray().put(JSONObject().put("text", systemInstruction))))
            }
            put("contents", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("parts", JSONArray().put(JSONObject().put("text", userText)))
            }))
            if (useSearch) {
                put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
            }
        }

        when (val o = postRaw(body)) {
            is HttpOutcome.Ok -> extractText(o.json)
            else -> null
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
     */
    suspend fun askTyped(context: String, question: String): AgentResult = withContext(Dispatchers.IO) {
        val userText = buildString {
            if (context.isNotBlank()) append(context).append("\n\n")
            append(question)
        }
        val body = JSONObject().apply {
            if (systemInstruction.isNotBlank()) {
                put("systemInstruction", JSONObject().put(
                    "parts", JSONArray().put(JSONObject().put("text", systemInstruction))))
            }
            put("contents", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("parts", JSONArray().put(JSONObject().put("text", userText)))
            }))
            if (useSearch) {
                put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
            }
        }
        when (val o = postRaw(body)) {
            is HttpOutcome.Ok -> extractText(o.json)?.let { AgentResult.Success(it) } ?: AgentResult.Failed
            is HttpOutcome.HttpError -> classify(o)
            HttpOutcome.Network -> AgentResult.Offline
        }
    }

    /**
     * Tool-using investigation loop. Runs up to [maxModelCalls] model POSTs
     * (the last is a forced answer-only round with function calling disabled),
     * within a whole-loop [budgetMs] deadline. Between rounds it executes the
     * [AgentTool]s the model requested and feeds the results back. Returns a
     * typed [AgentResult].
     */
    suspend fun investigate(
        context: String,
        question: String,
        tools: List<AgentTool>,
        maxModelCalls: Int = 4,
        budgetMs: Long = 30_000,
    ): AgentResult = withContext(Dispatchers.IO) {
        withTimeoutOrNull(budgetMs) { runInvestigation(context, question, tools, maxModelCalls) }
            ?: AgentResult.Failed
    }

    private suspend fun runInvestigation(
        context: String,
        question: String,
        tools: List<AgentTool>,
        maxModelCalls: Int,
    ): AgentResult {
        val toolsByName = tools.associateBy { it.name }
        val declarations = AgentProtocol.declarations(tools)

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
            val forceAnswer = postNumber >= maxModelCalls

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
                return if (text != null) AgentResult.Success(text) else AgentResult.Failed
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
                        if (out != null) JSONObject().put("result", out)
                        else JSONObject().put("error", "timed out")
                    } catch (e: Exception) {
                        JSONObject().put("error", e.message ?: "tool failed")
                    }
                }
                results.add(AgentProtocol.ToolResult(call.name, call.id, response))
            }

            // If the NEXT post is the forced-answer round, nudge now.
            val nudge = if ((postNumber + 1) >= maxModelCalls) "Answer now with what you have." else null
            contents.put(AgentProtocol.functionResponseContent(results, nudge))
        }
    }

    private sealed class HttpOutcome {
        class Ok(val json: String) : HttpOutcome()
        class HttpError(val code: Int, val body: String) : HttpOutcome()
        object Network : HttpOutcome()
    }

    /** POST the body, retrying once after a 1s pause on a transport failure. */
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

    private fun postOnce(body: JSONObject): HttpOutcome {
        val url = URL("$API_URL/$model:generateContent?key=${GeminiKeyProvider.key()}")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            doOutput = true
            connectTimeout = 15000
            readTimeout = 30000
        }
        return try {
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

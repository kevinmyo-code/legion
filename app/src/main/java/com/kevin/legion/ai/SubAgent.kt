package com.kevin.legion.ai

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
 * ([com.kevin.legion.ai.MemoryConsolidator] and [com.kevin.legion.ai.ReflectionEngine] - a third,
 * the retired AmbientListener, went with that feature) - neither passes one - see byte-identical
 * request bodies to before this ticket. Only [com.kevin.legion.advisor.AdvisorAgent] supplies one,
 * from [com.kevin.legion.advisor.AdvisorAnswer.responseSchema].
 */
data class StructuredOutputRequest(val responseSchema: JSONObject)

/**
 * Outcome of [SubAgent.uploadFile] - the resumable upload's first-leg-then-second-leg round trip
 * collapsed into one call for the caller. [Uploaded.name] is the bare resource name
 * (`files/xxxxx`, used by [SubAgent.awaitFileActive]/[SubAgent.deleteFile]); [Uploaded.uri] is the
 * fully-qualified `fileUri` [SubAgent.ask]/[SubAgent.askTyped] reference in a `fileData` part.
 * [Uploaded.state] is the File resource's own `state` at the moment upload finished - almost
 * always `"PROCESSING"` for audio per the research file ("Audio always passes through
 * PROCESSING"), which is exactly why a caller must still poll [SubAgent.awaitFileActive] before
 * referencing [Uploaded.uri] in a generation call.
 */
sealed class FileUploadResult {
    data class Uploaded(val name: String, val uri: String, val state: String) : FileUploadResult()
    data class Failed(val reason: String) : FileUploadResult()
}

/** Outcome of [SubAgent.awaitFileActive] - never returns while a file is still `PROCESSING`; that
 * state is spent from inside the polling loop, never handed to the caller. */
sealed class FileActiveResult {
    data class Active(val uri: String) : FileActiveResult()
    /** The File resource itself reported `FAILED`, or its own `error` field was set. */
    data class Failed(val reason: String) : FileActiveResult()
    /** Still `PROCESSING` when [SubAgent.awaitFileActive]'s own timeout ran out. */
    object TimedOut : FileActiveResult()
}

class SubAgent internal constructor(
    private val systemInstruction: String,
    private val useSearch: Boolean,
    private val model: String,
    /**
     * Test seam (2026-10-27, router mode): when set, every POST goes here instead of over HTTP, so
     * a JVM test can script Gemini's replies and count the calls. Null in production, which is
     * the only value the public constructor below can pass.
     */
    private val transport: (suspend (JSONObject) -> RestOutcome)?,
) {
    constructor(
        systemInstruction: String = "",
        useSearch: Boolean = true,
        model: String = DEFAULT_MODEL,
    ) : this(systemInstruction, useSearch, model, null)

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
     *
     * [fileUri]/[fileMimeType] (voice-notes ticket 03), when set, attach a `fileData` part
     * referencing a file already uploaded through the Files API ([uploadFile]) - the audio
     * equivalent of [imageBytes], added the same additive way rather than as a second helper
     * class. **Never send audio inline**: the research this ticket stands on
     * (`.scratch/voice-notes/research/gemini-audio-upload.md`) found a 20 MB total-request cap
     * that a modest recording already clears, so audio always goes through Files, never
     * `inlineData`. Both [imageBytes] and [fileUri] may not usefully coexist in one call today (no
     * caller does), but nothing here forbids it - [userParts] simply appends whichever are set.
     */
    suspend fun ask(
        context: String,
        question: String,
        imageBytes: ByteArray? = null,
        imageMimeType: String = "image/jpeg",
        fileUri: String? = null,
        fileMimeType: String = "audio/m4a",
    ): String? = withContext(Dispatchers.IO) {
        val body = buildAskBody(context, question, imageBytes, imageMimeType, fileUri = fileUri, fileMimeType = fileMimeType)
        when (val o = postRaw(body)) {
            is RestOutcome.Ok -> extractText(o.json)
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
        fileUri: String? = null,
        fileMimeType: String = "audio/m4a",
    ): AskOutcome = withContext(Dispatchers.IO) {
        val body = buildAskBody(context, question, imageBytes, imageMimeType, fileUri = fileUri, fileMimeType = fileMimeType)
        when (val o = postRaw(body)) {
            is RestOutcome.Ok -> {
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
        fileUri: String? = null,
        fileMimeType: String = "audio/m4a",
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
                put("parts", userParts(userText, imageBytes, imageMimeType, fileUri, fileMimeType))
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
        fileUri: String? = null,
        fileMimeType: String = "audio/m4a",
    ): AgentResult = withContext(Dispatchers.IO) {
        val body = buildAskBody(
            context, question, imageBytes, imageMimeType, structuredOutput,
            fileUri = fileUri, fileMimeType = fileMimeType,
        )
        when (val o = postRaw(body)) {
            is RestOutcome.Ok -> {
                val text = extractText(o.json)
                if (text != null) {
                    // See [parseFinishReason]'s doc comment - a MAX_TOKENS finish means the
                    // caller's own JSON is very likely unterminated (voice-notes ticket 03: the
                    // output cap is real for a long recording's verbatim transcript).
                    AgentResult.Success(text, truncated = parseFinishReason(o.json) == "MAX_TOKENS")
                } else {
                    AgentResult.Failed
                }
            }
            is RestOutcome.HttpError -> classify(o)
            RestOutcome.Network -> AgentResult.Offline
        }
    }

    /**
     * Builds the `parts` array for a user turn: text, plus an inline image part
     * if supplied. Internal (not private) so [SubAgentPartsTest] can verify the
     * JSON shape directly, without a real network call.
     */
    internal fun userParts(
        text: String,
        imageBytes: ByteArray?,
        imageMimeType: String,
        fileUri: String? = null,
        fileMimeType: String = "audio/m4a",
    ): JSONArray {
        val parts = JSONArray().put(JSONObject().put("text", text))
        if (imageBytes != null) {
            parts.put(JSONObject().put("inlineData", JSONObject().apply {
                put("mimeType", imageMimeType)
                put("data", android.util.Base64.encodeToString(imageBytes, android.util.Base64.NO_WRAP))
            }))
        }
        // `fileData`, not `inlineData` - referencing a file already uploaded through the Files
        // API ([uploadFile]), not attaching raw bytes. See [ask]'s doc comment for why audio never
        // goes inline.
        if (fileUri != null) {
            parts.put(JSONObject().put("fileData", JSONObject().apply {
                put("mimeType", fileMimeType)
                put("fileUri", fileUri)
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

            val body = toolCallingBody(contents, declarations, forceAnswer)

            val json = when (val o = postRaw(body)) {
                is RestOutcome.Ok -> o.json
                is RestOutcome.HttpError -> return classify(o)
                RestOutcome.Network -> return AgentResult.Offline
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
                val run = runTool(toolsByName[call.name], call, "investigate round $postNumber")
                // Only recorded on a completed, non-throwing run - a timeout falls into the
                // "timed out" branch of runTool, never marked as called.
                if (run.output != null && call.name in mutatingToolNames) mutatingToolsCalled.add(call.name)
                results.add(AgentProtocol.ToolResult(call.name, call.id, run.response))
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

    /**
     * Router mode (2026-10-27, Kevin: the voice agent feels slow). ONE model POST with [tools]
     * declared. If the model calls tools, they run (in parallel - callers hand this only read-only
     * tools, see LiveToolbox's DISPATCHED) under each tool's own [AgentTool.timeoutMs], and their
     * RAW results come back framed by [frameRouteResults] for the Live model to phrase itself. No
     * second POST composes prose, which is where [investigate] spends most of its time. If the
     * model calls no tool, its text comes back as [investigate]'s would.
     *
     * Known cost: a question that needs two DEPENDENT tool steps (look something up, then use it
     * as the next tool's argument) gets one round only. The model is told so in the question.
     *
     * Errors map to the same [AgentResult] variants [investigate] returns, so a caller's failure
     * wording does not change: HTTP errors through [classify], transport failure [AgentResult.Offline],
     * a blocked prompt, an empty reply, every requested tool failing, or [budgetMs] running out
     * [AgentResult.Failed]. A PARTIAL failure is not a failure: the failed calls' errors ride
     * along in the results so the Live model can say what it could not get.
     */
    suspend fun route(
        question: String,
        tools: List<AgentTool>,
        budgetMs: Long = 30_000,
        mutatingToolNames: Set<String> = emptySet(),
    ): AgentResult = withContext(Dispatchers.IO) {
        withTimeoutOrNull(budgetMs) { runRoute(question, tools, mutatingToolNames) } ?: AgentResult.Failed
    }

    private suspend fun runRoute(
        question: String,
        tools: List<AgentTool>,
        mutatingToolNames: Set<String>,
    ): AgentResult {
        val contents = JSONArray().put(
            JSONObject()
                .put("role", "user")
                .put("parts", JSONArray().put(JSONObject().put("text", question + ROUTE_NOTE))),
        )
        return when (val o = postRaw(toolCallingBody(contents, AgentProtocol.declarations(tools), false))) {
            is RestOutcome.Ok -> routeReply(o.json, question, tools, mutatingToolNames)
            is RestOutcome.HttpError -> classify(o)
            RestOutcome.Network -> AgentResult.Offline
        }
    }

    private suspend fun routeReply(
        json: String,
        question: String,
        tools: List<AgentTool>,
        mutatingToolNames: Set<String>,
    ): AgentResult {
        val calls = AgentProtocol.functionCalls(json)
        return when {
            AgentProtocol.blockReason(json) != null -> AgentResult.Failed
            calls.isEmpty() -> AgentProtocol.answerText(json)?.let { AgentResult.Success(it) } ?: AgentResult.Failed
            else -> runRoutedTools(question, calls, tools, mutatingToolNames)
        }
    }

    private suspend fun runRoutedTools(
        question: String,
        calls: List<AgentProtocol.FunctionCall>,
        tools: List<AgentTool>,
        mutatingToolNames: Set<String>,
    ): AgentResult {
        Log.d(TAG, "route: called ${calls.map { it.name }}")
        val toolsByName = tools.associateBy { it.name }
        val runs = coroutineScope {
            calls.map { call -> async { runTool(toolsByName[call.name], call, "route") } }.awaitAll()
        }
        val mutated = calls.zip(runs)
            .filter { (call, run) -> run.output != null && call.name in mutatingToolNames }
            .map { it.first.name }
        return if (runs.all { it.output == null }) {
            AgentResult.Failed
        } else {
            AgentResult.Success(
                frameRouteResults(question, calls.map { it.name }.zip(runs.map { it.response })),
                mutatingToolsCalled = mutated,
            )
        }
    }

    /** One tool execution: [output] is the tool's raw string on success, null on timeout/throw. */
    private class ToolRun(val output: String?, val response: JSONObject)

    /**
     * Runs one requested [call] against [tool] under the tool's own [AgentTool.timeoutMs]. Shared
     * by [investigate] and [route] so both report a missing tool, a timeout and a throw the same
     * way. [label] only feeds the log line.
     */
    private suspend fun runTool(tool: AgentTool?, call: AgentProtocol.FunctionCall, label: String): ToolRun {
        if (tool == null) return ToolRun(null, JSONObject().put("error", "unknown tool"))
        return try {
            val out = withTimeoutOrNull(tool.timeoutMs) { tool.run(call.args) }
            if (out != null) {
                ToolRun(out, JSONObject().put("result", out))
            } else {
                Log.w(TAG, "$label: ${call.name} timed out")
                ToolRun(null, JSONObject().put("error", "timed out"))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A thrown tool used to vanish into the {"error": ...} fed back to the model with no
            // trace on-device (CLAUDE.md §4 rule 6's shape one layer up: "called and failed" must
            // not be invisible). Logged, not swallowed - this is what would have shown ask_goals
            // actually attempting (and losing) a write during the 2026-08-17 defect, had one been
            // attempted and thrown instead of never being called at all.
            Log.w(TAG, "$label: ${call.name} failed: ${e.message}")
            ToolRun(null, JSONObject().put("error", e.message ?: "tool failed"))
        }
    }

    /** The function-calling request body [investigate] and [route] both send. */
    private fun toolCallingBody(contents: JSONArray, declarations: JSONObject, forceAnswer: Boolean): JSONObject =
        JSONObject().apply {
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

    /**
     * The POST itself lives in [GeminiRest] (2026-10-27), lifted out unchanged so the typed chat
     * engine shares this exact boundary: retry once on a transport failure, cancellable I/O,
     * metering through [GeminiUsageMeter], and [KeyHealth] recording. See [GeminiRest.post].
     */
    private suspend fun postRaw(body: JSONObject): RestOutcome {
        transport?.let { return it(body) }
        return GeminiRest.post(model, body)
    }

    /** Map an HTTP error to a typed result and record it in [KeyHealth]. See [GeminiRest.classify]. */
    private fun classify(e: RestOutcome.HttpError): AgentResult = GeminiRest.classify(e)

    /**
     * The Files API's resumable upload, two legs, exactly as
     * `.scratch/voice-notes/research/gemini-audio-upload.md` documents it (voice-notes ticket 03).
     * Used because inline base64 is unsafe above ~20 MB total request size - a 45-minute recording
     * at ticket 01's bitrate already clears 10 MB, and a meeting-length one clears the cap outright.
     *
     * **The session URL arrives in the `x-goog-upload-url` RESPONSE HEADER, not the body** - the
     * easy mistake the research file calls out by name, and the reason this reads
     * `connection.getHeaderField(...)` rather than parsing a JSON response on the first leg.
     *
     * Leg 1: `POST /upload/v1beta/files` with the resumable-start headers and a JSON body naming
     * only `displayName` (nothing else is required to start a session). Leg 2: `POST` to the
     * returned session URL with `upload, finalize` and the raw audio bytes as the body; THAT
     * response's `file` object carries the real `name`/`uri`/`state`.
     *
     * Returns [FileUploadResult.Failed] on any transport failure or a missing upload URL/file
     * object - there is no partial-success shape here worth distinguishing further, since a caller
     * that gets [FileUploadResult.Failed] has nothing to poll or reference either way.
     */
    suspend fun uploadFile(bytes: ByteArray, mimeType: String = "audio/m4a", displayName: String = "voice-note"): FileUploadResult =
        withContext(Dispatchers.IO) {
            val startUrl = URL("$UPLOAD_URL?key=${GeminiKeyProvider.key()}")
            val sessionUrl = try {
                val startBody = JSONObject().put("file", JSONObject().put("displayName", displayName))
                    .toString().toByteArray(Charsets.UTF_8)
                val conn = (startUrl.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 15000
                    readTimeout = 30000
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("X-Goog-Upload-Protocol", "resumable")
                    setRequestProperty("X-Goog-Upload-Command", "start")
                    setRequestProperty("X-Goog-Upload-Header-Content-Length", bytes.size.toString())
                    setRequestProperty("X-Goog-Upload-Header-Content-Type", mimeType)
                }
                try {
                    conn.outputStream.use { it.write(startBody) }
                    val code = conn.responseCode
                    val url = conn.getHeaderField("x-goog-upload-url")
                    if (code >= 400 || url.isNullOrBlank()) {
                        Log.e(TAG, "uploadFile: start leg failed, code=$code, url=$url")
                        return@withContext FileUploadResult.Failed("upload session start failed (HTTP $code)")
                    }
                    url
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                Log.e(TAG, "uploadFile: start leg threw: ${e.message}", e)
                return@withContext FileUploadResult.Failed("couldn't reach the upload service: ${e.message}")
            }

            try {
                val conn = (URL(sessionUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 15000
                    readTimeout = 120000
                    setRequestProperty("X-Goog-Upload-Offset", "0")
                    setRequestProperty("X-Goog-Upload-Command", "upload, finalize")
                }
                try {
                    conn.outputStream.use { it.write(bytes) }
                    val code = conn.responseCode
                    if (code >= 400) {
                        val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                        Log.e(TAG, "uploadFile: finalize leg failed $code: $err")
                        return@withContext FileUploadResult.Failed("upload finalize failed (HTTP $code)")
                    }
                    val responseText = conn.inputStream.bufferedReader().use { it.readText() }
                    val file = JSONObject(responseText).optJSONObject("file")
                        ?: return@withContext FileUploadResult.Failed("upload response carried no file object")
                    val name = file.optString("name").takeIf { it.isNotBlank() }
                        ?: return@withContext FileUploadResult.Failed("upload response carried no file name")
                    val uri = file.optString("uri").takeIf { it.isNotBlank() }
                        ?: return@withContext FileUploadResult.Failed("upload response carried no file uri")
                    FileUploadResult.Uploaded(name, uri, file.optString("state"))
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                Log.e(TAG, "uploadFile: finalize leg threw: ${e.message}", e)
                FileUploadResult.Failed("upload didn't complete: ${e.message}")
            }
        }

    /**
     * Polls `GET /v1beta/files/{name}` until `state` leaves `PROCESSING` or [timeoutMs] runs out.
     * "Audio always passes through PROCESSING; a PROCESSING uri fails at inference" (research
     * file) - so this is not optional between [uploadFile] and a `fileData`-referencing
     * [askTyped]/[ask] call, it is the step that makes the reference usable at all.
     */
    suspend fun awaitFileActive(
        name: String,
        timeoutMs: Long = 120_000,
        pollIntervalMs: Long = 2_000,
    ): FileActiveResult = withContext(Dispatchers.IO) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val outcome = try {
                val conn = (URL("$FILES_URL/$name?key=${GeminiKeyProvider.key()}")
                    .openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 15000
                    readTimeout = 30000
                }
                try {
                    val code = conn.responseCode
                    if (code >= 400) {
                        FileActiveResult.Failed("file status check failed (HTTP $code)")
                    } else {
                        val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                        when (json.optString("state")) {
                            "ACTIVE" -> FileActiveResult.Active(json.optString("uri"))
                            "FAILED" -> FileActiveResult.Failed(
                                json.optJSONObject("error")?.optString("message") ?: "file processing failed")
                            else -> null // still PROCESSING
                        }
                    }
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                Log.w(TAG, "awaitFileActive: status check threw: ${e.message}")
                null // transient - keep polling until the deadline
            }
            if (outcome != null) return@withContext outcome
            if (System.currentTimeMillis() >= deadline) return@withContext FileActiveResult.TimedOut
            delay(pollIntervalMs)
        }
        @Suppress("UNREACHABLE_CODE")
        FileActiveResult.TimedOut
    }

    /**
     * `DELETE /v1beta/files/{name}` - called once a transcript is safely in hand, per the research
     * file's own instruction, rather than leaving the file to the 48-hour auto-expiry. Best-effort:
     * returns false on any failure and never throws, since a caller that already has its transcript
     * has nothing left to roll back if cleanup itself fails - the file simply expires on Google's
     * side later instead.
     */
    suspend fun deleteFile(name: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val conn = (URL("$FILES_URL/$name?key=${GeminiKeyProvider.key()}")
                .openConnection() as HttpURLConnection).apply {
                requestMethod = "DELETE"
                connectTimeout = 15000
                readTimeout = 30000
            }
            try {
                val code = conn.responseCode
                if (code >= 400) {
                    Log.w(TAG, "deleteFile: delete failed (HTTP $code) for $name")
                }
                code < 400
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            Log.w(TAG, "deleteFile: threw for $name: ${e.message}")
            false
        }
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
    /**
     * `candidates[0].finishReason`, or null if absent/unparseable. Gemini sets this to
     * `"MAX_TOKENS"` when generation stopped because it hit the output cap rather than because
     * the model decided it was done - the signal [askTyped] uses to flag [AgentResult.Success.truncated]
     * (voice-notes ticket 03, output-cap handling: 65,536 output tokens is real for an hour-plus
     * verbatim transcript). Internal, not private, matching [parseUsageMetadata]'s own pattern -
     * a unit test can feed it a fabricated response body with no network call.
     */
    internal fun parseFinishReason(json: String): String? = try {
        JSONObject(json)
            .optJSONArray("candidates")
            ?.optJSONObject(0)
            ?.optString("finishReason")
            ?.takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        null
    }

    /**
     * `usageMetadata.totalTokenCount`, or null when the field is absent (2026-09-06).
     *
     * Separate from [parseUsageMetadata] rather than folded into it: that function returns a Pair
     * and has a caller ([askWithUsage] -> [com.kevin.legion.ledger.CategoryAgent]) plus
     * [com.kevin.legion.ai.SubAgentUsageMetadataTest] built on that exact shape, and widening it
     * to a Triple would change both for no gain.
     *
     * **Never falls back to `prompt + candidates`.** The total is its own reported figure and on
     * some models it exceeds the sum, because thinking tokens and cached content are counted in it
     * but reported in their own fields. Deriving it would produce a number that always looks
     * self-consistent and is sometimes wrong low - CLAUDE.md section 4 rule 6's shape.
     */
    internal fun parseTotalTokens(json: String): Int? = try {
        JSONObject(json).optJSONObject("usageMetadata")
            ?.takeIf { it.has("totalTokenCount") }
            ?.optInt("totalTokenCount")
    } catch (e: Exception) {
        null
    }

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

        /** Appended to a [route] question: the model gets one turn, so it must ask for everything at once. */
        private const val ROUTE_NOTE =
            "\n\nYou get one turn: call every tool the answer needs now. Their results go to the " +
                "user unedited, so if no tool fits, answer in plain spoken text."

        /**
         * The text [route] hands back when tools ran: a one-line frame, then each tool's name and
         * its raw result (parsed back to JSON where it is JSON) or its error. Pure and internal so
         * the shape is pinned by a test.
         */
        internal fun frameRouteResults(question: String, results: List<Pair<String, JSONObject>>): String {
            val arr = JSONArray()
            for ((name, response) in results) {
                val entry = JSONObject().put("tool", name)
                if (response.has("result")) {
                    entry.put("result", parseOrRaw(response.optString("result")))
                } else {
                    entry.put("error", response.optString("error"))
                }
                arr.put(entry)
            }
            return "Results for \"$question\". Relay them in your own voice; state only what is here.\n$arr"
        }

        /** A tool's output as JSON when it parses as JSON, else the raw string unchanged. */
        private fun parseOrRaw(s: String): Any = runCatching<Any> {
            when (s.trimStart().firstOrNull()) {
                '{' -> JSONObject(s)
                '[' -> JSONArray(s)
                else -> s
            }
        }.getOrDefault(s)

        // Files API bases (voice-notes ticket 03) - a different host path than API_URL's
        // `/v1beta/models`, both under the same generativelanguage.googleapis.com host and the
        // same BYO key. UPLOAD_URL is the resumable-start leg only; the second leg POSTs to
        // whatever session URL that leg's `x-goog-upload-url` response header hands back.
        private const val UPLOAD_URL = "https://generativelanguage.googleapis.com/upload/v1beta/files"
        // Deliberately the v1beta ROOT, not `.../v1beta/files` - [FileUploadResult.Uploaded.name]
        // is the file's full resource name (`files/{id}`, per Google's own resource-name
        // convention, same shape [uploadFile]'s own `file.optString("name")` read confirms), so
        // every caller below already appends a `name` that STARTS WITH `files/`. Appending it onto
        // a base that also ends in `/files` doubled the segment (`.../v1beta/files/files/{id}`),
        // which 404s unconditionally - found 2026-09-04 via
        // "The upload didn't finish processing: file status check failed (HTTP 404)" on every
        // single recording, never a transient miss.
        private const val FILES_URL = "https://generativelanguage.googleapis.com/v1beta"

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

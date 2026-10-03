package com.kevin.legion.backend.engine

import org.json.JSONArray
import org.json.JSONObject

/** One engine tool as `tools/list` describes it. [writes] is `!readOnlyHint`; an annotation the
 * server omitted reads as a WRITE, never as a read, so a tool that cannot say it is harmless is
 * tracked as if it were not. */
data class McpTool(
    val name: String,
    val description: String,
    val inputSchema: JSONObject,
    val writes: Boolean,
)

/** What `tools/call` answered. [isError] is the server's own flag; [text] is its sentence. */
data class McpCallResult(val text: String, val isError: Boolean)

/**
 * Why a call to `/mcp` produced no result, in the words a person hears. Every branch states what
 * did NOT happen first (CLAUDE.md section 7), and none of them is ever an empty string, because an
 * empty answer from a bridge reads as "there is nothing there" - the one sentence this class exists
 * never to let anyone infer.
 */
sealed interface McpFailure {
    val sentence: String

    /** No address or no token on this device: nothing could be sent. */
    data class NotSignedIn(override val sentence: String) : McpFailure

    /** The request never reached the engine (offline, wrong address, cleartext blocked). */
    data class Unreachable(override val sentence: String) : McpFailure

    /** 404 on `/mcp`: the engine answered, and the operator has not switched MCP on (server
     * `LEGION_MCP`, off by default) or this is an older engine with no such route. */
    data class SwitchedOff(override val sentence: String) : McpFailure

    /** 401/403: the engine does not accept this device's token. */
    data class AuthRefused(override val sentence: String) : McpFailure

    /** Any other refusal, a 5xx, a throttle, or a reply that was not JSON-RPC. */
    data class Refused(override val sentence: String) : McpFailure
}

sealed interface McpListResult {
    data class Listed(val tools: List<McpTool>) : McpListResult
    data class Failed(val failure: McpFailure) : McpListResult
}

sealed interface McpCallOutcome {
    data class Called(val result: McpCallResult) : McpCallOutcome
    data class Failed(val failure: McpFailure) : McpCallOutcome
}

/**
 * The phone as an MCP client of its own engine (engine-mcp ticket 11, ruled by ticket 04).
 * JSON-RPC over plain HTTP POST to `<engine base url>/mcp`, one request per call, no session: the
 * server (`server/engine_mcp/server.py`) runs stateless, so there is no `initialize` handshake to
 * hold open and nothing to resume.
 *
 * **The request envelope is the 2026-07-28 revision's** - `MCP-Protocol-Version`, `MCP-Method` and
 * (for a call) `MCP-Name` headers plus the protocol version under `params._meta` - which is exactly
 * what the server's own tests send (`server/tests/test_engine_mcp.py` `rpc`). `Accept` carries both
 * `application/json` and `text/event-stream` because the SDK refuses a POST that does not accept
 * both, even though the server only ever answers JSON.
 *
 * **Auth is [EngineHttp]'s, unchanged:** the phone's existing device token, `Authorization: Token
 * ...`, through the same transport every Django backend uses. A token's read/write scope is the
 * server's to enforce; a read-scoped token's refusal arrives as an `isError` result in words and is
 * relayed as one.
 *
 * `tools/list` is cached for [cacheMs] (a registry that changes on a deploy does not need
 * re-fetching every utterance), keyed on the engine address so a changed address never serves
 * another engine's tools. Failures are never cached.
 */
class EngineMcpClient(
    private val http: EngineHttp,
    private val baseUrl: () -> String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val cacheMs: Long = CACHE_MS,
) {

    private var cached: Pair<String, List<McpTool>>? = null
    private var cachedAt = 0L

    @Suppress("ReturnCount") // guard clauses, each a distinct worded failure
    suspend fun listTools(): McpListResult {
        notUsable()?.let { return McpListResult.Failed(it) }
        val key = baseUrl()
        cached?.let { (cachedKey, tools) ->
            if (cachedKey == key && clock() - cachedAt < cacheMs) return McpListResult.Listed(tools)
        }
        val reply = rpc("tools/list", name = null, params = JSONObject())
            .getOrElse { return McpListResult.Failed(failureOf(it, readSide = true)) }
        val parsed = parseTools(reply)
            ?: return McpListResult.Failed(
                McpFailure.Refused(
                    "The engine's reply to its tool list did not look like MCP, so nothing was " +
                        "read or written.",
                ),
            )
        cached = key to parsed
        cachedAt = clock()
        return McpListResult.Listed(parsed)
    }

    /** [writes] only changes the wording of a transport failure: a read that never answered read
     * nothing, a write that never answered may or may not have landed, and must not be worded as
     * though it certainly did not. */
    @Suppress("ReturnCount") // guard clauses, each a distinct worded failure
    suspend fun callTool(name: String, arguments: JSONObject, writes: Boolean): McpCallOutcome {
        notUsable()?.let { return McpCallOutcome.Failed(it) }
        val params = JSONObject().put("name", name).put("arguments", arguments)
        val reply = rpc("tools/call", name = name, params = params)
            .getOrElse { return McpCallOutcome.Failed(failureOf(it, readSide = !writes)) }
        val result = parseCall(reply)
            ?: return McpCallOutcome.Failed(
                McpFailure.Refused(
                    if (writes) {
                        "The engine's reply to $name did not look like MCP, so it cannot be " +
                            "confirmed that anything was written. Treat it as not written."
                    } else {
                        "The engine's reply to $name did not look like MCP, so nothing was read."
                    },
                ),
            )
        return McpCallOutcome.Called(result)
    }

    private fun notUsable(): McpFailure? =
        if (http.isUsable()) {
            null
        } else {
            McpFailure.NotSignedIn(
                "This phone has no engine address or no signed-in token, so nothing was read or " +
                    "written. Set the engine up in Setup first.",
            )
        }

    private suspend fun rpc(method: String, name: String?, params: JSONObject): Result<String> {
        params.put("_meta", JSONObject().put(PROTOCOL_META_KEY, PROTOCOL_VERSION))
        val body = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", 1)
            .put("method", method)
            .put("params", params)
        val headers = buildMap {
            put("Accept", "application/json, text/event-stream")
            put("MCP-Protocol-Version", PROTOCOL_VERSION)
            put("MCP-Method", method)
            if (name != null) put("MCP-Name", name)
        }
        return http.post(MCP_PATH, body.toString(), headers).map { it.body }
    }

    private fun failureOf(error: Throwable, readSide: Boolean): McpFailure {
        val failure = (error as? EngineHttpException)?.failure
        val what = if (readSide) "nothing was read or written" else "it cannot be confirmed that anything was written"
        return when (failure) {
            is EngineFailure.Unreachable -> McpFailure.Unreachable(
                "The engine is unreachable, so $what. ${failure.message}".trim(),
            )
            is EngineFailure.Unauthorized -> McpFailure.AuthRefused(
                "The engine refused this device's token, so $what. Sign in to the engine again.",
            )
            is EngineFailure.Refused -> if (failure.status == NOT_FOUND) {
                McpFailure.SwitchedOff(
                    "The engine's MCP endpoint is switched off or not there, so $what. " +
                        detailOf(failure.body),
                ).let { it.copy(sentence = it.sentence.trim()) }
            } else {
                McpFailure.Refused(
                    "The engine refused the request (HTTP ${failure.status}), so $what. " +
                        detailOf(failure.body),
                ).let { it.copy(sentence = it.sentence.trim()) }
            }
            is EngineFailure.Malformed -> McpFailure.Refused(
                "The engine's reply could not be read, so $what.",
            )
            null -> McpFailure.Refused("The request to the engine failed, so $what.")
        }
    }

    /** DRF's `{"detail": "..."}` unwrapped; anything else (HTML from a proxy, an empty body) is
     * dropped rather than spoken. */
    private fun detailOf(body: String): String =
        runCatching { JSONObject(body).optString("detail") }.getOrDefault("").take(DETAIL_MAX)

    companion object {
        const val MCP_PATH = "/mcp"
        const val PROTOCOL_VERSION = "2026-07-28"
        private const val PROTOCOL_META_KEY = "io.modelcontextprotocol/protocolVersion"
        private const val NOT_FOUND = 404
        private const val DETAIL_MAX = 400
        const val CACHE_MS = 5 * 60 * 1000L

        /** `result.tools[]` -> [McpTool]s, or null when the body is not a JSON-RPC result. */
        @Suppress("ReturnCount") // guard clauses, each a distinct worded failure
        internal fun parseTools(body: String): List<McpTool>? {
            val result = resultOf(body) ?: return null
            val array: JSONArray = result.optJSONArray("tools") ?: return null
            return (0 until array.length()).mapNotNull { index ->
                val tool = array.optJSONObject(index) ?: return@mapNotNull null
                val name = tool.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val readOnly = tool.optJSONObject("annotations")?.optBoolean("readOnlyHint", false) ?: false
                McpTool(
                    name = name,
                    description = tool.optString("description"),
                    inputSchema = tool.optJSONObject("inputSchema") ?: JSONObject(),
                    writes = !readOnly,
                )
            }
        }

        /** `result.content[].text` joined, with the server's own `isError`. A result with no text
         * at all is NOT passed through as an empty answer: it becomes an error in words. */
        @Suppress("ReturnCount") // guard clauses, each a distinct worded failure
        internal fun parseCall(body: String): McpCallResult? {
            val rpcError = runCatching { JSONObject(body).optJSONObject("error") }.getOrNull()
            if (rpcError != null) {
                return McpCallResult(
                    "The engine answered with a protocol error: ${rpcError.optString("message")}. " +
                        "Nothing is known to have happened.",
                    isError = true,
                )
            }
            val result = resultOf(body) ?: return null
            val content = result.optJSONArray("content")
            val text = buildString {
                if (content != null) {
                    for (index in 0 until content.length()) {
                        val block = content.optJSONObject(index) ?: continue
                        if (block.optString("type") == "text") append(block.optString("text"))
                    }
                }
            }
            val isError = result.optBoolean("isError", false)
            return if (text.isBlank()) {
                McpCallResult(
                    "The engine's tool returned no text, so nothing is known about what it did.",
                    isError = true,
                )
            } else {
                McpCallResult(text, isError)
            }
        }

        private fun resultOf(body: String): JSONObject? =
            runCatching { JSONObject(body).optJSONObject("result") }.getOrNull()
    }
}

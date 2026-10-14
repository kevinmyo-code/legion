package com.kevin.midnightai.backend.engine

import android.util.Log
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
    data class Unreachable(override val sentence: String, val timedOut: Boolean = false) : McpFailure

    /** 404 on `/mcp`: the engine answered, and the operator has not switched MCP on (server
     * `LEGION_MCP`, off by default) or this is an older engine with no such route. */
    data class SwitchedOff(override val sentence: String) : McpFailure

    /** 401/403: the engine does not accept this device's token. */
    data class AuthRefused(override val sentence: String) : McpFailure

    /** Any other refusal, a 5xx, a throttle, or a reply that was not JSON-RPC. [status] is the HTTP
     * status when there was one, [rpcCode] the JSON-RPC `error.code` the server put in the body
     * (e.g. -32602 for a malformed envelope). Both are fixed codes with no user content, so they
     * are safe to report; the server's own message travels in [sentence]. */
    data class Refused(
        override val sentence: String,
        val status: Int? = null,
        val rpcCode: Int? = null,
    ) : McpFailure
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
 * The head unit as an MCP client of the LEGION engine (ported from the phone app's engine-mcp
 * ticket 11 client, same server contract).
 * JSON-RPC over plain HTTP POST to `<engine base url>/mcp`, one request per call, no session: the
 * server (`server/engine_mcp/server.py`) runs stateless, so there is no `initialize` handshake to
 * hold open and nothing to resume.
 *
 * **The request envelope is the 2026-07-28 revision's** - `MCP-Protocol-Version`, `MCP-Method` and
 * (for a call) `MCP-Name` headers, plus BOTH required keys under `params._meta`: the protocol
 * version AND the client capabilities. That pair is what the server's own tests send
 * (`server/tests/test_engine_mcp.py` `rpc`). The SDK's per-request ladder
 * (`mcp/shared/inbound.py` `classify_inbound_request`, rung 1) answers HTTP 400 / -32602
 * "params._meta is missing the required envelope key(s)" to any request without the
 * capabilities key - before auth-scoped tool code ever runs. Leaving it out is what made every
 * `ask_engine` call fail with "refused the request (HTTP 400)" (fix/engine-http400, 2026-10-13).
 * `Accept` carries both `application/json` and `text/event-stream` because the SDK refuses a POST
 * that does not accept both, even though the server only ever answers JSON.
 *
 * **One fallback, and only for a rejection that ran nothing.** If the engine answers a request
 * with an envelope- or session-level rejection (400 with -32602 naming `_meta`, -32020 header
 * mismatch, -32021, -32022 unsupported version, or a session-id error), the request never reached
 * a tool, so it is safe to retry once even for a write. The retry goes over the handshake-era
 * protocol ([LEGACY_PROTOCOL_VERSION]): `initialize`, `notifications/initialized`, then the
 * request, carrying `Mcp-Session-Id` if the server minted one. That keeps the head unit working
 * against an engine whose SDK moved its modern revision on, and against a stateful server that
 * dropped its session (restart, redeploy): a session error there re-initializes once.
 *
 * **Auth is [EngineHttp]'s, unchanged:** this head unit's existing device token, `Authorization: Token
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
    /** [fresh] skips the cache READ (a connection test must hit the wire); a good answer still
     * refreshes the cache, which is harmless since it is the same data a normal call would store. */
    suspend fun listTools(fresh: Boolean = false): McpListResult {
        notUsable()?.let { return McpListResult.Failed(it) }
        val key = baseUrl()
        if (!fresh) {
            cached?.let { (cachedKey, tools) ->
                if (cachedKey == key && clock() - cachedAt < cacheMs) return McpListResult.Listed(tools)
            }
        }
        // One retry, tools/list ONLY: it is a read, so repeating it cannot double-apply anything,
        // and the first attempt's timeout is what wakes a scaled-to-zero Cloud Run instance. A
        // tools/call is never retried here - a write that timed out may have landed.
        var attempt = rpc("tools/list", name = null, params = JSONObject())
        if (attempt.exceptionOrNull().let { (it as? EngineHttpException)?.failure }
                .let { it is EngineFailure.Unreachable && it.timedOut }
        ) {
            attempt = rpc("tools/list", name = null, params = JSONObject())
        }
        val reply = attempt.getOrElse { return McpListResult.Failed(failureOf(it, readSide = true)) }
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
                "This head unit has no engine address or no signed-in token, so nothing was read or " +
                    "written. Sign in to the engine in Settings > Connections > Engine first.",
            )
        }

    /** A handshake-era session this client fell back to, for the engine at [baseUrl]. [sessionId]
     * is null when the server runs stateless and mints none. */
    private class LegacySession(val baseUrl: String, val protocolVersion: String, val sessionId: String?)

    @Volatile
    private var legacy: LegacySession? = null

    /**
     * The modern request first; on a rejection that provably ran nothing ([isPreDispatchRejection]),
     * one retry over the handshake-era protocol. A legacy session already in hand (same engine) is
     * used directly, and a session error on it re-initializes once. If the fallback handshake itself
     * fails, the ORIGINAL failure is returned: it carries the server's own reason for the 400.
     */
    private suspend fun rpc(method: String, name: String?, params: JSONObject): Result<String> {
        val key = baseUrl()
        legacy?.takeIf { it.baseUrl == key }?.let { session ->
            return legacyRpc(method, params, session, reinitOnSessionError = true)
        }
        val modern = modernRpc(method, name, params)
        val failure = (modern.exceptionOrNull() as? EngineHttpException)?.failure
        if (!isPreDispatchRejection(failure)) return modern
        warn("MCP $method rejected before dispatch (${describe(failure)}); retrying via $LEGACY_PROTOCOL_VERSION handshake")
        val session = initialize(key).getOrElse { return modern }
        legacy = session
        return legacyRpc(method, params, session, reinitOnSessionError = false)
    }

    private suspend fun modernRpc(method: String, name: String?, params: JSONObject): Result<String> {
        val meta = JSONObject()
            .put(PROTOCOL_META_KEY, PROTOCOL_VERSION)
            .put(CAPABILITIES_META_KEY, JSONObject())
            .put(CLIENT_INFO_META_KEY, clientInfo())
        val body = envelope(method, JSONObject(params.toString()).put("_meta", meta))
        val headers = buildMap {
            put("Accept", ACCEPT)
            put("MCP-Protocol-Version", PROTOCOL_VERSION)
            put("MCP-Method", method)
            if (name != null) put("MCP-Name", name)
        }
        return http.post(MCP_PATH, body.toString(), headers).map { it.body }
    }

    private suspend fun legacyRpc(
        method: String,
        params: JSONObject,
        session: LegacySession,
        reinitOnSessionError: Boolean,
    ): Result<String> {
        val sent = http.post(MCP_PATH, envelope(method, params).toString(), legacyHeaders(session))
            .map { it.body }
        val failure = (sent.exceptionOrNull() as? EngineHttpException)?.failure
        if (!reinitOnSessionError || !isSessionError(failure)) return sent
        warn("MCP session refused (${describe(failure)}); re-initializing once")
        val fresh = initialize(session.baseUrl).getOrElse { return sent }
        legacy = fresh
        return http.post(MCP_PATH, envelope(method, params).toString(), legacyHeaders(fresh)).map { it.body }
    }

    /** `initialize` then `notifications/initialized`, the 2025-11-25 handshake. */
    private suspend fun initialize(key: String): Result<LegacySession> {
        val params = JSONObject()
            .put("protocolVersion", LEGACY_PROTOCOL_VERSION)
            .put("capabilities", JSONObject())
            .put("clientInfo", clientInfo())
        val headers = mapOf("Accept" to ACCEPT)
        val reply = http.post(MCP_PATH, envelope("initialize", params).toString(), headers)
            .getOrElse { return Result.failure(it) }
        val negotiated = resultOf(reply.body)?.optString("protocolVersion")?.takeIf { it.isNotBlank() }
            ?: return Result.failure(
                EngineHttpException(EngineFailure.Malformed("The engine's reply to initialize was not MCP.")),
            )
        val session = LegacySession(key, negotiated, reply.headers[SESSION_HEADER.lowercase()])
        val note = JSONObject().put("jsonrpc", "2.0").put("method", "notifications/initialized")
        // 202 with no body; a failure here is not fatal, the request that follows will say so.
        http.post(MCP_PATH, note.toString(), legacyHeaders(session))
        return Result.success(session)
    }

    private fun legacyHeaders(session: LegacySession): Map<String, String> = buildMap {
        put("Accept", ACCEPT)
        put("MCP-Protocol-Version", session.protocolVersion)
        session.sessionId?.let { put(SESSION_HEADER, it) }
    }

    private fun envelope(method: String, params: JSONObject): JSONObject = JSONObject()
        .put("jsonrpc", "2.0")
        .put("id", 1)
        .put("method", method)
        .put("params", params)

    private fun clientInfo(): JSONObject = JSONObject().put("name", CLIENT_NAME).put("version", CLIENT_VERSION)

    private fun failureOf(error: Throwable, readSide: Boolean): McpFailure {
        val failure = (error as? EngineHttpException)?.failure
        val what = if (readSide) "nothing was read or written" else "it cannot be confirmed that anything was written"
        return when (failure) {
            is EngineFailure.Unreachable -> McpFailure.Unreachable(
                when {
                    // A write that timed out may have landed: no "try again" (it invites a
                    // duplicate), and say so in words.
                    failure.timedOut && !readSide ->
                        "The request to the engine timed out, so it cannot be confirmed that anything " +
                            "was written. It may have been written; check before retrying."
                    // A read is safe to repeat; the message says it took too long, which is also
                    // why "unreachable" would be wrong here.
                    failure.timedOut -> "The request to the engine timed out, so $what. ${failure.message}".trim()
                    else -> "The engine is unreachable, so $what. ${failure.message}".trim()
                },
                timedOut = failure.timedOut,
            )
            is EngineFailure.Unauthorized -> McpFailure.AuthRefused(
                "The engine refused this device's token, so $what. Sign in to the engine again.",
            )
            is EngineFailure.Refused -> if (failure.status == NOT_FOUND && rpcErrorOf(failure.body) == null) {
                McpFailure.SwitchedOff(
                    "The engine's MCP endpoint is switched off or not there, so $what. " +
                        detailOf(failure.body),
                ).let { it.copy(sentence = it.sentence.trim()) }
            } else {
                val rpcCode = rpcErrorOf(failure.body)?.optInt("code")
                warn("MCP refused: ${describe(failure)} body=${failure.body.take(DETAIL_MAX)}")
                McpFailure.Refused(
                    ("The engine refused the request (HTTP ${failure.status}" +
                        (rpcCode?.let { ", JSON-RPC $it" } ?: "") + "), so $what. " +
                        detailOf(failure.body)).trim(),
                    status = failure.status,
                    rpcCode = rpcCode,
                )
            }
            is EngineFailure.Malformed -> McpFailure.Refused(
                "The engine's reply could not be read, so $what.",
            )
            null -> McpFailure.Refused("The request to the engine failed, so $what.")
        }
    }

    /** The server's own reason: DRF's `{"detail": "..."}`, or the MCP SDK's JSON-RPC
     * `{"error": {"message": "..."}}` (which is what every SDK-level 400 carries, and which this
     * used to drop, leaving "refused (HTTP 400)" with no reason). Anything else (HTML from a proxy,
     * an empty body) is dropped rather than spoken. */
    private fun detailOf(body: String): String {
        val detail = runCatching { JSONObject(body).optString("detail") }.getOrDefault("")
        if (detail.isNotBlank()) return detail.take(DETAIL_MAX)
        val message = rpcErrorOf(body)?.optString("message").orEmpty()
        return if (message.isBlank()) "" else "The engine said: ${message.take(DETAIL_MAX)}"
    }

    private fun warn(message: String) {
        runCatching { Log.w(TAG, message) }
    }

    companion object {
        const val MCP_PATH = "/mcp"
        const val PROTOCOL_VERSION = "2026-07-28"
        /** The handshake-era revision the fallback speaks (`initialize` + optional session). */
        const val LEGACY_PROTOCOL_VERSION = "2025-11-25"
        private const val PROTOCOL_META_KEY = "io.modelcontextprotocol/protocolVersion"
        private const val CAPABILITIES_META_KEY = "io.modelcontextprotocol/clientCapabilities"
        private const val CLIENT_INFO_META_KEY = "io.modelcontextprotocol/clientInfo"
        private const val SESSION_HEADER = "Mcp-Session-Id"
        private const val ACCEPT = "application/json, text/event-stream"
        private const val CLIENT_NAME = "midnight-ai-head-unit"
        private const val CLIENT_VERSION = "1"
        private const val TAG = "EngineMcp"
        private const val BAD_REQUEST = 400
        private const val NOT_FOUND = 404
        private const val DETAIL_MAX = 400
        const val CACHE_MS = 5 * 60 * 1000L

        // JSON-RPC codes the SDK's per-request ladder answers BEFORE any handler runs
        // (mcp_types/jsonrpc.py, mcp/shared/inbound.py ERROR_CODE_HTTP_STATUS).
        private const val INVALID_PARAMS = -32602
        private const val HEADER_MISMATCH = -32020
        private const val MISSING_REQUIRED_CLIENT_CAPABILITY = -32021
        private const val UNSUPPORTED_PROTOCOL_VERSION = -32022
        private val LADDER_ONLY_CODES = setOf(HEADER_MISMATCH, MISSING_REQUIRED_CLIENT_CAPABILITY, UNSUPPORTED_PROTOCOL_VERSION)

        /** `error` of a JSON-RPC error body, or null. */
        internal fun rpcErrorOf(body: String): JSONObject? =
            runCatching { JSONObject(body).optJSONObject("error") }.getOrNull()

        /**
         * True only for a rejection the server gives before dispatching to any tool, so a retry
         * cannot double-apply a write: the envelope ladder's own codes, a -32602 that names the
         * `_meta` envelope (not a tool-argument -32602, which may come from a handler), or a
         * session-id error. A DRF refusal (`detail`, no JSON-RPC body) never qualifies.
         */
        internal fun isPreDispatchRejection(failure: EngineFailure?): Boolean {
            if (failure !is EngineFailure.Refused || failure.status != BAD_REQUEST && failure.status != NOT_FOUND) {
                return false
            }
            if (isSessionError(failure)) return true
            if (failure.status != BAD_REQUEST) return false
            val error = rpcErrorOf(failure.body) ?: return false
            val code = error.optInt("code")
            return code in LADDER_ONLY_CODES ||
                (code == INVALID_PARAMS && error.optString("message").contains("_meta"))
        }

        /** The SDK's "Bad Request: Missing session ID" (400) / "Not Found: Invalid or expired
         * session ID" (404), both JSON-RPC bodies (`mcp/server/streamable_http.py`). */
        internal fun isSessionError(failure: EngineFailure?): Boolean {
            if (failure !is EngineFailure.Refused) return false
            if (failure.status != BAD_REQUEST && failure.status != NOT_FOUND) return false
            val message = rpcErrorOf(failure.body)?.optString("message").orEmpty()
            return message.contains("session ID", ignoreCase = true)
        }

        /** Status and JSON-RPC code only, no body text: safe for a log line. */
        private fun describe(failure: EngineFailure?): String = when (failure) {
            is EngineFailure.Refused ->
                "HTTP ${failure.status}" + (rpcErrorOf(failure.body)?.let { ", JSON-RPC ${it.optInt("code")}" } ?: "")
            null -> "no failure"
            else -> failure::class.simpleName.orEmpty()
        }

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

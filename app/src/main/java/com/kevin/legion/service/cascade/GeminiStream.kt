package com.kevin.legion.service.cascade

import com.kevin.legion.ai.AgentProtocol
import com.kevin.legion.ai.GeminiKeyProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** How one `streamGenerateContent` POST ended. The events themselves went to the caller as they came. */
sealed interface StreamOutcome {
    data object Ok : StreamOutcome
    data class HttpError(val code: Int, val body: String) : StreamOutcome
    data class Network(val reason: String) : StreamOutcome
}

/** One streamed POST. [onEvent] gets each SSE `data:` object, on the transport's thread, in order. */
fun interface GeminiStreamTransport {
    suspend fun stream(model: String, body: JSONObject, onEvent: (JSONObject) -> Unit): StreamOutcome
}

/** `POST cachedContents`. Returns the cache's `name`, or the HTTP failure. */
fun interface CacheApi {
    suspend fun create(body: JSONObject): CacheCreateOutcome
}

sealed interface CacheCreateOutcome {
    data class Created(val name: String) : CacheCreateOutcome
    data class Failed(val code: Int, val body: String) : CacheCreateOutcome
}

/** Server-sent events, as Gemini sends them: one JSON object per `data:` line. Pure. */
object SseLines {
    /** The JSON after `data:`, or null for a blank line, a comment, or any other field. */
    @Suppress("ReturnCount")
    fun dataOf(line: String): JSONObject? {
        if (!line.startsWith("data:")) return null
        val payload = line.removePrefix("data:").trim()
        if (payload.isEmpty() || payload == "[DONE]") return null
        return runCatching { JSONObject(payload) }.getOrNull()
    }
}

/**
 * Everything one streamed model turn said, gathered as it arrives. Text parts go to the caller at
 * once (for the sentence splitter); thought parts are kept for the echo but never spoken; each
 * `functionCall` part arrives whole. [modelContent] echoes the parts VERBATIM, so a
 * `thoughtSignature` on any part survives into the next round (TextChatEngine's rule).
 */
class StreamedTurn {
    private val parts = JSONArray()
    val calls = mutableListOf<AgentProtocol.FunctionCall>()
    val text = StringBuilder()
    var usage: JSONObject? = null
        private set
    var finishReason: String? = null
        private set
    var blockReason: String? = null
        private set
    var error: String? = null
        private set
    var grounded = false
        private set
    var events = 0
        private set

    @Suppress("CyclomaticComplexMethod") // one branch per kind of part the stream can carry
    fun onEvent(ev: JSONObject, onText: (String) -> Unit = {}) {
        events++
        ev.optJSONObject("error")?.let { error = it.optString("message").ifBlank { it.toString() } }
        ev.optJSONObject("usageMetadata")?.let { usage = it }
        ev.optJSONObject("promptFeedback")?.optString("blockReason")
            ?.takeIf { it.isNotBlank() }
            ?.let { blockReason = it }
        val cand = ev.optJSONArray("candidates")?.optJSONObject(0) ?: return
        cand.optString("finishReason").takeIf { it.isNotBlank() }?.let { finishReason = it }
        if (cand.optJSONObject("groundingMetadata") != null) grounded = true
        val ps = cand.optJSONObject("content")?.optJSONArray("parts") ?: return
        for (i in 0 until ps.length()) {
            val p = ps.optJSONObject(i) ?: continue
            parts.put(p)
            p.optJSONObject("functionCall")?.let { fc ->
                val name = fc.optString("name")
                if (name.isNotBlank()) {
                    calls.add(
                        AgentProtocol.FunctionCall(
                            name,
                            fc.optJSONObject("args") ?: JSONObject(),
                            fc.optString("id").takeIf { it.isNotBlank() },
                        ),
                    )
                }
            }
            if (p.has("text") && !p.optBoolean("thought", false)) {
                val t = p.optString("text")
                if (t.isNotEmpty()) {
                    text.append(t)
                    onText(t)
                }
            }
        }
    }

    fun modelContent(): JSONObject = JSONObject().put("role", "model").put("parts", parts)

    fun hasParts(): Boolean = parts.length() > 0
}

/**
 * The cacheable prefix of every cascade request: the system instruction and the tools, which never
 * change within an app version (design doc decision 4). The date, the place and the conversation
 * context ride in `contents` instead, because an explicit cache hits only on an identical prefix.
 */
data class CascadePrefix(
    val model: String,
    val systemInstruction: String,
    val functionDeclarations: JSONArray,
    val search: Boolean = true,
) {
    fun tools(force: Boolean = false): JSONArray {
        val t = JSONArray()
        if (search && !force) t.put(JSONObject().put("googleSearch", JSONObject()))
        t.put(JSONObject().put("functionDeclarations", functionDeclarations))
        return t
    }

    /** Null when there is nothing to configure. */
    fun toolConfig(force: Boolean = false): JSONObject? = when {
        force -> JSONObject().put("functionCallingConfig", JSONObject().put("mode", "NONE"))
        // Function calling and Google Search together need this flag (design doc, Evidence 3: 400 without it).
        search -> JSONObject().put("includeServerSideToolInvocations", true)
        else -> null
    }

    /** What the cache is keyed by: any change to model, instruction or tools is a new cache. */
    val key: String by lazy {
        sha256(model + "\n" + systemInstruction + "\n" + tools() + "\n" + toolConfig())
    }

    companion object {
        fun sha256(s: String): String =
            MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}

/** The three request shapes. Pure, so the "cachedContent never travels with tools" rule is pinned by a test. */
object CascadeRequests {
    const val MAX_OUTPUT_TOKENS = 800

    private fun instruction(prefix: CascadePrefix): JSONObject =
        JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prefix.systemInstruction)))
    const val CACHE_TTL_S = 3_600L

    fun generationConfig(): JSONObject = JSONObject().put("maxOutputTokens", MAX_OUTPUT_TOKENS)

    /**
     * With a cache: ONLY `cachedContent`, `contents` and `generationConfig`. A request that also sets
     * `systemInstruction`, `tools` or `toolConfig` is a 400 (design doc, measured).
     */
    fun cached(cacheName: String, contents: JSONArray): JSONObject = JSONObject()
        .put("cachedContent", cacheName)
        .put("contents", contents)
        .put("generationConfig", generationConfig())

    /** Without a cache: the whole prefix inline. [force] is the tool-free backstop round (mode NONE). */
    fun inline(prefix: CascadePrefix, contents: JSONArray, force: Boolean): JSONObject = JSONObject().apply {
        put("systemInstruction", instruction(prefix))
        put("tools", prefix.tools(force))
        prefix.toolConfig(force)?.let { put("toolConfig", it) }
        put("contents", contents)
        put("generationConfig", generationConfig())
    }

    fun cacheCreate(prefix: CascadePrefix): JSONObject = JSONObject().apply {
        put("model", "models/${prefix.model}")
        put("displayName", "legion-cascade")
        put("systemInstruction", instruction(prefix))
        put("tools", prefix.tools())
        prefix.toolConfig()?.let { put("toolConfig", it) }
        put("ttl", "${CACHE_TTL_S}s")
    }
}

/**
 * The production [GeminiStreamTransport] and [CacheApi] over OkHttp, on the user's own key. The key
 * travels in the `x-goog-api-key` header, never in the URL, so no log line or error can carry it.
 * Cancelling the caller cancels the HTTP call at once.
 */
class OkHttpGemini(private val client: OkHttpClient = CLIENT) : GeminiStreamTransport, CacheApi {

    override suspend fun stream(model: String, body: JSONObject, onEvent: (JSONObject) -> Unit): StreamOutcome =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("$API/models/$model:streamGenerateContent?alt=sse")
                .header("x-goog-api-key", GeminiKeyProvider.key())
                .post(body.toString().toRequestBody(JSON))
                .build()
            val call = client.newCall(request)
            val handle = coroutineContext.job.invokeOnCompletion { if (it != null) call.cancel() }
            try {
                call.execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext StreamOutcome.HttpError(response.code, response.body?.string().orEmpty())
                    }
                    val source = response.body?.source() ?: return@withContext StreamOutcome.Network("empty_body")
                    while (true) {
                        val line = source.readUtf8Line() ?: break
                        SseLines.dataOf(line)?.let(onEvent)
                    }
                    StreamOutcome.Ok
                }
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                if (!coroutineContext.job.isActive) throw kotlinx.coroutines.CancellationException("cancelled")
                StreamOutcome.Network(e.javaClass.simpleName)
            } finally {
                handle.dispose()
            }
        }

    override suspend fun create(body: JSONObject): CacheCreateOutcome = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$API/cachedContents")
            .header("x-goog-api-key", GeminiKeyProvider.key())
            .post(body.toString().toRequestBody(JSON))
            .build()
        try {
            client.newCall(request).execute().use { r ->
                val text = r.body?.string().orEmpty()
                if (!r.isSuccessful) return@use CacheCreateOutcome.Failed(r.code, text)
                val name = runCatching { JSONObject(text).optString("name") }.getOrNull().orEmpty()
                if (name.isBlank()) CacheCreateOutcome.Failed(r.code, "no name") else CacheCreateOutcome.Created(name)
            }
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            CacheCreateOutcome.Failed(-1, e.javaClass.simpleName)
        }
    }

    companion object {
        private const val API = "https://generativelanguage.googleapis.com/v1beta"
        private val JSON = "application/json".toMediaType()
        private const val CONNECT_TIMEOUT_MS = 10_000L
        private const val READ_TIMEOUT_MS = 30_000L

        val CLIENT: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .build()
        }
    }
}

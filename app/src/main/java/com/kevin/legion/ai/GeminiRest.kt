package com.kevin.legion.ai

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

/** What one `generateContent` POST came back with, before anything reads the body. */
sealed class RestOutcome {
    class Ok(val json: String) : RestOutcome()
    class HttpError(val code: Int, val body: String) : RestOutcome()
    object Network : RestOutcome()
}

/**
 * The ONE HTTP boundary every Gemini REST `generateContent` call goes through: [SubAgent] (every
 * one-shot and investigate caller) and [TextChatEngine] (typed chat, 2026-10-27). The fallback
 * model retry (async-lookups ticket 03) sits one layer up, in [SubAgent]; [post] itself only ever
 * talks to the model it is handed.
 *
 * Lifted out of [SubAgent] unchanged so the typed chat engine meters, records [KeyHealth] and
 * honours cancellation exactly the way every other REST caller does. The 2026-09-06 lesson ("my
 * gemini credits burned really fast") was that metering at call sites gets forgotten by the next
 * caller; metering here cannot be.
 */
object GeminiRest {
    private const val TAG = "GeminiRest"
    private const val API_URL = "https://generativelanguage.googleapis.com/v1beta/models"
    private const val RETRY_PAUSE_MS = 1_000L
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 30_000
    private const val HTTP_ERROR_FLOOR = 400
    private const val HTTP_BAD_REQUEST = 400
    private const val HTTP_UNAUTHORIZED = 401
    private const val HTTP_FORBIDDEN = 403
    private const val HTTP_TOO_MANY = 429
    private const val HTTP_SERVER_ERROR = 500
    private const val HTTP_UNAVAILABLE = 503

    /**
     * POST [body] to [model]'s `generateContent`, retrying once after a 1 s pause on a transport
     * failure. Never retries a cancelled call: cancellation unwinds through [postOnce]'s
     * `withContext` before a second attempt can start.
     */
    suspend fun post(model: String, body: JSONObject): RestOutcome {
        var attempt = 0
        while (true) {
            val outcome = postOnce(model, body)
            if (outcome is RestOutcome.Network && attempt == 0) {
                attempt++
                delay(RETRY_PAUSE_MS)
                continue
            }
            return outcome
        }
    }

    /**
     * The actual HTTP round-trip. `HttpURLConnection` I/O is blocking Java, so a coroutine parked in
     * it is deaf to cancellation. `withContext(Dispatchers.IO)` makes the suspend point cancellable,
     * and `invokeOnCompletion` on this call's own job calls `disconnect()` the moment the coroutine
     * is cancelled, so a blocked read throws at once instead of sitting out the 30 s socket timeout.
     * Socket-level timeouts stay as the backstop for a stall with no caller timeout at all.
     */
    private suspend fun postOnce(model: String, body: JSONObject): RestOutcome = withContext(Dispatchers.IO) {
        val url = URL("$API_URL/$model:generateContent?key=${GeminiKeyProvider.key()}")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            doOutput = true
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
        }
        val cancelHandle = coroutineContext.job.invokeOnCompletion { cause ->
            if (cause != null) runCatching { connection.disconnect() }
        }
        try {
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            if (code >= HTTP_ERROR_FLOOR) {
                val err = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                Log.e(TAG, "Gemini REST error $code: $err")
                RestOutcome.HttpError(code, err)
            } else {
                val json = connection.inputStream.bufferedReader().use { it.readText() }
                // Meter EVERY REST call here, at the one HTTP boundary, rather than at call sites.
                // Fire-and-forget and self-swallowing inside the meter.
                val usage = runCatching { JSONObject(json).optJSONObject("usageMetadata") }.getOrNull()
                GeminiUsageMeter.recordRestCall(
                    model,
                    usage?.takeIf { it.has("promptTokenCount") }?.optInt("promptTokenCount"),
                    usage?.takeIf { it.has("candidatesTokenCount") }?.optInt("candidatesTokenCount"),
                    usage?.takeIf { it.has("totalTokenCount") }?.optInt("totalTokenCount"),
                )
                // A 200 is proof the key is valid and has quota, so any recorded problem is stale.
                KeyHealth.noteOk()
                RestOutcome.Ok(json)
            }
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            // Any transport or parse failure is "could not reach Gemini"; cancellation is not an
            // Exception path here (withContext rethrows it at its own boundary).
            Log.e(TAG, "Gemini REST request failed: ${e.message}", e)
            RestOutcome.Network
        } finally {
            cancelHandle.dispose()
            connection.disconnect()
        }
    }

    /**
     * Map an HTTP error to a typed result, recording the verdict in [KeyHealth] with the server's
     * own status and message attached so the Setup sentence can say what was actually seen.
     */
    fun classify(e: RestOutcome.HttpError): AgentResult = when {
        e.code == HTTP_TOO_MANY -> {
            KeyHealth.noteRateLimited("HTTP 429 from Gemini: ${e.body}")
            AgentResult.RateLimited
        }
        e.code == HTTP_UNAUTHORIZED || e.code == HTTP_FORBIDDEN -> {
            KeyHealth.noteInvalid("HTTP ${e.code} from Gemini: ${e.body}")
            AgentResult.KeyInvalid
        }
        e.code == HTTP_BAD_REQUEST && e.body.contains("API_KEY_INVALID") -> {
            KeyHealth.noteInvalid("HTTP 400 API_KEY_INVALID from Gemini")
            AgentResult.KeyInvalid
        }
        e.code == HTTP_SERVER_ERROR || e.code == HTTP_UNAVAILABLE -> AgentResult.Overloaded
        else -> AgentResult.Failed
    }

    /**
     * Why [e] may be retried on [SubAgent.FALLBACK_MODEL] (async-lookups ticket 03): `"RateLimited"`
     * for a 429, `"Overloaded"` for a 500 or 503, null for anything else. The same code split
     * [classify] makes, but pure: it records nothing in [KeyHealth], so a 429 the fallback then
     * recovers from leaves no stale "rate-limited" note behind. [classify] still runs on whatever
     * outcome the caller finally gets.
     */
    fun fallbackReason(e: RestOutcome.HttpError): String? = when (e.code) {
        HTTP_TOO_MANY -> "RateLimited"
        HTTP_SERVER_ERROR, HTTP_UNAVAILABLE -> "Overloaded"
        else -> null
    }
}

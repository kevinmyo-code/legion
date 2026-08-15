package com.kevin.legion.gmail

import android.util.Base64
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Thin, read-only Gmail REST client - the ticket 15 counterpart to
 * [com.kevin.legion.sync.DriveClient], same shape and same failing-soft posture, scoped to
 * exactly what `search_mail`/`read_mail` need: `users.messages.list` (with `q` and
 * `maxResults`) and `users.messages.get`. All calls are blocking;
 * [com.kevin.legion.service.LiveToolbox] runs them off the main thread via the tool
 * dispatch's own `withContext(Dispatchers.IO)`.
 *
 * **Parses only what §4/ticket 05 need**: message id, the `From`/`Subject`/`Date` headers, and
 * `snippet` for a search hit; the full decoded plain-text body for a single [fetchFull] read.
 * Nothing here writes anywhere - see [com.kevin.legion.service.LiveToolbox]'s mail tools for the
 * "nothing is stored" constraint this class has no opinion on, it just returns Kotlin values.
 *
 * Every method distinguishes a NETWORK failure (an [IOException] that never reached Google -
 * offline, DNS, timeout) from any other API failure (a non-2xx response: unauthorized, quota,
 * malformed request), because ticket 10's four failure messages depend on that distinction and
 * collapsing it here would make the caller unable to tell them apart - the exact defect
 * (`DriveConnectResolver`, 2026-08-03) this repo already paid for once.
 */
class GmailClient(private val accessToken: String) {

    /** One search hit: enough to speak a line, never a body. [timestampMs] is Gmail's own
     * `internalDate` (epoch millis) - parsed once here so [GmailToolLogic.relativeMailDate] never
     * has to parse the free-text RFC 2822 `Date` header itself. */
    data class MessageSummary(
        val id: String,
        val from: String,
        val subject: String,
        val dateHeader: String,
        val snippet: String,
        val timestampMs: Long,
    )

    /** One message's full plain-text body, for `read_mail`. */
    data class MessageBody(
        val id: String,
        val from: String,
        val subject: String,
        val dateHeader: String,
        val body: String,
        val timestampMs: Long,
    )

    /**
     * A page of search hits plus Gmail's own `resultSizeEstimate` for the query - the total the
     * "over the cap, state the total and read the first ten" wording (ticket 05) needs, since
     * [messages] itself is already truncated to whatever `maxResults` was requested and can never
     * answer "how many were there really" on its own.
     */
    data class SearchPage(val messages: List<MessageSummary>, val totalEstimate: Int)

    /** Whether a failed call never reached Google at all ([true], a network failure) or did and
     * came back with a non-2xx status ([false], quota/auth/malformed - a real API error).
     * [statusCode]/[body] are only ever populated for an actual HTTP response ([get]'s `else`
     * branch below) - null for a plain [IOException] that never got one, and null for a 2xx
     * response this class itself failed to parse (there [get] already succeeded; the failure is
     * downstream of it). They exist for exactly one caller, ticket 20's `GoogleAccessScreen`
     * Gmail TEST panel - `GmailToolLogic`'s spoken failure messages never look at them, only at
     * [networkFailure], so adding these two fields changes nothing anyone hears. */
    sealed interface FetchResult<out T> {
        data class Ok<T>(val value: T) : FetchResult<T>
        data class Failed(
            val networkFailure: Boolean,
            val statusCode: Int? = null,
            val body: String? = null,
        ) : FetchResult<Nothing>
    }

    private val client = OkHttpClient.Builder()
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    private fun authed(builder: Request.Builder): Request.Builder =
        builder.header("Authorization", "Bearer $accessToken")

    /**
     * `users.messages.list` with [query] passed to Gmail's own `q` UNCHANGED (ticket 05: the
     * model or the app's fixed briefing query, never rewritten here), capped at [maxResults],
     * then `users.messages.get(format=METADATA)` for each hit's headers + snippet. A hit that
     * fails to resolve individually is dropped from the returned list rather than failing the
     * whole search - this is a spoken read, not the §4 reconciliation gate, so a shorter list is
     * the honest outcome, not a wrong total silently reported as fact anywhere durable.
     */
    fun search(query: String, maxResults: Int): FetchResult<SearchPage> {
        val q = URLEncoder.encode(query, "UTF-8")
        val listUrl = "$BASE/messages?q=$q&maxResults=$maxResults"
        val listBody = when (val r = get(listUrl)) {
            is FetchResult.Ok -> r.value
            is FetchResult.Failed -> return r
        }
        val (ids, totalEstimate) = try {
            val json = JSONObject(listBody)
            val arr = json.optJSONArray("messages") ?: JSONArray()
            val ids = (0 until arr.length()).map { arr.getJSONObject(it).getString("id") }
            ids to json.optInt("resultSizeEstimate", ids.size)
        } catch (e: Exception) {
            Log.w(TAG, "search: unparseable list response: ${e.message}")
            return FetchResult.Failed(networkFailure = false)
        }
        return FetchResult.Ok(SearchPage(ids.mapNotNull { fetchSummary(it) }, totalEstimate))
    }

    private fun fetchSummary(id: String): MessageSummary? {
        val url = "$BASE/messages/$id?format=metadata" +
            "&metadataHeaders=From&metadataHeaders=Subject&metadataHeaders=Date"
        val body = (get(url) as? FetchResult.Ok)?.value ?: return null
        return try {
            val json = JSONObject(body)
            val headers = headerMap(json)
            MessageSummary(
                id = json.getString("id"),
                from = headers["From"].orEmpty(),
                subject = headers["Subject"].orEmpty(),
                dateHeader = headers["Date"].orEmpty(),
                snippet = json.optString("snippet"),
                timestampMs = json.optString("internalDate").toLongOrNull() ?: 0L,
            )
        } catch (e: Exception) {
            Log.w(TAG, "fetchSummary $id: unparseable response: ${e.message}")
            null
        }
    }

    /** `users.messages.get(format=FULL)` for one message, decoded to plain text - `read_mail`'s only call. */
    fun fetchFull(id: String): FetchResult<MessageBody> {
        val url = "$BASE/messages/$id?format=full"
        val body = when (val r = get(url)) {
            is FetchResult.Ok -> r.value
            is FetchResult.Failed -> return r
        }
        return try {
            val json = JSONObject(body)
            val headers = headerMap(json)
            val payload = json.optJSONObject("payload")
            val text = payload?.let { extractPlainText(it) } ?: json.optString("snippet")
            FetchResult.Ok(MessageBody(
                id = json.getString("id"),
                from = headers["From"].orEmpty(),
                subject = headers["Subject"].orEmpty(),
                dateHeader = headers["Date"].orEmpty(),
                body = text,
                timestampMs = json.optString("internalDate").toLongOrNull() ?: 0L,
            ))
        } catch (e: Exception) {
            Log.w(TAG, "fetchFull $id: unparseable response: ${e.message}")
            FetchResult.Failed(networkFailure = false)
        }
    }

    private fun headerMap(message: JSONObject): Map<String, String> {
        val headers = message.optJSONObject("payload")?.optJSONArray("headers") ?: return emptyMap()
        return buildMap {
            for (i in 0 until headers.length()) {
                val h = headers.getJSONObject(i)
                put(h.getString("name"), h.optString("value"))
            }
        }
    }

    /**
     * Walks a MIME payload for the first `text/plain` part (recursing into nested
     * `multipart/alternative`/`multipart/mixed`), falling back to `text/html` with tags
     * stripped if no plain part exists anywhere in the tree. Null means genuinely nothing
     * decodable was found - [fetchFull] falls back to the `snippet` in that case rather than
     * reading nothing at all.
     */
    private fun extractPlainText(payload: JSONObject): String? {
        fun decode(body: JSONObject?): String? {
            val data = body?.optString("data")?.takeIf { it.isNotBlank() } ?: return null
            return try {
                String(Base64.decode(data, Base64.URL_SAFE or Base64.NO_WRAP))
            } catch (e: Exception) {
                null
            }
        }

        val mime = payload.optString("mimeType")
        if (mime == "text/plain") decode(payload.optJSONObject("body"))?.let { return it }

        val parts = payload.optJSONArray("parts")
        if (parts != null) {
            for (i in 0 until parts.length()) {
                val part = parts.getJSONObject(i)
                if (part.optString("mimeType") == "text/plain") {
                    decode(part.optJSONObject("body"))?.let { return it }
                }
            }
            for (i in 0 until parts.length()) {
                extractPlainText(parts.getJSONObject(i))?.let { return it }
            }
            for (i in 0 until parts.length()) {
                val part = parts.getJSONObject(i)
                if (part.optString("mimeType") == "text/html") {
                    decode(part.optJSONObject("body"))?.let { return it.replace(Regex("<[^>]*>"), " ") }
                }
            }
        }

        if (mime == "text/html") decode(payload.optJSONObject("body"))?.let { return it.replace(Regex("<[^>]*>"), " ") }
        return null
    }

    private fun get(url: String): FetchResult<String> =
        try {
            client.newCall(authed(Request.Builder().url(url)).get().build()).execute().use { resp ->
                if (resp.isSuccessful) {
                    FetchResult.Ok(resp.body?.string().orEmpty())
                } else {
                    // Read the body BEFORE logging/returning - Response.use only closes the
                    // stream once this block exits, but resp.body.string() can only be called
                    // once, so this is the one and only read of a non-2xx body. Google puts the
                    // actual refusal reason here (unverified-app / disabled API / wrong scope
                    // read as different JSON, not as different status codes), which is the whole
                    // point of ticket 20 - the four spoken failure messages never read this, only
                    // [networkFailure], so capturing it changes nothing anyone hears.
                    val errorBody = resp.body?.string()
                    Log.w(TAG, "GET $url failed: ${resp.code}")
                    FetchResult.Failed(networkFailure = false, statusCode = resp.code, body = errorBody)
                }
            }
        } catch (t: IOException) {
            Log.w(TAG, "GET $url network error", t)
            FetchResult.Failed(networkFailure = true)
        } catch (t: Throwable) {
            Log.w(TAG, "GET $url error", t)
            FetchResult.Failed(networkFailure = false)
        }

    private companion object {
        const val TAG = "GmailClient"
        const val BASE = "https://gmail.googleapis.com/gmail/v1/users/me"
    }
}

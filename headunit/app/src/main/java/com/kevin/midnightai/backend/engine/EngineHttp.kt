package com.kevin.midnightai.backend.engine

import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * Why a request to the Django engine did not come back with a 2xx. **Every branch is a failure -
 * there is no branch here that a caller could mistake for a success**, which is CLAUDE.md section
 * 7's outcome-verb rule expressed as a type rather than as a convention someone has to remember:
 * `EngineHttp` returns `Result.success` for a 2xx and nothing else, so "the write landed" can only
 * ever be said off a real 2xx body.
 *
 * The four branches exist because four different next actions follow from them, not for
 * taxonomy's sake:
 *
 * - [Unauthorized] - the token this device holds is gone, revoked or wrong
 *   (`household/authentication.py`'s `DeviceTokenAuthentication` 401s for all three, and DRF
 *   renders `IsHouseholdMember`'s 403 refusals in the same `{"detail": ...}` shape). Sign in
 *   again; retrying the same request will never help.
 * - [Unreachable] - nothing was sent at all. Offline, wrong address, refused connection, a
 *   cleartext-policy block. Retrying later, or fixing the address, is the right action; this is
 *   also the ONLY branch a caller may queue a write behind, because it is the only one where the
 *   server has not seen the request and cannot have applied it - EXCEPT when `timedOut`, where the
 *   request may have arrived and a write must be treated as unknown, never queued or retried.
 * - [Refused] - the engine received the request, understood it, and said no in words
 *   ([body] is its own sentence, verbatim - the measured-tick refusal the server's own serializers
 *   emit reaches this app through here unaltered). Queuing a refused write would retry a
 *   rejection forever; the wording has to reach the user instead.
 * - [Malformed] - a 2xx whose body could not be decoded into the shape the caller asked for. Kept
 *   distinct from [Refused] because the engine did NOT refuse anything: the write may well have
 *   landed, so nothing here may report it as either applied or queued.
 *
 * **[Refused.status] is carried alongside [Refused.body], and the body is still verbatim.** The
 * status is not decoration - a caller may need "no such row" (404) told apart from "the engine
 * refused this write" (400), and a substring match against the server's English would be a far
 * worse way to answer that question than reading the status code the server actually sent.
 *
 * Ported from LEGION's `com.kevin.legion.backend.engine.EngineHttp` (the phone client's own
 * transport, `.claude/worktrees/openapi/app/src/main/java/com/kevin/legion/backend/engine/` in the
 * legion repo) onto OkHttp instead of Ktor - see this file's class doc for what changed and why.
 */
sealed interface EngineFailure {
    val sentence: String

    data class Unauthorized(override val sentence: String) : EngineFailure

    /**
     * [timedOut] marks a socket/call timeout. It is NOT "nothing was sent": a read timeout means
     * the request may well have reached the engine, so the wording (and a caller's retry policy)
     * must not claim otherwise. A read-side caller may retry; a write must not.
     */
    data class Unreachable(
        val host: String,
        val message: String,
        val timedOut: Boolean = false,
    ) : EngineFailure {
        override val sentence: String get() = message
    }

    data class Refused(val status: Int, val body: String) : EngineFailure {
        override val sentence: String get() = body
    }

    data class Malformed(override val sentence: String) : EngineFailure
}

/**
 * What every [EngineHttp] failure is wrapped in before it reaches a `Result.failure`. Owned by
 * this package, never a raw OkHttp exception escaping into a caller.
 */
class EngineHttpException(val failure: EngineFailure) : Exception(failure.sentence)

/**
 * A 2xx, with its status code kept rather than discarded. **The code is load-bearing on exactly
 * one path and it would be unrecoverable if thrown away here**: the engine's idempotent-create
 * endpoints (events, checklists) answer with **200 and the row that already existed** versus
 * **201 and a row they just made**, and both hand back an identical-looking body - so "did this
 * write actually create something" is a question only the status can answer. Every other caller
 * ignores [status] and reads [body].
 *
 * [headers] are the response headers with LOWERCASE names (last value wins). Only `/mcp` reads
 * them, for the `Mcp-Session-Id` a handshake-era MCP server mints on `initialize`.
 */
data class EngineOk(val status: Int, val body: String, val headers: Map<String, String> = emptyMap())

/**
 * One thin OkHttp wrapper around the Django engine's HTTP surface: attaches
 * `Authorization: Token <key>` from [EngineConfig], resolves a path against the saved base URL,
 * and turns every non-2xx into a typed [EngineFailure]. It knows nothing about events, checklists
 * or any other aspect - a per-aspect backend owns the JSON shapes and this owns the transport.
 *
 * **[client] is the test seam**, exactly as in [EngineAuth]: production gets the lazily-built real
 * client, a test passes an [OkHttpClient] built with a fake `Interceptor` (or backed by
 * `MockWebServer`) so no socket ever opens.
 *
 * **No `object` singleton** - a plain class taking [EngineConfig] as a constructor parameter, so
 * wiring it through DI later is a signature-compatible move rather than a rewrite (matching
 * LEGION's own Hilt-readiness note on the class this was ported from).
 *
 * **Network catches are [IOException], never a blanket `Exception`** - a bare `catch (e:
 * Exception)` inside a suspend function also swallows `kotlinx.coroutines.CancellationException`
 * and would report a caller's own cancellation as "the server is unreachable". [get]/[post]/
 * [patch]/[delete] go through [executeAsync], which uses `suspendCancellableCoroutine` around
 * `Call.enqueue` rather than `withContext(Dispatchers.IO) { call.execute() }` - the async path
 * runs on OkHttp's own dispatcher thread pool instead of borrowing one of `Dispatchers.IO`'s
 * threads for the duration of the call, and it lets a genuine coroutine cancellation cancel the
 * underlying [Call] (via `invokeOnCancellation`) instead of leaving it to complete unobserved.
 */
class EngineHttp(
    private val config: EngineConfig,
    private val client: OkHttpClient = defaultClient(),
) {

    /** True when this device has both an engine address and a stored token. Cheap and synchronous
     * (two SharedPreferences reads plus one decrypt), so a caller may use it as a pre-flight gate
     * before deciding to even build a backend. */
    fun isUsable(): Boolean = EngineConfig.isValidBaseUrl(config.baseUrl()) && config.isSignedIn()

    /**
     * `GET <base><path>?<query>`. [query] entries are attached through
     * [HttpUrl.Builder.addQueryParameter], never string-concatenated into [path] - **the `+`
     * footgun the server's own `api/changes.py` documents at length**: `api/sync.paginate_since`
     * renders its `next` cursor with Python's `isoformat()`, which emits a `+00:00` offset, and a
     * raw `+` in an un-percent-encoded query string decodes to a literal SPACE by the
     * `application/x-www-form-urlencoded` convention. `parse_since` would then fail to parse the
     * corrupted watermark and fall back to EPOCH - "fetch everything" instead of "fetch what
     * changed", silently. `addQueryParameter` percent-encodes `+` as `%2B`, which is precisely the
     * fix that server-side comment recommends ("a real HTTP client library encodes its own query
     * parameters and would never hit this"). See `EngineHttpQueryEncodingTest` for the proof.
     */
    suspend fun get(path: String, query: Map<String, String> = emptyMap()): Result<EngineOk> =
        send(path) { baseUrl ->
            val urlBuilder = (baseUrl + path).toHttpUrl().newBuilder()
            query.forEach { (key, value) -> urlBuilder.addQueryParameter(key, value) }
            Request.Builder().url(urlBuilder.build()).get().authorize().build()
        }

    /**
     * [extraHeaders] exist because `/mcp` routes on `Accept` and the MCP routing headers, which no
     * REST caller needs; every pre-existing caller passes none and is byte-identical to before.
     * Never used to carry `Authorization` - [authorize] owns that.
     */
    suspend fun post(
        path: String,
        jsonBody: String,
        extraHeaders: Map<String, String> = emptyMap(),
    ): Result<EngineOk> =
        send(path) { baseUrl ->
            val builder = Request.Builder()
                .url(baseUrl + path)
                .post(jsonBody.toRequestBody(JSON_MEDIA_TYPE))
                .authorize()
            extraHeaders.forEach { (name, value) -> builder.header(name, value) }
            builder.build()
        }

    /**
     * `POST <base><path>` as `multipart/form-data`: [fields] as form fields plus one file part.
     * Added 2026-10-15 for the drive-log upload (`diag/DriveLogUploader`), whose body is a gzip
     * file the engine's `devicelogs` view takes as a multipart FILE part (a raw non-file body
     * over 2.5 MB is refused by Django before any view runs; a file part is not). Goes through
     * [send] like every other verb, so the 2xx-only-is-success rule and the failure classes are
     * the same ones.
     */
    suspend fun postMultipart(
        path: String,
        fields: Map<String, String>,
        fileField: String,
        fileName: String,
        fileBytes: ByteArray,
        fileMimeType: String,
    ): Result<EngineOk> =
        send(path) { baseUrl ->
            val body = okhttp3.MultipartBody.Builder().setType(okhttp3.MultipartBody.FORM)
            fields.forEach { (name, value) -> body.addFormDataPart(name, value) }
            body.addFormDataPart(fileField, fileName, fileBytes.toRequestBody(fileMimeType.toMediaType()))
            Request.Builder().url(baseUrl + path).post(body.build()).authorize().build()
        }

    suspend fun patch(path: String, jsonBody: String): Result<EngineOk> =
        send(path) { baseUrl ->
            Request.Builder()
                .url(baseUrl + path)
                .patch(jsonBody.toRequestBody(JSON_MEDIA_TYPE))
                .authorize()
                .build()
        }

    /**
     * `PUT <base><path>`. The engine's identity-mint-on-the-client tables (memory, voice notes,
     * bodyweight/meal/sleep logs, workout plans) are all `PUT <table>/<identity>/` - idempotent
     * upsert, 200 whether the row was created or updated (see `openapi/legion-schema.yaml`'s own
     * doc comment on each of those paths). Added for the old hand-written engine tools (since replaced by
     * [com.kevin.midnightai.service.AskEngine]); every write [EngineHttp] supported before that (checklists, checklist items, events) is a
     * plain server-minted-id POST/PATCH instead, so this method had no caller until then.
     */
    suspend fun put(path: String, jsonBody: String): Result<EngineOk> =
        send(path) { baseUrl ->
            Request.Builder()
                .url(baseUrl + path)
                .put(jsonBody.toRequestBody(JSON_MEDIA_TYPE))
                .authorize()
                .build()
        }

    suspend fun delete(path: String): Result<EngineOk> =
        send(path) { baseUrl ->
            Request.Builder().url(baseUrl + path).delete().authorize().build()
        }

    private fun Request.Builder.authorize(): Request.Builder {
        val token = config.token()
        if (!token.isNullOrBlank()) header("Authorization", "Token $token")
        return this
    }

    /**
     * The one place a request's outcome becomes a [Result]. A 2xx hands back an [EngineOk] -
     * the status code plus the raw body text (empty for a 204, which every `DELETE` in this API
     * returns); everything else becomes an [EngineHttpException] carrying the branch of
     * [EngineFailure] that says what to do next.
     *
     * **The base-URL check comes first and reports [EngineFailure.Unreachable]**, not a made-up
     * refusal: an unconfigured device did not have its request rejected, it never sent one, and a
     * queued write behind this branch is exactly right (it will go out once an address is saved).
     */
    private suspend fun send(
        path: String,
        buildRequest: (baseUrl: String) -> Request,
    ): Result<EngineOk> {
        val baseUrl = config.baseUrl()
        if (!EngineConfig.isValidBaseUrl(baseUrl)) {
            return Result.failure(
                EngineHttpException(
                    EngineFailure.Unreachable(
                        host = "(no engine address)",
                        message = "No engine address saved on this device yet - nothing was sent.",
                    ),
                ),
            )
        }
        return try {
            classify(executeAsync(buildRequest(baseUrl)))
        } catch (e: IOException) {
            Result.failure(
                EngineHttpException(
                    EngineFailure.Unreachable(baseUrl, unreachableMessage(baseUrl, e), isTimeout(e)),
                ),
            )
        }
    }

    /**
     * Runs [request] on OkHttp's own dispatcher via `Call.enqueue`, suspending the caller without
     * blocking a thread. `invokeOnCancellation` cancels the underlying [Call] the moment the
     * coroutine itself is cancelled - `onFailure` then fires with an OkHttp-internal
     * `IOException("Canceled")`, which the `cont.isCancelled` guard below discards rather than
     * resuming with (resuming an already-cancelled `CancellableContinuation` is a documented no-op
     * in kotlinx.coroutines, so the guard is a comment more than a correctness requirement, but it
     * keeps the intent explicit rather than relying on that library detail silently doing the
     * right thing).
     */
    private suspend fun executeAsync(request: Request): Response = suspendCancellableCoroutine { cont ->
        val call = client.newCall(request)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isCancelled) return
                    cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    cont.resume(response)
                }
            },
        )
    }

    private fun classify(response: Response): Result<EngineOk> {
        val code = response.code
        val headers = response.headers.associate { (name, value) -> name.lowercase() to value }
        val body = response.use { r -> runCatching { r.body?.string() }.getOrNull() }.orEmpty()
        return when {
            code in SUCCESS_RANGE -> Result.success(EngineOk(code, body, headers))
            code == UNAUTHORIZED || code == FORBIDDEN -> Result.failure(
                EngineHttpException(
                    EngineFailure.Unauthorized(
                        "The engine no longer accepts this device's token${detailSuffix(body)} Sign in again.",
                    ),
                ),
            )
            code in CLIENT_ERROR_RANGE -> Result.failure(
                // The server's own sentence, verbatim and unwrapped - see EngineFailure.Refused's
                // own doc comment for why nothing here paraphrases it.
                EngineHttpException(EngineFailure.Refused(code, body)),
            )
            else -> Result.failure(
                // 5xx and anything else. Not Unreachable: the request DID reach the engine, so a
                // caller must not treat it as "nothing was sent" and silently queue it.
                EngineHttpException(
                    EngineFailure.Refused(code, body.ifBlank { "The engine failed on its side (HTTP $code)." }),
                ),
            )
        }
    }

    private fun detailSuffix(body: String): String = if (body.isBlank()) "." else ": $body."

    companion object {
        private val SUCCESS_RANGE = 200..299
        private val CLIENT_ERROR_RANGE = 400..499
        private const val UNAUTHORIZED = 401
        private const val FORBIDDEN = 403
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        /**
         * ONE OkHttp client for the whole process, not one per [EngineHttp]. An `OkHttpClient`
         * owns a connection pool and a dispatcher thread pool - building one per request would
         * leak both. `by lazy` so a process that never talks to an engine never creates it at all.
         *
         * [EngineAuth] deliberately keeps its own separate client: it is constructed once per a
         * setup/login screen, its lifetime is a screen rather than the process, and entangling the
         * two would mean a login attempt and a background sync share a pool for no benefit - the
         * same split LEGION's own `EngineHttp`/`EngineAuth` doc comments state.
         */
        private val sharedClient: OkHttpClient by lazy { buildClient() }

        /**
         * The engine is a Cloud Run service that scales to zero, and a Django cold start can run
         * well past OkHttp's default 10s, which surfaced as "unreachable" on a perfectly healthy
         * engine. Read gets 30s because the first byte of a cold start is what we are waiting
         * for; connect/write stay 15s (TLS to Google's edge is fast, a body is a few KB).
         * No `callTimeout`: per-read is the right bound and the callers own the overall budget -
         * `LiveSessionController.ENGINE_TOOL_TIMEOUT_MS` outer, `AskEngine`'s 20s per-tool bound for the
         * (already warm, `tools/list` having woken the instance) calls inside the agent loop.
         */
        internal fun buildClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_S, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_S, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT_S, java.util.concurrent.TimeUnit.SECONDS)
            .build()

        internal const val CONNECT_TIMEOUT_S = 15L
        internal const val READ_TIMEOUT_S = 30L
        internal const val WRITE_TIMEOUT_S = 15L

        private fun defaultClient(): OkHttpClient = sharedClient
    }
}

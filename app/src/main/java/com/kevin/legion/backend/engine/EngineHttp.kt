package com.kevin.legion.backend.engine

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import java.io.IOException

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
 *   server has not seen the request and cannot have applied it.
 * - [Refused] - the engine received the request, understood it, and said no in words
 *   ([body] is its own sentence, verbatim - the measured-tick refusal
 *   `checklists/serializers.py` copies from `ChecklistController.tick` reaches a phone screen
 *   through here unaltered). Queuing a refused write would retry a rejection forever; the wording
 *   has to reach the user instead.
 * - [Malformed] - a 2xx whose body could not be decoded into the shape the caller asked for. Kept
 *   distinct from [Refused] because the engine did NOT refuse anything: the write may well have
 *   landed, so nothing here may report it as either applied or queued.
 *
 * **[Refused.status] is carried alongside [Refused.body], and the body is still verbatim.** The
 * status is not decoration: `DjangoEventsBackend.softDelete` has to tell "no such row" (404) from
 * "the engine refused this write" (400) to honour [com.kevin.legion.backend.EventsBackend.softDelete]'s
 * own `Result.success(false)` contract, and a substring match against the server's English would
 * be a far worse way to answer that question than reading the status code the server actually sent.
 */
sealed interface EngineFailure {
    val sentence: String

    data class Unauthorized(override val sentence: String) : EngineFailure

    data class Unreachable(val host: String, val message: String) : EngineFailure {
        override val sentence: String get() = message
    }

    data class Refused(val status: Int, val body: String) : EngineFailure {
        override val sentence: String get() = body
    }

    data class Malformed(override val sentence: String) : EngineFailure
}

/**
 * What every [EngineHttp] failure is wrapped in before it reaches a `Result.failure`. Owned by
 * this package, never a raw Ktor/OkHttp exception escaping into an aspect's backend - the same
 * posture [com.kevin.legion.backend.EventsBackendException] and
 * [com.kevin.legion.backend.LastAspectsBackendException] already take for the Supabase side.
 *
 * [failure] is the structured reason; [message] is the same thing already rendered into a sentence,
 * so a caller that only logs (every `MidnightEvents` breadcrumb) needs no `when` at all, and a
 * caller that must BEHAVE differently (queue vs. surface) reads [failure].
 */
class EngineHttpException(val failure: EngineFailure) : Exception(failure.sentence)

/**
 * A 2xx, with its status code kept rather than discarded. **The code is load-bearing on exactly
 * one path and it would be unrecoverable if thrown away here**:
 * `EventListCreateView.post`/`ChecklistListCreateView.post` answer an idempotent create with
 * **200 and the row that already existed** versus **201 and a row they just made**, and both hand
 * back an identical-looking body - so "did this write actually create something" is a question
 * only the status can answer. Every other caller ignores [status] and reads [body].
 */
data class EngineOk(val status: Int, val body: String)

/**
 * One thin Ktor wrapper around the Django engine's HTTP surface: attaches
 * `Authorization: Token <key>` from [EngineConfig], resolves a path against the saved base URL,
 * and turns every non-2xx into a typed [EngineFailure]. It knows nothing about events, checklists
 * or any other aspect - `DjangoEventsBackend`/`DjangoChecklistsBackend` own the JSON shapes and
 * this owns the transport.
 *
 * **[client] is the test seam**, exactly as in [EngineAuth]: production gets the lazily-built real
 * client, a test passes an [HttpClient] built on [io.ktor.client.engine.mock.MockEngine] so no
 * socket ever opens.
 *
 * **No `object` singleton** - a plain class taking [EngineConfig] as a constructor parameter,
 * matching [EngineConfig]/[EngineAuth]/[EngineTransport]'s own Hilt-readiness note.
 *
 * **Network catches are [IOException], never a blanket `Exception`** - the identical reasoning
 * [EngineAuth]'s own class doc spells out: a bare `catch (e: Exception)` inside a suspend function
 * also swallows `kotlinx.coroutines.CancellationException` and would report a caller's own
 * cancellation as "the server is unreachable".
 */
class EngineHttp(
    private val config: EngineConfig,
    private val client: HttpClient = defaultClient(),
) {

    /** True when this device has both an engine address and a stored token. Cheap and synchronous
     * (two SharedPreferences reads plus one decrypt), so a caller may use it as a pre-flight gate
     * before deciding to even build a backend - see [EngineBackends]. */
    fun isUsable(): Boolean = EngineConfig.isValidBaseUrl(config.baseUrl()) && config.isSignedIn()

    /**
     * `GET <base><path>?<query>`. [query] entries are attached through Ktor's own
     * `parameter(...)`, never string-concatenated into [path] - **the `+` footgun the server's own
     * `api/changes.py` documents at length**: `api/sync.paginate_since` renders its `next` cursor
     * with Python's `isoformat()`, which emits a `+00:00` offset, and a raw `+` in an
     * un-percent-encoded query string decodes to a literal SPACE by the
     * `application/x-www-form-urlencoded` convention. `parse_since` would then fail to parse the
     * corrupted watermark and fall back to EPOCH - "fetch everything" instead of "fetch what
     * changed", silently. Ktor's parameter encoding escapes `+` as `%2B`, which is precisely the
     * fix that server-side comment recommends ("a real HTTP client library encodes its own query
     * parameters and would never hit this").
     */
    suspend fun get(path: String, query: Map<String, String> = emptyMap()): Result<EngineOk> =
        send(path) { url ->
            client.get(url) {
                authorize()
                query.forEach { (key, value) -> parameter(key, value) }
            }
        }

    suspend fun post(path: String, jsonBody: String): Result<EngineOk> =
        send(path) { url ->
            client.post(url) {
                authorize()
                contentType(ContentType.Application.Json)
                setBody(jsonBody)
            }
        }

    suspend fun patch(path: String, jsonBody: String): Result<EngineOk> =
        send(path) { url ->
            client.patch(url) {
                authorize()
                contentType(ContentType.Application.Json)
                setBody(jsonBody)
            }
        }

    /**
     * `PUT <path>` - the upsert verb of `server/api/synced.py`'s generic shape, added for the
     * Phase 5 aspects (places, voice notes, body, memory).
     *
     * **Distinct from [patch], not a synonym for it.** The Phase 2 slice patches: `PATCH
     * /api/events/<id>` is DRF's `partial=True` update of a row that must already exist. This is
     * an upsert whose identity comes from the URL, so the same request creates the row when it is
     * absent and replaces it when it is present, and answers 200 either way - which is what makes
     * a queued write safe to drain twice.
     */
    suspend fun put(path: String, jsonBody: String): Result<EngineOk> =
        send(path) { url ->
            client.put(url) {
                authorize()
                contentType(ContentType.Application.Json)
                setBody(jsonBody)
            }
        }

    suspend fun delete(path: String): Result<EngineOk> =
        send(path) { url ->
            client.delete(url) { authorize() }
        }

    private fun io.ktor.client.request.HttpRequestBuilder.authorize() {
        val token = config.token()
        if (!token.isNullOrBlank()) header("Authorization", "Token $token")
    }

    /**
     * The one place a request's outcome becomes a [Result]. A 2xx hands back an [EngineOk] -
     * the status code plus the raw body text (empty for a 204, which every `DELETE` in this API
     * returns); everything else becomes
     * an [EngineHttpException] carrying the branch of [EngineFailure] that says what to do next.
     *
     * **The base-URL check comes first and reports [EngineFailure.Unreachable]**, not a made-up
     * refusal: an unconfigured device did not have its request rejected, it never sent one, and a
     * queued write behind this branch is exactly right (it will go out once an address is saved).
     */
    private suspend fun send(
        path: String,
        request: suspend (String) -> HttpResponse,
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
            classify(request(baseUrl + path))
        } catch (e: IOException) {
            Result.failure(
                EngineHttpException(EngineFailure.Unreachable(baseUrl, unreachableMessage(baseUrl, e))),
            )
        }
    }

    private suspend fun classify(response: HttpResponse): Result<EngineOk> {
        val code = response.status.value
        val body = runCatching { response.bodyAsText() }.getOrDefault("")
        return when {
            code in SUCCESS_RANGE -> Result.success(EngineOk(code, body))
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

        /**
         * ONE Ktor/OkHttp client for the whole process, not one per [EngineHttp].
         * [EngineBackends] builds an [EngineHttp] per call (see its own comment for why it does
         * not cache one), and an `HttpClient` owns a connection pool and a dispatcher thread pool -
         * building one per request would leak both. `by lazy` so a process that never talks to an
         * engine never creates it at all.
         *
         * [EngineAuth] deliberately keeps its own separate client: it is constructed once per Setup
         * screen, its lifetime is a screen rather than the process, and entangling the two would
         * mean a login attempt and a background sync share a pool for no benefit.
         */
        private val sharedClient: HttpClient by lazy { HttpClient(OkHttp.create()) }

        private fun defaultClient(): HttpClient = sharedClient
    }
}

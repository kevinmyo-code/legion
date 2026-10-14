package com.kevin.midnightai.backend.engine

import com.kevin.midnightai.backend.engine.api.model.LoginRequest
import com.kevin.midnightai.backend.engine.api.model.LoginResponse
import com.kevin.midnightai.backend.engine.api.model.MeResponse
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * Outcome of [EngineAuth.login]. Every branch says in words what did not happen - CLAUDE.md
 * section 7's outcome-verb rule applied to a login attempt rather than a spoken assistant turn:
 * "signed in" is an outcome verb, so it may follow only a request that actually came back 200
 * with a token this process actually stored.
 */
sealed interface LoginResult {
    /** Django accepted the credentials, minted a token, and [EngineConfig.saveSession] wrote it -
     * see that function's own fail-closed doc for the one way this can still not have happened. */
    data class Ok(val userId: String) : LoginResult

    /** Django rejected the request outright (bad credentials, a malformed body) OR accepted it but
     * the token could not be saved to this device (Keystore failure) - either way, no usable
     * session exists on this device after this result. */
    data class Refused(val message: String) : LoginResult

    /** The request never reached the engine at all - offline, wrong address, DNS failure, refused
     * connection. Retrying later or fixing the address is the right next action, not retyping the
     * password. */
    data class Unreachable(val message: String) : LoginResult
}

/**
 * Outcome of [EngineAuth.me] - this app's own "am I still signed in" check, mirroring the
 * server's `household/views.py` MeView doc comment: if this call succeeds, the calling token is
 * live and its user is a household member; if it 401s or 403s, the app knows to ask for a new
 * token.
 */
sealed interface MeResult {
    data class Ok(val userId: String, val email: String, val deviceName: String) : MeResult

    /** No token on this device, or the engine rejected the one stored (revoked, expired, wrong
     * key) - `household/authentication.py`'s DeviceTokenAuthentication returns 401 for all three,
     * and DRF returns 403 IsHouseholdMember refusals in the same {"detail": ...} shape, so both
     * collapse to this one branch rather than needing a caller to distinguish them. Other HTTP
     * errors (5xx, 429) are NOT this: they are [Unreachable]. */
    data class Refused(val message: String) : MeResult

    /** The request never reached the engine at all. */
    data class Unreachable(val message: String) : MeResult
}

/**
 * DRF's standard error shape for both LoginView's explicit 401 and every authentication/
 * permission failure DRF's own exception handler produces ({"detail": "..."}). Not part of the
 * generated model package (`legion-schema.yaml`'s `DetailError` schema covers the same shape, but
 * this stays hand-written rather than pulling in the generated type purely to read one optional
 * field off an error body).
 */
@Serializable
private data class ErrorBody(val detail: String? = null)

private val engineJson = Json { ignoreUnknownKeys = true }

/** `SocketTimeoutException` (connect/read/write) and OkHttp's call-timeout `InterruptedIOException`
 * share this supertype. A coroutine cancel arrives as a plain `IOException("Canceled")`, so it is
 * not mistaken for a timeout. */
internal fun isTimeout(e: IOException): Boolean = e is java.io.InterruptedIOException

/**
 * Shared by [EngineAuth.login], [EngineAuth.me] and [EngineHttp]'s `catch (e: IOException)`
 * branches. Section 7's outcome-verb rule read backwards: a failure result must say in words what
 * did NOT happen and, where there is one, the way around it - not just relay whatever string
 * OkHttp happened to throw.
 *
 * OkHttp's own cleartext guard (`okhttp3.internal.connection.RealConnection`, backed by
 * `NetworkSecurityPolicy.isCleartextTrafficPermitted`) throws an [IOException] whose message
 * contains "CLEARTEXT" whenever the current build's network security config blocks plain HTTP to
 * the given host - release builds keep Android's API 28+ default of blocking it outright, and a
 * debug build with no `usesCleartextTraffic`/`networkSecurityConfig` opt-in hits the same wall.
 * The raw message ("CLEARTEXT communication to 192.168.1.117 not permitted by network security
 * policy") names the OS mechanism, not the fix; a driver reading it on the head unit has no way to
 * know a debug build (or an https address) would work. This branch replaces it with the actual
 * next action instead of relaying the OS's own wording.
 *
 * Ported from LEGION's `com.kevin.legion.backend.engine.unreachableMessage` (identical reasoning,
 * identical wall - same OkHttp engine talking to the same laptop-hosted engine address).
 * `internal`, matching LEGION's own widening note: shared with [EngineHttp] rather than
 * duplicated.
 */
internal fun unreachableMessage(baseUrl: String, e: IOException): String {
    val raw = e.message ?: "unknown error"
    return if (isTimeout(e)) {
        // Deliberately does NOT say "nothing was sent": a read timeout means the request may have
        // arrived. A Cloud Run engine that scaled to zero is the usual cause, hence "waking up".
        "The engine at $baseUrl took too long to answer; it may be waking up, and the request " +
            "may have reached it. Try again in a few seconds."
    } else if (raw.contains("CLEARTEXT")) {
        "This app blocks plain http; use the engine's https address. " +
            "Nothing was sent."
    } else {
        "Could not reach $baseUrl, nothing was sent: $raw"
    }
}

/**
 * Talks to the Django engine's three auth endpoints - `POST /api/auth/login`,
 * `GET /api/auth/me`, `POST /api/auth/logout` (`openapi/legion-schema.yaml`). This is a bare
 * [OkHttpClient], not the shared one [EngineHttp] uses - see [defaultClient]'s doc for why they
 * stay separate.
 *
 * **[client] is the test seam.** Production code gets the lazily-built real client
 * ([defaultClient]); a test passes an [OkHttpClient] built with a fake `Interceptor` (or backed by
 * `MockWebServer`) so no real socket ever opens.
 *
 * **No `object` singleton, deliberately** - a plain class taking its collaborators ([EngineConfig],
 * optionally a test [client]) as constructor parameters, matching [EngineConfig]/[EngineHttp].
 *
 * **Network catches are [IOException], not blanket `Exception`** (login/me): a bare
 * `catch (e: Exception)` inside a suspend function also catches
 * `kotlinx.coroutines.CancellationException` (it extends `IllegalStateException` extends
 * `RuntimeException` extends `Exception`), which would swallow a caller's own coroutine
 * cancellation and report it as "unreachable" instead of letting it propagate. [IOException]
 * covers the real connectivity failures (`UnknownHostException`, `ConnectException`,
 * `SocketTimeoutException`, `SSLException` are all subtypes) without that hazard.
 *
 * Ported from LEGION's `com.kevin.legion.backend.engine.EngineAuth` onto OkHttp instead of Ktor;
 * see [EngineHttp]'s class doc for the transport-level notes shared with this file.
 */
class EngineAuth(
    private val config: EngineConfig,
    private val client: OkHttpClient = defaultClient(),
) {

    /**
     * Signs in with [email]/[password]/[deviceName]. See [LoginResult] for what each branch means
     * and what the driver should do next.
     */
    suspend fun login(email: String, password: String, deviceName: String): LoginResult {
        val baseUrl = config.baseUrl()
        if (!EngineConfig.isValidBaseUrl(baseUrl)) {
            return LoginResult.Refused("No engine address saved yet - set one above first.")
        }
        return try {
            val requestJson = engineJson.encodeToString(
                LoginRequest.serializer(),
                LoginRequest(email.trim(), password, deviceName.trim()),
            )
            val request = Request.Builder()
                .url(baseUrl + "/api/auth/login")
                .post(requestJson.toRequestBody(JSON_MEDIA_TYPE))
                .build()
            handleLoginResponse(baseUrl, executeAsync(request))
        } catch (e: IOException) {
            // Never reached the engine at all - offline, wrong address, refused connection.
            // Section 7: this must say in words that nothing was sent, not merely that it failed.
            LoginResult.Unreachable(unreachableMessage(baseUrl, e))
        }
    }

    private fun handleLoginResponse(baseUrl: String, response: Response): LoginResult {
        val body = response.use { r -> runCatching { r.body?.string() }.getOrNull() }.orEmpty()
        if (!response.isSuccessful) {
            // Any non-200 (401 from LoginView's own explicit refusal, or anything else DRF
            // returns in its standard {"detail": ...} shape) - no token was minted, matching
            // LoginView's own comment: "a failed login must never look like a successful one."
            val detail = decodeOrNull(body, ErrorBody.serializer())?.detail
            return LoginResult.Refused(
                "The engine refused these credentials, no token was stored" +
                    (detail?.let { ": $it" } ?: "."),
            )
        }
        val decoded = decodeOrNull(body, LoginResponse.serializer())
        return when {
            decoded == null -> LoginResult.Unreachable(
                "The engine's reply from $baseUrl didn't look like a login response - nothing was stored.",
            )
            config.saveSession(decoded.token, decoded.userId) -> LoginResult.Ok(decoded.userId)
            else -> {
                // EngineConfig.saveSession's own fail-closed branch: Django DID accept the
                // credentials, but this device's Keystore refused to encrypt the token, so
                // nothing was written - the outcome-verb rule (section 7) forbids reporting this
                // as "signed in".
                LoginResult.Refused(
                    "Signed in, but the token could not be saved to this device - nothing was " +
                        "stored. Try again once this device's storage is working.",
                )
            }
        }
    }

    /** This app's own membership check - see [MeResult]. */
    suspend fun me(): MeResult {
        val baseUrl = config.baseUrl()
        val token = config.token()
        if (!EngineConfig.isValidBaseUrl(baseUrl) || token.isNullOrBlank()) {
            return MeResult.Refused("Not signed in on this device.")
        }
        return try {
            val request = Request.Builder()
                .url(baseUrl + "/api/auth/me")
                .header("Authorization", "Token $token")
                .get()
                .build()
            handleMeResponse(baseUrl, executeAsync(request))
        } catch (e: IOException) {
            MeResult.Unreachable(unreachableMessage(baseUrl, e))
        }
    }

    private fun handleMeResponse(baseUrl: String, response: Response): MeResult {
        val body = response.use { r -> runCatching { r.body?.string() }.getOrNull() }.orEmpty()
        return meResultFor(baseUrl, response.code, body)
    }

    /**
     * Best-effort remote revoke, then an unconditional local clear: a driver stuck offline must
     * still be able to get back to a clean "signed out" state on THIS device, even though the
     * token technically stays live on the server until it is reached, and even a non-network
     * failure here must never block that local clear.
     */
    suspend fun logout() {
        val baseUrl = config.baseUrl()
        val token = config.token()
        if (EngineConfig.isValidBaseUrl(baseUrl) && !token.isNullOrBlank()) {
            try {
                val request = Request.Builder()
                    .url(baseUrl + "/api/auth/logout")
                    .header("Authorization", "Token $token")
                    .post(ByteArray(0).toRequestBody(null))
                    .build()
                executeAsync(request).close()
            } catch (ignored: Exception) {
                // Remote revoke failing (offline, already revoked, or anything else) must not
                // block the local session clear below.
            }
        }
        config.clearSession()
    }

    /** Runs [request] on OkHttp's own dispatcher via `Call.enqueue` - same shape as
     * [EngineHttp.executeAsync], duplicated rather than shared because this class deliberately
     * keeps its own [client] (see [defaultClient]'s doc). */
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

    companion object {
        private const val UNAUTHORIZED = 401
        private const val FORBIDDEN = 403

    /**
     * Pure classification of a `/api/auth/me` reply. ONLY 401 and 403 mean the engine refused this
     * token (`DeviceTokenAuthentication` 401s a revoked/expired/wrong token; `IsHouseholdMember`
     * 403s a non-member). Any other non-2xx (a 502 from Cloud Run mid-cold-start, a 500, a 429) says
     * nothing about the token, so it is [MeResult.Unreachable]: telling a driver "sign in again"
     * because the engine hiccupped would be a false alarm that costs them a password entry.
     */
    internal fun meResultFor(baseUrl: String, code: Int, body: String): MeResult {
        if (code !in 200..299) {
            if (code == UNAUTHORIZED || code == FORBIDDEN) {
                val detail = decodeOrNull(body, ErrorBody.serializer())?.detail
                return MeResult.Refused(
                    "The engine no longer accepts this device's token" +
                        (detail?.let { ": $it" } ?: ".") + " Sign in again.",
                )
            }
            return MeResult.Unreachable("The engine at $baseUrl answered HTTP $code - try again shortly.")
        }
        val decoded = decodeOrNull(body, MeResponse.serializer())
        return if (decoded != null) {
            MeResult.Ok(decoded.userId, decoded.email, decoded.deviceName)
        } else {
            MeResult.Unreachable("The engine's reply from $baseUrl didn't look like a membership response.")
        }
    }

    /** Any decode failure - malformed JSON, an unexpected shape, an empty body - degrades to
     * null rather than throwing; every caller already has its own "didn't look like a ... response"
     * message for exactly this case. */
    private fun <T> decodeOrNull(body: String, serializer: kotlinx.serialization.KSerializer<T>): T? = try {
        engineJson.decodeFromString(serializer, body)
    } catch (ignored: Exception) {
        null
    }

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        // Same cold-start timeouts as EngineHttp: sign-in / "who am I" against a scaled-to-zero
        // Cloud Run engine hit the 10s default too. Still a separate instance (own pool).
        private fun defaultClient(): OkHttpClient = EngineHttp.buildClient()
    }
}

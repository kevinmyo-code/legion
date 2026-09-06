package com.kevin.legion.backend.engine

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import java.io.IOException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

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
 * Outcome of [EngineAuth.me] - the phone's own "am I still signed in" check, mirroring
 * household/views.py's MeView doc comment: if this call succeeds, the calling token is live and
 * its user is a household member; if it 401s or 403s, the phone knows to ask for a new token.
 */
sealed interface MeResult {
    data class Ok(val userId: String, val email: String, val deviceName: String) : MeResult

    /** No token on this device, or the engine rejected the one stored (revoked, expired, wrong
     * key) - household/authentication.py's DeviceTokenAuthentication returns 401 for all three,
     * and DRF returns 403 IsHouseholdMember refusals in the same {"detail": ...} shape, so both
     * collapse to this one branch rather than needing a caller to distinguish them. */
    data class Refused(val message: String) : MeResult

    /** The request never reached the engine at all. */
    data class Unreachable(val message: String) : MeResult
}

/**
 * Wire-format DTOs. Property names are camelCase (Kotlin convention, and detekt's
 * ConstructorParameterNaming) with [SerialName] bridging to Django's own snake_case JSON keys
 * (`server/household/serializers.py`) rather than using snake_case property names directly.
 */
@Serializable
private data class LoginRequestBody(
    val email: String,
    val password: String,
    @SerialName("device_name") val deviceName: String,
)

@Serializable
private data class LoginResponseBody(
    val token: String,
    @SerialName("user_id") val userId: String,
)

@Serializable
private data class MeResponseBody(
    @SerialName("user_id") val userId: String,
    val email: String,
    @SerialName("device_name") val deviceName: String,
)

/**
 * DRF's standard error shape for both LoginView's explicit 401 and every authentication/
 * permission failure DRF's own exception handler produces ({"detail": "..."}) - see
 * household/views.py and household/authentication.py's exceptions.AuthenticationFailed messages.
 */
@Serializable
private data class ErrorBody(val detail: String? = null)

private val engineJson = Json { ignoreUnknownKeys = true }

/**
 * Shared by [EngineAuth.login] and [EngineAuth.me]'s `catch (e: IOException)` branches. Section
 * 7's outcome-verb rule read backwards: a failure result must say in words what did NOT happen
 * and, where there is one, the way around it - not just relay whatever string OkHttp happened to
 * throw.
 *
 * OkHttp's own cleartext guard (`okhttp3.internal.connection.RealConnection`, backed by
 * `NetworkSecurityPolicy.isCleartextTrafficPermitted`) throws an [IOException] whose message
 * contains "CLEARTEXT" whenever the current build's network security config blocks plain HTTP to
 * the given host - release builds keep Android's API 28+ default of blocking it outright, and a
 * debug build with no `usesCleartextTraffic`/`networkSecurityConfig` opt-in hits the same wall.
 * The raw message ("CLEARTEXT communication to 192.168.1.117 not permitted by network security
 * policy") names the OS mechanism, not the fix; a Kevin reading it on the A25 has no way to know a
 * debug build would work. This branch replaces it with the actual next action instead of relaying
 * the OS's own wording.
 */
private fun unreachableMessage(baseUrl: String, e: IOException): String {
    val raw = e.message ?: "unknown error"
    return if (raw.contains("CLEARTEXT")) {
        "This build blocks plain http; use https, or a debug build for a laptop engine. " +
            "Nothing was sent."
    } else {
        "Could not reach $baseUrl, nothing was sent: $raw"
    }
}

/**
 * Talks to the Django engine's three auth endpoints - server/household/urls.py:
 * POST /api/auth/login, GET /api/auth/me, POST /api/auth/logout - over whatever Ktor already
 * brings in via supabase-kt (ADR 0044's build note: "no new HTTP stack"). This is a bare
 * [HttpClient] on the same OkHttp engine [com.kevin.legion.backend.SupabaseClientProvider]
 * already uses, not the Supabase-specific client - Django is not Supabase and has no anon key or
 * session-manager machinery to hang this off.
 *
 * **[client] is the test seam.** Production code gets the lazily-built real client
 * ([defaultClient]); a test passes an [HttpClient] built on
 * [io.ktor.client.engine.mock.MockEngine] so no real socket ever opens - the same posture
 * EventsRealtimeFetchTest's clientBacked helper uses for the Supabase client, generalised to a
 * plain client this class owns outright.
 *
 * **No `object` singleton, deliberately** - same Hilt-readiness note as [EngineConfig]: a plain
 * class taking its collaborators ([EngineConfig], optionally a test [client]) as constructor
 * parameters.
 *
 * **Network catches are [IOException], not blanket `Exception`** (login/me): narrower than the
 * codebase's SupabaseAuth.signIn catch-all, and deliberately so - a bare `catch (e: Exception)`
 * inside a suspend function also catches `kotlinx.coroutines.CancellationException` (it extends
 * `IllegalStateException` extends `RuntimeException` extends `Exception`), which would swallow a
 * caller's own coroutine cancellation and report it as "unreachable" instead of letting it
 * propagate. [IOException] covers the real connectivity failures (`UnknownHostException`,
 * `ConnectException`, `SocketTimeoutException`, `SSLException` are all subtypes) without that
 * hazard.
 */
class EngineAuth(
    private val config: EngineConfig,
    private val client: HttpClient = defaultClient(),
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
            val response = client.post(baseUrl + "/api/auth/login") {
                contentType(ContentType.Application.Json)
                setBody(
                    engineJson.encodeToString(
                        LoginRequestBody.serializer(),
                        LoginRequestBody(email.trim(), password, deviceName.trim()),
                    ),
                )
            }
            handleLoginResponse(baseUrl, response)
        } catch (e: IOException) {
            // Never reached the engine at all - offline, wrong address, refused connection.
            // Section 7: this must say in words that nothing was sent, not merely that it failed.
            LoginResult.Unreachable(unreachableMessage(baseUrl, e))
        }
    }

    private suspend fun handleLoginResponse(baseUrl: String, response: HttpResponse): LoginResult {
        if (response.status != HttpStatusCode.OK) {
            // Any non-200 (401 from LoginView's own explicit refusal, or anything else DRF
            // returns in its standard {"detail": ...} shape) - no token was minted, matching
            // LoginView's own comment: "a failed login must never look like a successful one."
            val detail = decodeOrNull(response, ErrorBody.serializer())?.detail
            return LoginResult.Refused(
                "The engine refused these credentials, no token was stored" +
                    (detail?.let { ": $it" } ?: "."),
            )
        }
        val body = decodeOrNull(response, LoginResponseBody.serializer())
        return when {
            body == null -> LoginResult.Unreachable(
                "The engine's reply from $baseUrl didn't look like a login response - nothing was stored.",
            )
            config.saveSession(body.token, body.userId) -> LoginResult.Ok(body.userId)
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

    /** The phone's own membership check - see [MeResult]. */
    suspend fun me(): MeResult {
        val baseUrl = config.baseUrl()
        val token = config.token()
        if (!EngineConfig.isValidBaseUrl(baseUrl) || token.isNullOrBlank()) {
            return MeResult.Refused("Not signed in on this device.")
        }
        return try {
            val response = client.get(baseUrl + "/api/auth/me") {
                header("Authorization", "Token $token")
            }
            handleMeResponse(baseUrl, response)
        } catch (e: IOException) {
            MeResult.Unreachable(unreachableMessage(baseUrl, e))
        }
    }

    private suspend fun handleMeResponse(baseUrl: String, response: HttpResponse): MeResult {
        if (response.status != HttpStatusCode.OK) {
            val detail = decodeOrNull(response, ErrorBody.serializer())?.detail
            return MeResult.Refused(
                "The engine no longer accepts this device's token" +
                    (detail?.let { ": $it" } ?: ".") + " Sign in again.",
            )
        }
        val body = decodeOrNull(response, MeResponseBody.serializer())
        return if (body != null) {
            MeResult.Ok(body.userId, body.email, body.deviceName)
        } else {
            MeResult.Unreachable("The engine's reply from $baseUrl didn't look like a membership response.")
        }
    }

    /**
     * Best-effort remote revoke, then an unconditional local clear - matching
     * [com.kevin.legion.backend.SupabaseAuth.signOut]'s posture exactly, including its catch-all
     * breadth: a driver stuck offline must still be able to get back to a clean "signed out"
     * state on THIS device, even though the token technically stays live on the server until it
     * is reached, and even a non-network failure here must never block that local clear.
     */
    suspend fun logout() {
        val baseUrl = config.baseUrl()
        val token = config.token()
        if (EngineConfig.isValidBaseUrl(baseUrl) && !token.isNullOrBlank()) {
            try {
                client.post(baseUrl + "/api/auth/logout") {
                    header("Authorization", "Token $token")
                }
            } catch (ignored: Exception) {
                // Remote revoke failing (offline, already revoked, or anything else) must not
                // block the local session clear below - see the function doc's cross-reference to
                // SupabaseAuth.signOut, which takes the identical breadth for the identical reason.
            }
        }
        config.clearSession()
    }

    /** Any decode failure - malformed JSON, an unexpected shape, a body read failure - degrades to
     * null rather than throwing; every caller already has its own "didn't look like a ... response"
     * message for exactly this case. */
    private suspend fun <T> decodeOrNull(response: HttpResponse, serializer: KSerializer<T>): T? = try {
        engineJson.decodeFromString(serializer, response.bodyAsText())
    } catch (ignored: Exception) {
        null
    }

    companion object {
        private fun defaultClient(): HttpClient = HttpClient(OkHttp.create())
    }
}

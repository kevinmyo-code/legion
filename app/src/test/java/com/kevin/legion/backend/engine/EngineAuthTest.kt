package com.kevin.legion.backend.engine

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * [EngineAuth] against the real Django contract (`server/household/views.py`,
 * `serializers.py`), with a [MockEngine] standing in for the network transport only - the same
 * posture `EventsRealtimeFetchTest` uses for the Supabase client, generalised here to the bare
 * client [EngineAuth] owns. Every branch is checked against what it actually wrote to
 * [EngineConfig], not just the return value, per CLAUDE.md section 7: a result that CLAIMS nothing
 * was stored must be checked against storage, not taken on its own word.
 *
 * Robolectric only for [EngineConfig]'s real [android.content.Context] (SharedPreferences) - no
 * Keystore path is exercised; encrypt/decrypt are the trivial reversible fakes
 * [EngineConfigTest] also uses.
 */
@RunWith(RobolectricTestRunner::class)
class EngineAuthTest {

    private val context = RuntimeEnvironment.getApplication()
    private val baseUrl = "http://192.168.1.20:8000"

    private fun configWithBaseUrl(): EngineConfig {
        val config = EngineConfig(
            context = context,
            encrypt = { plain -> "ENC($plain)" },
            decrypt = { blob -> blob.removePrefix("ENC(").removeSuffix(")") },
        )
        config.saveBaseUrl(baseUrl)
        return config
    }

    private fun clientRespondingWith(
        status: HttpStatusCode,
        body: String,
    ): HttpClient {
        val engine = MockEngine { _ ->
            respond(
                content = body,
                status = status,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        return HttpClient(engine)
    }

    private fun clientThatNeverConnects(): HttpClient {
        val engine = MockEngine { _ -> throw IOException("connection refused") }
        return HttpClient(engine)
    }

    @Test
    fun `login 200 stores the token and reports the user id`() = runBlocking {
        val config = configWithBaseUrl()
        val client = clientRespondingWith(
            HttpStatusCode.OK,
            """{"token": "raw-device-token", "user_id": "11111111-1111-1111-1111-111111111111"}""",
        )
        val auth = EngineAuth(config, client)

        val result = auth.login("kevin@example.com", "correct horse", "Pixel 9")

        assertTrue(result is LoginResult.Ok)
        assertEquals("11111111-1111-1111-1111-111111111111", (result as LoginResult.Ok).userId)
        assertEquals("raw-device-token", config.token())
        assertEquals("11111111-1111-1111-1111-111111111111", config.userId())
        assertTrue(config.isSignedIn())
    }

    @Test
    fun `login 401 stores nothing and returns the exact refusal sentence`() = runBlocking {
        val config = configWithBaseUrl()
        val client = clientRespondingWith(
            HttpStatusCode.Unauthorized,
            """{"detail": "Invalid email or password."}""",
        )
        val auth = EngineAuth(config, client)

        val result = auth.login("kevin@example.com", "wrong password", "Pixel 9")

        assertTrue(result is LoginResult.Refused)
        assertEquals(
            "The engine refused these credentials, no token was stored: Invalid email or password.",
            (result as LoginResult.Refused).message,
        )
        assertNull("a 401 must never leave a token behind", config.token())
        assertNull(config.userId())
        assertFalse(config.isSignedIn())
    }

    @Test
    fun `login against an unreachable engine names the host and stores nothing`() = runBlocking {
        val config = configWithBaseUrl()
        val auth = EngineAuth(config, clientThatNeverConnects())

        val result = auth.login("kevin@example.com", "correct horse", "Pixel 9")

        assertTrue(result is LoginResult.Unreachable)
        val message = (result as LoginResult.Unreachable).message
        assertTrue(
            "the message must name the host that was unreachable: $message",
            message.contains(baseUrl),
        )
        assertTrue(message.contains("nothing was sent"))
        assertNull(config.token())
        assertFalse(config.isSignedIn())
    }

    @Test
    fun `login with no engine address configured is refused before any request is sent`() = runBlocking {
        val config = EngineConfig(context, encrypt = { it }, decrypt = { it }) // no saveBaseUrl call
        val auth = EngineAuth(config, clientThatNeverConnects())

        val result = auth.login("kevin@example.com", "correct horse", "Pixel 9")

        assertTrue(result is LoginResult.Refused)
        assertFalse(config.isSignedIn())
    }

    @Test
    fun `me with a live token decodes the membership response`() = runBlocking {
        val config = configWithBaseUrl()
        config.saveSession("raw-device-token", "user-1")
        val client = clientRespondingWith(
            HttpStatusCode.OK,
            """{"user_id": "user-1", "email": "kevin@example.com", "device_name": "Pixel 9"}""",
        )
        val auth = EngineAuth(config, client)

        val result = auth.me()

        assertTrue(result is MeResult.Ok)
        assertEquals("kevin@example.com", (result as MeResult.Ok).email)
        assertEquals("Pixel 9", result.deviceName)
    }

    @Test
    fun `me with no stored token is refused without sending a request`() = runBlocking {
        val config = configWithBaseUrl() // never signed in
        val auth = EngineAuth(config, clientThatNeverConnects())

        val result = auth.me()

        assertTrue(result is MeResult.Refused)
        assertEquals("Not signed in on this device.", (result as MeResult.Refused).message)
    }

    @Test
    fun `me against a revoked token is refused and does not throw`() = runBlocking {
        val config = configWithBaseUrl()
        config.saveSession("revoked-token", "user-1")
        val client = clientRespondingWith(HttpStatusCode.Unauthorized, """{"detail": "Token has been revoked."}""")
        val auth = EngineAuth(config, client)

        val result = auth.me()

        assertTrue(result is MeResult.Refused)
        assertTrue((result as MeResult.Refused).message.contains("Token has been revoked."))
    }

    @Test
    fun `logout clears the local session even when the remote call cannot be reached`() = runBlocking {
        val config = configWithBaseUrl()
        config.saveSession("raw-device-token", "user-1")
        val auth = EngineAuth(config, clientThatNeverConnects())

        auth.logout()

        assertNull("an offline logout must still clear the local session", config.token())
        assertFalse(config.isSignedIn())
    }
}

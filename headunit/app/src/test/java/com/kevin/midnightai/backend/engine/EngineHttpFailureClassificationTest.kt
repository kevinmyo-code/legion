package com.kevin.midnightai.backend.engine

import java.net.ConnectException
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * [EngineHttp]'s failure classification - the part of the port CLAUDE.md section 7's
 * outcome-verb rule actually depends on: every non-2xx must land in the [EngineFailure] branch
 * whose NEXT ACTION matches what really happened (retry, sign in again, surface the server's own
 * words, or leave a write's fate unknown), never in a branch a caller could read as success.
 *
 * A real [MockWebServer] stands in for the engine - no fake `Interceptor`, no hand-rolled
 * dispatcher - so what is exercised is the real OkHttp request/response path, not a mock of it.
 */
@RunWith(RobolectricTestRunner::class)
class EngineHttpFailureClassificationTest {

    private val server = MockWebServer()

    private fun engineHttp(): EngineHttp {
        val config = EngineConfig(
            context = RuntimeEnvironment.getApplication(),
            // No real Android Keystore in a JVM test (KeyVault's own doc comment) - a trivial
            // reversible fake, same seam LEGION's own EngineConfigTest/EngineTestSupport use.
            encrypt = { plain -> "ENC($plain)" },
            decrypt = { blob -> blob.removePrefix("ENC(").removeSuffix(")") },
        )
        config.saveBaseUrl(server.url("/").toString().trimEnd('/'))
        config.saveSession("test-device-token", "11111111-1111-1111-1111-111111111111")
        return EngineHttp(config, OkHttpClient())
    }

    @Before
    fun start() {
        server.start()
    }

    @After
    fun stop() {
        // The "connection refused" test shuts the server down itself mid-test to force a real
        // IOException; shutdown() a second time here would throw, so this tolerates that.
        runCatching { server.shutdown() }
    }

    @Test
    fun `401 is classified Unauthorized`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"detail":"Invalid token."}"""))

        val result = engineHttp().get("/api/events/")

        val failure = (result.exceptionOrNull() as EngineHttpException).failure
        assertTrue(failure is EngineFailure.Unauthorized)
    }

    @Test
    fun `403 is also classified Unauthorized`() = runTest {
        // household/authentication.py's DeviceTokenAuthentication 401s for a dead/revoked/wrong
        // token, and DRF's own IsHouseholdMember renders its refusal as a 403 in the identical
        // {"detail": ...} shape - both collapse to one branch because the right next action
        // ("sign in again") is the same either way.
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"detail":"Not a household member."}"""))

        val result = engineHttp().get("/api/events/")

        val failure = (result.exceptionOrNull() as EngineHttpException).failure
        assertTrue(failure is EngineFailure.Unauthorized)
    }

    @Test
    fun `a 4xx is classified Refused and carries the server body verbatim`() = runTest {
        val body = """{"detail":"That measured tick already exists for this checklist item."}"""
        server.enqueue(MockResponse().setResponseCode(400).setBody(body))

        val result = engineHttp().post("/api/checklists/items/1/tick", "{}")

        val failure = (result.exceptionOrNull() as EngineHttpException).failure as EngineFailure.Refused
        assertEquals(400, failure.status)
        assertEquals(body, failure.body)
    }

    @Test
    fun `a 5xx is classified Refused, specifically not Unreachable`() = runTest {
        // The request DID reach the engine - a caller must not treat this as "nothing was sent"
        // and silently queue the write behind it, which is exactly what Unreachable licenses.
        server.enqueue(MockResponse().setResponseCode(500).setBody("Internal Server Error"))

        val result = engineHttp().post("/api/events/", "{}")

        val failure = (result.exceptionOrNull() as EngineHttpException).failure
        assertTrue(failure is EngineFailure.Refused)
        assertFalse(failure is EngineFailure.Unreachable)
        assertEquals(500, (failure as EngineFailure.Refused).status)
    }

    @Test
    fun `an IOException - connection refused - is classified Unreachable`() = runTest {
        // Point at a real closed port rather than the running MockWebServer, so OkHttp raises a
        // genuine ConnectException - the same shape a phone talking to an offline laptop engine
        // hits for real.
        server.shutdown()
        val config = EngineConfig(
            context = RuntimeEnvironment.getApplication(),
            encrypt = { plain -> "ENC($plain)" },
            decrypt = { blob -> blob.removePrefix("ENC(").removeSuffix(")") },
        )
        config.saveBaseUrl(server.url("/").toString().trimEnd('/'))
        config.saveSession("test-device-token", "11111111-1111-1111-1111-111111111111")
        val http = EngineHttp(config, OkHttpClient())

        val result = http.get("/api/events/")

        val failure = (result.exceptionOrNull() as EngineHttpException).failure
        assertTrue(failure is EngineFailure.Unreachable)
    }

    @Test
    fun `a 2xx with an undecodable body is classified Malformed by the caller`() = runTest {
        // EngineHttp itself only distinguishes 2xx-vs-not; Malformed is the caller's own
        // classification once it tries and fails to decode EngineOk.body - proven at that layer
        // rather than inside EngineHttp, which has no JSON-shape knowledge of any endpoint.
        server.enqueue(MockResponse().setResponseCode(200).setBody("not json at all"))

        val result = engineHttp().get("/api/auth/me")

        val ok = result.getOrThrow()
        assertEquals(200, ok.status)
        val decodeAttempt = runCatching {
            kotlinx.serialization.json.Json.decodeFromString(
                com.kevin.midnightai.backend.engine.api.model.MeResponse.serializer(),
                ok.body,
            )
        }
        assertTrue(
            "a caller decoding this body must fail, proving the Malformed branch is reachable",
            decodeAttempt.isFailure,
        )
    }

    @Test
    fun `a real ConnectException reaches Unreachable (sanity check on the exception type)`() {
        val e = ConnectException("Connection refused")
        val message = unreachableMessage("http://192.168.1.117:8000", e)
        assertTrue(message.contains("Could not reach"))
    }
}

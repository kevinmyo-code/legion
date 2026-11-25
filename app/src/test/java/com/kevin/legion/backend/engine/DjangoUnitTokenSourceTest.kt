package com.kevin.legion.backend.engine

import com.kevin.legion.backend.engine.EngineTestSupport.json
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
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
 * The phone minting the HEAD UNIT's own engine sign-in (T3b break 1, 2026-11-24). A [io.ktor.client.engine.mock.MockEngine]
 * stands in for the transport only, so the real [DjangoUnitTokenSource] decode and refusal paths
 * are what run. Every token here is obviously invented.
 */
@RunWith(RobolectricTestRunner::class)
class DjangoUnitTokenSourceTest {
    private val context = RuntimeEnvironment.getApplication()

    private fun source(
        status: HttpStatusCode = HttpStatusCode.Created,
        body: String = ISSUED,
    ): Pair<DjangoUnitTokenSource, EngineTestSupport.RecordingEngine> {
        val engine = EngineTestSupport.RecordingEngine { json(body, status) }
        return DjangoUnitTokenSource(
            EngineHttp(EngineTestSupport.signedInConfig(context), engine.client()),
        ) to engine
    }

    @Test fun `a mint names the kind and the chosen car, and answers the token once`() = runBlocking {
        val (tokens, engine) = source()
        val minted = tokens.mint("Head unit", "vehicle-id-for-tests")!!
        assertEquals("hu-id-for-tests", minted.id)
        assertEquals("hu1_invented-token", minted.token)
        assertEquals("2030-01-01T00:00:00Z", minted.expires)
        val request = engine.requests.single()
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("/api/auth/head-units", request.url.encodedPath)
        val sent = sentBody(request)
        assertTrue(sent, sent.contains(""""kind":"head_unit""""))
        assertTrue(sent, sent.contains(""""name":"Head unit""""))
        assertTrue(sent, sent.contains(""""vehicle_id":"vehicle-id-for-tests""""))
    }

    @Test fun `no car chosen means no vehicle_id field at all, never a null`() = runBlocking {
        val (tokens, engine) = source()
        tokens.mint("Head unit", null)
        assertFalse(sentBody(engine.requests.single()).contains("vehicle_id"))
    }

    /**
     * The branch that mattered before 2026-11-24, when `LEGION_HEADUNIT_SIGNIN` gated these routes.
     * The switch is gone, but an engine behind this build, or a rollback, answers the same 404, and
     * the pairing must go on handing over everything else rather than failing.
     */
    @Test fun `an engine that does not serve the route mints nothing and does not throw`() = runBlocking {
        val (tokens, _) = source(HttpStatusCode.NotFound, """{"detail":"Not found."}""")
        assertNull(tokens.mint("Head unit", null))
    }

    @Test fun `every other refusal is null too, never an exception`() = runBlocking {
        listOf(
            HttpStatusCode.Unauthorized,
            HttpStatusCode.Forbidden,
            HttpStatusCode.BadRequest,
            HttpStatusCode.TooManyRequests,
            HttpStatusCode.InternalServerError,
        ).forEach { status ->
            val (tokens, _) = source(status, """{"detail":"no"}""")
            assertNull(status.toString(), tokens.mint("Head unit", null))
        }
    }

    @Test fun `an unreachable engine mints nothing`() = runBlocking {
        val tokens = DjangoUnitTokenSource(
            EngineHttp(EngineTestSupport.signedInConfig(context), EngineTestSupport.unreachableClient()),
        )
        assertNull(tokens.mint("Head unit", null))
        assertNull(tokens.rotate("hu-id-for-tests"))
        assertFalse(tokens.revoke("hu-id-for-tests"))
    }

    @Test fun `a 2xx whose body names no token is not a token`() = runBlocking {
        val (noToken, _) = source(HttpStatusCode.Created, """{"id":"hu-id-for-tests"}""")
        assertNull(noToken.mint("Head unit", null))
        val (blank, _) = source(HttpStatusCode.Created, """{"id":"hu-id-for-tests","token":"  "}""")
        assertNull(blank.mint("Head unit", null))
        val (notJson, _) = source(HttpStatusCode.Created, "<html>not json</html>")
        assertNull(notJson.mint("Head unit", null))
    }

    @Test fun `rotate posts to the units own rotate route and reads the new token`() = runBlocking {
        val (tokens, engine) = source(HttpStatusCode.OK, ROTATED)
        assertEquals("hu1_invented-rotated", tokens.rotate("hu-id-for-tests")!!.token)
        assertEquals("/api/auth/head-units/hu-id-for-tests/rotate", engine.requests.single().url.encodedPath)
    }

    @Test fun `forget deletes the unit, and already gone counts as gone`() = runBlocking {
        val (ok, engine) = source(HttpStatusCode.NoContent, "")
        assertTrue(ok.revoke("hu-id-for-tests"))
        assertEquals(HttpMethod.Delete, engine.requests.single().method)
        assertEquals("/api/auth/head-units/hu-id-for-tests", engine.requests.single().url.encodedPath)
        // The engine's own words for a stale id; Forget must not read that as a failure.
        val (gone, _) = source(HttpStatusCode.NotFound, """{"detail":"Nothing was revoked."}""")
        assertTrue(gone.revoke("hu-id-for-tests"))
        // A real refusal still is one.
        val (refused, _) = source(HttpStatusCode.Forbidden, """{"detail":"no"}""")
        assertFalse(refused.revoke("hu-id-for-tests"))
    }

    private fun sentBody(request: HttpRequestData): String = (request.body as TextContent).text

    private companion object {
        const val ISSUED =
            """{"id":"hu-id-for-tests","token":"hu1_invented-token","expires":"2030-01-01T00:00:00Z"}"""
        const val ROTATED =
            """{"id":"hu-id-for-tests","token":"hu1_invented-rotated","expires":"2030-02-01T00:00:00Z"}"""
    }
}

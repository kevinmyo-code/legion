package com.kevin.midnightai.backend.engine

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
 * The silent-data-loss bug [EngineHttp.get]'s own doc comment names at length: the server's
 * `next` sync cursor is a Python `isoformat()` string containing a `+00:00` offset, and a raw `+`
 * in an un-percent-encoded query string decodes to a literal SPACE under the
 * `application/x-www-form-urlencoded` convention - silently resetting the watermark to EPOCH on
 * the next poll. [okhttp3.HttpUrl.Builder.addQueryParameter] must percent-encode `+` as `%2B`
 * rather than let it through raw, or this app inherits the exact bug the phone already hit.
 */
@RunWith(RobolectricTestRunner::class)
class EngineHttpQueryEncodingTest {

    private val server = MockWebServer()

    @Before
    fun start() {
        server.start()
    }

    @After
    fun stop() {
        server.shutdown()
    }

    @Test
    fun `a raw plus in a query value is percent-encoded, never sent as a literal plus`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"results":[],"next":null}"""))

        val config = EngineConfig(
            context = RuntimeEnvironment.getApplication(),
            encrypt = { plain -> "ENC($plain)" },
            decrypt = { blob -> blob.removePrefix("ENC(").removeSuffix(")") },
        )
        config.saveBaseUrl(server.url("/").toString().trimEnd('/'))
        config.saveSession("test-device-token", "11111111-1111-1111-1111-111111111111")
        val http = EngineHttp(config, OkHttpClient())

        val cursor = "2026-09-07T12:00:00+00:00"
        http.get("/api/changes/", mapOf("since" to cursor)).getOrThrow()

        val recorded = server.takeRequest()
        val rawPath = recorded.path.orEmpty()
        // The bug this test exists to catch: a raw, un-percent-encoded '+' surviving into the
        // request line decodes to a SPACE server-side and silently resets the watermark to EPOCH.
        assertFalse(
            "a literal unencoded '+' must never reach the wire in a query value",
            rawPath.contains("+00:00"),
        )
        assertTrue("the '+' must be percent-encoded as %2B", rawPath.contains("%2B"))

        // Decoding the recorded request's own query back out proves the ORIGINAL value survives
        // the round trip - not just that some encoding happened.
        val decodedCursor = recorded.requestUrl?.queryParameter("since")
        assertEquals(cursor, decodedCursor)
    }
}

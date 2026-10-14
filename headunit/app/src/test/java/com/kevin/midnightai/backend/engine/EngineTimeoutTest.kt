package com.kevin.midnightai.backend.engine

import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The timeout half of the engine transport: a Cloud Run cold start must not read as "unreachable",
 * a timeout must not claim "nothing was sent", `tools/list` retries once and `tools/call` never
 * does. A real [MockWebServer] with [SocketPolicy.NO_RESPONSE] produces genuine OkHttp read
 * timeouts against a short-timeout client.
 */
@RunWith(RobolectricTestRunner::class)
class EngineTimeoutTest {

    private val server = MockWebServer()

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    private val toolsList = """{"jsonrpc":"2.0","id":1,"result":{"tools":[
        {"name":"read_records","description":"d","inputSchema":{"type":"object"},
         "annotations":{"readOnlyHint":true}}]}}"""

    private fun client(): EngineMcpClient {
        server.start()
        val config = EngineConfig(
            context = RuntimeEnvironment.getApplication(),
            encrypt = { plain -> "ENC($plain)" },
            decrypt = { blob -> blob.removePrefix("ENC(").removeSuffix(")") },
        )
        config.saveBaseUrl(server.url("/").toString().trimEnd('/'))
        config.saveSession("test-device-token", "user-1")
        val fast = OkHttpClient.Builder().readTimeout(300, TimeUnit.MILLISECONDS).build()
        return EngineMcpClient(EngineHttp(config, fast), config::baseUrl)
    }

    @Test
    fun `shared client carries cold-start sized timeouts`() {
        val built = EngineHttp.buildClient()
        assertEquals(15_000, built.connectTimeoutMillis)
        assertEquals(30_000, built.readTimeoutMillis)
        assertEquals(15_000, built.writeTimeoutMillis)
    }

    @Test
    fun `tools list retries once after a timeout and succeeds`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        server.enqueue(MockResponse().setBody(toolsList))

        val result = client().listTools()

        assertTrue("second attempt should have listed the tools: $result", result is McpListResult.Listed)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `tools list gives up after one retry and does not claim nothing was sent`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))

        val result = client().listTools() as McpListResult.Failed

        assertEquals(2, server.requestCount)
        assertTrue(result.failure is McpFailure.Unreachable)
        val sentence = result.failure.sentence
        assertTrue(sentence, sentence.contains("took too long"))
        assertFalse(sentence, sentence.contains("nothing was sent"))
        assertFalse(sentence, sentence.contains("is unreachable"))
    }

    @Test
    fun `fresh list bypasses the cache read, normal list uses it`() = runBlocking {
        server.enqueue(MockResponse().setBody(toolsList))
        server.enqueue(MockResponse().setBody(toolsList))
        val client = client()

        client.listTools()
        client.listTools()
        assertEquals("second normal call is served from cache", 1, server.requestCount)

        val fresh = client.listTools(fresh = true)
        assertTrue(fresh is McpListResult.Listed)
        assertEquals("fresh call hits the wire", 2, server.requestCount)
    }

    @Test
    fun `tools list does not retry a non-timeout failure`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"detail":"no"}"""))

        val result = client().listTools()

        assertTrue(result is McpListResult.Failed)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a write tools call is never retried after a timeout`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        server.enqueue(MockResponse().setBody("""{"jsonrpc":"2.0","id":1,"result":{"content":[{"type":"text","text":"ok"}]}}"""))

        val outcome = client().callTool("add_event", JSONObject(), writes = true)

        assertTrue(outcome is McpCallOutcome.Failed)
        assertEquals(1, server.requestCount)
        val sentence = (outcome as McpCallOutcome.Failed).failure.sentence
        assertTrue(sentence, sentence.contains("cannot be confirmed"))
    }

    @Test
    fun `timeout wording is its own sentence, other IOExceptions keep theirs`() {
        val timeout = unreachableMessage("https://e.example", SocketTimeoutException("timeout"))
        assertTrue(timeout.contains("took too long to answer"))
        assertFalse(timeout.contains("nothing was sent"))

        val refused = unreachableMessage("https://e.example", IOException("Connection refused"))
        assertTrue(refused.contains("nothing was sent"))
        assertFalse(isTimeout(IOException("Canceled")))
    }
}

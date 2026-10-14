package com.kevin.midnightai.backend.engine

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The `/mcp` request envelope and the one pre-dispatch retry (fix/engine-http400).
 *
 * The car saw "The engine refused the request (HTTP 400)" on every ask_engine call. The engine's
 * MCP SDK 2.2 rejects a 2026-07-28 request whose `params._meta` lacks
 * `io.modelcontextprotocol/clientCapabilities` with HTTP 400 / JSON-RPC -32602, before any tool
 * runs (`mcp/shared/inbound.py` `classify_inbound_request` rung 1), and the client only ever sent
 * the protocol-version key. [ladder] reproduces that rung and the header rung so the regression
 * test fails on the old client.
 */
@RunWith(RobolectricTestRunner::class)
class EngineMcpEnvelopeTest {

    private val server = MockWebServer()
    private val requests = CopyOnWriteArrayList<RecordedRequest>()

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    private val toolsList = """{"jsonrpc":"2.0","id":1,"result":{"tools":[
        {"name":"read_records","description":"d","inputSchema":{"type":"object"},
         "annotations":{"readOnlyHint":true}}]}}"""

    private val callOk = """{"jsonrpc":"2.0","id":1,"result":{"content":[{"type":"text","text":"ok"}]}}"""

    private val metaMissing = """{"jsonrpc":"2.0","id":1,"error":{"code":-32602,"message":
        "params._meta is missing the required envelope key(s): io.modelcontextprotocol/clientCapabilities"}}"""

    private fun rpcError(status: Int, code: Int, message: String) = MockResponse().setResponseCode(status)
        .setHeader("Content-Type", "application/json")
        .setBody(JSONObject().put("jsonrpc", "2.0").put("id", 1)
            .put("error", JSONObject().put("code", code).put("message", message)).toString())

    private fun bodyOf(request: RecordedRequest) = JSONObject(request.body.clone().readUtf8())
    private fun methodOf(request: RecordedRequest) = bodyOf(request).getString("method")

    private fun serve(handler: (RecordedRequest) -> MockResponse) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                return handler(request)
            }
        }
        server.start()
    }

    private fun client(): EngineMcpClient {
        val config = EngineConfig(
            context = RuntimeEnvironment.getApplication(),
            encrypt = { plain -> "ENC($plain)" },
            decrypt = { blob -> blob.removePrefix("ENC(").removeSuffix(")") },
        )
        config.saveBaseUrl(server.url("/").toString().trimEnd('/'))
        config.saveSession("test-device-token", "user-1")
        return EngineMcpClient(EngineHttp(config), config::baseUrl)
    }

    /** The SDK 2.2 modern-path ladder, rungs 1 and 2, then [answer]. */
    private fun ladder(request: RecordedRequest, answer: (String) -> MockResponse): MockResponse {
        val body = bodyOf(request)
        val meta = body.optJSONObject("params")?.optJSONObject("_meta")
        val method = body.getString("method")
        return when {
            meta == null || !meta.has("io.modelcontextprotocol/protocolVersion") ||
                !meta.has("io.modelcontextprotocol/clientCapabilities") ->
                MockResponse().setResponseCode(400).setBody(metaMissing)
            request.getHeader("MCP-Protocol-Version") != meta.getString("io.modelcontextprotocol/protocolVersion") ||
                request.getHeader("MCP-Method") != method ->
                rpcError(400, -32020, "mcp-method header does not match the request body's method")
            method == "tools/call" &&
                request.getHeader("MCP-Name") != body.getJSONObject("params").getString("name") ->
                rpcError(400, -32020, "mcp-name header does not match the request body's 'name' parameter")
            else -> answer(method)
        }
    }

    @Test
    fun `a modern request carries both required envelope keys and passes the SDK ladder`() = runBlocking {
        serve { ladder(it) { method -> MockResponse().setBody(if (method == "tools/list") toolsList else callOk) } }

        val listed = client().listTools(fresh = true)

        assertTrue("$listed", listed is McpListResult.Listed)
        assertEquals(1, requests.size)
        val meta = bodyOf(requests[0]).getJSONObject("params").getJSONObject("_meta")
        assertEquals("2026-07-28", meta.getString("io.modelcontextprotocol/protocolVersion"))
        assertEquals(0, meta.getJSONObject("io.modelcontextprotocol/clientCapabilities").length())
        assertTrue(meta.getJSONObject("io.modelcontextprotocol/clientInfo").getString("name").isNotBlank())
        assertEquals("application/json, text/event-stream", requests[0].getHeader("Accept"))
        assertTrue(requests[0].getHeader("Content-Type")!!.startsWith("application/json"))
        assertEquals("Token test-device-token", requests[0].getHeader("Authorization"))
    }

    @Test
    fun `a tools call passes the ladder with its name header and arguments intact`() = runBlocking {
        serve { ladder(it) { MockResponse().setBody(callOk) } }

        val args = JSONObject().put("table", "places")
        val outcome = client().callTool("read_records", args, writes = false)

        assertTrue("$outcome", outcome is McpCallOutcome.Called)
        val params = bodyOf(requests.single()).getJSONObject("params")
        assertEquals("read_records", params.getString("name"))
        assertEquals("places", params.getJSONObject("arguments").getString("table"))
        assertFalse("caller's JSON is not mutated", args.has("_meta"))
    }

    @Test
    fun `an envelope rejection falls back once to the handshake and remembers it`() = runBlocking {
        // An engine whose modern path refuses this client's envelope; legacy path is stateful.
        serve { request ->
            val version = request.getHeader("MCP-Protocol-Version")
            when {
                version == "2026-07-28" -> MockResponse().setResponseCode(400).setBody(metaMissing)
                methodOf(request) == "initialize" -> MockResponse()
                    .setHeader("Mcp-Session-Id", "sess-1")
                    .setBody("""{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-11-25","capabilities":{}}}""")
                request.getHeader("Mcp-Session-Id") != "sess-1" ->
                    rpcError(400, -32600, "Bad Request: Missing session ID")
                methodOf(request) == "notifications/initialized" -> MockResponse().setResponseCode(202)
                methodOf(request) == "tools/list" -> MockResponse().setBody(toolsList)
                else -> MockResponse().setBody(callOk)
            }
        }
        val client = client()

        val listed = client.listTools(fresh = true)
        assertTrue("$listed", listed is McpListResult.Listed)
        assertEquals(
            listOf("tools/list", "initialize", "notifications/initialized", "tools/list"),
            requests.map(::methodOf),
        )
        assertEquals("2025-11-25", requests[3].getHeader("MCP-Protocol-Version"))
        assertNull("no modern _meta on the legacy path", bodyOf(requests[3]).getJSONObject("params").optJSONObject("_meta"))

        // A write after the fallback goes straight to the legacy session: one request, no re-probe.
        requests.clear()
        val outcome = client.callTool("add_event", JSONObject().put("title", "x"), writes = true)
        assertTrue("$outcome", outcome is McpCallOutcome.Called)
        assertEquals(listOf("tools/call"), requests.map(::methodOf))
    }

    @Test
    fun `a dropped legacy session re-initializes once and retries`() = runBlocking {
        var current = "sess-1"
        var inits = 0
        serve { request ->
            when {
                request.getHeader("MCP-Protocol-Version") == "2026-07-28" ->
                    rpcError(400, -32022, "Unsupported protocol version")
                methodOf(request) == "initialize" -> {
                    inits++
                    current = "sess-$inits"
                    MockResponse().setHeader("Mcp-Session-Id", current)
                        .setBody("""{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-11-25"}}""")
                }
                request.getHeader("Mcp-Session-Id") != current ->
                    rpcError(404, -32600, "Not Found: Invalid or expired session ID")
                methodOf(request) == "notifications/initialized" -> MockResponse().setResponseCode(202)
                else -> MockResponse().setBody(toolsList)
            }
        }
        val client = client()
        assertTrue(client.listTools(fresh = true) is McpListResult.Listed)
        assertEquals(1, inits)

        current = "sess-restarted" // the server restarted and forgot sess-1
        requests.clear()
        val again = client.listTools(fresh = true)

        assertTrue("$again", again is McpListResult.Listed)
        assertEquals(2, inits)
        assertEquals(
            listOf("tools/list", "initialize", "notifications/initialized", "tools/list"),
            requests.map(::methodOf),
        )
    }

    @Test
    fun `a tool-argument 400 is not retried and its reason reaches the sentence`() = runBlocking {
        serve { rpcError(400, -32602, "Validation error: intent is a required property") }

        val outcome = client().callTool("add_event", JSONObject(), writes = true)

        assertEquals("a write rejected for its arguments is sent exactly once", 1, requests.size)
        val failure = (outcome as McpCallOutcome.Failed).failure as McpFailure.Refused
        assertEquals(400, failure.status)
        assertEquals(-32602, failure.rpcCode)
        assertTrue(failure.sentence, failure.sentence.contains("HTTP 400, JSON-RPC -32602"))
        assertTrue(failure.sentence, failure.sentence.contains("intent is a required property"))
    }

    @Test
    fun `a failed fallback handshake returns the original refusal with the server's reason`() = runBlocking {
        serve { request ->
            if (methodOf(request) == "initialize") {
                MockResponse().setResponseCode(500).setBody("boom")
            } else {
                MockResponse().setResponseCode(400).setBody(metaMissing)
            }
        }

        val listed = client().listTools(fresh = true)

        val failure = (listed as McpListResult.Failed).failure as McpFailure.Refused
        assertEquals(400, failure.status)
        assertTrue(failure.sentence, failure.sentence.contains("clientCapabilities"))
        assertEquals(listOf("tools/list", "initialize"), requests.map(::methodOf))
    }

    @Test
    fun `a DRF 400 and a switched-off 404 never trigger the fallback`() = runBlocking {
        serve { request ->
            if (requests.size == 1) {
                MockResponse().setResponseCode(400).setBody("""{"detail":"Household required."}""")
            } else {
                MockResponse().setResponseCode(404).setBody("""{"detail":"MCP is switched off."}""")
            }
        }
        val client = client()

        val first = (client.listTools(fresh = true) as McpListResult.Failed).failure
        assertTrue(first.sentence, first is McpFailure.Refused && first.sentence.contains("Household required."))
        val second = (client.listTools(fresh = true) as McpListResult.Failed).failure
        assertTrue("$second", second is McpFailure.SwitchedOff)
        assertEquals(2, requests.size)
    }

    @Test
    fun `pre-dispatch classification is narrow`() {
        fun refused(status: Int, body: String) = EngineFailure.Refused(status, body)
        assertTrue(EngineMcpClient.isPreDispatchRejection(refused(400, metaMissing)))
        assertTrue(EngineMcpClient.isPreDispatchRejection(refused(400, """{"error":{"code":-32020,"message":"x"}}""")))
        assertTrue(EngineMcpClient.isPreDispatchRejection(refused(400, """{"error":{"code":-32022,"message":"x"}}""")))
        assertTrue(
            EngineMcpClient.isPreDispatchRejection(
                refused(404, """{"error":{"code":-32600,"message":"Not Found: Invalid or expired session ID"}}"""),
            ),
        )
        assertFalse(EngineMcpClient.isPreDispatchRejection(refused(400, """{"error":{"code":-32602,"message":"bad arg"}}""")))
        assertFalse(EngineMcpClient.isPreDispatchRejection(refused(400, """{"detail":"no"}""")))
        assertFalse(EngineMcpClient.isPreDispatchRejection(refused(404, """{"detail":"switched off"}""")))
        assertFalse(EngineMcpClient.isPreDispatchRejection(refused(500, metaMissing)))
        assertFalse(EngineMcpClient.isPreDispatchRejection(EngineFailure.Unauthorized("no")))
        assertFalse(EngineMcpClient.isPreDispatchRejection(null))
    }
}

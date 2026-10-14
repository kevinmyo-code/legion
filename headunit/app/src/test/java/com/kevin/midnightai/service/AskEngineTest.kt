package com.kevin.midnightai.service

import com.kevin.midnightai.ai.AgentResult
import com.kevin.midnightai.ai.AgentTool
import com.kevin.midnightai.backend.engine.EngineConfig
import com.kevin.midnightai.backend.engine.EngineHttp
import com.kevin.midnightai.backend.engine.EngineMcpClient
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONArray
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
 * The `ask_engine` bridge, ported from the phone app's AskEngineTest. The transport is a real
 * [MockWebServer] (same choice as EngineHttpFailureClassificationTest) and the sub-agent is a fake
 * that calls the tools it is handed exactly as the real loop would, so every assertion is about
 * what the BRIDGE does: what crosses to the engine, and what is said about the outcome.
 *
 * Robolectric because the bridge and the MCP client use `org.json`, which is a stub on a plain JVM.
 */
@RunWith(RobolectricTestRunner::class)
class AskEngineTest {

    private val server = MockWebServer()
    private val requests = CopyOnWriteArrayList<RecordedRequest>()

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    private val toolsList = """{"jsonrpc":"2.0","id":1,"result":{"tools":[
        {"name":"read_records","description":"Reads a table.","inputSchema":{"type":"object",
          "properties":{"table":{"type":"string","enum":["places","chassis_quirks","drive_reassignments"]}},
          "required":["table"],"additionalProperties":false},
         "annotations":{"readOnlyHint":true}},
        {"name":"add_event","description":"Adds an event.","inputSchema":{"type":"object",
          "properties":{"title":{"type":"string"},"id":{"type":"string","format":"uuid"},
                        "fields":{"type":"object"}}},
         "annotations":{"readOnlyHint":false}}
    ]}}"""

    private fun callReply(text: String, isError: Boolean = false) =
        JSONObject().put("jsonrpc", "2.0").put("id", 1).put(
            "result",
            JSONObject()
                .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", text)))
                .put("isError", isError),
        ).toString()

    private fun methodOf(request: RecordedRequest): String =
        JSONObject(request.body.clone().readUtf8()).getString("method")

    /** Serves `tools/list` with [list] and every `tools/call` with [call]; records everything. */
    private fun engine(
        call: MockResponse = MockResponse().setBody(callReply("ok")),
        list: MockResponse = MockResponse().setBody(toolsList),
    ) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                // Each response is built fresh: a MockResponse is single-use state on some paths.
                return if (methodOf(request) == "tools/list") list.clone() else call.clone()
            }
        }
        server.start()
    }

    private fun config(): EngineConfig {
        val config = EngineConfig(
            context = RuntimeEnvironment.getApplication(),
            // No real Android Keystore in a JVM test (KeyVault's own doc comment).
            encrypt = { plain -> "ENC($plain)" },
            decrypt = { blob -> blob.removePrefix("ENC(").removeSuffix(")") },
        )
        config.saveBaseUrl(server.url("/").toString().trimEnd('/'))
        config.saveSession("test-device-token", "user-1")
        return config
    }

    private fun client(): EngineMcpClient {
        val config = config()
        return EngineMcpClient(EngineHttp(config), config::baseUrl)
    }

    /** A fake agent: calls each named tool with the given args, in order, then answers [say]. */
    private fun agent(vararg calls: Pair<String, JSONObject>, say: String = "Done."):
        suspend (String, String, List<AgentTool>) -> AgentResult = { _, _, tools ->
        for ((name, args) in calls) tools.first { it.name == name }.run(args)
        AgentResult.Success(say)
    }

    private fun bridge(
        client: EngineMcpClient,
        run: suspend (String, String, List<AgentTool>) -> AgentResult = agent(),
    ) = bridgeWith(client, AskEngine.WRITE_CALL_TIMEOUT_MS, run)

    private fun bridgeWith(
        client: EngineMcpClient,
        writeTimeoutMs: Long,
        run: suspend (String, String, List<AgentTool>) -> AgentResult,
    ) = AskEngine(
        client, run, identityClause = { "You are a helper." }, nowText = { "2026-10-10T09:00:00-05:00" },
        writeTimeoutMs = writeTimeoutMs,
    )

    private fun callsSent() = requests.count { methodOf(it) == "tools/call" }

    @Test
    fun `unreachable engine is a sentence that says nothing was read or written`() = runBlocking {
        engine()
        val client = client()
        server.shutdown() // the address now refuses connections
        var agentRan = false
        val answer = bridge(client) { _, _, _ ->
            agentRan = true
            AgentResult.Success("you have nothing due")
        }.ask("what is due", wantsWrite = false)
        assertFalse(answer.success)
        assertTrue(answer.message, answer.message.contains("unreachable"))
        assertTrue(answer.message, answer.message.contains("nothing was read or written"))
        assertFalse("the agent must not run with nothing to answer from", agentRan)
    }

    @Test
    fun `MCP switched off 404 says so and that nothing was read or written`() = runBlocking {
        engine(
            list = MockResponse().setResponseCode(404).setBody(
                """{"detail":"Nothing was read or written. This engine's MCP endpoint is switched off."}""",
            ),
        )
        val answer = bridge(client()).ask("what is due", false)
        assertFalse(answer.success)
        assertTrue(answer.message, answer.message.contains("switched off"))
        assertTrue(answer.message, answer.message.contains("nothing was read or written"))
    }

    @Test
    fun `an MCP 400 is EngineRefused with codes for Crashlytics and the server's reason for the model`() = runBlocking {
        engine(
            list = MockResponse().setResponseCode(400).setBody(
                """{"jsonrpc":"2.0","id":1,"error":{"code":-32600,"message":"Body must be a single JSON-RPC request"}}""",
            ),
        )
        val answer = bridge(client()).ask("what is due", false)
        assertFalse(answer.success)
        assertEquals(EngineFailureClass.EngineRefused, answer.failure)
        assertEquals("HTTP 400, JSON-RPC -32600", answer.detail)
        assertTrue(answer.message, answer.message.contains("Body must be a single JSON-RPC request"))
        assertTrue(answer.message, answer.message.contains("nothing was read or written"))
    }

    @Test
    fun `refused token says so and that nothing was read or written`() = runBlocking {
        engine(list = MockResponse().setResponseCode(401).setBody("""{"detail":"Invalid token."}"""))
        val answer = bridge(client()).ask("what is due", false)
        assertFalse(answer.success)
        assertTrue(answer.message, answer.message.contains("refused this device's token"))
        assertTrue(answer.message, answer.message.contains("nothing was read or written"))
    }

    @Test
    fun `no address or token sends nothing and says to sign in`() = runBlocking {
        engine()
        val config = EngineConfig(
            RuntimeEnvironment.getApplication(),
            encrypt = { "ENC($it)" },
            decrypt = { it.removePrefix("ENC(").removeSuffix(")") },
        )
        config.clearSession()
        val answer = bridge(EngineMcpClient(EngineHttp(config), config::baseUrl)).ask("what is due", false)
        assertFalse(answer.success)
        assertTrue(answer.message, answer.message.contains("nothing was read or written"))
        assertEquals(0, requests.size)
    }

    @Test
    fun `list request carries the device token and the MCP envelope`() = runBlocking {
        engine()
        bridge(client()).ask("what is due", false)
        val request = requests.first()
        assertEquals("Token test-device-token", request.getHeader("Authorization"))
        assertEquals("/mcp", request.path)
        assertEquals("application/json, text/event-stream", request.getHeader("Accept"))
        assertEquals("tools/list", request.getHeader("MCP-Method"))
        assertEquals("2026-07-28", request.getHeader("MCP-Protocol-Version"))
        val sent = JSONObject(request.body.clone().readUtf8())
        assertEquals("2.0", sent.getString("jsonrpc"))
        assertEquals(
            "2026-07-28",
            sent.getJSONObject("params").getJSONObject("_meta").getString("io.modelcontextprotocol/protocolVersion"),
        )
    }

    @Test
    fun `tools list is cached so a second question fetches it once`() = runBlocking {
        engine()
        val client = client()
        bridge(client).ask("one", false)
        bridge(client).ask("two", false)
        assertEquals(1, requests.count { methodOf(it) == "tools/list" })
    }

    /** Runs each call the way SubAgent.investigate does: under withTimeoutOrNull(tool.timeoutMs). */
    private fun realRaceAgent(name: String, args: JSONObject): suspend (String, String, List<AgentTool>) -> AgentResult =
        { _, _, tools ->
            val tool = tools.first { it.name == name }
            kotlinx.coroutines.withTimeoutOrNull(tool.timeoutMs) { tool.run(args) }
            AgentResult.Success("Done, added it.")
        }

    @Test
    fun `a write that hangs past its inner timeout is reported as possibly written, never as committed nothing`() = runBlocking {
        // The engine accepts the request and then sits on the reply: the real race. The inner
        // bound (200ms here) must fire before the agent-tool bound, or the tally would be empty.
        engine(call = MockResponse().setBody(callReply("ok")).setBodyDelay(3, java.util.concurrent.TimeUnit.SECONDS))
        val answer = bridgeWith(
            client(),
            200,
            realRaceAgent("add_event", JSONObject().put("title", "dentist")),
        ).ask("add dentist", wantsWrite = true)
        assertFalse(answer.success)
        assertTrue(answer.message, answer.message.contains("may have been written"))
        assertFalse(answer.message, answer.message.contains("committed nothing"))
        assertFalse(answer.message, answer.message.contains("was not written"))
        assertFalse(answer.message, answer.message.contains("Try again"))
    }

    @Test
    fun `a write tool's agent timeout is longer than its inner timeout so the inner always fires first`() = runBlocking {
        engine()
        var writeTimeout = 0L
        var readTimeout = 0L
        bridgeWith(client(), 1_000) { _, _, tools ->
            writeTimeout = tools.first { it.name == "add_event" }.timeoutMs
            readTimeout = tools.first { it.name == "read_records" }.timeoutMs
            AgentResult.Success("x")
        }.ask("q", false)
        assertTrue(writeTimeout > 1_000)
        assertEquals(20_000L, readTimeout)
    }

    @Test
    fun `an isError write is relayed as a failure with the server's sentence, not the model's done`() = runBlocking {
        engine(call = MockResponse().setBody(callReply("Nothing was written. This device token is read-only.", isError = true)))
        val answer = bridge(
            client(),
            agent("add_event" to JSONObject().put("title", "dentist"), say = "Done, added it."),
        ).ask("add dentist", wantsWrite = true)
        assertFalse(answer.success)
        assertTrue(answer.message, answer.message.contains("Nothing was written. This device token is read-only."))
        assertFalse(answer.message.contains("Done, added"))
    }

    @Test
    fun `a successful write in the turn is reported as success`() = runBlocking {
        engine(call = MockResponse().setBody(callReply("Added event dentist (committed).")))
        val answer = bridge(
            client(),
            agent("add_event" to JSONObject().put("title", "dentist"), say = "Added the dentist."),
        ).ask("add dentist", wantsWrite = true)
        assertTrue(answer.message, answer.success)
        assertEquals("Added the dentist.", answer.message)
    }

    @Test
    fun `record intent with no write committed is a failure not a summary`() = runBlocking {
        engine()
        val answer = bridge(client(), agent(say = "Sure, noted.")).ask("add milk to groceries", wantsWrite = true)
        assertFalse(answer.success)
        assertTrue(answer.message, answer.message.contains("did not get written"))
    }

    @Test
    fun `an inferred record that commits nothing returns the read answer, a declared one fails`() = runBlocking {
        engine()
        val inferred = bridge(client(), agent(say = "Milk is on the list."))
            .ask("groceries add milk", wantsWrite = true, intentDeclared = false)
        assertTrue(inferred.message, inferred.success)
        assertEquals("Milk is on the list.", inferred.message)
        val declared = bridge(client(), agent(say = "Milk is on the list."))
            .ask("groceries add milk", wantsWrite = true, intentDeclared = true)
        assertFalse(declared.success)
        assertEquals(EngineFailureClass.WriteRefused, declared.failure)
    }

    @Test
    fun `a write called under an ask intent is refused before anything is sent`() = runBlocking {
        engine()
        val answer = bridge(
            client(),
            agent("add_event" to JSONObject().put("title", "x"), say = "Added."),
        ).ask("what is due", wantsWrite = false)
        assertEquals(0, callsSent())
        // Not a success: the model's "Added." must not reach the driver, and the Live model is
        // told how to retry instead of being left to say "I can't add that".
        assertFalse(answer.message, answer.success)
        assertFalse(answer.message, answer.message.contains("Added."))
        assertTrue(answer.message, answer.message.contains("intent record"))
        assertEquals(EngineFailureClass.WriteRefused, answer.failure)
    }

    @Test
    fun `a loop past its budget is an AgentTimeout with its own sentence`() = runBlocking {
        engine()
        val answer = AskEngine(
            client(),
            runAgent = { _, _, _ ->
                kotlinx.coroutines.delay(5_000)
                AgentResult.Success("late")
            },
            identityClause = { "x" },
            nowText = { "now" },
            agentBudgetMs = 100,
        ).ask("what is due", false)
        assertFalse(answer.success)
        assertEquals(EngineFailureClass.AgentTimeout, answer.failure)
        assertTrue(answer.message, answer.message.contains("took too long"))
    }

    @Test
    fun `a model error is an AgentError and is worded differently from a timeout`() = runBlocking {
        engine()
        for (result in listOf(AgentResult.Failed, AgentResult.Overloaded, AgentResult.RateLimited, AgentResult.KeyInvalid)) {
            val answer = bridge(client()) { _, _, _ -> result }.ask("what is due", false)
            assertFalse(answer.success)
            assertEquals(result.toString(), EngineFailureClass.AgentError, answer.failure)
            assertFalse(answer.message, answer.message.contains("took too long"))
        }
        val failed = bridge(client()) { _, _, _ -> AgentResult.Failed }.ask("what is due", false)
        assertTrue(failed.message, failed.message.contains("returned an error"))
    }

    @Test
    fun `an engine-refused write is WriteRefused and says the engine refused it`() = runBlocking {
        engine(call = MockResponse().setBody(callReply("Nothing was written. Read-only token.", isError = true)))
        val answer = bridge(
            client(),
            agent("add_event" to JSONObject().put("title", "dentist"), say = "Done."),
        ).ask("add dentist", wantsWrite = true)
        assertEquals(EngineFailureClass.WriteRefused, answer.failure)
        assertTrue(answer.message, answer.message.contains("refused"))
    }

    @Test
    fun `list failures carry their own class`() = runBlocking {
        engine(list = MockResponse().setResponseCode(401).setBody("""{"detail":"Invalid token."}"""))
        assertEquals(EngineFailureClass.AuthRefused, bridge(client()).ask("q", false).failure)
    }

    @Test
    fun `a success carries no failure class`() = runBlocking {
        engine()
        val answer = bridge(client(), agent(say = "Milk, eggs.")).ask("what is on groceries", false)
        assertTrue(answer.success)
        assertEquals(null, answer.failure)
    }

    @Test
    fun `a transport failure on a write does not claim it certainly was not written`() = runBlocking {
        engine(call = MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        val answer = bridge(client(), agent("add_event" to JSONObject().put("title", "x"), say = "Done."))
            .ask("add x", wantsWrite = true)
        assertFalse(answer.success)
        assertTrue(answer.message, answer.message.contains("cannot be confirmed"))
    }

    @Test
    fun `car-local tables are not in the enum and a direct call is refused unsent`() = runBlocking {
        engine()
        var seenEnum = ""
        val run: suspend (String, String, List<AgentTool>) -> AgentResult = { _, _, tools ->
            val read = tools.first { it.name == "read_records" }
            seenEnum = read.params.getJSONObject("table").getJSONArray("enum").toString()
            val refused = read.run(JSONObject().put("table", "chassis_quirks"))
            AgentResult.Success(refused)
        }
        val answer = bridge(client(), run).ask("show quirks", false)
        assertEquals("[\"places\"]", seenEnum)
        assertEquals(0, callsSent())
        assertTrue(answer.message, answer.message.contains("kept on this device"))
    }

    @Test
    fun `bare object params become strings for Gemini and are parsed back for the engine`() {
        val translated = GeminiSchema.translate(
            JSONObject(
                """{"type":"object","properties":{"id":{"type":"string","format":"uuid"},""" +
                    """"fields":{"type":"object"}},"required":["id"]}""",
            ),
        )
        assertEquals("string", translated.properties.getJSONObject("fields").getString("type"))
        assertFalse(translated.properties.getJSONObject("id").has("format"))
        val restored = translated.restore(JSONObject().put("fields", """{"a":1}"""))
        assertEquals(1, restored.getJSONObject("fields").getInt("a"))
        assertEquals(listOf("id"), translated.required)
    }

    @Test
    fun `ask_engine is declared once with question and intent and the old engine tools are gone`() {
        val declarations = LiveToolbox.declarations()
        val names = (0 until declarations.length()).map { declarations.getJSONObject(it).getString("name") }
        assertEquals(1, names.count { it == "ask_engine" })
        assertFalse("list_personal_lists" in names)
        val found = (0 until declarations.length()).map { declarations.getJSONObject(it) }
            .first { it.getString("name") == "ask_engine" }
        val properties = found.getJSONObject("parameters").getJSONObject("properties")
        assertTrue(properties.has("question"))
        assertTrue(properties.has("intent"))
        val required = found.getJSONObject("parameters").getJSONArray("required").toString()
        assertTrue(required, required.contains("\"intent\""))
        assertEquals("[\"record\",\"ask\"]", properties.getJSONObject("intent").getJSONArray("enum").toString())
    }

    @Test
    fun `ask_engine description names the personal domains and is not a last resort`() {
        val d = AskEngine.DESCRIPTION.lowercase()
        for (word in listOf("grocery", "checklist", "to-dos", "calendar", "notes", "weight", "meals", "sleep", "workout", "memory")) {
            assertTrue(word, d.contains(word))
        }
        assertFalse(d.contains("no named tool"))
        val names = (0 until LiveToolbox.declarations().length())
            .map { LiveToolbox.declarations().getJSONObject(it).getString("name") }
        assertFalse("car to-do tools are retired", names.any { it.endsWith("car_task") || it == "list_car_tasks" })
    }
}

package com.kevin.legion.service

import com.kevin.legion.ai.AgentResult
import com.kevin.legion.ai.AgentTool
import com.kevin.legion.backend.engine.EngineHttp
import com.kevin.legion.backend.engine.EngineMcpClient
import com.kevin.legion.backend.engine.EngineTestSupport
import com.kevin.legion.backend.engine.EngineTestSupport.json
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * engine-mcp ticket 11. The transport is a MockEngine and the sub-agent is a fake that calls the
 * tools it is handed exactly as the real loop would, so every assertion is about what the BRIDGE
 * does: what crosses to the engine, and what is said about the outcome.
 */
@RunWith(RobolectricTestRunner::class)
class AskEngineTest {

    private val context = RuntimeEnvironment.getApplication()

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

    private fun bodyOf(request: HttpRequestData): String = (request.body as TextContent).text

    private fun engine(
        callAnswer: String = callReply("ok"),
        listStatus: HttpStatusCode = HttpStatusCode.OK,
        listBody: String = toolsList,
    ) = EngineTestSupport.RecordingEngine { request: HttpRequestData ->
        respondTo(request, callAnswer, listStatus, listBody)
    }

    private fun MockRequestHandleScope.respondTo(
        request: HttpRequestData,
        callAnswer: String,
        listStatus: HttpStatusCode,
        listBody: String,
    ): HttpResponseData {
        val method = JSONObject(bodyOf(request)).getString("method")
        return if (method == "tools/list") json(listBody, listStatus) else json(callAnswer)
    }

    private fun client(rec: EngineTestSupport.RecordingEngine): EngineMcpClient {
        val config = EngineTestSupport.signedInConfig(context)
        return EngineMcpClient(EngineHttp(config, rec.client()), config::baseUrl)
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
    ) = AskEngine(client, run, identityClause = { "You are a helper." }, nowText = { "2026-10-02T09:00:00-05:00" })

    private fun callsSent(rec: EngineTestSupport.RecordingEngine) =
        rec.requests.count { JSONObject(bodyOf(it)).getString("method") == "tools/call" }

    @Test
    fun `unreachable engine is a sentence that says nothing was read or written`() = runBlocking {
        val config = EngineTestSupport.signedInConfig(context)
        val client = EngineMcpClient(EngineHttp(config, EngineTestSupport.unreachableClient()), config::baseUrl)
        var agentRan = false
        val answer = bridge(client) { _, _, _ ->
            agentRan = true
            AgentResult.Success("you have nothing due")
        }.ask("what is due", wantsWrite = false, touchedReadThroughTool = false)
        assertFalse(answer.success)
        assertTrue(answer.message, answer.message.contains("unreachable"))
        assertTrue(answer.message, answer.message.contains("nothing was read or written"))
        assertFalse("the agent must not run with nothing to answer from", agentRan)
    }

    @Test
    fun `MCP switched off 404 says so and that nothing was read or written`() = runBlocking {
        val rec = engine(
            listStatus = HttpStatusCode.NotFound,
            listBody = """{"detail":"Nothing was read or written. This engine's MCP endpoint is switched off."}""",
        )
        val answer = bridge(client(rec)).ask("what is due", false, false)
        assertFalse(answer.success)
        assertTrue(answer.message, answer.message.contains("switched off"))
        assertTrue(answer.message, answer.message.contains("nothing was read or written"))
    }

    @Test
    fun `refused token says so and that nothing was read or written`() = runBlocking {
        val rec = engine(listStatus = HttpStatusCode.Unauthorized, listBody = """{"detail":"Invalid token."}""")
        val answer = bridge(client(rec)).ask("what is due", false, false)
        assertFalse(answer.success)
        assertTrue(answer.message, answer.message.contains("refused this device's token"))
        assertTrue(answer.message, answer.message.contains("nothing was read or written"))
    }

    @Test
    fun `list request carries the device token and the MCP envelope`() = runBlocking {
        val rec = engine()
        bridge(client(rec)).ask("what is due", false, false)
        val request = rec.requests.first()
        assertEquals("Token test-device-token", request.headers["Authorization"])
        assertEquals("/mcp", request.url.encodedPath)
        assertEquals("application/json, text/event-stream", request.headers["Accept"])
        assertEquals("tools/list", request.headers["MCP-Method"])
        assertEquals("2026-07-28", request.headers["MCP-Protocol-Version"])
        val sent = JSONObject(bodyOf(request))
        assertEquals("2.0", sent.getString("jsonrpc"))
        assertEquals(
            "2026-07-28",
            sent.getJSONObject("params").getJSONObject("_meta").getString("io.modelcontextprotocol/protocolVersion"),
        )
    }

    @Test
    fun `tools list is cached so a second question fetches it once`() = runBlocking {
        val rec = engine()
        val client = client(rec)
        bridge(client).ask("one", false, false)
        bridge(client).ask("two", false, false)
        assertEquals(1, rec.requests.count { JSONObject(bodyOf(it)).getString("method") == "tools/list" })
    }

    @Test
    fun `an isError write is relayed as a failure with the server's sentence, not the model's done`() = runBlocking {
        val rec = engine(callAnswer = callReply("Nothing was written. This device token is read-only.", isError = true))
        val answer = bridge(
            client(rec),
            agent("add_event" to JSONObject().put("title", "dentist"), say = "Done, added it."),
        ).ask("add dentist", wantsWrite = true, touchedReadThroughTool = false)
        assertFalse(answer.success)
        assertTrue(answer.message, answer.message.contains("Nothing was written. This device token is read-only."))
        assertFalse(answer.message.contains("Done, added"))
    }

    @Test
    fun `a successful write in the turn is reported as success`() = runBlocking {
        val rec = engine(callAnswer = callReply("Added event dentist (committed)."))
        val answer = bridge(
            client(rec),
            agent("add_event" to JSONObject().put("title", "dentist"), say = "Added the dentist."),
        ).ask("add dentist", wantsWrite = true, touchedReadThroughTool = false)
        assertTrue(answer.message, answer.success)
        assertEquals("Added the dentist.", answer.message)
    }

    @Test
    fun `record intent with no write committed is a failure not a summary`() = runBlocking {
        val rec = engine()
        val answer = bridge(client(rec), agent(say = "Sure, noted."))
            .ask("add dentist", wantsWrite = true, touchedReadThroughTool = false)
        assertFalse(answer.success)
        assertTrue(answer.message, answer.message.contains("did not get written"))
    }

    @Test
    fun `a write called under an ask intent is refused before anything is sent`() = runBlocking {
        val rec = engine()
        val answer = bridge(
            client(rec),
            agent("add_event" to JSONObject().put("title", "x"), say = "Added."),
        ).ask("what is due", wantsWrite = false, touchedReadThroughTool = false)
        assertEquals(0, callsSent(rec))
        assertTrue(answer.message, answer.success)
    }

    @Test
    fun `a transport failure on a write does not claim it certainly was not written`() = runBlocking {
        val config = EngineTestSupport.signedInConfig(context)
        var first = true
        val rec = EngineTestSupport.RecordingEngine { _: HttpRequestData ->
            if (first) {
                first = false
                json(toolsList)
            } else {
                throw java.io.IOException("reset")
            }
        }
        val client = EngineMcpClient(EngineHttp(config, rec.client()), config::baseUrl)
        val answer = bridge(client, agent("add_event" to JSONObject().put("title", "x"), say = "Done."))
            .ask("add x", wantsWrite = true, touchedReadThroughTool = false)
        assertFalse(answer.success)
        assertTrue(answer.message, answer.message.contains("cannot be confirmed"))
    }

    @Test
    fun `a turn that touched mail sends nothing to the engine at all`() = runBlocking {
        val rec = engine()
        val answer = bridge(client(rec))
            .ask("remind me about what the insurer wrote", false, touchedReadThroughTool = true)
        assertFalse(answer.success)
        assertEquals(AskEngine.MAIL_REFUSAL, answer.message)
        assertEquals("not even tools/list may go out", 0, rec.requests.size)
    }

    @Test
    fun `screenless tables are not in the enum and a direct call is refused unsent`() = runBlocking {
        val rec = engine()
        var seenEnum = ""
        val run: suspend (String, String, List<AgentTool>) -> AgentResult = { _, _, tools ->
            val read = tools.first { it.name == "read_records" }
            seenEnum = read.params.getJSONObject("table").getJSONArray("enum").toString()
            val refused = read.run(JSONObject().put("table", "chassis_quirks"))
            AgentResult.Success(refused)
        }
        val answer = bridge(client(rec), run).ask("show quirks", false, false)
        assertEquals("[\"places\"]", seenEnum)
        assertEquals(0, callsSent(rec))
        assertTrue(answer.message, answer.message.contains("no screen"))
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
    fun `ask_engine is declared once with question and intent`() {
        val declarations = LiveToolbox.declarations()
        val found = (0 until declarations.length()).map { declarations.getJSONObject(it) }
            .filter { it.getString("name") == "ask_engine" }
        assertEquals(1, found.size)
        val properties = found.single().getJSONObject("parameters").getJSONObject("properties")
        assertNotNull(properties.optJSONObject("question"))
        assertNotNull(properties.optJSONObject("intent"))
    }

    @Test
    fun `ask_engine is not episodic excluded because engine data is first party`() {
        assertFalse("ask_engine" in LiveToolbox.EPISODIC_EXCLUDED_TOOLS)
    }
}

package com.kevin.legion.service

import com.kevin.legion.ai.AgentResult
import com.kevin.legion.ai.AgentTool
import com.kevin.legion.ai.WebReadMark
import com.kevin.legion.backend.engine.EngineHttp
import com.kevin.legion.backend.engine.EngineMcpClient
import com.kevin.legion.backend.engine.EngineTestSupport
import com.kevin.legion.backend.engine.EngineTestSupport.json
import io.ktor.client.request.HttpRequestData
import io.ktor.http.content.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

/**
 * Integration 2026-11-10 (round 5 + watches + look): round 5's `github_status` returns issue and PR
 * titles anyone can write, and the engine marks only its own web door for it. The phone reaches it
 * through `ask_engine` over `/mcp`, which marks nothing, so a Live turn that heard a stranger's PR
 * title could still set a watch or save a memory. Now [AskEngine] notes [WebReadMark] for the
 * engine's third-party tools, the runner raises the turn's flag, and [CrossBranchReadThroughTest]'s
 * guards apply. Made-up titles only.
 */
@RunWith(RobolectricTestRunner::class)
class AskEngineReadThroughTest {

    private val context = RuntimeEnvironment.getApplication()

    private val toolsList = """{"jsonrpc":"2.0","id":1,"result":{"tools":[
        {"name":"github_status","description":"Builds and PRs.","inputSchema":{"type":"object",
          "properties":{"what":{"type":"string"}}},"annotations":{"readOnlyHint":true}},
        {"name":"read_records","description":"Reads a table.","inputSchema":{"type":"object",
          "properties":{"table":{"type":"string","enum":["places"]}},"required":["table"]},
         "annotations":{"readOnlyHint":true}}
    ]}}"""

    private val reply = JSONObject().put("jsonrpc", "2.0").put("id", 1).put(
        "result",
        JSONObject()
            .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "#7 An example title")))
            .put("isError", false),
    ).toString()

    private fun bridge(toolName: String, args: JSONObject): AskEngine {
        val rec = EngineTestSupport.RecordingEngine { request: HttpRequestData ->
            val method = JSONObject((request.body as TextContent).text).getString("method")
            if (method == "tools/list") json(toolsList) else json(reply)
        }
        val config = EngineTestSupport.signedInConfig(context)
        val client = EngineMcpClient(EngineHttp(config, rec.client()), config::baseUrl)
        val run: suspend (String, String, List<AgentTool>) -> AgentResult = { _, _, tools ->
            tools.first { it.name == toolName }.run(args)
            AgentResult.Success("Done.")
        }
        return AskEngine(
            client,
            run,
            identityClause = { "You are a helper." },
            nowText = { "2026-11-10T09:00:00-06:00" },
        )
    }

    private fun markAfter(toolName: String, args: JSONObject): Boolean = runBlocking {
        val mark = WebReadMark()
        withContext(mark) {
            bridge(toolName, args).ask("did the build pass", wantsWrite = false, touchedReadThroughTool = false)
        }
        mark.touched
    }

    @Test
    fun `an ask_engine call that read GitHub titles marks the turn read-through`() {
        assertTrue(markAfter("github_status", JSONObject().put("what", "open")))
    }

    @Test
    fun `an ask_engine call that read the household's own rows does not`() {
        assertFalse(markAfter("read_records", JSONObject().put("table", "places")))
    }

    @Test
    fun `the phone's list is the engine's THIRD_PARTY_TOOLS`() {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        var source: File? = null
        while (dir != null && source == null) {
            source = File(dir, "server/assistant/read_through.py").takeIf { it.isFile }
            dir = dir.parentFile
        }
        val text = requireNotNull(source) { "server/assistant/read_through.py not found" }.readText()
        val line = text.lines().first { it.startsWith("THIRD_PARTY_TOOLS") }
        val names = Regex("\"([a-z_]+)\"").findAll(line).map { it.groupValues[1] }.toSet()
        assertEquals(names, AskEngine.ENGINE_THIRD_PARTY_TOOLS)
    }
}

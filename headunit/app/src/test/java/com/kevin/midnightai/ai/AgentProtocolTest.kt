package com.kevin.midnightai.ai

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [AgentProtocol] against canned Gemini v1beta generateContent
 * response strings. This is the hardware-free coverage for the function-calling
 * loop's most bug-prone part: parsing parallel calls, echoing the model turn
 * verbatim, and building the functionResponse content in the exact shape the
 * API requires.
 */
class AgentProtocolTest {

    // --- declarations() ------------------------------------------------------

    @Test fun declarations_camelCase_with_params_and_required() {
        val tool = AgentTool(
            name = "get_trend",
            description = "trend",
            params = JSONObject().put("metric", JSONObject().put("type", "string")),
            required = listOf("metric"),
        ) { "" }
        val decls = AgentProtocol.declarations(listOf(tool))

        val arr = decls.getJSONArray("functionDeclarations")
        assertEquals(1, arr.length())
        val fn = arr.getJSONObject(0)
        assertEquals("get_trend", fn.getString("name"))
        assertEquals("trend", fn.getString("description"))
        val params = fn.getJSONObject("parameters")
        assertEquals("object", params.getString("type"))
        assertTrue(params.getJSONObject("properties").has("metric"))
        assertEquals("metric", params.getJSONArray("required").getString(0))
    }

    // --- functionCalls() -----------------------------------------------------

    @Test fun functionCalls_parses_two_parallel_and_ignores_text() {
        val json = """
            {"candidates":[{"content":{"role":"model","parts":[
              {"text":"let me check"},
              {"functionCall":{"name":"get_trend","args":{"metric":"coolant"}}},
              {"functionCall":{"name":"get_readiness","args":{}}}
            ]}}]}
        """.trimIndent()
        val calls = AgentProtocol.functionCalls(json)
        assertEquals(2, calls.size)
        assertEquals("get_trend", calls[0].name)
        assertEquals("coolant", calls[0].args.getString("metric"))
        assertEquals("get_readiness", calls[1].name)
    }

    @Test fun functionCalls_empty_on_text_only() {
        val json = """{"candidates":[{"content":{"parts":[{"text":"all good"}]}}]}"""
        assertTrue(AgentProtocol.functionCalls(json).isEmpty())
    }

    @Test fun functionCalls_echoes_id_when_present() {
        val json = """
            {"candidates":[{"content":{"parts":[
              {"functionCall":{"name":"get_specs","args":{},"id":"call-7"}}
            ]}}]}
        """.trimIndent()
        val calls = AgentProtocol.functionCalls(json)
        assertEquals("call-7", calls[0].id)
    }

    // --- answerText() --------------------------------------------------------

    @Test fun answerText_concatenates_multiple_text_parts() {
        val json = """
            {"candidates":[{"content":{"parts":[
              {"text":"Your coolant "},{"text":"runs a bit warm."}
            ]}}]}
        """.trimIndent()
        assertEquals("Your coolant runs a bit warm.", AgentProtocol.answerText(json))
    }

    @Test fun answerText_null_for_functionCall_only() {
        val json = """
            {"candidates":[{"content":{"parts":[
              {"functionCall":{"name":"get_specs","args":{}}}
            ]}}]}
        """.trimIndent()
        assertNull(AgentProtocol.answerText(json))
    }

    // --- blockReason() -------------------------------------------------------

    @Test fun blockReason_extracted() {
        val json = """{"promptFeedback":{"blockReason":"SAFETY"}}"""
        assertEquals("SAFETY", AgentProtocol.blockReason(json))
        assertNull(AgentProtocol.blockReason("""{"candidates":[]}"""))
    }

    // --- modelContent() ------------------------------------------------------

    @Test fun modelContent_returns_content_verbatim() {
        val json = """
            {"candidates":[{"content":{"role":"model","parts":[
              {"functionCall":{"name":"x","args":{}},"thoughtSignature":"sig123"}
            ]}}]}
        """.trimIndent()
        val content = AgentProtocol.modelContent(json)!!
        assertEquals("model", content.getString("role"))
        // The thoughtSignature must survive verbatim for multi-turn calling.
        val part = content.getJSONArray("parts").getJSONObject(0)
        assertEquals("sig123", part.getString("thoughtSignature"))
    }

    // --- functionResponseContent() -------------------------------------------

    @Test fun functionResponseContent_shape_order_and_wrapper() {
        val results = listOf(
            AgentProtocol.ToolResult("get_trend", null, JSONObject().put("result", "warm")),
            AgentProtocol.ToolResult("get_specs", "call-9", JSONObject().put("result", "2.5L")),
        )
        val content = AgentProtocol.functionResponseContent(results)
        assertEquals("user", content.getString("role"))

        val parts = content.getJSONArray("parts")
        assertEquals(2, parts.length())

        val fr0 = parts.getJSONObject(0).getJSONObject("functionResponse")
        assertEquals("get_trend", fr0.getString("name"))
        assertEquals("warm", fr0.getJSONObject("response").getString("result"))
        assertTrue(!fr0.has("id")) // no id echoed when absent

        val fr1 = parts.getJSONObject(1).getJSONObject("functionResponse")
        assertEquals("get_specs", fr1.getString("name"))
        assertEquals("call-9", fr1.getString("id")) // id echoed when present
    }

    @Test fun functionResponseContent_appends_finalNudge_as_trailing_text() {
        val results = listOf(
            AgentProtocol.ToolResult("get_trend", null, JSONObject().put("result", "ok")),
        )
        val content = AgentProtocol.functionResponseContent(results, finalNudge = "Answer now with what you have.")
        val parts = content.getJSONArray("parts")
        assertEquals(2, parts.length())
        // functionResponse first, nudge text last.
        assertTrue(parts.getJSONObject(0).has("functionResponse"))
        assertEquals("Answer now with what you have.", parts.getJSONObject(1).getString("text"))
    }
}

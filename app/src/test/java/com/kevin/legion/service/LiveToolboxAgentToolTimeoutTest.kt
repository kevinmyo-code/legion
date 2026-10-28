package com.kevin.legion.service

import com.kevin.legion.ai.AgentResult
import com.kevin.legion.ai.AgentTool
import com.kevin.legion.ai.RestOutcome
import com.kevin.legion.ai.SubAgent
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The per-tool leash inside a dispatcher (2026-10-27): specialists that run their own Gemini loop
 * are no longer cut at the plain-read 8 s, and the timeouts nest inside each other.
 */
class LiveToolboxAgentToolTimeoutTest {

    @Test
    fun `specialists get the long leash and plain reads keep 8 s`() {
        for (name in listOf("diagnose_codes", "triage_symptom", "ask_maintenance", "check_cold_start", "ask_advisor")) {
            assertEquals(name, LiveToolbox.SPECIALIST_TOOL_TIMEOUT_MS, LiveToolbox.agentToolTimeoutMs(name))
        }
        assertEquals(8_000L, LiveToolbox.agentToolTimeoutMs("get_codes"))
        assertEquals(8_000L, LiveToolbox.agentToolTimeoutMs("list_goals"))
    }

    @Test
    fun `the timeouts nest, innermost first`() {
        // Specialist's own investigate budget is 30 s.
        assertTrue(LiveToolbox.SPECIALIST_TOOL_TIMEOUT_MS > 30_000L)
        assertTrue(LiveToolbox.DISPATCH_ROUTE_BUDGET_MS > LiveToolbox.SPECIALIST_TOOL_TIMEOUT_MS)
        assertTrue(LiveSessionController.SUB_AGENT_TOOL_TIMEOUT_MS > LiveToolbox.DISPATCH_ROUTE_BUDGET_MS)
    }

    @Test
    fun `a slow specialist is not cut at 8 s while a plain read is`() = runBlocking {
        val reply = JSONObject().put(
            "candidates",
            JSONArray().put(
                JSONObject().put(
                    "content",
                    JSONObject().put("role", "model").put(
                        "parts",
                        JSONArray()
                            .put(JSONObject().put("functionCall", JSONObject().put("name", "get_codes")))
                            .put(JSONObject().put("functionCall", JSONObject().put("name", "diagnose_codes"))),
                    ),
                ),
            ),
        ).toString()
        val sub = SubAgent("sys", false, SubAgent.DEFAULT_MODEL) { _, _ -> RestOutcome.Ok(reply) }
        fun slow(name: String) = AgentTool(
            name = name,
            description = "d",
            timeoutMs = LiveToolbox.agentToolTimeoutMs(name),
            run = { delay(9_000); """{"done":true}""" },
        )

        val r = sub.route(
            "q",
            listOf(slow("get_codes"), slow("diagnose_codes")),
            budgetMs = LiveToolbox.DISPATCH_ROUTE_BUDGET_MS,
        )

        val arr = JSONArray((r as AgentResult.Success).text.substringAfter('\n'))
        assertEquals("timed out", arr.getJSONObject(0).getString("error"))
        assertTrue(arr.getJSONObject(1).getJSONObject("result").getBoolean("done"))
    }
}

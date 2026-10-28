package com.kevin.legion.ai

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Per-stage timing on [LookupProbe] (async-lookups ticket 03): [SubAgent.route] and
 * [SubAgent.investigate] fill the probe the caller hands in, timeouts included. Driven through the
 * transport seam with real delays, so the bounds are loose lower bounds, never exact values.
 */
class SubAgentProbeTest {

    private fun agent(postDelayMs: Long, vararg replies: RestOutcome): SubAgent {
        val queue = ArrayDeque(replies.toList())
        return SubAgent("sys", false, SubAgent.DEFAULT_MODEL) { _, _ ->
            delay(postDelayMs)
            queue.removeFirst()
        }
    }

    private fun ok(parts: JSONArray) = RestOutcome.Ok(
        JSONObject().put(
            "candidates",
            JSONArray().put(JSONObject().put("content", JSONObject().put("role", "model").put("parts", parts))),
        ).toString(),
    )

    private fun text(t: String) = ok(JSONArray().put(JSONObject().put("text", t)))

    private fun calls(vararg names: String) = ok(
        JSONArray().apply {
            for (n in names) {
                put(JSONObject().put("functionCall", JSONObject().put("name", n).put("args", JSONObject())))
            }
        },
    )

    private fun slowTool(name: String, ms: Long) = AgentTool(name = name, description = "d") {
        delay(ms)
        "{}"
    }

    @Test
    fun `route reports its POST, its fan-out and the tools it ran`() = runBlocking {
        val probe = LookupProbe("ask_fleet")
        val r = agent(60, calls("get_codes", "get_health"))
            .route("q", listOf(slowTool("get_codes", 80), slowTool("get_health", 80)), probe = probe)
        assertTrue(r is AgentResult.Success)
        assertEquals(listOf("get_codes", "get_health"), probe.toolNames)
        assertTrue("post ${probe.postMs}", probe.postMs >= 60)
        // Parallel: the fan-out is one tool's wait, not two.
        assertTrue("tools ${probe.toolsMs}", probe.toolsMs in 80L until 160L)
        assertTrue("total ${probe.totalMs}", probe.totalMs >= probe.postMs + probe.toolsMs)
        assertEquals(1, probe.rounds)
        assertEquals(SubAgent.DEFAULT_MODEL, probe.model)
    }

    @Test
    fun `a route with no tool call reports no fan-out`() = runBlocking {
        val probe = LookupProbe("ask_goals")
        agent(0, text("nothing to look up")).route("q", emptyList(), probe = probe)
        assertEquals(emptyList<String>(), probe.toolNames)
        assertEquals(0L, probe.toolsMs)
    }

    @Test
    fun `a route cut at its budget still reports its total and the POST it was stuck in`() = runBlocking {
        val probe = LookupProbe("ask_mail")
        val r = agent(5_000, text("too late")).route("q", emptyList(), budgetMs = 100, probe = probe)
        assertSame(AgentResult.Failed, r)
        assertTrue("total ${probe.totalMs}", probe.totalMs >= 100)
        assertTrue("post ${probe.postMs}", probe.postMs >= 100)
    }

    @Test
    fun `investigate counts its rounds`() = runBlocking {
        val probe = LookupProbe("ask_engine")
        val r = agent(0, calls("get_codes"), calls("get_codes"), text("done"))
            .investigate("", "q", listOf(slowTool("get_codes", 0)), probe = probe)
        assertEquals("done", (r as AgentResult.Success).text)
        assertEquals(3, probe.rounds)
        assertEquals(SubAgent.DEFAULT_MODEL, probe.model)
    }

    @Test
    fun `no probe means no change`() = runBlocking {
        val r = agent(0, text("fine")).route("q", emptyList())
        assertEquals(AgentResult.Success("fine"), r)
    }
}

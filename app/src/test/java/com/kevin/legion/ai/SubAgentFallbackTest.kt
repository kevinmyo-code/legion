package com.kevin.legion.ai

import com.kevin.legion.car.CarProbeLog
import com.kevin.legion.service.LiveLatency
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The sub-agent fallback model (async-lookups ticket 03): a 429 or 500/503 retries that one POST
 * once on [SubAgent.FALLBACK_MODEL]. Driven through the transport seam, which is handed the model
 * each POST is aimed at, so the switch itself is what is asserted. The log line is read back from
 * [CarProbeLog], the in-memory sink [LiveLatency.log] writes beside logcat.
 */
class SubAgentFallbackTest {

    private val models = mutableListOf<String>()
    private var logMark = 0

    @Before
    fun mark() {
        logMark = CarProbeLog.entries.value.size
    }

    @After
    fun reset() {
        KeyHealth.resetForTest(null)
    }

    private fun agent(start: String, vararg replies: RestOutcome): SubAgent {
        val queue = ArrayDeque(replies.toList())
        return SubAgent("sys", false, start) { model, _ ->
            models.add(model)
            queue.removeFirst()
        }
    }

    private fun agent(vararg replies: RestOutcome) = agent(SubAgent.DEFAULT_MODEL, *replies)

    private fun fallbackLines(): List<String> =
        CarProbeLog.entries.value.drop(logMark)
            .filter { it.tag == LiveLatency.TAG && it.message.startsWith("subagent_fallback") }
            .map { it.message }

    private fun ok(parts: JSONArray) = RestOutcome.Ok(
        JSONObject().put(
            "candidates",
            JSONArray().put(JSONObject().put("content", JSONObject().put("role", "model").put("parts", parts))),
        ).toString(),
    )

    private fun text(t: String) = ok(JSONArray().put(JSONObject().put("text", t)))

    private fun call(name: String) =
        ok(JSONArray().put(JSONObject().put("functionCall", JSONObject().put("name", name).put("args", JSONObject()))))

    private val tooMany = RestOutcome.HttpError(429, "RESOURCE_EXHAUSTED")
    private val lite = SubAgent.DEFAULT_MODEL
    private val flash = SubAgent.FALLBACK_MODEL

    @Test
    fun `429 then 200 on the fallback returns Success and logs once`() = runBlocking {
        val r = agent(tooMany, text("fine")).askTyped("", "q")
        assertEquals(AgentResult.Success("fine"), r)
        assertEquals(listOf(lite, flash), models)
        assertEquals(
            listOf("subagent_fallback from=$lite to=$flash reason=RateLimited"),
            fallbackLines(),
        )
    }

    @Test
    fun `503 falls back with reason Overloaded`() = runBlocking {
        val r = agent(RestOutcome.HttpError(503, ""), text("fine")).askTyped("", "q")
        assertTrue(r is AgentResult.Success)
        assertEquals(listOf(lite, flash), models)
        assertEquals(listOf("subagent_fallback from=$lite to=$flash reason=Overloaded"), fallbackLines())
    }

    @Test
    fun `500 falls back too`() = runBlocking {
        val r = agent(RestOutcome.HttpError(500, ""), text("fine")).askTyped("", "q")
        assertTrue(r is AgentResult.Success)
        assertEquals(listOf(lite, flash), models)
    }

    @Test
    fun `429 then 429 returns RateLimited and never tries a third model`() = runBlocking {
        val r = agent(tooMany, tooMany).askTyped("", "q")
        assertSame(AgentResult.RateLimited, r)
        assertEquals(listOf(lite, flash), models)
        assertEquals(1, fallbackLines().size)
    }

    @Test
    fun `a rejected key never falls back`() = runBlocking {
        assertSame(AgentResult.KeyInvalid, agent(RestOutcome.HttpError(401, "")).askTyped("", "q"))
        assertSame(AgentResult.KeyInvalid, agent(RestOutcome.HttpError(403, "")).askTyped("", "q"))
        assertSame(
            AgentResult.KeyInvalid,
            agent(RestOutcome.HttpError(400, "API_KEY_INVALID")).askTyped("", "q"),
        )
        assertEquals(listOf(lite, lite, lite), models)
        assertTrue(fallbackLines().isEmpty())
    }

    @Test
    fun `other 4xx never falls back`() = runBlocking {
        assertSame(AgentResult.Failed, agent(RestOutcome.HttpError(404, "")).askTyped("", "q"))
        assertEquals(listOf(lite), models)
    }

    @Test
    fun `a network failure never changes model`() = runBlocking {
        assertSame(AgentResult.Offline, agent(RestOutcome.Network).askTyped("", "q"))
        assertSame(
            AgentResult.Offline,
            agent(RestOutcome.Network).investigate("", "q", emptyList()),
        )
        assertEquals(listOf(lite, lite), models)
        assertTrue(fallbackLines().isEmpty())
    }

    @Test
    fun `a blocked prompt is a 200 and never falls back`() = runBlocking {
        val blocked = RestOutcome.Ok(
            JSONObject().put("promptFeedback", JSONObject().put("blockReason", "SAFETY")).toString(),
        )
        assertSame(AgentResult.Failed, agent(blocked).investigate("", "q", emptyList()))
        assertEquals(listOf(lite), models)
    }

    @Test
    fun `a call that starts on the fallback model never falls back`() = runBlocking {
        assertSame(AgentResult.RateLimited, agent(flash, tooMany).askTyped("", "q"))
        assertEquals(listOf(flash), models)
        assertTrue(fallbackLines().isEmpty())
    }

    @Test
    fun `ask falls back too`() = runBlocking {
        assertEquals("fine", agent(tooMany, text("fine")).ask("", "q"))
        assertEquals(listOf(lite, flash), models)
    }

    @Test
    fun `investigate stays on the fallback for the rest of the loop`() = runBlocking {
        val tool = AgentTool(name = "get_codes", description = "d") { "{}" }
        val r = agent(tooMany, call("get_codes"), text("no codes")).investigate("", "q", listOf(tool))
        assertEquals("no codes", (r as AgentResult.Success).text)
        assertEquals(listOf(lite, flash, flash), models)
        assertEquals(1, fallbackLines().size)
    }

    @Test
    fun `investigate never falls back twice in one loop`() = runBlocking {
        val tool = AgentTool(name = "get_codes", description = "d") { "{}" }
        val r = agent(tooMany, call("get_codes"), tooMany).investigate("", "q", listOf(tool))
        assertSame(AgentResult.RateLimited, r)
        assertEquals(listOf(lite, flash, flash), models)
    }

    @Test
    fun `route falls back and runs the tools the fallback picked`() = runBlocking {
        val tool = AgentTool(name = "get_codes", description = "d") { """{"codes":[]}""" }
        val r = agent(RestOutcome.HttpError(503, ""), call("get_codes")).route("q", listOf(tool))
        assertTrue(r is AgentResult.Success)
        assertEquals(listOf(lite, flash), models)
    }

    @Test
    fun `the fallback line names the lookup and the probe names the model that answered`() = runBlocking {
        val tool = AgentTool(name = "get_codes", description = "d") { "{}" }
        val probe = LookupProbe("ask_fleet")
        agent(tooMany, call("get_codes")).route("q", listOf(tool), probe = probe)
        assertEquals(
            listOf("subagent_fallback from=$lite to=$flash reason=RateLimited tool=ask_fleet"),
            fallbackLines(),
        )
        assertEquals(flash, probe.model)
        // The retry is part of the round, not a second one.
        assertEquals(1, probe.rounds)
    }

    @Test
    fun `with no fallback the probe names the requested model`() = runBlocking {
        val probe = LookupProbe("ask_engine")
        agent(text("fine")).investigate("", "q", emptyList(), probe = probe)
        assertEquals(lite, probe.model)
        assertTrue(fallbackLines().isEmpty())
    }

    @Test
    fun `the fallback is per call, not per instance`() = runBlocking {
        val sub = agent(tooMany, text("one"), text("two"))
        sub.askTyped("", "q1")
        sub.investigate("", "q2", emptyList())
        // The second call starts on the default again.
        assertEquals(listOf(lite, flash, lite), models)
    }

    @Test
    fun `fallbackReason splits codes the way classify does`() {
        assertEquals("RateLimited", GeminiRest.fallbackReason(RestOutcome.HttpError(429, "")))
        assertEquals("Overloaded", GeminiRest.fallbackReason(RestOutcome.HttpError(500, "")))
        assertEquals("Overloaded", GeminiRest.fallbackReason(RestOutcome.HttpError(503, "")))
        for (code in listOf(400, 401, 403, 404, 502)) {
            assertEquals(null, GeminiRest.fallbackReason(RestOutcome.HttpError(code, "")))
        }
    }

    @Test
    fun `fallback reason does not touch KeyHealth`() {
        KeyHealth.resetForTest(null)
        GeminiRest.fallbackReason(RestOutcome.HttpError(429, ""))
        assertEquals(null, KeyHealth.lastProblem)
    }
}

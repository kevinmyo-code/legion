package com.kevin.legion.ai

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [WebReadMark] (round 3, 2026-11-09): web text a sub-agent got back while one tool call ran is
 * noted on the mark the tool runner put around that call. The `ask_fleet` shape is the one that
 * matters: a router whose routed tool runs a nested search-grounded SubAgent (`web_lookup`), on a
 * parallel fan-out. Driven through SubAgent's transport seam, no network.
 */
class WebReadMarkTest {

    private fun ok(parts: JSONArray) = RestOutcome.Ok(
        JSONObject().put(
            "candidates",
            JSONArray().put(JSONObject().put("content", JSONObject().put("role", "model").put("parts", parts))),
        ).toString(),
    )

    private fun text(t: String) = ok(JSONArray().put(JSONObject().put("text", t)))

    private fun callsReply(vararg names: String) = ok(
        JSONArray().apply {
            for (n in names) {
                put(JSONObject().put("functionCall", JSONObject().put("name", n).put("args", JSONObject())))
            }
        },
    )

    private fun agent(useSearch: Boolean, vararg replies: RestOutcome): SubAgent {
        val queue = ArrayDeque(replies.toList())
        return SubAgent("sys", useSearch, SubAgent.DEFAULT_MODEL) { _, _ -> queue.removeFirst() }
    }

    private suspend fun marked(block: suspend () -> Unit): Boolean {
        val mark = WebReadMark()
        withContext(mark) { block() }
        return mark.touched
    }

    @Test
    fun `a search-grounded answer notes the mark`() = runBlocking {
        assertTrue(marked { agent(true, text("80 ft-lb.")).ask("", "torque spec?") })
        assertTrue(marked { agent(true, text("80 ft-lb.")).askTyped("", "torque spec?") })
        assertTrue(marked { agent(true, text("80 ft-lb.")).askWithUsage("", "torque spec?") })
    }

    @Test
    fun `an answer with no search grounding leaves the mark clean`() = runBlocking {
        assertFalse(marked { agent(false, text("Fine.")).ask("", "q") })
    }

    @Test
    fun `a grounded call that failed brought no web text back`() = runBlocking {
        assertFalse(marked { agent(true, RestOutcome.Network).ask("", "q") })
        assertFalse(marked { agent(true, RestOutcome.HttpError(404, "")).askTyped("", "q") })
    }

    @Test
    fun `web_lookup nested behind a routed tool notes the outer mark, across the parallel fan-out`() = runBlocking {
        val webLookup = agent(true, text("Known failure: the water pump."))
        val tools = listOf(
            AgentTool(name = "get_codes", description = "d") { """{"codes":[]}""" },
            AgentTool(name = "web_lookup", description = "d") { webLookup.ask("", it.optString("query")) ?: "" },
        )
        val router = agent(false, callsReply("get_codes", "web_lookup"))
        assertTrue(marked { router.route("why is it overheating?", tools) })
    }

    @Test
    fun `a routed call answered from the car's own data leaves the mark clean`() = runBlocking {
        val tools = listOf(AgentTool(name = "get_codes", description = "d") { """{"codes":["P0420"]}""" })
        assertFalse(marked { agent(false, callsReply("get_codes")).route("any codes?", tools) })
    }

    @Test
    fun `an investigate loop never grounds its own POSTs`() = runBlocking {
        val tools = listOf(AgentTool(name = "get_codes", description = "d") { "{}" })
        assertFalse(marked { agent(true, callsReply("get_codes"), text("No codes.")).investigate("", "q", tools) })
    }

    @Test
    fun `with no runner around the call, noting does nothing and does not throw`() = runBlocking {
        agent(true, text("ok")).ask("", "q")
        WebReadMark.noteWebRead()
    }

    @Test
    fun `isSearchGrounded reads the body that is sent`() {
        val grounded = agent(true).buildAskBody("", "q", null, "image/jpeg")
        val plain = agent(false).buildAskBody("", "q", null, "image/jpeg")
        assertTrue(SubAgent.isSearchGrounded(grounded))
        assertFalse(SubAgent.isSearchGrounded(plain))
        assertFalse(SubAgent.isSearchGrounded(JSONObject().put("tools", JSONArray().put(JSONObject()))))
    }
}

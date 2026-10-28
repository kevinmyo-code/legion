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
 * [SubAgent.route], router mode (2026-10-27): one POST picks the tools, the tools run, and their
 * raw results come back for the Live model to phrase. Driven through the internal transport seam,
 * so no network and no Robolectric.
 */
class SubAgentRouteTest {

    private val posts = mutableListOf<JSONObject>()

    private fun agent(vararg replies: RestOutcome): SubAgent {
        val queue = ArrayDeque(replies.toList())
        return SubAgent("sys", false, SubAgent.DEFAULT_MODEL) { _, body ->
            posts.add(body)
            queue.removeFirst()
        }
    }

    private fun callsReply(vararg calls: Pair<String, JSONObject>): RestOutcome {
        val parts = JSONArray()
        for ((name, args) in calls) {
            parts.put(JSONObject().put("functionCall", JSONObject().put("name", name).put("args", args)))
        }
        return ok(parts)
    }

    private fun textReply(text: String) = ok(JSONArray().put(JSONObject().put("text", text)))

    private fun ok(parts: JSONArray) = RestOutcome.Ok(
        JSONObject().put(
            "candidates",
            JSONArray().put(JSONObject().put("content", JSONObject().put("role", "model").put("parts", parts))),
        ).toString(),
    )

    private fun tool(name: String, timeoutMs: Long = 5_000, run: suspend (JSONObject) -> String) =
        AgentTool(name = name, description = "d", timeoutMs = timeoutMs, run = run)

    @Test
    fun `tool picked and raw results returned in one POST`() = runBlocking {
        val sub = agent(callsReply("get_codes" to JSONObject(), "get_mpg" to JSONObject()))
        val r = sub.route(
            "any codes?",
            listOf(
                tool("get_codes") { """{"codes":["P0420"]}""" },
                tool("get_mpg") { """{"mpg":21.5}""" },
            ),
        )

        assertEquals(1, posts.size)
        assertTrue(r is AgentResult.Success)
        val text = (r as AgentResult.Success).text
        val frame = "Results for the user's question \"any codes?\". Answer that question from them in " +
            "your own voice; state only what is here."
        assertTrue(text, text.startsWith(frame))
        val arr = JSONArray(text.substringAfter('\n'))
        assertEquals("get_codes", arr.getJSONObject(0).getString("tool"))
        assertEquals("P0420", arr.getJSONObject(0).getJSONObject("result").getJSONArray("codes").getString(0))
        assertEquals(21.5, arr.getJSONObject(1).getJSONObject("result").getDouble("mpg"), 0.0)
        // Tools are declared, no forced-answer toolConfig.
        assertTrue(posts[0].has("tools"))
        assertTrue(!posts[0].has("toolConfig"))
    }

    @Test
    fun `no tool call returns the model text`() = runBlocking {
        val r = agent(textReply("Nothing to look up.")).route("hello", listOf(tool("get_codes") { "{}" }))
        assertEquals(1, posts.size)
        assertEquals("Nothing to look up.", (r as AgentResult.Success).text)
    }

    @Test
    fun `failures map to the same variants as investigate`() = runBlocking {
        val tools = listOf(tool("get_codes") { "{}" })
        assertSame(AgentResult.Offline, agent(RestOutcome.Network).route("q", tools))
        // A 503 is retried once on the fallback model (async-lookups ticket 03); both answer 503.
        val unavailable = RestOutcome.HttpError(503, "")
        assertSame(AgentResult.Overloaded, agent(unavailable, unavailable).route("q", tools))
        assertSame(AgentResult.Failed, agent(RestOutcome.HttpError(404, "")).route("q", tools))
        val blocked = RestOutcome.Ok(
            JSONObject().put("promptFeedback", JSONObject().put("blockReason", "SAFETY")).toString(),
        )
        assertSame(AgentResult.Failed, agent(blocked).route("q", tools))
        assertSame(AgentResult.Failed, agent(ok(JSONArray())).route("q", tools))
        // Every requested tool failed: nothing to relay, so the caller's failure wording speaks.
        val throwing = listOf(tool("get_codes") { error("port closed") })
        assertSame(AgentResult.Failed, agent(callsReply("get_codes" to JSONObject())).route("q", throwing))
    }

    @Test
    fun `a slow tool times out on its own budget and the rest still return`() = runBlocking {
        val sub = agent(callsReply("slow" to JSONObject(), "fast" to JSONObject()))
        val r = sub.route(
            "q",
            listOf(
                tool("slow", timeoutMs = 50) { delay(5_000); "{}" },
                tool("fast") { "plain words" },
            ),
        )
        val arr = JSONArray((r as AgentResult.Success).text.substringAfter('\n'))
        assertEquals("timed out", arr.getJSONObject(0).getString("error"))
        assertEquals("plain words", arr.getJSONObject(1).getString("result"))
    }

    @Test
    fun `the whole route gives up at its budget`() = runBlocking {
        val sub = SubAgent("sys", false, SubAgent.DEFAULT_MODEL) { _, _ ->
            delay(5_000)
            textReply("too late")
        }
        assertSame(AgentResult.Failed, sub.route("q", emptyList(), budgetMs = 50))
    }

    @Test
    fun `mutating tools are reported only when they ran`() = runBlocking {
        val sub = agent(callsReply("log_x" to JSONObject()))
        val r = sub.route("q", listOf(tool("log_x") { "{}" }), mutatingToolNames = setOf("log_x"))
        assertEquals(listOf("log_x"), (r as AgentResult.Success).mutatingToolsCalled)
    }
}

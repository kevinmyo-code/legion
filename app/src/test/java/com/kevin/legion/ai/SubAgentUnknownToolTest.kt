package com.kevin.legion.ai

import com.kevin.legion.service.LiveToolbox
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-11-03, A25: the fleet router called `check_recall` (the tool is `check_recalls`), the
 * result was Failed, and the user heard "Could not reach the fleet specialist". The router WAS
 * reached. A near-miss name now maps to the real tool; a name that maps to nothing gets one retry
 * with the real names; a second miss comes back as [AgentResult.UnknownTool], spoken as what
 * actually happened.
 */
class SubAgentUnknownToolTest {

    private val posts = mutableListOf<JSONObject>()

    private fun agent(vararg replies: RestOutcome): SubAgent {
        val queue = ArrayDeque(replies.toList())
        return SubAgent("sys", false, SubAgent.DEFAULT_MODEL) { _, body ->
            posts.add(body)
            queue.removeFirst()
        }
    }

    private fun callsReply(vararg names: String): RestOutcome {
        val parts = JSONArray()
        for (name in names) {
            parts.put(JSONObject().put("functionCall", JSONObject().put("name", name).put("args", JSONObject())))
        }
        return ok(parts)
    }

    private fun ok(parts: JSONArray) = RestOutcome.Ok(
        JSONObject().put(
            "candidates",
            JSONArray().put(JSONObject().put("content", JSONObject().put("role", "model").put("parts", parts))),
        ).toString(),
    )

    private val ran = mutableListOf<String>()
    private fun tool(name: String) = AgentTool(name = name, description = "d", timeoutMs = 5_000) {
        ran.add(name)
        """{"ok":true}"""
    }

    private val fleet = listOf(tool("check_recalls"), tool("get_next_service"), tool("list_vehicles"))

    @Test
    fun `singular for plural maps to the real tool in one POST`() = runBlocking {
        val r = agent(callsReply("check_recall")).route("any recalls?", fleet)
        assertEquals(1, posts.size)
        assertEquals(listOf("check_recalls"), ran)
        assertTrue(r is AgentResult.Success)
        val arr = JSONArray((r as AgentResult.Success).text.substringAfter('\n'))
        assertEquals("check_recalls", arr.getJSONObject(0).getString("tool"))
    }

    @Test
    fun `near miss rules`() {
        val names = listOf("check_recalls", "get_next_service", "get_code", "get_codes")
        assertEquals("check_recalls", SubAgent.nearMissTool("check_recall", names))
        assertEquals("check_recalls", SubAgent.nearMissTool("Check_Recalls", names))
        assertEquals("get_next_service", SubAgent.nearMissTool("getNextService", names))
        // Two real tools normalise to the same key: never guess between them.
        assertNull(SubAgent.nearMissTool("GET_CODES_", names))
        assertNull(SubAgent.nearMissTool("recalls", names))
    }

    @Test
    fun `an unknown name gets one retry that lists the real names`() = runBlocking {
        val r = agent(callsReply("lookup_recall_db"), callsReply("check_recalls")).route("any recalls?", fleet)
        assertEquals(2, posts.size)
        val retryContents = posts[1].getJSONArray("contents").toString()
        assertTrue(retryContents, retryContents.contains("No tool named lookup_recall_db exists"))
        assertTrue(retryContents, retryContents.contains("check_recalls, get_next_service, list_vehicles"))
        assertEquals(listOf("check_recalls"), ran)
        assertTrue(r is AgentResult.Success)
    }

    @Test
    fun `a second miss is UnknownTool, never a bare Failed`() = runBlocking {
        val r = agent(callsReply("lookup_recall_db"), callsReply("recall_lookup")).route("any recalls?", fleet)
        assertEquals(2, posts.size)
        assertTrue(ran.isEmpty())
        assertTrue(r is AgentResult.UnknownTool)
        r as AgentResult.UnknownTool
        assertEquals(listOf("lookup_recall_db", "recall_lookup"), r.requested)
        assertEquals(listOf("check_recalls", "get_next_service", "list_vehicles"), r.available)
    }

    @Test
    fun `the spoken failure says what happened, not could not reach`() {
        val msg = LiveToolbox.unknownToolMessage(
            AgentResult.UnknownTool(listOf("check_recall"), listOf("check_recalls")),
            "fleet lookup",
        )
        assertEquals(
            "The fleet lookup asked for a tool that doesn't exist (check_recall), so nothing was checked. " +
                "Ask again and it will try the real tools.",
            msg,
        )
        assertFalse(msg.contains("reach"))
    }
}

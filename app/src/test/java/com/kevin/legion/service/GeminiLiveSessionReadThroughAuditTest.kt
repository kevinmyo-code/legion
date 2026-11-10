package com.kevin.legion.service

import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLooper

/**
 * Read-through observability (2026-11-09): every place [GeminiLiveSession] marks a turn
 * read-through (so `remember` is refused) writes one LIVE_AUDIT `read_through source=<...>` line,
 * naming the source only. The server messages go through the session's own private handlers, so
 * the set sites under test are the production ones, not a copy. Robolectric for the session's
 * Context and org.json.
 */
@RunWith(RobolectricTestRunner::class)
class GeminiLiveSessionReadThroughAuditTest {

    private val lines = mutableListOf<String>()
    private lateinit var session: GeminiLiveSession

    @Before
    fun setUp() {
        session = GeminiLiveSession(ApplicationProvider.getApplicationContext()) {}
        session.auditSink = { lines += it }
        session.audit.enabled = true
    }

    private fun invokePrivate(name: String, arg: JSONObject) {
        GeminiLiveSession::class.java.getDeclaredMethod(name, JSONObject::class.java)
            .apply { isAccessible = true }
            .invoke(session, arg)
        ShadowLooper.idleMainLooper()
    }

    private fun setPrivate(name: String, value: Any) {
        GeminiLiveSession::class.java.getDeclaredField(name).apply { isAccessible = true }.set(session, value)
    }

    private val readThroughLines get() = lines.filter { it.startsWith("read_through") }

    @Test
    fun `grounding logs source grounding once per turn, with no query or URL`() {
        val chunk = JSONObject().put("web", JSONObject().put("uri", "https://example.com/a"))
        val grounding = JSONObject().put(
            "groundingMetadata",
            JSONObject()
                .put("webSearchQueries", JSONArray().put("example private query"))
                .put("groundingChunks", JSONArray().put(chunk)),
        )
        assertFalse(session.readThroughToolTouchedThisTurn())
        invokePrivate("handleServerContent", grounding)
        invokePrivate("handleServerContent", grounding)
        assertTrue(session.readThroughToolTouchedThisTurn())
        assertEquals(listOf("read_through source=grounding"), lines)
    }

    @Test
    fun `a read-through tool call logs the tool's name, never its args`() {
        val call = JSONObject().put("id", "c1").put("name", "ask_mail")
            .put("args", JSONObject().put("question", "example private question"))
        invokePrivate("handleToolCall", JSONObject().put("functionCalls", JSONArray().put(call)))
        assertTrue(session.readThroughToolTouchedThisTurn())
        assertEquals(listOf("read_through source=ask_mail"), readThroughLines)
        // Logged before the tool_call line, which is the existing line that carries args.
        assertTrue(lines.first().startsWith("read_through"))
    }

    @Test
    fun `an ordinary tool call logs no read_through line`() {
        val call = JSONObject().put("id", "c1").put("name", "get_current_time").put("args", JSONObject())
        invokePrivate("handleToolCall", JSONObject().put("functionCalls", JSONArray().put(call)))
        assertFalse(session.readThroughToolTouchedThisTurn())
        assertTrue(readThroughLines.isEmpty())
    }

    @Test
    fun `a tool result carrying web text logs fleet_web and the tool`() {
        session.markToolResultReadThrough("ask_fleet")
        assertTrue(session.readThroughToolTouchedThisTurn())
        assertEquals(listOf("read_through source=fleet_web tool=ask_fleet"), lines)
    }

    @Test
    fun `a proactive raise logs source proactive`() {
        session.markTurnReadThrough()
        assertTrue(session.readThroughToolTouchedThisTurn())
        assertEquals(listOf("read_through source=proactive"), lines)
    }

    @Test
    fun `a NON_BLOCKING web result carried into the next turn logs carry there, and only there`() {
        setPrivate("nonBlockingTools", setOf("ask_fleet"))
        setPrivate("toolCalledThisTurn", true)
        session.markToolResultReadThrough("ask_fleet")
        session.audit.turnComplete()
        session.startNextTurnReadThrough()
        assertTrue(session.readThroughToolTouchedThisTurn())
        session.audit.turnComplete()
        session.startNextTurnReadThrough()
        assertFalse(session.readThroughToolTouchedThisTurn())
        assertEquals(
            listOf(
                "read_through source=fleet_web tool=ask_fleet",
                "turn_complete",
                "read_through source=carry",
                "turn_complete",
            ),
            lines,
        )
    }

    @Test
    fun `a turn with nothing carried starts clean and logs nothing`() {
        session.startNextTurnReadThrough()
        assertFalse(session.readThroughToolTouchedThisTurn())
        assertTrue(lines.isEmpty())
    }
}

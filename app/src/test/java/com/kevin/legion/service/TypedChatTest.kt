package com.kevin.legion.service

import com.kevin.legion.ai.AgentResult
import com.kevin.legion.ai.RestOutcome
import com.kevin.legion.service.ChatEntry.Kind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The new `onTyped` routing ([TypedChat], which [LiveSessionController.onTyped] forwards to): what
 * reaches the text model, what the panel shows, and what never happens. [TypedChat] is never given
 * the Live session, so "touches no socket" is structural; these tests pin the rest.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class TypedChatTest {

    private var panel = ChatTranscript()
    private val bodies = mutableListOf<JSONObject>()
    private val records = mutableListOf<TypedTurnRecord>()
    private val inFlightEvents = mutableListOf<Boolean>()
    private var crises = 0
    private var hasKey = true
    private val toolRuns = mutableListOf<String>()

    private fun textReply(t: String) = RestOutcome.Ok(
        JSONObject().put(
            "candidates",
            JSONArray().put(
                JSONObject().put(
                    "content",
                    JSONObject().put("parts", JSONArray().put(JSONObject().put("text", t))),
                ),
            ),
        ).toString(),
    )

    private fun toolCall(name: String) = RestOutcome.Ok(
        JSONObject().put(
            "candidates",
            JSONArray().put(
                JSONObject().put(
                    "content",
                    JSONObject().put(
                        "parts",
                        JSONArray().put(JSONObject().put("functionCall", JSONObject().put("name", name))),
                    ),
                ),
            ),
        ).toString(),
    )

    private fun TestScope.chat(
        gate: CompletableDeferred<Unit>? = null,
        toolResult: (String) -> JSONObject = { JSONObject().put("success", true) },
        vararg replies: RestOutcome,
    ): TypedChat {
        val queue = ArrayDeque(replies.toList())
        return TypedChat(
            scope = this,
            engine = TextChatEngine(
                transport = { body ->
                    bodies.add(JSONObject(body.toString()))
                    gate?.await()
                    queue.removeFirst()
                },
                classify = { AgentResult.Failed },
                isMutating = { name, _ -> name == "log_meal" },
                isReadThrough = { it == "ask_mail" },
            ),
            hasKey = { hasKey },
            isOnline = { true },
            isCrisis = { it.contains("end it all") },
            systemParts = { listOf("BASE") },
            declarations = { JSONArray() },
            runTool = { name, _, _, _ -> toolRuns.add(name); toolResult(name) },
            speakerName = { "Alfred" },
            onCrisis = { crises++ },
            onInFlightChanged = { inFlightEvents.add(it) },
            record = { records.add(it) },
            clock = { 42L },
            readPanel = { panel },
            updatePanel = { panel = it(panel) },
        )
    }

    @Test
    fun `a typed message is answered in text and shown, not spoken`() = runTest {
        val c = chat(replies = arrayOf(textReply("It is four.")))
        assertEquals(TypedTurnPolicy.Decision.Send, c.onTyped("  what time is it  "))
        assertTrue(c.inFlight)
        assertEquals("", panel.pendingTypedReply)
        advanceUntilIdle()
        assertFalse(c.inFlight)
        assertNull(panel.pendingTypedReply)
        assertEquals(listOf(Kind.USER, Kind.ASSISTANT), panel.entries.map { it.kind })
        assertEquals("what time is it", panel.entries[0].text)
        assertEquals(ChatEntry.Via.TYPED, panel.entries[1].via)
        assertEquals("Alfred", panel.entries[1].speaker)
        assertEquals(listOf(true, false), inFlightEvents)
        assertEquals(listOf(TypedTurnRecord(42L, "what time is it", "It is four.", readThrough = false)), records)
    }

    @Test
    fun `the panel, spoken lines included, is the history and the new message is not sent twice`() = runTest {
        panel = panel.turnComplete("what's on tomorrow", "Dentist at nine.", typed = false).sessionEnded()
        val c = chat(replies = arrayOf(textReply("Moved.")))
        c.onTyped("move it to ten")
        advanceUntilIdle()
        val contents = bodies.single().getJSONArray("contents")
        val texts = (0 until contents.length()).map {
            contents.getJSONObject(it).getJSONArray("parts").getJSONObject(0).getString("text")
        }
        assertEquals(listOf("what's on tomorrow", "Dentist at nine.", "move it to ten"), texts)
        // And the voice close did not wipe the panel.
        assertEquals("what's on tomorrow", panel.entries.first().text)
    }

    @Test
    fun `a crisis phrase shows the crisis response and sends nothing`() = runTest {
        val c = chat()
        assertEquals(TypedTurnPolicy.Decision.Crisis, c.onTyped("I want to end it all"))
        advanceUntilIdle()
        assertEquals(1, crises)
        assertTrue(bodies.isEmpty())
        assertTrue(panel.entries.isEmpty())
        assertTrue(records.isEmpty())
    }

    @Test
    fun `no key is refused in words and nothing is sent`() = runTest {
        hasKey = false
        val c = chat()
        c.onTyped("hello")
        advanceUntilIdle()
        assertTrue(bodies.isEmpty())
        val e = panel.entries.single()
        assertEquals(Kind.NOT_SENT, e.kind)
        assertEquals(TypedTurnPolicy.Refusal.NO_KEY.words, e.note)
    }

    @Test
    fun `a second message while the first is being answered is refused, not queued or cancelled`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val c = chat(gate = gate, replies = arrayOf(textReply("First.")))
        c.onTyped("one")
        runCurrent() // not advanceUntilIdle: that would run virtual time past the turn budget
        assertTrue(c.inFlight)
        assertEquals(TypedTurnPolicy.Decision.Refuse(TypedTurnPolicy.Refusal.BUSY), c.onTyped("two"))
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, bodies.size)
        assertEquals(
            listOf(Kind.USER, Kind.NOT_SENT, Kind.ASSISTANT),
            panel.entries.map { it.kind },
        )
        assertEquals("First.", panel.entries.last().text)
    }

    @Test
    fun `tools run through the shared runner and get panel lines only when they report success`() = runTest {
        val c = chat(
            toolResult = { if (it == "log_meal") JSONObject().put("success", true) else JSONObject().put("x", 1) },
            replies = arrayOf(toolCall("log_meal"), toolCall("get_current_time"), textReply("Logged.")),
        )
        c.onTyped("two eggs")
        advanceUntilIdle()
        assertEquals(listOf("log_meal", "get_current_time"), toolRuns)
        assertEquals(listOf(Kind.USER, Kind.TOOL, Kind.ASSISTANT), panel.entries.map { it.kind })
        assertEquals("Log meal", panel.entries[1].text)
    }

    @Test
    fun `a read-through turn is recorded as read-through`() = runTest {
        val c = chat(replies = arrayOf(toolCall("ask_mail"), textReply("One unread from the bank.")))
        c.onTyped("any mail")
        advanceUntilIdle()
        assertTrue(records.single().readThrough)
    }

    @Test
    fun `a failed turn says what did not happen and leaves no pending reply`() = runTest {
        val c = chat(replies = arrayOf(RestOutcome.Network))
        c.onTyped("hello")
        advanceUntilIdle()
        assertNull(panel.pendingTypedReply)
        assertEquals(
            "Not answered: couldn't reach Gemini. Check the connection and try again. Nothing was changed.",
            panel.entries.last().text,
        )
        assertFalse(c.inFlight)
        assertEquals("", records.single().replyText)
    }

    @Test
    fun `blank text does nothing at all`() = runTest {
        val c = chat()
        assertEquals(TypedTurnPolicy.Decision.Ignore, c.onTyped("   "))
        assertTrue(panel.entries.isEmpty())
        assertTrue(inFlightEvents.isEmpty())
    }
}

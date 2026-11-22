package com.kevin.legion.service

import com.kevin.legion.ai.VerbatimSpeech
import com.kevin.legion.board.BoardBackend
import com.kevin.legion.board.BoardBrief
import com.kevin.legion.board.BoardCardRow
import com.kevin.legion.board.BoardOutcome
import com.kevin.legion.board.IdeaDraft
import com.kevin.legion.board.IdeaSaveGate
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * `claude_session` `where` and `idea` (backlog intake 1122): the brief is spoken by code and the
 * model's copy holds counts only; an idea is read back by code and saved only on a clear yes in a
 * later turn, never on silence or a no. Every card and idea here is invented.
 */
@RunWith(RobolectricTestRunner::class)
class BoardActionsTest {
    private val context = RuntimeEnvironment.getApplication()
    private val fake = FakeBoard()
    private val original = BoardActions.backendFactory

    @Before
    fun setUp() {
        BoardActions.backendFactory = { fake }
        BoardActions.gate = IdeaSaveGate()
    }

    @After
    fun tearDown() {
        BoardActions.backendFactory = original
        BoardActions.gate = IdeaSaveGate()
    }

    @Test
    fun `where speaks the brief by code and hands the model counts only`() {
        val result = call(userText = "where are we", "action" to "where")
        assertEquals("Fresh. 1 in flight. Working on Pick a teapot.", VerbatimSpeech.of(result))
        val forModel = VerbatimSpeech.forModel(result)
        assertNull(VerbatimSpeech.of(forModel))
        assertFalse(forModel.toString().contains("teapot"))
        assertTrue(forModel.getString("message").startsWith("Fresh. 1 in flight."))
        // The phone's own copy still carries the words to speak.
        assertTrue(result.toString().contains("teapot"))
    }

    @Test
    fun `an idea is read back by code and nothing is saved`() {
        val result = call(userText = "add a bigger orb to the backlog", "action" to "idea", "request" to "a bigger orb")
        val readback = "Backlog idea: a bigger orb. Not scored yet. Shall I add it to the backlog?"
        assertEquals(readback, VerbatimSpeech.of(result))
        assertEquals(listOf("a bigger orb"), fake.drafted)
        assertTrue(fake.confirmed.isEmpty())
        assertFalse(result.getBoolean("saved"))
    }

    @Test
    fun `a yes in the next turn saves it, once`() {
        call(userText = "add a bigger orb to the backlog", "action" to "idea", "request" to "a bigger orb")
        val saved = call(userText = "yes", "action" to "idea", "yes" to true)
        assertEquals(VerbatimSpeech.of(saved), "Added to the backlog as queued. Nothing starts until you pick it.")
        assertEquals(listOf("idea-uuid"), fake.confirmed)
        val again = call(userText = "yes", "action" to "idea", "yes" to true)
        assertFalse(again.getBoolean("success"))
        assertEquals(1, fake.confirmed.size)
    }

    @Test
    fun `a yes in the same turn, silence or a hedge saves nothing`() {
        call(userText = "add a bigger orb, yes", "action" to "idea", "request" to "a bigger orb")
        assertFalse(call(userText = "add a bigger orb, yes", "action" to "idea", "yes" to true).getBoolean("success"))
        assertFalse(call(userText = "", "action" to "idea", "yes" to true).getBoolean("success"))
        assertFalse(call(userText = "hmm maybe", "action" to "idea", "yes" to true).getBoolean("success"))
        assertTrue(fake.confirmed.isEmpty())
    }

    @Test
    fun `a no drops it`() {
        call(userText = "add a bigger orb to the backlog", "action" to "idea", "request" to "a bigger orb")
        val dropped = call(userText = "no", "action" to "idea", "yes" to false)
        assertEquals("Dropped. Nothing was added to the backlog.", VerbatimSpeech.of(dropped))
        assertEquals(listOf("idea-uuid"), fake.dropped)
        assertFalse(call(userText = "yes", "action" to "idea", "yes" to true).getBoolean("success"))
    }

    @Test
    fun `a new idea in words wins over a yes the model filled in`() {
        call(userText = "add a bigger orb", "action" to "idea", "request" to "a bigger orb", "yes" to false)
        assertEquals(listOf("a bigger orb"), fake.drafted)
        assertTrue(fake.dropped.isEmpty())
    }

    @Test
    fun `history keeps the board's words out, even cut off`() {
        val words = "Fresh. 1 in flight. Working on Pick a teapot."
        val kept = VerbatimSpeech.redact("Here goes. $words Anything else?", words)
        assertFalse(kept.contains("teapot"))
        assertTrue(kept.contains(VerbatimSpeech.PRIVATE_HISTORY) && kept.endsWith("Anything else?"))
        val cut = VerbatimSpeech.redact("Fresh. 1 in flight. Working on Pi", words)
        assertEquals(VerbatimSpeech.PRIVATE_HISTORY, cut)
    }

    @Test
    fun `an idea is refused in a read-through turn`() {
        val json = JSONObject().put("action", "idea").put("request", "from a web page")
        val result = runBlocking {
            ClaudeSessionToolbox.dispatch(context, "claude_session", json, touchedReadThroughToolThisTurn = true)!!
        }
        assertEquals(ClaudeSessionToolbox.READ_THROUGH_REFUSAL, result.getString("message"))
        assertTrue(fake.drafted.isEmpty())
    }

    @Test
    fun `needs speaks the engine's counts by code and approves nothing`() {
        val queue = object : com.kevin.legion.board.ControlRoomBackend by NoControlRoom {
            override suspend fun queue() = BoardOutcome.Ok(
                com.kevin.legion.board.BoardQueue(1, 2, 0, "Needs you: 1 session, 2 approvals, 0 decisions.",
                    listOf("Needs you: 1 session, 2 approvals, 0 decisions."), emptyList()),
            )
        }
        val original = BoardActions.queueFactory
        BoardActions.queueFactory = { queue }
        try {
            val result = call(userText = "what needs me", "action" to "needs")
            assertEquals("Needs you: 1 session, 2 approvals, 0 decisions.", VerbatimSpeech.of(result))
            assertEquals(2, result.getInt("approvals"))
            assertTrue(result.getString("message").contains("never by voice"))
        } finally {
            BoardActions.queueFactory = original
        }
    }

    private object NoControlRoom : com.kevin.legion.board.ControlRoomBackend {
        override suspend fun board() = error("unused")
        override suspend fun answer(cardId: String, askSha256: String, choice: String, text: String) = error("unused")
        override suspend fun vote(cardId: String, vote: String) = error("unused")
        override suspend fun actions() = error("unused")
        override suspend fun decide(actionId: String, recordSha256: String, allow: Boolean) = error("unused")
        override suspend fun queue(): BoardOutcome<com.kevin.legion.board.BoardQueue> = error("unused")
    }

    private fun call(userText: String, vararg args: Pair<String, Any>): JSONObject = runBlocking {
        val json = JSONObject().apply { args.forEach { (k, v) -> put(k, v) } }
        ClaudeSessionToolbox.dispatch(context, "claude_session", json, userText = userText)!!
    }

    private class FakeBoard : BoardBackend {
        val drafted = mutableListOf<String>()
        val confirmed = mutableListOf<String>()
        val dropped = mutableListOf<String>()

        override suspend fun brief() = BoardOutcome.Ok(
            BoardBrief(
                lines = listOf("Fresh.", "1 in flight.", "Working on Pick a teapot."),
                forModel = "Fresh. 1 in flight.",
                cards = listOf(BoardCardRow("Pick a teapot", "working", "blue or green")),
            ),
        )

        override suspend fun draftIdea(text: String): BoardOutcome<IdeaDraft> {
            drafted += text
            return BoardOutcome.Ok(
                IdeaDraft("idea-uuid", text, "Backlog idea: $text. Not scored yet. Shall I add it to the backlog?"),
            )
        }

        override suspend fun confirmIdea(id: String): BoardOutcome<String> {
            confirmed += id
            return BoardOutcome.Ok("Added to the backlog as queued. Nothing starts until you pick it.")
        }

        override suspend fun dropIdea(id: String): BoardOutcome<String> {
            dropped += id
            return BoardOutcome.Ok("Dropped. Nothing was added to the backlog.")
        }
    }
}

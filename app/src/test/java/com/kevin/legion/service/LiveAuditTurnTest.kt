package com.kevin.legion.service

import com.kevin.legion.service.conversationlog.SavedTurn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Each finished turn is handed on as one [SavedTurn] (memory chain ticket 450): the person's words,
 * the assistant's spoken reply, the tools it ran. A read-through turn reaches the sink already
 * stripped (CLAUDE.md sec 7); the engine gates it again. Made-up words only.
 */
class LiveAuditTurnTest {
    private val turns = mutableListOf<SavedTurn>()
    private var ids = 0
    private var clock = 1_000L
    private val audit = LiveAudit(
        sink = {},
        isReadThroughTool = { it == "read_mail" },
        onSession = {},
        onTurn = { turns += it },
        now = { clock++ },
        newChatId = { "chat-${++ids}" },
    ).apply { enabled = true }

    private fun toolResult(id: String, name: String, json: String, ok: Boolean = true) =
        LiveAudit.ToolResult(id, name, ok, ms = 5, async = false, sent = true, responseJson = json)

    private fun say(user: String, reply: String) {
        audit.user(user, final = true)
        audit.assistant(reply, readThrough = false)
        audit.turnComplete()
    }

    @Test
    fun `a spoken turn is handed on whole when it completes`() {
        audit.sessionStart("model", emptySet())
        say("what is on my list", "Milk and eggs.")
        val t = turns.single()
        assertEquals("what is on my list", t.userText)
        assertEquals("Milk and eggs.", t.assistantText)
        assertEquals(0, t.seq)
        assertEquals("voice", t.surface)
        assertFalse(t.readThrough)
        assertTrue(t.toolCalls.isEmpty())
    }

    @Test
    fun `nothing is handed on before the turn completes`() {
        audit.sessionStart("model", emptySet())
        audit.user("what is on my list", final = true)
        audit.assistant("Milk", readThrough = false)
        assertTrue(turns.isEmpty())
    }

    @Test
    fun `turns of one session share a chat and count up`() {
        audit.sessionStart("model", emptySet())
        say("one", "a")
        say("two", "b")
        assertEquals(listOf(0, 1), turns.map { it.seq })
        assertEquals(1, turns.map { it.chatId }.toSet().size)
    }

    @Test
    fun `a new session is a new chat counting from zero`() {
        audit.sessionStart("model", emptySet())
        say("one", "a")
        audit.sessionEnd("done")
        audit.sessionStart("model", emptySet())
        say("two", "b")
        assertNotEquals(turns[0].chatId, turns[1].chatId)
        assertEquals(0, turns[1].seq)
    }

    @Test
    fun `assistant chunks join and a non final transcript chunk is not his words`() {
        audit.sessionStart("model", emptySet())
        audit.user("wha", final = false)
        audit.user("what time is it", final = true)
        audit.assistant("It is ", readThrough = false)
        audit.assistant("noon.", readThrough = false)
        audit.turnComplete()
        assertEquals("what time is it", turns.single().userText)
        assertEquals("It is noon.", turns.single().assistantText)
    }

    @Test
    fun `a tool turn keeps the spoken reply and records the call with a clipped result`() {
        audit.sessionStart("model", emptySet())
        audit.user("start a ten minute timer", final = true)
        audit.toolCall("t1", "start_timer", async = false, argsJson = """{"minutes":10}""")
        audit.toolResult(toolResult("t1", "start_timer", """{"success":true,"text":"${"x".repeat(900)}"}"""))
        audit.assistant("Ten minutes, started.", readThrough = false)
        audit.turnComplete()
        val t = turns.single()
        assertEquals("Ten minutes, started.", t.assistantText)
        val call = t.toolCalls.single()
        assertEquals("start_timer", call.name)
        assertTrue(call.ok)
        assertTrue("the result is one clipped line, not the payload", call.result!!.length <= 200)
    }

    @Test
    fun `a failed tool says so`() {
        audit.sessionStart("model", emptySet())
        audit.toolCall("t1", "start_timer", async = false, argsJson = "{}")
        audit.toolResult(toolResult("t1", "start_timer", """{"success":false,"error":"no clock"}""", ok = false))
        audit.turnComplete()
        assertFalse(turns.single().toolCalls.single().ok)
    }

    @Test
    fun `a read through tool turn keeps his words and drops the reply and the result`() {
        audit.sessionStart("model", emptySet())
        audit.user("anything from the school", final = true)
        audit.toolCall("m1", "read_mail", async = false, argsJson = "{}")
        audit.toolResult(toolResult("m1", "read_mail", """{"success":true,"text":"Trip on Friday"}"""))
        audit.assistant("The school says the trip is on Friday.", readThrough = false)
        audit.turnComplete()
        val t = turns.single()
        assertEquals("anything from the school", t.userText)
        assertNull(t.assistantText)
        assertTrue(t.readThrough)
        assertNull(t.toolCalls.single().result)
        assertFalse(t.toString().contains("Friday"))
    }

    @Test
    fun `a turn the session marked read through drops the reply`() {
        audit.sessionStart("model", emptySet())
        audit.user("look at this", final = true)
        audit.assistant("It is a menu with soup.", readThrough = true)
        audit.turnComplete()
        assertNull(turns.single().assistantText)
        assertTrue(turns.single().readThrough)
    }

    @Test
    fun `an explicit read through mark is carried`() {
        audit.sessionStart("model", emptySet())
        audit.user("what is new", final = true)
        audit.readThrough("grounding")
        audit.assistant("Some headlines.", readThrough = false)
        audit.turnComplete()
        assertTrue(turns.single().readThrough)
        assertNull(turns.single().assistantText)
    }

    @Test
    fun `the next turn is clean again`() {
        audit.sessionStart("model", emptySet())
        audit.user("any mail", final = true)
        audit.toolCall("m1", "read_mail", async = false, argsJson = "{}")
        audit.turnComplete()
        say("hello", "Hi.")
        assertFalse(turns[1].readThrough)
        assertEquals("Hi.", turns[1].assistantText)
    }

    @Test
    fun `a turn with nothing in it is not handed on`() {
        audit.sessionStart("model", emptySet())
        audit.turnComplete()
        audit.turnComplete()
        assertTrue(turns.isEmpty())
    }

    @Test
    fun `words still pending when the session ends are handed on`() {
        audit.sessionStart("model", emptySet())
        audit.user("one last thing", final = true)
        audit.sessionEnd("closed")
        assertEquals("one last thing", turns.single().userText)
    }

    @Test
    fun `audit off hands nothing on`() {
        audit.enabled = false
        audit.sessionStart("model", emptySet())
        say("one", "a")
        assertTrue(turns.isEmpty())
    }

    @Test
    fun `a typed chat is a chat of its own and a new one starts on request`() {
        audit.startChat()
        audit.user("typed hello", final = true, typed = true)
        audit.assistant("Hello.", readThrough = false)
        audit.turnComplete()
        assertEquals("typed", turns.single().surface)
        val first = turns.single().chatId
        audit.startChat()
        audit.user("later", final = true, typed = true)
        audit.turnComplete()
        assertNotEquals(first, turns[1].chatId)
    }

    @Test
    fun `a credential in speech is scrubbed before it is handed on`() {
        audit.sessionStart("model", emptySet())
        say("my key is AIzaSyA1234567890123456789012345678901", "Noted.")
        assertFalse(turns.single().userText!!.contains("AIza"))
    }

    @Test
    fun `a typed chat keeps its chat id across a long gap until a new conversation`() {
        var at = 0L
        val typed = TypedChatAudit(enabled = { true }, audit = audit, now = { at })
        fun send(text: String) {
            typed.user(text)
            typed.turn(TypedTurnRecord(turnSeq = 1L, userText = text, replyText = "ok", readThrough = false))
        }
        send("first")
        at += 5 * 60_000L
        send("second")
        at += 3 * 60 * 60_000L
        send("third")
        typed.newConversation()
        send("fourth")
        assertEquals(1, turns.take(3).map { it.chatId }.toSet().size)
        assertEquals(listOf(0, 1, 2), turns.take(3).map { it.seq })
        assertNotEquals(turns[2].chatId, turns[3].chatId)
        assertEquals(0, turns[3].seq)
    }

    @Test
    fun `a resumed socket keeps the chat id and counts on`() {
        val thread = ChatThread("chat-resumed")
        audit.continueChat(thread)
        audit.sessionStart("model", emptySet())
        say("any mail from the school", "Checking.")
        audit.sessionEnd("network drop")
        val resumed = LiveAudit(sink = {}, onSession = {}, onTurn = { turns += it }, now = { clock++ })
            .apply { enabled = true }
        resumed.continueChat(thread)
        resumed.sessionStart("model", emptySet())
        resumed.user("what else did it say", final = true)
        resumed.turnComplete()
        assertEquals(listOf("chat-resumed", "chat-resumed"), turns.map { it.chatId })
        assertEquals(listOf(0, 1), turns.map { it.seq })
    }

    @Test
    fun `a socket with no resume handle starts a fresh chat`() {
        val kept = ChatThread("old")
        val fresh = LiveSessionController.chatThreadAfterStart(kept, resuming = false)
        assertNotEquals("old", fresh.id)
        assertEquals(0, fresh.nextSeq)
        assertEquals(kept, LiveSessionController.chatThreadAfterStart(kept, resuming = true))
        assertNotEquals("old", LiveSessionController.chatThreadAfterStart(null, resuming = true).id)
    }

    @Test
    fun `a folded call carries the tool it resolved to and a plain call carries none`() {
        val folded = LiveAudit(
            sink = {},
            isReadThroughTool = { it == "ask_mail" },
            onSession = {},
            onTurn = { turns += it },
        ).apply { enabled = true }
        folded.sessionStart("model", emptySet())
        folded.user("anything from the school", final = true)
        folded.toolCall("f1", "look_up", async = false, argsJson = """{"domain":"ask_mail","question":"newest"}""")
        folded.toolResult(toolResult("f1", "look_up", """{"success":true,"text":"Trip on Friday"}"""))
        folded.toolCall("p1", "start_timer", async = false, argsJson = "{}")
        folded.toolResult(toolResult("p1", "start_timer", """{"success":true}"""))
        folded.turnComplete()
        val (lookUp, timer) = turns.single().toolCalls
        assertEquals("ask_mail", lookUp.action)
        assertNull(lookUp.result)
        assertNull(timer.action)
    }

    @Test
    fun `the whole heard line replaces the transcript chunks instead of repeating them`() {
        audit.sessionStart("model", emptySet())
        audit.user("what is on", final = true)
        audit.user("what is on my list", final = true)
        audit.user("what is on my list", final = true, whole = true)
        audit.assistant("Milk.", readThrough = false)
        audit.turnComplete()
        assertEquals("what is on my list", turns.single().userText)
    }

    @Test
    fun `without a whole line the final chunks are his words`() {
        audit.sessionStart("model", emptySet())
        audit.user("what is on", final = true)
        audit.user("my list", final = true)
        audit.turnComplete()
        assertEquals("what is on my list", turns.single().userText)
    }
}

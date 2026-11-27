package com.kevin.legion.service

import com.kevin.legion.service.conversationlog.SavedToolCall
import com.kevin.legion.service.conversationlog.SavedTurn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Each finished turn is handed on as one [SavedTurn] (memory chain ticket 450): the person's own words
 * and the NAMES of the tools it ran. The assistant's reply and a tool's result are never collected
 * (CLAUDE.md sec 7: either can carry text other people wrote). Made-up words only.
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
        assertEquals(0, t.seq)
        assertEquals("voice", t.surface)
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
    fun `a non final transcript chunk is not his words`() {
        audit.sessionStart("model", emptySet())
        audit.user("wha", final = false)
        audit.user("what time is it", final = true)
        audit.assistant("It is ", readThrough = false)
        audit.assistant("noon.", readThrough = false)
        audit.turnComplete()
        assertEquals("what time is it", turns.single().userText)
    }

    @Test
    fun `a tool turn records the call's name and that it worked, never the result`() {
        audit.sessionStart("model", emptySet())
        audit.user("start a ten minute timer", final = true)
        audit.toolCall("t1", "start_timer", async = false, argsJson = """{"minutes":10}""")
        audit.toolResult(toolResult("t1", "start_timer", """{"success":true,"text":"a long invented result"}"""))
        audit.assistant("Ten minutes, started.", readThrough = false)
        audit.turnComplete()
        val t = turns.single()
        assertEquals(listOf(SavedToolCall("start_timer", ok = true)), t.toolCalls)
        assertFalse(t.toString().contains("invented"))
        assertFalse(t.toString().contains("Ten minutes"))
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
    fun `a mail turn hands on his words and the tool name and nothing the mail said`() {
        audit.sessionStart("model", emptySet())
        audit.user("anything from the school", final = true)
        audit.toolCall("m1", "read_mail", async = false, argsJson = "{}")
        audit.toolResult(toolResult("m1", "read_mail", """{"success":true,"text":"Trip on Friday"}"""))
        audit.assistant("The school says the trip is on Friday.", readThrough = false)
        audit.turnComplete()
        val t = turns.single()
        assertEquals("anything from the school", t.userText)
        assertEquals(listOf(SavedToolCall("read_mail", ok = true)), t.toolCalls)
        assertFalse(t.toString().contains("Friday"))
    }

    @Test
    fun `a spoken reply alone leaves no turn`() {
        audit.sessionStart("model", emptySet())
        audit.assistant("It is a menu with soup.", readThrough = true)
        audit.turnComplete()
        assertTrue(turns.isEmpty())
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
    fun `a folded mail call stores only its wire name and that it worked`() {
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
        assertEquals(
            listOf(SavedToolCall("look_up", ok = true), SavedToolCall("start_timer", ok = true)),
            turns.single().toolCalls,
        )
        assertFalse(turns.single().toString().contains("Friday"))
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

    @Test
    fun `a line logged but never sent to the model is not saved or folded into the next turn`() {
        audit.sessionStart("model", emptySet())
        audit.user("remind me to call the vet", final = true, save = false)
        audit.turnComplete()
        assertTrue(turns.isEmpty())
        say("what is the weather", "Mild.")
        assertEquals("what is the weather", turns.single().userText)
    }

    @Test
    fun `a line kept once the turn goes to the model is saved`() {
        audit.sessionStart("model", emptySet())
        audit.user("what is on my list", final = true, save = false)
        audit.keepWords("what is on my list")
        audit.turnComplete()
        assertEquals("what is on my list", turns.single().userText)
    }

    @Test
    fun `a crisis line is dropped with the rest of its turn and the next turn is clean`() {
        audit.sessionStart("model", emptySet())
        audit.user("I want to", final = true)
        audit.discardWords()
        audit.user("I want to end it all", final = true)
        audit.user("I want to end it all", final = true, whole = true)
        audit.sessionEnd("crisis")
        assertTrue(turns.isEmpty())
        audit.sessionStart("model", emptySet())
        say("what is the weather", "Mild.")
        assertEquals(listOf("what is the weather"), turns.map { it.userText })
    }

    @Test
    fun `a crisis turn leaves no row at all, tool names included`() {
        audit.sessionStart("model", emptySet())
        audit.user("I want to", final = true)
        audit.toolCall("t1", "start_timer", async = false, argsJson = "{}")
        audit.toolResult(toolResult("t1", "start_timer", """{"success":true}"""))
        audit.discardWords()
        audit.toolCall("t2", "start_timer", async = false, argsJson = "{}")
        audit.toolResult(toolResult("t2", "start_timer", """{"success":true}"""))
        audit.sessionEnd("crisis")
        assertTrue(turns.isEmpty())
    }

    @Test
    fun `a merged turn saves his words once`() {
        audit.sessionStart("model", emptySet())
        audit.user("add milk", final = true, save = false)
        audit.replaceWords("add milk")
        audit.dropTurn() // the cascade merges: the words go to carry
        audit.user("add milk and eggs", final = true, save = false)
        audit.replaceWords("add milk and eggs")
        audit.turnComplete()
        assertEquals("add milk and eggs", turns.single().userText)
    }

    @Test
    fun `a heard turn replaces what a cancelled one left, tool names included`() {
        audit.sessionStart("model", emptySet())
        audit.replaceWords("what is the weather")
        audit.toolCall("t1", "get_weather", async = false, argsJson = "{}")
        audit.toolResult(toolResult("t1", "get_weather", """{"success":true}"""))
        // that turn never completed (an engine miss); he asks something else
        audit.replaceWords("add milk")
        audit.turnComplete()
        val t = turns.single()
        assertEquals("add milk", t.userText)
        assertTrue(t.toolCalls.isEmpty())
    }

    @Test
    fun `a merged turn then a crisis saves nothing`() {
        audit.sessionStart("model", emptySet())
        audit.replaceWords("I cannot do this anymore")
        audit.discardWords() // the cascade crisis branch, before shutdown
        audit.sessionEnd("crisis")
        assertTrue(turns.isEmpty())
    }

    @Test
    fun `the turn is stamped with who was signed in when it was spoken`() {
        var who: String? = "member-a@engine"
        val stamped = LiveAudit(
            sink = {},
            onSession = {},
            onTurn = { turns += it },
            identity = { who },
        ).apply { enabled = true }
        stamped.sessionStart("model", emptySet())
        stamped.replaceWords("add milk")
        who = "member-b@engine" // someone else signs in before the turn finishes
        stamped.turnComplete()
        assertEquals("member-a@engine", turns.single().owner)
    }
}

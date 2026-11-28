package com.kevin.legion.service.conversationlog

import com.kevin.legion.backend.engine.EngineFailure
import com.kevin.legion.backend.engine.EngineHttpException
import com.kevin.legion.service.LiveAudit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #450: each finished turn goes to the engine as it ends, tool turns included.
 * Issue #451: when a conversation ends, the engine is told, so it summarizes it straight away.
 */
class TurnLogTest {
    private val actions = mutableListOf<TurnAction>()
    private var sessions = 0

    private fun assembler() = TurnAssembler(
        emit = { actions += it },
        newSession = { "s${++sessions}" },
        now = { 0L },
    )

    private fun saves() = actions.filterIsInstance<TurnAction.Save>().map { it.body }

    @Test
    fun `a plain turn is saved the moment it completes`() {
        val turns = assembler()
        turns.sessionStart()
        turns.user("I start at Quanex", final = true)
        turns.user("on Monday", final = true)
        turns.assistant("Good luck ", readThroughTurn = false)
        turns.assistant("on Monday.", readThroughTurn = false)
        assertTrue(actions.isEmpty())
        turns.turnComplete()
        assertEquals(
            listOf(
                TurnBody(
                    "s1",
                    0,
                    userText = "I start at Quanex on Monday",
                    assistantText = "Good luck on Monday.",
                    tools = emptyList(),
                    spokenAt = "1970-01-01T00:00:00Z",
                ),
            ),
            saves(),
        )
    }

    @Test
    fun `a tool turn keeps the spoken reply and only the tool names`() {
        val turns = assembler()
        turns.sessionStart()
        turns.user("add milk to the list", final = true)
        turns.toolCall("lists", readThroughTool = false)
        turns.toolCall("lists", readThroughTool = false)
        turns.assistant("Milk is on the list.", readThroughTurn = false)
        turns.turnComplete()
        val body = saves().single()
        assertEquals("Milk is on the list.", body.assistantText)
        assertEquals(listOf("lists"), body.tools)
    }

    @Test
    fun `partial transcripts are ignored and turns count up within a session`() {
        val turns = assembler()
        turns.sessionStart()
        turns.user("hel", final = false)
        turns.user("hello", final = true)
        turns.turnComplete()
        turns.user("again", final = true)
        turns.turnComplete()
        assertEquals(listOf("hello" to 0, "again" to 1), saves().map { it.userText to it.turn })
        turns.sessionStart()
        turns.user("new chat", final = true)
        turns.turnComplete()
        assertEquals("s2" to 0, saves().last().let { it.session to it.turn })
    }

    @Test
    fun `a read-through turn is never sent, not even his words`() {
        val turns = assembler()
        turns.sessionStart()
        turns.user("what did Sam email me", final = true)
        turns.toolCall("search_mail", readThroughTool = true)
        turns.assistant("Sam says hello.", readThroughTurn = false)
        turns.turnComplete()
        turns.user("check the news", final = true)
        turns.readThrough()
        turns.assistant("Here's the news.", readThroughTurn = false)
        turns.turnComplete()
        turns.user("thanks", final = true)
        turns.turnComplete()
        assertEquals(listOf("thanks" to 2), saves().map { it.userText to it.turn })
    }

    @Test
    fun `a session end closes an unfinished turn`() {
        val turns = assembler()
        turns.sessionStart()
        turns.user("goodbye", final = true)
        turns.sessionEnd()
        assertEquals(listOf("goodbye"), saves().map { it.userText })
        turns.turnComplete()
        assertEquals(listOf(TurnAction.Save::class, TurnAction.End::class), actions.map { it::class })
    }

    @Test
    fun `a session end tells the engine after its last turn, with how it ended`() {
        val turns = assembler()
        turns.sessionStart()
        turns.user("hello", final = true)
        turns.turnComplete()
        turns.sessionEnd("idle")
        assertEquals(TurnAction.End(EndBody("s1", "timeout")), actions.last())
        turns.sessionStart()
        turns.user("again", final = true)
        turns.sessionEnd("stopped")
        assertEquals(TurnAction.End(EndBody("s2", "closed")), actions.last())
        assertTrue(actions[actions.size - 2] is TurnAction.Save)
    }

    @Test
    fun `a session that sent no turn sends no end`() {
        val turns = assembler()
        turns.sessionStart()
        turns.sessionEnd("idle")
        turns.sessionStart()
        turns.user("what did Sam email me", final = true)
        turns.toolCall("search_mail", readThroughTool = true)
        turns.assistant("Sam says hello.", readThroughTurn = false)
        turns.sessionEnd("stopped")
        assertTrue(actions.isEmpty())
    }

    @Test
    fun `a crisis conversation is forgotten and sends no end`() {
        val turns = assembler()
        turns.sessionStart()
        turns.user("rough day", final = true)
        turns.turnComplete()
        turns.user("I want to die", final = true)
        turns.sessionEnd("crisis")
        assertEquals(listOf(TurnAction.Save::class, TurnAction.Forget::class), actions.map { it::class })
    }

    @Test
    fun `close reasons map to the engine's words`() {
        listOf("idle", "warm expired", "follow_up", "missed_twice").forEach {
            assertEquals(it, "timeout", TurnAssembler.endedBy(it))
        }
        listOf("failure http 500: x", "stale socket", "closed code 1011", "goAway", "engine_unreachable")
            .forEach { assertEquals(it, "dropped", TurnAssembler.endedBy(it)) }
        listOf("stopped", "hung_up", "destroyed", "superseded", "sleep_phrase", "").forEach {
            assertEquals(it, "closed", TurnAssembler.endedBy(it))
        }
    }

    @Test
    fun `a crisis phrase forgets the conversation and saves nothing more of it`() {
        val turns = assembler()
        turns.sessionStart()
        turns.user("rough day", final = true)
        turns.turnComplete()
        turns.user("I want to die", final = true)
        turns.turnComplete()
        turns.user("still here", final = true)
        turns.turnComplete()
        assertEquals(
            listOf(TurnAction.Save::class, TurnAction.Forget::class, TurnAction.Forget::class),
            actions.map { it::class },
        )
        assertEquals("s1", (actions[1] as TurnAction.Forget).session)
        turns.sessionStart()
        turns.user("play jazz", final = true)
        turns.turnComplete()
        assertEquals("s2", saves().last().session)
    }

    @Test
    fun `typed chat with no session events starts a new conversation after a crisis forget`() {
        val turns = assembler()
        turns.user("hello", final = true)
        turns.turnComplete()
        turns.user("I want to die", final = true)
        turns.turnComplete()
        turns.user("hello again", final = true)
        turns.turnComplete()
        assertEquals(listOf("s1", "s2"), saves().map { it.session })
        assertEquals(TurnAction.Forget("s1"), actions[1])
    }

    @Test
    fun `credentials are scrubbed from what is sent`() {
        val turns = assembler()
        turns.user("my key is AIzaSyA1234567890abcdefghijklmnop", final = true)
        turns.turnComplete()
        assertEquals("my key is [key]", saves().single().userText)
    }

    @Test
    fun `LiveAudit feeds turns whether or not its log is on, and a test conversation saves none`() {
        val audit = LiveAudit(
            sink = {},
            isReadThroughTool = { it == "search_mail" },
            onSession = {},
            turns = { actions += it },
        )
        audit.sessionStart("m", emptySet())
        audit.user("add eggs", final = true)
        audit.toolCall("t1", "lists", async = false, argsJson = "{}")
        audit.assistant("Eggs are on the list.", readThrough = false)
        audit.turnComplete()
        audit.user("read my mail", final = true)
        audit.toolCall("t2", "search_mail", async = false, argsJson = "{}")
        audit.assistant("You have two.", readThrough = false)
        audit.turnComplete()
        audit.sessionEnd("done")
        assertEquals(listOf("Eggs are on the list."), saves().map { it.assistantText })
        assertEquals(listOf("lists"), saves().single().tools)

        val silent = LiveAudit(sink = {}, onSession = {}, turns = null)
        silent.user("hello", final = true)
        silent.turnComplete()
        assertEquals(1, saves().size)
    }

    @Test
    fun `the body is the engine's field names`() {
        val body = TurnBody("s", 2, userText = "u", assistantText = "a", tools = listOf("x"), spokenAt = "t")
        val json = turnJson.encodeToString(TurnBody.serializer(), body)
        val fields = Json.parseToJsonElement(json).jsonObject
        assertEquals(
            setOf("session", "turn", "source", "user_text", "assistant_text", "tools", "spoken_at"),
            fields.keys,
        )
        assertEquals("phone", fields["source"]!!.jsonPrimitive.content)
        val end = Json.parseToJsonElement(turnJson.encodeToString(EndBody.serializer(), EndBody("s", "timeout")))
        assertEquals(setOf("session", "ended_by"), end.jsonObject.keys)
    }

    private class FakeTransport(var result: TurnSendResult = TurnSendResult.SENT) : TurnTransport {
        val calls = mutableListOf<String>()
        override suspend fun save(json: String): TurnSendResult {
            calls += "save:" + Json.parseToJsonElement(json).jsonObject["turn"]!!.jsonPrimitive.content
            return result
        }
        override suspend fun forget(session: String): TurnSendResult {
            calls += "forget:$session"
            return result
        }
        override suspend fun end(json: String): TurnSendResult {
            calls += "end:" + Json.parseToJsonElement(json).jsonObject["session"]!!.jsonPrimitive.content
            return result
        }
    }

    private fun save(turn: Int, session: String = "s") =
        TurnAction.Save(
            TurnBody(session, turn, userText = "u", assistantText = "", tools = emptyList(), spokenAt = "t"),
        )

    @Test
    fun `an offline turn waits and goes first, in order, once the engine answers`() = runBlocking {
        val transport = FakeTransport(TurnSendResult.RETRY)
        val sender = TurnSender(transport)
        sender.send(save(0))
        sender.send(save(1))
        assertEquals(2, sender.waiting)
        transport.result = TurnSendResult.SENT
        transport.calls.clear()
        sender.send(save(2))
        assertEquals(listOf("save:0", "save:1", "save:2"), transport.calls)
        assertEquals(0, sender.waiting)
    }

    @Test
    fun `a refused turn is dropped, never retried`() = runBlocking {
        val transport = FakeTransport(TurnSendResult.DROP)
        val sender = TurnSender(transport)
        sender.send(save(0))
        assertEquals(0, sender.waiting)
    }

    @Test
    fun `a forget removes queued saves of its conversation first`() = runBlocking {
        val transport = FakeTransport(TurnSendResult.RETRY)
        val sender = TurnSender(transport)
        sender.send(save(0, "a"))
        sender.send(save(0, "b"))
        sender.send(TurnAction.Forget("a"))
        transport.result = TurnSendResult.SENT
        transport.calls.clear()
        sender.send(save(1, "b"))
        assertEquals(listOf("save:0", "forget:a", "save:1"), transport.calls)
    }

    @Test
    fun `an end goes behind its conversation's turns, and a forget drops it`() = runBlocking {
        val transport = FakeTransport(TurnSendResult.RETRY)
        val sender = TurnSender(transport)
        sender.send(save(0, "a"))
        sender.send(TurnAction.End(EndBody("a", "closed")))
        sender.send(save(0, "b"))
        sender.send(TurnAction.End(EndBody("b", "timeout")))
        sender.send(TurnAction.Forget("b"))
        transport.result = TurnSendResult.SENT
        transport.calls.clear()
        sender.send(save(0, "c"))
        assertEquals(listOf("save:0", "end:a", "forget:b", "save:0"), transport.calls)
    }

    @Test
    fun `the queue is bounded`() = runBlocking {
        val sender = TurnSender(FakeTransport(TurnSendResult.RETRY), maxPending = 3)
        repeat(5) { sender.send(save(it)) }
        assertEquals(3, sender.waiting)
    }

    @Test
    fun `engine failures map to retry or drop`() {
        fun of(failure: EngineFailure) = EngineTurnTransport.resultOf(EngineHttpException(failure))
        assertEquals(TurnSendResult.RETRY, of(EngineFailure.Unreachable("h", "m")))
        assertEquals(TurnSendResult.RETRY, of(EngineFailure.Unauthorized("s", status = 401)))
        assertEquals(TurnSendResult.DROP, of(EngineFailure.Unauthorized("s", status = 403)))
        assertEquals(TurnSendResult.DROP, of(EngineFailure.Refused(400, "bad")))
        assertEquals(TurnSendResult.RETRY, of(EngineFailure.Refused(429, "slow down")))
        assertEquals(TurnSendResult.RETRY, of(EngineFailure.Refused(503, "down")))
    }
}

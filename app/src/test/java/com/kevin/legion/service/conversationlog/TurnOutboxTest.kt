package com.kevin.legion.service.conversationlog

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The per-turn save queue (memory chain ticket 450, ADR 0044): a turn that cannot reach the engine
 * is kept in order, sent again later, and said so in words. Offline is never an error.
 */
class TurnOutboxTest {
    @get:Rule val tmp = TemporaryFolder()

    private class Fake(var usable: Boolean = true) : TurnTransport {
        /** Who the phone is signed in as; a test changes it to model a sign-in as someone else. */
        var who: String? = "member-a@engine"
        val sent = mutableListOf<JSONObject>()
        var script: (JSONObject) -> PostResult = { PostResult.Ok(201) }

        override fun isUsable() = usable

        override fun identity() = who

        override suspend fun post(json: String): PostResult {
            val body = JSONObject(json)
            val result = script(body)
            if (result is PostResult.Ok) sent += body
            return result
        }
    }

    private fun turn(seq: Int, user: String = "line $seq") = SavedTurn(
        chatId = "11111111-1111-1111-1111-111111111111",
        seq = seq,
        surface = "voice",
        userText = user,
        toolCalls = listOf(SavedToolCall("start_timer", ok = true)),
        spokenAtMs = 1_700_000_000_000L,
    )

    private fun box(fake: Fake, max: Int = 500) = TurnOutbox(tmp.root.resolve("turns.jsonl"), fake, max = max)

    private fun seqs(fake: Fake) = fake.sent.map { it.getInt("seq") }

    @Test fun `a turn is sent once and leaves the queue`() = runBlocking {
        val fake = Fake()
        val box = box(fake)
        box.enqueue(turn(0))
        val outcome = box.drain()
        assertEquals(1, fake.sent.size)
        assertEquals(0, box.pending())
        assertTrue(outcome.sentence, outcome is TurnOutbox.Outcome.Sent)
    }

    @Test fun `the body names the chat, the place in it, the words and the tools`() = runBlocking {
        val fake = Fake()
        val box = box(fake)
        box.enqueue(turn(2))
        box.drain()
        val body = fake.sent.single()
        assertEquals("11111111-1111-1111-1111-111111111111", body.getString("chat_id"))
        assertEquals(2, body.getInt("seq"))
        assertEquals("voice", body.getString("surface"))
        assertEquals("line 2", body.getString("user_text"))
        assertEquals("start_timer", body.getJSONArray("tool_calls").getJSONObject(0).getString("name"))
        assertTrue(body.has("spoken_at"))
    }

    @Test fun `offline keeps every turn in order and says so in words`() = runBlocking {
        val fake = Fake().apply { script = { PostResult.Unreachable("no route") } }
        val box = box(fake)
        box.enqueue(turn(0))
        box.enqueue(turn(1))
        val outcome = box.drain()
        assertEquals(2, box.pending())
        assertTrue(outcome is TurnOutbox.Outcome.Kept)
        assertTrue(outcome.sentence, outcome.sentence.contains("kept on this phone", ignoreCase = true))
    }

    @Test fun `the kept turns go in order when the engine is back`() = runBlocking {
        val fake = Fake().apply { script = { PostResult.Unreachable("no route") } }
        val box = box(fake)
        (0..2).forEach { box.enqueue(turn(it)) }
        box.drain()
        fake.script = { PostResult.Ok(201) }
        box.drain()
        assertEquals(listOf(0, 1, 2), seqs(fake))
        assertEquals(0, box.pending())
    }

    @Test fun `a failure stops the drain at that turn`() = runBlocking {
        val fake = Fake().apply {
            script = { if (it.getInt("seq") == 1) PostResult.Http(503, "down") else PostResult.Ok(201) }
        }
        val box = box(fake)
        (0..2).forEach { box.enqueue(turn(it)) }
        box.drain()
        assertEquals(listOf(0), seqs(fake))
        assertEquals(2, box.pending())
    }

    @Test fun `a turn the engine refuses is dropped and the rest still go`() = runBlocking {
        val fake = Fake().apply {
            script = { if (it.getInt("seq") == 0) PostResult.Http(400, "Nothing was saved.") else PostResult.Ok(201) }
        }
        val box = box(fake)
        (0..1).forEach { box.enqueue(turn(it)) }
        val outcome = box.drain()
        assertEquals(listOf(1), seqs(fake))
        assertEquals(0, box.pending())
        assertTrue(outcome.sentence, outcome.sentence.contains("refused", ignoreCase = true))
    }

    @Test fun `signed out keeps the turns`() = runBlocking {
        val fake = Fake().apply { script = { PostResult.Http(401, "no token") } }
        val box = box(fake)
        box.enqueue(turn(0))
        box.drain()
        assertEquals(1, box.pending())
    }

    @Test fun `no engine address sends nothing and loses nothing`() = runBlocking {
        val fake = Fake(usable = false)
        val box = box(fake)
        box.enqueue(turn(0))
        val outcome = box.drain()
        assertEquals(1, box.pending())
        assertTrue(outcome is TurnOutbox.Outcome.NoEngine)
    }

    @Test fun `the queue survives the process`() = runBlocking {
        val first = box(Fake(usable = false))
        first.enqueue(turn(0))
        val fake = Fake()
        val second = box(fake)
        assertEquals(1, second.pending())
        second.drain()
        assertEquals(listOf(0), seqs(fake))
    }

    @Test fun `the oldest turns go first when the queue is full`() = runBlocking {
        val fake = Fake(usable = false)
        val box = box(fake, max = 3)
        (0..4).forEach { box.enqueue(turn(it)) }
        assertEquals(3, box.pending())
        fake.usable = true
        box.drain()
        assertEquals(listOf(2, 3, 4), seqs(fake))
    }

    @Test fun `an empty queue has nothing to send`() = runBlocking {
        assertTrue(box(Fake()).drain() is TurnOutbox.Outcome.NothingToSend)
    }

    @Test fun `a newline in speech cannot split a stored turn`() = runBlocking {
        val fake = Fake()
        val box = box(fake)
        box.enqueue(turn(0, user = "line one\nline two"))
        box.drain()
        assertEquals("line one\nline two", fake.sent.single().getString("user_text"))
    }

    @Test fun `an engine without the route keeps the queue and says it needs updating`() = runBlocking {
        val fake = Fake().apply { script = { PostResult.Http(404, "Not Found") } }
        val box = box(fake)
        (0..1).forEach { box.enqueue(turn(it)) }
        val outcome = box.drain()
        assertEquals(2, box.pending())
        assertTrue(outcome is TurnOutbox.Outcome.Kept)
        assertTrue(outcome.sentence, outcome.sentence.contains("needs updating"))
    }

    @Test fun `the kept turns go once the engine has the route`() = runBlocking {
        val fake = Fake().apply { script = { PostResult.Http(404, "Not Found") } }
        val box = box(fake)
        (0..2).forEach { box.enqueue(turn(it)) }
        box.drain()
        fake.script = { PostResult.Ok(201) }
        box.drain()
        assertEquals(listOf(0, 1, 2), seqs(fake))
    }

    @Test fun `a conflict on one turn drops only that turn and the rest still go`() = runBlocking {
        val fake = Fake().apply {
            script = { if (it.getInt("seq") == 0) PostResult.Http(409, "Nothing was saved.") else PostResult.Ok(201) }
        }
        val box = box(fake)
        (0..2).forEach { box.enqueue(turn(it)) }
        val outcome = box.drain()
        assertEquals(listOf(1, 2), seqs(fake))
        assertEquals(0, box.pending())
        assertEquals(TurnOutbox.Outcome.Sent(2, 1), outcome)
    }

    @Test fun `the wire body is his words and tool names only`() = runBlocking {
        val fake = Fake()
        val box = box(fake)
        box.enqueue(turn(0))
        box.drain()
        val body = fake.sent.single()
        assertEquals(
            setOf("chat_id", "seq", "surface", "user_text", "tool_calls", "spoken_at"),
            body.keys().asSequence().toSet(),
        )
        val call = body.getJSONArray("tool_calls").getJSONObject(0)
        assertEquals(setOf("name", "ok"), call.keys().asSequence().toSet())
    }

    @Test fun `a full queue says how many turns it threw away`() {
        val box = box(Fake(usable = false), max = 3)
        val cuts = (0..4).map { box.enqueue(turn(it)) }
        assertEquals(listOf(0, 0, 0, 1, 1), cuts)
    }

    @Test fun `clear throws the queue away`() = runBlocking {
        val fake = Fake()
        val box = box(fake)
        (0..2).forEach { box.enqueue(turn(it)) }
        box.clear()
        assertEquals(0, box.pending())
        assertTrue(box.drain() is TurnOutbox.Outcome.NothingToSend)
    }

    @Test fun `offline reads as a count and a reason in plain words`() = runBlocking {
        val fake = Fake().apply { script = { PostResult.Unreachable("no route") } }
        val box = box(fake)
        (0..1).forEach { box.enqueue(turn(it)) }
        assertEquals(
            "2 chat turns not saved yet: the engine could not be reached (no route). " +
                "They are kept on this phone and will be tried again.",
            box.drain().sentence,
        )
    }

    @Test fun `turns queued as one member are never sent under another sign-in and go when they return`() =
        runBlocking {
            val fake = Fake(usable = false)
            val box = box(fake)
            (0..1).forEach { box.enqueue(turn(it)) }
            fake.usable = true
            fake.who = "member-b@engine"
            val outcome = box.drain()
            assertTrue(outcome is TurnOutbox.Outcome.NothingToSend)
            assertTrue(fake.sent.isEmpty())
            assertEquals(2, box.pending())
            assertEquals(2, box.heldForOthers())
            fake.who = "member-a@engine"
            box.drain()
            assertEquals(listOf(0, 1), seqs(fake))
            assertEquals(0, box.pending())
        }

    @Test fun `the second member own turns go while the first member waits`() = runBlocking {
        val fake = Fake(usable = false)
        val box = box(fake)
        box.enqueue(turn(0))
        fake.who = "member-b@engine"
        box.enqueue(turn(1))
        fake.usable = true
        box.drain()
        assertEquals(listOf(1), seqs(fake))
        assertEquals(1, box.heldForOthers())
        assertEquals(1, box.pending())
    }

    @Test fun `the same member on another engine address is another sign-in`() = runBlocking {
        val fake = Fake(usable = false)
        val box = box(fake)
        box.enqueue(turn(0))
        fake.usable = true
        fake.who = "member-a@other-engine"
        box.drain()
        assertTrue(fake.sent.isEmpty())
        assertEquals(1, box.heldForOthers())
    }

    @Test fun `a line with no owner from an older queue is dropped and counted`() = runBlocking {
        val fake = Fake()
        val file = tmp.root.resolve("turns.jsonl")
        file.writeText(turn(0).toJson() + "\n")
        val box = TurnOutbox(file, fake)
        box.drain()
        assertTrue(fake.sent.isEmpty())
        assertEquals(0, box.pending())
        assertEquals(1, box.takeRefused())
    }

    @Test fun `signed out nothing can be queued`() {
        val fake = Fake().apply { who = null }
        val box = box(fake)
        try {
            box.enqueue(turn(0))
            org.junit.Assert.fail("expected NotSignedIn")
        } catch (_: TurnOutbox.NotSignedIn) {
            assertEquals(0, box.pending())
        }
    }

    @Test fun `a drain does not send once sending is switched off`() = runBlocking {
        val fake = Fake()
        val box = box(fake)
        box.enqueue(turn(0))
        box.drain(canSend = { false })
        assertTrue(fake.sent.isEmpty())
    }
}

package com.kevin.legion.service.conversationlog

import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * What the person is told about saving chat turns (memory chain ticket 450, CLAUDE.md section 7):
 * a turn kept for later, a turn that could not be saved, in words, and never silently.
 */
class TurnSaverTest {
    @get:Rule val tmp = TemporaryFolder()

    private class Fake(var usable: Boolean = true, var script: () -> PostResult = { PostResult.Ok(201) }) : TurnTransport {
        override fun isUsable() = usable

        override suspend fun post(json: String): PostResult = script()
    }

    private val logged = mutableListOf<Pair<String, Throwable?>>()

    private fun turn(seq: Int) = SavedTurn(
        chatId = "11111111-1111-1111-1111-111111111111",
        seq = seq,
        surface = "voice",
        userText = "line $seq",
        assistantText = "reply $seq",
        toolCalls = emptyList(),
        readThrough = false,
        spokenAtMs = 1_700_000_000_000L,
    )

    private fun saver(box: TurnOutbox, keep: Boolean = true) = TurnSaver(
        box = box,
        writer = { it.run() },
        scope = CoroutineScope(Dispatchers.Unconfined),
        keep = { keep },
        log = { message, error -> logged += message to error },
    )

    private fun box(fake: Fake) = TurnOutbox(tmp.root.resolve("turns.jsonl"), fake)

    @Test fun `a queued turn the engine cannot take shows in words`() {
        val fake = Fake().apply { script = { PostResult.Unreachable("no route") } }
        val saver = saver(box(fake))
        saver.save(turn(0))
        saver.save(turn(1))
        val status = saver.status.value
        assertNotNull(status)
        assertTrue(status!!, status.startsWith("2 chat turns not saved yet"))
        assertTrue(status, status.contains("kept on this phone"))
    }

    @Test fun `the words go away once the turns are saved`() = runBlocking {
        val fake = Fake().apply { script = { PostResult.Unreachable("no route") } }
        val saver = saver(box(fake))
        saver.save(turn(0))
        fake.script = { PostResult.Ok(201) }
        saver.drain()
        assertEquals("Saved 1 chat turn.", saver.status.value)
    }

    @Test fun `a refused turn stays in the words for the rest of the run`() = runBlocking {
        val fake = Fake().apply { script = { PostResult.Http(400, "Nothing was saved.") } }
        val saver = saver(box(fake))
        saver.save(turn(0))
        fake.script = { PostResult.Ok(201) }
        saver.save(turn(1))
        assertEquals(1, saver.lostTurns)
        val status = saver.status.value!!
        assertTrue(status, status.contains("Saved 1 chat turn."))
        assertTrue(status, status.contains("1 chat turn could not be saved this run."))
    }

    @Test fun `a turn that cannot be written down is counted, logged with its error and shown`() {
        val unwritable = tmp.newFile("blocker")
        val box = TurnOutbox(unwritable.resolve("turns.jsonl"), Fake())
        val saver = saver(box)
        saver.save(turn(0))
        assertEquals(1, saver.lostTurns)
        assertTrue(saver.status.value!!, saver.status.value!!.contains("could not be saved"))
        assertTrue(logged.any { it.second != null })
    }

    @Test fun `a failing queue read is said in words not swallowed`() = runBlocking {
        val failing = object : TurnTransport {
            override fun isUsable(): Boolean = throw IOException("disk gone")

            override suspend fun post(json: String): PostResult = PostResult.Ok(201)
        }
        val box = TurnOutbox(tmp.root.resolve("turns.jsonl"), failing)
        box.enqueue(turn(0))
        val saver = saver(box)
        saver.drain()
        assertTrue(saver.status.value!!, saver.status.value!!.contains("could not be sent"))
        assertTrue(logged.any { it.second is IOException })
    }

    @Test fun `with the log off nothing is queued`() {
        val box = box(Fake())
        saver(box, keep = false).save(turn(0))
        assertEquals(0, box.pending())
    }

    @Test fun `switching the log off discards what was waiting`() {
        val fake = Fake().apply { script = { PostResult.Unreachable("no route") } }
        val box = box(fake)
        val saver = saver(box)
        saver.save(turn(0))
        assertEquals(1, box.pending())
        saver.discardQueued()
        assertEquals(0, box.pending())
        assertNull(saver.status.value)
    }
}

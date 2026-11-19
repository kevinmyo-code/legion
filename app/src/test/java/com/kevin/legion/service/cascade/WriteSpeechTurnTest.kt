@file:Suppress("MaxLineLength") // invented JSON and wire frames read best on one line

package com.kevin.legion.service.cascade

import com.kevin.legion.ai.AgentResult
import com.kevin.legion.service.TextToolExecutor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Collections

/**
 * What he HEARS after a write, turn by turn (review 2026-11-19: the session's wiring was only
 * checked as source text). Each test drives the real loop ([GeminiTextLoop] with the session's
 * `writeFact`), the real speaker ([ReplySpeaker]) on a fake voice that records every clip it
 * plays, and the conversation's one owed list ([OwedWrites]), joined the way [CascadeSession] joins
 * them: model text to `speaker.text`, code's line to [OwedWrites.line], every new reply opened
 * with [OwedWrites.open], every turn's end through [OwedWrites.endTurn], every close through
 * [OwedWrites.writeDown]. Invented content only; Robolectric only for `org.json`.
 */
@RunWith(RobolectricTestRunner::class)
class WriteSpeechTurnTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val prefix = CascadePrefix("model-x", "Invented instruction.", JSONArray())
    private val contents = JSONArray().put(
        JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", "invented"))),
    )
    private val milk = "Done: added milk to Groceries."

    // --- a voice that records what played -----------------------------------------------------------

    /** Every clip is one frame per word; [failFor] fails a clip, [slowFor] plays it slowly. */
    private class Voice(val failFor: Set<String> = emptySet(), val slowFor: Set<String> = emptySet()) : TextToSpeech {
        override val sampleRate = 24_000
        override val streamsText = false
        val played = Collections.synchronizedList(mutableListOf<String>())
        private var nextId = 0
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        inner class Clip(override val id: Int, override val text: String) : StreamingSpeechClip {
            override val frames = Channel<ByteArray>(Channel.UNLIMITED)
            private val done = CompletableDeferred<TtsOutcome>()

            init {
                scope.launch {
                    val t = text.trim()
                    if (t in failFor) {
                        done.complete(TtsOutcome.Failed("internal"))
                        frames.close()
                        return@launch
                    }
                    for (w in t.split(" ")) {
                        if (done.isCompleted) break
                        if (t in slowFor) delay(150)
                        frames.trySend(w.toByteArray())
                    }
                    if (done.complete(TtsOutcome.Done(10, 5))) frames.close()
                }
            }

            override fun append(text: String) = Unit
            override fun finish() = Unit
            override fun cancel() {
                if (done.complete(TtsOutcome.Cancelled)) frames.close()
            }
            override suspend fun outcome() = done.await()
        }

        override fun speak(text: String): SpeechClip = Clip(nextId++, text)
        override fun openStream(): StreamingSpeechClip = error("not a streaming voice")
    }

    /** What reached the ear, word by word, joined. */
    private class Ear {
        val words = Collections.synchronizedList(mutableListOf<String>())
        fun heard() = words.joinToString(" ")
    }

    private fun speaker(voice: Voice, ear: Ear) = ReplySpeaker(
        tts = voice, scope = scope, play = { ear.words.add(String(it)); true }, firstFrameTimeoutMs = 1_000,
        onFirstAudio = { _, _ -> }, onClipStarted = {}, normalise = { it },
    )

    // --- the model ------------------------------------------------------------------------------------

    private fun parts(vararg p: JSONObject) = JSONObject().put(
        "candidates",
        JSONArray().put(JSONObject().put("content", JSONObject().put("role", "model").put("parts", JSONArray(p.toList())))),
    )
    private fun text(t: String) = parts(JSONObject().put("text", t))
    private fun add(item: String) = parts(
        JSONObject().put("functionCall", JSONObject().put("name", "manage_item").put("args", JSONObject().put("action", "add").put("item", item))),
    )

    private class Scripted(vararg steps: List<JSONObject>) : GeminiStreamTransport {
        private val queue = ArrayDeque(steps.toList())
        override suspend fun stream(model: String, body: JSONObject, onEvent: (JSONObject) -> Unit): StreamOutcome {
            queue.removeFirst().forEach(onEvent)
            return StreamOutcome.Ok
        }
    }

    private fun loop(t: GeminiStreamTransport, toolTimeoutMs: Long = 5_000) = GeminiTextLoop(
        transport = t, cache = null, classify = { AgentResult.Failed },
        isMutating = { name, _ -> name == "manage_item" }, isReadThrough = { _, _ -> false },
        toolTimeoutMs = { toolTimeoutMs }, writeFact = ScriptedLines::factOf,
    )

    private val adds = TextToolExecutor { _, args, _ ->
        JSONObject().put("success", true).put("item", args.optString("item")).put("list", "Groceries")
    }

    /** One turn, joined as the session joins it; returns once the model is done (not the speech). */
    private suspend fun turn(
        owed: OwedWrites,
        reply: ReplySpeaker,
        writes: TurnWrites,
        t: GeminiStreamTransport,
        tools: TextToolExecutor = adds,
        toolTimeoutMs: Long = 5_000,
    ): GeminiTextLoop.Result = loop(t, toolTimeoutMs).answer(
        prefix, contents, tools,
        object : GeminiTextLoop.Listener {
            override fun onText(delta: String) = reply.text(delta)
            override fun onWriteFact(fact: ScriptedLines.WriteFact) = writes.add(fact)
            override fun onCodeLine(text: String, facts: List<ScriptedLines.WriteFact>) = owed.line(writes, reply, text, facts)
        },
    )

    private fun newReply(owed: OwedWrites, voice: Voice, ear: Ear) = speaker(voice, ear).also { owed.open(it) }

    private suspend fun play(reply: ReplySpeaker) = withTimeout(5_000) { reply.await() }

    // --- the turns ------------------------------------------------------------------------------------

    @Test
    fun `a write is heard once, between the model's words`() = runBlocking {
        val owed = OwedWrites()
        val ear = Ear()
        val writes = TurnWrites()
        val reply = newReply(owed, Voice(), ear)
        turn(owed, reply, writes, Scripted(listOf(add("milk")), listOf(text("Anything else?"))))
        reply.finishText()
        assertEquals(ReplySpeaker.Outcome.Played, play(reply))
        owed.endTurn(writes, cutOff = false)
        assertEquals("$milk Anything else?", ear.heard())
        assertNull("nothing owed after it played", owed.due())
    }

    @Test
    fun `a barge-in on the line owes it, and the next reply says it first, once`() = runBlocking {
        val owed = OwedWrites()
        val ear = Ear()
        val writes = TurnWrites()
        val voice = Voice(slowFor = setOf(milk))
        val reply = newReply(owed, voice, ear)
        turn(owed, reply, writes, Scripted(listOf(add("milk")), listOf()))
        reply.finishText()
        delay(200)
        reply.interrupt()
        play(reply)
        owed.endTurn(writes, cutOff = true)
        ear.words.clear()
        val next = newReply(owed, Voice(), ear)
        next.say("It is mild.")
        assertEquals(ReplySpeaker.Outcome.Played, play(next))
        assertEquals("$milk It is mild.", ear.heard())
        assertNull(owed.due())
    }

    @Test
    fun `a voice that fails on the line, then the re-voice says it once before the failure words (review F1)`() = runBlocking {
        val owed = OwedWrites()
        val ear = Ear()
        val writes = TurnWrites()
        val reply = newReply(owed, Voice(failFor = setOf(milk)), ear)
        turn(owed, reply, writes, Scripted(listOf(add("milk")), listOf()))
        reply.finishText()
        assertTrue(play(reply) is ReplySpeaker.Outcome.Failed)
        // The session owes what did not play, then re-voices only the words (never the line itself).
        owed.endTurn(writes, cutOff = false)
        val again = newReply(owed, Voice(), ear)
        again.say("Sorry, something broke.")
        assertEquals(ReplySpeaker.Outcome.Played, play(again))
        assertEquals("$milk Sorry, something broke.", ear.heard())
        assertTrue("the record keeps it", again.spoken().contains(milk))
        owed.endTurn(writes, cutOff = false)
        assertNull("said once, never again", owed.due())
    }

    @Test
    fun `a held lone word or a refusal is never said as a failure after a cut-off (review F9)`() = runBlocking {
        val owed = OwedWrites()
        val writes = TurnWrites()
        val reply = newReply(owed, Voice(), Ear())
        val held = TextToolExecutor { name, args, _ -> LoneWordWriteGuard.refusal(name, args, "Five.", "Alfred", false)!! }
        turn(owed, reply, writes, Scripted(listOf(add("five")), listOf(text("Add five?"))), tools = held)
        reply.interrupt()
        owed.endTurn(writes, cutOff = true)
        assertNull("nothing changed, nothing owed", owed.due())
    }

    @Test
    fun `a write that timed out is said as not confirmed after a cut-off, never as failed (review F4)`() = runBlocking {
        val owed = OwedWrites()
        val ear = Ear()
        val writes = TurnWrites()
        val reply = newReply(owed, Voice(), ear)
        val slow = TextToolExecutor { _, _, _ ->
            delay(2_000)
            JSONObject().put("success", true)
        }
        turn(owed, reply, writes, Scripted(listOf(add("milk")), listOf(text("Hm."))), tools = slow, toolTimeoutMs = 100)
        reply.interrupt()
        owed.endTurn(writes, cutOff = true)
        ear.words.clear()
        val next = newReply(owed, Voice(), ear)
        next.finishText()
        play(next)
        val heard = ear.heard()
        assertEquals(ScriptedLines.UNCONFIRMED_ONE, heard)
        assertFalse(heard.contains("didn't go through"))
    }

    @Test
    fun `a close that says nothing more writes the owed line down once, and nothing is said after (review F5)`() = runBlocking {
        val owed = OwedWrites()
        val writes = TurnWrites()
        val reply = newReply(owed, Voice(slowFor = setOf(milk)), Ear())
        turn(owed, reply, writes, Scripted(listOf(add("milk")), listOf()))
        reply.interrupt()
        owed.endTurn(writes, cutOff = true)
        assertEquals(milk, owed.writeDown())
        assertNull(owed.writeDown())
        val ear = Ear()
        val after = newReply(owed, Voice(), ear)
        after.finishText()
        play(after)
        assertEquals("", ear.heard())
    }

    @Test
    fun `an owed line opening a reply leaves the model's own leading filler to be dropped`() = runBlocking {
        val owed = OwedWrites()
        val writes = TurnWrites()
        val first = newReply(owed, Voice(slowFor = setOf(milk)), Ear())
        turn(owed, first, writes, Scripted(listOf(add("milk")), listOf()))
        first.interrupt()
        owed.endTurn(writes, cutOff = true)
        val ear = Ear()
        val next = newReply(owed, Voice(), ear)
        next.text("Checking. It is mild.")
        next.finishText()
        play(next)
        assertEquals("$milk It is mild.", ear.heard())
    }
}

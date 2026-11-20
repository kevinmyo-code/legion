@file:Suppress("MaxLineLength") // test fixtures: invented JSON reads best on one line

package com.kevin.legion.service.cascade

import com.kevin.legion.ai.AgentResult
import com.kevin.legion.service.TextToolExecutor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * Speculative answers ([SpeculativeAnswer], [UsageHold], [SpeculationRules]) against a scripted
 * transport: the hard rules Kevin approved them under. Nothing is heard before the turn is confirmed,
 * no tool runs before it, a confirmed draft's held call runs once, and every dropped draft is costed
 * as discarded. Invented content only. Robolectric only for `org.json`.
 */
@RunWith(RobolectricTestRunner::class)
class SpeculationTest {

    private val prefix = CascadePrefix("model-x", "Invented instruction.", JSONArray().put(JSONObject().put("name", "add_item")))
    private val contents = JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", "add milk"))))

    private fun parts(vararg p: JSONObject) = JSONObject().put("candidates", JSONArray().put(JSONObject().put("content", JSONObject().put("role", "model").put("parts", JSONArray().apply { p.forEach { put(it) } }))))
    private fun text(t: String) = parts(JSONObject().put("text", t))
    private fun call(name: String) = parts(JSONObject().put("functionCall", JSONObject().put("name", name).put("args", JSONObject()).put("id", "c1")))
    private fun usage(prompt: Int, out: Int) = JSONObject().put("usageMetadata", JSONObject().put("promptTokenCount", prompt).put("candidatesTokenCount", out))

    /** Each POST answers with the next script entry; a null entry waits until cancelled. */
    private class Scripted(vararg steps: List<JSONObject>?) : GeminiStreamTransport {
        val posts = AtomicInteger(0)
        private val queue = ArrayDeque(steps.toList())
        override suspend fun stream(model: String, body: JSONObject, onEvent: (JSONObject) -> Unit): StreamOutcome {
            posts.incrementAndGet()
            val events = synchronized(queue) { queue.removeFirst() }
            if (events == null) {
                delay(60_000)
                return StreamOutcome.Ok
            }
            events.forEach(onEvent)
            return StreamOutcome.Ok
        }
    }

    private class Heard : GeminiTextLoop.Listener {
        val text = StringBuffer()
        val calls = Collections.synchronizedList(mutableListOf<String>())
        val firstParts = Collections.synchronizedList(mutableListOf<Long>())
        override fun onText(delta: String) {
            text.append(delta)
        }
        override fun onToolCalls(names: List<String>) {
            calls.addAll(names)
        }
        override fun onFirstPart(round: Int, ms: Long) {
            firstParts.add(ms)
        }
    }

    private val lines = Collections.synchronizedList(mutableListOf<CallUsage>())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Every write here is an add: its facts, as the session's loop would make them. */
    private val sayAdds: (String, JSONObject, JSONObject, Boolean, Boolean) -> ScriptedLines.WriteFact =
        { _, _, r, ok, confirmed -> ScriptedLines.writeFact("manage_item", "add", r, ok, confirmed) }

    private fun draft(t: GeminiStreamTransport, sayWrites: Boolean = false): SpeculativeAnswer {
        val hold = UsageHold { lines.add(it) }
        val loop = GeminiTextLoop(
            transport = t,
            cache = null,
            classify = { AgentResult.Failed },
            isMutating = { name, _ -> name == "add_item" },
            isReadThrough = { _, _ -> false },
            meter = { model, u -> hold.add(CallUsage.of(CallUsage.LLM, model, u)) },
            discarded = { model, u, chars -> hold.add(CallUsage.of(CallUsage.LLM, model, u, chars = chars, discarded = true)) },
            writeFact = if (sayWrites) sayAdds else null,
        )
        return SpeculativeAnswer(scope, loop, hold)
    }

    private suspend fun until(cond: () -> Boolean) = withTimeout(5_000) { while (!cond()) delay(5) }

    @Test
    fun `nothing a draft writes is heard before confirmation, all of it after, in order`() = runBlocking {
        val d = draft(Scripted(listOf(text("Milk is "), text("on the list."), usage(100, 7))))
        d.start(prefix, contents)
        until { d.isDone }
        assertEquals("on the list.".length + "Milk is ".length, d.draftChars)
        assertTrue("the usage line is held until the verdict", lines.isEmpty())
        val heard = Heard()
        val r = d.adopt(TextToolExecutor { _, _, _ -> JSONObject().put("success", true) }, heard)
        assertEquals("Milk is on the list.", heard.text.toString())
        assertEquals("Milk is on the list.", r.text)
        assertEquals("first part replayed as 'ms after confirmation'", listOf(0L), heard.firstParts)
        assertEquals(1, lines.size)
        assertFalse("a kept draft is billed as heard", lines[0].discarded)
    }

    @Test
    fun `a draft that wants a tool holds it until confirmation, then runs it once`() = runBlocking {
        val t = Scripted(listOf(call("add_item"), usage(100, 3)), listOf(text("Added."), usage(120, 2)))
        val d = draft(t)
        val ran = AtomicInteger(0)
        d.start(prefix, contents)
        until { d.heldAtTool }
        delay(100)
        assertEquals("no tool before confirmation", 0, ran.get())
        assertEquals("the second round is not even asked", 1, t.posts.get())
        val heard = Heard()
        val r = d.adopt(TextToolExecutor { _, _, _ -> ran.incrementAndGet(); JSONObject().put("success", true) }, heard)
        assertEquals(1, ran.get())
        assertEquals(listOf("add_item"), heard.calls)
        assertEquals("Added.", r.text)
        assertEquals(listOf("add_item"), r.writesDone)
        assertEquals(2, lines.size)
        assertTrue(lines.none { it.discarded })
    }

    @Test
    fun `an adopted draft cut off mid-write reports the write before the turn ends (review F2)`() = runBlocking {
        val t = Scripted(listOf(call("add_item"), usage(100, 3)), null)
        val d = draft(t, sayWrites = true)
        d.start(prefix, contents)
        until { d.heldAtTool }
        val facts = Collections.synchronizedList(mutableListOf<ScriptedLines.WriteFact>())
        val heard = object : GeminiTextLoop.Listener {
            override fun onWriteFact(fact: ScriptedLines.WriteFact) {
                facts.add(fact)
            }
        }
        val slowWrite = TextToolExecutor { _, _, _ ->
            delay(300)
            JSONObject().put("success", true).put("item", "milk")
        }
        val turn = launch(Dispatchers.Default) { d.adopt(slowWrite, heard) }
        delay(50)
        // The turn is cut off while the write runs: by the time the cut-off turn has ended, the
        // write's result is in, so the session can owe it.
        turn.cancelAndJoin()
        assertEquals(1, facts.size)
        assertTrue(facts[0].said)
    }

    @Test
    fun `a discarded draft held at a tool runs nothing and is costed as discarded`() = runBlocking {
        val t = Scripted(listOf(call("add_item"), usage(100, 3)), listOf(text("Added.")))
        val d = draft(t)
        d.start(prefix, contents)
        until { d.heldAtTool }
        assertTrue(d.discard())
        assertFalse("only once", d.discard())
        delay(100)
        assertEquals(1, t.posts.get())
        assertEquals(1, lines.size)
        assertTrue("a round that finished but was dropped is discarded too", lines[0].discarded)
        assertEquals(SpeculativeAnswer.State.DISCARDED, d.current)
    }

    @Test
    fun `a draft cut off mid-stream logs one discarded line`() = runBlocking {
        val d = draft(Scripted(null))
        d.start(prefix, contents)
        delay(100)
        d.discard()
        until { lines.size == 1 }
        assertTrue(lines.single().discarded)
    }

    @Test
    fun `usage held for a dropped draft and late reports after it are all discarded`() {
        val out = mutableListOf<CallUsage>()
        val hold = UsageHold { out.add(it) }
        hold.add(CallUsage(CallUsage.STT, "m", present = true, prompt = 5))
        assertTrue(out.isEmpty())
        hold.release(discarded = true)
        hold.release(discarded = false) // only the first release counts
        hold.add(CallUsage(CallUsage.STT, "m", present = false))
        assertEquals(2, out.size)
        assertTrue(out.all { it.discarded })
    }

    @Test
    fun `same text tolerates only trailing punctuation and spacing`() {
        assertTrue(SpeculationRules.sameText("what is on my calendar thursday", "what is on my calendar thursday?"))
        assertTrue(SpeculationRules.sameText("set a  timer", "set a timer."))
        assertFalse(SpeculationRules.sameText("what is on my calendar thursday", "what is on my calendar thursday the fourteenth?"))
        assertFalse(SpeculationRules.sameText("card ending in 44", "card ending in 4417"))
        assertFalse(SpeculationRules.sameText("navigate to", "Navigate to"))
    }

    @Test
    fun `a fragment is too short to draft on`() {
        assertFalse(SpeculationRules.longEnough("um"))
        assertFalse(SpeculationRules.longEnough("so..."))
        assertTrue(SpeculationRules.longEnough("add milk"))
    }

    @Test
    fun `the session summary counts drafts, and says nothing when there were none`() {
        val ledger = UsageLedger()
        assertFalse(ledger.summary("laptop", 1).contains("spec_"))
        ledger.spec(started = 2)
        ledger.spec(kept = 1)
        ledger.spec(discarded = 1)
        assertTrue(ledger.summary("laptop", 2).contains("spec_started=2 spec_kept=1 spec_discarded=1"))
    }

    @Test
    fun `every lookup Live ran non-blocking is a slow tool here too`() {
        assertTrue(ScriptedLines.SLOW_TOOLS.containsAll(com.kevin.legion.service.LiveToolbox.NON_BLOCKING_READ_TOOLS))
    }

    @Test
    fun `the log lines carry counts only`() {
        assertEquals("spec_start trigger=eot backend=laptop chars=12", CascadeLines.specStart("eot", "laptop", 12))
        assertEquals("spec_discard trigger=silence reason=speech_resumed age_ms=40", CascadeLines.specDiscard("silence", "speech_resumed", 40))
        assertEquals(
            "spec_kept trigger=eot lead_ms=280 first_part_ahead_ms=-1 draft_chars=0 held_at_tool=false done=false",
            CascadeLines.specKept("eot", 280, null, 0, heldAtTool = false, done = false),
        )
    }

    @Test
    fun `a draft whose model call failed says so, so it is dropped and not adopted`() = runBlocking<Unit> {
        val failing = object : GeminiStreamTransport {
            override suspend fun stream(model: String, body: JSONObject, onEvent: (JSONObject) -> Unit): StreamOutcome =
                StreamOutcome.HttpError(503, "unavailable")
        }
        val d = draft(failing)
        d.start(prefix, contents)
        until { d.isDone }
        until { d.failed }
        assertTrue(d.failed)
        assertEquals("draft_failed", d.unusableReason)
        assertTrue("it is still live until the session drops it", d.discard())
        assertEquals(SpeculativeAnswer.State.DISCARDED, d.current)

        val good = draft(Scripted(listOf(text("Fine."), usage(10, 1))))
        good.start(prefix, contents)
        until { good.isDone }
        assertFalse("a draft that answered is not marked failed", good.failed)
        assertEquals(null, good.unusableReason)
        good.discard()
    }

    @Test
    fun `an utterance gets a small fixed number of cloud drafts, then none`() {
        val inner = object : SttUtterance {
            override val id = 1
            override fun audio(pcm: ByteArray, offset: Int, length: Int) = Unit
            override fun end() = Unit
            override fun cancel() = Unit
            override suspend fun result(timeoutMs: Long): SttResult = SttResult.Failed("unused")
        }
        val backend = object : SpeechBackend {
            override val kind = SpeechBackend.Kind.CLOUD
            override val stt get() = error("unused")
            override val tts get() = error("unused")
            override val sttFinalTimeoutMs = 1L
            override val ttsFirstAudioTimeoutMs = 1L
            override fun close(reason: String) = Unit
        }
        val u = TurnUtterance(inner, backend, keepAudio = true)
        repeat(SpeculationRules.MAX_CLOUD_DRAFTS) {
            assertTrue(u.cloudDraftBudgetLeft)
            assertTrue(u.spendCloudDraft())
        }
        assertFalse(u.cloudDraftBudgetLeft)
        assertFalse(u.spendCloudDraft())
        assertTrue("the next utterance has its own budget", TurnUtterance(inner, backend, keepAudio = true).spendCloudDraft())
    }
}

@file:Suppress("MaxLineLength", "ReturnCount", "EmptyFunctionBlock") // test fixtures: invented JSON and wire frames read best on one line

package com.kevin.legion.service.cascade

import com.kevin.legion.ai.AgentResult
import com.kevin.legion.service.TextFailure
import com.kevin.legion.service.TextToolExecutor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
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
 * [GeminiTextLoop], [StreamedTurn], [SseLines] and [CascadeRequests] against a scripted transport.
 * Invented content only. Robolectric only for `org.json`.
 */
@RunWith(RobolectricTestRunner::class)
class GeminiTextLoopTest {

    private val decls = JSONArray()
        .put(JSONObject().put("name", "add_item").put("description", "invented"))
        .put(JSONObject().put("name", "get_weather").put("description", "invented"))
    private val prefix = CascadePrefix("model-x", "Invented instruction.", decls)
    private val contents = JSONArray().put(
        JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", "hello"))),
    )

    // --- event builders -------------------------------------------------------------------------

    private fun textEv(t: String, thought: Boolean = false) = JSONObject().put(
        "candidates",
        JSONArray().put(
            JSONObject().put(
                "content",
                JSONObject().put("role", "model").put(
                    "parts",
                    JSONArray().put(JSONObject().put("text", t).apply { if (thought) put("thought", true) }),
                ),
            ),
        ),
    )

    private fun callEv(vararg names: String, signature: String? = null): JSONObject {
        val parts = JSONArray()
        names.forEachIndexed { i, n ->
            val p = JSONObject().put("functionCall", JSONObject().put("name", n).put("args", JSONObject().put("k", i)).put("id", "id$i"))
            if (i == 0 && signature != null) p.put("thoughtSignature", signature)
            parts.put(p)
        }
        return JSONObject().put("candidates", JSONArray().put(JSONObject().put("content", JSONObject().put("role", "model").put("parts", parts))))
    }

    private fun usageEv(prompt: Int, cached: Int, out: Int) = JSONObject().put(
        "usageMetadata",
        JSONObject().put("promptTokenCount", prompt).put("cachedContentTokenCount", cached).put("candidatesTokenCount", out),
    )

    /** A transport that answers each POST with the next script entry and records every body. */
    private class Scripted(vararg steps: Pair<StreamOutcome, List<JSONObject>>) : GeminiStreamTransport {
        val bodies = Collections.synchronizedList(mutableListOf<JSONObject>())
        private val queue = ArrayDeque(steps.toList())
        override suspend fun stream(model: String, body: JSONObject, onEvent: (JSONObject) -> Unit): StreamOutcome {
            bodies.add(JSONObject(body.toString()))
            val (outcome, events) = queue.removeFirst()
            events.forEach(onEvent)
            return outcome
        }
    }

    private fun ok(vararg ev: JSONObject) = StreamOutcome.Ok to ev.toList()

    private fun loop(t: GeminiStreamTransport, cache: ContextCacheStore? = null, maxRounds: Int = 6) = GeminiTextLoop(
        transport = t,
        cache = cache,
        classify = { e -> if (e.code == 429) AgentResult.RateLimited else AgentResult.Failed },
        isMutating = { name, _ -> name == "add_item" },
        isReadThrough = { it == "ask_mail" },
        maxRounds = maxRounds,
    )

    private val okTool = TextToolExecutor { _, _, _ -> JSONObject().put("success", true) }

    private class Collect : GeminiTextLoop.Listener {
        val text = StringBuilder()
        val calls = mutableListOf<List<String>>()
        val readThrough = mutableListOf<String>()
        override fun onText(delta: String) {
            text.append(delta)
        }
        override fun onToolCalls(names: List<String>) {
            calls.add(names)
        }
        override fun onReadThrough(source: String) {
            readThrough.add(source)
        }
    }

    // --- SSE and the streamed turn ----------------------------------------------------------------

    @Test
    fun `sse data lines parse and everything else is skipped`() {
        assertEquals("x", SseLines.dataOf("data: {\"a\":\"x\"}")!!.getString("a"))
        assertNull(SseLines.dataOf(""))
        assertNull(SseLines.dataOf(": keepalive"))
        assertNull(SseLines.dataOf("event: message"))
        assertNull(SseLines.dataOf("data: [DONE]"))
        assertNull(SseLines.dataOf("data: {not json"))
    }

    @Test
    fun `a streamed turn speaks text, hides thoughts and echoes every part verbatim`() {
        val t = StreamedTurn()
        val spoken = StringBuilder()
        t.onEvent(textEv("thinking about it", thought = true)) { spoken.append(it) }
        t.onEvent(textEv("Two ")) { spoken.append(it) }
        t.onEvent(textEv("minutes.")) { spoken.append(it) }
        t.onEvent(callEv("add_item", signature = "sig-invented"))
        t.onEvent(usageEv(100, 90, 5).put("candidates", JSONArray().put(JSONObject().put("finishReason", "STOP"))))
        assertEquals("Two minutes.", spoken.toString())
        assertEquals("Two minutes.", t.text.toString())
        assertEquals(listOf("add_item"), t.calls.map { it.name })
        assertEquals(90, t.usage!!.getInt("cachedContentTokenCount"))
        assertEquals("STOP", t.finishReason)
        val echo = t.modelContent().getJSONArray("parts")
        assertEquals(4, echo.length())
        assertTrue(echo.getJSONObject(0).getBoolean("thought"))
        assertEquals("sig-invented", echo.getJSONObject(3).getString("thoughtSignature"))
    }

    @Test
    fun `grounding and block reasons are seen`() {
        val t = StreamedTurn()
        t.onEvent(JSONObject().put("candidates", JSONArray().put(JSONObject().put("groundingMetadata", JSONObject()))))
        t.onEvent(JSONObject().put("promptFeedback", JSONObject().put("blockReason", "SAFETY")))
        assertTrue(t.grounded)
        assertEquals("SAFETY", t.blockReason)
    }

    // --- request shapes ---------------------------------------------------------------------------

    @Test
    fun `a cached request never carries the prefix (a 400 otherwise)`() {
        val b = CascadeRequests.cached("cachedContents/abc", contents)
        assertEquals("cachedContents/abc", b.getString("cachedContent"))
        for (k in listOf("systemInstruction", "tools", "toolConfig")) assertFalse("cached body carries $k", b.has(k))
        assertTrue(b.has("contents") && b.has("generationConfig"))
    }

    @Test
    fun `an inline request carries search with the server-side flag, and the backstop carries neither`() {
        val b = CascadeRequests.inline(prefix, contents, force = false)
        assertEquals("Invented instruction.", b.getJSONObject("systemInstruction").getJSONArray("parts").getJSONObject(0).getString("text"))
        assertTrue(b.getJSONArray("tools").getJSONObject(0).has("googleSearch"))
        assertTrue(b.getJSONObject("toolConfig").getBoolean("includeServerSideToolInvocations"))
        val f = CascadeRequests.inline(prefix, contents, force = true)
        assertEquals(1, f.getJSONArray("tools").length())
        assertEquals("NONE", f.getJSONObject("toolConfig").getJSONObject("functionCallingConfig").getString("mode"))
        assertFalse(f.getJSONObject("toolConfig").has("includeServerSideToolInvocations"))
    }

    @Test
    fun `the cache create body holds the whole prefix and a ttl, and the key follows it`() {
        val c = CascadeRequests.cacheCreate(prefix)
        assertEquals("models/model-x", c.getString("model"))
        assertTrue(c.has("systemInstruction") && c.has("tools") && c.has("toolConfig"))
        assertEquals("${CascadeRequests.CACHE_TTL_S}s", c.getString("ttl"))
        assertEquals(prefix.key, prefix.copy().key)
        assertFalse(prefix.key == prefix.copy(systemInstruction = "Other.").key)
    }

    // --- the loop -----------------------------------------------------------------------------------

    @Test
    fun `a plain answer streams to the listener and counts usage`() = runBlocking<Unit> {
        val t = Scripted(ok(textEv("Hello "), textEv("there."), usageEv(1000, 0, 4)))
        val l = Collect()
        val r = loop(t).answer(prefix, contents, okTool, l)
        assertNull(r.failure)
        assertEquals("Hello there.", r.text)
        assertEquals("Hello there.", l.text.toString())
        assertEquals(1, r.usage.rounds)
        assertEquals(1, r.usage.inlineRounds)
        assertEquals(1000, r.usage.prompt)
        // The caller's contents are copied, never changed.
        assertEquals(1, contents.length())
    }

    @Test
    fun `parallel calls run at the same time and all results go back in one turn`() = runBlocking<Unit> {
        val t = Scripted(ok(callEv("add_item", "get_weather")), ok(textEv("Done, and it is mild.")))
        val bothStarted = CompletableDeferred<Unit>()
        val started = Collections.synchronizedSet(mutableSetOf<String>())
        val exec = TextToolExecutor { name, _, _ ->
            started.add(name)
            if (started.size == 2) bothStarted.complete(Unit)
            // Each waits for the other: only possible if they run concurrently.
            withTimeout(2_000) { bothStarted.await() }
            JSONObject().put("success", true)
        }
        val l = Collect()
        val r = loop(t).answer(prefix, contents, exec, l)
        assertNull(r.failure)
        assertEquals(listOf(listOf("add_item", "get_weather")), l.calls)
        assertEquals(listOf("add_item"), r.writesDone)
        val second = t.bodies[1].getJSONArray("contents")
        assertEquals(3, second.length()) // user, model (echoed), one user turn with both responses
        val responses = second.getJSONObject(2).getJSONArray("parts")
        assertEquals(2, responses.length())
        assertEquals("id0", responses.getJSONObject(0).getJSONObject("functionResponse").getString("id"))
        assertEquals("model", second.getJSONObject(1).getString("role"))
    }

    @Test
    fun `multi-step calls go round after round, then a forced inline answer at the cap`() = runBlocking<Unit> {
        val t = Scripted(ok(callEv("get_weather")), ok(callEv("get_weather")), ok(textEv("Enough.")))
        val r = loop(t, maxRounds = 2).answer(prefix, contents, okTool, Collect())
        assertEquals("Enough.", r.text)
        assertEquals(3, t.bodies.size)
        // Round 2 is told to wrap up; round 3 is the tool-free backstop, inline.
        val nudged = t.bodies[2].getJSONArray("contents")
        assertTrue(nudged.toString().contains("Answer now with what you have."))
        assertTrue(t.bodies[0].getJSONObject("toolConfig").has("includeServerSideToolInvocations"))
        val last = t.bodies.last()
        assertEquals("NONE", last.getJSONObject("toolConfig").getJSONObject("functionCallingConfig").getString("mode"))
        assertFalse(last.has("cachedContent"))
    }

    @Test
    fun `a write that did not say success is not counted done`() = runBlocking<Unit> {
        val t = Scripted(ok(callEv("add_item")), ok(textEv("Tried.")))
        val r = loop(t).answer(prefix, contents, TextToolExecutor { _, _, _ -> JSONObject().put("note", "?") }, Collect())
        assertTrue(r.writesDone.isEmpty())
        assertEquals(listOf("add_item"), r.toolsRan)
    }

    @Test
    fun `a read-through tool marks the turn`() = runBlocking<Unit> {
        val t = Scripted(ok(callEv("ask_mail")), ok(textEv("You have mail.")))
        val l = Collect()
        val r = loop(t).answer(prefix, contents, okTool, l)
        assertTrue(r.readThrough)
        assertEquals(listOf("ask_mail"), l.readThrough)
    }

    @Test
    fun `search grounding marks the turn read-through`() = runBlocking<Unit> {
        val grounded = textEv("The invented team won.")
        grounded.getJSONArray("candidates").getJSONObject(0).put("groundingMetadata", JSONObject())
        val r = loop(Scripted(ok(grounded))).answer(prefix, contents, okTool, Collect())
        assertTrue(r.readThrough)
    }

    @Test
    fun `a cache that is gone falls back inline in the same round and is dropped`() = runBlocking<Unit> {
        val api = CacheApi { CacheCreateOutcome.Created("cachedContents/old") }
        val store = ContextCacheStore(api, CoroutineScope(Dispatchers.Unconfined))
        store.warm(prefix)
        assertEquals("cachedContents/old", store.peek(prefix))
        val t = Scripted(
            StreamOutcome.HttpError(404, "{\"error\":{\"message\":\"CachedContent not found\"}}") to emptyList(),
            ok(textEv("Fine.")),
        )
        val r = loop(t, cache = store).answer(prefix, contents, okTool, Collect())
        assertNull(r.failure)
        assertEquals("cachedContents/old", t.bodies[0].getString("cachedContent"))
        assertFalse(t.bodies[1].has("cachedContent"))
        assertTrue(t.bodies[1].has("tools"))
        assertEquals(1, r.usage.rounds)
        assertEquals(1, r.usage.inlineRounds)
    }

    @Test
    fun `a ready cache is used and its hits are counted`() = runBlocking<Unit> {
        val store = ContextCacheStore({ CacheCreateOutcome.Created("cachedContents/c1") }, CoroutineScope(Dispatchers.Unconfined))
        store.warm(prefix)
        val t = Scripted(ok(textEv("Hi."), usageEv(14_000, 13_500, 2)))
        val r = loop(t, cache = store).answer(prefix, contents, okTool, Collect())
        assertEquals("cachedContents/c1", t.bodies[0].getString("cachedContent"))
        assertEquals(1, r.usage.cachedRounds)
        assertEquals(13_500, r.usage.cached)
    }

    @Test
    fun `a cache not ready yet means inline now, never a wait`() = runBlocking<Unit> {
        val gate = CompletableDeferred<Unit>()
        val store = ContextCacheStore({ gate.await(); CacheCreateOutcome.Created("cachedContents/late") }, CoroutineScope(Dispatchers.Default))
        val t = Scripted(ok(textEv("Quick.")))
        val r = loop(t, cache = store).answer(prefix, contents, okTool, Collect())
        assertNull(r.failure)
        assertFalse(t.bodies[0].has("cachedContent"))
        gate.complete(Unit)
    }

    @Test
    fun `a rejected search is dropped and the round retried without it`() = runBlocking<Unit> {
        val t = Scripted(
            StreamOutcome.HttpError(400, "Please enable tool_config.include_server_side_tool_invocations to use Built-in tools") to emptyList(),
            ok(textEv("Fine without search.")),
        )
        val r = loop(t).answer(prefix, contents, okTool, Collect())
        assertNull(r.failure)
        assertTrue(t.bodies[0].getJSONArray("tools").getJSONObject(0).has("googleSearch"))
        assertFalse(t.bodies[1].getJSONArray("tools").getJSONObject(0).has("googleSearch"))
    }

    @Test
    fun `failures come back in words`() = runBlocking<Unit> {
        val rate = loop(Scripted(StreamOutcome.HttpError(429, "quota") to emptyList())).answer(prefix, contents, okTool, Collect())
        assertEquals(TextFailure.RATE_LIMITED, rate.failure)
        assertTrue(rate.failureWords!!.contains("Nothing was changed"))
        val off = loop(Scripted(StreamOutcome.Network("x") to emptyList())).answer(prefix, contents, okTool, Collect())
        assertEquals(TextFailure.OFFLINE, off.failure)
        val empty = loop(Scripted(ok(usageEv(1, 0, 0)))).answer(prefix, contents, okTool, Collect())
        assertEquals(TextFailure.NO_TEXT, empty.failure)
    }

    @Test
    fun `cancelling mid-stream stops at once, and a tool already running still finishes`() = runBlocking<Unit> {
        val toolFinished = CompletableDeferred<Unit>()
        val toolStarted = CompletableDeferred<Unit>()
        val t = object : GeminiStreamTransport {
            var n = 0
            override suspend fun stream(model: String, body: JSONObject, onEvent: (JSONObject) -> Unit): StreamOutcome {
                n++
                if (n == 1) {
                    onEvent(callEv("add_item"))
                    return StreamOutcome.Ok
                }
                onEvent(textEv("Partial "))
                awaitCancellation()
            }
        }
        val exec = TextToolExecutor { _, _, _ ->
            toolStarted.complete(Unit)
            delay(200)
            toolFinished.complete(Unit)
            JSONObject().put("success", true)
        }
        val job = async(Dispatchers.Default) { loop(t).answer(prefix, contents, exec, Collect()) }
        toolStarted.await()
        job.cancelAndJoin()
        // The write was not cut in half.
        assertTrue(toolFinished.isCompleted)
        assertTrue(job.isCancelled)
    }

    // --- a tool turn that ends on filler (FillerTurn, 2026-11-16) --------------------------------

    private fun mode(b: JSONObject): String? =
        b.optJSONObject("toolConfig")?.optJSONObject("functionCallingConfig")?.optString("mode")

    @Test
    fun `a tool turn that ends on filler is never spoken as the answer and is asked once more, tool-free`() = runBlocking<Unit> {
        val t = Scripted(
            ok(callEv("get_weather")),
            ok(textEv("Checking "), textEv("the modules.")),
            ok(textEv("Two modules answered; the one at seven sixty did not.")),
        )
        val l = Collect()
        val r = loop(t).answer(prefix, contents, okTool, l)
        assertNull(r.failure)
        assertEquals(1, r.fillerContinued)
        assertFalse(r.fillerAgain)
        assertEquals(3, t.bodies.size)
        // Never handed to the voice: the listener got only the answer.
        assertEquals("Two modules answered; the one at seven sixty did not.", l.text.toString())
        assertEquals("Two modules answered; the one at seven sixty did not.", r.text)
        val last = t.bodies[2]
        assertEquals("NONE", mode(last))
        val c = last.getJSONArray("contents")
        assertEquals("model", c.getJSONObject(c.length() - 2).getString("role"))
        assertEquals(FillerTurn.NOTE, c.getJSONObject(c.length() - 1).getJSONArray("parts").getJSONObject(0).getString("text"))
    }

    @Test
    fun `filler twice ends on the failure line and never loops`() = runBlocking<Unit> {
        val t = Scripted(ok(callEv("get_weather")), ok(textEv("Let me look.")), ok(textEv("Checking.")))
        val l = Collect()
        val r = loop(t).answer(prefix, contents, okTool, l)
        assertEquals(TextFailure.NO_TEXT, r.failure)
        assertTrue(r.fillerAgain)
        assertEquals(1, r.fillerContinued)
        assertEquals(3, t.bodies.size) // the tool round, the filler round, ONE continuation
        assertEquals("", l.text.toString())
        assertTrue(r.failureWords!!.startsWith(TextFailure.NO_TEXT.lead))
    }

    @Test
    fun `a leading filler before the tool and an empty last round is asked once more`() = runBlocking<Unit> {
        val t = Scripted(ok(textEv("Checking."), callEv("get_weather")), ok(), ok(textEv("It is mild.")))
        val l = Collect()
        val r = loop(t).answer(prefix, contents, okTool, l)
        assertNull(r.failure)
        assertEquals(1, r.fillerContinued)
        assertEquals(3, t.bodies.size)
        // The speaker drops the leading "Checking." (LeadingFiller); the answer follows it.
        assertEquals("It is mild.", LeadingFiller.strip(r.text))
    }

    @Test
    fun `filler with no tool, content after a tool, or filler before more content goes as it is`() = runBlocking<Unit> {
        // No tool called: "On it." is the whole reply and is said.
        val a = Scripted(ok(textEv("On it.")))
        val ra = loop(a).answer(prefix, contents, okTool, Collect())
        assertEquals("On it.", ra.text)
        assertEquals(0, ra.fillerContinued)
        assertEquals(1, a.bodies.size)
        // After a tool, a line with content is said, streamed, no extra round.
        for (line in listOf("Looking good.", "On it, timer set.", "Checking: two codes.", "Checking. It is mild.")) {
            val t = Scripted(ok(callEv("get_weather")), ok(textEv(line)))
            val l = Collect()
            val r = loop(t).answer(prefix, contents, okTool, l)
            assertEquals(line, 0, r.fillerContinued)
            assertEquals(line, 2, t.bodies.size)
            assertEquals(line, line, l.text.toString())
        }
    }

    // --- a balance the phone speaks itself (VerbatimSpeech, stage 7) -----------------------------

    private fun verbatimTool(from: String) = TextToolExecutor { _, _, _ ->
        JSONObject().put("success", true)
            .put("speak_verbatim", "Net cash is forty-five dollars, as of today at 9:04 PM.")
            .put("verbatim_from", from)
    }

    @Test
    fun `a balance result is spoken verbatim and the model's money sentences after it are dropped`() = runBlocking<Unit> {
        val t = Scripted(ok(callEv("get_weather")), ok(textEv("You have \$45.00. "), textEv("Your quiz is due tonight.")))
        val l = Collect()
        val r = loop(t).answer(prefix, contents, verbatimTool("get_balance"), l)
        assertNull(r.failure)
        assertTrue(r.verbatimSpoken)
        assertEquals(1, r.moneyDropped)
        assertEquals("Net cash is forty-five dollars, as of today at 9:04 PM. Your quiz is due tonight.", l.text.toString())
        assertEquals(l.text.toString(), r.text)
    }

    @Test
    fun `after a verbatim balance a filler or a repeat ends the turn cleanly, no extra round`() = runBlocking<Unit> {
        for (after in listOf("Checking.", "Net cash is forty-five dollars, as of today at 9:04 PM.", "")) {
            val t = Scripted(ok(callEv("get_weather")), ok(*if (after.isEmpty()) arrayOf<JSONObject>() else arrayOf(textEv(after))))
            val l = Collect()
            val r = loop(t).answer(prefix, contents, verbatimTool("get_balance"), l)
            assertNull(after, r.failure)
            assertEquals(after, 2, t.bodies.size)
            assertEquals(after, 0, r.fillerContinued)
            assertEquals(after, "Net cash is forty-five dollars, as of today at 9:04 PM.", l.text.toString().trim())
            assertEquals(after, "Net cash is forty-five dollars, as of today at 9:04 PM.", r.text)
        }
    }

    @Test
    fun `only a tool on the allowlist may have its words spoken`() = runBlocking<Unit> {
        val t = Scripted(ok(callEv("get_weather")), ok(textEv("It is mild.")))
        val l = Collect()
        val r = loop(t).answer(prefix, contents, verbatimTool("add_item"), l)
        assertFalse(r.verbatimSpoken)
        assertEquals("It is mild.", l.text.toString())
    }

    @Test
    fun `a routed balance lifts its words only from the allowlisted tool`() {
        val out = JSONObject().put("success", true).put("speak_verbatim", "Said.").put("verbatim_from", "get_balance").toString()
        assertEquals("Said.", com.kevin.legion.ai.VerbatimSpeech.fromToolOutput("get_balance", out))
        assertNull(com.kevin.legion.ai.VerbatimSpeech.fromToolOutput("list_recent_transactions", out))
        assertNull(com.kevin.legion.ai.VerbatimSpeech.fromToolOutput("get_balance", "not json"))
    }

    // --- diagnostics the phone speaks itself (VerbatimSpeech get_codes, stage 7 diagnostics) -------

    private val diagWords = "No stored engine codes. That is the engine's generic stored-code read only: " +
        "pending codes and the other modules were not read."

    private fun diagTool() = TextToolExecutor { _, _, _ ->
        JSONObject().put("success", true).put("speak_verbatim", diagWords).put("verbatim_from", "get_codes")
    }

    @Test
    fun `a diagnostics read is spoken verbatim and a verdict or code restated after it is dropped`() = runBlocking<Unit> {
        val t = Scripted(
            ok(callEv("get_weather")),
            ok(textEv("Clean bill. "), textEv("P0420 is gone. "), textEv("Want me to scan every module?")),
        )
        val l = Collect()
        val r = loop(t).answer(prefix, contents, diagTool(), l)
        assertNull(r.failure)
        assertTrue(r.verbatimSpoken)
        assertEquals(2, r.diagDropped)
        assertEquals(0, r.moneyDropped)
        assertEquals("$diagWords Want me to scan every module?", l.text.toString())
        assertEquals(l.text.toString(), r.text)
    }

    @Test
    fun `after a verbatim diagnostics read a filler ends the turn, the words themselves are never dropped`() =
        runBlocking<Unit> {
            for (after in listOf("Checking.", "")) {
                val t = Scripted(ok(callEv("get_weather")), ok(*if (after.isEmpty()) arrayOf<JSONObject>() else arrayOf(textEv(after))))
                val l = Collect()
                val r = loop(t).answer(prefix, contents, diagTool(), l)
                assertNull(after, r.failure)
                assertEquals(after, 0, r.fillerContinued)
                assertTrue(after, r.text.startsWith(diagWords))
            }
        }
}

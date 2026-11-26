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

    private fun loop(
        t: GeminiStreamTransport,
        cache: ContextCacheStore? = null,
        maxRounds: Int = 6,
        mayCreateCache: () -> Boolean = { true },
    ) = GeminiTextLoop(
        transport = t,
        cache = cache,
        mayCreateCache = mayCreateCache,
        classify = { e -> if (e.code == 429) AgentResult.RateLimited else AgentResult.Failed },
        isMutating = { name, _ -> name == "add_item" },
        // The production predicate: what the cascade model actually sends is a folded call.
        isReadThrough = CascadeRules::isReadThrough,
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

    /** One functionCall part with real arguments, as the cascade model sends a folded action tool. */
    private fun foldedEv(name: String, args: JSONObject) = JSONObject().put(
        "candidates",
        JSONArray().put(
            JSONObject().put(
                "content",
                JSONObject().put("role", "model").put(
                    "parts",
                    JSONArray().put(JSONObject().put("functionCall", JSONObject().put("name", name).put("args", args).put("id", "f-$name"))),
                ),
            ),
        ),
    )

    private val newestMail = JSONObject().put("domain", "ask_mail").put("question", "read my newest email")

    private class Seen : TextToolExecutor {
        val calls = Collections.synchronizedList(mutableListOf<Pair<String, Boolean>>())
        override suspend fun run(name: String, args: JSONObject, readThrough: Boolean): JSONObject {
            calls.add(name to readThrough)
            return JSONObject().put("success", true)
        }
    }

    // ADR 0090 gap 1 (2026-11-17): the old test called the unfolded name `ask_mail`, which the
    // cascade model never sends, so it passed while production missed every folded mail read.
    @Test
    fun `a folded mail read marks the turn, as the cascade model sends it`() = runBlocking<Unit> {
        val t = Scripted(ok(foldedEv("look_up", newestMail)), ok(textEv("You have mail.")))
        val l = Collect()
        val r = loop(t).answer(prefix, contents, okTool, l)
        assertTrue(r.readThrough)
        assertEquals(listOf("look_up"), l.readThrough)
    }

    @Test
    fun `a folded brief, parcel or flight read marks the turn`() = runBlocking<Unit> {
        for (action in listOf("get_sitrep", "track_package", "flight_status")) {
            val t = Scripted(ok(foldedEv("day", JSONObject().put("action", action))), ok(textEv("Here it is.")))
            val r = loop(t).answer(prefix, contents, okTool, Collect())
            assertTrue("day $action", r.readThrough)
        }
    }

    @Test
    fun `a folded calendar read leaves the turn clean`() = runBlocking<Unit> {
        val args = JSONObject().put("action", "read_calendar").put("from", "today").put("to", "today")
        val t = Scripted(ok(foldedEv("day", args)), ok(textEv("A clear day.")))
        val r = loop(t).answer(prefix, contents, okTool, Collect())
        assertFalse(r.readThrough)
    }

    @Test
    fun `a write after a folded mail read is handed the mark`() = runBlocking<Unit> {
        val remember = JSONObject().put("action", "remember").put("text", "an invented fact from the mail")
        val t = Scripted(ok(foldedEv("look_up", newestMail)), ok(foldedEv("memory", remember)), ok(textEv("Not kept.")))
        val seen = Seen()
        loop(t).answer(prefix, contents, seen, Collect())
        assertEquals(listOf("look_up" to false, "memory" to true), seen.calls)
    }

    // A read-through tool that also acts (email reads mail for the recipient; browser opens a
    // page) must not refuse itself: it is handed the mark as it stood before its own.
    @Test
    fun `a tool's own mark is not handed to it, a mark from an earlier read is`() = runBlocking<Unit> {
        val draft = JSONObject().put("action", "draft").put("to", "someone@example.invalid").put("subject", "x").put("body", "y")
        val first = Seen()
        loop(Scripted(ok(foldedEv("email", draft)), ok(textEv("Drafted.")))).answer(prefix, contents, first, Collect())
        assertEquals(listOf("email" to false), first.calls)
        val after = Seen()
        loop(Scripted(ok(foldedEv("look_up", newestMail)), ok(foldedEv("email", draft)), ok(textEv("Not drafted.")))).answer(prefix, contents, after, Collect())
        assertEquals(listOf("look_up" to false, "email" to true), after.calls)
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
    fun `a first turn makes no cache, the second does, and the third reuses it (cost cut 4)`() = runBlocking<Unit> {
        var creates = 0
        val store = ContextCacheStore({ creates++; CacheCreateOutcome.Created("cachedContents/c2") }, CoroutineScope(Dispatchers.Unconfined))
        val first = Scripted(ok(textEv("One.")))
        loop(first, cache = store, mayCreateCache = { false }).answer(prefix, contents, okTool, Collect())
        assertEquals(0, creates)
        assertFalse(first.bodies[0].has("cachedContent"))
        val second = Scripted(ok(textEv("Two.")))
        loop(second, cache = store).answer(prefix, contents, okTool, Collect())
        assertEquals(1, creates)
        assertFalse(second.bodies[0].has("cachedContent"))
        val third = Scripted(ok(textEv("Three.")))
        loop(third, cache = store, mayCreateCache = { false }).answer(prefix, contents, okTool, Collect())
        assertEquals(1, creates)
        assertEquals("cachedContents/c2", third.bodies[0].getString("cachedContent"))
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
        // No tool called, a line that is not exactly a filler: said as it is, one round.
        val a = Scripted(ok(textEv("On it, timer set.")))
        val ra = loop(a).answer(prefix, contents, okTool, Collect())
        assertEquals("On it, timer set.", ra.text)
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

    // --- a holding line with no tool is never the answer (2026-11-19, trace item e) ----------------

    @Test
    fun `a reply that is only a holding line, no tool, is withheld and asked once more with tools`() = runBlocking<Unit> {
        val t = Scripted(ok(textEv("Let "), textEv("me look.")), ok(textEv("It closes at nine.")))
        val l = Collect()
        val r = loop(t).answer(prefix, contents, okTool, l)
        assertNull(r.failure)
        assertEquals(1, r.fillerContinued)
        assertEquals(2, t.bodies.size)
        assertEquals("It closes at nine.", l.text.toString())
        val again = t.bodies[1]
        assertTrue("tools stay available", mode(again) != "NONE")
        val c = again.getJSONArray("contents")
        assertEquals("model", c.getJSONObject(c.length() - 2).getString("role"))
        assertEquals(FillerTurn.FIRST_NOTE, c.getJSONObject(c.length() - 1).getJSONArray("parts").getJSONObject(0).getString("text"))
    }

    @Test
    fun `asked again, the model may still call the tool it meant to`() = runBlocking<Unit> {
        val t = Scripted(ok(textEv("Checking.")), ok(callEv("get_weather")), ok(textEv("It is mild.")))
        val l = Collect()
        val r = loop(t).answer(prefix, contents, okTool, l)
        assertNull(r.failure)
        assertEquals(listOf(listOf("get_weather")), l.calls)
        assertEquals("It is mild.", l.text.toString())
    }

    @Test
    fun `a holding line twice with no tool ends on the failure line, two rounds, nothing of it spoken`() = runBlocking<Unit> {
        val t = Scripted(ok(textEv("Checking. Let me look.")), ok(textEv("One moment.")))
        val l = Collect()
        val r = loop(t).answer(prefix, contents, okTool, l)
        assertEquals(TextFailure.NO_TEXT, r.failure)
        assertTrue(r.fillerAgain)
        assertEquals(2, t.bodies.size)
        assertEquals("", l.text.toString())
    }

    @Test
    fun `filler lines before content are dropped, joined or not, and the content streams in one round`() = runBlocking<Unit> {
        for ((said, heard) in listOf(
            "Checking.It is mild." to "It is mild.",
            "Checking. Let me look. It is mild." to "It is mild.",
            "Only two left." to "Only two left.",
            "One moment, please hold the line steady." to "One moment, please hold the line steady.",
        )) {
            val t = Scripted(ok(*said.chunked(4).map { textEv(it) }.toTypedArray()))
            val l = Collect()
            val r = loop(t).answer(prefix, contents, okTool, l)
            assertEquals(said, 1, t.bodies.size)
            assertEquals(said, 0, r.fillerContinued)
            assertEquals(said, heard, l.text.toString())
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
    fun `the factory board's words are spoken but never handed to the model`() = runBlocking<Unit> {
        val board = "Fresh. 1 in flight. Working on Pick a teapot."
        val tool = TextToolExecutor { _, _, _ ->
            com.kevin.legion.ai.VerbatimSpeech.markPrivate(
                JSONObject().put("success", true).put("message", "Fresh. 1 in flight."),
                board,
                "claude_session",
            )
        }
        val t = Scripted(ok(callEv("get_weather")), ok(textEv("Anything else?")))
        val l = Collect()
        val r = loop(t).answer(prefix, contents, tool, l)
        assertTrue(r.verbatimSpoken)
        assertTrue(l.text.toString().startsWith(board))
        // The second request (after the tool) carries the tool's result: the counts, not the words.
        val sent = t.bodies[1].toString()
        assertFalse(sent, sent.contains("teapot"))
        assertTrue(sent, sent.contains("Fresh. 1 in flight."))
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

    // --- a filler after a finished answer (2026-11-17 phone logs) -------------------------------

    @Test
    fun `a wait line after real content is never spoken, in the same round or a later one`() = runBlocking<Unit> {
        // One round, no tool: the answer, then a wait line.
        val a = Scripted(ok(textEv("I missed that. "), textEv("Give me a second on that.")))
        val la = Collect()
        val ra = loop(a).answer(prefix, contents, okTool, la)
        assertEquals("I missed that. ", la.text.toString())
        assertEquals("I missed that.", ra.text)
        assertEquals(1, ra.trailingFillerDropped)
        // A verbatim answer, then the model's own "Just a second." in the next round.
        val b = Scripted(ok(callEv("get_weather")), ok(textEv("Just a second.")))
        val lb = Collect()
        val rb = loop(b).answer(prefix, contents, diagTool(), lb)
        assertNull(rb.failure)
        assertEquals(diagWords, rb.text)
        assertEquals(0, rb.fillerContinued)
    }

    @Test
    fun `a reply that only waits, or opens with a wait before content, is left as it was`() = runBlocking<Unit> {
        val a = Scripted(ok(textEv("Just a second.")))
        val la = Collect()
        assertEquals("Just a second.", loop(a).answer(prefix, contents, okTool, la).text)
        val b = Scripted(ok(textEv("Hang on. "), textEv("It is mild today.")))
        val lb = Collect()
        val rb = loop(b).answer(prefix, contents, okTool, lb)
        assertEquals("Hang on. It is mild today.", lb.text.toString())
        assertEquals(0, rb.trailingFillerDropped)
    }
}

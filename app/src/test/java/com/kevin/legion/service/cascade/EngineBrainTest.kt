package com.kevin.legion.service.cascade

import androidx.test.core.app.ApplicationProvider
import com.kevin.legion.service.TextToolExecutor
import com.kevin.legion.service.cascade.FakeEngineWire.Companion.event
import com.kevin.legion.service.cascade.FakeEngineWire.Companion.json
import com.kevin.legion.service.cascade.FakeEngineWire.Companion.sse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The engine brain for one conversation: the attach (the engine is the brain only on `brain:
 * engine`), the before-audio decision, which brain answers a turn, the phone's own gate on what it
 * runs (review B-1, B-2), read-through marks on a cut-off turn (B-13), and the K3 budget (B-10).
 * Invented data only.
 */
@RunWith(RobolectricTestRunner::class)
class EngineBrainTest {
    private val wire = FakeEngineWire()
    private val logs = CopyOnWriteArrayList<String>()
    private val ran = CopyOnWriteArrayList<String>()
    private val outsideMarks = CopyOnWriteArrayList<String>()
    private val open = EngineBrainRules.DeviceState(locked = false, screenOn = true)
    private val his = EngineBrain.RouteFacts(test = false, fromUser = true, engineHeard = true)

    /** The phone's offer for the tests: real relayed tools, a small invented schema each. */
    private fun offer(): EngineLimbManifest.Offer {
        val names = listOf("clock", "show_agenda_modal", "clear_codes", "get_current_location")
        val tools = names.associateWith { EngineLimbManifest.tool(it, EngineLimbManifest.RELAYED.getValue(it)) }
        val action = JSONObject().put("type", "STRING").put("enum", org.json.JSONArray(listOf("set", "left")))
        val schema = JSONObject().put("type", "OBJECT").put("properties", JSONObject().put("action", action))
        return EngineLimbManifest.Offer(JSONObject(), tools, names.associateWith { schema })
    }

    private fun brain(scope: CoroutineScope, state: EngineBrainRules.DeviceState = open) =
        EngineBrain(wire, scope, { state }, { logs.add(it) }, tail = scope)

    private suspend fun attached(b: EngineBrain): EngineBrain {
        b.attach { offer() }
        while (b.state == EngineBrain.State.ATTACHING) delay(1)
        return b
    }

    @Test
    fun `only brain engine makes the engine the brain, and a dark or lost engine leaves every turn to the phone`() =
        runBlocking {
            assertEquals(EngineBrain.State.ON, attached(brain(this)).state)
            for (answer in listOf(
                json(200, """{"brain": "phone"}"""),
                json(404, """{"detail": "switched off"}"""),
                json(503, "{}"),
                WireReply.Unreachable("ConnectException"),
            )) {
                wire.attachAnswer = answer
                val b = attached(brain(this))
                assertEquals(EngineBrain.State.OFF, b.state)
                assertNull("the phone transcribes", b.utterance(cloudVoice = true))
                assertNull("the phone answers", b.route(null, "what time is it", his.copy(engineHeard = false)))
            }
        }

    @Test
    fun `one failed turn says so and listens, the second in a row closes (K3)`() = runBlocking {
        val b = attached(brain(this))
        assertFalse(b.failed(missed = false))
        assertTrue(b.failed(missed = false))
        assertFalse("a missed transcript is not the engine failing", brain(this).failed(missed = true))
    }

    @Test
    fun `the words for a lost turn are true as said`() = runBlocking {
        val b = attached(brain(this))
        wire.streamAnswers.add(WireReply.Unreachable("SocketTimeoutException"))
        val u = checkNotNull(b.utterance(cloudVoice = true))
        speak(u)
        assertEquals("it went out: it may still finish", EngineBrainRules.NO_ANSWER_LINE, EngineBrain.lostWords(u))
        assertEquals("posted again only under the same turn id", u.turnId, wire.clips.single()["turn_id"])
    }

    @Test
    fun `mail words go to the phone loop and the held turn is taken back`() = runBlocking {
        wire.streamAnswers.add(heard("read my email"))
        val b = attached(brain(this))
        val u = checkNotNull(b.utterance(cloudVoice = true))
        speak(u)
        assertNull(b.route(u, "read my email", his))
        delay(20)
        assertEquals("take, never go", listOf("take:${u.turnId}"), wire.decisions)
        assertTrue(logs.any { it == "engine_route brain=phone reason=mail" })
    }

    @Test
    fun `a reply to a phone line streams held, with the line, the clock and no speech in the metadata`() =
        runBlocking {
            wire.streamAnswers.add(heard("yes please"))
            val b = attached(brain(this))
            b.context.recorded("", "Want me to read the list?", readThrough = false, fromUser = false)
            val u = checkNotNull(b.utterance(cloudVoice = true))
            speak(u)
            val meta = wire.uploads.single().meta
            assertEquals(false, meta.getBoolean("go"))
            assertEquals(false, meta.getBoolean("speak"))
            assertEquals(u.turnId, meta.getString("turn_id"))
            val facts = meta.getJSONObject("client_facts")
            assertEquals("Want me to read the list?", facts.getString("prior_line"))
            assertTrue(facts.has("utc_offset_minutes"))
        }

    @Test
    fun `a phone-kept read-through turn goes blanked, with carry, and the engine's turn clears them`() =
        runBlocking {
            val b = attached(brain(this))
            b.context.recorded("read my mail", "Two new messages from work.", readThrough = true, fromUser = true)
            val facts = b.context.facts()
            assertTrue(facts.getBoolean("carry_tainted"))
            assertFalse("never a read-through line as prior_line", facts.has("prior_line"))
            val turn = facts.getJSONArray("phone_turns").getJSONObject(0)
            assertEquals("read my mail", turn.getString("said"))
            assertEquals("", turn.getString("answer"))
            assertTrue(turn.getBoolean("tainted"))
            b.context.engineAnswering()
            assertFalse(b.context.facts().has("phone_turns"))
        }

    @Test
    fun `a cut-off engine reply is reported on the next turn as heard_until`() = runBlocking {
        val b = attached(brain(this))
        val turnEvent = event("turn", """{"turn_id": "t7", "seq": 1}""")
        wire.textAnswers.add(stream(turnEvent, sentence("A long answer."), doneEvent))
        b.answer(checkNotNull(b.route(null, "tell me a story", his)), null, executor(), listener())
        b.context.cutOff(4)
        val heard = b.context.facts().getJSONObject("heard_until")
        assertEquals("t7", heard.getString("turn_id"))
        assertEquals(4, heard.getInt("chars"))
    }

    @Test
    fun `while the engine attaches the phone transcribes and answers, then his turns stream`() = runBlocking {
        val gate = CompletableDeferred<WireReply>()
        val slow = object : EngineWire by wire {
            override suspend fun attach(manifest: JSONObject): WireReply = gate.await()
        }
        val late = EngineBrain(slow, this, { open }, { logs.add(it) }, tail = this)
        late.attach { offer() }
        assertNull("the phone transcribes", late.utterance(cloudVoice = true))
        assertNull(late.route(null, "brief me", his.copy(fromUser = false, engineHeard = false)))
        gate.complete(json(200, """{"brain": "engine"}"""))
        while (late.state == EngineBrain.State.ATTACHING) delay(1)
        assertNotNull("the engine is the brain now", late.utterance(cloudVoice = true))
    }

    @Test
    fun `a laptop-voice turn reaches the engine as a transcript`() = runBlocking {
        val b = attached(brain(this))
        assertNull("the laptop keeps its own transcriber", b.utterance(cloudVoice = false))
        wire.textAnswers.add(stream(sentence("It's noon."), doneEvent))
        val start = checkNotNull(b.route(null, "what time is it", his))
        val result = b.answer(start, null, executor(), listener())
        assertEquals("It's noon.", result.text)
        assertEquals("what time is it", wire.texts.single().getString("transcript"))
    }

    @Test
    fun `a whole relayed turn, request, the phone's executor, result, the resumed sentence`() = runBlocking {
        val heardText = StringBuilder()
        relayed("set a timer", request("k1", "clock", args = """{"action": "set"}"""), heardText = heardText)
        assertEquals(listOf("clock"), ran)
        assertEquals("ok", wire.posts.single().second.getString("outcome"))
        assertTrue("the phone's go was posted", wire.decisions.single().startsWith("go:"))
        // Each sentence reaches the reply speaker with its ending space: the splitter releases it at once.
        assertEquals("Timer set. ", heardText.toString())
        assertEquals(listOf("Timer set."), SentenceSplitter().feed(heardText.toString()))
        assertTrue(logs.any { it.startsWith("engine_turn route=stream") && it.contains("relayed=1") })
        assertFalse("no words in the timing line", logs.any { it.startsWith("engine_turn") && it.contains("imer") })
    }

    @Test
    fun `a tool the phone never offered is refused as not_offered and nothing runs`() = runBlocking {
        relayed("look that up", request("k1", "read_mail"))
        relayed("do it", request("k2", "manage_item", writes = true))
        assertTrue("never ran", ran.isEmpty())
        for ((_, post) in wire.posts) {
            assertEquals("refused", post.getString("outcome"))
            assertTrue(post.getString("words").startsWith(EngineCallGate.NOT_OFFERED))
            assertFalse("no result posted", post.has("result"))
        }
    }

    @Test
    fun `arguments outside the tool's own schema are refused`() = runBlocking {
        relayed("set a timer", request("k1", "clock", args = """{"action": "set", "to": "someone@example.com"}"""))
        relayed("set a timer", request("k2", "clock", args = """{"action": "wipe"}"""))
        relayed("set a timer", request("k3", "clock", args = """{"action": 5}"""))
        assertTrue(ran.isEmpty())
        assertEquals(listOf("refused", "refused", "refused"), wire.posts.map { it.second.getString("outcome") })
    }

    @Test
    fun `the phone's own flags win, so an engine that says a write is a read is still refused when tainted`() =
        runBlocking {
            relayed("clear the codes", request("k1", "clear_codes", writes = false, tainted = true))
            assertTrue(ran.isEmpty())
            assertEquals("refused", wire.posts.single().second.getString("outcome"))
        }

    @Test
    fun `a screen call on a locked phone is refused at once from the phone's own needs`() = runBlocking {
        val locked = EngineBrainRules.DeviceState(locked = true, screenOn = true)
        relayed("show my agenda", request("k1", "show_agenda_modal"), state = locked)
        assertTrue("never ran", ran.isEmpty())
        val post = wire.posts.single().second
        assertEquals("refused", post.getString("outcome"))
        assertEquals("Not done: the phone is locked.", post.getString("words"))
    }

    @Test
    fun `an outside read in a round taints a later write in the same round on the phone`() = runBlocking {
        wire.resultAnswers.add(json(202, """{"waiting_for": ["k2"]}"""))
        relayed(
            "where am I and clear the codes",
            request("k1", "get_current_location"),
            request("k2", "clear_codes", writes = true),
            calls = listOf("k1", "k2"),
        )
        assertEquals(listOf("get_current_location"), ran)
        assertEquals(listOf("ok", "refused"), wire.posts.map { it.second.getString("outcome") })
        assertTrue("marked the moment the outside read started", outsideMarks.isNotEmpty())
    }

    @Test
    fun `a turn cut off after an engine tool ran is marked read-through before it is stored`() = runBlocking {
        val events = kotlinx.coroutines.channels.Channel<EngineEvent>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        events.trySend(EngineEvent.Turn("t1", "c1", 1, 1))
        events.trySend(EngineEvent.Tool("web_search", true, "ok", null))
        val b = attached(brain(this))
        val job = async { b.answer({ WireReply.Events(events) {} }, null, executor(), listener()) }
        delay(20)
        job.cancel()
        delay(20)
        assertEquals(listOf("engine"), outsideMarks)
        assertEquals(listOf("t1"), wire.cancels)
    }

    @Test
    fun `a go after the engine's hold ran out ends the turn in true words, nothing acted`() = runBlocking {
        wire.goAnswer = json(409, """{"code": "turn_over"}""")
        wire.streamAnswers.add(heard("what's on today"))
        val b = attached(brain(this))
        val u = checkNotNull(b.utterance(cloudVoice = true))
        speak(u)
        val result = b.answer(checkNotNull(b.route(u, "what's on today", his)), u, executor(), listener())
        assertEquals(EngineBrainRules.NO_ANSWER_LINE, result.failureWords)
        assertEquals("", result.text)
    }

    @Test
    fun `a tool the engine dropped at attach is never run here either`() = runBlocking {
        val answer = """{"brain": "engine", "dropped": [{"name": "clock", "reason": "engine_tool"}]}"""
        wire.attachAnswer = json(200, answer)
        relayed("set a timer", request("k1", "clock", args = """{"action": "set"}"""))
        assertTrue(ran.isEmpty())
        assertEquals("refused", wire.posts.single().second.getString("outcome"))
        assertTrue(logs.any { it.startsWith("engine_attach dropped=1 tools=clock") })
    }

    @Test
    fun `brain_phone on a turn sends that turn and the conversation to the phone`() = runBlocking {
        wire.streamAnswers.add(json(409, """{"code": "brain_phone"}"""))
        wire.clipAnswers.add(json(409, """{"code": "brain_phone"}"""))
        val b = attached(brain(this))
        val u = checkNotNull(b.utterance(cloudVoice = true))
        val pcm = ByteArray(32_000) { i -> if (i in 8_000 until 24_000) ((i * 37) % 200 - 100).toByte() else 0 }
        u.audio(pcm, 0, pcm.size)
        u.end()
        assertEquals(SttResult.Failed(EngineUtterance.SWITCHED_OFF), u.result(1_000))
        assertEquals(EngineBrain.State.OFF, b.state)
    }

    @Test
    fun `an engine write's fact reaches the done line, and a local tool gets no holding line`() = runBlocking {
        val facts = CopyOnWriteArrayList<String>()
        val started = CopyOnWriteArrayList<String>()
        val l = object : GeminiTextLoop.Listener {
            override fun onWriteFact(fact: ScriptedLines.WriteFact) {
                facts.add(fact.title)
            }

            override fun onToolsStarting(calls: List<Pair<String, JSONObject>>) {
                started.addAll(calls.map { it.first })
            }
        }
        val b = attached(brain(this))
        val fact = """{"tool": "add_checklist_item", "title": "stamps", "list": "To-do"}"""
        wire.textAnswers.add(
            stream(
                event("working", """{"tool": "get_time", "kind": "local"}"""),
                event("working", """{"tool": "add_checklist_item", "kind": "write"}"""),
                event("tool", """{"name": "add_checklist_item", "ok": true, "outcome": "ok", "write_fact": $fact}"""),
                doneEvent,
            ),
        )
        b.answer(checkNotNull(b.route(null, "add stamps", his)), null, executor(), l)
        assertEquals(listOf("add_checklist_item"), started)
        assertEquals(listOf("stamps"), facts)
    }

    @Test
    fun `with the switch off nothing is built and no transport is ever made`() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        EngineBrainSettings.setOn(ctx, false)
        val scope = CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
        assertNull(EngineBrainOpen.open(ctx, scope) { error("no transport: switch off") })
        // On, but this phone is not signed in to an engine: still nothing.
        EngineBrainSettings.setOn(ctx, true)
        assertNull(EngineBrainOpen.open(ctx, scope) { error("no transport: signed out") })
        EngineBrainSettings.setOn(ctx, false)
    }

    @Test
    fun `the developer switch is set over the adb door only, and reads back`() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        assertEquals(true, EngineBrainSettings.fromAdb(ctx, "on")["engine_brain"])
        assertEquals(true, EngineBrainSettings.fromAdb(ctx, null)["engine_brain"])
        assertEquals(false, EngineBrainSettings.fromAdb(ctx, "off")["engine_brain"])
    }

    // --- fixtures ----------------------------------------------------------------------------------

    private fun executor() = TextToolExecutor { name, _, _ ->
        ran.add(name)
        JSONObject().put("success", true).put("message", "Done.")
    }

    private fun listener(heard: StringBuilder = StringBuilder()) = object : GeminiTextLoop.Listener {
        override fun onText(delta: String) {
            heard.append(delta)
        }

        override fun onReadThrough(source: String) {
            outsideMarks.add(source)
        }
    }

    /** One engine turn that relays [requests] (round order [calls]) and resumes with one sentence. */
    private suspend fun CoroutineScope.relayed(
        words: String,
        vararg requests: String,
        calls: List<String> = listOf(requests.first().substringAfter("call_id\": \"").substringBefore("\"")),
        state: EngineBrainRules.DeviceState = open,
        heardText: StringBuilder = StringBuilder(),
    ) {
        val ids = calls.joinToString { "\"$it\"" }
        val awaiting = event("done", """{"status": "awaiting_limb", "calls": [$ids]}""")
        wire.streamAnswers.add(heard(words, *requests, awaiting))
        wire.resultAnswers.add(stream(sentence("Timer set."), doneEvent))
        val b = attached(brain(this, state))
        val u = checkNotNull(b.utterance(cloudVoice = true))
        speak(u)
        b.answer(checkNotNull(b.route(u, words, his)), u, executor(), listener(heardText))
    }

    private fun stream(vararg parts: String) = sse(parts.joinToString("\n"))

    private val doneEvent = event("done", """{"status": "done"}""")

    private fun sentence(text: String) = event("sentence", """{"text": "$text", "line_id": "1.1.1"}""")

    private fun request(
        id: String,
        name: String,
        writes: Boolean = false,
        tainted: Boolean = false,
        args: String = "{}",
    ) = event(
        "tool_request",
        """{"turn_id": "t1", "call_id": "$id", "name": "$name", "args": $args, "writes": $writes,
        "tainted": $tainted, "deadline_ms": 20000, "needs": [], "confirm": "limb"}""".replace("\n", " "),
    )

    private fun heard(words: String, vararg then: String): WireReply.Events {
        val rest = if (then.isEmpty()) listOf(sentence("Sure."), doneEvent) else then.toList()
        val head = listOf(
            event("waiting", "{}"),
            event("turn", """{"turn_id": "t1", "conversation_id": "c1", "seq": 1}"""),
            event("heard", """{"text": "$words"}"""),
        )
        return sse((head + rest).joinToString("\n"))
    }

    /** One second of a voiced tone, then the phone's end of turn; waits for the engine's words. */
    private suspend fun speak(u: EngineUtterance) {
        val pcm = ByteArray(32_000) { i -> if (i in 8_000 until 24_000) ((i * 37) % 200 - 100).toByte() else 0 }
        u.audio(pcm, 0, pcm.size)
        u.end()
        u.result(1_000)
    }
}

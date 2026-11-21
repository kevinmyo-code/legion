package com.kevin.legion.service.cascade

import androidx.test.core.app.ApplicationProvider
import com.kevin.legion.service.TextToolExecutor
import com.kevin.legion.service.cascade.FakeEngineWire.Companion.event
import com.kevin.legion.service.cascade.FakeEngineWire.Companion.json
import com.kevin.legion.service.cascade.FakeEngineWire.Companion.sse
import kotlinx.coroutines.CoroutineScope
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
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The engine brain for one conversation: the attach and its answers (K3), which brain answers a
 * turn, and a whole relayed turn through the cascade's own listener and tool executor. Plus the
 * proof that with the setting off nothing of it is built. Invented data only.
 */
@RunWith(RobolectricTestRunner::class)
class EngineBrainTest {
    private val wire = FakeEngineWire()
    private val logs = CopyOnWriteArrayList<String>()
    private val open = EngineBrainRules.DeviceState(locked = false, screenOn = true)
    private val his = EngineBrain.RouteFacts(test = false, fromUser = true, carryMarked = false)

    private fun brain(scope: CoroutineScope, state: EngineBrainRules.DeviceState = open, line: Boolean = false) =
        EngineBrain(wire, scope, { state }, { logs.add(it) }, line)

    private suspend fun attached(b: EngineBrain, onLost: () -> Unit = {}): EngineBrain {
        b.attach({ JSONObject().put("tools", org.json.JSONArray()) }, onLost)
        while (b.state == EngineBrain.State.ATTACHING) delay(1)
        return b
    }

    @Test
    fun `brain engine turns it on, brain phone or a switched-off engine leaves the phone loop`() = runBlocking {
        assertEquals(EngineBrain.State.ON, attached(brain(this)).state)
        wire.attachAnswer = json(200, """{"brain": "phone", "brain_reason": "default"}""")
        val phone = attached(brain(this))
        assertEquals(EngineBrain.State.OFF, phone.state)
        assertNull("the phone transcribes", phone.utterance(cloudVoice = true))
        wire.attachAnswer = json(404, """{"detail": "switched off"}""")
        assertEquals(EngineBrain.State.OFF, attached(brain(this)).state)
    }

    @Test
    fun `unreachable at open follows K3, say so and close, no phone loop`() = runBlocking {
        wire.attachAnswer = WireReply.Unreachable("ConnectException")
        var lost = 0
        val b = attached(brain(this)) { lost++ }
        assertEquals(EngineBrain.State.UNREACHABLE, b.state)
        assertEquals(1, lost)
        assertNull(b.utterance(cloudVoice = true))
    }

    @Test
    fun `mail words go to the phone loop and the engine's turn is cancelled`() = runBlocking {
        wire.streamAnswers.add(heard("read my email"))
        val b = attached(brain(this))
        val u = checkNotNull(b.utterance(cloudVoice = true))
        speak(u)
        assertNull(b.route(u, "read my email", his))
        delay(10)
        assertEquals(listOf("t1"), wire.cancels)
        assertTrue(logs.any { it == "engine_route brain=phone reason=mail" })
    }

    @Test
    fun `the turn after a phone-spoken line stays on the phone, the engine has no prior line yet`() = runBlocking {
        val b = attached(brain(this, line = true))
        assertNull(b.route(null, "yes please", his))
        assertNotNull("then his own turns go to the engine", b.route(null, "what time is it", his))
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
        wire.streamAnswers.add(heard("set a timer for ten minutes", request("k1", "clock")))
        wire.resultAnswers.add(stream(sentence("Timer set for ten minutes."), doneEvent))
        val b = attached(brain(this))
        val u = checkNotNull(b.utterance(cloudVoice = true))
        speak(u)
        val start = checkNotNull(b.route(u, "set a timer for ten minutes", his))
        val heardText = StringBuilder()
        val result = b.answer(start, u, executor(), listener(heardText))
        assertEquals(listOf("clock"), ran)
        assertEquals("ok", wire.posts.single().second.getString("outcome"))
        assertEquals("Timer set for ten minutes.", result.text)
        assertEquals("Timer set for ten minutes.", heardText.toString())
        assertTrue(logs.any { it.startsWith("engine_turn route=stream") && it.contains("relayed=1") })
        assertFalse("no words in the timing line", logs.any { it.startsWith("engine_turn") && it.contains("timer") })
    }

    @Test
    fun `a screen call on a locked phone is refused at once, never run`() = runBlocking {
        val screenCall = request("k1", "show_agenda_modal", needs = """["screen", "unlock"]""")
        wire.streamAnswers.add(heard("show my agenda", screenCall))
        val locked = EngineBrainRules.DeviceState(locked = true, screenOn = true)
        val b = attached(brain(this, locked))
        val u = checkNotNull(b.utterance(cloudVoice = true))
        speak(u)
        b.answer(checkNotNull(b.route(u, "show my agenda", his)), u, executor(), listener())
        assertTrue("never ran", ran.isEmpty())
        val post = wire.posts.single().second
        assertEquals("refused", post.getString("outcome"))
        assertEquals("Not done: the phone is locked.", post.getString("words"))
    }

    @Test
    fun `a tainted turn's write is refused on the phone too`() = runBlocking {
        wire.streamAnswers.add(heard("clear the codes", request("k1", "clear_codes", writes = true, tainted = true)))
        val b = attached(brain(this))
        val u = checkNotNull(b.utterance(cloudVoice = true))
        speak(u)
        b.answer(checkNotNull(b.route(u, "clear the codes", his)), u, executor(), listener())
        assertTrue(ran.isEmpty())
        assertEquals("refused", wire.posts.single().second.getString("outcome"))
    }

    @Test
    fun `with the setting off nothing is built and nothing is read past the setting`() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        EngineBrainSettings.setOn(ctx, false)
        val scope = CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
        assertNull(EngineBrainOpen.open(ctx, scope, openedWithLine = false) { error("never called") })
        assertFalse(EngineBrainSettings.on(ctx))
    }

    @Test
    fun `every hook in the cascade is behind the brain, which is null with the setting off`() {
        val here = File("src/main/java/com/kevin/legion/service/cascade/CascadeSession.kt")
        val src = (if (here.isFile) here else File("app", here.path)).readText()
        assertEquals("one place builds it", 1, Regex("EngineBrainOpen\\.open\\(").findAll(src).count())
        assertTrue(src.contains("engineBrain?.utterance(") && src.contains("?: b.stt.begin"))
        assertTrue(src.contains("brain?.route("))
        assertTrue(src.contains("engineBrain?.runDeviceActions()"))
        assertTrue("no drafts on the engine brain", src.contains("&& engineBrain == null"))
        for (line in src.lines().filter { it.contains("engineBrain") && !it.trim().startsWith("*") }) {
            val guarded = line.contains("engineBrain?.") || line.contains("engineBrain = ") ||
                line.contains("engineBrain == null") || line.contains("val brain = engineBrain") ||
                line.contains("private var engineBrain")
            assertTrue("unguarded: $line", guarded)
        }
    }

    // --- fixtures ----------------------------------------------------------------------------------

    private val ran = CopyOnWriteArrayList<String>()

    private fun executor() = TextToolExecutor { name, _, _ ->
        ran.add(name)
        JSONObject().put("success", true).put("message", "Done.")
    }

    private fun listener(heard: StringBuilder = StringBuilder()) = object : GeminiTextLoop.Listener {
        override fun onText(delta: String) {
            heard.append(delta)
        }
    }

    private fun stream(vararg parts: String) = sse(parts.joinToString("\n"))

    private val doneEvent = event("done", """{"status": "done"}""")

    private fun sentence(text: String) = event("sentence", """{"text": "$text", "line_id": "1.1.1"}""")

    private fun request(
        id: String,
        name: String,
        writes: Boolean = false,
        tainted: Boolean = false,
        needs: String = "[]",
    ) =
        event(
            "tool_request",
            """{"turn_id": "t1", "call_id": "$id", "name": "$name", "args": {}, "writes": $writes,
            "tainted": $tainted, "deadline_ms": 20000, "needs": $needs, "confirm": "limb"}""".replace("\n", " "),
        )

    private fun heard(words: String, vararg then: String): WireReply.Events {
        val awaiting = if (then.isEmpty()) {
            listOf(sentence("Sure."), event("done", """{"status": "done"}"""))
        } else {
            then.toList() + event("done", """{"status": "awaiting_limb", "calls": ["k1"]}""")
        }
        val head = listOf(
            event("waiting", "{}"),
            event("turn", """{"turn_id": "t1", "conversation_id": "c1", "seq": 1}"""),
            event("heard", """{"text": "$words"}"""),
        )
        return sse((head + awaiting).joinToString("\n"))
    }

    /** One second of a voiced tone, then the phone's end of turn; waits for the engine's words. */
    private suspend fun speak(u: EngineUtterance) {
        val pcm = ByteArray(32_000) { i -> if (i in 8_000 until 24_000) ((i * 37) % 200 - 100).toByte() else 0 }
        u.audio(pcm, 0, pcm.size)
        u.end()
        u.result(1_000)
    }
}

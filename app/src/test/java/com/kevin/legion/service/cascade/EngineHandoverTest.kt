package com.kevin.legion.service.cascade

import com.kevin.legion.service.cascade.FakeEngineWire.Companion.event
import com.kevin.legion.service.cascade.FakeEngineWire.Companion.json
import com.kevin.legion.service.cascade.FakeEngineWire.Companion.sse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
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

/**
 * Who answers a turn when the phone and the engine hand over (re-check round 2): his answer to the
 * PHONE's own question stays on the phone (R1); a turn queued behind another is taken back at once
 * rather than its go arriving after the engine's hold ran out (R2); a turn kept for mail reaches the
 * engine blanked (R3); the skip-once flag cannot stick (R5); nested arguments are checked and an
 * unknown schema type fails closed (R6); every engine hook in the cascade stays null-safe. Invented
 * data. Robolectric only for `org.json`.
 */
@RunWith(RobolectricTestRunner::class)
class EngineHandoverTest {
    private val wire = FakeEngineWire()
    private val his = EngineBrain.RouteFacts(test = false, fromUser = true, engineHeard = true)
    private val open = EngineBrainRules.DeviceState(locked = false, screenOn = true)

    private suspend fun brain(scope: CoroutineScope): EngineBrain {
        val b = EngineBrain(wire, scope, { open }, {}, tail = scope)
        b.attach { EngineLimbManifest.Offer(JSONObject(), emptyMap(), emptyMap()) }
        while (b.state == EngineBrain.State.ATTACHING) delay(1)
        return b
    }

    private fun heard(words: String) = sse(
        listOf(
            event("turn", """{"turn_id": "t1", "seq": 1}"""),
            event("heard", """{"text": "$words"}"""),
            event("done", """{"status": "done"}"""),
        ).joinToString("\n"),
    )

    private suspend fun speak(u: EngineUtterance) {
        val pcm = ByteArray(32_000) { i -> if (i in 8_000 until 24_000) ((i * 37) % 200 - 100).toByte() else 0 }
        u.audio(pcm, 0, pcm.size)
        u.end()
        u.result(1_000)
    }

    // --- R1: the phone's own question --------------------------------------------------------------

    @Test
    fun `each kind of phone question keeps his answer on the phone, a plain greeting does not`() {
        // (checkIn, toolAsked, phoneQuestion)
        assertEquals("check_in", EngineBrainRules.phoneAwaits(true, false, false))
        assertEquals("phone_confirm", EngineBrainRules.phoneAwaits(false, true, false))
        assertEquals("phone_question", EngineBrainRules.phoneAwaits(false, false, true))
        assertNull(EngineBrainRules.phoneAwaits(false, false, false))
        val waiting = EngineBrainRules.Facts(test = false, fromUser = true, awaits = "phone_confirm")
        assertEquals("phone_confirm", EngineBrainRules.phoneKeeps("yes send it", waiting))
    }

    @Test
    fun `the phone's own question is tracked from what it said, the greeting never counts`() {
        val c = EnginePhoneContext()
        c.greeted("Morning. Anything I can do?")
        assertFalse(c.phoneAsked)
        c.recorded("draft a note to the plumber", "Here's the draft. Send it?", readThrough = false, fromUser = true)
        assertTrue(c.phoneAsked)
        c.recorded("", "Your check-in: did the run go well?", readThrough = false, fromUser = false)
        assertTrue(c.phoneAsked)
        c.engineAnswering()
        assertFalse("the engine answered since", c.phoneAsked)
    }

    @Test
    fun `while the phone waits on his answer nothing starts on the engine and the laptop's words stay too`() =
        runBlocking {
            val b = brain(this)
            assertNull(b.utterance(cloudVoice = true, awaits = "phone_confirm"))
            assertNull(b.route(null, "yes", his.copy(awaits = "phone_confirm")))
            assertTrue(wire.texts.isEmpty())
            assertNotNull("then his turns stream again", b.utterance(cloudVoice = true))
        }

    // --- R2: a turn queued behind another -----------------------------------------------------------

    @Test
    fun `a turn queued behind a slow one is taken back as soon as its words are in, never a late go`() =
        runBlocking {
            wire.streamAnswers.add(heard("and the weather"))
            val b = brain(this)
            val u = checkNotNull(b.utterance(cloudVoice = true))
            speak(u)
            // The session's early step while the previous turn still runs: the words, then the take.
            val words = u.result(0) as SttResult.Text
            u.release()
            delay(SLOW_PREVIOUS_MS)
            // The slow previous turn is done; this turn is routed only now, and stays on the phone.
            assertNull(b.route(u, words.text, his))
            assertEquals("and the weather", words.text)
            assertEquals(listOf("take:${u.turnId}"), wire.decisions)
        }

    // --- R3 and R5: what the engine is told of phone turns ------------------------------------------

    @Test
    fun `a turn kept for mail goes blanked, an ordinary phone turn keeps its words`() = runBlocking {
        val b = brain(this)
        assertNull(b.route(null, "read my email", his.copy(engineHeard = false)))
        assertNull(b.route(null, "read my email", his))
        b.context.recorded("read my email from the landlord", "You have one from the landlord.", false, true)
        assertNull(b.route(null, "hold on", his.copy(engineHeard = false)))
        b.context.recorded("hold on", "Sure.", readThrough = false, fromUser = true)
        val turns = b.context.facts().getJSONArray("phone_turns")
        val mail = turns.getJSONObject(0)
        assertEquals(EnginePhoneContext.KEPT_PRIVATE, mail.getString("said"))
        assertEquals("", mail.getString("answer"))
        assertTrue(mail.getBoolean("tainted"))
        assertEquals("hold on", turns.getJSONObject(1).getString("said"))
        assertFalse("no prior line from a mail turn", b.context.facts().optString("prior_line").contains("landlord"))
    }

    @Test
    fun `an engine turn never recorded (a merge) does not swallow the next phone turn`() {
        val c = EnginePhoneContext()
        c.engineAnswering()
        // Merged away: no record for it. The next turn is routed, then recorded by the phone.
        c.newTurn()
        c.recorded("what's the time", "Ten past nine.", readThrough = false, fromUser = true)
        assertEquals(1, c.facts().getJSONArray("phone_turns").length())
    }

    // --- R6: arguments ------------------------------------------------------------------------------

    private val nested = JSONObject(
        """{"type": "OBJECT", "properties": {
            "when": {"type": "OBJECT", "properties": {"minutes": {"type": "INTEGER"}}, "required": ["minutes"]},
            "tags": {"type": "ARRAY", "items": {"type": "STRING", "enum": ["a", "b"]}},
            "level": {"type": "NUMBER", "enum": [5, 10]}}}""",
    )

    @Test
    fun `nested objects, array items and enums by value are checked`() {
        assertNull(EngineArgs.problem(nested, JSONObject("""{"when": {"minutes": 5}, "tags": ["a"], "level": 5.0}""")))
        assertNotNull(EngineArgs.problem(nested, JSONObject("""{"when": {"minutes": 5, "to": "x@example.com"}}""")))
        assertNotNull(EngineArgs.problem(nested, JSONObject("""{"when": {}}""")))
        assertNotNull(EngineArgs.problem(nested, JSONObject("""{"when": {"minutes": "soon"}}""")))
        assertNotNull(EngineArgs.problem(nested, JSONObject("""{"tags": ["a", "rm -rf"]}""")))
        assertNotNull(EngineArgs.problem(nested, JSONObject("""{"tags": [{"a": 1}]}""")))
        assertNotNull(EngineArgs.problem(nested, JSONObject("""{"level": 7}""")))
        assertNotNull(EngineArgs.problem(nested, JSONObject("""{"level": "5"}""")))
    }

    @Test
    fun `an unknown or missing schema type fails closed`() {
        val odd = JSONObject("""{"type": "OBJECT", "properties": {"x": {"type": "TUPLE"}, "y": {}}}""")
        assertNotNull(EngineArgs.problem(odd, JSONObject("""{"x": 1}""")))
        assertNotNull(EngineArgs.problem(odd, JSONObject("""{"y": 1}""")))
        assertNotNull("a list with no item schema", EngineArgs.problem(JSONObject(
            """{"type": "OBJECT", "properties": {"z": {"type": "ARRAY"}}}""",
        ), JSONObject().put("z", JSONArray().put(1))))
    }

    // --- the cascade's hooks ------------------------------------------------------------------------

    @Test
    fun `every engineBrain use in the cascade is null-safe, so the switch off reaches none of it`() {
        val here = File("src/main/java/com/kevin/legion/service/cascade/CascadeSession.kt")
        val src = (if (here.isFile) here else File("app", here.path)).readText()
        assertEquals("one place builds it", 1, src.split("EngineBrainOpen.open(").size - 1)
        val uses = src.lines().map { it.trim() }
            .filter { it.contains("engineBrain") && !it.startsWith("*") && !it.startsWith("//") }
        assertTrue(uses.isNotEmpty())
        for (line in uses) {
            val guarded = line.contains("engineBrain?.") || line.contains("engineBrain = EngineBrainOpen") ||
                line.contains("val brain = engineBrain") || line.contains("private var engineBrain")
            assertTrue("unguarded: $line", guarded)
        }
        // The local copy and the utterance's engine half are only ever used null-safely too.
        assertFalse(src.contains("u.engine."))
        assertTrue(src.contains("brain?.route(") && src.contains("brain?.answer("))
    }

    private companion object {
        const val SLOW_PREVIOUS_MS = 50L
    }
}

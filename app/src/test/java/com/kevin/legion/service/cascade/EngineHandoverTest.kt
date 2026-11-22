package com.kevin.legion.service.cascade

import com.kevin.legion.service.cascade.FakeEngineWire.Companion.event
import com.kevin.legion.service.cascade.FakeEngineWire.Companion.json
import com.kevin.legion.service.cascade.FakeEngineWire.Companion.sse
import com.kevin.legion.testutil.MainSource
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
    fun `each kind of phone state keeps his answer on the phone, punctuation and the greeting never do`() {
        // (checkIn, toolAsked, mailExchange): state the phone holds, whatever its line ended with.
        assertEquals("check_in", EngineBrainRules.phoneAwaits(true, false, false))
        assertEquals("phone_confirm", EngineBrainRules.phoneAwaits(false, true, false))
        assertEquals("mail_follow_up", EngineBrainRules.phoneAwaits(false, false, true))
        assertNull(EngineBrainRules.phoneAwaits(false, false, false))
        val waiting = EngineBrainRules.Facts(test = false, fromUser = true, awaits = "phone_confirm")
        assertEquals("phone_confirm", EngineBrainRules.phoneKeeps("yes send it", waiting))
    }

    @Test
    fun `an opener or a reply ending in a question holds no state, so his answer goes to the engine`() =
        runBlocking {
            val b = brain(this)
            b.context.greeted("Morning. Anything I can do?")
            b.context.recorded("", "Want a look at today?", readThrough = false, fromUser = false)
            b.context.recorded("what's the weather", "Sunny. Anything else?", readThrough = false, fromUser = true)
            assertFalse(b.context.mailExchange)
            val awaits = EngineBrainRules.phoneAwaits(false, false, b.context.mailExchange)
            assertNull(awaits)
            assertNotNull("his answer streams to the engine", b.utterance(cloudVoice = true, awaits = awaits))
            assertEquals("Sunny. Anything else?", b.context.facts().getString("prior_line"))
        }

    @Test
    fun `the mail exchange does not chain, a follow-up that leaves no state ends it`() = runBlocking {
        val b = brain(this)
        assertNull(b.route(null, "any new email", his))
        b.context.recorded("any new email", "Two new ones.", readThrough = true, fromUser = true)
        assertTrue("a mail turn opens the exchange", b.context.mailExchange)
        // "read the first": kept only because the exchange is open, and it leaves no state behind.
        assertNull(b.route(null, "read the first", his.copy(awaits = "mail_follow_up")))
        b.context.recorded("read the first", "It says the meeting moved.", readThrough = true, fromUser = true)
        assertFalse("ended: the next turn may go to the engine", b.context.mailExchange)
        assertNull(EngineBrainRules.phoneAwaits(false, false, b.context.mailExchange))
    }

    @Test
    fun `a send confirm inside the mail exchange reaches the engine only as the placeholder`() = runBlocking {
        val b = brain(this)
        assertNull(b.route(null, "email the plumber that I'm late", his))
        b.context.recorded("email the plumber that I'm late", "Draft ready. Send it?", false, true)
        // "yes, send it": no mail word; kept by the phone's draft state, private by the open exchange.
        assertNull(b.route(null, "yes, send it", his.copy(awaits = "phone_confirm")))
        b.context.recorded("yes, send it", "Sent your reply to the plumber.", readThrough = false, fromUser = true)
        val turns = b.context.facts().getJSONArray("phone_turns")
        for (i in 0 until turns.length()) {
            assertEquals(EnginePhoneContext.KEPT_PRIVATE, turns.getJSONObject(i).getString("said"))
            assertEquals("", turns.getJSONObject(i).getString("answer"))
        }
        assertFalse(b.context.facts().toString().contains("plumber"))
        assertTrue("the send's state kept the exchange open", b.context.mailExchange)
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

    @Test
    fun `barge-in off, he talks over a reply, the go goes at once and the answer waits for the reply to end`() =
        runBlocking {
            val sentence = event("sentence", """{"text": "It's sunny.", "line_id": "1.1.1"}""")
            wire.streamAnswers.add(
                sse(
                    listOf(
                        event("turn", """{"turn_id": "t1", "seq": 1}"""),
                        event("heard", """{"text": "and the weather"}"""),
                        sentence,
                        event("done", """{"status": "done"}"""),
                    ).joinToString("\n"),
                ),
            )
            val b = brain(this)
            val u = checkNotNull(b.utterance(cloudVoice = true))
            speak(u)
            // The session's early step while the previous reply still plays: the route says engine.
            u.goNow()
            assertEquals("go at once, inside the hold", listOf("go:${u.turnId}"), wire.decisions)
            assertTrue(u.goPosted)
            delay(SLOW_PREVIOUS_MS)
            // The previous reply ended; this turn is reached now. The go is final and not posted again.
            val start = checkNotNull(b.route(u, "and the weather", his.copy(awaits = "phone_confirm")))
            val said = StringBuilder()
            val listener = object : GeminiTextLoop.Listener {
                override fun onText(delta: String) {
                    said.append(delta)
                }
            }
            val executor = com.kevin.legion.service.TextToolExecutor { _, _, _ -> JSONObject() }
            b.answer(start, u, executor, listener)
            assertEquals("spoken once", "It's sunny. ", said.toString())
            assertEquals(listOf("go:${u.turnId}"), wire.decisions)
            assertTrue("reached normally: never cancelled", wire.cancels.isEmpty())
            assertTrue("read: no longer an open early turn", b.earlyTurns.isEmpty())
        }

    // --- round 4: an early go nobody reads is cancelled ---------------------------------------------

    /** One utterance, its early go posted, its stream's close recorded in [closed]. */
    private suspend fun earlyGone(b: EngineBrain, closed: MutableList<String>): EngineUtterance {
        val events = heard("add milk to the list")
        val u = checkNotNull(b.utterance(cloudVoice = true))
        wire.streamAnswers.add(WireReply.Events(events.events) { closed.add(u.turnId) })
        speak(u)
        u.goNow()
        return u
    }

    @Test
    fun `an early go then a stop over it, cancel posted, stream closed, nothing said`() = runBlocking {
        val b = brain(this)
        val closed = mutableListOf<String>()
        val u = earlyGone(b, closed)
        assertEquals(setOf(u), b.earlyTurns)
        // The stop (or a close) releases every early turn nobody has read.
        b.earlyTurns.forEach { it.release() }
        delay(20)
        assertEquals("a cancel, never a take after a go", listOf(u.turnId), wire.cancels)
        assertTrue(wire.decisions.none { it.startsWith("take:") })
        assertEquals(listOf(u.turnId), closed)
        assertTrue("the turn says nothing at all", u.cancelled)
        assertTrue(b.earlyTurns.isEmpty())
        assertNull("routed later, it is not answered by the engine", b.route(u, "add milk to the list", his))
    }

    @Test
    fun `two early turns queued are both cancelled when the conversation closes`() = runBlocking {
        val b = brain(this)
        val closed = mutableListOf<String>()
        val first = earlyGone(b, closed)
        val second = earlyGone(b, closed)
        assertEquals(setOf(first, second), b.earlyTurns)
        b.earlyTurns.forEach { it.release() }
        delay(20)
        assertEquals(setOf(first.turnId, second.turnId), wire.cancels.toSet())
        assertEquals(setOf(first.turnId, second.turnId), closed.toSet())
        assertTrue(first.cancelled && second.cancelled)
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
        val src = MainSource.read(MainSource.CASCADE_SESSION)
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

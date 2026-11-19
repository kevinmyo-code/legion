@file:Suppress("MaxLineLength") // invented JSON and wire frames read best on one line

package com.kevin.legion.service.cascade

import com.kevin.legion.ai.AgentResult
import com.kevin.legion.service.TextFailure
import com.kevin.legion.service.TextToolExecutor
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
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
 * Every way a spoken turn can end after a write went through (2026-11-19): code says the write from
 * its result ([GeminiTextLoop] `writeFact`, [ScriptedLines.doneLine]), once, after the model's text
 * so far; nothing reads the model's words. One test per turn shape. Invented content only;
 * Robolectric only for `org.json`.
 */
@RunWith(RobolectricTestRunner::class)
class DoneLineTurnTest {

    private val prefix = CascadePrefix("model-x", "Invented instruction.", JSONArray())
    private val contents = JSONArray().put(
        JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", "invented"))),
    )

    // --- wire ------------------------------------------------------------------------------------

    private fun parts(vararg p: JSONObject) = JSONObject().put(
        "candidates",
        JSONArray().put(JSONObject().put("content", JSONObject().put("role", "model").put("parts", JSONArray(p.toList())))),
    )

    private fun text(t: String) = parts(JSONObject().put("text", t))

    private fun call(name: String, vararg kv: Pair<String, String>) =
        JSONObject().put("functionCall", JSONObject().put("name", name).put("args", JSONObject().apply { kv.forEach { (k, v) -> put(k, v) } }))

    private fun calls(vararg c: JSONObject) = parts(*c)

    private fun ok(vararg ev: JSONObject) = StreamOutcome.Ok to ev.toList()
    private fun broke(o: StreamOutcome): Pair<StreamOutcome, List<JSONObject>> = Pair(o, emptyList())

    /** Answers each request with the next step; [hang] makes a step wait forever (the budget catches it). */
    private class Scripted(vararg steps: Pair<StreamOutcome, List<JSONObject>>?) : GeminiStreamTransport {
        val bodies = Collections.synchronizedList(mutableListOf<JSONObject>())
        private val queue = ArrayDeque(steps.toList())
        override suspend fun stream(model: String, body: JSONObject, onEvent: (JSONObject) -> Unit): StreamOutcome {
            bodies.add(JSONObject(body.toString()))
            val step = queue.removeFirst() ?: awaitCancellation()
            step.second.forEach(onEvent)
            return step.first
        }
    }

    private class Heard : GeminiTextLoop.Listener {
        val model = StringBuilder()
        val lines = mutableListOf<String>()
        val facts = mutableListOf<ScriptedLines.WriteFact>()
        override fun onText(delta: String) {
            model.append(delta)
        }
        override fun onWriteFact(fact: ScriptedLines.WriteFact) {
            facts.add(fact)
        }
        override fun onCodeLine(text: String, facts: List<ScriptedLines.WriteFact>) {
            lines.add(text)
        }
    }

    /** The resolved surface, as the session gives it: every call here already names a real tool. */
    private fun loop(t: GeminiStreamTransport, maxRounds: Int = 6, budgetMs: Long = 5_000) = GeminiTextLoop(
        transport = t,
        cache = null,
        classify = { e -> if (e.code == 429) AgentResult.RateLimited else AgentResult.Failed },
        isMutating = { name, _ -> name != "get_weather" },
        isReadThrough = { _, _ -> false },
        maxRounds = maxRounds,
        budgetMs = budgetMs,
        writeFact = ScriptedLines::factOf,
    )

    /** Each tool answers with its own invented result. */
    private val tools = TextToolExecutor { name, args, _ ->
        when (name) {
            "get_weather" -> JSONObject().put("success", true).put("summary", "Invented: mild.")
            "manage_item" -> JSONObject().put("success", true).put("item", args.optString("item")).put("list", "Groceries")
            "manage_vehicle" -> when (args.optString("vehicle")) {
                "same" -> JSONObject().put("success", true).put("changed", false).put("vehicle", "Invented Fit")
                "old" -> JSONObject().put("success", true).put("message", "You are on the Invented Fit now.")
                else -> JSONObject().put("success", true).put("changed", true).put("vehicle", "Invented Fit")
            }
            "ask_engine" -> JSONObject().put("success", true).put("message", "Logged on the engine.").put("changed", true)
            // A module scan the model tagged as a record: a router answer with nothing committed.
            "ask_fleet" -> JSONObject().put("success", true).put("say", "Scanning the modules.")
            "email" -> JSONObject().put("success", true).put("message", "Opened.")
            "fails" -> JSONObject().put("success", false).put("message", "Nothing was changed: invented.")
            "unconfirmed" -> JSONObject().put("message", "no success key")
            else -> JSONObject().put("success", true)
        }
    }

    private fun add(item: String) = call("manage_item", "action" to "add", "item" to item)
    private fun run(t: GeminiStreamTransport, l: Heard = Heard(), maxRounds: Int = 6, budgetMs: Long = 5_000) =
        runBlocking { loop(t, maxRounds, budgetMs).answer(prefix, contents, tools, l) }

    private val milk = "Done: added milk to Groceries."

    // --- the table --------------------------------------------------------------------------------

    @Test
    fun `1 one write, the model says nothing after it`() {
        val l = Heard()
        val r = run(Scripted(ok(calls(add("milk"))), ok()), l)
        assertEquals(milk, r.text)
        assertNull(r.failure)
        assertEquals(listOf(milk), l.lines)
        assertEquals("", l.model.toString())
    }

    @Test
    fun `2 one write, the model is told it was said and its own words follow the line`() {
        val t = Scripted(ok(calls(add("milk"))), ok(text("Anything else?")))
        val r = run(t)
        assertEquals("$milk Anything else?", r.text)
        val sent = t.bodies[1].getJSONArray("contents").let { it.getJSONObject(it.length() - 1) }.toString()
        assertTrue(sent, sent.contains("\"said_aloud\":\"$milk\"") && sent.contains("Do not say it again"))
    }

    @Test
    fun `3 two writes in one round are one line`() {
        val l = Heard()
        val r = run(Scripted(ok(calls(add("milk"), add("eggs"))), ok()), l)
        assertEquals("Done: added milk to Groceries; added eggs to Groceries.", r.text)
        assertEquals(1, l.lines.size)
    }

    @Test
    fun `4 two writes in two rounds are two lines, each after its round`() {
        val l = Heard()
        val r = run(Scripted(ok(calls(add("milk"))), ok(text("And eggs? "), calls(add("eggs"))), ok()), l)
        assertEquals("$milk And eggs? Done: added eggs to Groceries.", r.text)
        assertEquals(2, r.codeLines)
    }

    @Test
    fun `5 a write that went through and one that failed, only the first is said by code`() {
        val l = Heard()
        val r = run(Scripted(ok(calls(add("milk"), call("fails"))), ok(text("The other one did not go through."))), l)
        assertEquals("$milk The other one did not go through.", r.text)
        assertEquals(listOf(false, true), l.facts.map { it.ok }.sorted())
    }

    @Test
    fun `6 a write and a read in one round, the read is not confirmed`() {
        val t = Scripted(ok(calls(add("milk"), call("get_weather"))), ok(text("It is mild.")))
        val r = run(t)
        assertEquals("$milk It is mild.", r.text)
        val sent = t.bodies[1].toString()
        assertEquals(1, Regex("said_aloud\\\"").findAll(sent).count())
    }

    @Test
    fun `7 a later round's HTTP error after the line, the failure is said without undoing it`() {
        val r = run(Scripted(ok(calls(add("milk"))), broke(StreamOutcome.HttpError(500, "invented"))))
        assertEquals(milk, r.text)
        assertEquals(TextFailure.MODEL_ERROR, r.failure)
        assertEquals(TextFailure.MODEL_ERROR.lead, r.failureWords)
    }

    @Test
    fun `8 a later round offline after the line`() {
        val r = run(Scripted(ok(calls(add("milk"))), broke(StreamOutcome.Network("invented"))))
        assertEquals(milk, r.text)
        assertEquals(TextFailure.OFFLINE, r.failure)
        assertFalse(r.failureWords!!.contains("Nothing was changed"))
    }

    @Test
    fun `9 the turn's budget runs out after the line`() {
        val r = run(Scripted(ok(calls(add("milk"))), null), budgetMs = 1_500)
        assertEquals(milk, r.text)
        assertEquals(TextFailure.TIMED_OUT, r.failure)
        assertEquals(TextFailure.TIMED_OUT.lead, r.failureWords)
    }

    @Test
    fun `10 a later round blocked after the line`() {
        val blocked = JSONObject().put("promptFeedback", JSONObject().put("blockReason", "SAFETY"))
        val r = run(Scripted(ok(calls(add("milk"))), ok(blocked)))
        assertEquals(milk, r.text)
        assertEquals(TextFailure.BLOCKED, r.failure)
    }

    @Test
    fun `11 the round cap's forced round comes back empty, the line is the answer`() {
        val r = run(Scripted(ok(calls(add("milk"))), ok()), maxRounds = 1)
        assertEquals(milk, r.text)
        assertNull(r.failure)
    }

    @Test
    fun `12 a filler-only reply after the line is not asked again`() {
        val t = Scripted(ok(calls(add("milk"))), ok(text("One moment.")))
        val r = run(t)
        assertEquals(2, t.bodies.size)
        assertNull(r.failure)
        assertTrue(r.text, r.text.startsWith(milk))
    }

    @Test
    fun `13 a write with no confirmed result is not said as done`() {
        val l = Heard()
        val r = run(Scripted(ok(calls(call("unconfirmed"))), ok(text("I could not confirm that."))), l)
        assertEquals("I could not confirm that.", r.text)
        assertTrue(l.lines.isEmpty())
    }

    @Test
    fun `14 a write that failed is the model's to say, not code's`() {
        val l = Heard()
        val r = run(Scripted(ok(calls(call("fails"))), ok(text("That did not go through."))), l)
        assertEquals("That did not go through.", r.text)
        assertTrue(l.lines.isEmpty())
        assertEquals(listOf(false), l.facts.map { it.ok })
    }

    @Test
    fun `15 a read-only turn says nothing more`() {
        val l = Heard()
        val r = run(Scripted(ok(calls(call("get_weather"))), ok(text("It is mild."))), l)
        assertEquals("It is mild.", r.text)
        assertTrue(l.lines.isEmpty() && l.facts.isEmpty())
    }

    @Test
    fun `16 the car switch names the car the result names`() {
        val r = run(Scripted(ok(calls(call("manage_vehicle", "action" to "switch", "vehicle" to "five"))), ok()))
        assertEquals("Done: switched to Invented Fit.", r.text)
    }

    @Test
    fun `17 the car that was already selected changes nothing, so nothing is confirmed`() {
        val l = Heard()
        val r = run(Scripted(ok(calls(call("manage_vehicle", "action" to "switch", "vehicle" to "same"))), ok(text("It already is."))), l)
        assertEquals("It already is.", r.text)
        assertTrue(l.lines.isEmpty())
    }

    @Test
    fun `18 a switch result without its fields gets the generic line`() {
        val r = run(Scripted(ok(calls(call("manage_vehicle", "action" to "switch", "vehicle" to "old"))), ok()))
        assertEquals(ScriptedLines.GENERIC_DONE, r.text)
    }

    @Test
    fun `19 an engine bridge write has no title, so the generic line`() {
        val r = run(Scripted(ok(calls(call("ask_engine", "question" to "invented", "intent" to "record"))), ok()))
        assertEquals(ScriptedLines.GENERIC_DONE, r.text)
    }

    @Test
    fun `19b a router answer that committed nothing is never said as a change (review F3)`() {
        val l = Heard()
        val r = run(Scripted(ok(calls(call("ask_fleet", "question" to "scan the modules", "intent" to "record"))), ok(text("Scanning."))), l)
        assertEquals("Scanning.", r.text)
        assertTrue(l.lines.isEmpty())
    }

    @Test
    fun `20 many writes stay short and the rest is counted`() {
        val many = (1..6).map { add("invented thing $it") }.toTypedArray()
        val r = run(Scripted(ok(calls(*many)), ok()))
        assertTrue(r.text, r.text.length <= ScriptedLines.MAX_DONE_CHARS && r.text.contains("more"))
    }

    @Test
    fun `21 opening a draft on screen is not a change to confirm`() {
        val l = Heard()
        run(Scripted(ok(calls(call("email", "action" to "open", "draft_id" to "d1"))), ok(text("It is open."))), l)
        assertTrue(l.lines.isEmpty())
    }

    @Test
    fun `22 a failure after a said write and an unconfirmed one names only the unconfirmed one`() {
        val r = run(Scripted(ok(calls(add("milk"), call("unconfirmed"))), broke(StreamOutcome.Network("invented"))))
        assertEquals(milk, r.text)
        assertTrue(r.failureWords, r.failureWords!!.contains("Not confirmed"))
        assertFalse(r.failureWords, r.failureWords!!.contains("Completed before it stopped"))
    }

    @Test
    fun `23 the model's text before the write stays before the line`() {
        val l = Heard()
        val r = run(Scripted(ok(text("Sure. "), calls(add("milk"))), ok(text("Anything else?"))), l)
        assertEquals("Sure. $milk Anything else?", r.text)
        assertEquals("Sure. Anything else?", l.model.toString())
    }

    @Test
    fun `24 without writeFact (typed chat, older callers) nothing is said by code`() {
        val l = Heard()
        val bare = GeminiTextLoop(transport = Scripted(ok(calls(add("milk"))), ok(text("Added."))), cache = null, isMutating = { _, _ -> true }, isReadThrough = { _, _ -> false })
        val r = runBlocking { bare.answer(prefix, contents, tools, l) }
        assertEquals("Added.", r.text)
        assertTrue(l.lines.isEmpty())
    }
}

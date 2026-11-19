@file:Suppress("MaxLineLength") // invented JSON results read best on one line

package com.kevin.legion.service.cascade

import com.kevin.legion.ai.AgentResult
import com.kevin.legion.service.TextToolExecutor
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Ruling D-prime (Kevin, 2026-11-19): the closing round is always asked for; after a round of
 * nothing but writes that code confirmed, its text alone is not spoken, and with a tool call it is.
 * One row per round shape, decided from tool results only. Robolectric only for `org.json`.
 */
@RunWith(RobolectricTestRunner::class)
class ClosingRoundTest {

    private fun write(name: String, action: String, result: String, ok: Boolean = true, confirmed: Boolean = true): ClosingRound.Call {
        val r = JSONObject(result)
        return ClosingRound.Call(ScriptedLines.writeFact(name, action, r, ok, confirmed), r)
    }
    private fun read(result: String = """{"success":true}""") = ClosingRound.Call(null, JSONObject(result))

    private val milk = write("manage_item", "add", """{"success":true,"item":"milk","list":"Groceries"}""")
    private val eggs = write("manage_item", "add", """{"success":true,"item":"eggs","list":"Groceries"}""")

    private fun skips(vararg calls: ClosingRound.Call) = ClosingRound.dropsText(calls.toList())

    @Test
    fun `the round shapes`() {
        // A text-only closing round after these is not spoken.
        assertTrue("1 one write", skips(milk))
        assertTrue("2 two writes", skips(milk, eggs))
        assertTrue("3 a write with only the generic line", skips(write("invented_write", "", """{"success":true}""")))
        // Review round 2 D2: a router or specialist, even one that committed, is always asked again.
        assertFalse("4 a router that committed", skips(write("ask_body", "", """{"success":true,"changed":true,"message":"Logged. What weight for bench?"}""")))
        assertFalse("4b the engine bridge, part of it failed", skips(write("ask_engine", "", """{"success":true,"changed":true,"message":"Added. Part of it did not go through."}""")))
        assertFalse("4c a router next to a plain write", skips(milk, write("ask_engine", "", """{"success":true,"changed":true}""")))
        assertTrue("5 the adapter moved this drive", skips(write("manage_vehicle", "adapter", """{"success":true,"changed":true,"readings_moved":true,"vehicle":"Invented Ranger"}""")))
        assertTrue("6 the adapter on a car", skips(write("manage_vehicle", "adapter", """{"success":true,"changed":true,"vehicle":"Invented Ranger"}""")))
        // The closing round's text is spoken, as before.
        assertFalse("7 a write and a read", skips(milk, read()))
        assertFalse("8 a write that asks him something", skips(write("car", "switch_vehicle", """{"success":true,"needs_answer":true,"message":"Which car?"}""")))
        assertFalse("9 a refused write", skips(write("manage_item", "add", """{"success":false,"needs_yes":true,"needs_answer":true}""", ok = false)))
        assertFalse("10 a write that timed out", skips(write("manage_item", "add", """{"success":false,"message":"timed out"}""", ok = false, confirmed = false)))
        assertFalse("11 changed nothing", skips(write("manage_vehicle", "switch", """{"success":true,"changed":false,"vehicle":"Invented Fit"}""")))
        assertFalse("12 a router that committed nothing", skips(write("ask_fleet", "", """{"success":true,"say":"Scanning."}""")))
        assertFalse("13 a mail draft waits for its send", skips(write("email", "draft", """{"success":true,"draft_id":"d1","to":"x@example.com"}""")))
        assertFalse("14 the first step of a two-step write (no facts: not a write)", skips(read("""{"success":true,"message":"Read this warning back."}""")))
        assertFalse("15 a hand-over or sign-off call", skips(milk, read("""{"success":true,"instruction":"say goodbye"}""")))
        assertFalse("16 a confirmed write and a failed one", skips(milk, write("manage_item", "add", """{"success":false}""", ok = false)))
        assertFalse("17 no calls", skips())
    }

    // --- in the loop ------------------------------------------------------------------------------

    private class Scripted(vararg steps: List<JSONObject>) : GeminiStreamTransport {
        val posts = mutableListOf<JSONObject>()
        private val queue = ArrayDeque(steps.toList())
        override suspend fun stream(model: String, body: JSONObject, onEvent: (JSONObject) -> Unit): StreamOutcome {
            posts.add(body)
            queue.removeFirst().forEach(onEvent)
            return StreamOutcome.Ok
        }
    }

    private fun parts(vararg p: JSONObject) = JSONObject().put(
        "candidates",
        JSONArray().put(JSONObject().put("content", JSONObject().put("role", "model").put("parts", JSONArray(p.toList())))),
    )
    private fun call(name: String, vararg kv: Pair<String, String>) =
        JSONObject().put("functionCall", JSONObject().put("name", name).put("args", JSONObject().apply { kv.forEach { (k, v) -> put(k, v) } }))

    private fun loopRun(t: Scripted, routerChanged: Boolean = false) = runBlocking {
        GeminiTextLoop(
            transport = t, cache = null, classify = { AgentResult.Failed },
            isMutating = { name, _ -> name == "manage_item" || name == "ask_body" }, isReadThrough = { _, _ -> false },
            writeFact = ScriptedLines::factOf,
        ).answer(
            CascadePrefix("model-x", "Invented instruction.", JSONArray()),
            JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", "invented")))),
            TextToolExecutor { name, args, _ ->
                when (name) {
                    "manage_item" -> JSONObject().put("success", true).put("item", args.optString("item")).put("list", "Groceries")
                    "ask_body" -> JSONObject().put("success", true).put("changed", routerChanged).put("message", "Logged.")
                    else -> JSONObject().put("success", true)
                }
            },
            object : GeminiTextLoop.Listener {},
        )
    }

    private fun text(t: String) = parts(JSONObject().put("text", t))
    private fun add(item: String) = parts(call("manage_item", "action" to "add", "item" to item))

    @Test
    fun `the closing round is asked for, and its text alone after confirmed writes is not spoken`() {
        val t = Scripted(listOf(add("milk")), listOf(text("Added milk.")))
        val r = loopRun(t)
        assertEquals(2, t.posts.size)
        assertTrue(r.closingDropped)
        assertEquals("Done: added milk to Groceries.", r.text)
    }

    @Test
    fun `a closing round that calls a tool runs it, says its line, and keeps its text`() {
        val t = Scripted(listOf(add("tent")), listOf(text("Now the stakes. "), add("stakes")), listOf(text("Both are on.")))
        val r = loopRun(t)
        assertEquals(3, t.posts.size)
        assertEquals("Done: added tent to Groceries. Now the stakes. Done: added stakes to Groceries.", r.text)
        assertTrue("the last closing round was text alone after a write", r.closingDropped)
    }

    @Test
    fun `text after a router write or after a write and a read round is spoken`() {
        val routed = Scripted(listOf(parts(call("ask_body", "question" to "log squats", "intent" to "record"))), listOf(text("Logged. What weight for bench?")))
        val r1 = loopRun(routed, routerChanged = true)
        assertFalse(r1.closingDropped)
        assertTrue(r1.text, r1.text.endsWith("What weight for bench?"))
        val mixed = Scripted(listOf(parts(call("manage_item", "action" to "add", "item" to "milk"), call("get_weather"))), listOf(text("It is mild.")))
        val r2 = loopRun(mixed)
        assertFalse(r2.closingDropped)
        assertEquals("Done: added milk to Groceries. It is mild.", r2.text)
    }

    @Test
    fun `a write after a read round, then text alone, ends on the write's line`() {
        val t = Scripted(listOf(parts(call("get_weather"))), listOf(add("umbrella")), listOf(text("Added it.")))
        val r = loopRun(t)
        assertEquals(3, t.posts.size)
        assertTrue(r.closingDropped)
        assertEquals("Done: added umbrella to Groceries.", r.text)
    }
}

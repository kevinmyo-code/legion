@file:Suppress("MaxLineLength") // invented JSON and wire frames read best on one line

package com.kevin.legion.service.cascade

import com.kevin.legion.ai.AgentResult
import com.kevin.legion.service.TextToolExecutor
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Ruling A (Kevin, 2026-11-19): in a turn where code said a write's confirmation, a model sentence
 * that names the thing just changed is not spoken. Membership in the strings the code line used,
 * never intent. Each test is one turn shape through the real loop. Invented content only;
 * Robolectric only for `org.json`.
 */
@RunWith(RobolectricTestRunner::class)
class SaidFilterTest {

    private val prefix = CascadePrefix("model-x", "Invented instruction.", JSONArray())
    private val contents = JSONArray().put(
        JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", "invented"))),
    )

    private fun parts(vararg p: JSONObject) = JSONObject().put(
        "candidates",
        JSONArray().put(JSONObject().put("content", JSONObject().put("role", "model").put("parts", JSONArray(p.toList())))),
    )
    private fun text(t: String) = parts(JSONObject().put("text", t))
    private fun call(name: String, vararg kv: Pair<String, String>) =
        JSONObject().put("functionCall", JSONObject().put("name", name).put("args", JSONObject().apply { kv.forEach { (k, v) -> put(k, v) } }))
    /** A read in each write round, so the model is asked again and the filter has words to judge (ruling D). */
    private fun weather() = call("get_weather")
    private fun add(item: String, list: String = "Groceries") = call("manage_item", "action" to "add", "item" to item, "list" to list)

    private class Scripted(vararg steps: List<JSONObject>) : GeminiStreamTransport {
        private val queue = ArrayDeque(steps.toList())
        override suspend fun stream(model: String, body: JSONObject, onEvent: (JSONObject) -> Unit): StreamOutcome {
            queue.removeFirst().forEach(onEvent)
            return StreamOutcome.Ok
        }
    }

    private val tools = TextToolExecutor { name, args, _ ->
        when (name) {
            "manage_item" -> JSONObject().put("success", true).put("item", args.optString("item")).put("list", args.optString("list"))
            "ask_engine" -> JSONObject().put("success", true).put("changed", true).put("message", "Logged.")
            else -> JSONObject().put("success", true)
        }
    }

    private class Heard : GeminiTextLoop.Listener {
        val model = StringBuilder()
        override fun onText(delta: String) {
            model.append(delta)
        }
    }

    private fun run(vararg steps: List<JSONObject>): Pair<GeminiTextLoop.Result, String> = runBlocking {
        val heard = Heard()
        val loop = GeminiTextLoop(
            transport = Scripted(*steps), cache = null, classify = { AgentResult.Failed },
            isMutating = { name, _ -> name != "get_weather" }, isReadThrough = { _, _ -> false },
            writeFact = ScriptedLines::factOf,
        )
        loop.answer(prefix, contents, tools, heard) to heard.model.toString().trim()
    }

    private val milk = "Done: added oat milk to Groceries."

    @Test
    fun `1 the model repeating the confirmation is not spoken`() {
        val (r, model) = run(listOf(parts(weather(), add("oat milk"))), listOf(text("Added it to your groceries.")))
        assertEquals(milk, r.text)
        assertEquals("", model)
        assertEquals(1, r.saidDropped)
    }

    @Test
    fun `2 the same thing in other words is not spoken either`() {
        val (r, _) = run(listOf(parts(weather(), add("oat milk"))), listOf(text("Oat milk is on there now.")))
        assertEquals(milk, r.text)
    }

    @Test
    fun `3 a second sentence about something else is kept`() {
        val (r, model) = run(listOf(parts(weather(), add("oat milk"))), listOf(text("Got the oat milk. It will rain later.")))
        assertEquals("$milk It will rain later.", r.text)
        assertEquals("It will rain later.", model)
        assertEquals(1, r.saidDropped)
    }

    @Test
    fun `4 a follow-up question is kept`() {
        val (r, _) = run(listOf(parts(weather(), add("oat milk"))), listOf(text("Anything else?")))
        assertEquals("$milk Anything else?", r.text)
        assertEquals(0, r.saidDropped)
    }

    @Test
    fun `5 the item named in a later turn with no write is spoken`() {
        val (r, _) = run(listOf(text("Milk is on your list.")))
        assertEquals("Milk is on your list.", r.text)
    }

    @Test
    fun `6 two writes in one turn, either name drops a sentence`() {
        val (r, _) = run(listOf(parts(weather(), add("oat milk"), add("eggs"))), listOf(text("Eggs and oat milk are added. Shall I order bread?")))
        assertEquals("Done: added oat milk to Groceries; added eggs to Groceries. Shall I order bread?", r.text)
    }

    @Test
    fun `7 a write with only the generic line names nothing, so nothing is dropped`() {
        val (r, _) = run(listOf(parts(weather(), call("ask_engine", "question" to "log it", "intent" to "record"))), listOf(text("Logged the dentist on the engine.")))
        assertEquals("${ScriptedLines.GENERIC_DONE} Logged the dentist on the engine.", r.text)
    }

    @Test
    fun `8 a short or numeric title never drops a sentence`() {
        val (tv, _) = run(listOf(parts(weather(), add("TV", "Todo"))), listOf(text("TV is on the to-do now.")))
        // "Todo" is in the line and the sentence says "to-do", not "Todo": kept.
        assertEquals("Done: added TV to Todo. TV is on the to-do now.", tv.text)
        val (n, _) = run(listOf(parts(weather(), add("42", "Todo"))), listOf(text("Item 42 is in.")))
        assertEquals("Done: added 42 to Todo. Item 42 is in.", n.text)
    }

    @Test
    fun `9 a whole word only, in a script with spaces`() {
        val (r, _) = run(listOf(parts(weather(), add("printer ink", "Todo"))), listOf(text("Thinking of you. The printer ink is in.")))
        assertEquals("Done: added printer ink to Todo. Thinking of you.", r.text)
    }

    @Test
    fun `10 another language, and a script without spaces`() {
        val (es, _) = run(listOf(parts(weather(), add("leche", "Compras"))), listOf(text("Añadí leche a la lista. La lechería abre a las nueve.")))
        assertEquals("Done: added leche to Compras. La lechería abre a las nueve.", es.text)
        val (zh, _) = run(listOf(parts(weather(), add("牛奶", "Compras"))), listOf(text("已经把牛奶加到清单了。 还需要别的吗？")))
        assertEquals("Done: added 牛奶 to Compras. 还需要别的吗？", zh.text)
    }

    @Test
    fun `11 when every model sentence names it, the turn ends on the code line`() {
        val (r, _) = run(listOf(parts(weather(), add("oat milk"))), listOf(text("Oat milk added. It is on Groceries.")))
        assertEquals(milk, r.text)
        assertNull(r.failure)
        assertEquals(2, r.saidDropped)
    }

    @Test
    fun `12 what the model said before the write stays (it was already spoken)`() {
        val (r, _) = run(listOf(text("Adding oat milk now. "), parts(weather(), add("oat milk"))), listOf(text("Oat milk is in.")))
        assertEquals("Adding oat milk now. $milk", r.text)
    }

    @Test
    fun `13 known limit, the list's name drops an unrelated sentence in that turn`() {
        val (r, _) = run(listOf(parts(weather(), add("oat milk"))), listOf(text("Your Groceries list has five things.")))
        assertEquals(milk, r.text)
    }

    @Test
    fun `14 the names are exactly what the line said`() {
        val facts = listOf(ScriptedLines.writeFact("manage_item", "add", JSONObject().put("item", "oat milk").put("list", "Groceries"), ok = true))
        assertEquals(listOf("oat milk", "Groceries"), ScriptedLines.namesIn(facts, ScriptedLines.doneLine(facts)!!))
        assertEquals(emptyList<String>(), ScriptedLines.namesIn(facts, ScriptedLines.GENERIC_DONE))
    }

    // --- review round 2, A1: a name must be specific enough, and only the next round is judged ---

    @Test
    fun `15 a short or everyday item or list name never drops an unrelated sentence`() {
        for (item in listOf("the", "tea", "gym", "car")) {
            val (r, _) = run(listOf(parts(weather(), add(item, "Todo"))), listOf(text("Rain later, so take the $item in.")))
            assertEquals("Done: added $item to Todo. Rain later, so take the $item in.", r.text)
        }
        val (home, _) = run(listOf(parts(weather(), add("umbrella", "Home"))), listOf(text("Want a reminder when you get home?")))
        assertEquals("Done: added umbrella to Home. Want a reminder when you get home?", home.text)
    }

    @Test
    fun `16 a long single word or two words still count`() {
        val (one, _) = run(listOf(parts(weather(), add("umbrella", "Todo"))), listOf(text("The umbrella is on there.")))
        assertEquals("Done: added umbrella to Todo.", one.text)
        val (two, _) = run(listOf(parts(weather(), add("oat milk", "Todo"))), listOf(text("Oat milk is in.")))
        assertEquals("Done: added oat milk to Todo.", two.text)
    }

    @Test
    fun `17 only the round right after the code line is judged`() {
        val (r, _) = run(
            listOf(parts(weather(), add("umbrella", "Todo"))),
            listOf(text("One more check. "), parts(weather())),
            listOf(text("Rain at five, and the umbrella is packed.")),
        )
        assertEquals("Done: added umbrella to Todo. One more check. Rain at five, and the umbrella is packed.", r.text)
    }

    @Test
    fun `18 the floor is a fixed table of everyday names, by membership`() {
        val facts = { item: String, list: String -> listOf(ScriptedLines.writeFact("manage_item", "add", JSONObject().put("item", item).put("list", list), ok = true)) }
        for (stop in listOf("home", "work", "shop", "store", "today", "later", "things", "stuff", "list", "notes")) {
            val f = facts("x", stop.replaceFirstChar { it.uppercase() })
            assertEquals(stop, emptyList<String>(), ScriptedLines.namesIn(f, ScriptedLines.doneLine(f)!!))
        }
        val f = facts("milk", "Groceries")
        assertEquals(listOf("Groceries"), ScriptedLines.namesIn(f, ScriptedLines.doneLine(f)!!))
    }
}

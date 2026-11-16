@file:Suppress("MaxLineLength", "NestedBlockDepth") // invented wire frames on one line; cases walk nested JSON

package com.kevin.legion.voiceeval

import com.kevin.legion.service.LiveToolbox
import com.kevin.legion.service.cascade.CascadePrefix
import com.kevin.legion.service.cascade.CascadeRules
import com.kevin.legion.service.cascade.GeminiStreamTransport
import com.kevin.legion.service.cascade.StreamOutcome
import com.kevin.legion.testutil.RoomTestReset
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.LocalDate

/**
 * The voice eval's offline half (no key, no network): the committed cases name calls that exist on
 * the voice surface, and the harness runs the REAL tools against the made-up fixture, writing the
 * phone's own line formats. The model is scripted here; [VoiceEvalRun] is the real-Gemini half.
 */
@RunWith(RobolectricTestRunner::class)
class VoiceEvalOfflineTest {
    private val context = RuntimeEnvironment.getApplication()
    private val today = LocalDate.now()

    @Before
    fun reset() {
        RoomTestReset.resetCarDatabaseSingleton()
    }

    @After
    fun drain() {
        RoomTestReset.drainArchDiskIoPool()
    }

    // --- the committed cases ----------------------------------------------------------------------

    @Test
    fun `every expected call in the committed cases is on the voice surface`() {
        val surface = surfaceActions()
        val json = JSONObject(VoiceEvalCases.resolve("tools/voice_eval/cases.json").readText())
        val cases = json.getJSONArray("cases")
        val ids = mutableSetOf<String>()
        var checked = 0
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            assertTrue("duplicate id ${c.getString("id")}", ids.add(c.getString("id")))
            val expects = c.optJSONArray("turns")?.let { t -> (0 until t.length()).map { t.getJSONObject(it).optJSONObject("expect") } }
                ?: listOf(c.optJSONObject("expect"))
            for (e in expects.filterNotNull()) {
                val calls = e.optJSONArray("calls") ?: continue
                for (j in 0 until calls.length()) {
                    for (alt in calls.getString(j).split('|')) {
                        val tool = alt.substringBefore('/')
                        val action = alt.substringAfter('/', "")
                        val names = surface[tool]
                        // A bare name may be a resolved tool (e.g. list_vehicles behind a router): skip.
                        if (names == null && action.isEmpty()) continue
                        assertTrue("${c.getString("id")}: $tool is not on the voice surface", names != null)
                        if (action.isNotEmpty()) {
                            assertTrue("${c.getString("id")}: $tool has no action $action", action in names!!)
                        }
                        checked++
                    }
                }
            }
        }
        assertTrue("no calls checked", checked > 10)
        assertEquals(ids.size, VoiceEvalCases.parse(json.toString()).size)
    }

    /** Tool name to its discriminator values (`action` or `domain`), as the model is offered them. */
    private fun surfaceActions(): Map<String, Set<String>> {
        val decls = CascadeRules.declarations(LiveToolbox.declarations())
        return (0 until decls.length()).associate { i ->
            val d = decls.getJSONObject(i)
            val props = d.optJSONObject("parameters")?.optJSONObject("properties") ?: JSONObject()
            val values = listOf("action", "domain").flatMap { key ->
                val e = props.optJSONObject(key)?.optJSONArray("enum") ?: JSONArray()
                (0 until e.length()).map { e.getString(it) }
            }
            d.getString("name") to values.toSet()
        }
    }

    // --- the harness on the fixture ---------------------------------------------------------------

    @Test
    fun `the harness runs the real calendar tool on the fixture and logs the phone's lines`() = runBlocking {
        VoiceEvalFixture.seed(context, today)
        val day = today.toString()
        val lines = runScripted(
            "due_today",
            "what's due today",
            call("day", JSONObject().put("action", "read_calendar").put("from", day).put("to", day)),
            text("Two things are due tonight."),
        )
        assertTrue(lines.first().startsWith("LIVE_TEST case_begin id=due_today"))
        assertTrue(lines.last().startsWith("LIVE_TEST case_end id=due_today"))
        assertTrue(lines.any { it.startsWith("LIVE_AUDIT session_start model=eval+model-x") })
        assertTrue(lines.any { it == "LIVE_AUDIT user final=true text=\"what's due today\"" })
        val result = lines.single { it.startsWith("LIVE_AUDIT tool_result") }
        assertTrue(result, result.contains("name=day success=true"))
        assertTrue(result, result.contains(VoiceEvalFixture.Facts.TASK_TODAY_A))
        assertTrue(result, result.contains("\"done\":false"))
        assertTrue(lines.any { it.startsWith("LIVE_LATENCY gemini_usage kind=llm model=model-x usage=present prompt=900") })
        assertTrue(lines.any { it.startsWith("LIVE_LATENCY llm_usage model=model-x") })
        assertTrue(lines.any { it == "LIVE_AUDIT assistant text=\"Two things are due tonight.\"" })
    }

    @Test
    fun `reminders, to-dos and an all-day row come back from the real tools`() = runBlocking {
        VoiceEvalFixture.seed(context, today)
        val reminders = toolResult("manage_item", JSONObject().put("action", "read_list"))
        assertTrue(reminders, reminders.contains(VoiceEvalFixture.Facts.REMINDER_SOON))
        val todo = toolResult("manage_checklist", JSONObject().put("action", "read").put("list", "Todo"))
        assertTrue(todo, todo.contains(VoiceEvalFixture.Facts.TODO_A))
        val tomorrow = today.plusDays(1).toString()
        val cal = toolResult("day", JSONObject().put("action", "read_calendar").put("from", tomorrow).put("to", tomorrow))
        assertTrue(cal, cal.contains(VoiceEvalFixture.Facts.ALL_DAY_TOMORROW))
        assertTrue(cal, cal.contains(VoiceEvalFixture.Facts.EVENT_TOMORROW))
    }

    // --- 2026-11-15: every door to a list ends at the right row, and says which -------------------

    @Test
    fun `a to-do list read carries both lists, whichever tool read it`() = runBlocking {
        VoiceEvalFixture.seed(context, today)
        val reminders = toolResult("manage_item", JSONObject().put("action", "read_list"))
        assertTrue(reminders, reminders.contains("todo_list") && reminders.contains(VoiceEvalFixture.Facts.TODO_A))
        val todo = toolResult("manage_checklist", JSONObject().put("action", "read").put("list", "to-do list"))
        assertTrue(todo, todo.contains(VoiceEvalFixture.Facts.TODO_A))
        assertTrue(todo, todo.contains("\"reminders\"") && todo.contains(VoiceEvalFixture.Facts.REMINDER_TODAY))
    }

    @Test
    fun `an undated add lands on the Todo checklist, whichever tool added it`() = runBlocking {
        VoiceEvalFixture.seed(context, today)
        val viaItem = toolResult("manage_item", JSONObject().put("action", "add").put("item", "buy stamps"))
        assertTrue(viaItem, viaItem.contains("success=true") && viaItem.contains("Todo checklist"))
        val viaList = toolResult("manage_checklist", JSONObject().put("action", "add").put("list", "to-do").put("text", "mail the form"))
        assertTrue(viaList, viaList.contains("success=true") && viaList.contains("Todo"))
        val read = toolResult("manage_checklist", JSONObject().put("action", "read").put("list", "Todo"))
        assertTrue(read, read.contains("buy stamps") && read.contains("mail the form"))
        val dated = toolResult(
            "manage_item",
            JSONObject().put("action", "add").put("item", "call the dentist").put("date", today.plusDays(1).toString()),
        )
        assertTrue(dated, dated.contains("success=true") && !dated.contains("Todo checklist"))
    }

    @Test
    fun `a tick by any door completes the calendar task and says it was one`() = runBlocking {
        VoiceEvalFixture.seed(context, today)
        val viaItem = toolResult("manage_item", JSONObject().put("action", "tick").put("item", "history reading quiz"))
        assertTrue(viaItem, viaItem.contains("success=true") && viaItem.contains(VoiceEvalFixture.Facts.TASK_TODAY_A))
        assertTrue(viaItem, viaItem.contains("calendar task, not a reminder"))
        val viaList = toolResult(
            "manage_checklist", JSONObject().put("action", "tick").put("list", "Todo").put("item", "math homework"),
        )
        assertTrue(viaList, viaList.contains("success=true") && viaList.contains(VoiceEvalFixture.Facts.TASK_OVERDUE))
        val todoLine = toolResult("day", JSONObject().put("action", "complete_task").put("title", "printer ink"))
        assertTrue(todoLine, todoLine.contains("success=true") && todoLine.contains("Todo checklist"))
        val reminder = toolResult("day", JSONObject().put("action", "complete_task").put("title", "call the landlord"))
        assertTrue(reminder, reminder.contains("success=true") && reminder.contains("a reminder, not a calendar task"))
    }

    @Test
    fun `a switch with no car named changes nothing and says where a read goes`() = runBlocking {
        VoiceEvalFixture.seed(context, today)
        val r = toolResult("car", JSONObject().put("action", "switch_vehicle"))
        assertTrue(r, r.contains("success=false") && r.contains("Nothing was switched") && r.contains("ask_fleet"))
    }

    private suspend fun toolResult(name: String, args: JSONObject): String =
        runScripted("t", "invented", call(name, args), text("ok.")).single { it.startsWith("LIVE_AUDIT tool_result") }

    private suspend fun runScripted(id: String, say: String, vararg rounds: List<JSONObject>): List<String> {
        val lines = mutableListOf<String>()
        val queue = ArrayDeque(rounds.toList())
        val transport = object : GeminiStreamTransport {
            override suspend fun stream(model: String, body: JSONObject, onEvent: (JSONObject) -> Unit): StreamOutcome {
                queue.removeFirst().forEach(onEvent)
                return StreamOutcome.Ok
            }
        }
        val prefix = CascadePrefix("model-x", "Invented instruction.", CascadeRules.declarations(LiveToolbox.declarations()))
        VoiceEvalHarness(context, transport, null, prefix, "", { tag, msg -> lines.add("$tag $msg") })
            .runCase(EvalCase(id, listOf(say), write = false))
        return lines
    }

    private fun usage() = JSONObject().put("usageMetadata", JSONObject().put("promptTokenCount", 900).put("candidatesTokenCount", 12))

    private fun parts(vararg p: JSONObject) = JSONObject().put(
        "candidates",
        JSONArray().put(JSONObject().put("content", JSONObject().put("role", "model").put("parts", JSONArray(p.toList())))),
    )

    private fun call(name: String, args: JSONObject) =
        listOf(parts(JSONObject().put("functionCall", JSONObject().put("name", name).put("args", args))), usage())

    private fun text(t: String) = listOf(parts(JSONObject().put("text", t)), usage())
}

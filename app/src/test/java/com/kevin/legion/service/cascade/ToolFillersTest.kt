@file:Suppress("MaxLineLength") // test fixtures and assertions read best on one line

package com.kevin.legion.service.cascade

import com.kevin.legion.service.LiveToolbox
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** [ToolFillers]: the holding line says which tool started, never an outcome. Robolectric for org.json and the surface. */
@RunWith(RobolectricTestRunner::class)
class ToolFillersTest {
    private fun call(name: String, vararg kv: Pair<String, String>, write: Boolean = false) =
        ToolFillers.Call(name, JSONObject().apply { kv.forEach { (k, v) -> put(k, v) } }, write)

    @Test
    fun `every tool the model can call has a line or is deliberately silent`() {
        val decls = CascadeRules.declarations(LiveToolbox.declarations())
        val names = (0 until decls.length()).map { decls.getJSONObject(it).getString("name") }
        assertTrue(names.size > 10)
        for (n in names) {
            val key = ToolFillers.keyOf(n, JSONObject())
            if (n in ToolFillers.SILENT) {
                assertNull(n, key)
            } else {
                val plan = ToolFillers.plan(listOf(ToolFillers.Call(n, JSONObject(), false)))!!
                assertTrue(n, plan.lines.isNotEmpty())
            }
        }
        // The specialists and the reads with a network hop have their own words, not the generic ones.
        for (n in listOf("ask", "look_up", "day", "ask_engine")) {
            if (n in names) assertTrue(n, n in ToolFillers.LINES)
        }
        println("TOOL_FILLER_NAMES ${names.sorted().joinToString(",")}")
    }

    @Test
    fun `the line names the domain or action that started`() {
        assertEquals("ask:ask_fleet", ToolFillers.plan(listOf(call("ask", "domain" to "ask_fleet")))!!.key)
        assertEquals("look_up:ask_mail", ToolFillers.plan(listOf(call("look_up", "domain" to "ask_mail")))!!.key)
        assertEquals("day:read_calendar", ToolFillers.plan(listOf(call("day", "action" to "read_calendar")))!!.key)
        assertEquals("ask", ToolFillers.plan(listOf(call("ask", "domain" to "ask_new_thing")))!!.key)
        assertEquals(ToolFillers.GENERIC, ToolFillers.plan(listOf(call("control_music", "action" to "play")))!!.lines)
        assertNull(ToolFillers.plan(listOf(call("end_conversation"))))
    }

    @Test
    fun `a slow read is said at once and wins over a local call in the same round, a write never is`() {
        val p = ToolFillers.plan(listOf(call("phone", "action" to "volume"), call("ask", "domain" to "ask_fleet")))!!
        assertEquals("ask:ask_fleet", p.key)
        assertTrue(p.slow)
        assertFalse(ToolFillers.plan(listOf(call("car", "action" to "set_odometer", write = true)))!!.slow)
        assertFalse(ToolFillers.plan(listOf(call("day", "action" to "complete_task", write = true)))!!.slow)
        assertFalse(ToolFillers.plan(listOf(call("records")))!!.slow)
        assertEquals(700L, ToolFillers.FAST_DELAY_MS)
    }

    @Test
    fun `never the same line twice running`() {
        val lines = ToolFillers.LINES.getValue("ask:ask_fleet")
        var last: String? = null
        repeat(10) { i ->
            val next = ToolFillers.pick(lines, last, i)
            assertNotEquals(last, next)
            last = next
        }
        assertEquals("only", ToolFillers.pick(listOf("only"), "only", 3))
    }

    @Test
    fun `no line claims an outcome, each is short and kept in memory`() {
        val outcome = Regex("(?i)\b(done|set|sent|saved|added|cleared|booked|found|fine|ok|ready)\b")
        for (line in ToolFillers.ALL) {
            assertFalse(line, outcome.containsMatchIn(line))
            assertTrue(line, line.split(' ').size <= 6 && line.length <= 36)
            assertTrue(line, line in ScriptedLines.KEEP)
        }
    }
}

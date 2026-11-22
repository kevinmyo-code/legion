@file:Suppress("MaxLineLength") // invented calls read best on one line

package com.kevin.legion.service.testturn

import com.kevin.legion.service.LiveToolbox
import com.kevin.legion.service.tools.LiveSurface
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** [TestTurnPolicy]: deny by default, every reachable tool classified, reads pass, writes refused in words. */
@RunWith(RobolectricTestRunner::class) // org.json
class TestTurnPolicyTest {

    @Test
    fun `every reachable tool is classified`() {
        val known = TestTurnPolicy.READS + TestTurnPolicy.READ_ACTIONS.keys + TestTurnPolicy.REFUSED + TestTurnPolicy.WHY
        val unclassified = LiveToolbox.reachableNamesForTest() - known
        assertTrue("classify in TestTurnPolicy: ${unclassified.sorted()}", unclassified.isEmpty())
    }

    @Test
    fun `no tool is both a read and refused`() {
        val both = (TestTurnPolicy.READS + TestTurnPolicy.READ_ACTIONS.keys) intersect TestTurnPolicy.REFUSED
        assertTrue(both.sorted().toString(), both.isEmpty())
    }

    @Test
    fun `no listed read is a known write`() {
        for (name in TestTurnPolicy.READS) {
            assertTrue("$name is mutating", !LiveToolbox.isMutatingTool(name))
        }
    }

    @Test
    fun `reads pass through, as the model sends them`() {
        assertNull(refuse("day", "action" to "read_calendar", "from" to "2026-11-15", "to" to "2026-11-15"))
        assertNull(refuse("manage_item", "action" to "read_list"))
        assertNull(refuse("manage_checklist", "action" to "read", "list" to "Todo"))
        assertNull(refuse("ask", "domain" to "ask_fleet", "question" to "which cars", "intent" to "ask"))
        assertNull(refuse("look_up", "domain" to "ask_money", "question" to "balance"))
        assertNull(refuse("end_conversation"))
    }

    @Test
    fun `writes, calls and controls are refused in words`() {
        val cases = listOf(
            refuse("manage_item", "action" to "add", "item" to "invented", "date" to "2026-11-16"),
            refuse("manage_checklist", "action" to "add", "list" to "Todo", "text" to "invented"),
            refuse("day", "action" to "complete_task", "title" to "invented"),
            refuse("ask", "domain" to "ask_fleet", "question" to "log it", "intent" to "record"),
            refuse("memory", "action" to "remember", "text" to "invented"),
            refuse("phone", "action" to "place_call", "target" to "invented"),
            refuse("show_agenda_modal"),
            refuse("some_tool_added_tomorrow"),
        )
        for (r in cases) {
            assertNotNull(r)
            assertEquals(false, r!!.getBoolean("success"))
            assertEquals(true, r.getBoolean("test_mode"))
            assertTrue(r.getString("message").startsWith("Test mode: not done."))
        }
    }

    @Test
    fun `a case that allows writes runs them`() {
        assertNull(TestTurnPolicy.refusal("manage_item", JSONObject().put("action", "add"), TestWrites.ALL))
    }

    @Test
    fun `a named allow list runs only those tools, refusing the rest before they run`() {
        val only = TestWrites.from(writes = true, allowTools = " manage_checklist , manage_item ")
        assertEquals(setOf("manage_checklist", "manage_item"), only.only)
        assertNull(TestTurnPolicy.refusal("manage_checklist", JSONObject().put("action", "add"), only))
        // Resolved names: an action tool reaching a listed tool is allowed, any other is refused.
        assertNull(TestTurnPolicy.refusal("day", JSONObject().put("action", "read_calendar"), only))
        for ((name, args) in listOf(
            "email" to JSONObject().put("to", "invented"),
            "phone" to JSONObject().put("action", "place_call").put("target", "invented"),
            "set_budget" to JSONObject().put("category", "invented"),
            "memory" to JSONObject().put("action", "remember").put("text", "invented"),
        )) {
            val r = TestTurnPolicy.refusal(name, args, only)
            assertNotNull(name, r)
            assertEquals(true, r!!.getBoolean("test_mode"))
        }
    }

    @Test
    fun `the door's extras pick the write rule`() {
        assertEquals(TestWrites.NONE, TestWrites.from(writes = false, allowTools = null))
        assertEquals(TestWrites.ALL, TestWrites.from(writes = true, allowTools = " "))
        assertEquals(TestWrites(only = setOf("timer_start")), TestWrites.from(writes = false, allowTools = "timer_start"))
        assertEquals("none", TestWrites.NONE.toString())
        assertEquals("a,b", TestWrites(only = setOf("b", "a")).toString())
    }

    @Test
    fun `why did you say that is a read unless it stops a line`() {
        assertNull(refuse("day", "action" to "why_did_you_say_that"))
        assertNotNull(refuse("day", "action" to "why_did_you_say_that", "stop" to true))
    }

    @Test
    fun `memory recall reads, forget does not`() {
        assertNull(refuse("memory", "action" to "recall", "query" to "invented"))
        assertNotNull(refuse("memory", "action" to "forget", "query" to "invented"))
        // The resolve step the gate relies on is the controller's own.
        assertEquals("read_calendar", LiveSurface.resolve("day", JSONObject().put("action", "read_calendar")).name)
    }

    private fun refuse(name: String, vararg args: Pair<String, Any>): JSONObject? {
        val o = JSONObject()
        for ((k, v) in args) o.put(k, v)
        return TestTurnPolicy.refusal(name, o, TestWrites.NONE)
    }
}

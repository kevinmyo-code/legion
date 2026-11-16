package com.kevin.legion.service.cascade

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [CascadeRules.isWrite]: the cascade judges a call by the tool it stands for (voice quick wins,
 * 2026-11-16). Before, an action tool's write (`day` complete_task) never counted as one, so an
 * interrupted turn's "already done" note could miss it. Robolectric only for the toolbox's statics.
 */
@RunWith(RobolectricTestRunner::class)
class CascadeWritesTest {

    private fun call(vararg kv: Pair<String, String>) = JSONObject().apply { kv.forEach { (k, v) -> put(k, v) } }

    @Test
    fun `an action tool's write counts as a write, its reads do not`() {
        assertTrue(CascadeRules.isWrite("day", call("action" to "complete_task", "title" to "invented")))
        assertFalse(CascadeRules.isWrite("day", call("action" to "read_calendar", "from" to "today")))
        assertFalse(CascadeRules.isWrite("day", call("action" to "get_sitrep")))
    }

    @Test
    fun `a base tool keeps its own verdict, and its added read action is a read`() {
        assertTrue(CascadeRules.isWrite("manage_item", call("action" to "add", "text" to "invented")))
        assertFalse(CascadeRules.isWrite("manage_item", call("action" to "read_list")))
    }

    @Test
    fun `a router call is a write only when it asks to record`() {
        val ask = call("domain" to "ask_fleet", "question" to "invented", "intent" to "ask")
        assertFalse(CascadeRules.isWrite("ask", ask))
        val record = call("domain" to "ask_fleet", "question" to "invented", "intent" to "record")
        assertTrue(CascadeRules.isWrite("ask", record))
        assertFalse(CascadeRules.isWrite("look_up", call("domain" to "ask_money", "question" to "invented")))
        assertFalse(CascadeRules.isWrite("end_conversation", call()))
    }
}

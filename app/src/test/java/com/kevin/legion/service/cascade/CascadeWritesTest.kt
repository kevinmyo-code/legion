package com.kevin.legion.service.cascade

import com.kevin.legion.service.LiveToolbox
import org.json.JSONObject
import org.junit.Assert.assertEquals
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
        // 2026-11-19: a mixed tool's read action is no write (it was counted done before).
        assertFalse(CascadeRules.isWrite("manage_checklist", call("action" to "read", "list" to "invented")))
        assertTrue(CascadeRules.isWrite("manage_checklist", call("action" to "add", "list" to "invented")))
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

    /**
     * 2026-11-19: every write the surface offers says something a person would call done, by a
     * phrase, by the generic line, or by nothing on purpose ([ScriptedLines.SILENT]). This lists the
     * writes that fall to the generic line, so a new write is a decision, never "did it".
     */
    @Test
    fun `every write on the surface has a phrase, the generic line, or is silent on purpose`() {
        val all = LiveToolbox.allDeclarations()
        val generic = sortedSetOf<String>()
        for (i in 0 until all.length()) {
            val d = all.getJSONObject(i)
            val name = d.getString("name")
            val actions = d.optJSONObject("parameters")?.optJSONObject("properties")?.optJSONObject("action")
                ?.optJSONArray("enum")?.let { e -> (0 until e.length()).map { e.getString(it) } } ?: listOf("")
            for (action in actions) {
                if (!CascadeRules.isWrite(name, call("action" to action))) continue
                val f = ScriptedLines.writeFact(name, action, JSONObject().put("success", true).put("vehicle", "x").put("title", "x").put("to", "x"), ok = true)
                if (f.silent) continue
                if (f.phrase == null) generic += if (action.isEmpty()) name else "$name/$action"
            }
        }
        assertEquals(GENERIC_WRITES, generic.toList())
    }

    private companion object {
        /** Writes said as "That change went through.": no phrase fits them yet (reviewed 2026-11-19). */
        val GENERIC_WRITES = listOf<String>()
    }
}

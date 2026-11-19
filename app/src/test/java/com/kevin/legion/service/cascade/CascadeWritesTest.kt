@file:Suppress("MaxLineLength") // invented JSON results read best on one line

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
     * 2026-11-19 (review F3): a row per tool and action the surface can call (every declaration as
     * written, before folding: the toolboxes `allDeclarations` leaves out too, and each router with
     * `intent: record`). Every write is PHRASED, GENERIC (listed below on purpose), SILENT (no change
     * he would call done: a read on a writing tool, a screen, a proposal) or a ROUTER (said only on
     * its result's `changed: true`). So a new write is a decision, never a false "done".
     */
    @Test
    fun `every tool and action the surface can call is classified for the done line`() {
        val all = LiveToolbox.sourceDeclarationsForTest()
        val rows = sortedMapOf<String, String>()
        for (i in 0 until all.length()) {
            val d = all.getJSONObject(i)
            val name = d.getString("name")
            val props = d.optJSONObject("parameters")?.optJSONObject("properties")
            val actions = props?.optJSONObject("action")?.optJSONArray("enum")
                ?.let { e -> (0 until e.length()).map { e.getString(it) } } ?: listOf("")
            for (action in actions) {
                val args = call("action" to action, "intent" to "record", "question" to "invented")
                if (!CascadeRules.isWrite(name, args)) continue
                val ok = JSONObject().put("success", true).put("vehicle", "x").put("title", "x").put("to", "x")
                val f = ScriptedLines.writeFact(name, action, ok, ok = true)
                val row = if (action.isEmpty()) name else "$name/$action"
                rows[row] = when {
                    f.silent -> "SILENT"
                    !f.changed -> "ROUTER"
                    f.phrase != null -> "PHRASED"
                    else -> "GENERIC"
                }
            }
        }
        assertTrue("walked the whole surface (${rows.size} writes)", rows.size > 40)
        assertEquals(GENERIC_WRITES, rows.filterValues { it == "GENERIC" }.keys.toList())
        // The two reads the review found spoken as changes, and a router.
        assertEquals("SILENT", rows["claude_session/check"])
        assertEquals("SILENT", rows["app_update/check"])
        assertEquals("ROUTER", rows["ask_fleet"])
        assertEquals("ROUTER", rows["ask_engine"])
        // The adapter's car (manage_vehicle adapter, surfaced as car adapter_in) is a declared action now.
        assertEquals("PHRASED", rows["manage_vehicle/adapter"])
    }

    @Test
    fun `a router's write is said only when its result says something changed`() {
        val scan = ScriptedLines.writeFact("ask_fleet", "", JSONObject().put("success", true).put("say", "Scanning."), ok = true)
        assertFalse(scan.said)
        val logged = ScriptedLines.writeFact("ask_body", "", JSONObject().put("success", true).put("changed", true), ok = true)
        assertTrue(logged.said)
        assertEquals(ScriptedLines.GENERIC_DONE, ScriptedLines.doneLine(listOf(scan, logged)))
    }

    private companion object {
        /** Writes said as "That change went through.": no phrase fits them yet (reviewed 2026-11-19). */
        val GENERIC_WRITES = listOf<String>()
    }
}

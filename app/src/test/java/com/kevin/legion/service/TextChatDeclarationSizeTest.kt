package com.kevin.legion.service

import com.kevin.legion.ai.ALFRED
import org.json.JSONArray
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What typed chat's text model is offered, and what the alternative would have cost (2026-10-27).
 *
 * The brief asked to choose between the trimmed Live set ([LiveToolbox.declarations]) and the full
 * set ([LiveToolbox.allDeclarations], every tool the `ask_*` dispatchers hide) with measured
 * numbers. The estimator is chars/4, the same one [LiveSetupPayloadSizeTest] uses: an ESTIMATE, a
 * floor-ish one, never a billed count. Measured figures are written in the decisions.md entry.
 *
 * Note the full set is not a superset: [LiveToolbox.declarations] appends the dispatchers and the
 * engine/purchase/books toolboxes, which [LiveToolbox.allDeclarations] does not contain.
 */
@RunWith(RobolectricTestRunner::class)
class TextChatDeclarationSizeTest {

    private fun tokens(arr: JSONArray) = arr.toString().length / 4
    private fun names(arr: JSONArray) = (0 until arr.length()).map { arr.getJSONObject(it).getString("name") }

    @Test
    fun `measure the text set against the full set`() {
        val text = LiveToolbox.textDeclarations(ALFRED.key)
        val live = LiveToolbox.declarationsFor(ALFRED.key)
        val all = LiveToolbox.allDeclarations()
        // "Full" as a typed-chat option would be: everything allDeclarations has, plus the toolboxes
        // only declarations() appends, minus the dispatchers that would then be redundant.
        val dispatchers = setOf("ask_fleet", "ask_body", "ask_goals", "ask_pantry", "ask_mail")
        val allNames = names(all).toSet()
        val full = JSONArray()
        for (i in 0 until all.length()) full.put(all.getJSONObject(i))
        for (i in 0 until live.length()) {
            val d = live.getJSONObject(i)
            val n = d.getString("name")
            if (n !in allNames && n !in dispatchers && n !in LiveToolbox.VOICE_SESSION_TOOLS) full.put(d)
        }
        println(
            "typed chat declarations: text set ${text.length()} tools, ${text.toString().length} chars, " +
                "~${tokens(text)} tokens; live set ${live.length()} tools, ~${tokens(live)} tokens; " +
                "full set ${full.length()} tools, ${full.toString().length} chars, ~${tokens(full)} tokens (chars/4)",
        )
        assertTrue(tokens(full) > tokens(text))
    }

    @Test
    fun `the text set is the voice set minus the two voice-session tools`() {
        val text = names(LiveToolbox.textDeclarations(ALFRED.key))
        val live = names(LiveToolbox.declarationsFor(ALFRED.key))
        assertFalse("end_conversation" in text)
        assertFalse("switch_companion" in text)
        assertTrue("end_conversation" in live)
        org.junit.Assert.assertEquals(live.toSet() - LiveToolbox.VOICE_SESSION_TOOLS, text.toSet())
    }

    @Test
    fun `writes are tracked by name, and dispatchers only when the model declared a record`() {
        val record = org.json.JSONObject().put("intent", "record")
        val ask = org.json.JSONObject().put("intent", "ask")
        assertTrue(LiveToolbox.isMutatingCall("log_meal", ask))
        assertTrue(LiveToolbox.isMutatingCall("ask_body", record))
        assertTrue(LiveToolbox.isMutatingCall("ask_engine", record))
        assertFalse(LiveToolbox.isMutatingCall("ask_body", ask))
        assertFalse(LiveToolbox.isMutatingCall("get_current_time", record))
    }
}

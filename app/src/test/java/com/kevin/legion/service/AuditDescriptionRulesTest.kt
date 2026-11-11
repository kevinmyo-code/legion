package com.kevin.legion.service

import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The tool-description rules the 2026-11-09 voice log audit added. Presence only: nothing here can
 * check that the model obeys them. Robolectric for `org.json`.
 */
@RunWith(RobolectricTestRunner::class)
class AuditDescriptionRulesTest {

    private fun declaration(name: String): JSONObject {
        val fns = LiveToolbox.sourceDeclarationsForTest()
        return (0 until fns.length()).map { fns.getJSONObject(it) }.first { it.getString("name") == name }
    }

    /** 11-08 08:51: "add coffee to the list" went to the Todo checklist, two minutes after a grocery run. */
    @Test
    fun `manage_checklist sends a food item on the list to Groceries`() {
        val d = declaration("manage_checklist").getString("description")
        assertTrue(d, d.contains("'the list' with food or a household item means Groceries"))
    }

    /** 11-08 17:29: "take me to the nearest Petco" was sent as just "Petco". */
    @Test
    fun `navigate keeps nearest in the destination`() {
        val dest = declaration("navigate").getJSONObject("parameters").getJSONObject("properties")
            .getJSONObject("destination").getString("description")
        assertTrue(dest, dest.contains("keeping 'nearest' or 'closest'"))
    }

    /** 11-07 10:12: "running clean" and "back online" said with no car tool behind them. */
    @Test
    fun `ask_fleet owns how the car is doing and whether it is connected`() {
        val d = declaration("ask_fleet").getString("description")
        assertTrue(d, d.contains("whether it is connected is answered only from this"))
    }
}

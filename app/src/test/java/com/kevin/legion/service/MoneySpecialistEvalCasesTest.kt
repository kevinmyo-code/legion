package com.kevin.legion.service

import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The OFFLINE half of the money specialist's eval (blind review M1, 2026-11-20): the router picking
 * ask_money says nothing about net cash against budget left, which the specialist decides (Kevin's
 * rulings 2026-11-19/20). `tools/live_tool_eval/money_specialist_cases.jsonl` holds invented
 * sentences and the call each must become. Here, with no key: every expected tool and view exists on
 * the money router. It writes `build/live-setup/money_specialist.json` (the specialist's own
 * instruction and its tools) for `tools/money_specialist_eval.py`, the live half.
 */
@RunWith(RobolectricTestRunner::class)
class MoneySpecialistEvalCasesTest {
    private val moneyTools = listOf(
        "get_balance", "get_monthly_spend", "list_recent_transactions", "list_pending_transactions",
    )

    private fun casesFile(): File =
        listOf("../tools/live_tool_eval", "tools/live_tool_eval")
            .map { File(it, "money_specialist_cases.jsonl") }.first { it.isFile }

    @Test
    fun `every case names a money tool and a view that exist, and the live setup is written`() {
        val decls = LiveToolbox.sourceDeclarationsForTest()
        val byName = (0 until decls.length()).map { decls.getJSONObject(it) }.associateBy { it.getString("name") }
        val reachable = LiveToolbox.reachableNamesForTest()
        moneyTools.forEach { assertTrue("$it is not reachable", it in reachable && it in byName) }
        val views = byName.getValue("get_monthly_spend").getJSONObject("parameters").getJSONObject("properties")
            .getJSONObject("view").getJSONArray("enum").let { e -> (0 until e.length()).map { e.getString(it) } }

        val cases = casesFile().readLines().filter { it.isNotBlank() }.map(::JSONObject)
        assertTrue("fewer than 7 cases", cases.size >= 7)
        for (c in cases) {
            val say = c.getString("say")
            assertTrue("$say: ${c.getString("tool")} is not a money tool", c.getString("tool") in moneyTools)
            val want = c.optJSONArray("view")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
            want.filter { it.isNotEmpty() }.forEach { assertTrue("$say: view $it does not exist", it in views) }
        }

        val setup = JSONObject()
            .put("system", LiveToolbox.moneyGroundingForTest(RuntimeEnvironment.getApplication()))
            .put("tools", JSONArray(moneyTools.map { byName.getValue(it) }))
        File("build/live-setup").mkdirs()
        File("build/live-setup/money_specialist.json").writeText(setup.toString())
    }
}

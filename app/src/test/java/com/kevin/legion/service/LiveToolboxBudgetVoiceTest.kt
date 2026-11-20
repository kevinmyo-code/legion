package com.kevin.legion.service

import android.content.Context
import com.kevin.legion.ai.VerbatimSpeech
import com.kevin.legion.finance.PrefsFinanceCache
import com.kevin.legion.finance.SpokenMoney
import com.kevin.legion.testutil.RoomTestReset
import java.time.YearMonth
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * `get_monthly_spend`'s budget views (2026-11-19) end to end on the phone: the engine's budgets body
 * as this phone saved it (no engine in a test, so the saved copy is what is read, as offline), the
 * structured fields, and the phone speaking the code-made sentence itself (VerbatimSpeech). Invented
 * categories and amounts only.
 */
@RunWith(RobolectricTestRunner::class)
class LiveToolboxBudgetVoiceTest {
    private val context = RuntimeEnvironment.getApplication()
    private val month = YearMonth.now(ZoneId.systemDefault())

    private val body = """
        {"month":"$month","currency":"USD","days_in_month":${month.lengthOfMonth()},"days_elapsed":1,
         "categories":[
           {"category":"Groceries","spent_cents":41207,"cap_cents":60000,"status":"ok"},
           {"category":"Dining","spent_cents":18950,"cap_cents":25000,"status":"ok"}],
         "total":{"spent_cents":60157,"cap_cents":85000},
         "uncategorised_cents":0,"fixed":[]}
    """.trimIndent()

    @Before
    fun setUp() {
        RoomTestReset.resetCarDatabaseSingleton()
        context.getSharedPreferences("finance_cache", Context.MODE_PRIVATE).edit().clear().commit()
        PrefsFinanceCache(context).write("budgets:$month", body, System.currentTimeMillis() - 60_000L)
    }

    @After
    fun drain() {
        RoomTestReset.drainArchDiskIoPool()
    }

    private fun call(vararg args: Pair<String, String>): JSONObject = runBlocking {
        val json = JSONObject()
        args.forEach { (k, v) -> json.put(k, v) }
        LiveToolbox.dispatch(context, "get_monthly_spend", json)!!
    }

    @Test
    fun `budget left is the saved engine figure, spoken by the phone`() {
        val r = call("view" to "left")
        assertTrue(r.getBoolean("success"))
        assertEquals(24_843L, r.getLong("left_cents"))
        assertEquals("248.43", r.getString("amount"))
        assertEquals("USD", r.getString("currency"))
        assertEquals(month.atDay(1).toString(), r.getString("period_start"))
        assertEquals(month.atEndOfMonth().toString(), r.getString("period_end"))
        assertTrue(r.has("as_of"))
        assertFalse(r.getBoolean("stale"))
        val spoken = VerbatimSpeech.of(r)!!
        assertTrue(spoken, spoken.contains(SpokenMoney.amount(24_843)))
        assertTrue(spoken, spoken.contains("last saved figure"))
    }

    @Test
    fun `a category's spend this month, matched by folded name`() {
        val r = call("view" to "category", "category" to "groceries")
        assertEquals("Groceries", r.getString("category"))
        assertEquals(41_207L, r.getLong("amount_cents"))
        assertTrue(VerbatimSpeech.of(r)!!.contains(SpokenMoney.amount(41_207)))
    }

    // Kevin, 2026-11-19: "whats left means how much net cash i have left." A bare "what's left" is
    // get_balance's net cash; the budget view is only for a budget he names.
    @Test
    fun `a bare what's left is net cash, the budget view only when he names a budget`() {
        val decls = LiveToolbox.sourceDeclarationsForTest()
        val byName = (0 until decls.length()).map { decls.getJSONObject(it) }.associateBy { it.getString("name") }
        val spend = byName.getValue("get_monthly_spend").getString("description")
        val balance = byName.getValue("get_balance").getString("description")
        assertTrue(spend, spend.contains("view=left only when he names a budget"))
        assertTrue(spend, spend.contains("a bare \"what's left\" is net cash: get_balance"))
        assertTrue(balance, balance.contains("\"what's left\""))
    }

    @Test
    fun `a category named with no view is the category view, never the month total`() {
        val r = call("category" to "Dining")
        assertEquals("category", r.getString("view"))
        assertEquals(18_950L, r.getLong("amount_cents"))
        assertFalse(r.has("total_spent_cents"))
    }

    @Test
    fun `a category that does not exist hands back the valid names and is not spoken`() {
        val r = call("view" to "category", "category" to "yacht upkeep")
        assertFalse(r.getBoolean("success"))
        assertNull(VerbatimSpeech.of(r))
        val names = r.getJSONArray("valid_categories")
        assertTrue((0 until names.length()).map { names.getString(it) }.containsAll(listOf("Dining", "Groceries")))
    }

    @Test
    fun `top categories come back in order with exact decimal amounts`() {
        val r = call("view" to "top")
        val top = r.getJSONArray("top")
        assertEquals("Groceries", top.getJSONObject(0).getString("category"))
        assertEquals("412.07", top.getJSONObject(0).getString("amount"))
        assertEquals("Dining", top.getJSONObject(1).getString("category"))
    }

    @Test
    fun `a month with no saved copy says it could not be read, never zero`() {
        val r = call("view" to "left", "month" to "last")
        assertFalse(r.getBoolean("success"))
        assertFalse(r.has("left_cents"))
        assertTrue(VerbatimSpeech.of(r)!!, VerbatimSpeech.of(r)!!.startsWith("There is no budget figure for"))
    }
}

package com.kevin.legion.ledger

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineMonthlySpendTest {

    private fun body(
        unverified: Boolean = false,
        complete: Boolean = true,
        uncategorised: Long = 0,
        own: Long = 0,
    ) = JSONObject(
        """
        {"month": "2026-10", "currency": "USD",
         "accounts": [
           {"account_last4": "7823", "spend_cents": 120000, "unverified": $unverified},
           {"account_last4": "5042", "spend_cents": 3450, "unverified": false}
         ],
         "categories": [],
         "uncategorised_cents": $uncategorised, "uncategorised_unverified": false,
         "excluded": {"not_spending_cents": 0, "not_spending_categories": [],
                      "own_account_moves_cents": $own, "early_charges_moved_cents": 0,
                      "early_charges_counted_here_cents": 0,
                      "early_charges_counted_next_month_cents": 180000},
         "complete": $complete}
        """.trimIndent(),
    )

    @Test
    fun `the total is every account's spend and the source is the engine`() {
        val out = EngineMonthlySpend.toToolJson(body())
        assertEquals(123450L, out.getLong("total_spent_cents"))
        assertEquals("engine", out.getString("source"))
        assertTrue(out.getBoolean("verified"))
    }

    @Test
    fun `an unverified account or an incomplete month is not verified`() {
        assertFalse(EngineMonthlySpend.toToolJson(body(unverified = true)).getBoolean("verified"))
        assertFalse(EngineMonthlySpend.toToolJson(body(complete = false)).getBoolean("verified"))
    }

    @Test
    fun `exclusions are said in words, and counts the engine does not send are null`() {
        val out = EngineMonthlySpend.toToolJson(body(uncategorised = 300, own = 50000))
        assertTrue(out.getString("uncategorized_note").contains("USD 3.00 is not assigned"))
        assertTrue(out.getString("note").contains("USD 500.00 moving money to your own accounts"))
        assertTrue(out.isNull("excluded_own_account_movements_count"))
        assertEquals(1, out.getJSONArray("early_housing_notes").length())
    }

    @Test
    fun `a fallback says it is the phone's copy and why`() {
        val note = EngineMonthlySpend.fallbackNote("not signed in to the engine")
        assertTrue(note.contains("not signed in to the engine"))
        assertTrue(note.contains("phone's own copy"))
    }
}

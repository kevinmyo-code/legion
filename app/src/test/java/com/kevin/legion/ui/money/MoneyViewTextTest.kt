package com.kevin.legion.ui.money

import com.kevin.legion.finance.FinanceFixtures
import com.kevin.legion.finance.FinanceText
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Household-finance 14: the Money view's sections, said in words. */
class MoneyViewTextTest {
    private val today = FinanceFixtures.today

    @Test
    fun `this month in USD, planned income said as an estimate, the rate said`() {
        val model = thisMonthModel(FinanceFixtures.cashFlow, "USD")
        assertEquals(KvRow("Money in, planned", "$2,610", "estimate"), model.rows[0])
        assertEquals(KvRow("Spent", "$1,402"), model.rows[2])
        assertEquals(KvRow("Net so far", "+$451", "estimate", good = true), model.rows[4])
        assertTrue(model.footnotes.contains("Rate 1 USD = 1.2946 SGD, Nov 5."))
        assertTrue(model.footnotes.any { it.startsWith("No rows yet this month from spouse_checking") })
    }

    @Test
    fun `this month in SGD converts at the engine's rate`() {
        val model = thisMonthModel(FinanceFixtures.cashFlow, "SGD")
        assertEquals("SGD 980", model.rows[3].value) // 757.00 USD x 1.2946
        assertEquals(98_001L, FinanceText.usdToSgd(75_700, 1_294_600))
    }

    @Test
    fun `no rate means no SGD view, said in words`() {
        val model = thisMonthModel(FinanceFixtures.cashFlow.copy(rate = null), "SGD")
        assertFalse(model.sgdAvailable)
        assertEquals("$757", model.rows[3].value)
        assertTrue(model.footnotes.contains("No USD/SGD rate is on file, so there is no SGD view."))
    }

    @Test
    fun `net worth by layer says partial and estimate`() {
        val model = netWorthModel(FinanceFixtures.netWorth)
        assertEquals("partial: 4 accounts not in it", model.partialLine)
        assertEquals(
            LayerRow("Property", locked = true, value = "$151,000", tags = listOf("estimate")),
            model.layers[3],
        )
        assertEquals(
            LayerRow("Investments", locked = false, value = "nothing on file", tags = emptyList()),
            model.layers[1],
        )
        assertEquals(KvRow("Total", "$148,200", "estimate"), model.total)
    }

    @Test
    fun `accounts are one line each, with how the balance is known`() {
        val rows = accountRows(FinanceFixtures.accounts, FinanceFixtures.netWorth, today)
        assertEquals(AccountRow("Everyday checking 0001", "live, as of today", "$88.50"), rows[0])
        assertEquals("live, as of today, -$32 pending", rows[1].sub)
        assertEquals(AccountRow("Spouse checking", "statement, none yet", "-"), rows[2])
        assertEquals("worked out, estimate, as of Nov 1", rows[4].sub)
    }

    @Test
    fun `statements present or missing for the month that is owed`() {
        val nov = statementRows(FinanceFixtures.accounts, FinanceFixtures.netWorth, YearMonth.of(2026, 11), today)
        assertEquals(StatementRow("Everyday checking", "live, no statement needed", true), nov[0])
        assertEquals(StatementRow("Spouse checking", "none yet", false), nov[2])
        val withSg = FinanceFixtures.netWorth.copy(
            layers = FinanceFixtures.netWorth.layers + com.kevin.legion.finance.NetWorthLayerWire(
                "liquid", 0, false,
                listOf(com.kevin.legion.finance.NetWorthLineWire(
                    "sg_savings",
                    "Singapore savings",
                    "SGD",
                    1,
                    "2026-09-30",
                    "statement",
                )),
            ),
        )
        // November is not over, so October is owed; the newest statement is September's.
        assertEquals(
            StatementRow("Singapore savings", "October missing", false),
            statementRows(FinanceFixtures.accounts, withSg, YearMonth.of(2026, 11), today)[3],
        )
        // On September's own page, September is on file.
        assertEquals(
            StatementRow("Singapore savings", "September on file", true),
            statementRows(FinanceFixtures.accounts, withSg, YearMonth.of(2026, 9), today)[3],
        )
    }

    @Test
    fun `money is said as the prototype says it`() {
        assertEquals("-$1,657", FinanceText.money(-165_700))
        assertEquals("$1,403", FinanceText.money(140_250))
        assertEquals("SGD 980", FinanceText.money(98_000, "SGD"))
        assertEquals("-$1,745.74", FinanceText.exact(-174_574))
        assertEquals("$3,500", FinanceText.compact(350_000))
        assertEquals("-$12.5k", FinanceText.compact(-1_250_000))
    }
}

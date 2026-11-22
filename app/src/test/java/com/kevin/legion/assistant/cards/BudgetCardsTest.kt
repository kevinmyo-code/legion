@file:Suppress("MaxLineLength") // fixtures and phrase lists read best on one line

package com.kevin.legion.assistant.cards

import com.kevin.legion.finance.BudgetLineWire
import com.kevin.legion.finance.BudgetTotalWire
import com.kevin.legion.finance.BudgetsWire
import com.kevin.legion.finance.FinanceRead
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The budget card a money answer puts up: the engine's budget_pace_card, row for row. Invented figures. */
class BudgetCardsTest {

    private val body = BudgetsWire(
        month = "2027-03",
        daysInMonth = 31,
        daysElapsed = 10,
        projectionFromDay = 10,
        categories = listOf(
            BudgetLineWire("Groceries", spentCents = 20_000, capCents = 50_000, projectionCents = 62_000),
            BudgetLineWire("Dining", spentCents = 31_250, capCents = 30_000, status = "over", pendingCents = 1_250, pendingCount = 1),
            BudgetLineWire("Fuel", spentCents = 4_000, capCents = 20_000, projectionCents = 12_400),
            BudgetLineWire("Gifts", spentCents = 9_000, capCents = null, status = "no cap"),
        ),
        total = BudgetTotalWire(spentCents = 64_250, capCents = 100_000),
    )

    @Test
    fun `capped categories only, the ones needing attention first, figures as the body states them`() {
        val card = BudgetCards.pace(FinanceRead.Fresh(body), nowMs = 1_000L)!!
        assertEquals("Budget pace", card.title)
        assertEquals(listOf("Dining", "Groceries", "Fuel"), card.items.map { it.name })
        assertEquals(listOf(RowStatus.ATTENTION, RowStatus.ATTENTION, RowStatus.OK), card.items.map { it.status })
        assertEquals("Over: \$312.50 of \$300.00. \$12.50 of it pending.", card.items[0].note)
        assertEquals("On pace to go over: \$200.00 of \$500.00. About \$620.00 by month end (estimate).", card.items[1].note)
        assertEquals("On track: \$40.00 of \$200.00. About \$124.00 by month end (estimate).", card.items[2].note)
        val meter = card.items[1].meter!!
        assertEquals(BigDecimal.valueOf(20_000), meter.value)
        assertEquals(BigDecimal.valueOf(50_000), meter.max)
        assertEquals("usd", meter.unit)
        // 50,000 x 10 / 31, rounded to the cent, as the engine works it.
        assertEquals(BigDecimal.valueOf(16_129), meter.mark!!.value)
        assertEquals("March 2027, day 10 of 31", card.period)
        assertEquals("Altogether \$642.50 of \$1,000.00.", card.note)
        assertEquals(CardDestination.MONEY, card.open)
    }

    @Test
    fun `a saved copy says so in words`() {
        val card = BudgetCards.pace(FinanceRead.Saved(body, savedAtMs = 500L, note = "Saved copy from yesterday."), 1_000L)!!
        assertTrue(card.note!!, card.note!!.endsWith("Saved copy from yesterday."))
        assertEquals(500L, card.provenance!!.readAtMs)
    }

    @Test
    fun `no read, no caps or another currency is no card`() {
        assertNull(BudgetCards.pace(null, 1_000L))
        assertNull(BudgetCards.pace(FinanceRead.Unavailable("offline"), 1_000L))
        assertNull(BudgetCards.pace(FinanceRead.Fresh(body.copy(categories = body.categories.takeLast(1))), 1_000L))
        assertNull(BudgetCards.pace(FinanceRead.Fresh(body.copy(currency = "SGD")), 1_000L))
    }
}

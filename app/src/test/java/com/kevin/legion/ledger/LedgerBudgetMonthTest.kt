package com.kevin.legion.ledger

import com.kevin.legion.data.local.IngestMethod
import com.kevin.legion.data.local.LedgerCurrency
import com.kevin.legion.data.local.LedgerTransaction
import com.kevin.legion.ui.home.moneyDisclosureLine
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Kevin's 2026-09-29 ruling "b": "Treat a Housing charge in the last 3 days of a month as belonging
 * to the next month." Exercises [budgetMonthOf] (the one definition), [budgetMonthRows] (the
 * selection the controller's fetch uses), the [EarlyChargesMoved] disclosure [buildBudgetVsActual]
 * attaches, and [earlyChargeSentences]. Plain JUnit. Every fixture is invented; the rent amount only
 * mirrors the shape of the real charge.
 */
class LedgerBudgetMonthTest {

    private var nextId = 1L

    private fun txn(
        date: LocalDate,
        amountCents: Long,
        category: String? = HOUSING,
        accountId: String = "card",
        description: String = "RPS*RENT PORTAL",
    ) = LedgerTransaction(
        id = nextId++,
        sourceFile = "statement.pdf",
        accountId = accountId,
        currency = LedgerCurrency.USD,
        txnDate = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        description = description,
        amountCents = amountCents,
        lineRef = "line",
        ingestMethod = IngestMethod.DETERMINISTIC,
        category = category,
    )

    private fun budgetFor(
        month: YearMonth,
        rows: List<LedgerTransaction>,
        ownAccountIds: Set<String> = emptySet(),
    ): BudgetVsActual {
        val (pairingWindow, inPeriod) = budgetMonthRows(rows, LedgerCurrency.USD, month, pairingDays = 5)
        return buildBudgetVsActual(
            entity = LedgerEntity.US,
            month = month,
            inPeriod = inPeriod,
            pairingWindow = pairingWindow,
            targets = emptyMap(),
            coverage = listOf(
                AccountCoverage("card", coversWholeMonth = true, coveredFromMs = 0L, coveredToMs = Long.MAX_VALUE),
            ),
            ownAccountIds = ownAccountIds,
        )
    }

    // ------------------------------------------------------------------ the definition

    @Test
    fun `a Housing charge on the 29th, 30th or 31st of a 31-day month moves to the next month`() {
        for (day in 29..31) {
            val row = txn(LocalDate.of(2026, 8, day), -RENT)
            assertTrue("day $day", countsInNextMonth(row))
            assertEquals("day $day", YearMonth.of(2026, 9), budgetMonthOf(row))
        }
    }

    @Test
    fun `a Housing charge on the 28th of a 31-day month stays in its own month`() {
        val row = txn(LocalDate.of(2026, 8, 28), -RENT)
        assertFalse(countsInNextMonth(row))
        assertEquals(YearMonth.of(2026, 8), budgetMonthOf(row))
    }

    @Test
    fun `February's last three days are the 26th, 27th and 28th`() {
        for (day in 26..28) {
            assertEquals("day $day", YearMonth.of(2026, 3), budgetMonthOf(txn(LocalDate.of(2026, 2, day), -RENT)))
        }
        assertEquals(YearMonth.of(2026, 2), budgetMonthOf(txn(LocalDate.of(2026, 2, 25), -RENT)))
    }

    @Test
    fun `a 30-day month moves the 28th but not the 27th`() {
        assertEquals(YearMonth.of(2026, 10), budgetMonthOf(txn(LocalDate.of(2026, 9, 28), -RENT)))
        assertEquals(YearMonth.of(2026, 9), budgetMonthOf(txn(LocalDate.of(2026, 9, 27), -RENT)))
    }

    @Test
    fun `a non-Housing charge on the 31st does not move`() {
        val groceries = txn(LocalDate.of(2026, 8, 31), -80_00, category = "Groceries")
        val uncategorised = txn(LocalDate.of(2026, 8, 31), -80_00, category = null)
        assertEquals(YearMonth.of(2026, 8), budgetMonthOf(groceries))
        assertEquals(YearMonth.of(2026, 8), budgetMonthOf(uncategorised))
    }

    @Test
    fun `an inflow categorised Housing does not move`() {
        val refund = txn(LocalDate.of(2026, 8, 31), RENT)
        assertFalse(countsInNextMonth(refund))
        assertEquals(YearMonth.of(2026, 8), budgetMonthOf(refund))
    }

    @Test
    fun `December's late Housing charge moves into January of the next year`() {
        assertEquals(YearMonth.of(2027, 1), budgetMonthOf(txn(LocalDate.of(2026, 12, 31), -RENT)))
    }

    @Test
    fun `the transaction's own date is never changed`() {
        val row = txn(LocalDate.of(2026, 8, 31), -RENT)
        val before = row.txnDate
        budgetFor(YearMonth.of(2026, 9), listOf(row))
        assertEquals(before, row.txnDate)
        assertEquals(LocalDate.of(2026, 8, 31), calendarDateOf(row))
    }

    // ------------------------------------------------------------------ the figures

    @Test
    fun `September includes the August 31 rent and August excludes it`() {
        val augRent = txn(LocalDate.of(2026, 8, 31), -RENT)
        val augGroceries = txn(LocalDate.of(2026, 8, 15), -50_00, category = "Groceries")
        val septGroceries = txn(LocalDate.of(2026, 9, 10), -60_00, category = "Groceries")
        val rows = listOf(augRent, augGroceries, septGroceries)

        val august = budgetFor(YearMonth.of(2026, 8), rows)
        val september = budgetFor(YearMonth.of(2026, 9), rows)

        assertEquals(50_00L, august.spentCents)
        assertTrue(august.lines.none { it.category == HOUSING })
        assertEquals(RENT + 60_00L, september.spentCents)
        assertEquals(RENT, september.lines.single { it.category == HOUSING }.gap.actual)

        // Each month names the moved row, from its own side.
        assertEquals(listOf(augRent.id), september.earlyChargesMoved.countedHere.map { it.id })
        assertTrue(september.earlyChargesMoved.countedNextMonth.isEmpty())
        assertEquals(listOf(augRent.id), august.earlyChargesMoved.countedNextMonth.map { it.id })
        assertTrue(august.earlyChargesMoved.countedHere.isEmpty())
    }

    @Test
    fun `the September tile states the moved rent in words`() {
        val augRent = txn(LocalDate.of(2026, 8, 31), -RENT)
        val septRent = txn(LocalDate.of(2026, 9, 30), -RENT)
        val september = budgetFor(YearMonth.of(2026, 9), listOf(augRent, septRent))

        assertEquals(RENT, september.spentCents)
        assertEquals(
            "Includes a Housing charge of USD 1,180.63 dated 2026-08-31, counted in September - " +
                "A Housing charge of USD 1,180.63 dated 2026-09-30 counts in October, not here",
            moneyDisclosureLine(september),
        )
    }

    @Test
    fun `a month with nothing moved has no early-charge words`() {
        val september = budgetFor(YearMonth.of(2026, 9), listOf(txn(LocalDate.of(2026, 9, 1), -RENT)))
        assertTrue(september.earlyChargesMoved.isEmpty)
        assertTrue(earlyChargeSentences(september.earlyChargesMoved, september.month, LedgerCurrency.USD).isEmpty())
    }

    // ------------------------------------------------------------------ the sentences

    @Test
    fun `the disclosure sentences name the amount, the real date and the month counted`() {
        val augRent = txn(LocalDate.of(2026, 8, 31), -RENT)
        val septRent = txn(LocalDate.of(2026, 9, 30), -RENT)
        val moved = EarlyChargesMoved(countedHere = listOf(augRent), countedNextMonth = listOf(septRent))

        assertEquals(
            listOf(
                "Includes a Housing charge of USD 1,180.63 dated 2026-08-31, counted in September.",
                "A Housing charge of USD 1,180.63 dated 2026-09-30 counts in October, not here.",
            ),
            earlyChargeSentences(moved, YearMonth.of(2026, 9), LedgerCurrency.USD),
        )
    }

    @Test
    fun `a move across the year names the year`() {
        val decRent = txn(LocalDate.of(2026, 12, 30), -RENT)
        assertEquals(
            listOf("A Housing charge of USD 1,180.63 dated 2026-12-30 counts in January 2027, not here."),
            earlyChargeSentences(
                EarlyChargesMoved(emptyList(), listOf(decRent)), YearMonth.of(2026, 12), LedgerCurrency.USD,
            ),
        )
        assertEquals(
            listOf("Includes a Housing charge of USD 1,180.63 dated 2026-12-30, counted in January."),
            earlyChargeSentences(
                EarlyChargesMoved(listOf(decRent), emptyList()), YearMonth.of(2027, 1), LedgerCurrency.USD,
            ),
        )
    }

    // ------------------------------------------------------------------ pairing

    @Test
    fun `the pairing window stays on calendar dates`() {
        val septRent = txn(LocalDate.of(2026, 9, 30), -RENT)
        val augRent = txn(LocalDate.of(2026, 8, 31), -RENT)
        val (pairingWindow, inPeriod) =
            budgetMonthRows(listOf(septRent, augRent), LedgerCurrency.USD, YearMonth.of(2026, 9), pairingDays = 5)
        // Moved out of September's figure, still a pairing candidate by its date...
        assertTrue(septRent in pairingWindow)
        assertFalse(septRent in inPeriod)
        // ...and the moved-in row is a candidate by its date too (inside the 5-day pad).
        assertTrue(augRent in pairingWindow)
        assertTrue(augRent in inPeriod)
    }

    @Test
    fun `a card payment filed as Housing on the 31st is still paired and never becomes spend in either month`() {
        val cardPayment = txn(
            LocalDate.of(2026, 8, 31), -1300_00, accountId = "checking", description = "PAYMENT TO CRD 7823",
        )
        val rows = listOf(cardPayment)
        val own = setOf("4111111111117823")

        val august = budgetFor(YearMonth.of(2026, 8), rows, own)
        val september = budgetFor(YearMonth.of(2026, 9), rows, own)

        assertEquals(0L, august.spentCents)
        assertEquals(0L, september.spentCents)
        // Classified as an own-account movement exactly as before; the month it is disclosed in
        // follows the reading rule, the classification does not.
        assertEquals(listOf(cardPayment.id), september.excludedOwnAccountMovements.rows.map { it.id })
        // Not spend, so neither month claims to have moved it.
        assertTrue(august.earlyChargesMoved.isEmpty)
        assertTrue(september.earlyChargesMoved.isEmpty)
    }

    @Test
    fun `a caller that still passes calendar rows is never told its counted row is not here`() {
        val augRent = txn(LocalDate.of(2026, 8, 31), -RENT)
        val result = buildBudgetVsActual(
            entity = LedgerEntity.US, month = YearMonth.of(2026, 8),
            inPeriod = listOf(augRent), pairingWindow = listOf(augRent),
            targets = emptyMap(), coverage = emptyList(),
        )
        assertEquals(RENT, result.spentCents)
        assertTrue(result.earlyChargesMoved.countedNextMonth.isEmpty())
    }

    companion object {
        private const val HOUSING = "Housing"
        private const val RENT = 1180_63L
    }
}

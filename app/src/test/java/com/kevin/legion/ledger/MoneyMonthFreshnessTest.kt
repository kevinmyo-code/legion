package com.kevin.legion.ledger

import com.kevin.legion.data.local.IngestMethod
import com.kevin.legion.data.local.LedgerCurrency
import com.kevin.legion.data.local.LedgerTransaction
import com.kevin.legion.ui.money.accountDataThroughLine
import com.kevin.legion.ui.money.accountsDifferInFreshness
import com.kevin.legion.ui.money.dataThroughLine
import com.kevin.legion.ui.money.emptyMonthMessage
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Money page's freshness: stale is its own sentence, never "you spent nothing". Invented rows. */
class MoneyMonthFreshnessTest {

    private val today = LocalDate.of(2026, 10, 2)
    private val oct = YearMonth.of(2026, 10)

    private fun txn(date: LocalDate, account: String, cents: Long = -1_000) = LedgerTransaction(
        sourceFile = "synced",
        accountId = account,
        currency = LedgerCurrency.USD,
        txnDate = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        description = "X",
        amountCents = cents,
        lineRef = "l$date$account",
        ingestMethod = IngestMethod.DETERMINISTIC,
        category = "Dining",
    )

    @Test
    fun `newest date is per account and overall, across every month`() {
        val rows = listOf(
            txn(LocalDate.of(2026, 9, 26), "BofA card"), txn(LocalDate.of(2026, 8, 1), "BofA card"),
            txn(LocalDate.of(2026, 9, 20), "BofA checking"),
        )
        val data = buildMoneyMonthData(rows, oct, emptySet())
        assertEquals(LocalDate.of(2026, 9, 26), data.newestOverall)
        assertEquals(LocalDate.of(2026, 9, 26), data.newestByAccount["BofA card"])
        assertEquals(LocalDate.of(2026, 9, 20), data.newestByAccount["BofA checking"])
        assertTrue("stale data shows no sections", data.accounts.isEmpty())
    }

    @Test
    fun `no rows at all has no newest date`() {
        val data = buildMoneyMonthData(emptyList(), oct, emptySet())
        assertNull(data.newestOverall)
        assertTrue(data.newestByAccount.isEmpty())
    }

    @Test
    fun `a bare last-4 and a full number share one newest date`() {
        val rows = listOf(
            txn(LocalDate.of(2026, 9, 1), "5555555555557823"), txn(LocalDate.of(2026, 9, 9), "7823"),
        )
        val data = buildMoneyMonthData(rows, oct, emptySet())
        assertEquals(1, data.newestByAccount.size)
        assertEquals(LocalDate.of(2026, 9, 9), data.newestByAccount.values.single())
    }

    @Test
    fun `empty month with data that stops before the month says the data is old`() {
        assertEquals(
            "No October transactions yet. The newest bank data is from Sep 26.",
            emptyMonthMessage(oct, LocalDate.of(2026, 9, 26), today),
        )
    }

    @Test
    fun `empty month with no rows ever says there is no bank data`() {
        assertEquals("No bank data yet.", emptyMonthMessage(oct, null, today))
    }

    @Test
    fun `empty month with data inside the month says nothing spent`() {
        assertEquals("Nothing spent yet this month.", emptyMonthMessage(oct, LocalDate.of(2026, 10, 1), today))
    }

    @Test
    fun `the year is added only when it is not the current year`() {
        assertEquals("Bank data through Sep 26", dataThroughLine(LocalDate.of(2026, 9, 26), today))
        assertEquals("Bank data through Dec 31, 2025", dataThroughLine(LocalDate.of(2025, 12, 31), today))
        assertEquals("Data through Oct 1", accountDataThroughLine(LocalDate.of(2026, 10, 1), today))
    }

    @Test
    fun `per-account lines only when accounts differ`() {
        val a = LocalDate.of(2026, 9, 26)
        assertFalse(accountsDifferInFreshness(mapOf("x" to a, "y" to a)))
        assertTrue(accountsDifferInFreshness(mapOf("x" to a, "y" to a.minusDays(1))))
    }
}

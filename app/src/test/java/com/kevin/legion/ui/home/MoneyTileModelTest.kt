package com.kevin.legion.ui.home

import com.kevin.legion.data.local.IngestMethod
import com.kevin.legion.data.local.LedgerCurrency
import com.kevin.legion.data.local.LedgerTransaction
import com.kevin.legion.ledger.CategorySpend
import com.kevin.legion.ledger.CombinedMonthSpend
import com.kevin.legion.ledger.buildMoneyMonthData
import com.kevin.legion.ledger.combineMonthSpend
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HOME's Money tile: the per-category month COMBINED across accounts ([combineMonthSpend]) and how
 * it becomes a tile ([moneyTileModel]). The sums must equal the Money page's, so the first group
 * builds from real rows through [buildMoneyMonthData], not from hand-made figures.
 */
class MoneyTileModelTest {

    private val oct = YearMonth.of(2026, 10)
    private var nextId = 1L

    private fun txn(
        day: Int,
        cents: Long,
        account: String,
        category: String?,
        method: IngestMethod = IngestMethod.DETERMINISTIC,
        currency: LedgerCurrency = LedgerCurrency.USD,
    ) = LedgerTransaction(
        id = nextId++, sourceFile = "synced", accountId = account, currency = currency,
        txnDate = LocalDate.of(2026, 10, day).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        description = "M", amountCents = cents, lineRef = "l$nextId", ingestMethod = method, category = category,
    )

    private fun combined(rows: List<LedgerTransaction>) =
        combineMonthSpend(buildMoneyMonthData(rows, oct, emptySet()), oct, LedgerCurrency.USD)

    @Test
    fun `categories are summed across accounts and sorted largest first`() {
        val c = combined(
            listOf(
                txn(1, -3_000, "BofA card 7823", "Dining"), txn(2, -2_000, "BofA checking 5042", "Dining"),
                txn(2, -9_000, "BofA checking 5042", "Groceries"), txn(3, -500, "BofA card 7823", "Fuel"),
            ),
        )
        assertEquals(
            listOf("Groceries" to 9_000L, "Dining" to 5_000L, "Fuel" to 500L),
            c.categories.map { it.category to it.cents },
        )
        assertEquals(14_500L, c.totalCents)
    }

    @Test
    fun `uncategorised is kept out of the total and the bars but reported`() {
        val c = combined(listOf(txn(1, -3_000, "A 1111", "Dining"), txn(2, -700, "B 2222", null)))
        assertEquals(3_000L, c.totalCents)
        assertEquals(700L, c.uncategorizedCents)
        assertEquals(listOf("Dining"), c.categories.map { it.category })
    }

    @Test
    fun `a refund is not netted and a card payment is not spend`() {
        val c = combined(
            listOf(
                txn(1, -4_000, "BofA card 7823", "Shopping"), txn(2, 1_500, "BofA card 7823", "Shopping"),
                txn(2, -50_000, "BofA checking 5042", "Payments").copy(description = "PAYMENT TO CRD 7823"),
                txn(2, 50_000, "BofA card 7823", "Payments").copy(description = "PAYMENT FROM CHK 5042"),
            ),
        )
        assertEquals(4_000L, c.totalCents)
    }

    @Test
    fun `current-period rows are counted as unverified inside the total`() {
        val c = combined(
            listOf(txn(1, -3_000, "A 1111", "Dining", IngestMethod.UNRECONCILED), txn(2, -1_000, "A 1111", "Fuel")),
        )
        assertEquals(3_000L, c.unverifiedTotalCents)
        assertEquals(4_000L, c.totalCents)
    }

    @Test
    fun `another currency is not summed in`() {
        val sgd = txn(1, -9_999, "DBS 8802", "Dining", currency = LedgerCurrency.SGD)
        val c = combined(listOf(txn(1, -3_000, "A 1111", "Dining"), sgd))
        assertEquals(3_000L, c.totalCents)
    }

    @Test
    fun `staleness is judged on the summed currency's own newest row`() {
        val sgdFresh = txn(1, -100, "DBS 8802", "Dining", currency = LedgerCurrency.SGD)
        val usdOld = txn(1, -100, "A 1111", "Dining").copy(
            txnDate = LocalDate.of(2026, 9, 26).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        val c = combined(listOf(sgdFresh, usdOld))
        assertEquals(LocalDate.of(2026, 9, 26), c.newest)
    }

    // ---------------------------------------------------------------- tile model

    private fun month(
        categories: List<CategorySpend>,
        unverified: Long = 0L,
        newest: LocalDate? = LocalDate.of(2026, 10, 2),
    ) =
        CombinedMonthSpend(
            LedgerCurrency.USD, oct, categories.sumOf { it.cents }, categories, 0L, unverified, emptyList(),
            hasActivity = true, newest = newest,
        )

    private fun cat(name: String, cents: Long) = CategorySpend(name, cents, 0L, false)

    @Test
    fun `top three bars scale to the largest and the rest are counted`() {
        val model = moneyTileModel(
            month(listOf(cat("A", 10_000), cat("B", 5_000), cat("C", 2_500), cat("D", 100), cat("E", 50))),
            failed = false, budget = null, syncLine = null,
        )
        assertEquals(listOf("A", "B", "C"), model.bars.map { it.label })
        assertEquals(listOf(1f, 0.5f, 0.25f), model.bars.map { it.fraction })
        assertEquals("+2 more", model.moreLine)
        assertEquals("USD 176.50 this month", model.status.text)
    }

    @Test
    fun `three categories or fewer have no more line`() {
        assertNull(moneyTileModel(month(listOf(cat("A", 1_000), cat("B", 500))), false, null, null).moreLine)
    }

    @Test
    fun `current period shows its own word line only when some counted spend is unreconciled`() {
        val some = moneyTileModel(month(listOf(cat("A", 1_000)), unverified = 1_000), false, null, null)
        assertEquals("Current period", some.currentPeriodLine)
        assertNull(moneyTileModel(month(listOf(cat("A", 1_000))), false, null, null).currentPeriodLine)
    }

    @Test
    fun `stale data says no data yet for the month, never bars of zero`() {
        val model = moneyTileModel(month(emptyList(), newest = LocalDate.of(2026, 9, 26)), false, null, null)
        assertEquals("No October data yet", model.status.text)
        assertTrue(model.bars.isEmpty())
    }

    @Test
    fun `no rows at all, and nothing spent this month, are their own sentences`() {
        val none = moneyTileModel(month(emptyList(), newest = null), false, null, null)
        assertEquals("No bank data yet", none.status.text)
        assertEquals("Nothing spent yet this month", moneyTileModel(month(emptyList()), false, null, null).status.text)
    }

    @Test
    fun `a failed read says it couldn't read, in alert`() {
        val model = moneyTileModel(null, failed = true, budget = null, syncLine = null)
        assertEquals("Couldn't read spending", model.status.text)
        assertTrue(model.status.alert)
    }

    @Test
    fun `an unreadable account is named in the disclosure`() {
        val m = month(listOf(cat("A", 1_000))).copy(unreadableNames = listOf("BofA checking"))
        assertEquals("Couldn't read BofA checking", moneyTileModel(m, false, null, null).disclosure)
    }
}

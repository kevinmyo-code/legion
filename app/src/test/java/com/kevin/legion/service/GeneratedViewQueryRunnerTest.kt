package com.kevin.legion.service

import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.IngestMethod
import com.kevin.legion.data.local.LedgerCurrency
import com.kevin.legion.data.local.LedgerTransaction
import com.kevin.legion.data.local.PantryReceipt
import com.kevin.legion.testutil.RoomTestReset
import java.time.YearMonth
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * [GeneratedViewQueryRunner] end to end against Room - the one place this ticket's whole rule
 * ("the model chooses the shape and names the query, it never emits a value") is actually checked:
 * every figure below has to come out of a real seeded row, never out of the spec itself.
 * [GeneratedViewControllerTest] covers the pure parser; this covers the runner reading through
 * [com.kevin.legion.ledger.LedgerController]/[com.kevin.legion.pantry.PantryController].
 */
@RunWith(RobolectricTestRunner::class)
class GeneratedViewQueryRunnerTest {
    private val context = RuntimeEnvironment.getApplication()
    private var nextId = 1L

    @Before
    fun clearState() {
        RoomTestReset.resetCarDatabaseSingleton()
    }

    @After
    fun drainRoomInvalidationTracker() {
        // See LedgerControllerOwnAccountMovementsTest's identical @After for why this is required.
        RoomTestReset.drainArchDiskIoPool()
    }

    // Real "now", not a fixed day-of-month - [GeneratedViewQueryRunner]'s pantry path caps its
    // window at the actual current instant, so a fixture dated later in the month than "today"
    // would fall outside its own query's range on an early-month test run.
    private val thisMonthMs = System.currentTimeMillis()

    private fun txn(
        amountCents: Long,
        description: String,
        ingestMethod: IngestMethod = IngestMethod.DETERMINISTIC,
        category: String? = "Shopping",
        txnDate: Long = thisMonthMs,
    ) = LedgerTransaction(
        sourceFile = "stmt.pdf",
        accountId = "acct-${nextId}",
        currency = LedgerCurrency.USD,
        txnDate = txnDate,
        description = description,
        amountCents = amountCents,
        lineRef = "line-${nextId++}",
        ingestMethod = ingestMethod,
        category = category,
    )

    private suspend fun seedLedger(vararg rows: LedgerTransaction) {
        CarDatabase.getDatabase(context).ledgerTransactionDao().insertAll(rows.toList())
    }

    private fun receipt(
        totalCents: Long,
        store: String = "Trader Joe's",
        unaccountedCents: Long? = null,
        purchaseDate: Long = thisMonthMs,
    ) = PantryReceipt(
        store = store,
        purchaseDate = purchaseDate,
        currency = LedgerCurrency.USD,
        totalCents = totalCents,
        sourceImagePath = "receipt-${nextId++}.jpg",
        unaccountedCents = unaccountedCents,
    )

    private suspend fun seedPantry(vararg rows: PantryReceipt) {
        val dao = CarDatabase.getDatabase(context).pantryReceiptDao()
        for (r in rows) dao.insert(r)
    }

    // ------------------------------------------------------------------------------- empty state

    @Test
    fun `a total with rows over an empty ledger renders empty, not a zero`() = runBlocking {
        val spec = GeneratedViewQuerySpec(
            shape = GeneratedViewShape.TOTAL_WITH_ROWS,
            source = QuerySource.LEDGER,
            aggregation = QueryAggregation.SUM,
            window = QueryWindow.THIS_MONTH,
            grouping = QueryGrouping.NONE,
            title = "Spend this month",
        )
        val result = GeneratedViewQueryRunner.run(context, spec) as GeneratedViewQueryRunner.RunResult.Rendered
        assertTrue(result.payload.isEmpty)
    }

    @Test
    fun `a real total is not empty`() = runBlocking {
        seedLedger(txn(-5_00, "COFFEE SHOP"))
        val spec = GeneratedViewQuerySpec(
            shape = GeneratedViewShape.TOTAL_WITH_ROWS,
            source = QuerySource.LEDGER,
            aggregation = QueryAggregation.SUM,
            window = QueryWindow.THIS_MONTH,
            grouping = QueryGrouping.NONE,
            title = "Spend this month",
        )
        val result = GeneratedViewQueryRunner.run(context, spec) as GeneratedViewQueryRunner.RunResult.Rendered
        assertTrue(!result.payload.isEmpty)
        assertEquals("USD 5.00", result.payload.totalLabel)
    }

    // --------------------------------------------------------------------------- exclusion rule

    @Test
    fun `an unreconciled ledger row never reaches the total, and its exclusion is named in the provenance text`() = runBlocking {
        seedLedger(
            txn(-10_00, "COFFEE SHOP", ingestMethod = IngestMethod.DETERMINISTIC),
            txn(-20_00, "MYSTERY CHARGE", ingestMethod = IngestMethod.UNRECONCILED),
        )
        val spec = GeneratedViewQuerySpec(
            shape = GeneratedViewShape.TOTAL_WITH_ROWS,
            source = QuerySource.LEDGER,
            aggregation = QueryAggregation.SUM,
            window = QueryWindow.THIS_MONTH,
            grouping = QueryGrouping.NONE,
            title = "Spend this month",
        )
        val result = GeneratedViewQueryRunner.run(context, spec) as GeneratedViewQueryRunner.RunResult.Rendered
        // Only the DETERMINISTIC row counted - the UNRECONCILED $20 never reaches the total.
        assertEquals("USD 10.00", result.payload.totalLabel)
        assertTrue(result.payload.rows.none { it.label == "MYSTERY CHARGE" })
        assertTrue(result.payload.provenanceText.contains("1 unreconciled"))
        assertTrue(result.payload.provenanceText.contains("USD 20.00"))
    }

    @Test
    fun `an unverified pantry receipt never reaches the total, and its exclusion is named in the provenance text`() = runBlocking {
        seedPantry(
            receipt(totalCents = 30_00, store = "Trader Joe's"),
            receipt(totalCents = 15_00, store = "Corner Store", unaccountedCents = 5_00),
        )
        val spec = GeneratedViewQuerySpec(
            shape = GeneratedViewShape.TOTAL_WITH_ROWS,
            source = QuerySource.PANTRY,
            aggregation = QueryAggregation.SUM,
            window = QueryWindow.THIS_MONTH,
            grouping = QueryGrouping.NONE,
            title = "Groceries this month",
        )
        val result = GeneratedViewQueryRunner.run(context, spec) as GeneratedViewQueryRunner.RunResult.Rendered
        assertEquals("USD 30.00", result.payload.totalLabel)
        assertTrue(result.payload.rows.none { it.label == "Corner Store" })
        assertTrue(result.payload.provenanceText.contains("1 unverified"))
    }

    // -------------------------------------------------------------------------------- refusals

    @Test
    fun `by-month grouping over a single-month window is a refusal, not an empty chart`() = runBlocking {
        val spec = GeneratedViewQuerySpec(
            shape = GeneratedViewShape.BAR_SERIES,
            source = QuerySource.LEDGER,
            aggregation = QueryAggregation.SUM,
            window = QueryWindow.THIS_MONTH,
            grouping = QueryGrouping.BY_MONTH,
            title = "Spend by month",
        )
        val result = GeneratedViewQueryRunner.run(context, spec)
        assertTrue(result is GeneratedViewQueryRunner.RunResult.Refusal)
    }

    @Test
    fun `pantry category grouping is a refusal - groceries have no category field`() = runBlocking {
        val spec = GeneratedViewQuerySpec(
            shape = GeneratedViewShape.TOTAL_WITH_ROWS,
            source = QuerySource.PANTRY,
            aggregation = QueryAggregation.SUM,
            window = QueryWindow.THIS_MONTH,
            grouping = QueryGrouping.BY_CATEGORY,
            title = "Groceries by category",
        )
        val result = GeneratedViewQueryRunner.run(context, spec)
        assertTrue(result is GeneratedViewQueryRunner.RunResult.Refusal)
    }

    @Test
    fun `a bar series with no grouping is a refusal, not an empty chart`() = runBlocking {
        val spec = GeneratedViewQuerySpec(
            shape = GeneratedViewShape.BAR_SERIES,
            source = QuerySource.LEDGER,
            aggregation = QueryAggregation.SUM,
            window = QueryWindow.LAST_3_MONTHS,
            grouping = QueryGrouping.NONE,
            title = "Spend",
        )
        val result = GeneratedViewQueryRunner.run(context, spec)
        assertTrue(result is GeneratedViewQueryRunner.RunResult.Refusal)
    }

    // ---------------------------------------------------------------------------- by-category

    @Test
    fun `category breakdown sums per category, excluding unreconciled rows`() = runBlocking {
        seedLedger(
            txn(-10_00, "WALMART", category = "Groceries"),
            txn(-5_00, "WALMART 2", category = "Groceries"),
            txn(-8_00, "GAS STATION", category = "Auto"),
            txn(-100_00, "MYSTERY", category = "Auto", ingestMethod = IngestMethod.UNRECONCILED),
        )
        val spec = GeneratedViewQuerySpec(
            shape = GeneratedViewShape.TOTAL_WITH_ROWS,
            source = QuerySource.LEDGER,
            aggregation = QueryAggregation.SUM,
            window = QueryWindow.THIS_MONTH,
            grouping = QueryGrouping.BY_CATEGORY,
            title = "This month by category",
        )
        val result = GeneratedViewQueryRunner.run(context, spec) as GeneratedViewQueryRunner.RunResult.Rendered
        val groceries = result.payload.rows.single { it.label == "Groceries" }
        val auto = result.payload.rows.single { it.label == "Auto" }
        assertEquals("USD 15.00", groceries.value)
        assertEquals("USD 8.00", auto.value)
        assertEquals("USD 23.00", result.payload.totalLabel)
        assertTrue(result.payload.provenanceText.contains("1 unreconciled"))
    }
}

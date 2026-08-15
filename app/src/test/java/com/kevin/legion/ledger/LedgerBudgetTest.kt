package com.kevin.legion.ledger

import com.kevin.legion.data.local.IngestMethod
import com.kevin.legion.data.local.LedgerCurrency
import com.kevin.legion.data.local.LedgerTransaction
import com.kevin.legion.plan.TrustTier
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises [buildBudgetVsActual], the direct successor to the deleted `buildProfitAndLoss`
 * (`.scratch/legion-shape/issues/06-budget-versus-actual.md`, D9-D13). Plain JUnit, no Room, no
 * Android - both [buildBudgetVsActual] and [analyzeTransfers] underneath it are pure by
 * construction. All fixtures are INVENTED, never Kevin's real rows.
 */
class LedgerBudgetTest {

    private var nextId = 1L

    private fun txn(
        accountId: String,
        amountCents: Long,
        currency: LedgerCurrency = LedgerCurrency.USD,
        txnDate: Long = MID_MONTH,
        description: String = "GENERIC",
        ingestMethod: IngestMethod = IngestMethod.DETERMINISTIC,
        category: String? = null,
        categoryPending: Boolean = false,
    ) = LedgerTransaction(
        id = nextId++,
        sourceFile = "statement.pdf",
        accountId = accountId,
        currency = currency,
        txnDate = txnDate,
        description = description,
        amountCents = amountCents,
        lineRef = "line",
        ingestMethod = ingestMethod,
        category = category,
        categoryPending = categoryPending,
    )

    private fun completeCoverage(vararg accountIds: String) = accountIds.map {
        AccountCoverage(it, coversWholeMonth = true, coveredFromMs = MONTH_START, coveredToMs = MONTH_END)
    }

    @Test
    fun `spend is summed per category, remaining is target minus spent - D10 plain subtraction`() {
        val groceries1 = txn("checking", -80_00, description = "KROGER", category = "Groceries")
        val groceries2 = txn("checking", -40_00, description = "KROGER", category = "Groceries")
        val dining = txn("checking", -25_00, description = "DINER", category = "Dining Out")

        val result = buildBudgetVsActual(
            entity = LedgerEntity.US,
            month = MONTH,
            inPeriod = listOf(groceries1, groceries2, dining),
            pairingWindow = listOf(groceries1, groceries2, dining),
            targets = mapOf("Groceries" to 150_00, "Dining Out" to 20_00),
            coverage = completeCoverage("checking"),
        )

        val groceriesLine = result.lines.first { it.category == "Groceries" }
        assertEquals(150_00L, groceriesLine.gap.target)
        assertEquals(120_00L, groceriesLine.gap.actual)
        assertEquals(30_00L, groceriesLine.gap.gap)

        val diningLine = result.lines.first { it.category == "Dining Out" }
        assertEquals(20_00L, diningLine.gap.target)
        assertEquals(25_00L, diningLine.gap.actual)
        // Over budget: gap is target - actual, which goes negative.
        assertEquals(-5_00L, diningLine.gap.gap)
    }

    @Test
    fun `income rows never enter any budget line - only expenses count against a budget`() {
        val payroll = txn("checking", 250_000, description = "PAYROLL", category = "Income")
        val groceries = txn("checking", -80_00, description = "KROGER", category = "Groceries")

        val result = buildBudgetVsActual(
            entity = LedgerEntity.US,
            month = MONTH,
            inPeriod = listOf(payroll, groceries),
            pairingWindow = listOf(payroll, groceries),
            targets = mapOf("Groceries" to 100_00, "Income" to 0L),
            coverage = completeCoverage("checking"),
        )

        // Income has a target but zero rows count against it, since payroll
        // is a credit, not an expense - D10 reads "spend" as money that left.
        val incomeLine = result.lines.first { it.category == "Income" }
        assertEquals(0L, incomeLine.gap.actual)
    }

    @Test
    fun `excluded transfers never inflate a category - reused analyzeTransfers exactly as the P&L used it`() {
        val groceries = txn("checking", -80_00, description = "KROGER", category = "Groceries")
        val transferOut = txn("checking", -1300_00, txnDate = MID_MONTH, description = "PAYMENT TO CARD", category = "Groceries")
        val transferIn = txn("card", 1300_00, txnDate = MID_MONTH + 2 * DAY_MS, description = "PAYMENT FROM CHK")

        val result = buildBudgetVsActual(
            entity = LedgerEntity.US,
            month = MONTH,
            inPeriod = listOf(groceries, transferOut, transferIn),
            pairingWindow = listOf(groceries, transferOut, transferIn),
            targets = mapOf("Groceries" to 100_00),
            coverage = completeCoverage("checking", "card"),
        )

        // The mislabelled transfer row (category = Groceries) is EXCLUDED by
        // analyzeTransfers before category summation ever sees it - a
        // category can't accidentally absorb $1,300 of transfer just
        // because a driver's rule happened to catch its description too.
        val groceriesLine = result.lines.first { it.category == "Groceries" }
        assertEquals(80_00L, groceriesLine.gap.actual)
    }

    @Test
    fun `D11 - uncategorised spend gets its own bucket, never folded into any category`() {
        val groceries = txn("checking", -80_00, description = "KROGER", category = "Groceries")
        val mystery = txn("checking", -34_12, description = "UNKNOWN MERCHANT", category = null)

        val result = buildBudgetVsActual(
            entity = LedgerEntity.US,
            month = MONTH,
            inPeriod = listOf(groceries, mystery),
            pairingWindow = listOf(groceries, mystery),
            targets = mapOf("Groceries" to 100_00),
            coverage = completeCoverage("checking"),
        )

        assertEquals(34_12L, result.uncategorized.spentCents)
        // Never spread across categories: Groceries only sees its own row.
        val groceriesLine = result.lines.first { it.category == "Groceries" }
        assertEquals(80_00L, groceriesLine.gap.actual)
        // Kevin 2026-08-15: spend is the category lines only - the uncategorised bucket sits
        // outside it (and every surface rendering `spentCents` says so in words, see
        // `uncategorizedExcludedSentence`).
        assertEquals(80_00L, result.spentCents)
        // Never silently dropped either: every operating-expense cent is still recoverable, which
        // is what keeps "excluded" from becoming "hidden".
        assertEquals(114_12L, result.allOperatingSpendCents)
    }

    @Test
    fun `spend excludes the uncategorised bucket even when every row this month is uncategorised`() {
        val mystery = txn("checking", -55_00, description = "UNKNOWN MERCHANT", category = null)

        val result = buildBudgetVsActual(
            entity = LedgerEntity.US,
            month = MONTH,
            inPeriod = listOf(mystery),
            pairingWindow = listOf(mystery),
            targets = emptyMap(),
            coverage = completeCoverage("checking"),
        )

        assertEquals(0L, result.spentCents)
        assertEquals(55_00L, result.allOperatingSpendCents)
        // ...and the exclusion is stated in words, with the figure in it - never colour or silence.
        val sentence = uncategorizedExcludedSentence(result.uncategorized, LedgerCurrency.USD)
        assertTrue(sentence.contains("55.00"))
        assertTrue(sentence.contains("NOT counted in spend"))
    }

    @Test
    fun `D11 - the uncategorised bucket is present even at zero, never conditionally omitted`() {
        val groceries = txn("checking", -80_00, description = "KROGER", category = "Groceries")

        val result = buildBudgetVsActual(
            entity = LedgerEntity.US,
            month = MONTH,
            inPeriod = listOf(groceries),
            pairingWindow = listOf(groceries),
            targets = mapOf("Groceries" to 100_00),
            coverage = completeCoverage("checking"),
        )

        assertEquals(0L, result.uncategorized.spentCents)
    }

    /**
     * Kevin's 2026-08-07 decision, item 2: an unpaired leg (the other statement not on file yet) is
     * still ordinary spend, not a silently dropped transfer guess. `analyzeTransfers`'s pass 2 flags
     * a wording-only match but no longer removes it from `operating` - see [LedgerTransfersTest]'s
     * case 7 for the pure-function assertion this exercises through the budget builder too.
     */
    @Test
    fun `an unpaired transfer-looking leg still counts as spend - the other statement isn't on file yet`() {
        val unpaired = txn(
            "card", -1300_00, description = "PAYMENT TO SAV 8267 CONF#v1ikbyqeg", category = "Groceries",
        )

        val result = buildBudgetVsActual(
            entity = LedgerEntity.US,
            month = MONTH,
            inPeriod = listOf(unpaired),
            pairingWindow = listOf(unpaired),
            targets = mapOf("Groceries" to 100_00),
            coverage = completeCoverage("card"),
        )

        // Not dropped - the description matches a transfer keyword but has no
        // confirming partner, so it stays real spend.
        assertEquals(1300_00L, result.lines.single { it.category == "Groceries" }.gap.actual)
    }

    @Test
    fun `a category with spend but no set budget gets its own zero-target line, not silence`() {
        val shopping = txn("checking", -42_00, description = "STORE", category = "Shopping")

        val result = buildBudgetVsActual(
            entity = LedgerEntity.US,
            month = MONTH,
            inPeriod = listOf(shopping),
            pairingWindow = listOf(shopping),
            targets = emptyMap(),
            coverage = completeCoverage("checking"),
        )

        val line = result.lines.single()
        assertEquals("Shopping", line.category)
        assertEquals(0L, line.gap.target)
        assertEquals(42_00L, line.gap.actual)
        assertEquals(-42_00L, line.gap.gap)
    }

    @Test
    fun `D12 - a category with an UNRECONCILED row sets hasProvisionalRows and its actual still counts it`() {
        val reconciled = txn("checking", -80_00, description = "KROGER", category = "Groceries", ingestMethod = IngestMethod.DETERMINISTIC)
        val provisional = txn("card", -30_00, description = "KROGER EXPRESS", category = "Groceries", ingestMethod = IngestMethod.UNRECONCILED)

        val allReconciled = buildBudgetVsActual(
            LedgerEntity.US, MONTH, listOf(reconciled), listOf(reconciled),
            mapOf("Groceries" to 100_00), completeCoverage("checking"),
        )
        val withProvisional = buildBudgetVsActual(
            LedgerEntity.US, MONTH, listOf(reconciled, provisional), listOf(reconciled, provisional),
            mapOf("Groceries" to 100_00), completeCoverage("checking", "card"),
        )

        assertFalse(allReconciled.lines.single().hasProvisionalRows)
        val provisionalLine = withProvisional.lines.single()
        assertTrue(provisionalLine.hasProvisionalRows)
        // §12: it counts toward spent, not just gets flagged.
        assertEquals(110_00L, provisionalLine.gap.actual)
    }

    @Test
    fun `D6 - one REPORTED row (UNRECONCILED) makes the whole gap REPORTED, no proportion`() {
        val nineProven = (1..9).map {
            txn("checking", -10_00, description = "KROGER $it", category = "Groceries", ingestMethod = IngestMethod.DETERMINISTIC)
        }
        val oneReported = txn("card", -10_00, description = "KROGER 10", category = "Groceries", ingestMethod = IngestMethod.UNRECONCILED)

        val result = buildBudgetVsActual(
            LedgerEntity.US, MONTH, nineProven + oneReported, nineProven + oneReported,
            mapOf("Groceries" to 200_00), completeCoverage("checking", "card"),
        )

        // 9 of 10 rows are PROVEN and only one is REPORTED - D6 forbids any
        // proportional reading. The combined tier is REPORTED, full stop.
        assertEquals(TrustTier.REPORTED, result.lines.single().gap.tier)
    }

    @Test
    fun `D6 counterpart - every row PROVEN and confirmed makes the gap PROVEN`() {
        val rows = (1..3).map {
            txn("checking", -10_00, description = "KROGER $it", category = "Groceries", ingestMethod = IngestMethod.DETERMINISTIC, categoryPending = false)
        }
        val result = buildBudgetVsActual(
            LedgerEntity.US, MONTH, rows, rows, mapOf("Groceries" to 100_00), completeCoverage("checking"),
        )
        assertEquals(TrustTier.PROVEN, result.lines.single().gap.tier)
    }

    @Test
    fun `an unconfirmed AI category guess makes the gap REPORTED even on a reconciled row`() {
        val guessed = txn(
            "checking", -80_00, description = "MYSTERY CAFE", category = "Dining Out",
            ingestMethod = IngestMethod.DETERMINISTIC, categoryPending = true,
        )

        val result = buildBudgetVsActual(
            LedgerEntity.US, MONTH, listOf(guessed), listOf(guessed),
            mapOf("Dining Out" to 100_00), completeCoverage("checking"),
        )

        val line = result.lines.single()
        assertEquals(TrustTier.REPORTED, line.gap.tier)
        assertTrue(line.hasPendingCategoryGuesses)
        assertFalse(line.hasProvisionalRows)
    }

    @Test
    fun `isComplete false when coverage is empty - an empty list must never read as complete`() {
        val result = buildBudgetVsActual(
            entity = LedgerEntity.US,
            month = MONTH,
            inPeriod = emptyList(),
            pairingWindow = emptyList(),
            targets = emptyMap(),
            coverage = emptyList(),
        )

        assertTrue(result.coverage.isEmpty())
        assertFalse(result.isComplete)
    }

    @Test
    fun `only the entity's own currency contributes - an SGD row in range never reaches a US budget`() {
        val usdExpense = txn("bofa-checking", -100_00, currency = LedgerCurrency.USD, category = "Groceries")
        val sgdExpense = txn("dbs-checking", -999_99, currency = LedgerCurrency.SGD, category = "Groceries")

        val result = buildBudgetVsActual(
            entity = LedgerEntity.US,
            month = MONTH,
            inPeriod = listOf(usdExpense, sgdExpense),
            pairingWindow = listOf(usdExpense, sgdExpense),
            targets = mapOf("Groceries" to 200_00),
            coverage = completeCoverage("bofa-checking"),
        )

        assertEquals(100_00L, result.lines.single { it.category == "Groceries" }.gap.actual)
    }

    @Test
    fun `a month with no transactions returns empty lines and zero uncategorised, isComplete false`() {
        val result = buildBudgetVsActual(
            entity = LedgerEntity.US,
            month = MONTH,
            inPeriod = emptyList(),
            pairingWindow = emptyList(),
            targets = emptyMap(),
            coverage = emptyList(),
        )

        assertTrue(result.lines.isEmpty())
        assertEquals(0L, result.uncategorized.spentCents)
        assertFalse(result.isComplete)
    }

    // ---- 2026-08-13: own-account movements leave spend, disclosed on BudgetVsActual -------------

    @Test
    fun `an own-account card payment is excluded from spend and shows up in the disclosure`() {
        val groceries = txn("checking", -80_00, description = "KROGER", category = "Groceries")
        val cardPayment = txn("checking", -1300_00, description = "PAYMENT TO CRD 7823", category = "Groceries")

        val result = buildBudgetVsActual(
            entity = LedgerEntity.US,
            month = MONTH,
            inPeriod = listOf(groceries, cardPayment),
            pairingWindow = listOf(groceries, cardPayment),
            targets = mapOf("Groceries" to 100_00),
            coverage = completeCoverage("checking"),
            ownAccountIds = setOf("4111111111117823"),
        )

        // The card payment never reaches the Groceries line...
        assertEquals(80_00L, result.lines.single { it.category == "Groceries" }.gap.actual)
        // ...and the disclosure says exactly what was pulled out and how much.
        assertEquals(1, result.excludedOwnAccountMovements.count)
        assertEquals(1300_00L, result.excludedOwnAccountMovements.totalCents)
        assertEquals(cardPayment.id, result.excludedOwnAccountMovements.rows.single().id)
    }

    @Test
    fun `a Zelle payment to a person still counts as spend even when ownAccountIds is non-empty`() {
        val zelle = txn("checking", -400_00, description = "Zelle payment to  R Alan Cole US Conf# b4nb0qacg", category = "Shopping")

        val result = buildBudgetVsActual(
            entity = LedgerEntity.US,
            month = MONTH,
            inPeriod = listOf(zelle),
            pairingWindow = listOf(zelle),
            targets = mapOf("Shopping" to 1000_00),
            coverage = completeCoverage("checking"),
            ownAccountIds = setOf("4111111111117823", "4111111115042"),
        )

        assertEquals(400_00L, result.lines.single().gap.actual)
        assertTrue("a Zelle payment to a person must never be disclosed as an own-account movement", result.excludedOwnAccountMovements.isEmpty)
    }

    @Test
    fun `omitting ownAccountIds leaves the disclosure empty - the pre-2026-08-13 default`() {
        val cardPayment = txn("checking", -1300_00, description = "PAYMENT TO CRD 7823", category = "Groceries")

        val result = buildBudgetVsActual(
            entity = LedgerEntity.US,
            month = MONTH,
            inPeriod = listOf(cardPayment),
            pairingWindow = listOf(cardPayment),
            targets = mapOf("Groceries" to 100_00),
            coverage = completeCoverage("checking"),
        )

        assertTrue(result.excludedOwnAccountMovements.isEmpty)
        // Falls back to the pre-existing SUSPECTED_TRANSFER behaviour: still counted as spend.
        assertEquals(1300_00L, result.lines.single().gap.actual)
    }

    @Test
    fun `excludedOwnAccountMovementsSentence discloses count and amount in words, and states zero honestly`() {
        val nonEmpty = ExcludedOwnAccountMovements(count = 2, totalCents = 1_00_000L, rows = emptyList())
        val sentence = excludedOwnAccountMovementsSentence(nonEmpty, LedgerCurrency.USD)
        assertTrue(sentence.contains("2 transactions"))
        assertTrue(sentence.contains("excluded from spend"))

        val singular = excludedOwnAccountMovementsSentence(
            ExcludedOwnAccountMovements(count = 1, totalCents = 500L, rows = emptyList()), LedgerCurrency.USD,
        )
        assertTrue(singular.contains("1 transaction "))
        assertFalse(singular.contains("1 transactions"))

        val empty = excludedOwnAccountMovementsSentence(ExcludedOwnAccountMovements(0, 0L, emptyList()), LedgerCurrency.USD)
        assertTrue(empty.contains("No own-account movements"))
    }

    // ---- ticket 04 (quant-viz): monthSpendFrom, the pure per-month rule monthlySpendTrend delegates to ----

    @Test
    fun `interior month with no coverage and no rows is a gap - monthSpendFrom returns null`() {
        val emptyBudget = buildBudgetVsActual(
            entity = LedgerEntity.US,
            month = MONTH,
            inPeriod = emptyList(),
            pairingWindow = emptyList(),
            targets = emptyMap(),
            coverage = emptyList(),
        )

        assertEquals(null, monthSpendFrom(MONTH, emptyBudget))
    }

    @Test
    fun `covered but empty month is a genuine zero, never omitted as a gap`() {
        val coveredEmptyBudget = buildBudgetVsActual(
            entity = LedgerEntity.US,
            month = MONTH,
            inPeriod = emptyList(),
            pairingWindow = emptyList(),
            targets = emptyMap(),
            coverage = completeCoverage("checking"),
        )

        val spend = monthSpendFrom(MONTH, coveredEmptyBudget)
        assertEquals(MonthSpend(MONTH, 0L, isComplete = true, hasProvisionalRows = false), spend)
    }

    @Test
    fun `totalCents is the exact Long sum of every category line's actual, uncategorised excluded`() {
        val groceries = txn("checking", -184_212, description = "KROGER", category = "Groceries")
        val mystery = txn("checking", -55_00, description = "UNKNOWN MERCHANT", category = null)

        val budget = buildBudgetVsActual(
            entity = LedgerEntity.US,
            month = MONTH,
            inPeriod = listOf(groceries, mystery),
            pairingWindow = listOf(groceries, mystery),
            targets = mapOf("Groceries" to 200_000L),
            coverage = completeCoverage("checking"),
        )

        val spend = monthSpendFrom(MONTH, budget)
        assertEquals(184_212L, spend!!.totalCents)
    }

    @Test
    fun `a month whose only rows are uncategorised is a real zero-spend month, never omitted as a gap`() {
        // Zero SPEND, but emphatically not "nothing was ever imported for this month" - the trend
        // must still carry the month rather than leaving a hole that reads as missing data.
        val mystery = txn("checking", -55_00, description = "UNKNOWN MERCHANT", category = null)
        val budget = buildBudgetVsActual(
            entity = LedgerEntity.US,
            month = MONTH,
            inPeriod = listOf(mystery),
            pairingWindow = listOf(mystery),
            targets = emptyMap(),
            coverage = emptyList(),
        )

        val spend = monthSpendFrom(MONTH, budget)
        assertEquals(0L, spend!!.totalCents)
    }

    @Test
    fun `isComplete false propagates from partial coverage into MonthSpend`() {
        val row = txn("checking", -10_00, description = "STORE", category = "Shopping")
        val partial = listOf(
            AccountCoverage("checking", coversWholeMonth = false, coveredFromMs = MONTH_START, coveredToMs = MONTH_START + 10 * DAY_MS),
        )
        val budget = buildBudgetVsActual(
            entity = LedgerEntity.US,
            month = MONTH,
            inPeriod = listOf(row),
            pairingWindow = listOf(row),
            targets = emptyMap(),
            coverage = partial,
        )

        val spend = monthSpendFrom(MONTH, budget)
        assertFalse(spend!!.isComplete)
    }

    @Test
    fun `hasProvisionalRows is true when either a category line or the uncategorised bucket carries one`() {
        val provisionalCategorised = txn(
            "card", -30_00, description = "KROGER EXPRESS", category = "Groceries", ingestMethod = IngestMethod.UNRECONCILED,
        )
        val budgetCategorised = buildBudgetVsActual(
            entity = LedgerEntity.US, month = MONTH,
            inPeriod = listOf(provisionalCategorised), pairingWindow = listOf(provisionalCategorised),
            targets = mapOf("Groceries" to 100_00), coverage = completeCoverage("card"),
        )
        assertTrue(monthSpendFrom(MONTH, budgetCategorised)!!.hasProvisionalRows)

        val provisionalUncategorised = txn(
            "card", -30_00, description = "UNKNOWN", category = null, ingestMethod = IngestMethod.UNRECONCILED,
        )
        val budgetUncategorised = buildBudgetVsActual(
            entity = LedgerEntity.US, month = MONTH,
            inPeriod = listOf(provisionalUncategorised), pairingWindow = listOf(provisionalUncategorised),
            targets = emptyMap(), coverage = completeCoverage("card"),
        )
        assertTrue(monthSpendFrom(MONTH, budgetUncategorised)!!.hasProvisionalRows)

        val reconciled = txn("checking", -10_00, description = "STORE", category = "Shopping")
        val budgetReconciled = buildBudgetVsActual(
            entity = LedgerEntity.US, month = MONTH,
            inPeriod = listOf(reconciled), pairingWindow = listOf(reconciled),
            targets = emptyMap(), coverage = completeCoverage("checking"),
        )
        assertFalse(monthSpendFrom(MONTH, budgetReconciled)!!.hasProvisionalRows)
    }

    companion object {
        private val MONTH: YearMonth = YearMonth.of(2026, 7)
        private const val MONTH_START = 1_751_328_000_000L
        private const val DAY_MS = 24L * 60 * 60 * 1000
        private const val MID_MONTH = MONTH_START + 15 * DAY_MS
        private const val MONTH_END = MONTH_START + 30 * DAY_MS
    }
}

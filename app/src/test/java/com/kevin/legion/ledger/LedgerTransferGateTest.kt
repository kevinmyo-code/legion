package com.kevin.legion.ledger

import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.IngestMethod
import com.kevin.legion.data.local.LedgerCurrency
import com.kevin.legion.data.local.LedgerTransaction
import com.kevin.legion.engine.RecordStore
import com.kevin.legion.engine.ledger.LedgerAspectSeeder
import com.kevin.legion.engine.ledger.LedgerRecordBridge
import com.kevin.legion.testutil.RoomTestReset
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * [LedgerController.uncategorizedMerchants]'s transfer gate (`.scratch/car-probe-transfers/`,
 * 2026-08-13). Before this fix, [analyzeTransfers] correctly flagged a transfer row but had no
 * connection at all to the merchant-guessing pipeline - this pins that a
 * [ExclusionReason.MATCHED_TRANSFER] or [ExclusionReason.SUSPECTED_TRANSFER] row can never reach
 * [CategoryAgent] through this function, end to end against a real Room database (matching
 * [LedgerAddCategoryTest]'s posture: no ContentResolver involved, so Robolectric's
 * `ShadowContentResolver` mismatch that keeps [LedgerController]'s import/dedup paths untested
 * (README/CLAUDE.md §10) does not apply here).
 */
@RunWith(RobolectricTestRunner::class)
class LedgerTransferGateTest {
    private val context = RuntimeEnvironment.getApplication()
    private var nextId = 1L

    @Before
    fun clearState() {
        RoomTestReset.resetCarDatabaseSingleton()
    }

    private fun txn(
        accountId: String,
        amountCents: Long,
        description: String,
        txnDate: Long = 1_000L,
    ) = LedgerTransaction(
        sourceFile = "stmt.pdf",
        accountId = accountId,
        currency = LedgerCurrency.USD,
        txnDate = txnDate,
        description = description,
        amountCents = amountCents,
        lineRef = "line-${nextId++}",
        ingestMethod = IngestMethod.DETERMINISTIC,
    )

    /** Cutover 3: [LedgerController] reads through the engine now - see
     * [com.kevin.legion.advisor.digest.CredDigestBuilderTest]'s identical helper for the reasoning. */
    private suspend fun insertEngineTransactions(vararg transactions: LedgerTransaction) {
        val db = CarDatabase.getDatabase(context)
        val schema = LedgerAspectSeeder.ensureSeeded(context)
        val recordStore = RecordStore(db.engineRecordDao(), db.fieldDefDao(), db.recordTypeDao())
        for (t in transactions) {
            recordStore.create(
                recordTypeId = schema.transaction.recordTypeId,
                fieldValues = LedgerRecordBridge.fieldValuesFor(t, schema.transaction.fieldIds),
                provenance = LedgerRecordBridge.provenanceFor(t.ingestMethod),
                now = t.txnDate,
                guid = t.syncId,
            )
        }
    }

    /** [dao.uncategorizedTransactions()]'s engine-backed successor - every row from BOTH halves of
     * [LedgerController.uncategorizedTransactionsSplit], which together are the whole `category IS
     * NULL` pool. */
    private suspend fun uncategorizedRowCount(): Int {
        val split = LedgerController.uncategorizedTransactionsSplit(context)
        return split.real.size + split.transfers.size
    }

    @After
    fun drainRoomInvalidationTracker() {
        // A DAO write anywhere in this test can schedule a Room InvalidationTracker refresh
        // on ArchTaskExecutor's disk-IO pool. If that refresh is still queued or running when
        // this test method returns, it races Robolectric's own per-@Test-METHOD native SQLite
        // reset and throws "Illegal connection pointer" on a background thread - uncaught, and
        // blamed by kotlinx-coroutines-test on whatever runTest starts next, not on this class.
        // Draining here, before Robolectric ever gets a chance to reset, is the fix - see
        // RoomTestReset's own class doc comment
        // (.scratch/hardening/issues/13-the-suite-is-green-by-luck.md) for the full account.
        RoomTestReset.drainArchDiskIoPool()
    }


    @Test
    fun `a matched transfer pair never reaches the merchant pool`() = runBlocking {
        insertEngineTransactions(
            txn("checking", 130_000, "PAYMENT TO CRD", txnDate = 1_000L),
            txn("card", -130_000, "PAYMENT FROM CHK", txnDate = 1_000L + 2 * 24L * 60 * 60 * 1000),
            txn("checking", -5_000, "KROGER #115 CYPRESS TX"),
        )

        val pool = LedgerController.uncategorizedMerchants(context)

        assertEquals(listOf("KROGER"), pool.keys)
        assertEquals(2, pool.transfersSkipped)
    }

    @Test
    fun `a suspected transfer with no matching leg is also kept out of the pool`() = runBlocking {
        insertEngineTransactions(
            // No opposite-account, opposite-amount partner exists anywhere - pass 1 cannot match
            // this, so only the keyword fallback (pass 2, SUSPECTED_TRANSFER) can flag it.
            txn("checking", -3_115_676, "MOBILE BANKING PAYMENT TO CRD"),
            txn("checking", -4_599, "WALMART SUPERCENTER"),
        )

        val pool = LedgerController.uncategorizedMerchants(context)

        assertEquals(listOf("WALMART SUPERCENTER"), pool.keys)
        assertEquals(1, pool.transfersSkipped)
    }

    @Test
    fun `no transfer-shaped rows means transfersSkipped is zero and nothing is filtered`() = runBlocking {
        insertEngineTransactions(
            txn("checking", -4_599, "WALMART SUPERCENTER"),
            txn("checking", -1_200, "PANDA EXPRESS HOUSTON TX"),
        )

        val pool = LedgerController.uncategorizedMerchants(context)

        assertEquals(0, pool.transfersSkipped)
        assertTrue("WALMART SUPERCENTER" in pool.keys)
        assertTrue("PANDA EXPRESS HOUSTON TX" in pool.keys)
    }

    @Test
    fun `a transfer row's partner already carrying a category still excludes both legs`() = runBlocking {
        // The partner (the card's own +130000 leg) is already categorised - proves the pairing
        // window must include ALL rows, not only the still-uncategorised ones, or this leg's
        // partner would be invisible to analyzeTransfers and the pair would never be found.
        insertEngineTransactions(
            txn("checking", -130_000, "PAYMENT TO CRD", txnDate = 1_000L).copy(category = null),
            txn("card", 130_000, "PAYMENT FROM CHK", txnDate = 1_000L).copy(category = "Income", categoryPending = false),
            txn("checking", -5_000, "KROGER #115 CYPRESS TX"),
        )

        val pool = LedgerController.uncategorizedMerchants(context)

        assertEquals(listOf("KROGER"), pool.keys)
        assertEquals(1, pool.transfersSkipped)
    }

    @Test
    fun `an empty uncategorised pool returns zero merchants and zero transfers skipped`() = runBlocking {
        val pool = LedgerController.uncategorizedMerchants(context)
        assertEquals(emptyList<String>(), pool.keys)
        assertEquals(0, pool.transfersSkipped)
        assertFalse(pool.keys.isNotEmpty())
    }

    // ---- Rule-creation refusal: `set_category`/the drill-down MOVE panel must never install a
    // CategoryRule on a transfer-shaped key, end to end (isBankNoiseKey's own doc comment explains
    // WHY this is the same gate as the bank-noise-prefix refusal; this pins that LedgerController's
    // two call sites actually honour it, not just the pure function in isolation).

    @Test
    fun `setCategory refuses to write a rule for a transfer-shaped key and touches nothing`() = runBlocking {
        insertEngineTransactions(txn("checking", -130_000, "MOBILE BANKING PAYMENT TO CRD"))

        val result = LedgerController.setCategory(context, "PAYMENT TO CRD", "Subscriptions")

        assertTrue(result.isNoiseKey)
        assertEquals(0, result.rowsTouched)
        val rules = CarDatabase.getDatabase(context).categoryRuleDao().getAll()
        assertTrue("expected no CategoryRule written for a transfer-shaped key", rules.isEmpty())
        // The row itself must also be untouched - a refused correction is a true no-op, not a
        // partial one.
        assertEquals(1, uncategorizedRowCount())
    }

    @Test
    fun `previewRecategorizeCount reports zero for a transfer-shaped key even though rows would match`() = runBlocking {
        insertEngineTransactions(txn("checking", -130_000, "MOBILE BANKING PAYMENT TO CRD"))

        // A real row DOES contain this substring - proves the zero comes from the refusal, not
        // from the key genuinely matching nothing.
        assertEquals(0, LedgerController.previewRecategorizeCount(context, "PAYMENT TO CRD"))
    }

    // ---- uncategorizedTransactionsSplit (2026-08-18 fix): the row-level split behind
    // uncategorizedMerchants, feeding the CATEGORIZE screen's new UNCATEGORISED/TRANSFERS sections.

    @Test
    fun `uncategorizedTransactionsSplit puts real merchants in real and transfer-shaped rows in transfers`() = runBlocking {
        insertEngineTransactions(
            txn("checking", 130_000, "PAYMENT TO CRD", txnDate = 1_000L),
            txn("card", -130_000, "PAYMENT FROM CHK", txnDate = 1_000L + 2 * 24L * 60 * 60 * 1000),
            txn("checking", -5_000, "KROGER #115 CYPRESS TX"),
            txn("checking", -4_599, "WALMART SUPERCENTER"),
        )

        val split = LedgerController.uncategorizedTransactionsSplit(context)

        assertEquals(2, split.real.size)
        assertTrue(split.real.any { it.description == "KROGER #115 CYPRESS TX" })
        assertTrue(split.real.any { it.description == "WALMART SUPERCENTER" })
        assertEquals(2, split.transfers.size)
        // Every row in `real` and every row in `transfers` must, between them, account for the
        // WHOLE uncategorised pool - the exact "count the driver sees must match what the list
        // shows" requirement (CLAUDE.md §4 rule 6's principle applied to this UI count).
        assertEquals(uncategorizedRowCount(), split.real.size + split.transfers.size)
    }

    @Test
    fun `uncategorizedTransactionsSplit is empty when nothing is uncategorised`() = runBlocking {
        val split = LedgerController.uncategorizedTransactionsSplit(context)
        assertTrue(split.real.isEmpty())
        assertTrue(split.transfers.isEmpty())
    }
}

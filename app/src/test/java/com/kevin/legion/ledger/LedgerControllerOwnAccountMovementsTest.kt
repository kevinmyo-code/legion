package com.kevin.legion.ledger

import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.IngestMethod
import com.kevin.legion.data.local.LedgerCurrency
import com.kevin.legion.data.local.LedgerTransaction
import com.kevin.legion.engine.RecordStore
import com.kevin.legion.engine.ledger.LedgerAspectSeeder
import com.kevin.legion.engine.ledger.LedgerRecordBridge
import com.kevin.legion.testutil.RoomTestReset
import java.time.YearMonth
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
 * [LedgerController.budgetVsActual]'s end-to-end threading of `ownAccountIds` (2026-08-13) - the
 * DAO's real [com.kevin.legion.data.local.LedgerTransactionDao.accountIdsForCurrency] read, not the
 * hand-built sets [LedgerBudgetTest]'s pure fixtures pass directly to [buildBudgetVsActual]. Matches
 * [LedgerTransferGateTest]'s posture: Room, Robolectric, no ContentResolver involved.
 */
@RunWith(RobolectricTestRunner::class)
class LedgerControllerOwnAccountMovementsTest {
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
        category: String? = null,
    ) = LedgerTransaction(
        sourceFile = "stmt.pdf",
        accountId = accountId,
        currency = LedgerCurrency.USD,
        txnDate = MONTH_START + 10 * DAY_MS,
        description = description,
        amountCents = amountCents,
        lineRef = "line-${nextId++}",
        ingestMethod = IngestMethod.DETERMINISTIC,
        category = category,
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
    fun `a card payment naming an account actually on file is excluded from spend and disclosed`() = runBlocking {
        insertEngineTransactions(
            // The card's own account, so `accountIdsForCurrency(USD)` will contain it.
            txn("4111111111117823", -5_000, "WALMART SUPERCENTER", category = "Shopping"),
            txn("4111111115042", -1300_00, "Online Banking payment to CRD 7823 Confirmation# 0649409616", category = "Shopping"),
        )

        val result = LedgerController.budgetVsActual(context, LedgerEntity.US, MONTH)

        assertEquals(50_00L, result.lines.single { it.category == "Shopping" }.gap.actual)
        assertEquals(1, result.excludedOwnAccountMovements.count)
        assertEquals(1300_00L, result.excludedOwnAccountMovements.totalCents)
    }

    @Test
    fun `a Zelle payment to a person is never excluded, even against a real ownAccountIds set`() = runBlocking {
        insertEngineTransactions(
            txn("4111111111117823", -5_000, "WALMART SUPERCENTER", category = "Shopping"),
            txn("4111111115042", -40_000, "Zelle payment to  R Alan Cole US Conf# b4nb0qacg", category = "Shopping"),
        )

        val result = LedgerController.budgetVsActual(context, LedgerEntity.US, MONTH)

        // Both rows are real spend - the Zelle payment is real money leaving to a person, not a
        // transfer, and it must count.
        assertEquals(450_00L, result.lines.single { it.category == "Shopping" }.gap.actual)
        assertTrue(result.excludedOwnAccountMovements.isEmpty)
    }

    @Test
    fun `categoryTransactions reads the same own-account exclusion the budget total used`() = runBlocking {
        insertEngineTransactions(
            txn("4111111111117823", -5_000, "WALMART SUPERCENTER", category = "Shopping"),
            txn("4111111115042", -1300_00, "Online Banking payment to CRD 7823 Confirmation# 0649409616", category = "Shopping"),
        )

        val rows = LedgerController.categoryTransactions(context, LedgerEntity.US, MONTH, "Shopping")

        assertEquals(listOf("WALMART SUPERCENTER"), rows.map { it.description })
    }

    companion object {
        private val MONTH: YearMonth = YearMonth.now()
        private const val DAY_MS = 24L * 60 * 60 * 1000
        private val MONTH_START = MONTH.atDay(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
    }
}

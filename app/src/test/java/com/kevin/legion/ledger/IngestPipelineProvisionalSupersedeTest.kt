package com.kevin.legion.ledger

import com.kevin.legion.data.local.IngestMethod
import com.kevin.legion.data.local.LedgerCurrency
import com.kevin.legion.data.local.LedgerTransaction
import com.kevin.legion.testutil.RoomTestReset
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import com.kevin.legion.data.local.CarDatabase

/**
 * Ticket 12 §5 - the supersede wiring is a transaction-ordering property
 * ([IngestPipeline.commit]'s three load-bearing properties documented at its
 * call site), which needs a real Room database to observe at all.
 *
 * **Live again as of engine retirement step 5** (`.scratch/backend-erp/issues/15-engine-retirement-sequence.md`).
 * Cutover 3 (2026-08-24) had made `LedgerTransactionDao.deleteSupersededProvisional` dead code (zero
 * production callers) and this file's four cases exercised only the engine mirror instead - see the
 * git history for that intermediate shape. Step 5 repointed [IngestPipeline.commit] back onto that
 * exact DAO method, so this file now asserts against `ledger_transactions` directly again, the
 * table production actually writes.
 *
 * The account-id choice below preserves the old file's own reasoning, restated
 * for the engine path: the reconciled row's supersede match against the
 * provisional row runs through [sameCard] (a suffix match, exactly as ticket
 * 12 §0 requires for a real CSV-last-4 vs PDF-full-PAN pair), but the DEDUP
 * read directly below it (`IngestPipeline.kt`'s `existingRows` filter) compares
 * `accountId` for EXACT string equality, not a suffix relation. Test 17
 * (`reconciledRowsAreNotDroppedAsDuplicatesOfTheProvisionalRowsTheyReplace`)
 * gives the provisional and reconciled rows the SAME `accountId` string
 * deliberately - real BofA card data never does this, since the CSV's last-4
 * and the PDF's full printed account are different strings - because an exact
 * match is what makes the false-duplicate failure mode (the verified row
 * dropped in favour of the unverified one it was meant to replace) actually
 * reachable to assert against, rather than testing a bug that provably cannot
 * occur given how two real accountIds happen to differ. The other three tests
 * use a suffix-matching pair (`"7823"` / `"...7823"`), matching the shape real
 * data takes, since they are not trying to reach that specific failure mode.
 */
@RunWith(RobolectricTestRunner::class)
class IngestPipelineProvisionalSupersedeTest {

    private val context = RuntimeEnvironment.getApplication()
    private val db get() = CarDatabase.getDatabase(context)

    @Before
    fun clearState() {
        RoomTestReset.resetCarDatabaseSingleton()
    }

    private fun provisionalTxn(txnDate: Long, description: String, amountCents: Long, accountId: String = "7823") =
        LedgerTransaction(
            sourceFile = "currentTransaction_$accountId.csv",
            accountId = accountId,
            currency = LedgerCurrency.USD,
            txnDate = txnDate,
            description = description,
            amountCents = amountCents,
            balanceCents = null,
            lineRef = "csv:$description",
            ingestMethod = IngestMethod.UNRECONCILED,
        )

    private fun reconciledTxn(txnDate: Long, description: String, amountCents: Long, accountId: String) =
        LedgerTransaction(
            sourceFile = "eStmt_card.pdf",
            accountId = accountId,
            currency = LedgerCurrency.USD,
            txnDate = txnDate,
            description = description,
            amountCents = amountCents,
            balanceCents = null,
            lineRef = "pdf:$description",
            ingestMethod = IngestMethod.DETERMINISTIC,
        )

    private fun staged() = IngestPipeline.StageOutcome.Staged(
        bytes = ByteArray(0), isReplace = false,
        previousAccountId = null, previousMinTxnDate = null, previousMaxTxnDate = null,
    )

    private suspend fun activeRowsFor(accountId: String, minDate: Long, maxDate: Long): List<Pair<String, IngestMethod>> =
        db.ledgerTransactionDao().getAll()
            .filter { it.accountId == accountId && it.txnDate in minDate..maxDate }
            .map { it.lineRef to it.ingestMethod }

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


    /** Test 16: commit provisional rows, then a reconciled statement covering the same window -> provisional gone, reconciled present, count correct. */
    @Test
    fun reconciledStatementSupersedesProvisionalRowsInItsWindow() = runBlocking {
        IngestPipeline.commit(
            context, "card-csv-1", null, "currentTransaction.csv", staged(),
            LedgerIngestResult.Success(
                listOf(
                    provisionalTxn(JUL_1, "NORTHWIND OUTFITTERS", -4500L),
                    provisionalTxn(JUL_10, "GREENFIELD MARKET", -1200L),
                ),
            ),
        )
        assertEquals(2, activeRowsFor("7823", JUL_1, JUL_10).size)

        val outcome = IngestPipeline.commit(
            context, "card-pdf-1", null, "eStmt_card.pdf", staged(),
            LedgerIngestResult.Success(
                listOf(
                    reconciledTxn(JUL_1, "NORTHWIND OUTFITTERS.COM", -4500L, "5555555555557823"),
                    reconciledTxn(JUL_10, "GREENFIELD MARKET #2", -1200L, "5555555555557823"),
                ),
            ),
        )

        val ingested = outcome as IngestPipeline.CommitOutcome.Ingested
        assertEquals(2, ingested.provisionalSuperseded)
        assertEquals(2, ingested.transactionCount)
        assertTrue("provisional rows must be gone", activeRowsFor("7823", JUL_1, JUL_10).isEmpty())
        assertEquals(2, activeRowsFor("5555555555557823", JUL_1, JUL_10).size)
    }

    /** Test 17: the reconciled statement's own rows are NOT dropped as duplicates of the provisional rows they replaced (the §5 ordering bug, asserted directly). */
    @Test
    fun reconciledRowsAreNotDroppedAsDuplicatesOfTheProvisionalRowsTheyReplace() = runBlocking {
        // Same accountId string deliberately - see this class's doc comment
        // for why that's what makes the failure mode reachable at all.
        IngestPipeline.commit(
            context, "card-csv-1", null, "currentTransaction.csv", staged(),
            LedgerIngestResult.Success(listOf(provisionalTxn(JUL_1, "SAME DESCRIPTION", -4500L, accountId = "SAMEACCT"))),
        )

        val outcome = IngestPipeline.commit(
            context, "card-pdf-1", null, "eStmt_card.pdf", staged(),
            LedgerIngestResult.Success(listOf(reconciledTxn(JUL_1, "SAME DESCRIPTION", -4500L, "SAMEACCT"))),
        )

        // Without the supersede running BEFORE the dedup read, this exact-key
        // match would have been read as "already exists" and dropped - the
        // verified row lost in favour of the unverified one it was meant to
        // replace.
        val ingested = outcome as IngestPipeline.CommitOutcome.Ingested
        assertEquals(1, ingested.provisionalSuperseded)
        assertEquals(1, ingested.transactionCount)
        assertEquals(0, ingested.duplicatesSkipped)
        val rows = activeRowsFor("SAMEACCT", JUL_1, JUL_1)
        assertEquals(1, rows.size)
        assertEquals(IngestMethod.DETERMINISTIC, rows.single().second)
    }

    /** Test 18: a reconciled statement covering a DIFFERENT window leaves provisional rows alone. */
    @Test
    fun aReconciledStatementForADifferentWindowLeavesProvisionalRowsAlone() = runBlocking {
        IngestPipeline.commit(
            context, "card-csv-1", null, "currentTransaction.csv", staged(),
            LedgerIngestResult.Success(listOf(provisionalTxn(JUL_1, "NORTHWIND OUTFITTERS", -4500L))),
        )

        // A card PDF for an entirely earlier cycle - doesn't touch July at all.
        val outcome = IngestPipeline.commit(
            context, "card-pdf-june", null, "eStmt_card_june.pdf", staged(),
            LedgerIngestResult.Success(listOf(reconciledTxn(JUN_5, "SOME JUNE PURCHASE", -900L, "5555555555557823"))),
        )

        val ingested = outcome as IngestPipeline.CommitOutcome.Ingested
        assertEquals(0, ingested.provisionalSuperseded)
        assertEquals(1, activeRowsFor("7823", JUL_1, JUL_1).size)
    }

    /** Test 19: importing the card CSV twice does not delete-then-reinsert (the §5 guard). */
    @Test
    fun importingTheProvisionalFileTwiceNeverSupersedesItself() = runBlocking {
        IngestPipeline.commit(
            context, "card-csv-1", null, "currentTransaction.csv", staged(),
            LedgerIngestResult.Success(listOf(provisionalTxn(JUL_1, "NORTHWIND OUTFITTERS", -4500L))),
        )

        // Re-importing the same provisional file (e.g. a re-scan before its
        // window has anything reconciled yet) must never trip the supersede
        // guard against its own prior rows.
        val outcome = IngestPipeline.commit(
            context, "card-csv-1", null, "currentTransaction.csv", staged(),
            LedgerIngestResult.Success(listOf(provisionalTxn(JUL_1, "NORTHWIND OUTFITTERS", -4500L))),
        )

        val ingested = outcome as IngestPipeline.CommitOutcome.Ingested
        assertEquals(0, ingested.provisionalSuperseded)
        // Ordinary exact-match dedup still applies - the identical row is
        // recognized as already on file, not duplicated.
        assertEquals(0, ingested.transactionCount)
        assertEquals(1, ingested.duplicatesSkipped)
        assertEquals(1, activeRowsFor("7823", JUL_1, JUL_1).size)
    }

    companion object {
        private const val DAY_MS = 24L * 60 * 60 * 1000
        private const val JUN_5 = 1_749_081_600_000L
        private const val JUL_1 = JUN_5 + 26 * DAY_MS
        private const val JUL_10 = JUL_1 + 9 * DAY_MS
    }
}

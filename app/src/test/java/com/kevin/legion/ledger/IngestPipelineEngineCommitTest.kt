package com.kevin.legion.ledger

import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.IngestMethod
import com.kevin.legion.data.local.IngestState
import com.kevin.legion.data.local.LedgerCurrency
import com.kevin.legion.data.local.LedgerTransaction
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
 * Engine retirement step 5 (`.scratch/backend-erp/issues/15-engine-retirement-sequence.md`):
 * [IngestPipeline.commit]'s write path is legacy-table-backed again, exercised directly with
 * Robolectric (Room, no ContentResolver - same posture as [LedgerTransferGateTest]/
 * [LedgerControllerOwnAccountMovementsTest]). Renamed in spirit but not in filename from its
 * cutover-3 predecessor, which asserted against `db.engineRecordDao()`; every assertion below reads
 * `db.ledgerTransactionDao()` instead, and the provenance-mapping cases now check
 * [LedgerTransaction.ingestMethod] directly rather than round-tripping through
 * [com.kevin.legion.data.local.RecordProvenance].
 */
@RunWith(RobolectricTestRunner::class)
class IngestPipelineEngineCommitTest {
    private val context = RuntimeEnvironment.getApplication()
    private val db get() = CarDatabase.getDatabase(context)

    @Before
    fun clearState() {
        RoomTestReset.resetCarDatabaseSingleton()
    }

    private fun txn(
        accountId: String = "BOFA-1234",
        currency: LedgerCurrency = LedgerCurrency.USD,
        txnDate: Long = 1_000_000L,
        description: String = "TEST MERCHANT",
        amountCents: Long = -1_000L,
        lineRef: String = "line-1",
        ingestMethod: IngestMethod = IngestMethod.DETERMINISTIC,
        id: Long = 0L,
    ) = LedgerTransaction(
        id = id, sourceFile = "stmt.pdf", accountId = accountId, currency = currency, txnDate = txnDate,
        description = description, amountCents = amountCents, lineRef = lineRef, ingestMethod = ingestMethod,
    )

    private fun staged(isReplace: Boolean = false, previousAccountId: String? = null, previousMin: Long? = null, previousMax: Long? = null) =
        IngestPipeline.StageOutcome.Staged(
            bytes = ByteArray(0), isReplace = isReplace,
            previousAccountId = previousAccountId, previousMinTxnDate = previousMin, previousMaxTxnDate = previousMax,
        )

    private suspend fun transactionCount(): Int = db.ledgerTransactionDao().getAll().size

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


    // --------------------------------------------------------------------------- provenance mapping

    @Test
    fun `a DETERMINISTIC commit lands a DETERMINISTIC row`() = runBlocking {
        val outcome = IngestPipeline.commit(
            context, "file-det", null, "stmt.pdf", staged(),
            LedgerIngestResult.Success(listOf(txn(ingestMethod = IngestMethod.DETERMINISTIC))),
        )
        assertTrue(outcome is IngestPipeline.CommitOutcome.Ingested)
        val row = db.ledgerTransactionDao().getAll().single()
        assertEquals(IngestMethod.DETERMINISTIC, row.ingestMethod)
        assertEquals(-1_000L, row.amountCents)
    }

    @Test
    fun `an LLM_RECONCILED commit lands an LLM_RECONCILED row`() = runBlocking {
        IngestPipeline.commit(
            context, "file-llm", null, "stmt.pdf", staged(),
            LedgerIngestResult.Success(listOf(txn(ingestMethod = IngestMethod.LLM_RECONCILED))),
        )
        val row = db.ledgerTransactionDao().getAll().single()
        assertEquals(IngestMethod.LLM_RECONCILED, row.ingestMethod)
    }

    @Test
    fun `an UNRECONCILED commit lands an UNRECONCILED row and never supersedes anything`() = runBlocking {
        val outcome = IngestPipeline.commit(
            context, "file-unr", null, "stmt.csv", staged(),
            LedgerIngestResult.Success(listOf(txn(ingestMethod = IngestMethod.UNRECONCILED))),
        )
        assertEquals(0, (outcome as IngestPipeline.CommitOutcome.Ingested).provisionalSuperseded)
        val row = db.ledgerTransactionDao().getAll().single()
        assertEquals(IngestMethod.UNRECONCILED, row.ingestMethod)
    }

    @Test
    fun `a quarantined result writes no row at all`() = runBlocking {
        val outcome = IngestPipeline.commit(
            context, "file-q", null, "stmt.pdf", staged(),
            LedgerIngestResult.Quarantined("numbers didn't reconcile"),
        )
        assertTrue(outcome is IngestPipeline.CommitOutcome.Quarantined)
        assertEquals(0, transactionCount())
        assertEquals(IngestState.QUARANTINED, db.ingestedFileDao().getByDriveFileId("file-q")!!.state)
    }

    // --------------------------------------------------------------------------- supersession-in-commit

    @Test
    fun `a reconciled commit supersedes an in-window UNRECONCILED row, leaves out-of-window and non-UNRECONCILED rows alone`() = runBlocking {
        db.ledgerTransactionDao().insertAll(
            listOf(
                // In-window provisional - the statement below covers [500_000, 1_500_000].
                txn(accountId = "7823", txnDate = 1_000_000L, lineRef = "in-window", ingestMethod = IngestMethod.UNRECONCILED),
                // Out-of-window provisional - same card (suffix match), but dated well outside the statement's range.
                txn(accountId = "7823", txnDate = 9_000_000L, lineRef = "out-of-window", ingestMethod = IngestMethod.UNRECONCILED),
                // A DETERMINISTIC row for the SAME account/date the statement below also covers - must never
                // be touched by supersession, which is scoped to UNRECONCILED only.
                txn(accountId = "7823", txnDate = 1_000_000L, lineRef = "already-reconciled", ingestMethod = IngestMethod.DETERMINISTIC),
            ),
        )
        assertEquals(3, transactionCount())

        // The reconciled statement - full printed PAN, matches "7823" by suffix (ticket 12 §0).
        val outcome = IngestPipeline.commit(
            context, "file-reconciled", null, "stmt.pdf", staged(),
            LedgerIngestResult.Success(
                listOf(txn(accountId = "4111111111117823", txnDate = 1_000_000L, description = "NEW ROW", lineRef = "new", ingestMethod = IngestMethod.DETERMINISTIC)),
            ),
        )

        assertEquals(1, (outcome as IngestPipeline.CommitOutcome.Ingested).provisionalSuperseded)
        // 3 pre-existing + 1 new - 1 superseded = 3 remaining rows (a legacy DELETE, not a trash).
        assertEquals(3, transactionCount())

        val lineRefs = db.ledgerTransactionDao().getAll().map { it.lineRef }
        assertTrue("out-of-window provisional must survive", "out-of-window" in lineRefs)
        assertTrue("a DETERMINISTIC row must never be touched by supersession", "already-reconciled" in lineRefs)
        assertTrue("the new statement's own row must be present", "new" in lineRefs)
        assertFalse("the in-window provisional must be gone", "in-window" in lineRefs)
    }

    // ------------------------------------------------------------------------------------ atomicity

    @Test
    fun `atomicity, early failure - zero rows land when the very first row write fails`() = runBlocking {
        // Forces a real Room PRIMARY KEY collision on the FIRST row's insert - occupies id 1 first,
        // then hands commit a stamped transaction explicitly claiming that same id. Same forcing
        // shape as PantryControllerTest's post-repoint write-failure test, aimed at Room's own
        // constraint instead of RecordStore.WriteResult.Failure, since that type no longer exists
        // on this path.
        db.ledgerTransactionDao().insertAll(listOf(txn(id = 1L, accountId = "OCCUPANT", lineRef = "occupant")))

        val outcome = IngestPipeline.commit(
            context, "file-fails", null, "stmt.pdf", staged(),
            LedgerIngestResult.Success(
                listOf(
                    txn(id = 1L, lineRef = "1", description = "ROW ONE"),
                    txn(lineRef = "2", description = "ROW TWO"),
                ),
            ),
        )

        assertTrue(outcome is IngestPipeline.CommitOutcome.EngineWriteFailed)
        assertTrue((outcome as IngestPipeline.CommitOutcome.EngineWriteFailed).reason.isNotBlank())
        // Only the pre-existing occupant row survives - the colliding insert never landed, and
        // Room's own transaction rollback means "ROW TWO" never landed either even though it did
        // not itself collide with anything.
        assertEquals(1, transactionCount())
        // Re-offered on the next scan, not silently dropped - CLAUDE.md §7's "in words what did NOT happen".
        assertEquals(IngestState.NEW, db.ingestedFileDao().getByDriveFileId("file-fails")!!.state)
    }

    @Test
    fun `atomicity, late failure - a replace-flow's already-applied deletes are rolled back too when the recreate then fails`() = runBlocking {
        // First, a genuine successful commit - two rows land for this file.
        val firstOutcome = IngestPipeline.commit(
            context, "file-replace", null, "stmt.pdf", staged(),
            LedgerIngestResult.Success(
                listOf(
                    txn(lineRef = "orig-1", description = "ORIGINAL ONE"),
                    txn(lineRef = "orig-2", description = "ORIGINAL TWO"),
                ),
            ),
        )
        assertTrue(firstOutcome is IngestPipeline.CommitOutcome.Ingested)
        assertEquals(2, transactionCount())

        // An unrelated row occupies id 3 (the next autogenerated id after the two rows above), so
        // the replacement commit's own insert collides with it. The replace-flow delete loop runs
        // FIRST inside the transaction - dropping the two original rows via `deleteBySourceFileId` -
        // and only THEN does the insert hit the id collision and fail. This is the "late" failure:
        // writes that already happened earlier in the SAME transaction (the replace delete) must be
        // undone too, not just the insert that actually threw.
        db.ledgerTransactionDao().insertAll(listOf(txn(id = 3L, accountId = "OCCUPANT", lineRef = "occupant")))

        val replaceOutcome = IngestPipeline.commit(
            context, "file-replace", null, "stmt.pdf",
            staged(isReplace = true, previousAccountId = "BOFA-1234", previousMin = 1_000_000L, previousMax = 1_000_000L),
            LedgerIngestResult.Success(listOf(txn(id = 3L, lineRef = "new-1", description = "REPLACEMENT ONE"))),
        )

        assertTrue(replaceOutcome is IngestPipeline.CommitOutcome.EngineWriteFailed)
        // The two ORIGINAL rows must still be present - the replace-flow delete was rolled back
        // along with the failed insert, not left half-applied. Plus the untouched occupant row.
        assertEquals(3, transactionCount())
        val lineRefs = db.ledgerTransactionDao().getAll().map { it.lineRef }
        assertTrue("orig-1" in lineRefs)
        assertTrue("orig-2" in lineRefs)
        assertFalse("a rolled-back replacement row must never appear", "new-1" in lineRefs)
    }

    // ------------------------------------------------------------------------------------------ dedup

    @Test
    fun `an exact duplicate row from an overlapping re-import is skipped, a genuinely new row is inserted`() = runBlocking {
        IngestPipeline.commit(
            context, "file-A", null, "stmtA.pdf", staged(),
            LedgerIngestResult.Success(
                listOf(txn(accountId = "BOFA-1234", txnDate = 1_000_000L, description = "TRADER JOES", amountCents = -4599L, lineRef = "A-1")),
            ),
        )
        assertEquals(1, transactionCount())

        // A second, overlapping statement restates the SAME transaction (same account/date/cents/
        // description) - dedupKey matches regardless of lineRef, which a second export is free to
        // number differently - plus one genuinely new row.
        val outcome = IngestPipeline.commit(
            context, "file-B", null, "stmtB.pdf", staged(),
            LedgerIngestResult.Success(
                listOf(
                    txn(accountId = "BOFA-1234", txnDate = 1_000_000L, description = "TRADER JOES", amountCents = -4599L, lineRef = "B-1"),
                    txn(accountId = "BOFA-1234", txnDate = 2_000_000L, description = "WALMART", amountCents = -1200L, lineRef = "B-2"),
                ),
            ),
        )

        val ingested = outcome as IngestPipeline.CommitOutcome.Ingested
        assertEquals(1, ingested.transactionCount)
        assertEquals(1, ingested.duplicatesSkipped)
        assertEquals(2, transactionCount()) // the original TRADER JOES row, plus the new WALMART row - never 3
    }
}

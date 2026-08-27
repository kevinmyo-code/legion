package com.kevin.legion.ledger

import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.FieldType
import com.kevin.legion.data.local.IngestMethod
import com.kevin.legion.data.local.IngestState
import com.kevin.legion.data.local.LedgerCurrency
import com.kevin.legion.data.local.LedgerTransaction
import com.kevin.legion.data.local.RecordProvenance
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
 * Cutover 3 (`docs/architecture/cutover3-2026-08-24.md`): [IngestPipeline.commit]'s engine-side
 * write path, exercised directly with Robolectric (Room, no ContentResolver - same posture as
 * [LedgerTransferGateTest]/[LedgerControllerOwnAccountMovementsTest], which is why this is testable
 * at all despite README/CLAUDE.md §10's note that the ContentResolver-touching HALF of
 * [LedgerController]'s import path is not). Nothing here existed before this branch -
 * `IngestPipeline.commit`/`stage` had zero direct test coverage prior to cutover.
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
    ) = LedgerTransaction(
        sourceFile = "stmt.pdf", accountId = accountId, currency = currency, txnDate = txnDate,
        description = description, amountCents = amountCents, lineRef = lineRef, ingestMethod = ingestMethod,
    )

    private fun staged(isReplace: Boolean = false, previousAccountId: String? = null, previousMin: Long? = null, previousMax: Long? = null) =
        IngestPipeline.StageOutcome.Staged(
            bytes = ByteArray(0), isReplace = isReplace,
            previousAccountId = previousAccountId, previousMinTxnDate = previousMin, previousMaxTxnDate = previousMax,
        )

    private suspend fun engineTransactionCount(): Int {
        val schema = LedgerAspectSeeder.ensureSeeded(context)
        return db.engineRecordDao().activeByRecordType(schema.transaction.recordTypeId).size
    }

    private suspend fun trashedTransactionCount(): Int {
        val schema = LedgerAspectSeeder.ensureSeeded(context)
        return db.engineRecordDao().trashedByRecordType(schema.transaction.recordTypeId).size
    }

    /** Corrupts `sourceFileId`'s field TYPE to REFERENCE - same lever [EngineDataMigrationWave3Test]
     * uses, forced because `Transaction` carries no naturally-occurring REFERENCE field (see the
     * carve doc). Since [IngestPipeline.commit] always stamps every row's `sourceFileId` with the
     * incoming file's own id (a non-null String), this makes every subsequent [RecordStore.create]
     * call in this test fail identically - `validateReferences`'s `(raw as? Number)` cast fails
     * immediately for a REFERENCE-typed field holding a String. */
    private suspend fun corruptSourceFileIdField() {
        val schema = LedgerAspectSeeder.ensureSeeded(context)
        val field = db.fieldDefDao().forRecordType(schema.transaction.recordTypeId)
            .single { it.id == schema.transaction.fieldIds.getValue(LedgerAspectSeeder.FIELD_SOURCE_FILE_ID) }
        db.fieldDefDao().update(field.copy(type = FieldType.REFERENCE))
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


    // --------------------------------------------------------------------------- provenance mapping

    @Test
    fun `a DETERMINISTIC commit lands a DETERMINISTIC engine record`() = runBlocking {
        val outcome = IngestPipeline.commit(
            context, "file-det", null, "stmt.pdf", staged(),
            LedgerIngestResult.Success(listOf(txn(ingestMethod = IngestMethod.DETERMINISTIC))),
        )
        assertTrue(outcome is IngestPipeline.CommitOutcome.Ingested)
        val schema = LedgerAspectSeeder.ensureSeeded(context)
        val record = db.engineRecordDao().activeByRecordType(schema.transaction.recordTypeId).single()
        assertEquals(RecordProvenance.DETERMINISTIC, record.provenance)
        assertEquals(-1_000L, record.amountCents) // primaryAmountFieldId promotion, sign preserved
    }

    @Test
    fun `an LLM_RECONCILED commit lands an LLM_RECONCILED engine record`() = runBlocking {
        IngestPipeline.commit(
            context, "file-llm", null, "stmt.pdf", staged(),
            LedgerIngestResult.Success(listOf(txn(ingestMethod = IngestMethod.LLM_RECONCILED))),
        )
        val schema = LedgerAspectSeeder.ensureSeeded(context)
        val record = db.engineRecordDao().activeByRecordType(schema.transaction.recordTypeId).single()
        assertEquals(RecordProvenance.LLM_RECONCILED, record.provenance)
    }

    @Test
    fun `an UNRECONCILED commit lands an UNRECONCILED engine record and never supersedes anything`() = runBlocking {
        val outcome = IngestPipeline.commit(
            context, "file-unr", null, "stmt.csv", staged(),
            LedgerIngestResult.Success(listOf(txn(ingestMethod = IngestMethod.UNRECONCILED))),
        )
        assertEquals(0, (outcome as IngestPipeline.CommitOutcome.Ingested).provisionalSuperseded)
        val schema = LedgerAspectSeeder.ensureSeeded(context)
        val record = db.engineRecordDao().activeByRecordType(schema.transaction.recordTypeId).single()
        assertEquals(RecordProvenance.UNRECONCILED, record.provenance)
    }

    @Test
    fun `a quarantined result writes no engine record at all`() = runBlocking {
        val outcome = IngestPipeline.commit(
            context, "file-q", null, "stmt.pdf", staged(),
            LedgerIngestResult.Quarantined("numbers didn't reconcile"),
        )
        assertTrue(outcome is IngestPipeline.CommitOutcome.Quarantined)
        assertEquals(0, engineTransactionCount())
        assertEquals(IngestState.QUARANTINED, db.ingestedFileDao().getByDriveFileId("file-q")!!.state)
    }

    // --------------------------------------------------------------------------- supersession-in-commit

    @Test
    fun `a reconciled commit trashes an in-window UNRECONCILED mirror row, leaves out-of-window and non-UNRECONCILED rows alone`() = runBlocking {
        val schema = LedgerAspectSeeder.ensureSeeded(context)
        val recordStore = RecordStore(db.engineRecordDao(), db.fieldDefDao(), db.recordTypeDao())
        suspend fun seed(t: LedgerTransaction) = recordStore.create(
            recordTypeId = schema.transaction.recordTypeId,
            fieldValues = LedgerRecordBridge.fieldValuesFor(t, schema.transaction.fieldIds),
            provenance = LedgerRecordBridge.provenanceFor(t.ingestMethod),
            now = t.txnDate,
            guid = t.syncId,
        )

        // In-window provisional - the statement below covers [500_000, 1_500_000].
        seed(txn(accountId = "7823", txnDate = 1_000_000L, lineRef = "in-window", ingestMethod = IngestMethod.UNRECONCILED))
        // Out-of-window provisional - same card (suffix match), but dated well outside the statement's range.
        seed(txn(accountId = "7823", txnDate = 9_000_000L, lineRef = "out-of-window", ingestMethod = IngestMethod.UNRECONCILED))
        // A DETERMINISTIC row for the SAME account/date the statement below also covers - must never
        // be touched by supersession, which is scoped to UNRECONCILED only.
        seed(txn(accountId = "7823", txnDate = 1_000_000L, lineRef = "already-reconciled", ingestMethod = IngestMethod.DETERMINISTIC))

        assertEquals(3, engineTransactionCount())

        // The reconciled statement - full printed PAN, matches "7823" by suffix (ticket 12 §0).
        val outcome = IngestPipeline.commit(
            context, "file-reconciled", null, "stmt.pdf", staged(),
            LedgerIngestResult.Success(
                listOf(txn(accountId = "4111111111117823", txnDate = 1_000_000L, description = "NEW ROW", lineRef = "new", ingestMethod = IngestMethod.DETERMINISTIC)),
            ),
        )

        assertEquals(1, (outcome as IngestPipeline.CommitOutcome.Ingested).provisionalSuperseded)
        // 3 pre-existing + 1 new - 1 trashed = 3 active.
        assertEquals(3, engineTransactionCount())
        assertEquals(1, trashedTransactionCount())

        val activeLineRefs = db.engineRecordDao().activeByRecordType(schema.transaction.recordTypeId)
            .map { com.kevin.legion.engine.PayloadCodec.readString(org.json.JSONObject(it.payload), schema.transaction.fieldIds.getValue(LedgerAspectSeeder.FIELD_LINE_REF)) }
        assertTrue("out-of-window provisional must survive", "out-of-window" in activeLineRefs)
        assertTrue("a DETERMINISTIC row must never be touched by supersession", "already-reconciled" in activeLineRefs)
        assertTrue("the new statement's own row must be present", "new" in activeLineRefs)
        assertFalse("the in-window provisional must be gone from the active set", "in-window" in activeLineRefs)
    }

    // ------------------------------------------------------------------------------------ atomicity

    @Test
    fun `atomicity, early failure - zero rows land when the very first engine write fails`() = runBlocking {
        corruptSourceFileIdField()

        val outcome = IngestPipeline.commit(
            context, "file-fails", null, "stmt.pdf", staged(),
            LedgerIngestResult.Success(
                listOf(
                    txn(lineRef = "1", description = "ROW ONE"),
                    txn(lineRef = "2", description = "ROW TWO"),
                ),
            ),
        )

        assertTrue(outcome is IngestPipeline.CommitOutcome.EngineWriteFailed)
        assertTrue((outcome as IngestPipeline.CommitOutcome.EngineWriteFailed).reason.isNotBlank())
        assertEquals(0, engineTransactionCount())
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
        assertEquals(2, engineTransactionCount())

        // Now corrupt the schema so every FUTURE create fails, then re-commit the SAME file as a
        // replace (the file was re-parsed with different bytes). The replace-flow delete loop runs
        // FIRST inside the transaction - trashing the two original rows via RecordStore.delete,
        // which is untouched by the corruption (delete never validates references) - and only THEN
        // does the create loop hit the corrupted field and fail. This is the "late" failure: writes
        // that already happened earlier in the SAME transaction (the replace deletes) must be undone
        // too, not just the create that actually threw.
        corruptSourceFileIdField()

        val replaceOutcome = IngestPipeline.commit(
            context, "file-replace", null, "stmt.pdf",
            staged(isReplace = true, previousAccountId = "BOFA-1234", previousMin = 1_000_000L, previousMax = 1_000_000L),
            LedgerIngestResult.Success(listOf(txn(lineRef = "new-1", description = "REPLACEMENT ONE"))),
        )

        assertTrue(replaceOutcome is IngestPipeline.CommitOutcome.EngineWriteFailed)
        // The two ORIGINAL rows must still be ACTIVE - the replace-flow delete was rolled back along
        // with the failed create, not left half-applied.
        assertEquals(2, engineTransactionCount())
        assertEquals(0, trashedTransactionCount())
        val schema = LedgerAspectSeeder.ensureSeeded(context)
        val activeLineRefs = db.engineRecordDao().activeByRecordType(schema.transaction.recordTypeId)
            .map { com.kevin.legion.engine.PayloadCodec.readString(org.json.JSONObject(it.payload), schema.transaction.fieldIds.getValue(LedgerAspectSeeder.FIELD_LINE_REF)) }
        assertTrue("orig-1" in activeLineRefs)
        assertTrue("orig-2" in activeLineRefs)
        assertFalse("a rolled-back replacement row must never appear", "new-1" in activeLineRefs)
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
        assertEquals(1, engineTransactionCount())

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
        assertEquals(2, engineTransactionCount()) // the original TRADER JOES row, plus the new WALMART row - never 3
    }
}

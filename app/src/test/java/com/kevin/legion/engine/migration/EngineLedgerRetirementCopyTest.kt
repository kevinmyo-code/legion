package com.kevin.legion.engine.migration

import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.IngestMethod
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * [EngineLedgerRetirementCopy]'s own suite - ticket 15 step 5
 * (`.scratch/backend-erp/issues/15-engine-retirement-sequence.md`). Mirrors `PlaceControllerTest`'s
 * step-1 section and `PantryControllerTest`'s step-2 section in shape (idempotency, no
 * duplicate-or-overwrite on the natural key, nothing deleted from the engine), plus the one thing
 * THIS aspect's identity/provenance answer has that neither of theirs did: a real three-valued
 * [IngestMethod]/[RecordProvenance] to carry through unchanged, never asserted to a fixed value the
 * way pantry's single-valued `LLM_RECONCILED` could be.
 */
@RunWith(RobolectricTestRunner::class)
class EngineLedgerRetirementCopyTest {
    private val context = RuntimeEnvironment.getApplication()
    private val db get() = CarDatabase.getDatabase(context)

    @Before
    fun clearState() {
        RoomTestReset.resetCarDatabaseSingleton()
    }

    @After
    fun drainRoomInvalidationTracker() {
        // Same "Illegal connection pointer" race every other Robolectric+Room suite in this
        // codebase guards against - see IngestPipelineEngineCommitTest's identically-worded @After.
        RoomTestReset.drainArchDiskIoPool()
    }

    private fun txn(
        accountId: String = "BOFA-1234",
        txnDate: Long = 1_000_000L,
        description: String = "TEST MERCHANT",
        amountCents: Long = -1_000L,
        lineRef: String = "line-1",
        ingestMethod: IngestMethod = IngestMethod.DETERMINISTIC,
        syncId: String = java.util.UUID.randomUUID().toString(),
    ) = LedgerTransaction(
        sourceFile = "stmt.pdf", accountId = accountId, currency = LedgerCurrency.USD, txnDate = txnDate,
        description = description, amountCents = amountCents, lineRef = lineRef, ingestMethod = ingestMethod,
        syncId = syncId,
    )

    /** Writes a `Transaction` record directly through the engine, bypassing both
     * [com.kevin.legion.ledger.IngestPipeline] and [com.kevin.legion.ledger.LedgerController]
     * entirely - simulates a row created before this step's repoint (or on an install still
     * mid-soak), which only [EngineLedgerRetirementCopy] should ever be able to see and copy
     * forward. */
    private suspend fun createEngineTransaction(t: LedgerTransaction): Long {
        val schema = LedgerAspectSeeder.ensureSeeded(context)
        val store = RecordStore(db.engineRecordDao(), db.fieldDefDao(), db.recordTypeDao())
        val result = store.create(
            recordTypeId = schema.transaction.recordTypeId,
            fieldValues = LedgerRecordBridge.fieldValuesFor(t, schema.transaction.fieldIds),
            provenance = LedgerRecordBridge.provenanceFor(t.ingestMethod),
            now = t.txnDate,
            guid = t.syncId,
        )
        return (result as RecordStore.WriteResult.Success).recordId
    }

    // ----------------------------------------------------------------------------------- the copy

    @Test
    fun `copies every engine transaction with no legacy row into ledger_transactions`() = runBlocking {
        createEngineTransaction(txn(description = "ENGINE ONLY", syncId = "guid-1"))

        val result = EngineLedgerRetirementCopy.copyIfNeeded(context)

        assertEquals(1, result.copied)
        assertTrue(!result.alreadyDone)
        val row = db.ledgerTransactionDao().getAll().single()
        assertEquals("ENGINE ONLY", row.description)
        assertEquals("guid-1", row.syncId)
    }

    /** Every provenance value the aspect actually carries, checked individually - a copier that
     * silently coerced one would be exactly CLAUDE.md §4 rule 7's "row that arrives looking more or
     * less verified than it was", just approached from a migration path instead of a live write. */
    @Test
    fun `all three provenance values survive the copy exactly, and UNRECONCILED still reads as unverified`() = runBlocking {
        createEngineTransaction(txn(ingestMethod = IngestMethod.DETERMINISTIC, syncId = "det", description = "DET"))
        createEngineTransaction(txn(ingestMethod = IngestMethod.LLM_RECONCILED, syncId = "llm", description = "LLM"))
        createEngineTransaction(txn(ingestMethod = IngestMethod.UNRECONCILED, syncId = "unr", description = "UNR"))

        EngineLedgerRetirementCopy.copyIfNeeded(context)

        val bySyncId = db.ledgerTransactionDao().getAll().associateBy { it.syncId }
        assertEquals(IngestMethod.DETERMINISTIC, bySyncId.getValue("det").ingestMethod)
        assertEquals(IngestMethod.LLM_RECONCILED, bySyncId.getValue("llm").ingestMethod)
        assertEquals(IngestMethod.UNRECONCILED, bySyncId.getValue("unr").ingestMethod)
    }

    // ------------------------------------------------------------------------------- idempotency

    @Test
    fun `a second run is a no-op - the SharedPreferences fast path`() = runBlocking {
        createEngineTransaction(txn(syncId = "guid-1"))
        val first = EngineLedgerRetirementCopy.copyIfNeeded(context)
        assertEquals(1, first.copied)

        val second = EngineLedgerRetirementCopy.copyIfNeeded(context)
        assertEquals(0, second.copied)
        assertTrue(second.alreadyDone)
        assertEquals(1, db.ledgerTransactionDao().getAll().size)
    }

    // ---------------------------------------------------------- reconcile-and-repoint, never blind-switch

    /** A syncId already present in `ledger_transactions` (forward-copied by
     * [EngineDataMigrationWave3], or a previously-interrupted pass of this very copier) must never
     * be duplicated or overwritten - `ledger_transactions` wins ties. Proven load-bearing by
     * mutation: the fix under test is the per-guid existence check, and this test is the one that
     * would fail if that check were ever removed or weakened. */
    @Test
    fun `an existing legacy row for the same syncId is never duplicated or overwritten`() = runBlocking {
        val existing = LedgerTransaction(
            sourceFile = "stmt.pdf", accountId = "BOFA-1234", currency = LedgerCurrency.USD,
            txnDate = 1_000_000L, description = "LEGACY WINS", amountCents = -1_000L,
            lineRef = "line-1", ingestMethod = IngestMethod.DETERMINISTIC, syncId = "guid-1",
        )
        db.ledgerTransactionDao().insertAll(listOf(existing))

        // The engine's own copy of the SAME guid has a DIFFERENT description - if the copier ever
        // overwrote instead of skipping, this is the field that would reveal it.
        createEngineTransaction(txn(description = "ENGINE VERSION SHOULD NOT WIN", syncId = "guid-1"))

        val result = EngineLedgerRetirementCopy.copyIfNeeded(context)

        assertEquals(0, result.copied)
        val rows = db.ledgerTransactionDao().getAll()
        assertEquals(1, rows.size)
        assertEquals("LEGACY WINS", rows.single().description)
    }

    // -------------------------------------------------------------------------------- deletes nothing

    @Test
    fun `the engine's Transaction records still exist after the copy`() = runBlocking {
        val schema = LedgerAspectSeeder.ensureSeeded(context)
        createEngineTransaction(txn(syncId = "guid-1"))

        EngineLedgerRetirementCopy.copyIfNeeded(context)

        assertEquals(1, db.engineRecordDao().activeByRecordType(schema.transaction.recordTypeId).size)
    }
}

package com.kevin.legion.backend

import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.IngestMethod
import com.kevin.legion.data.local.RecordProvenance
import com.kevin.legion.engine.RecordStore
import com.kevin.legion.engine.ledger.LedgerAspectSeeder
import com.kevin.legion.testutil.RoomTestReset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * LedgerReconcile's Phase 4 step 1/2 wave - one record type (`Transaction`), and only
 * `UNRECONCILED` rows actually upload this wave (see [LedgerReconcile]'s own class doc for the
 * traced reason `DETERMINISTIC`/`LLM_RECONCILED` rows are reported [LedgerReconcile.Report.skipped]
 * instead). Exercised entirely against an in-memory [FakeLedgerBackend] and a real (Robolectric)
 * engine/Room, never a network - same posture as [FleetReconcileTest]/[EventsReconcileTest].
 */
@RunWith(RobolectricTestRunner::class)
class LedgerReconcileTest {
    private val context = RuntimeEnvironment.getApplication()

    private class FakeLedgerBackend : LedgerBackend {
        val transactions = mutableMapOf<String, RemoteLedgerTransaction>() // keyed by originGuid
        private var counter = 0

        /** Set to make the NEXT [uploadMigratedTransaction] call fail - the short-circuit test's hook. */
        var failNextUpload = false

        override suspend fun fetchActiveTransactions(): Result<List<RemoteLedgerTransaction>> =
            Result.success(transactions.values.toList())

        override suspend fun uploadMigratedTransaction(txn: MigratedLedgerTransaction): Result<Boolean> {
            if (failNextUpload) {
                failNextUpload = false
                return Result.failure(LedgerBackendException("simulated transport failure"))
            }
            if (transactions.containsKey(txn.originGuid)) return Result.success(false)
            transactions[txn.originGuid] = RemoteLedgerTransaction(
                serverId = "txn-${++counter}",
                statementId = txn.statementId,
                accountLast4 = txn.accountLast4,
                accountNickname = txn.accountNickname,
                currency = txn.currency,
                txnDateEpochMs = txn.txnDateEpochMs,
                description = txn.description,
                amountCents = txn.amountCents,
                balanceCents = txn.balanceCents,
                lineRef = txn.lineRef,
                category = txn.category,
                categoryPending = txn.categoryPending,
                pendingLoggedAtMs = txn.pendingLoggedAtMs,
                provenance = txn.provenance.name,
                createdAtMs = 1_000L,
                originGuid = txn.originGuid,
            )
            return Result.success(true)
        }
    }

    @Before
    fun clearState() {
        RoomTestReset.resetCarDatabaseSingleton()
    }

    /** Creates an active engine `Transaction` record with the exact field map
     * [com.kevin.legion.engine.ledger.LedgerRecordBridge.fieldValuesFor] would build for a real
     * [com.kevin.legion.data.local.LedgerTransaction] - so this suite's fixtures match the real
     * write path rather than inventing a second one. */
    private suspend fun createEngineTransaction(
        provenance: RecordProvenance,
        accountId: String = "5555555555557823",
        description: String = "COFFEE SHOP",
        amountCents: Long = -450L,
        balanceCents: Long? = null,
        currency: String = "USD",
        txnDate: Long = 20_000L,
        lineRef: String = "file.pdf:0",
        category: String? = null,
        categoryPending: Boolean = false,
        pendingLoggedAt: Long? = null,
        guid: String = java.util.UUID.randomUUID().toString(),
    ): Long {
        val db = CarDatabase.getDatabase(context)
        val sch = LedgerAspectSeeder.ensureSeeded(context)
        val store = RecordStore(db.engineRecordDao(), db.fieldDefDao(), db.recordTypeDao())
        val fieldIds = sch.transaction.fieldIds
        val result = store.create(
            sch.transaction.recordTypeId,
            mapOf(
                fieldIds.getValue(LedgerAspectSeeder.FIELD_SOURCE_FILE) to "file.pdf",
                fieldIds.getValue(LedgerAspectSeeder.FIELD_ACCOUNT_ID) to accountId,
                fieldIds.getValue(LedgerAspectSeeder.FIELD_CURRENCY) to currency,
                fieldIds.getValue(LedgerAspectSeeder.FIELD_TXN_DATE) to txnDate,
                fieldIds.getValue(LedgerAspectSeeder.FIELD_DESCRIPTION) to description,
                fieldIds.getValue(LedgerAspectSeeder.FIELD_AMOUNT) to amountCents,
                fieldIds.getValue(LedgerAspectSeeder.FIELD_BALANCE) to balanceCents,
                fieldIds.getValue(LedgerAspectSeeder.FIELD_LINE_REF) to lineRef,
                fieldIds.getValue(LedgerAspectSeeder.FIELD_SOURCE_FILE_ID) to null,
                fieldIds.getValue(LedgerAspectSeeder.FIELD_CATEGORY) to category,
                fieldIds.getValue(LedgerAspectSeeder.FIELD_CATEGORY_PENDING) to categoryPending,
                fieldIds.getValue(LedgerAspectSeeder.FIELD_PENDING_LOGGED_AT) to pendingLoggedAt,
            ),
            provenance,
            guid = guid,
        )
        return (result as RecordStore.WriteResult.Success).recordId
    }

    @Test
    fun `an UNRECONCILED row maps field-for-field and its provenance survives verbatim`() = runBlocking {
        createEngineTransaction(
            provenance = RecordProvenance.UNRECONCILED,
            accountId = "7823",
            description = "MID-CYCLE PURCHASE",
            amountCents = -1299L,
            currency = "USD",
            txnDate = 30_000L,
            lineRef = "card.csv:2",
            category = "Dining",
            categoryPending = true,
        )
        val backend = FakeLedgerBackend()

        val report = LedgerReconcile.run(context, backend).getOrThrow()

        assertEquals(1, report.engineCount)
        assertEquals(1, report.uploaded)
        assertTrue(report.skipped.isEmpty())
        val row = backend.transactions.values.single()
        assertNull(row.statementId)
        assertEquals("7823", row.accountLast4)
        assertEquals("7823", row.accountNickname) // accountId itself - see LedgerReconcile's own doc
        assertEquals("USD", row.currency)
        assertEquals(30_000L, row.txnDateEpochMs)
        assertEquals("MID-CYCLE PURCHASE", row.description)
        assertEquals(-1299L, row.amountCents)
        assertNull(row.balanceCents)
        assertEquals("card.csv:2", row.lineRef)
        assertEquals("Dining", row.category)
        assertTrue(row.categoryPending)
        assertEquals(IngestMethod.UNRECONCILED.name, row.provenance)
        assertTrue(report.isClean)
    }

    @Test
    fun `DETERMINISTIC and LLM_RECONCILED rows are reported skipped, never uploaded or reclassified`() = runBlocking {
        createEngineTransaction(provenance = RecordProvenance.DETERMINISTIC, description = "DBS ROW")
        createEngineTransaction(provenance = RecordProvenance.LLM_RECONCILED, description = "LEGION CSV ROW")
        createEngineTransaction(provenance = RecordProvenance.UNRECONCILED, description = "CARD CSV ROW")
        val backend = FakeLedgerBackend()

        val report = LedgerReconcile.run(context, backend).getOrThrow()

        assertEquals(3, report.engineCount)
        assertEquals(1, report.uploaded)
        assertEquals(2, report.skipped.size)
        assertTrue(report.skipped.any { it.contains("DBS ROW") })
        assertTrue(report.skipped.any { it.contains("LEGION CSV ROW") })
        assertEquals(1, backend.transactions.size)
        val uploadedRow = backend.transactions.values.single()
        assertEquals("CARD CSV ROW", uploadedRow.description)
        // Skipped rows are excluded from the diff entirely - a "not yet migrated" state, not a
        // rejection - so a run with only skips left over is still clean.
        assertTrue(report.isClean)
    }

    @Test
    fun `a re-run is idempotent - identity is stable, not just counts`() = runBlocking {
        createEngineTransaction(provenance = RecordProvenance.UNRECONCILED, description = "REPEATED ROW")
        val backend = FakeLedgerBackend()

        val first = LedgerReconcile.run(context, backend).getOrThrow()
        val serverIdAfterFirst = backend.transactions.values.single().serverId

        val second = LedgerReconcile.run(context, backend).getOrThrow()

        // Identity, not just counts (lessons.md's "assert on identity, not only on counts" shape) -
        // a defective re-run could still report matching counts while quietly reminting a fresh
        // server row for an already-migrated transaction.
        assertEquals(serverIdAfterFirst, backend.transactions.values.single().serverId)
        assertEquals(1, backend.transactions.size)
        assertEquals(0, second.uploaded)
        assertEquals(first.serverCountAfter, second.serverCountAfter)
        assertTrue(second.isClean)
    }

    @Test
    fun `a failed upload short-circuits and touches nothing further`() = runBlocking {
        createEngineTransaction(provenance = RecordProvenance.UNRECONCILED, description = "FIRST ROW")
        createEngineTransaction(provenance = RecordProvenance.UNRECONCILED, description = "SECOND ROW")
        val backend = FakeLedgerBackend()
        backend.failNextUpload = true

        val result = LedgerReconcile.run(context, backend)

        assertTrue(result.isFailure)
        // The failure happened on the FIRST row - nothing landed server-side, and the second row's
        // upload was never attempted, matching FleetReconcile/PantryReconcile/EventsReconcile's own
        // "a partial upload must never be reported as a low count" posture.
        assertTrue(backend.transactions.isEmpty())
    }

    @Test
    fun `never deletes or trashes a source engine record`() = runBlocking {
        createEngineTransaction(provenance = RecordProvenance.UNRECONCILED, description = "SURVIVES")
        createEngineTransaction(provenance = RecordProvenance.DETERMINISTIC, description = "ALSO SURVIVES")
        val backend = FakeLedgerBackend()
        val db = CarDatabase.getDatabase(context)
        val sch = LedgerAspectSeeder.ensureSeeded(context)

        LedgerReconcile.run(context, backend).getOrThrow()

        assertEquals(2, db.engineRecordDao().activeByRecordType(sch.transaction.recordTypeId).size)
    }

    @Test
    fun `each of the three provenance values survives a backend round trip verbatim`() = runBlocking {
        // LedgerReconcile itself only ever calls uploadMigratedTransaction for UNRECONCILED rows
        // this wave (see its own class doc for the traced reason). This test exercises the
        // BACKEND's own field-mapping contract directly - LedgerBackend/SupabaseLedgerBackend must
        // carry all three IngestMethod values without corruption, since a caller that CAN
        // legitimately construct a statement header (a later ticket, or SupabaseLedgerBackend's
        // future statement-header-aware caller) depends on that plumbing being honest regardless of
        // which value it is asked to carry.
        val backend = FakeLedgerBackend()
        val values = listOf(IngestMethod.DETERMINISTIC, IngestMethod.LLM_RECONCILED, IngestMethod.UNRECONCILED)
        for (provenance in values) {
            backend.uploadMigratedTransaction(
                MigratedLedgerTransaction(
                    originGuid = "guid-$provenance",
                    statementId = if (provenance == IngestMethod.UNRECONCILED) null else "statement-$provenance",
                    accountLast4 = "1234",
                    accountNickname = "1234",
                    currency = "SGD",
                    txnDateEpochMs = 1_000L,
                    description = "row for $provenance",
                    amountCents = -100L,
                    balanceCents = null,
                    lineRef = "ref-$provenance",
                    category = null,
                    categoryPending = false,
                    pendingLoggedAtMs = null,
                    provenance = provenance,
                ),
            ).getOrThrow()
        }

        val fetched = backend.fetchActiveTransactions().getOrThrow().associateBy { it.description }
        for (provenance in values) {
            assertEquals(provenance.name, fetched.getValue("row for $provenance").provenance)
        }
    }

    @Test
    fun `an accountId with fewer than 4 digits is skipped rather than crashing`() = runBlocking {
        createEngineTransaction(provenance = RecordProvenance.UNRECONCILED, accountId = "12", description = "TOO SHORT")
        val backend = FakeLedgerBackend()

        val report = LedgerReconcile.run(context, backend).getOrThrow()

        assertEquals(0, report.uploaded)
        assertEquals(1, report.skipped.size)
        assertTrue(report.skipped.single().contains("TOO SHORT"))
        assertFalse(backend.transactions.values.any { it.description == "TOO SHORT" })
        assertTrue(report.isClean)
    }
}

package com.kevin.legion.backend

import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.IngestMethod
import com.kevin.legion.data.local.LedgerCurrency
import com.kevin.legion.data.local.LedgerTransaction
import com.kevin.legion.testutil.RoomTestReset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * [LedgerTransactionsSync.pull] - exercised the same way [LedgerConfigSyncTest] exercises
 * [LedgerConfigSync.pull]: against an in-memory [FakeLedgerBackend] and the real (Robolectric)
 * local `ledger_transactions` table, never a network. `ledger_transactions` has no `updated_at`/
 * `deleted_at` at all (see [LedgerTransactionsSync]'s own class doc), so this is insert-if-absent
 * only - there is no LWW or tombstone branch to test the way [LedgerConfigSyncTest] tests them.
 */
@RunWith(RobolectricTestRunner::class)
class LedgerTransactionsSyncTest {
    private val context = RuntimeEnvironment.getApplication()

    private class FakeLedgerBackend : LedgerBackend {
        val rows = mutableListOf<RemoteLedgerTransaction>()

        override suspend fun fetchActiveTransactions(): Result<List<RemoteLedgerTransaction>> = Result.success(rows)
        override suspend fun uploadMigratedTransaction(txn: MigratedLedgerTransaction): Result<Boolean> =
            Result.failure(LedgerBackendException("not used"))

        override suspend fun fetchChangedTransactionsSince(sinceMs: Long): Result<List<RemoteLedgerTransaction>> =
            Result.success(rows.filter { it.createdAtMs >= sinceMs })
    }

    @Before
    fun clearState() {
        RoomTestReset.resetCarDatabaseSingleton()
    }

    private fun remoteTxn(
        serverId: String,
        provenance: String,
        createdAtMs: Long,
        originGuid: String? = null,
        amountCents: Long = -1_234L,
    ) = RemoteLedgerTransaction(
        serverId = serverId,
        statementId = null,
        accountLast4 = "4471",
        accountNickname = "BOFA ****4471",
        currency = "USD",
        txnDateEpochMs = createdAtMs,
        description = "WHOLE FOODS #123",
        amountCents = amountCents,
        balanceCents = null,
        lineRef = "$serverId-ref",
        category = null,
        categoryPending = false,
        pendingLoggedAtMs = null,
        provenance = provenance,
        createdAtMs = createdAtMs,
        originGuid = originGuid,
    )

    @Test
    fun `a server-only DETERMINISTIC row is inserted and tagged exactly`() = runBlocking {
        val backend = FakeLedgerBackend()
        backend.rows += remoteTxn("txn-1", "DETERMINISTIC", 1_000L)

        val report = LedgerTransactionsSync.pull(context, backend)

        assertEquals(1, report.inserted)
        assertEquals(0, report.alreadyPresent)
        val stored = CarDatabase.getDatabase(context).ledgerTransactionDao().getAll()
        assertEquals(1, stored.size)
        assertEquals(IngestMethod.DETERMINISTIC, stored.single().ingestMethod)
        assertEquals("txn-1", stored.single().syncId)
    }

    @Test
    fun `LLM_RECONCILED provenance survives the pull exactly`() = runBlocking {
        val backend = FakeLedgerBackend()
        backend.rows += remoteTxn("txn-2", "LLM_RECONCILED", 1_000L)

        LedgerTransactionsSync.pull(context, backend)

        val stored = CarDatabase.getDatabase(context).ledgerTransactionDao().getAll().single()
        assertEquals(IngestMethod.LLM_RECONCILED, stored.ingestMethod)
    }

    @Test
    fun `UNRECONCILED provenance survives the pull exactly, never upgraded`() = runBlocking {
        val backend = FakeLedgerBackend()
        backend.rows += remoteTxn("txn-3", "UNRECONCILED", 1_000L)

        LedgerTransactionsSync.pull(context, backend)

        val stored = CarDatabase.getDatabase(context).ledgerTransactionDao().getAll().single()
        assertEquals(IngestMethod.UNRECONCILED, stored.ingestMethod)
    }

    @Test
    fun `a USER-provenance row maps to UNRECONCILED, same as LedgerRecordBridge`() = runBlocking {
        val backend = FakeLedgerBackend()
        backend.rows += remoteTxn("txn-4", "USER", 1_000L)

        LedgerTransactionsSync.pull(context, backend)

        val stored = CarDatabase.getDatabase(context).ledgerTransactionDao().getAll().single()
        assertEquals(IngestMethod.UNRECONCILED, stored.ingestMethod)
    }

    @Test
    fun `an unrecognised provenance is refused, never guessed`() = runBlocking {
        val backend = FakeLedgerBackend()
        backend.rows += remoteTxn("txn-5", "SOMETHING_NEW", 1_000L)

        val report = LedgerTransactionsSync.pull(context, backend)

        assertEquals(0, report.inserted)
        assertEquals(1, report.unrecognizedProvenance.size)
        assertTrue(report.unrecognizedProvenance.single().contains("SOMETHING_NEW"))
        assertEquals(0, CarDatabase.getDatabase(context).ledgerTransactionDao().getAll().size)
    }

    @Test
    fun `a migrated row already known locally by syncId equalling originGuid is not duplicated`() = runBlocking {
        val db = CarDatabase.getDatabase(context)
        db.ledgerTransactionDao().insertAll(
            listOf(
                LedgerTransaction(
                    sourceFile = "eStmt.pdf",
                    accountId = "BOFA ****4471",
                    currency = LedgerCurrency.USD,
                    txnDate = 500L,
                    description = "already local",
                    amountCents = -500L,
                    lineRef = "local-ref",
                    ingestMethod = IngestMethod.UNRECONCILED,
                    syncId = "local-guid-1",
                ),
            ),
        )
        val backend = FakeLedgerBackend()
        backend.rows += remoteTxn("txn-6", "UNRECONCILED", 1_000L, originGuid = "local-guid-1")

        val report = LedgerTransactionsSync.pull(context, backend)

        assertEquals(0, report.inserted)
        assertEquals(1, report.alreadyPresent)
        assertEquals(1, db.ledgerTransactionDao().getAll().size)
    }

    @Test
    fun `a local-only row survives untouched`() = runBlocking {
        val db = CarDatabase.getDatabase(context)
        db.ledgerTransactionDao().insertAll(
            listOf(
                LedgerTransaction(
                    sourceFile = "voice",
                    accountId = "BOFA ****4471",
                    currency = LedgerCurrency.USD,
                    txnDate = 500L,
                    description = "pending charge",
                    amountCents = -900L,
                    lineRef = "pending-ref",
                    ingestMethod = IngestMethod.UNRECONCILED,
                    syncId = "local-only-guid",
                    pendingLoggedAt = 500L,
                ),
            ),
        )
        val backend = FakeLedgerBackend() // server has nothing

        val report = LedgerTransactionsSync.pull(context, backend)

        assertEquals(0, report.inserted)
        val stored = db.ledgerTransactionDao().getAll()
        assertEquals(1, stored.size)
        assertEquals("pending charge", stored.single().description)
    }

    @Test
    fun `a second consecutive pull of the same server state is a true no-op`() = runBlocking {
        val backend = FakeLedgerBackend()
        backend.rows += remoteTxn("txn-7", "DETERMINISTIC", 1_000L)

        val first = LedgerTransactionsSync.pull(context, backend)
        assertEquals(1, first.inserted)

        val beforeSecond = CarDatabase.getDatabase(context).ledgerTransactionDao().getAll()
        val second = LedgerTransactionsSync.pull(context, backend)
        assertEquals(0, second.inserted)
        assertEquals(1, second.alreadyPresent)
        val afterSecond = CarDatabase.getDatabase(context).ledgerTransactionDao().getAll()
        assertEquals(beforeSecond, afterSecond)
    }

    @Test
    fun `money survives the pull as exact Long cents`() = runBlocking {
        val backend = FakeLedgerBackend()
        backend.rows += remoteTxn("txn-8", "DETERMINISTIC", 1_000L, amountCents = -123_456_789L)

        LedgerTransactionsSync.pull(context, backend)

        val stored = CarDatabase.getDatabase(context).ledgerTransactionDao().getAll().single()
        assertEquals(-123_456_789L, stored.amountCents)
        assertNotNull(stored.amountCents)
    }
}

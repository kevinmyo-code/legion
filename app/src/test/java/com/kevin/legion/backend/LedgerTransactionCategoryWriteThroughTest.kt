package com.kevin.legion.backend

import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.IngestMethod
import com.kevin.legion.data.local.LedgerCurrency
import com.kevin.legion.data.local.LedgerTransaction
import com.kevin.legion.data.local.OutboxTarget
import com.kevin.legion.testutil.RoomTestReset
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * [LedgerTransactionCategoryWriteThrough] and [LedgerTransactionCategoryOutboxDrain] - a person's
 * category reaching the engine as an override (backend-etl ticket 14 option 2), queued and said so in
 * words when the engine cannot be reached.
 */
@RunWith(RobolectricTestRunner::class)
class LedgerTransactionCategoryWriteThroughTest {
    private val context = RuntimeEnvironment.getApplication()
    private val db get() = CarDatabase.getDatabase(context)

    private class FakeEngine(var down: Boolean = false) : LedgerBackend {
        val sent = mutableListOf<Pair<String, String>>()
        override suspend fun fetchActiveTransactions(): Result<List<RemoteLedgerTransaction>> =
            Result.success(emptyList())
        override suspend fun uploadMigratedTransaction(txn: MigratedLedgerTransaction): Result<Boolean> =
            Result.failure(LedgerBackendException("not used"))
        override suspend fun setTransactionCategory(serverId: String, category: String): Result<Unit> {
            if (down) return Result.failure(LedgerBackendException("engine unreachable"))
            sent += serverId to category
            return Result.success(Unit)
        }
    }

    private fun row(id: Long, syncId: String, sourceFile: String = SYNCED_SOURCE_FILE) = LedgerTransaction(
        id = id,
        sourceFile = sourceFile,
        accountId = "BofA checking",
        currency = LedgerCurrency.USD,
        txnDate = 1_788_000_000_000L,
        description = "ZELLE PAYMENT TO MIA $id",
        amountCents = -100L,
        lineRef = "l-$id",
        ingestMethod = IngestMethod.UNRECONCILED,
        syncId = syncId,
    )

    @Before
    fun reset() {
        RoomTestReset.resetCarDatabaseSingleton()
    }

    @After
    fun clearOverride() {
        LedgerTransactionCategoryWriteThrough.backendOverride = null
    }

    @Test
    fun `a reachable engine gets a person override per server row, keyed by the server id`() = runBlocking {
        val engine = FakeEngine()
        LedgerTransactionCategoryWriteThrough.backendOverride = engine

        val rows = listOf(row(1, "s-1"), row(2, "s-2"))
        val report = LedgerTransactionCategoryWriteThrough.push(context, rows, "Transfers")

        assertEquals(listOf("s-1" to "Transfers", "s-2" to "Transfers"), engine.sent)
        assertEquals(LedgerTransactionCategoryWriteThrough.Report(2, 0, 0), report)
        assertNull(report.sentence())
        assertNull(LedgerTransactionCategoryWriteThrough.pendingSentence(context))
    }

    @Test
    fun `an unreachable engine queues every row, says so, and the drain sends them later`() = runBlocking {
        val engine = FakeEngine(down = true)
        LedgerTransactionCategoryWriteThrough.backendOverride = engine

        val rows = listOf(row(1, "s-1"), row(2, "s-2"))
        val report = LedgerTransactionCategoryWriteThrough.push(context, rows, "Transfers")

        assertEquals(LedgerTransactionCategoryWriteThrough.Report(0, 2, 0), report)
        assertTrue(report.sentence()!!.contains("the server couldn't be reached"))
        assertEquals(setOf("s-1", "s-2"), LedgerTransactionCategoryWriteThrough.pendingServerIds(context))
        assertNotNull(LedgerTransactionCategoryWriteThrough.pendingSentence(context))

        engine.down = false
        val drained = LedgerTransactionCategoryOutboxDrain.drain(context, engine)
        assertEquals(2, drained.succeeded)
        assertEquals(setOf("s-1" to "Transfers", "s-2" to "Transfers"), engine.sent.toSet())
        assertTrue(db.outboxDao().pendingForTable(OutboxTarget.LEDGER_TRANSACTION_CATEGORIES, 99).isEmpty())
        assertNull(LedgerTransactionCategoryWriteThrough.pendingSentence(context))
    }

    @Test
    fun `a newer choice replaces the queued one, so a stale entry never drains over it`() = runBlocking {
        val engine = FakeEngine(down = true)
        LedgerTransactionCategoryWriteThrough.backendOverride = engine
        LedgerTransactionCategoryWriteThrough.push(context, listOf(row(1, "s-1")), "Shopping")
        LedgerTransactionCategoryWriteThrough.push(context, listOf(row(1, "s-1")), "Transfers")

        engine.down = false
        LedgerTransactionCategoryOutboxDrain.drain(context, engine)
        assertEquals(listOf("s-1" to "Transfers"), engine.sent)
    }

    @Test
    fun `a row this phone minted has no server id, stays here, and says so`() = runBlocking {
        val engine = FakeEngine()
        LedgerTransactionCategoryWriteThrough.backendOverride = engine

        val phoneRow = row(1, "local-guid", sourceFile = "voice")
        val report = LedgerTransactionCategoryWriteThrough.push(context, listOf(phoneRow), "Travel")

        assertTrue(engine.sent.isEmpty())
        assertEquals(LedgerTransactionCategoryWriteThrough.Report(0, 0, 1), report)
        assertTrue(report.sentence()!!.contains("stays on this phone"))
    }
}

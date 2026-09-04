package com.kevin.legion.backend

import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.LedgerCurrency
import com.kevin.legion.data.local.PantryReceipt
import com.kevin.legion.testutil.RoomTestReset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * [PantryReceiptsSync.pull] - exercised the same way [LedgerTransactionsSyncTest] exercises
 * [LedgerTransactionsSync.pull]: against an in-memory [FakePantryBackend] and the real
 * (Robolectric) local `pantry_receipts`/`pantry_line_items` tables, never a network.
 * `receipts`/`receipt_line_items` have no `updated_at`/`deleted_at` at all (see
 * [PantryReceiptsSync]'s own class doc), so this is insert-if-absent only.
 */
@RunWith(RobolectricTestRunner::class)
class PantryReceiptsSyncTest {
    private val context = RuntimeEnvironment.getApplication()

    private class FakePantryBackend : PantryBackend {
        val rows = mutableListOf<RemoteReceipt>()

        override suspend fun fetchActiveReceipts(): Result<List<RemoteReceipt>> = Result.success(rows)
        override suspend fun commitReceipt(payload: String): Result<CommitOutcome> =
            Result.failure(PantryBackendException("not used"))
        override suspend fun uploadMigratedReceipt(receipt: MigratedReceipt): Result<Boolean> =
            Result.failure(PantryBackendException("not used"))

        override suspend fun fetchChangedReceiptsSince(sinceMs: Long): Result<List<RemoteReceipt>> =
            Result.success(rows.filter { it.createdAtMs >= sinceMs })
    }

    @Before
    fun clearState() {
        RoomTestReset.resetCarDatabaseSingleton()
    }

    private fun remoteReceipt(
        serverId: String,
        provenance: String,
        createdAtMs: Long,
        originGuid: String? = null,
        unaccountedCents: Long? = null,
        lines: List<RemoteReceiptLine> = listOf(
            RemoteReceiptLine(
                name = "Milk",
                quantity = 1.0,
                unitPriceCents = 399L,
                totalPriceCents = 399L,
                estimatedCaloriesKcal = 150.0,
                estimatedProteinG = 8.0,
                estimatedCarbsG = 12.0,
                estimatedFatG = 5.0,
            ),
        ),
    ) = RemoteReceipt(
        serverId = serverId,
        store = "Whole Foods",
        purchaseDateEpochMs = createdAtMs,
        currency = "USD",
        totalCents = 399L,
        createdAtMs = createdAtMs,
        originGuid = originGuid,
        provenance = provenance,
        unaccountedCents = unaccountedCents,
        lines = lines,
    )

    @Test
    fun `a server-only receipt is inserted with its lines`() = runBlocking {
        val backend = FakePantryBackend()
        backend.rows += remoteReceipt("rcpt-1", "LLM_RECONCILED", 1_000L)

        val report = PantryReceiptsSync.pull(context, backend)

        assertEquals(1, report.inserted)
        assertEquals(0, report.alreadyPresent)
        assertEquals(1, report.linesInserted)
        val db = CarDatabase.getDatabase(context)
        val stored = db.pantryReceiptDao().getAll()
        assertEquals(1, stored.size)
        assertEquals("rcpt-1", stored.single().syncId)
        assertEquals("LLM_RECONCILED", stored.single().provenance)
        val lines = db.pantryLineItemDao().getForReceipt(stored.single().id)
        assertEquals(1, lines.size)
        assertEquals("Milk", lines.single().name)
    }

    @Test
    fun `unaccountedCents survives the pull exactly and forces UNRECONCILED provenance`() = runBlocking {
        val backend = FakePantryBackend()
        backend.rows += remoteReceipt("rcpt-2", "UNRECONCILED", 1_000L, unaccountedCents = 250L)

        PantryReceiptsSync.pull(context, backend)

        val stored = CarDatabase.getDatabase(context).pantryReceiptDao().getAll().single()
        assertEquals(250L, stored.unaccountedCents)
        assertEquals("UNRECONCILED", stored.provenance)
    }

    @Test
    fun `a healthy receipt carries no unaccountedCents`() = runBlocking {
        val backend = FakePantryBackend()
        backend.rows += remoteReceipt("rcpt-3", "LLM_RECONCILED", 1_000L)

        PantryReceiptsSync.pull(context, backend)

        val stored = CarDatabase.getDatabase(context).pantryReceiptDao().getAll().single()
        assertNull(stored.unaccountedCents)
    }

    @Test
    fun `an unrecognised provenance is refused, never guessed`() = runBlocking {
        val backend = FakePantryBackend()
        backend.rows += remoteReceipt("rcpt-4", "DETERMINISTIC", 1_000L)

        val report = PantryReceiptsSync.pull(context, backend)

        assertEquals(0, report.inserted)
        assertEquals(1, report.unrecognizedProvenance.size)
        assertTrue(report.unrecognizedProvenance.single().contains("DETERMINISTIC"))
        assertEquals(0, CarDatabase.getDatabase(context).pantryReceiptDao().getAll().size)
    }

    @Test
    fun `a migrated receipt already known locally by syncId equalling originGuid is not duplicated`() = runBlocking {
        val db = CarDatabase.getDatabase(context)
        db.pantryReceiptDao().insert(
            PantryReceipt(
                store = "Trader Joe's",
                purchaseDate = 500L,
                currency = LedgerCurrency.USD,
                totalCents = 100L,
                sourceImagePath = "/tmp/receipt.jpg",
                syncId = "local-guid-1",
            ),
        )
        val backend = FakePantryBackend()
        backend.rows += remoteReceipt("rcpt-5", "LLM_RECONCILED", 1_000L, originGuid = "local-guid-1")

        val report = PantryReceiptsSync.pull(context, backend)

        assertEquals(0, report.inserted)
        assertEquals(1, report.alreadyPresent)
        assertEquals(1, db.pantryReceiptDao().getAll().size)
    }

    @Test
    fun `a local-only receipt survives untouched`() = runBlocking {
        val db = CarDatabase.getDatabase(context)
        db.pantryReceiptDao().insert(
            PantryReceipt(
                store = "Local Only Store",
                purchaseDate = 500L,
                currency = LedgerCurrency.USD,
                totalCents = 100L,
                sourceImagePath = "/tmp/local.jpg",
                syncId = "local-only-guid",
            ),
        )
        val backend = FakePantryBackend() // server has nothing

        val report = PantryReceiptsSync.pull(context, backend)

        assertEquals(0, report.inserted)
        val stored = db.pantryReceiptDao().getAll()
        assertEquals(1, stored.size)
        assertEquals("Local Only Store", stored.single().store)
    }

    @Test
    fun `a restored receipt with no local photo carries no photo reference, never a broken one`() = runBlocking {
        val backend = FakePantryBackend()
        backend.rows += remoteReceipt("rcpt-6", "LLM_RECONCILED", 1_000L)

        PantryReceiptsSync.pull(context, backend)

        val stored = CarDatabase.getDatabase(context).pantryReceiptDao().getAll().single()
        assertNull(stored.photoObjectPath)
        assertEquals("", stored.sourceImagePath)
    }

    @Test
    fun `a second consecutive pull of the same server state is a true no-op`() = runBlocking {
        val backend = FakePantryBackend()
        backend.rows += remoteReceipt("rcpt-7", "LLM_RECONCILED", 1_000L)

        val first = PantryReceiptsSync.pull(context, backend)
        assertEquals(1, first.inserted)

        val db = CarDatabase.getDatabase(context)
        val beforeSecond = db.pantryReceiptDao().getAll()
        val second = PantryReceiptsSync.pull(context, backend)
        assertEquals(0, second.inserted)
        assertEquals(1, second.alreadyPresent)
        val afterSecond = db.pantryReceiptDao().getAll()
        assertEquals(beforeSecond, afterSecond)
    }
}

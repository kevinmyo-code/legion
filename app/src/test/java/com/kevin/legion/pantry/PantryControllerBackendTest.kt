package com.kevin.legion.pantry

import com.kevin.legion.backend.CommitOutcome
import com.kevin.legion.backend.MigratedReceipt
import com.kevin.legion.backend.PantryBackend
import com.kevin.legion.backend.PantryBackendException
import com.kevin.legion.backend.PantryReconcile
import com.kevin.legion.backend.RemoteReceipt
import com.kevin.legion.backend.RemoteReceiptLine
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.LedgerCurrency
import com.kevin.legion.data.local.PantryLineItem
import com.kevin.legion.data.local.PantryReceipt
import com.kevin.legion.engine.PayloadCodec
import com.kevin.legion.engine.RecordStore
import com.kevin.legion.engine.pantry.PantryAspectSeeder
import com.kevin.legion.testutil.RoomTestReset
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
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
 * PantryController's CONFIGURED path plus [PantryReconcile] (backend-erp Phase 4, pantry -
 * `.scratch/backend-erp/issues/05-migration-path.md`). Exercised entirely through
 * [PantryController.backendOverride] and an in-memory [FakePantryBackend] - never a real
 * SupabaseClient - same posture as `PlaceControllerBackendTest`. [PantryControllerTest] (this
 * package) covers the UNCONFIGURED (engine) path and is untouched by this ticket.
 */
@RunWith(RobolectricTestRunner::class)
class PantryControllerBackendTest {
    private val context = RuntimeEnvironment.getApplication()
    private val db get() = CarDatabase.getDatabase(context)

    private class FakePantryBackend(
        var commitFails: Boolean = false,
        var uploadFails: Boolean = false,
        var nextCommitOutcome: CommitOutcome? = null,
    ) : PantryBackend {
        val serverReceipts = mutableListOf<RemoteReceipt>()
        val committedShas = mutableSetOf<String>()
        var commitCalls = 0
        var uploadCalls = 0
        private var nextId = 1

        override suspend fun fetchActiveReceipts(): Result<List<RemoteReceipt>> = Result.success(serverReceipts.toList())

        override suspend fun commitReceipt(payload: String): Result<CommitOutcome> {
            if (commitFails) return Result.failure(PantryBackendException("simulated network failure"))
            val json = JSONObject(payload)
            val sha = json.getString("content_sha256")
            if (sha in committedShas) return Result.success(CommitOutcome.AlreadyCommitted)
            commitCalls++
            nextCommitOutcome?.let { outcome ->
                if (outcome is CommitOutcome.Committed) committedShas.add(sha)
                return Result.success(outcome)
            }
            val itemCount = json.getJSONArray("items").length()
            val id = "server-receipt-${nextId++}"
            committedShas.add(sha)
            return Result.success(CommitOutcome.Committed(receiptId = id, insertedLines = itemCount))
        }

        override suspend fun uploadMigratedReceipt(receipt: MigratedReceipt): Result<Boolean> {
            if (uploadFails) return Result.failure(PantryBackendException("simulated network failure"))
            uploadCalls++
            if (serverReceipts.any { it.originGuid == receipt.originGuid }) return Result.success(false)
            serverReceipts.add(
                RemoteReceipt(
                    serverId = "server-receipt-${nextId++}",
                    store = receipt.store,
                    purchaseDateEpochMs = receipt.purchaseDateEpochMs,
                    currency = receipt.currency,
                    totalCents = receipt.totalCents,
                    createdAtMs = System.currentTimeMillis(),
                    originGuid = receipt.originGuid,
                    // Mirrors SupabasePantryBackend's real rule: a non-null unaccountedCents is
                    // exactly what forces UNRECONCILED, both in the DB check constraint and here.
                    provenance = if (receipt.unaccountedCents != null) "UNRECONCILED" else "LLM_RECONCILED",
                    unaccountedCents = receipt.unaccountedCents,
                    lines = receipt.lines.map {
                        RemoteReceiptLine(
                            name = it.name,
                            quantity = it.quantity,
                            unitPriceCents = it.unitPriceCents,
                            totalPriceCents = it.totalPriceCents,
                            estimatedCaloriesKcal = it.estimatedCaloriesKcal,
                            estimatedProteinG = it.estimatedProteinG,
                            estimatedCarbsG = it.estimatedCarbsG,
                            estimatedFatG = it.estimatedFatG,
                        )
                    },
                ),
            )
            return Result.success(true)
        }
    }

    @Before
    fun clearState() {
        RoomTestReset.resetCarDatabaseSingleton()
    }

    @After
    fun clearOverride() {
        // Drains ArchTaskExecutor's disk-IO pool before anything else in this @After - see
        // RoomTestReset's class doc comment and
        // .scratch/hardening/issues/13-the-suite-is-green-by-luck.md: a DAO write earlier in
        // this test can leave a Room InvalidationTracker refresh in flight, and it must finish
        // before this test method returns or it races Robolectric's per-method reset.
        RoomTestReset.drainArchDiskIoPool()

        PantryController.backendOverride = null
    }

    private fun success(
        store: String = "Trader Joe's",
        purchaseDate: Long = System.currentTimeMillis(),
        currency: LedgerCurrency = LedgerCurrency.USD,
        totalCents: Long = 1000L,
        subtotalCents: Long? = null,
        taxCents: Long? = null,
        otherChargesCents: Long? = null,
        items: List<PantryLineItem> = listOf(PantryLineItem(receiptId = 0, name = "milk", totalPriceCents = 1000L)),
    ): PantryIngestResult.Success = PantryIngestResult.Success(
        receipt = PantryReceipt(
            store = store, purchaseDate = purchaseDate, currency = currency,
            totalCents = totalCents, sourceImagePath = "/data/pantry_receipts/1.jpg",
        ),
        items = items,
        subtotalCents = subtotalCents,
        taxCents = taxCents,
        otherChargesCents = otherChargesCents,
    )

    // ------------------------------------------------------------------------- commitReceiptRemote

    @Test
    fun `a successful commit writes the replica exactly once`() = runBlocking {
        val backend = FakePantryBackend()
        PantryController.backendOverride = backend

        val outcome = PantryController.commitReceiptRemote(context, backend, "photo-a".toByteArray(), success(store = "Walmart"))

        assertTrue(outcome.success)
        assertEquals(1, backend.commitCalls)
        val receipts = db.pantryReceiptDao().getAll()
        assertEquals(1, receipts.size)
        assertEquals("Walmart", receipts.single().store)
        assertEquals(1, db.pantryLineItemDao().getAll().size)
    }

    @Test
    fun `a FAILED commit leaves the replica untouched and returns no success wording`() = runBlocking {
        val backend = FakePantryBackend(commitFails = true)
        PantryController.backendOverride = backend

        val outcome = PantryController.commitReceiptRemote(context, backend, "photo-a".toByteArray(), success())

        assertFalse(outcome.success)
        assertTrue(
            "a failed commit must say in words that it did not save",
            outcome.message.contains("couldn't reach") || outcome.message.contains("try again"),
        )
        assertTrue(db.pantryReceiptDao().getAll().isEmpty())
    }

    @Test
    fun `a good receipt is already stored, the next commit fails, and the stored receipt survives unchanged`() = runBlocking {
        val backend = FakePantryBackend()
        PantryController.backendOverride = backend
        PantryController.commitReceiptRemote(context, backend, "photo-a".toByteArray(), success(store = "Walmart"))
        val before = db.pantryReceiptDao().getAll().single()

        backend.commitFails = true
        val outcome = PantryController.commitReceiptRemote(context, backend, "photo-b".toByteArray(), success(store = "Target"))

        assertFalse(outcome.success)
        val after = db.pantryReceiptDao().getAll()
        assertEquals("the previously stored receipt must survive, and no second one must appear", 1, after.size)
        assertEquals("Walmart", after.single().store)
        assertEquals(before.id, after.single().id)
    }

    @Test
    fun `a gate QUARANTINE is reported as a quarantine, not a transport error, and writes nothing`() = runBlocking {
        val backend = FakePantryBackend(nextCommitOutcome = CommitOutcome.Quarantined("the numbers didn't tie out"))
        PantryController.backendOverride = backend

        val outcome = PantryController.commitReceiptRemote(context, backend, "photo-a".toByteArray(), success())

        assertFalse(outcome.success)
        assertEquals("the numbers didn't tie out", outcome.message)
        assertTrue(db.pantryReceiptDao().getAll().isEmpty())
    }

    @Test
    fun `an idempotent re-commit of the same content_sha256 does not duplicate`() = runBlocking {
        val backend = FakePantryBackend()
        PantryController.backendOverride = backend
        val bytes = "same-photo".toByteArray()

        val first = PantryController.commitReceiptRemote(context, backend, bytes, success(store = "Walmart"))
        val second = PantryController.commitReceiptRemote(context, backend, bytes, success(store = "Walmart"))

        assertTrue(first.success)
        assertTrue(second.success)
        assertEquals(1, db.pantryReceiptDao().getAll().size)
    }

    // --------------------------------------------------------------------------------------- reads

    @Test
    fun `reads come from the replica when configured`() = runBlocking {
        val backend = FakePantryBackend()
        PantryController.backendOverride = backend
        val receiptId = db.pantryReceiptDao().insert(
            PantryReceipt(store = "Aldi", purchaseDate = 500L, currency = LedgerCurrency.USD, totalCents = 700L, sourceImagePath = ""),
        )
        db.pantryLineItemDao().insertAll(listOf(PantryLineItem(receiptId = receiptId, name = "eggs", totalPriceCents = 700L)))

        val receipts = PantryController.recentReceiptsWithItems(context)

        assertEquals(1, receipts.size)
        assertEquals("Aldi", receipts.single().first.store)
        assertEquals(0, backend.commitCalls)
    }

    // ------------------------------------------------------------------------------ PantryReconcile

    private suspend fun writeEngineReceipt(result: PantryIngestResult.Success): String {
        val written = PantryController.writeReceipt(context, result)
        assertTrue(written.success)
        val sch = PantryAspectSeeder.ensureSeeded(context)
        val record = db.engineRecordDao().activeByRecordType(sch.receipt.recordTypeId)
            .first { PayloadCodec.readString(JSONObject(it.payload), sch.receipt.fieldIds.getValue(PantryAspectSeeder.FIELD_STORE)) == result.receipt.store }
        return record.guid
    }

    @Test
    fun `PantryReconcile uploads a reconciling engine receipt and fills the replica`() = runBlocking {
        writeEngineReceipt(success(store = "Costco", totalCents = 5000L, subtotalCents = null, items = listOf(
            PantryLineItem(receiptId = 0, name = "bulk rice", totalPriceCents = 5000L),
        )))
        val backend = FakePantryBackend()

        val report = PantryReconcile.run(context, backend).getOrThrow()

        assertEquals(1, report.engineCount)
        assertEquals(1, report.uploaded)
        assertTrue(report.uploadedUnreconciled.isEmpty())
        assertTrue(report.rejectedOveraccounted.isEmpty())
        assertTrue(report.isClean)
        assertEquals("LLM_RECONCILED", backend.serverReceipts.single().provenance)
        assertEquals(null, backend.serverReceipts.single().unaccountedCents)
        assertEquals(1, db.pantryReceiptDao().getAll().size)
        assertEquals("Costco", db.pantryReceiptDao().getAll().single().store)
    }

    @Test
    fun `PantryReconcile is idempotent across two runs`() = runBlocking {
        writeEngineReceipt(success(store = "Costco", totalCents = 5000L, items = listOf(
            PantryLineItem(receiptId = 0, name = "bulk rice", totalPriceCents = 5000L),
        )))
        val backend = FakePantryBackend()

        val first = PantryReconcile.run(context, backend).getOrThrow()
        val second = PantryReconcile.run(context, backend).getOrThrow()

        assertEquals(1, first.uploaded)
        assertEquals(0, second.uploaded, )
        assertEquals(1, second.serverCountAfter)
        assertEquals(1, second.replicaCountAfter)
        assertTrue(second.isClean)
    }

    @Test
    fun `a failed migration upload fails the whole run rather than reporting a partial count`() = runBlocking {
        writeEngineReceipt(success(store = "Costco", totalCents = 5000L, items = listOf(
            PantryLineItem(receiptId = 0, name = "bulk rice", totalPriceCents = 5000L),
        )))
        val backend = FakePantryBackend(uploadFails = true)

        // The upload itself fails, so nothing lands server-side and the whole run fails outright -
        // a partial upload must never be reported as a report with a lower count.
        val result = PantryReconcile.run(context, backend)
        assertTrue(result.isFailure)
    }

    @Test
    fun `a stored engine receipt that charges more than its lines explain uploads UNRECONCILED with the residual, and is reported separately`() = runBlocking {
        // Written honestly (gate passed at write time), then hand-corrupted at the engine level to
        // simulate the exact real-world shape ticket 08 exists for: a total that no longer matches
        // its lines, with no subtotal/tax anchor stored to explain the gap.
        val guid = writeEngineReceipt(success(store = "Costco", totalCents = 5000L, items = listOf(
            PantryLineItem(receiptId = 0, name = "bulk rice", totalPriceCents = 5000L),
        )))
        val sch = PantryAspectSeeder.ensureSeeded(context)
        val record = db.engineRecordDao().activeByRecordType(sch.receipt.recordTypeId).single { it.guid == guid }
        val recordStore = RecordStore(db.engineRecordDao(), db.fieldDefDao(), db.recordTypeDao())
        recordStore.update(record.id, mapOf(sch.receipt.fieldIds.getValue(PantryAspectSeeder.FIELD_TOTAL) to 5802L))

        val backend = FakePantryBackend()
        val report = PantryReconcile.run(context, backend).getOrThrow()

        assertEquals(1, report.engineCount)
        // Uploaded, not counted in the ordinary [uploaded] count - a different provenance entirely.
        assertEquals(0, report.uploaded)
        assertEquals(1, report.uploadedUnreconciled.size)
        assertTrue(report.uploadedUnreconciled.single().contains("Costco"))
        assertTrue(report.uploadedUnreconciled.single().contains("802"))
        assertTrue(report.rejectedOveraccounted.isEmpty())
        val serverReceipt = backend.serverReceipts.single()
        assertEquals("UNRECONCILED", serverReceipt.provenance)
        assertEquals(802L, serverReceipt.unaccountedCents)
        // A receipt this ticket explicitly authorises DID land server-side, so it is no longer a
        // one-sided diff - unlike the old skip behaviour, this receipt's guid does NOT show up
        // here even though it is unreconciled.
        assertTrue(report.onlyOnEngine.isEmpty())
        assertTrue(report.onlyOnServer.isEmpty())
        assertTrue(report.isClean)
    }

    @Test
    fun `a stored engine receipt whose lines exceed its total is rejected outright, never given a negative or zero unaccounted_cents`() = runBlocking {
        val guid = writeEngineReceipt(success(store = "Costco", totalCents = 5000L, items = listOf(
            PantryLineItem(receiptId = 0, name = "bulk rice", totalPriceCents = 5000L),
        )))
        val sch = PantryAspectSeeder.ensureSeeded(context)
        val record = db.engineRecordDao().activeByRecordType(sch.receipt.recordTypeId).single { it.guid == guid }
        val recordStore = RecordStore(db.engineRecordDao(), db.fieldDefDao(), db.recordTypeDao())
        // The total now reads LOWER than the lines it is supposed to cover - the lines claim more
        // money than the receipt actually charged.
        recordStore.update(record.id, mapOf(sch.receipt.fieldIds.getValue(PantryAspectSeeder.FIELD_TOTAL) to 4000L))

        val backend = FakePantryBackend()
        val report = PantryReconcile.run(context, backend).getOrThrow()

        assertEquals(1, report.engineCount)
        assertEquals(0, report.uploaded)
        assertTrue(report.uploadedUnreconciled.isEmpty())
        assertEquals(1, report.rejectedOveraccounted.size)
        assertTrue(report.rejectedOveraccounted.single().contains("Costco"))
        assertTrue(db.pantryReceiptDao().getAll().isEmpty())
        assertTrue(backend.serverReceipts.isEmpty())
        // Never uploaded at all - the rejected receipt's guid must show up as a genuine one-sided
        // diff entry, not vanish into a falsely-clean report.
        assertEquals(listOf(guid), report.onlyOnEngine)
        assertTrue(report.onlyOnServer.isEmpty())
        assertFalse(report.isClean)
    }
}

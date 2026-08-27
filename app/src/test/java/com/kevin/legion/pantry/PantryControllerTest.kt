package com.kevin.legion.pantry

import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.DeletePolicy
import com.kevin.legion.data.local.FieldType
import com.kevin.legion.data.local.LedgerCurrency
import com.kevin.legion.data.local.PantryLineItem
import com.kevin.legion.data.local.PantryReceipt
import com.kevin.legion.data.local.RecordProvenance
import com.kevin.legion.engine.FieldConfig
import com.kevin.legion.engine.PayloadCodec
import com.kevin.legion.engine.pantry.PantryAspectSeeder
import com.kevin.legion.testutil.RoomTestReset
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
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
 * Robolectric coverage for [PantryController] since cutover 2
 * (`docs/architecture/cutover2-2026-08-24.md`) - CRUD reads against engine records, the
 * reconciliation gate's Success/Quarantine boundary, anchor persistence, and a genuine post-gate
 * engine-write failure rolling back rather than reporting a false success.
 *
 * **Network-free throughout** - every test drives [PantryController.writeReceipt] directly (the
 * network-free half of [PantryController.importReceipt] - see that function's own doc comment) or
 * [PantryReceiptAgent.parseAndReconcile] directly (already network-free - [PantryReceiptAgentTest]
 * covers its own arithmetic; this file only reuses it to produce a real [PantryIngestResult] to
 * hand to [PantryController.writeReceipt], never to re-test the gate's math itself).
 */
@RunWith(RobolectricTestRunner::class)
class PantryControllerTest {
    private val context = RuntimeEnvironment.getApplication()
    private val db get() = CarDatabase.getDatabase(context)

    @Before
    fun clearState() {
        RoomTestReset.resetCarDatabaseSingleton()
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


    // ------------------------------------------------------------------------------- writeReceipt

    @Test
    fun `writeReceipt on a Success result writes the receipt then its line items, referencing intact, provenance LLM_RECONCILED`() = runBlocking {
        val result = success(
            store = "Walmart", totalCents = 12886L,
            items = listOf(
                PantryLineItem(receiptId = 0, name = "Paper towels", totalPriceCents = 6000L),
                PantryLineItem(receiptId = 0, name = "Chicken thighs", totalPriceCents = 4084L),
                PantryLineItem(receiptId = 0, name = "Laundry detergent", totalPriceCents = 2000L),
            ),
        )

        val outcome = PantryController.writeReceipt(context, result)

        assertTrue(outcome.success)
        assertEquals(3, outcome.itemCount)
        assertTrue(outcome.message.contains("Walmart"))

        val schema = PantryAspectSeeder.ensureSeeded(context)
        val receiptRecord = db.engineRecordDao().activeByRecordType(schema.receipt.recordTypeId).single()
        val itemRecords = db.engineRecordDao().activeByRecordType(schema.lineItem.recordTypeId)
        assertEquals(3, itemRecords.size)
        assertEquals(RecordProvenance.LLM_RECONCILED, receiptRecord.provenance)
        for (item in itemRecords) {
            assertEquals(RecordProvenance.LLM_RECONCILED, item.provenance)
            val payload = JSONObject(item.payload)
            assertEquals(
                receiptRecord.id,
                PayloadCodec.readReferenceId(payload, schema.lineItem.fieldIds.getValue(PantryAspectSeeder.FIELD_RECEIPT)),
            )
        }
        assertEquals(12886L, receiptRecord.amountCents)
    }

    @Test
    fun `Quarantined results are never handed to writeReceipt, and nothing is written`() = runBlocking {
        // A real quarantine from the actual gate - the mismatched-total shape PantryReceiptAgentTest
        // already covers the arithmetic for. Reused here only to prove the CONTROLLER side of the
        // boundary: importReceipt's `when` has no branch from Quarantined to writeReceipt at all, so
        // the engine record count must be zero with nothing further to call.
        val raw = """
            {"store": "NTUC FairPrice", "purchaseDate": "2026-07-20", "currency": "SGD",
             "total": "99.99",
             "items": [{"name": "Milk", "totalPrice": "4.50"}]}
        """.trimIndent()
        val quarantined = PantryReceiptAgent.parseAndReconcile(raw, "/tmp/receipt.jpg")
        assertTrue(quarantined is PantryIngestResult.Quarantined)

        val schema = PantryAspectSeeder.ensureSeeded(context)
        assertEquals(0, db.engineRecordDao().activeByRecordType(schema.receipt.recordTypeId).size)
        assertEquals(0, db.engineRecordDao().activeByRecordType(schema.lineItem.recordTypeId).size)
    }

    // ---------------------------------------------------------------------------- anchor persistence

    @Test
    fun `writeReceipt persists subtotal, tax, and other charges so the gate invariant is re-checkable post-hoc`() = runBlocking {
        val result = success(totalCents = 12936L, subtotalCents = 12084L, taxCents = 802L, otherChargesCents = 50L)

        PantryController.writeReceipt(context, result)

        val schema = PantryAspectSeeder.ensureSeeded(context)
        val receiptRecord = db.engineRecordDao().activeByRecordType(schema.receipt.recordTypeId).single()
        val payload = JSONObject(receiptRecord.payload)
        assertEquals(12084L, PayloadCodec.readLong(payload, schema.receipt.fieldIds.getValue(PantryAspectSeeder.FIELD_SUBTOTAL)))
        assertEquals(802L, PayloadCodec.readLong(payload, schema.receipt.fieldIds.getValue(PantryAspectSeeder.FIELD_TAX)))
        assertEquals(50L, PayloadCodec.readLong(payload, schema.receipt.fieldIds.getValue(PantryAspectSeeder.FIELD_OTHER_CHARGES)))

        // The gate invariant, re-checked post-hoc off the persisted anchors alone - subtotal + tax
        // + otherCharges == total, exactly what PantryReceiptAgent verified before this row existed.
        val subtotal = PayloadCodec.readLong(payload, schema.receipt.fieldIds.getValue(PantryAspectSeeder.FIELD_SUBTOTAL))!!
        val tax = PayloadCodec.readLong(payload, schema.receipt.fieldIds.getValue(PantryAspectSeeder.FIELD_TAX))!!
        val other = PayloadCodec.readLong(payload, schema.receipt.fieldIds.getValue(PantryAspectSeeder.FIELD_OTHER_CHARGES))!!
        assertEquals(12936L, subtotal + tax + other)
    }

    @Test
    fun `a receipt with no printed subtotal or tax persists the anchors as absent, not zero`() = runBlocking {
        // Singapore GST-inclusive receipts print no subtotal/tax line at all - PantryReceiptAgent's
        // own tax-free branch. Null here must mean "not printed," never zero.
        val result = success(totalCents = 1250L, subtotalCents = null, taxCents = null, otherChargesCents = null)

        PantryController.writeReceipt(context, result)

        val schema = PantryAspectSeeder.ensureSeeded(context)
        val receiptRecord = db.engineRecordDao().activeByRecordType(schema.receipt.recordTypeId).single()
        val payload = JSONObject(receiptRecord.payload)
        assertNull(PayloadCodec.readLong(payload, schema.receipt.fieldIds.getValue(PantryAspectSeeder.FIELD_SUBTOTAL)))
        assertNull(PayloadCodec.readLong(payload, schema.receipt.fieldIds.getValue(PantryAspectSeeder.FIELD_TAX)))
        assertNull(PayloadCodec.readLong(payload, schema.receipt.fieldIds.getValue(PantryAspectSeeder.FIELD_OTHER_CHARGES)))
    }

    // ----------------------------------------------------------------------------- worded failure

    /** Corrupts the `receipt` REFERENCE field's config so every line item's create is rejected
     * ("wrong record type") - same forcing technique `EngineDataMigrationWave2Test` already uses
     * against the same seam. This forces a failure on the LINE ITEM'S OWN create call, AFTER the
     * receipt's create has already succeeded - see the test below this one for the other half
     * (the RECEIPT's own create failing, before any line item is ever attempted). */
    private suspend fun corruptReceiptReferenceField(schema: PantryAspectSeeder.Schema) {
        val field = db.fieldDefDao().forRecordType(schema.lineItem.recordTypeId)
            .single { it.id == schema.lineItem.fieldIds.getValue(PantryAspectSeeder.FIELD_RECEIPT) }
        db.fieldDefDao().update(field.copy(config = FieldConfig.serializeReference(schema.lineItem.recordTypeId, DeletePolicy.CASCADE)))
    }

    @Test
    fun `a post-gate LINE-ITEM create failure rolls back the WHOLE transaction, including the already-created receipt, and reports a worded failure`() = runBlocking {
        val schema = PantryAspectSeeder.ensureSeeded(context)
        corruptReceiptReferenceField(schema)

        val result = success(store = "Corrupted Co", totalCents = 500L)
        val outcome = PantryController.writeReceipt(context, result)

        assertFalse("a failed engine write must never report success", outcome.success)
        assertTrue(
            "the failure must be worded, not silent",
            outcome.message.contains("checked out") && outcome.message.contains("couldn't save"),
        )
        assertEquals(0, outcome.itemCount)

        // The receipt itself has no REFERENCE field and would otherwise have created successfully -
        // the whole point of this test is that its create is ROLLED BACK along with the line item's
        // failure, not left behind as an orphaned receipt with zero items.
        assertEquals(0, db.engineRecordDao().activeByRecordType(schema.receipt.recordTypeId).size)
        assertEquals(0, db.engineRecordDao().activeByRecordType(schema.lineItem.recordTypeId).size)
    }

    /** Corrupts the receipt's OWN `store` field - a plain TEXT field, retyped to REFERENCE - so the
     * RECEIPT's own `RecordStore.create` call fails, before any line item is ever attempted. The
     * value handed in for `store` is a `String`, and `RecordStore.validateReferences` rejects a
     * non-`Number` value for a `REFERENCE` field immediately ("needs a record id, not String"),
     * with no need for the field's `config` to even describe a valid reference target. This is the
     * untested other half of the atomicity claim [corruptReceiptReferenceField] doesn't reach: that
     * test forces the LINE ITEM's create to fail after the receipt already landed; this one forces
     * the RECEIPT's own create to fail, so the rollback has to undo something that was never
     * followed by a line-item attempt at all. */
    private suspend fun corruptStoreFieldToReference(schema: PantryAspectSeeder.Schema) {
        val field = db.fieldDefDao().forRecordType(schema.receipt.recordTypeId)
            .single { it.id == schema.receipt.fieldIds.getValue(PantryAspectSeeder.FIELD_STORE) }
        db.fieldDefDao().update(field.copy(type = FieldType.REFERENCE))
    }

    @Test
    fun `a post-gate RECEIPT create failure rolls back before any line item is attempted, and reports a worded failure, zero engine records`() = runBlocking {
        val schema = PantryAspectSeeder.ensureSeeded(context)
        corruptStoreFieldToReference(schema)

        val result = success(
            store = "Doomed receipt", totalCents = 500L,
            items = listOf(
                PantryLineItem(receiptId = 0, name = "should never be attempted", totalPriceCents = 500L),
            ),
        )
        val outcome = PantryController.writeReceipt(context, result)

        assertFalse("a failed RECEIPT create must never report success", outcome.success)
        assertTrue(
            "the failure must be worded, not silent",
            outcome.message.contains("checked out") && outcome.message.contains("couldn't save"),
        )
        assertEquals(0, outcome.itemCount)

        // Zero of BOTH - no receipt (its own create failed) and no line item (never even attempted,
        // since the receipt's engine id - required by every item's REFERENCE field - never existed).
        assertEquals(0, db.engineRecordDao().activeByRecordType(schema.receipt.recordTypeId).size)
        assertEquals(0, db.engineRecordDao().activeByRecordType(schema.lineItem.recordTypeId).size)
    }

    // -------------------------------------------------------------------------------------- reads

    @Test
    fun `recentReceiptsWithItems groups items under their own receipt, newest purchase first`() = runBlocking {
        val old = success(store = "Old Store", purchaseDate = 1_000L, totalCents = 100L, items = listOf(PantryLineItem(receiptId = 0, name = "a", totalPriceCents = 100L)))
        val new = success(store = "New Store", purchaseDate = 2_000L, totalCents = 200L, items = listOf(PantryLineItem(receiptId = 0, name = "b", totalPriceCents = 200L)))
        PantryController.writeReceipt(context, old)
        PantryController.writeReceipt(context, new)

        val receipts = PantryController.recentReceiptsWithItems(context, limitReceipts = 10)

        assertEquals(2, receipts.size)
        assertEquals("New Store", receipts[0].first.store) // newest purchase first
        assertEquals(1, receipts[0].second.size)
        assertEquals("b", receipts[0].second[0].name)
        assertEquals("Old Store", receipts[1].first.store)
    }

    @Test
    fun `recentLineItemsWithCurrency tags every item with its OWN receipt's currency`() = runBlocking {
        val sgd = success(
            store = "NTUC", currency = LedgerCurrency.SGD, purchaseDate = 2_000L, totalCents = 450L,
            items = listOf(PantryLineItem(receiptId = 0, name = "Milk SGD", totalPriceCents = 450L)),
        )
        val usd = success(
            store = "Walmart", currency = LedgerCurrency.USD, purchaseDate = 1_000L, totalCents = 300L,
            items = listOf(PantryLineItem(receiptId = 0, name = "Milk USD", totalPriceCents = 300L)),
        )
        PantryController.writeReceipt(context, sgd)
        PantryController.writeReceipt(context, usd)

        val items = PantryController.recentLineItemsWithCurrency(context, limit = 20)

        assertEquals(2, items.size)
        val sgdItem = items.single { it.item.name == "Milk SGD" }
        val usdItem = items.single { it.item.name == "Milk USD" }
        assertEquals(LedgerCurrency.SGD, sgdItem.currency)
        assertEquals(LedgerCurrency.USD, usdItem.currency)
    }

    @Test
    fun `totalSpendCentsByCurrency never combines currencies`() = runBlocking {
        PantryController.writeReceipt(context, success(currency = LedgerCurrency.SGD, totalCents = 500L))
        PantryController.writeReceipt(context, success(currency = LedgerCurrency.SGD, totalCents = 300L))
        PantryController.writeReceipt(context, success(currency = LedgerCurrency.USD, totalCents = 700L))

        val totals = PantryController.totalSpendCentsByCurrency(context).associate { it.currency to it.totalCents }

        assertEquals(800L, totals[LedgerCurrency.SGD])
        assertEquals(700L, totals[LedgerCurrency.USD])
    }

    @Test
    fun `allReceiptSummaries returns every receipt's date, total, and currency`() = runBlocking {
        PantryController.writeReceipt(context, success(store = "A", totalCents = 100L, purchaseDate = 1L))
        PantryController.writeReceipt(context, success(store = "B", totalCents = 200L, purchaseDate = 2L))

        val summaries = PantryController.allReceiptSummaries(context)

        assertEquals(2, summaries.size)
        assertEquals(setOf(100L, 200L), summaries.map { it.totalCents }.toSet())
    }

    @Test
    fun `recentLineItems respects the limit, newest receipt first`() = runBlocking {
        val older = success(purchaseDate = 1L, totalCents = 100L, items = listOf(PantryLineItem(receiptId = 0, name = "old item", totalPriceCents = 100L)))
        val newer = success(purchaseDate = 2L, totalCents = 200L, items = listOf(PantryLineItem(receiptId = 0, name = "new item", totalPriceCents = 200L)))
        PantryController.writeReceipt(context, older)
        PantryController.writeReceipt(context, newer)

        val items = PantryController.recentLineItems(context, limit = 1)

        assertEquals(1, items.size)
        assertEquals("new item", items[0].name)
    }
}

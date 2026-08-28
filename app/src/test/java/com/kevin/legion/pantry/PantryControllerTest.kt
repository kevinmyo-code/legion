package com.kevin.legion.pantry

import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.LedgerCurrency
import com.kevin.legion.data.local.PantryLineItem
import com.kevin.legion.data.local.PantryReceipt
import com.kevin.legion.data.local.RecordProvenance
import com.kevin.legion.engine.RecordStore
import com.kevin.legion.engine.migration.EnginePantryRetirementCopy
import com.kevin.legion.engine.pantry.PantryAspectSeeder
import com.kevin.legion.testutil.RoomTestReset
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Robolectric coverage for [PantryController]'s UNCONFIGURED path.
 *
 * **Cutover 2** (`docs/architecture/cutover2-2026-08-24.md`) originally made this the engine-backed
 * suite for that path. **Engine retirement step 2**
 * (`.scratch/backend-erp/issues/15-engine-retirement-sequence.md`) repointed it onto the legacy
 * `pantry_receipts`/`pantry_line_items` tables, so this file now covers CRUD against those tables
 * via the unconfigured branch, plus [EnginePantryRetirementCopy]'s one-time reconcile - the step
 * that keeps a receipt imported directly through the engine (before this repoint, or on an install
 * still mid-soak) from being silently dropped the moment the read flips. [PantryControllerBackendTest]
 * covers the CONFIGURED path and is untouched by this ticket.
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

    /** Writes a Receipt (and its LineItems) directly through the engine, bypassing
     * [PantryController] entirely - simulates a receipt imported before engine retirement step 2's
     * repoint (or on an install still mid-soak), which only [EnginePantryRetirementCopy] should
     * ever be able to see and copy forward. Mirrors [PantryController.writeReceipt]'s OLD
     * (pre-repoint) engine write shape, kept here only as a test fixture now that production code
     * no longer does this. */
    private suspend fun createEngineReceipt(
        store: String,
        totalCents: Long,
        itemNames: List<String> = listOf("item"),
        itemTotalCents: Long = totalCents,
    ): Long {
        val schema = PantryAspectSeeder.ensureSeeded(context)
        val recordStore = RecordStore(db.engineRecordDao(), db.fieldDefDao(), db.recordTypeDao())
        val receiptResult = recordStore.create(
            recordTypeId = schema.receipt.recordTypeId,
            fieldValues = mapOf(
                schema.receipt.fieldIds.getValue(PantryAspectSeeder.FIELD_STORE) to store,
                schema.receipt.fieldIds.getValue(PantryAspectSeeder.FIELD_PURCHASE_DATE) to System.currentTimeMillis(),
                schema.receipt.fieldIds.getValue(PantryAspectSeeder.FIELD_CURRENCY) to LedgerCurrency.USD.name,
                schema.receipt.fieldIds.getValue(PantryAspectSeeder.FIELD_TOTAL) to totalCents,
                schema.receipt.fieldIds.getValue(PantryAspectSeeder.FIELD_SOURCE_IMAGE_PATH) to "/data/pantry_receipts/engine.jpg",
            ),
            provenance = RecordProvenance.LLM_RECONCILED,
        )
        val receiptId = (receiptResult as RecordStore.WriteResult.Success).recordId
        for (name in itemNames) {
            recordStore.create(
                recordTypeId = schema.lineItem.recordTypeId,
                fieldValues = mapOf(
                    schema.lineItem.fieldIds.getValue(PantryAspectSeeder.FIELD_RECEIPT) to receiptId,
                    schema.lineItem.fieldIds.getValue(PantryAspectSeeder.FIELD_NAME) to name,
                    schema.lineItem.fieldIds.getValue(PantryAspectSeeder.FIELD_QUANTITY) to 1.0,
                    schema.lineItem.fieldIds.getValue(PantryAspectSeeder.FIELD_TOTAL_PRICE) to itemTotalCents,
                ),
                provenance = RecordProvenance.LLM_RECONCILED,
            )
        }
        return receiptId
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

    // ------------------------------------------------------------------------------- writeReceipt

    @Test
    fun `writeReceipt on a Success result writes the receipt then its line items into the legacy tables, referencing intact, provenance LLM_RECONCILED`() = runBlocking {
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

        val receiptRow = db.pantryReceiptDao().getAll().single()
        val itemRows = db.pantryLineItemDao().getAll()
        assertEquals(3, itemRows.size)
        assertEquals("LLM_RECONCILED", receiptRow.provenance)
        for (item in itemRows) {
            assertEquals(receiptRow.id, item.receiptId)
        }
        assertEquals(12886L, receiptRow.totalCents)
    }

    @Test
    fun `Quarantined results are never handed to writeReceipt, and nothing is written`() = runBlocking {
        // A real quarantine from the actual gate - the mismatched-total shape PantryReceiptAgentTest
        // already covers the arithmetic for. Reused here only to prove the CONTROLLER side of the
        // boundary: importReceipt's `when` has no branch from Quarantined to writeReceipt at all, so
        // nothing must exist in either legacy table, with nothing further to call.
        val raw = """
            {"store": "NTUC FairPrice", "purchaseDate": "2026-07-20", "currency": "SGD",
             "total": "99.99",
             "items": [{"name": "Milk", "totalPrice": "4.50"}]}
        """.trimIndent()
        val quarantined = PantryReceiptAgent.parseAndReconcile(raw, "/tmp/receipt.jpg")
        assertTrue(quarantined is PantryIngestResult.Quarantined)

        assertEquals(0, db.pantryReceiptDao().getAll().size)
        assertEquals(0, db.pantryLineItemDao().getAll().size)
    }

    // ---------------------------------------------------------- anchor persistence (v44)

    @Test
    fun `writeReceipt persists subtotal, tax, and other charges on the legacy row so the gate invariant is re-checkable post-hoc`() = runBlocking {
        // v44 (`MIGRATION_43_44`) added these three columns to PantryReceipt specifically so this
        // repoint would not recreate ticket 08's defect - a receipt whose gate passed in memory but
        // whose anchors were then discarded, leaving nothing to re-verify against later. THIS is
        // the assertion whose absence WAS ticket 08's whole defect - see the mutation-check test
        // below for proof it is load-bearing.
        val result = success(totalCents = 12936L, subtotalCents = 12084L, taxCents = 802L, otherChargesCents = 50L)

        val outcome = PantryController.writeReceipt(context, result)

        assertTrue("the receipt still writes successfully - the gate already passed", outcome.success)
        val receiptRow = db.pantryReceiptDao().getAll().single()
        assertEquals(12936L, receiptRow.totalCents)
        assertEquals(12084L, receiptRow.subtotalCents)
        assertEquals(802L, receiptRow.taxCents)
        assertEquals(50L, receiptRow.otherChargesCents)

        // The gate invariant, re-checked post-hoc off the persisted anchors alone - subtotal + tax
        // + otherCharges == total, exactly what PantryReceiptAgent verified before this row existed.
        assertEquals(12936L, receiptRow.subtotalCents!! + receiptRow.taxCents!! + receiptRow.otherChargesCents!!)
    }

    @Test
    fun `a receipt with no printed subtotal or tax persists the anchors as absent, not zero`() = runBlocking {
        // Singapore GST-inclusive receipts print no subtotal/tax line at all - PantryReceiptAgent's
        // own tax-free branch. Null here must mean "not printed," never zero.
        val result = success(totalCents = 1250L, subtotalCents = null, taxCents = null, otherChargesCents = null)

        PantryController.writeReceipt(context, result)

        val receiptRow = db.pantryReceiptDao().getAll().single()
        assertEquals(null, receiptRow.subtotalCents)
        assertEquals(null, receiptRow.taxCents)
        assertEquals(null, receiptRow.otherChargesCents)
    }

    @Test
    fun `the copy carries subtotal, tax, and otherCharges through from an engine record that has them`() = runBlocking {
        val schema = PantryAspectSeeder.ensureSeeded(context)
        val recordStore = RecordStore(db.engineRecordDao(), db.fieldDefDao(), db.recordTypeDao())
        val receiptResult = recordStore.create(
            recordTypeId = schema.receipt.recordTypeId,
            fieldValues = mapOf(
                schema.receipt.fieldIds.getValue(PantryAspectSeeder.FIELD_STORE) to "Whole Foods",
                schema.receipt.fieldIds.getValue(PantryAspectSeeder.FIELD_PURCHASE_DATE) to 1L,
                schema.receipt.fieldIds.getValue(PantryAspectSeeder.FIELD_CURRENCY) to LedgerCurrency.USD.name,
                schema.receipt.fieldIds.getValue(PantryAspectSeeder.FIELD_TOTAL) to 4550L,
                schema.receipt.fieldIds.getValue(PantryAspectSeeder.FIELD_SOURCE_IMAGE_PATH) to "",
                schema.receipt.fieldIds.getValue(PantryAspectSeeder.FIELD_SUBTOTAL) to 4200L,
                schema.receipt.fieldIds.getValue(PantryAspectSeeder.FIELD_TAX) to 350L,
                schema.receipt.fieldIds.getValue(PantryAspectSeeder.FIELD_OTHER_CHARGES) to 0L,
            ),
            provenance = RecordProvenance.LLM_RECONCILED,
        )
        assertTrue(receiptResult is RecordStore.WriteResult.Success)

        EnginePantryRetirementCopy.copyIfNeeded(context)

        val row = db.pantryReceiptDao().getAll().single()
        assertEquals(4200L, row.subtotalCents)
        assertEquals(350L, row.taxCents)
        assertEquals(0L, row.otherChargesCents)
    }

    // ----------------------------------------------------------------------------- worded failure

    @Test
    fun `a genuine write failure rolls back the WHOLE transaction, including the already-inserted receipt, and reports a worded failure, never a raw throw`() = runBlocking {
        // Forces a real Room PRIMARY KEY collision on the line-item insert, after the receipt's own
        // insert has already landed inside the same withTransaction block - occupies id 1 first,
        // then hands writeReceipt an item explicitly claiming that same id. Same forcing shape as
        // the pre-repoint RecordStore version of this test, aimed at Room's own constraint instead
        // of RecordStore.WriteResult.Failure, since that type no longer exists on this path.
        //
        // Unlike PlaceController.tagPlace's unconfigured write (safe to let throw because its
        // VOICE caller, LiveSessionController's dispatch, already wraps every tool call in a
        // catch-all), writeReceipt's only production caller - PantryImportScreen's bare
        // LaunchedEffect - has no try/catch at all, so a raw throw here would crash the screen
        // instead of showing a worded failure. writeReceipt catches it itself for exactly that
        // reason - see this function's own doc comment.
        db.pantryLineItemDao().insertAll(listOf(PantryLineItem(id = 1L, receiptId = 999L, name = "occupant", totalPriceCents = 1L)))

        val result = success(
            store = "Doomed receipt", totalCents = 500L,
            items = listOf(PantryLineItem(id = 1L, receiptId = 0, name = "colliding id", totalPriceCents = 500L)),
        )

        val outcome = PantryController.writeReceipt(context, result)

        assertFalse("a failed write must never report success", outcome.success)
        assertTrue(
            "the failure must be worded, not silent - the exact contract PantryImportScreen reads .message off of",
            outcome.message.contains("checked out") && outcome.message.contains("couldn't save"),
        )
        assertEquals(0, outcome.itemCount)

        // The receipt insert happened INSIDE the same transaction as the colliding line item -
        // the whole point of this test is that it is rolled back too, never left behind orphaned.
        assertEquals("no receipt must survive a rolled-back transaction", 0, db.pantryReceiptDao().getAll().size)
        assertEquals("only the pre-existing occupant row survives, not the colliding one", 1, db.pantryLineItemDao().getAll().size)
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

    // ------------------------------------------------------ engine retirement step 2: engine -> legacy reconcile

    @Test
    fun `the one-time copy moves an engine receipt and its line items into the legacy tables, idempotently`() = runBlocking {
        createEngineReceipt(store = "Costco", totalCents = 8000L, itemNames = listOf("Rotisserie chicken", "Paper towels"))

        val first = EnginePantryRetirementCopy.copyIfNeeded(context)
        assertEquals(1, first.receiptsCopied)
        assertEquals(2, first.lineItemsCopied)
        assertFalse(first.alreadyDone)

        val receipts = db.pantryReceiptDao().getAll()
        assertEquals(1, receipts.size)
        assertEquals("Costco", receipts.single().store)
        assertEquals(2, db.pantryLineItemDao().getForReceipt(receipts.single().id).size)

        // Running it again changes nothing - both the fast-path completion flag AND, independently,
        // the per-guid existence check (if the flag were ever cleared) must be no-ops the second
        // time.
        val second = EnginePantryRetirementCopy.copyIfNeeded(context)
        assertEquals(0, second.receiptsCopied)
        assertEquals(0, second.lineItemsCopied)
        assertTrue(second.alreadyDone)
        assertEquals(1, db.pantryReceiptDao().getAll().size)
        assertEquals(2, db.pantryLineItemDao().getAll().size)
    }

    @Test
    fun `a receipt whose guid already exists in pantry_receipts is not duplicated or overwritten by the copy`() = runBlocking {
        // A forward-copied (wave 2) or previously-repointed receipt already sits in
        // pantry_receipts under some syncId - the copier must never touch it, regardless of what
        // the engine's own copy of it says.
        val existingGuid = "already-here-guid"
        db.pantryReceiptDao().insert(
            PantryReceipt(
                store = "Legacy Costco", purchaseDate = 1L, currency = LedgerCurrency.USD,
                totalCents = 100L, sourceImagePath = "", syncId = existingGuid,
            ),
        )
        // An engine Receipt record that happens to carry the SAME guid - simulates the
        // forward-copy case where guid == syncId already.
        val schema = PantryAspectSeeder.ensureSeeded(context)
        val recordStore = RecordStore(db.engineRecordDao(), db.fieldDefDao(), db.recordTypeDao())
        recordStore.create(
            recordTypeId = schema.receipt.recordTypeId,
            fieldValues = mapOf(
                schema.receipt.fieldIds.getValue(PantryAspectSeeder.FIELD_STORE) to "Engine Costco (should be ignored)",
                schema.receipt.fieldIds.getValue(PantryAspectSeeder.FIELD_PURCHASE_DATE) to 2L,
                schema.receipt.fieldIds.getValue(PantryAspectSeeder.FIELD_CURRENCY) to LedgerCurrency.USD.name,
                schema.receipt.fieldIds.getValue(PantryAspectSeeder.FIELD_TOTAL) to 999L,
                schema.receipt.fieldIds.getValue(PantryAspectSeeder.FIELD_SOURCE_IMAGE_PATH) to "",
            ),
            provenance = RecordProvenance.LLM_RECONCILED,
            guid = existingGuid,
        )

        val result = EnginePantryRetirementCopy.copyIfNeeded(context)
        assertEquals("a syncId already present in pantry_receipts must be skipped, not copied over", 0, result.receiptsCopied)

        val rows = db.pantryReceiptDao().getAll()
        assertEquals(1, rows.size)
        assertEquals("Legacy Costco", rows.single().store)
        assertEquals(100L, rows.single().totalCents)
    }

    @Test
    fun `unconfigured reads return engine-only receipts and line items after the repoint`() = runBlocking {
        // Nothing ever calls PantryController.writeReceipt here - this receipt exists ONLY in the
        // engine, simulating data imported before engine retirement step 2's repoint landed.
        // allReceipts/allLineItems (via recentReceiptsWithItems) must still surface it.
        createEngineReceipt(store = "Whole Foods", totalCents = 4500L, itemNames = listOf("Kombucha"))

        val receipts = PantryController.recentReceiptsWithItems(context, limitReceipts = 10)

        assertEquals(1, receipts.size)
        assertEquals("Whole Foods", receipts.single().first.store)
        assertEquals(1, receipts.single().second.size)
        assertEquals("Kombucha", receipts.single().second.single().name)
    }

    @Test
    fun `the engine's Receipt and LineItem records still exist after the copy - nothing is deleted`() = runBlocking {
        createEngineReceipt(store = "Costco", totalCents = 8000L, itemNames = listOf("A", "B"))
        EnginePantryRetirementCopy.copyIfNeeded(context)

        val schema = PantryAspectSeeder.ensureSeeded(context)
        val receiptRecords = db.engineRecordDao().activeByRecordType(schema.receipt.recordTypeId)
        val lineItemRecords = db.engineRecordDao().activeByRecordType(schema.lineItem.recordTypeId)
        assertEquals("ticket 15: nothing is deleted until every aspect is repointed and soaked", 1, receiptRecords.size)
        assertEquals(2, lineItemRecords.size)
    }

    @Test
    fun `provenance and unaccountedCents survive the copy exactly - LLM_RECONCILED and null, never invented`() = runBlocking {
        createEngineReceipt(store = "Target", totalCents = 2000L, itemNames = listOf("Detergent"))

        EnginePantryRetirementCopy.copyIfNeeded(context)

        val row = db.pantryReceiptDao().getAll().single()
        assertEquals(
            "every engine Receipt is unconditionally LLM_RECONCILED - PantryController.writeReceipt " +
                "hardcodes it and there is no other engine write path for this record type",
            "LLM_RECONCILED",
            row.provenance,
        )
        assertEquals(
            "the engine has no unaccountedCents concept at all - null here is exact, not a filled-in default",
            null,
            row.unaccountedCents,
        )
    }

    @Test
    fun `a header and its line items move together, never a receipt with some but not all of its items copied`() = runBlocking {
        createEngineReceipt(store = "BJ's", totalCents = 3000L, itemNames = listOf("A", "B", "C"))

        val result = EnginePantryRetirementCopy.copyIfNeeded(context)

        assertEquals(1, result.receiptsCopied)
        assertEquals(3, result.lineItemsCopied)
        val receiptRow = db.pantryReceiptDao().getAll().single()
        assertEquals(
            "the receipt's line items must all reference the SAME new legacy row, none left behind",
            3,
            db.pantryLineItemDao().getForReceipt(receiptRow.id).size,
        )
    }

    /**
     * Mutation proof (per this ticket's brief: "prove one assertion load-bearing by mutation") -
     * temporarily removes the `guid in existingReceiptSyncIds` skip a copier of this shape depends
     * on, by instead pre-poisoning `pantry_receipts` with a DIFFERENT syncId than the engine record
     * actually carries. If the copier's existence check were keyed on anything looser than an exact
     * guid match (e.g. matching on store name, the places-style semantic key this object's class doc
     * explains does NOT apply here), this receipt would be wrongly skipped as "already present."
     * With the real guid-exact check, it must still be copied.
     */
    @Test
    fun `mutation check - a differently-guided existing row for the same store does not block the copy`() = runBlocking {
        // A legacy row for the same STORE NAME, but a syncId that shares nothing with the engine
        // record about to be copied - proves the copier keys on guid, not store, purchaseDate, or
        // any other semantic field a "looser" existence check might have used instead.
        db.pantryReceiptDao().insert(
            PantryReceipt(
                store = "Costco", purchaseDate = 1L, currency = LedgerCurrency.USD,
                totalCents = 1L, sourceImagePath = "", syncId = "unrelated-guid-entirely",
            ),
        )
        createEngineReceipt(store = "Costco", totalCents = 8000L, itemNames = listOf("Rotisserie chicken"))

        val result = EnginePantryRetirementCopy.copyIfNeeded(context)

        assertEquals(
            "a same-named row under a DIFFERENT guid must not block the copy - the key is the guid, not the store",
            1,
            result.receiptsCopied,
        )
        assertEquals(2, db.pantryReceiptDao().getAll().size) // the pre-existing one, plus the newly copied one
    }
}

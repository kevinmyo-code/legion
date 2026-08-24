package com.kevin.legion.engine.migration

import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.DeletePolicy
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
 * Robolectric coverage for [EngineDataMigrationWave2] - Wave 2's own owed tests
 * (`.scratch/aspect-engine/issues/21-migration-waves.md` point 5: idempotent, content-faithful,
 * count-exact). Same shape as [EngineDataMigrationWave1Test].
 */
@RunWith(RobolectricTestRunner::class)
class EngineDataMigrationWave2Test {
    private val context = RuntimeEnvironment.getApplication()
    private val db get() = CarDatabase.getDatabase(context)

    @Before
    fun clearState() {
        RoomTestReset.resetCarDatabaseSingleton()
        context.getSharedPreferences("engine_migration_wave2", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    private suspend fun seedReceipt(
        store: String = "Trader Joe's",
        purchaseDate: Long = System.currentTimeMillis(),
        currency: LedgerCurrency = LedgerCurrency.USD,
        totalCents: Long = 1234L,
        sourceImagePath: String = "/data/pantry_receipts/1.jpg",
    ): Long = db.pantryReceiptDao().insert(
        PantryReceipt(
            store = store,
            purchaseDate = purchaseDate,
            currency = currency,
            totalCents = totalCents,
            sourceImagePath = sourceImagePath,
        ),
    )

    // --------------------------------------------------------------------------------- count-exact

    @Test
    fun `count-exact - every PantryReceipt and every PantryLineItem produces exactly one engine record`() = runBlocking {
        val r1 = seedReceipt(store = "A", totalCents = 500L)
        db.pantryLineItemDao().insertAll(listOf(PantryLineItem(receiptId = r1, name = "milk", totalPriceCents = 500L)))

        val r2 = seedReceipt(store = "B", totalCents = 700L)
        db.pantryLineItemDao().insertAll(
            listOf(
                PantryLineItem(receiptId = r2, name = "eggs", totalPriceCents = 300L),
                PantryLineItem(receiptId = r2, name = "bread", totalPriceCents = 400L),
            ),
        )

        val result = EngineDataMigrationWave2.copyPantryIfNeeded(context)

        assertEquals(2, result.receiptsCopied)
        assertEquals(3, result.lineItemsCopied)

        val schema = PantryAspectSeeder.ensureSeeded(context)
        assertEquals(2, db.engineRecordDao().activeByRecordType(schema.receipt.recordTypeId).size)
        assertEquals(3, db.engineRecordDao().activeByRecordType(schema.lineItem.recordTypeId).size)
    }

    // ----------------------------------------------------------------------------- content-faithful

    @Test
    fun `content-faithful - receipt fields, money cents exact, and provenance are LLM_RECONCILED`() = runBlocking {
        val purchaseDate = System.currentTimeMillis() - 86_400_000L
        val receiptId = seedReceipt(
            store = "Walmart", purchaseDate = purchaseDate, currency = LedgerCurrency.USD,
            totalCents = 128_86L, sourceImagePath = "/data/pantry_receipts/42.jpg",
        )
        db.pantryLineItemDao().insertAll(listOf(PantryLineItem(receiptId = receiptId, name = "eggs", totalPriceCents = 128_86L)))

        EngineDataMigrationWave2.copyPantryIfNeeded(context)

        val schema = PantryAspectSeeder.ensureSeeded(context)
        val receiptRecord = db.engineRecordDao().activeByRecordType(schema.receipt.recordTypeId).single()
        val payload = JSONObject(receiptRecord.payload)

        assertEquals("Walmart", PayloadCodec.readString(payload, schema.receipt.fieldIds.getValue(PantryAspectSeeder.FIELD_STORE)))
        assertEquals(purchaseDate, PayloadCodec.readLong(payload, schema.receipt.fieldIds.getValue(PantryAspectSeeder.FIELD_PURCHASE_DATE)))
        assertEquals("USD", PayloadCodec.readString(payload, schema.receipt.fieldIds.getValue(PantryAspectSeeder.FIELD_CURRENCY)))
        assertEquals(128_86L, PayloadCodec.readLong(payload, schema.receipt.fieldIds.getValue(PantryAspectSeeder.FIELD_TOTAL)))
        assertEquals("/data/pantry_receipts/42.jpg", PayloadCodec.readString(payload, schema.receipt.fieldIds.getValue(PantryAspectSeeder.FIELD_SOURCE_IMAGE_PATH)))
        assertEquals(128_86L, receiptRecord.amountCents) // primaryAmountFieldId promotion, exact cents
        assertEquals(RecordProvenance.LLM_RECONCILED, receiptRecord.provenance) // never USER - see class doc
        assertEquals(purchaseDate, receiptRecord.createdAt) // seeded from purchaseDate, the closest available anchor
        assertEquals(purchaseDate, receiptRecord.updatedAt)
    }

    @Test
    fun `content-faithful - line item money cents exact, macro estimates carried, and reference intact`() = runBlocking {
        val receiptId = seedReceipt(totalCents = 999L)
        db.pantryLineItemDao().insertAll(
            listOf(
                PantryLineItem(
                    receiptId = receiptId, name = "steak", quantity = 2.0,
                    unitPriceCents = 400L, totalPriceCents = 999L,
                    caloriesKcal = 650, proteinG = 45.5, carbsG = 0.0, fatG = 50.2,
                ),
            ),
        )

        EngineDataMigrationWave2.copyPantryIfNeeded(context)

        val schema = PantryAspectSeeder.ensureSeeded(context)
        val receiptRecord = db.engineRecordDao().activeByRecordType(schema.receipt.recordTypeId).single()
        val itemRecord = db.engineRecordDao().activeByRecordType(schema.lineItem.recordTypeId).single()
        val payload = JSONObject(itemRecord.payload)

        assertEquals("steak", PayloadCodec.readString(payload, schema.lineItem.fieldIds.getValue(PantryAspectSeeder.FIELD_NAME)))
        assertEquals(2.0, payload.getDouble(PayloadCodec.key(schema.lineItem.fieldIds.getValue(PantryAspectSeeder.FIELD_QUANTITY))), 0.0001)
        assertEquals(400L, PayloadCodec.readLong(payload, schema.lineItem.fieldIds.getValue(PantryAspectSeeder.FIELD_UNIT_PRICE)))
        assertEquals(999L, PayloadCodec.readLong(payload, schema.lineItem.fieldIds.getValue(PantryAspectSeeder.FIELD_TOTAL_PRICE)))
        assertEquals(999L, itemRecord.amountCents) // primaryAmountFieldId promotion

        assertEquals(650.0, payload.getDouble(PayloadCodec.key(schema.lineItem.fieldIds.getValue(PantryAspectSeeder.FIELD_ESTIMATED_CALORIES_KCAL))), 0.0001)
        assertEquals(45.5, payload.getDouble(PayloadCodec.key(schema.lineItem.fieldIds.getValue(PantryAspectSeeder.FIELD_ESTIMATED_PROTEIN_G))), 0.0001)
        assertEquals(0.0, payload.getDouble(PayloadCodec.key(schema.lineItem.fieldIds.getValue(PantryAspectSeeder.FIELD_ESTIMATED_CARBS_G))), 0.0001)
        assertEquals(50.2, payload.getDouble(PayloadCodec.key(schema.lineItem.fieldIds.getValue(PantryAspectSeeder.FIELD_ESTIMATED_FAT_G))), 0.0001)

        // The reference is intact - it points at the receipt's real, new EngineRecord id.
        assertEquals(receiptRecord.id, PayloadCodec.readReferenceId(payload, schema.lineItem.fieldIds.getValue(PantryAspectSeeder.FIELD_RECEIPT)))
        assertEquals(RecordProvenance.LLM_RECONCILED, itemRecord.provenance)
    }

    @Test
    fun `a line item with no unit price migrates that field as absent, not zero`() = runBlocking {
        val receiptId = seedReceipt(totalCents = 100L)
        db.pantryLineItemDao().insertAll(listOf(PantryLineItem(receiptId = receiptId, name = "loose apple", unitPriceCents = null, totalPriceCents = 100L)))

        EngineDataMigrationWave2.copyPantryIfNeeded(context)

        val schema = PantryAspectSeeder.ensureSeeded(context)
        val itemRecord = db.engineRecordDao().activeByRecordType(schema.lineItem.recordTypeId).single()
        val payload = JSONObject(itemRecord.payload)
        assertNull(PayloadCodec.readLong(payload, schema.lineItem.fieldIds.getValue(PantryAspectSeeder.FIELD_UNIT_PRICE)))
    }

    // ---------------------------------------------------------------------------- CASCADE policy

    @Test
    fun `deleting a migrated receipt CASCADEs to trash its migrated line items`() = runBlocking {
        val receiptId = seedReceipt(totalCents = 300L)
        db.pantryLineItemDao().insertAll(
            listOf(
                PantryLineItem(receiptId = receiptId, name = "a", totalPriceCents = 150L),
                PantryLineItem(receiptId = receiptId, name = "b", totalPriceCents = 150L),
            ),
        )
        EngineDataMigrationWave2.copyPantryIfNeeded(context)

        val schema = PantryAspectSeeder.ensureSeeded(context)
        val receiptRecord = db.engineRecordDao().activeByRecordType(schema.receipt.recordTypeId).single()
        val recordStore = com.kevin.legion.engine.RecordStore(db.engineRecordDao(), db.fieldDefDao(), db.recordTypeDao())

        val deleteResult = recordStore.delete(receiptRecord.id)

        assertTrue(deleteResult is com.kevin.legion.engine.RecordStore.DeleteResult.Trashed)
        assertEquals(0, db.engineRecordDao().activeByRecordType(schema.lineItem.recordTypeId).size)
    }

    // ----------------------------------------------------------------------------------- idempotence

    @Test
    fun `idempotent - a second run copies nothing and leaves counts unchanged`() = runBlocking {
        val receiptId = seedReceipt(totalCents = 200L)
        db.pantryLineItemDao().insertAll(listOf(PantryLineItem(receiptId = receiptId, name = "milk", totalPriceCents = 200L)))

        val first = EngineDataMigrationWave2.copyPantryIfNeeded(context)
        val second = EngineDataMigrationWave2.copyPantryIfNeeded(context)

        assertEquals(1, first.receiptsCopied)
        assertEquals(1, first.lineItemsCopied)
        assertFalse(first.alreadyDone)
        assertEquals(0, second.receiptsCopied)
        assertEquals(0, second.lineItemsCopied)
        assertTrue(second.alreadyDone)

        val schema = PantryAspectSeeder.ensureSeeded(context)
        assertEquals(1, db.engineRecordDao().activeByRecordType(schema.receipt.recordTypeId).size)
        assertEquals(1, db.engineRecordDao().activeByRecordType(schema.lineItem.recordTypeId).size)
    }

    @Test
    fun `per-row guid check is also idempotent even without the completion flag`() = runBlocking {
        val receiptId = seedReceipt(totalCents = 200L)
        db.pantryLineItemDao().insertAll(listOf(PantryLineItem(receiptId = receiptId, name = "milk", totalPriceCents = 200L)))

        EngineDataMigrationWave2.copyPantryIfNeeded(context)
        // Simulate a crash after the loop wrote its rows but before the flag was set.
        context.getSharedPreferences("engine_migration_wave2", android.content.Context.MODE_PRIVATE)
            .edit().putBoolean("pantry_completed_v1", false).commit()
        val retry = EngineDataMigrationWave2.copyPantryIfNeeded(context)

        assertEquals(0, retry.receiptsCopied)
        assertEquals(0, retry.lineItemsCopied)
        val schema = PantryAspectSeeder.ensureSeeded(context)
        assertEquals(1, db.engineRecordDao().activeByRecordType(schema.receipt.recordTypeId).size)
        assertEquals(1, db.engineRecordDao().activeByRecordType(schema.lineItem.recordTypeId).size)
    }

    @Test
    fun `a receipt with no line items still copies cleanly`() = runBlocking {
        seedReceipt(totalCents = 0L)

        val result = EngineDataMigrationWave2.copyPantryIfNeeded(context)

        assertEquals(1, result.receiptsCopied)
        assertEquals(0, result.lineItemsCopied)
        assertTrue(result.alreadyDone.not())
    }

    // -------------------------------------------------------------- section-4 invariant survives

    @Test
    fun `sum of migrated line-item totalPrice equals the migrated receipt total, re-summed on the engine records`() = runBlocking {
        // Mirrors the real gate shape (PantryReceiptReceiptAgent's own anchor 1: sum(items) ==
        // subtotal/total) - this is that invariant re-checked AFTER migration, on the engine's own
        // records, not merely assumed to have survived the copy.
        val receiptId = seedReceipt(totalCents = 900L)
        db.pantryLineItemDao().insertAll(
            listOf(
                PantryLineItem(receiptId = receiptId, name = "a", totalPriceCents = 300L),
                PantryLineItem(receiptId = receiptId, name = "b", totalPriceCents = 250L),
                PantryLineItem(receiptId = receiptId, name = "c", totalPriceCents = 350L),
            ),
        )

        EngineDataMigrationWave2.copyPantryIfNeeded(context)

        val schema = PantryAspectSeeder.ensureSeeded(context)
        val receiptRecord = db.engineRecordDao().activeByRecordType(schema.receipt.recordTypeId).single()
        val itemRecords = db.engineRecordDao().activeByRecordType(schema.lineItem.recordTypeId)
        assertEquals(3, itemRecords.size)

        val engineSideTotal = itemRecords.sumOf { record ->
            PayloadCodec.readLong(JSONObject(record.payload), schema.lineItem.fieldIds.getValue(PantryAspectSeeder.FIELD_TOTAL_PRICE))
                ?: 0L
        }
        val receiptTotal = PayloadCodec.readLong(JSONObject(receiptRecord.payload), schema.receipt.fieldIds.getValue(PantryAspectSeeder.FIELD_TOTAL))

        assertEquals(900L, engineSideTotal)
        assertEquals(receiptTotal, engineSideTotal)
        assertEquals(receiptRecord.amountCents, engineSideTotal) // promoted column agrees too
    }

    // ------------------------------------------------------------------------------ failure paths

    /** Corrupts the already-seeded `receipt` REFERENCE field's config so [FieldType.REFERENCE]
     * validation in [com.kevin.legion.engine.RecordStore.create] rejects EVERY line item this pass
     * ("wrong record type" - the target config is pointed at the LineItem type itself instead of
     * Receipt). [PantryAspectSeeder.ensureSeeded] never re-creates a field that already exists by
     * name, so this corruption survives every subsequent `ensureSeeded`/`copyPantryIfNeeded` call
     * until [restoreReceiptReferenceField] fixes it back - the exact "test seam" the senior review
     * names as an acceptable way to force a genuine [com.kevin.legion.engine.RecordStore.WriteResult.Failure]. */
    private suspend fun corruptReceiptReferenceField(schema: PantryAspectSeeder.Schema) {
        val field = db.fieldDefDao().forRecordType(schema.lineItem.recordTypeId)
            .single { it.id == schema.lineItem.fieldIds.getValue(PantryAspectSeeder.FIELD_RECEIPT) }
        db.fieldDefDao().update(
            field.copy(config = FieldConfig.serializeReference(schema.lineItem.recordTypeId, DeletePolicy.CASCADE)),
        )
    }

    private suspend fun restoreReceiptReferenceField(schema: PantryAspectSeeder.Schema) {
        val field = db.fieldDefDao().forRecordType(schema.lineItem.recordTypeId)
            .single { it.id == schema.lineItem.fieldIds.getValue(PantryAspectSeeder.FIELD_RECEIPT) }
        db.fieldDefDao().update(
            field.copy(config = FieldConfig.serializeReference(schema.receipt.recordTypeId, DeletePolicy.CASCADE)),
        )
    }

    @Test
    fun `a forced line-item create failure leaves the completion flag UNSET, and the item is retried on the next run`() = runBlocking {
        val receiptId = seedReceipt(totalCents = 200L)
        db.pantryLineItemDao().insertAll(listOf(PantryLineItem(receiptId = receiptId, name = "milk", totalPriceCents = 200L)))
        val schema = PantryAspectSeeder.ensureSeeded(context)
        corruptReceiptReferenceField(schema)

        val first = EngineDataMigrationWave2.copyPantryIfNeeded(context)

        assertEquals(1, first.receiptsCopied) // the receipt itself has no reference field - unaffected
        assertEquals(0, first.lineItemsCopied) // the line item's reference validation rejected it
        assertFalse(
            "the completion flag must stay clear when a line-item create failed this pass",
            context.getSharedPreferences("engine_migration_wave2", android.content.Context.MODE_PRIVATE)
                .getBoolean("pantry_completed_v1", false),
        )
        assertEquals(0, db.engineRecordDao().activeByRecordType(schema.lineItem.recordTypeId).size)

        // Fix the corruption (as a real fix/redeploy would) and confirm the retry actually lands.
        restoreReceiptReferenceField(schema)
        val retry = EngineDataMigrationWave2.copyPantryIfNeeded(context)

        assertEquals(0, retry.receiptsCopied) // already copied, guid recognized
        assertEquals(1, retry.lineItemsCopied) // the previously-failed item is retried and now lands
    }

    @Test
    fun `failure-path mirror of idempotence - partial failure leaves the flag clear, second run completes the copy, count-exact`() = runBlocking {
        val r1 = seedReceipt(store = "A", totalCents = 500L)
        db.pantryLineItemDao().insertAll(listOf(PantryLineItem(receiptId = r1, name = "milk", totalPriceCents = 500L)))
        val r2 = seedReceipt(store = "B", totalCents = 300L)
        db.pantryLineItemDao().insertAll(
            listOf(
                PantryLineItem(receiptId = r2, name = "eggs", totalPriceCents = 150L),
                PantryLineItem(receiptId = r2, name = "bread", totalPriceCents = 150L),
            ),
        )
        val schema = PantryAspectSeeder.ensureSeeded(context)
        corruptReceiptReferenceField(schema)

        val first = EngineDataMigrationWave2.copyPantryIfNeeded(context)
        assertEquals(2, first.receiptsCopied)
        assertEquals(0, first.lineItemsCopied)
        assertFalse(first.alreadyDone)
        assertFalse(
            context.getSharedPreferences("engine_migration_wave2", android.content.Context.MODE_PRIVATE)
                .getBoolean("pantry_completed_v1", false),
        )

        restoreReceiptReferenceField(schema)
        val second = EngineDataMigrationWave2.copyPantryIfNeeded(context)

        assertEquals(0, second.receiptsCopied) // both receipts already carried a real guid
        assertEquals(3, second.lineItemsCopied) // all three previously-failed items land now
        assertFalse(second.alreadyDone) // the flag was clear going in, so this was a real pass, not a skip

        // count-exact at the end: every legacy row has exactly one engine record, nothing doubled.
        assertEquals(2, db.engineRecordDao().activeByRecordType(schema.receipt.recordTypeId).size)
        assertEquals(3, db.engineRecordDao().activeByRecordType(schema.lineItem.recordTypeId).size)

        val third = EngineDataMigrationWave2.copyPantryIfNeeded(context)
        assertTrue("a genuinely complete pass must now set the flag and fast-path every later call", third.alreadyDone)
    }

    // ---------------------------------------------------------------------- cutover 2's catchUpOnce

    @Test
    fun `catchUpOnce picks up a legacy receipt written after the ordinary wave-2 copy already completed`() = runBlocking {
        // Simulates the real cutover window: wave 2 lands and copies whatever exists, then more
        // receipts get imported through the still-legacy-backed PantryController before cutover 2's
        // build reaches the phone.
        val early = seedReceipt(store = "Before cutover", totalCents = 100L)
        db.pantryLineItemDao().insertAll(listOf(PantryLineItem(receiptId = early, name = "milk", totalPriceCents = 100L)))
        EngineDataMigrationWave2.copyPantryIfNeeded(context)

        val late = seedReceipt(store = "Written in the cutover window", totalCents = 200L)
        db.pantryLineItemDao().insertAll(listOf(PantryLineItem(receiptId = late, name = "eggs", totalPriceCents = 200L)))

        val ran = EngineDataMigrationWave2.catchUpOnce(context)

        assertTrue("the first catch-up call must actually run", ran)
        val schema = PantryAspectSeeder.ensureSeeded(context)
        // Both receipts present, count-exact - the early one recognized by guid and skipped, the
        // late one picked up for the first time.
        assertEquals(2, db.engineRecordDao().activeByRecordType(schema.receipt.recordTypeId).size)
        assertEquals(2, db.engineRecordDao().activeByRecordType(schema.lineItem.recordTypeId).size)
    }

    @Test
    fun `catchUpOnce is idempotent - a second call is a no-op and never re-copies or duplicates`() = runBlocking {
        val receiptId = seedReceipt(totalCents = 300L)
        db.pantryLineItemDao().insertAll(listOf(PantryLineItem(receiptId = receiptId, name = "bread", totalPriceCents = 300L)))
        EngineDataMigrationWave2.copyPantryIfNeeded(context)

        val first = EngineDataMigrationWave2.catchUpOnce(context)
        val second = EngineDataMigrationWave2.catchUpOnce(context)

        assertTrue(first)
        assertFalse("a second catchUpOnce call must be a no-op, guarded by its own completion marker", second)

        val schema = PantryAspectSeeder.ensureSeeded(context)
        assertEquals(1, db.engineRecordDao().activeByRecordType(schema.receipt.recordTypeId).size)
        assertEquals(1, db.engineRecordDao().activeByRecordType(schema.lineItem.recordTypeId).size)
    }
}

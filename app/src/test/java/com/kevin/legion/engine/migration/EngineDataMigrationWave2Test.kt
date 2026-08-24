package com.kevin.legion.engine.migration

import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.LedgerCurrency
import com.kevin.legion.data.local.PantryLineItem
import com.kevin.legion.data.local.PantryReceipt
import com.kevin.legion.data.local.RecordProvenance
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
}

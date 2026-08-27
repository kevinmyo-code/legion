package com.kevin.legion.engine.pantry

import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.DeletePolicy
import com.kevin.legion.data.local.FieldType
import com.kevin.legion.engine.FieldConfig
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
 * Robolectric coverage for [PantryAspectSeeder] - mirrors
 * [com.kevin.legion.engine.notes.NotesAspectSeederTest]'s shape (idempotence at every
 * granularity, correct field/type/required/lock wiring, the promoted-column assignments).
 */
@RunWith(RobolectricTestRunner::class)
class PantryAspectSeederTest {
    private val context = RuntimeEnvironment.getApplication()
    private val db get() = CarDatabase.getDatabase(context)

    @Before
    fun clearState() {
        RoomTestReset.resetCarDatabaseSingleton()
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


    @Test
    fun `seeding is idempotent - a second call creates no duplicate aspect, record types, or fields`() = runBlocking {
        val first = PantryAspectSeeder.ensureSeeded(context)
        val second = PantryAspectSeeder.ensureSeeded(context)

        assertEquals(first.aspectId, second.aspectId)
        assertEquals(first.receipt.recordTypeId, second.receipt.recordTypeId)
        assertEquals(first.lineItem.recordTypeId, second.lineItem.recordTypeId)
        assertEquals(first.receipt.fieldIds, second.receipt.fieldIds)
        assertEquals(first.lineItem.fieldIds, second.lineItem.fieldIds)

        assertEquals(1, db.aspectDao().listActive().count { it.name == PantryAspectSeeder.ASPECT_NAME })
        assertEquals(1, db.recordTypeDao().listByAspect(first.aspectId).count { it.name == "Receipt" })
        assertEquals(1, db.recordTypeDao().listByAspect(first.aspectId).count { it.name == "LineItem" })
        assertEquals(8, db.fieldDefDao().forRecordType(first.receipt.recordTypeId).size) // 5 wave-2 fields + cutover 2's 3 anchor fields
        assertEquals(9, db.fieldDefDao().forRecordType(first.lineItem.recordTypeId).size)
    }

    @Test
    fun `Receipt total is promoted as primaryAmountFieldId, and no due-date field is wired`() = runBlocking {
        val schema = PantryAspectSeeder.ensureSeeded(context)
        val recordType = db.recordTypeDao().getById(schema.receipt.recordTypeId)!!

        assertEquals(schema.receipt.fieldIds.getValue(PantryAspectSeeder.FIELD_TOTAL), recordType.primaryAmountFieldId)
        assertEquals(null, recordType.primaryDueDateFieldId) // a past purchase date is not agenda-shaped - see the carve doc
    }

    @Test
    fun `LineItem totalPrice is promoted as primaryAmountFieldId`() = runBlocking {
        val schema = PantryAspectSeeder.ensureSeeded(context)
        val recordType = db.recordTypeDao().getById(schema.lineItem.recordTypeId)!!

        assertEquals(schema.lineItem.fieldIds.getValue(PantryAspectSeeder.FIELD_TOTAL_PRICE), recordType.primaryAmountFieldId)
    }

    @Test
    fun `receipt field is a REFERENCE to Receipt with CASCADE delete policy`() = runBlocking {
        val schema = PantryAspectSeeder.ensureSeeded(context)
        val receiptField = db.fieldDefDao().forRecordType(schema.lineItem.recordTypeId)
            .single { it.id == schema.lineItem.fieldIds.getValue(PantryAspectSeeder.FIELD_RECEIPT) }

        assertEquals(FieldType.REFERENCE, receiptField.type)
        assertTrue(receiptField.required)
        assertEquals("pantry", receiptField.ownerPluginId)
        assertTrue(receiptField.locked)

        val refConfig = FieldConfig.referenceConfig(receiptField.config)!!
        assertEquals(schema.receipt.recordTypeId, refConfig.targetRecordTypeId)
        assertEquals(DeletePolicy.CASCADE, refConfig.deletePolicy)
    }

    @Test
    fun `macro estimate fields are named with an estimated prefix, and are not required or locked`() = runBlocking {
        val schema = PantryAspectSeeder.ensureSeeded(context)
        val fields = db.fieldDefDao().forRecordType(schema.lineItem.recordTypeId).associateBy { it.id }

        val estimateNames = listOf(
            PantryAspectSeeder.FIELD_ESTIMATED_CALORIES_KCAL,
            PantryAspectSeeder.FIELD_ESTIMATED_PROTEIN_G,
            PantryAspectSeeder.FIELD_ESTIMATED_CARBS_G,
            PantryAspectSeeder.FIELD_ESTIMATED_FAT_G,
        )
        for (name in estimateNames) {
            val field = fields.getValue(schema.lineItem.fieldIds.getValue(name))
            assertTrue("field name '$name' must say 'estimated'", field.name.startsWith("estimated"))
            assertEquals(false, field.required)
            assertEquals(false, field.locked)
            assertEquals(FieldType.NUMBER, field.type)
        }
    }

    @Test
    fun `currency is a CHOICE field with exactly SGD and USD as options`() = runBlocking {
        val schema = PantryAspectSeeder.ensureSeeded(context)
        val currencyField = db.fieldDefDao().forRecordType(schema.receipt.recordTypeId)
            .single { it.id == schema.receipt.fieldIds.getValue(PantryAspectSeeder.FIELD_CURRENCY) }

        assertEquals(FieldType.CHOICE, currencyField.type)
        assertEquals(listOf("SGD", "USD"), FieldConfig.choiceOptions(currencyField.config))
    }
}

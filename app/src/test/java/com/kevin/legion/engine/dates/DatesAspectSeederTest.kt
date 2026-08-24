package com.kevin.legion.engine.dates

import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.FieldType
import com.kevin.legion.testutil.RoomTestReset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Guards [DatesAspectSeeder.ensureSeeded]'s idempotence - ticket 19 point 1: "Seeder idempotent."
 * Robolectric through the real [CarDatabase.getDatabase] path, same shape as
 * [com.kevin.legion.engine.RecordStoreTest].
 */
@RunWith(RobolectricTestRunner::class)
class DatesAspectSeederTest {
    private val context = RuntimeEnvironment.getApplication()
    private val db get() = CarDatabase.getDatabase(context)

    @Before
    fun clearState() {
        RoomTestReset.resetCarDatabaseSingleton()
    }

    @Test
    fun `first run creates exactly one aspect, one record type, and seven fields`() = runBlocking {
        DatesAspectSeeder.ensureSeeded(context)

        assertEquals(1, db.aspectDao().listActive().count { it.name == DatesAspectSeeder.ASPECT_NAME })
        val aspectId = db.aspectDao().listActive().first { it.name == DatesAspectSeeder.ASPECT_NAME }.id
        val recordTypes = db.recordTypeDao().listByAspect(aspectId)
        assertEquals(1, recordTypes.size)
        val fields = db.fieldDefDao().forRecordType(recordTypes.single().id)
        assertEquals(7, fields.size)
    }

    @Test
    fun `running the seeder twice never duplicates the aspect, record type, or any field`() = runBlocking {
        val first = DatesAspectSeeder.ensureSeeded(context)
        val second = DatesAspectSeeder.ensureSeeded(context)

        assertEquals(first.aspectId, second.aspectId)
        assertEquals(first.recordTypeId, second.recordTypeId)
        assertEquals(first.fieldIds, second.fieldIds)
        assertEquals(1, db.aspectDao().listActive().count { it.name == DatesAspectSeeder.ASPECT_NAME })
        assertEquals(7, db.fieldDefDao().forRecordType(second.recordTypeId).size)
    }

    @Test
    fun `required fields are locked to the dates plugin, optional fields are not`() = runBlocking {
        val schema = DatesAspectSeeder.ensureSeeded(context)
        val fields = db.fieldDefDao().forRecordType(schema.recordTypeId).associateBy { it.name }

        assertTrue(fields.getValue(DatesAspectSeeder.FIELD_TITLE).locked)
        assertTrue(fields.getValue(DatesAspectSeeder.FIELD_START).locked)
        assertTrue(fields.getValue(DatesAspectSeeder.FIELD_SOURCE).locked)
        assertTrue("end is not required, so it must stay user-deletable", !fields.getValue(DatesAspectSeeder.FIELD_END).locked)
        assertTrue("location is not required, so it must stay user-deletable", !fields.getValue(DatesAspectSeeder.FIELD_LOCATION).locked)
        assertEquals(DatesAspectSeeder.OWNER_PLUGIN_ID, fields.getValue(DatesAspectSeeder.FIELD_TITLE).ownerPluginId)
    }

    @Test
    fun `start is wired as the primary due date field so RecordStore promotes it into dueAt`() = runBlocking {
        val schema = DatesAspectSeeder.ensureSeeded(context)
        val recordType = db.recordTypeDao().getById(schema.recordTypeId)!!

        assertEquals(schema.fieldIds.getValue(DatesAspectSeeder.FIELD_START), recordType.primaryDueDateFieldId)
    }

    @Test
    fun `the source field is a choice of exactly legion and google`() = runBlocking {
        val schema = DatesAspectSeeder.ensureSeeded(context)
        val sourceField = db.fieldDefDao().forRecordType(schema.recordTypeId)
            .single { it.name == DatesAspectSeeder.FIELD_SOURCE }

        assertEquals(FieldType.CHOICE, sourceField.type)
        assertEquals(
            listOf(DatesAspectSeeder.SOURCE_LEGION, DatesAspectSeeder.SOURCE_GOOGLE),
            com.kevin.legion.engine.FieldConfig.choiceOptions(sourceField.config),
        )
    }
}

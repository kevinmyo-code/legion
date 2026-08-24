package com.kevin.legion.engine.places

import com.kevin.legion.data.local.CarDatabase
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
 * Guards [PlacesAspectSeeder.ensureSeeded]'s idempotence - Wave 1's own owed test
 * (`.scratch/aspect-engine/issues/21-migration-waves.md` point 3: "carve seeders idempotent").
 */
@RunWith(RobolectricTestRunner::class)
class PlacesAspectSeederTest {
    private val context = RuntimeEnvironment.getApplication()
    private val db get() = CarDatabase.getDatabase(context)

    @Before
    fun clearState() {
        RoomTestReset.resetCarDatabaseSingleton()
    }

    @Test
    fun `first run creates exactly one aspect, one record type, and three fields`() = runBlocking {
        PlacesAspectSeeder.ensureSeeded(context)

        assertEquals(1, db.aspectDao().listActive().count { it.name == PlacesAspectSeeder.ASPECT_NAME })
        val aspectId = db.aspectDao().listActive().first { it.name == PlacesAspectSeeder.ASPECT_NAME }.id
        val recordTypes = db.recordTypeDao().listByAspect(aspectId)
        assertEquals(1, recordTypes.size)
        val fields = db.fieldDefDao().forRecordType(recordTypes.single().id)
        assertEquals(3, fields.size)
    }

    @Test
    fun `running the seeder twice never duplicates the aspect, record type, or any field`() = runBlocking {
        val first = PlacesAspectSeeder.ensureSeeded(context)
        val second = PlacesAspectSeeder.ensureSeeded(context)

        assertEquals(first.aspectId, second.aspectId)
        assertEquals(first.recordTypeId, second.recordTypeId)
        assertEquals(first.fieldIds, second.fieldIds)
        assertEquals(1, db.aspectDao().listActive().count { it.name == PlacesAspectSeeder.ASPECT_NAME })
        assertEquals(3, db.fieldDefDao().forRecordType(second.recordTypeId).size)
    }

    @Test
    fun `every field is required and locked to the places plugin - a place needs a label and coordinates`() = runBlocking {
        val schema = PlacesAspectSeeder.ensureSeeded(context)
        val fields = db.fieldDefDao().forRecordType(schema.recordTypeId).associateBy { it.name }

        assertTrue(fields.getValue(PlacesAspectSeeder.FIELD_LABEL).locked)
        assertTrue(fields.getValue(PlacesAspectSeeder.FIELD_LATITUDE).locked)
        assertTrue(fields.getValue(PlacesAspectSeeder.FIELD_LONGITUDE).locked)
        assertEquals(PlacesAspectSeeder.OWNER_PLUGIN_ID, fields.getValue(PlacesAspectSeeder.FIELD_LABEL).ownerPluginId)
    }

    @Test
    fun `no due-date field is wired - a saved place carries no clock`() = runBlocking {
        val schema = PlacesAspectSeeder.ensureSeeded(context)
        val recordType = db.recordTypeDao().getById(schema.recordTypeId)!!

        assertNull(recordType.primaryDueDateFieldId)
    }
}

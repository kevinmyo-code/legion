package com.kevin.legion.engine.notes

import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.FieldType
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
 * Guards [NotesAspectSeeder.ensureSeeded]'s idempotence - Wave 1's own owed test
 * (`.scratch/aspect-engine/issues/21-migration-waves.md` point 3: "carve seeders idempotent").
 * Same shape as [com.kevin.legion.engine.dates.DatesAspectSeederTest].
 */
@RunWith(RobolectricTestRunner::class)
class NotesAspectSeederTest {
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
    fun `first run creates exactly one aspect, one record type, and twenty-one fields`() = runBlocking {
        // Cutover 1 (`docs/architecture/cutover1-2026-08-24.md`) added FIELD_LOGGED_AT - loggedAt
        // needed a home on the engine record once NotesController stopped writing the legacy
        // ListItem.loggedAt column at all. Twenty was Wave 1's own original count.
        NotesAspectSeeder.ensureSeeded(context)

        assertEquals(1, db.aspectDao().listActive().count { it.name == NotesAspectSeeder.ASPECT_NAME })
        val aspectId = db.aspectDao().listActive().first { it.name == NotesAspectSeeder.ASPECT_NAME }.id
        val recordTypes = db.recordTypeDao().listByAspect(aspectId)
        assertEquals(1, recordTypes.size)
        val fields = db.fieldDefDao().forRecordType(recordTypes.single().id)
        assertEquals(21, fields.size)
    }

    @Test
    fun `running the seeder twice never duplicates the aspect, record type, or any field`() = runBlocking {
        val first = NotesAspectSeeder.ensureSeeded(context)
        val second = NotesAspectSeeder.ensureSeeded(context)

        assertEquals(first.aspectId, second.aspectId)
        assertEquals(first.recordTypeId, second.recordTypeId)
        assertEquals(first.fieldIds, second.fieldIds)
        assertEquals(1, db.aspectDao().listActive().count { it.name == NotesAspectSeeder.ASPECT_NAME })
        assertEquals(21, db.fieldDefDao().forRecordType(second.recordTypeId).size)
    }

    @Test
    fun `text and done are locked to the notes plugin, everything else stays user-ownable`() = runBlocking {
        val schema = NotesAspectSeeder.ensureSeeded(context)
        val fields = db.fieldDefDao().forRecordType(schema.recordTypeId).associateBy { it.name }

        assertTrue(fields.getValue(NotesAspectSeeder.FIELD_TEXT).locked)
        assertTrue(fields.getValue(NotesAspectSeeder.FIELD_DONE).locked)
        assertTrue("startsAt is not required, so it must stay user-deletable", !fields.getValue(NotesAspectSeeder.FIELD_STARTS_AT).locked)
        assertTrue("triggerPlaceLabel is not required, so it must stay user-deletable", !fields.getValue(NotesAspectSeeder.FIELD_TRIGGER_PLACE_LABEL).locked)
        assertEquals(NotesAspectSeeder.OWNER_PLUGIN_ID, fields.getValue(NotesAspectSeeder.FIELD_TEXT).ownerPluginId)
        assertEquals(NotesAspectSeeder.OWNER_PLUGIN_ID, fields.getValue(NotesAspectSeeder.FIELD_STARTS_AT).ownerPluginId)
    }

    @Test
    fun `startsAt is wired as the primary due date field so RecordStore promotes it into dueAt`() = runBlocking {
        val schema = NotesAspectSeeder.ensureSeeded(context)
        val recordType = db.recordTypeDao().getById(schema.recordTypeId)!!

        assertEquals(schema.fieldIds.getValue(NotesAspectSeeder.FIELD_STARTS_AT), recordType.primaryDueDateFieldId)
    }

    @Test
    fun `repeat fields are the expected choice options`() = runBlocking {
        val schema = NotesAspectSeeder.ensureSeeded(context)
        val fields = db.fieldDefDao().forRecordType(schema.recordTypeId).associateBy { it.name }

        val repeatKind = fields.getValue(NotesAspectSeeder.FIELD_REPEAT_KIND)
        assertEquals(FieldType.CHOICE, repeatKind.type)
        assertEquals(NotesAspectSeeder.REPEAT_KIND_OPTIONS, com.kevin.legion.engine.FieldConfig.choiceOptions(repeatKind.config))

        val repeatEndKind = fields.getValue(NotesAspectSeeder.FIELD_REPEAT_END_KIND)
        assertEquals(FieldType.CHOICE, repeatEndKind.type)
        assertEquals(NotesAspectSeeder.REPEAT_END_KIND_OPTIONS, com.kevin.legion.engine.FieldConfig.choiceOptions(repeatEndKind.config))
    }
}

package com.kevin.legion.engine.migration

import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.ItemList
import com.kevin.legion.data.local.ListItem
import com.kevin.legion.data.local.RecordProvenance
import com.kevin.legion.data.local.TaggedPlace
import com.kevin.legion.engine.PayloadCodec
import com.kevin.legion.engine.notes.NotesAspectSeeder
import com.kevin.legion.engine.places.PlacesAspectSeeder
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
 * Robolectric coverage for [EngineDataMigrationWave1] - Wave 1's own owed tests
 * (`.scratch/aspect-engine/issues/21-migration-waves.md` point 5: "the copier idempotent (second
 * run copies nothing), content-faithful (spot fields), count-exact (every old row has exactly one
 * engine record)"). Robolectric through the real [CarDatabase.getDatabase] path, same shape as
 * [com.kevin.legion.engine.RecordStoreTest].
 */
@RunWith(RobolectricTestRunner::class)
class EngineDataMigrationWave1Test {
    private val context = RuntimeEnvironment.getApplication()
    private val db get() = CarDatabase.getDatabase(context)

    @Before
    fun clearState() {
        RoomTestReset.resetCarDatabaseSingleton()
        // Every test gets its own SharedPreferences slate too - the completion flags this class
        // guards on live under the app's real prefs file, and Robolectric's SharedPreferences
        // persist across tests within the same package name unless cleared explicitly.
        context.getSharedPreferences("engine_migration_wave1", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    private suspend fun seedList(): Long {
        val now = System.currentTimeMillis()
        return db.itemListDao().insert(ItemList(name = "List", tickable = true, lastUsedAt = now, createdAt = now, updatedAt = now))
    }

    // ------------------------------------------------------------------------------- Notes

    @Test
    fun `count-exact - every active ListItem produces exactly one engine record`() = runBlocking {
        val listId = seedList()
        val now = System.currentTimeMillis()
        db.listItemDao().insert(ListItem(listId = listId, text = "milk", createdAt = now, updatedAt = now))
        db.listItemDao().insert(ListItem(listId = listId, text = "eggs", createdAt = now, updatedAt = now))
        db.listItemDao().insert(ListItem(listId = listId, text = "gone", createdAt = now, updatedAt = now, deleted = true))

        val result = EngineDataMigrationWave1.copyNotesIfNeeded(context)

        assertEquals(2, result.copied) // the deleted row is excluded, per the carve doc
        val schema = NotesAspectSeeder.ensureSeeded(context)
        assertEquals(2, db.engineRecordDao().activeByRecordType(schema.recordTypeId).size)
    }

    @Test
    fun `content-faithful - text, done, startsAt, and repeat fields survive the copy`() = runBlocking {
        val listId = seedList()
        val now = System.currentTimeMillis()
        val startsAt = now + 3_600_000L
        val item = ListItem(
            listId = listId, text = "call the vet", done = true, doneAt = now,
            startsAt = startsAt, allDay = false, repeatKind = "WEEKLY", repeatEvery = 2,
            createdAt = now - 10_000L, updatedAt = now,
        )
        db.listItemDao().insert(item)

        EngineDataMigrationWave1.copyNotesIfNeeded(context)

        val schema = NotesAspectSeeder.ensureSeeded(context)
        val record = db.engineRecordDao().activeByRecordType(schema.recordTypeId).single()
        val payload = JSONObject(record.payload)

        assertEquals("call the vet", PayloadCodec.readString(payload, schema.fieldIds.getValue(NotesAspectSeeder.FIELD_TEXT)))
        assertTrue(payload.getBoolean(PayloadCodec.key(schema.fieldIds.getValue(NotesAspectSeeder.FIELD_DONE))))
        assertEquals(startsAt, PayloadCodec.readLong(payload, schema.fieldIds.getValue(NotesAspectSeeder.FIELD_STARTS_AT)))
        assertEquals("WEEKLY", PayloadCodec.readString(payload, schema.fieldIds.getValue(NotesAspectSeeder.FIELD_REPEAT_KIND)))
        assertEquals(RecordProvenance.USER, record.provenance)
        assertEquals(startsAt, record.dueAt) // primaryDueDateFieldId promotion
        assertEquals(item.createdAt, record.createdAt) // timestamp preserved
        assertEquals(item.updatedAt, record.updatedAt) // the second-call updatedAt bump landed
        assertEquals(item.syncId, record.guid) // identity carried, not re-minted
    }

    @Test
    fun `idempotent - a second run copies nothing and leaves the record count unchanged`() = runBlocking {
        val listId = seedList()
        val now = System.currentTimeMillis()
        db.listItemDao().insert(ListItem(listId = listId, text = "milk", createdAt = now, updatedAt = now))

        val first = EngineDataMigrationWave1.copyNotesIfNeeded(context)
        val second = EngineDataMigrationWave1.copyNotesIfNeeded(context)

        assertEquals(1, first.copied)
        assertFalse(first.alreadyDone)
        assertEquals(0, second.copied)
        assertTrue("the SharedPreferences fast path must report alreadyDone on the second call", second.alreadyDone)

        val schema = NotesAspectSeeder.ensureSeeded(context)
        assertEquals(1, db.engineRecordDao().activeByRecordType(schema.recordTypeId).size)
    }

    @Test
    fun `per-row guid check is also idempotent even without the completion flag`() = runBlocking {
        // Simulates a crash after the loop wrote its one row but before the flag was set - the
        // exact case the flag-only design cannot protect against on its own (this object's class
        // doc). A second call with the SAME data must still land on one record, not two.
        val listId = seedList()
        val now = System.currentTimeMillis()
        db.listItemDao().insert(ListItem(listId = listId, text = "milk", createdAt = now, updatedAt = now))

        EngineDataMigrationWave1.copyNotesIfNeeded(context)
        // Manually clear only the flag, as if the process had died just before it was written.
        context.getSharedPreferences("engine_migration_wave1", android.content.Context.MODE_PRIVATE)
            .edit().putBoolean("notes_completed_v1", false).commit()
        val retry = EngineDataMigrationWave1.copyNotesIfNeeded(context)

        assertEquals(0, retry.copied) // the one row's guid already existed, so nothing new was written
        val schema = NotesAspectSeeder.ensureSeeded(context)
        assertEquals(1, db.engineRecordDao().activeByRecordType(schema.recordTypeId).size)
    }

    // ------------------------------------------------------------------------------- Places

    @Test
    fun `count-exact and content-faithful - every active TaggedPlace produces exactly one engine record`() = runBlocking {
        val now = System.currentTimeMillis()
        db.placeDao().upsert(TaggedPlace(label = "home", latitude = 29.7, longitude = -95.3, timestamp = now))
        db.placeDao().upsert(TaggedPlace(label = "work", latitude = 29.8, longitude = -95.4, timestamp = now))
        db.placeDao().upsert(TaggedPlace(label = "gone", latitude = 0.0, longitude = 0.0, timestamp = now, deleted = true))

        val result = EngineDataMigrationWave1.copyPlacesIfNeeded(context)

        assertEquals(2, result.copied)
        val schema = PlacesAspectSeeder.ensureSeeded(context)
        val records = db.engineRecordDao().activeByRecordType(schema.recordTypeId)
        assertEquals(2, records.size)

        val home = records.single {
            PayloadCodec.readString(JSONObject(it.payload), schema.fieldIds.getValue(PlacesAspectSeeder.FIELD_LABEL)) == "home"
        }
        val payload = JSONObject(home.payload)
        assertEquals(29.7, payload.getDouble(PayloadCodec.key(schema.fieldIds.getValue(PlacesAspectSeeder.FIELD_LATITUDE))), 0.0001)
        assertEquals(-95.3, payload.getDouble(PayloadCodec.key(schema.fieldIds.getValue(PlacesAspectSeeder.FIELD_LONGITUDE))), 0.0001)
        assertEquals(now, home.createdAt)
        assertEquals(now, home.updatedAt)
        assertNull(home.dueAt) // no due-date field on Places
    }

    @Test
    fun `places copy is idempotent - a second run copies nothing`() = runBlocking {
        val now = System.currentTimeMillis()
        db.placeDao().upsert(TaggedPlace(label = "home", latitude = 29.7, longitude = -95.3, timestamp = now))

        val first = EngineDataMigrationWave1.copyPlacesIfNeeded(context)
        val second = EngineDataMigrationWave1.copyPlacesIfNeeded(context)

        assertEquals(1, first.copied)
        assertEquals(0, second.copied)
        assertTrue(second.alreadyDone)

        val schema = PlacesAspectSeeder.ensureSeeded(context)
        assertEquals(1, db.engineRecordDao().activeByRecordType(schema.recordTypeId).size)
    }
}

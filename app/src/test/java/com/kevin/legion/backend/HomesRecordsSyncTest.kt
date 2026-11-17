package com.kevin.legion.backend

import com.kevin.legion.data.local.Aspect
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.FieldDef
import com.kevin.legion.data.local.FieldType
import com.kevin.legion.data.local.RecordProvenance
import com.kevin.legion.data.local.RecordType
import com.kevin.legion.engine.RecordStore
import com.kevin.legion.testutil.RoomTestReset
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Engine-brain ticket 15: [HomesRecordsSync] against an in-memory engine and Robolectric's Room.
 * Only user aspects go up, parents first, translated; a cut-off backfill resumes; what the
 * engine's tools wrote comes down through [RecordStore] under the engine's guid and is never sent
 * back as a second row, even after the remembered guids are lost. Invented rows.
 */
@RunWith(RobolectricTestRunner::class)
class HomesRecordsSyncTest {
    private val context = RuntimeEnvironment.getApplication()
    private val db get() = CarDatabase.getDatabase(context)

    /** The engine's tables as maps of origin_guid to row, with its own clock. */
    private class FakeEngine : HomesBackend {
        val rows = HomeTable.entries.associateWith { linkedMapOf<String, JsonObject>() }
        val batches = mutableListOf<HomeTable>()
        var failBatches = 0
        private var clock = 1_800_000_000_000L

        fun write(table: HomeTable, row: JsonObject) {
            val stamped = row.toMutableMap()
            stamped["updated_at"] = JsonPrimitive(HomesRows.iso(clock++))
            rows.getValue(table)[HomesFromEngine.guidOf(row).orEmpty()] = JsonObject(stamped)
        }

        override suspend fun batch(table: HomeTable, rows: List<JsonObject>): Result<HomesBatchResult> {
            if (failBatches > 0) {
                failBatches--
                return Result.failure(IllegalStateException("offline"))
            }
            batches += table
            val created = rows.count { HomesFromEngine.guidOf(it) !in this.rows.getValue(table) }
            rows.forEach { write(table, it) }
            return Result.success(
                HomesBatchResult(rows.size, created, rows.size - created, 0, 0, this.rows.getValue(table).size),
            )
        }

        override suspend fun put(table: HomeTable, guid: String, row: JsonObject) = Result.success(write(table, row))

        override suspend fun delete(table: HomeTable, guid: String) = Result.success(Unit)

        override suspend fun changedSince(table: HomeTable, sinceIso: String?): Result<List<JsonObject>> =
            Result.success(
                rows.getValue(table).values.filter { row ->
                    sinceIso == null || HomesFromEngine.textOf(row, "updated_at").orEmpty() >= sinceIso
                },
            )
    }

    private lateinit var engine: FakeEngine

    @Before
    fun setUp() {
        RoomTestReset.resetCarDatabaseSingleton()
        context.getSharedPreferences(HomesSync.PREFS, 0).edit().clear().commit()
        engine = FakeEngine()
    }

    private suspend fun seed(): Long {
        val hikes = db.aspectDao().insert(Aspect(name = "Hikes", createdAt = 1_000, updatedAt = 1_000))
        val hike = db.recordTypeDao().insert(
            RecordType(aspectId = hikes, name = "Hike", createdAt = 1_000, updatedAt = 1_000),
        )
        val trail = db.fieldDefDao().insert(
            FieldDef(recordTypeId = hike, name = "trail", type = FieldType.TEXT, createdAt = 1_000, updatedAt = 1_000),
        )
        // A plugin's aspect: replica rows with typed homes on the engine. Never sent.
        val fleet = db.aspectDao().insert(Aspect(name = "Fleet", createdAt = 500, updatedAt = 500))
        val vehicle = db.recordTypeDao().insert(
            RecordType(aspectId = fleet, name = "Vehicle", createdAt = 500, updatedAt = 500),
        )
        val plate = db.fieldDefDao().insert(
            FieldDef(
                recordTypeId = vehicle, name = "plate", type = FieldType.TEXT, ownerPluginId = "fleet", locked = true,
                createdAt = 500, updatedAt = 500,
            ),
        )
        val store = RecordStore(db.engineRecordDao(), db.fieldDefDao(), db.recordTypeDao())
        store.create(hike, mapOf(trail to "Test ridge"), RecordProvenance.USER, now = 2_000)
        store.create(hike, mapOf(trail to "Test creek"), RecordProvenance.USER, now = 2_001)
        store.create(vehicle, mapOf(plate to "TEST-123"), RecordProvenance.DETERMINISTIC, now = 2_002)
        return trail
    }

    @Test
    fun theBackfillSendsOnlyUserAspectsParentsFirstAndTranslated() = runBlocking {
        val trail = seed()
        val reports = HomesRecordsSync.run(context, engine)
        assertTrue(reports.all { it.clean })
        assertEquals(HomeTable.RECORDS, engine.batches)
        assertEquals(listOf(1, 1, 1, 2), reports.map { it.room })
        assertEquals(listOf(1, 1, 1, 2), HomeTable.RECORDS.map { engine.rows.getValue(it).size })
        val fieldGuid = engine.rows.getValue(HomeTable.USER_FIELD_DEFS).keys.single()
        val payloads = engine.rows.getValue(HomeTable.USER_RECORDS).values.map { it.getValue("payload").jsonObject }
        val trails = payloads.map { it.getValue(fieldGuid).jsonPrimitive.content }.toSet()
        assertEquals(setOf("Test ridge", "Test creek"), trails)
        assertFalse(engine.rows.values.any { table -> table.values.any { "TEST-123" in it.toString() } })
        assertFalse(fieldGuid == trail.toString())
        // Again: the same rows, nothing doubled.
        HomesRecordsSync.run(context, engine)
        assertEquals(listOf(1, 1, 1, 2), HomeTable.RECORDS.map { engine.rows.getValue(it).size })
    }

    @Test
    fun theRealPluginSeedersAspectsNeverTravel() = runBlocking {
        com.kevin.legion.engine.fleet.FleetAspectSeeder.ensureSeeded(context)
        com.kevin.legion.engine.ledger.LedgerAspectSeeder.ensureSeeded(context)
        com.kevin.legion.engine.pantry.PantryAspectSeeder.ensureSeeded(context)
        com.kevin.legion.engine.notes.NotesAspectSeeder.ensureSeeded(context)
        com.kevin.legion.engine.places.PlacesAspectSeeder.ensureSeeded(context)
        com.kevin.legion.engine.dates.DatesAspectSeeder.ensureSeeded(context)
        val snap = HomesRecordsSync.snapshot(context)
        assertTrue(snap.aspects.size >= 6)
        HomeTable.RECORDS.forEach { assertTrue(it.key, snap.rows(it).isEmpty()) }
        // And with their fields' owners wiped (an install seeded before `ownerPluginId`): still none.
        db.fieldDefDao().listAll().forEach { db.fieldDefDao().update(it.copy(ownerPluginId = null, locked = false)) }
        val unowned = HomesRecordsSync.snapshot(context)
        HomeTable.RECORDS.forEach { assertTrue(it.key, unowned.rows(it).isEmpty()) }
    }

    @Test
    fun aCutOffBackfillIsNotMarkedDoneAndTheNextRunCompletesIt() = runBlocking {
        seed()
        engine.failBatches = 1
        val first = HomesRecordsSync.run(context, engine)
        assertFalse(first.first().clean)
        val second = HomesRecordsSync.run(context, engine)
        assertTrue(second.all { it.clean })
        assertEquals(2, engine.rows.getValue(HomeTable.USER_RECORDS).size)
    }

    private fun engineTracker() {
        val at = HomesRows.iso(1_700_000_000_000)
        val clocks = mapOf("created_at_client" to at, "updated_at_client" to at)
        fun row(guid: String, vararg pairs: Pair<String, String>) = buildJsonObject {
            put(HomesRows.ORIGIN_GUID, guid)
            (clocks + pairs).forEach { (k, v) -> put(k, v) }
        }
        engine.write(HomeTable.USER_ASPECTS, row("eng-a", "name" to "Reading"))
        engine.write(HomeTable.USER_RECORD_TYPES, row("eng-t", "aspect" to "eng-a", "name" to "Book"))
        engine.write(
            HomeTable.USER_FIELD_DEFS,
            row("eng-f", "record_type" to "eng-t", "name" to "title", "type" to "TEXT"),
        )
        val entry = row("eng-e", "record_type" to "eng-t", "provenance" to "USER").toMutableMap()
        entry["payload"] = buildJsonObject { put("eng-f", "Test novel") }
        engine.write(HomeTable.USER_RECORDS, JsonObject(entry))
    }

    @Test
    fun whatTheEngineWroteComesDownAndIsNeverSentBackAsASecondRow() = runBlocking {
        engineTracker()
        HomesRecordsSync.run(context, engine)
        val reading = db.aspectDao().listAll().single { it.name == "Reading" }
        val book = db.recordTypeDao().listByAspect(reading.id).single()
        val title = db.fieldDefDao().forRecordType(book.id).single()
        val record = db.engineRecordDao().getByGuid("eng-e")
        assertNotNull(record)
        assertTrue(record!!.payload.contains("Test novel") && record.payload.contains("\"${title.id}\""))
        assertEquals(listOf(1, 1, 1, 1), HomeTable.RECORDS.map { engine.rows.getValue(it).size })
        // The remembered guids are lost (a restored backup): the pull finds the same rows by
        // creation instant and name, and nothing is inserted or sent twice.
        context.getSharedPreferences(HomesSync.PREFS, 0).edit().clear().commit()
        HomesRecordsSync.run(context, engine)
        assertEquals(1, db.aspectDao().listAll().count { it.name == "Reading" })
        assertEquals(listOf(1, 1, 1, 1), HomeTable.RECORDS.map { engine.rows.getValue(it).size })
    }

    @Test
    fun anEntryTheEngineTrashedIsTrashedInRoom() = runBlocking {
        engineTracker()
        HomesRecordsSync.run(context, engine)
        val row = engine.rows.getValue(HomeTable.USER_RECORDS).getValue("eng-e").toMutableMap()
        row["trashed_at"] = JsonPrimitive(HomesRows.iso(1_800_000_000_000))
        row["updated_at_client"] = JsonPrimitive(HomesRows.iso(4_000_000_000_000))
        engine.write(HomeTable.USER_RECORDS, JsonObject(row))
        HomesRecordsSync.run(context, engine)
        assertNotNull(db.engineRecordDao().getByGuid("eng-e")!!.deletedAt)
    }

    @Test
    fun theWriteThroughSendsOnlyWhatChangedSinceTheLastCleanPush() = runBlocking {
        val trail = seed()
        HomesRecordsSync.run(context, engine)
        engine.batches.clear()
        val hike = db.recordTypeDao().listAll().single { it.name == "Hike" }
        val store = RecordStore(db.engineRecordDao(), db.fieldDefDao(), db.recordTypeDao())
        store.create(hike.id, mapOf(trail to "Test summit"), RecordProvenance.USER)
        val reports = HomesRecordsSync.pushChanged(context, engine)
        assertEquals(listOf(0, 0, 0, 1), reports.map { it.sent })
        assertEquals(3, engine.rows.getValue(HomeTable.USER_RECORDS).size)
    }
}

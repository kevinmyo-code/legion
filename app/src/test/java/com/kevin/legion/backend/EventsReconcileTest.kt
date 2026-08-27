package com.kevin.legion.backend

import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.ListItemSkip
import com.kevin.legion.data.local.RecordProvenance
import com.kevin.legion.engine.RecordStore
import com.kevin.legion.engine.dates.DatesAspectSeeder
import com.kevin.legion.engine.notes.NotesAspectSeeder
import com.kevin.legion.testutil.RoomTestReset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * EventsReconcile - the Phase 4 step 1/2 job for Notes+Dates, cut over TOGETHER because the merge
 * itself only has to happen once (`.scratch/backend-erp/issues/05-migration-path.md`, ticket 01
 * ruling 4). Exercised entirely against an in-memory FakeEventsBackend and a real (Robolectric)
 * engine, never a network - same posture as [PlacesReconcileTest]/`PantryReconcileTest`.
 *
 * **The merge test (`a Dates Event and a Notes Item both land as correct events rows`) is the one
 * that matters most** - see [EventsReconcile]'s own class doc for the undated-item ruling this
 * suite also covers.
 */
@RunWith(RobolectricTestRunner::class)
class EventsReconcileTest {
    private val context = RuntimeEnvironment.getApplication()

    private class FakeEventsBackend(
        var uploadFails: Boolean = false,
    ) : EventsBackend {
        val rows = mutableMapOf<String, RemoteEvent>()
        val skips = mutableMapOf<String, MutableList<Long>>()
        var clock = 1_000L
        var nextId = 0

        override suspend fun fetchActive(): Result<List<RemoteEvent>> =
            Result.success(rows.values.filterNot { it.deleted })

        override suspend fun upsert(serverId: String?, fields: EventFields): Result<RemoteEvent> {
            val id = serverId ?: "server-${nextId++}"
            val row = fields.toRemote(id, originGuid = rows[id]?.originGuid)
            rows[id] = row
            return Result.success(row)
        }

        override suspend fun softDelete(serverId: String): Result<Boolean> {
            val existing = rows[serverId] ?: return Result.success(false)
            if (existing.deleted) return Result.success(false)
            rows[serverId] = existing.copy(deleted = true)
            return Result.success(true)
        }

        override suspend fun skipOccurrence(serverId: String, skipDateEpochMs: Long): Result<Unit> {
            skips.getOrPut(serverId) { mutableListOf() }.add(skipDateEpochMs)
            return Result.success(Unit)
        }

        override suspend fun fetchSkips(serverId: String): Result<List<Long>> =
            Result.success(skips[serverId].orEmpty())

        override suspend fun uploadMigratedEvent(event: MigratedEvent): Result<Boolean> {
            if (uploadFails) return Result.failure(EventsBackendException("simulated network failure"))
            if (rows.values.any { it.originGuid == event.originGuid }) return Result.success(false)
            val id = "server-${nextId++}"
            rows[id] = event.fields.toRemote(id, originGuid = event.originGuid)
            if (event.skipDatesEpochMs.isNotEmpty()) skips.getOrPut(id) { mutableListOf() }.addAll(event.skipDatesEpochMs)
            return Result.success(true)
        }

        private fun EventFields.toRemote(id: String, originGuid: String?) = RemoteEvent(
            serverId = id,
            title = title,
            startsAtMs = startsAtMs,
            endsAtMs = endsAtMs,
            allDay = allDay,
            location = location,
            notes = notes,
            source = source,
            googleEventId = googleEventId,
            done = done,
            doneAtMs = doneAtMs,
            sortOrder = sortOrder,
            triggerPlaceLabel = triggerPlaceLabel,
            repeatKind = repeatKind,
            repeatEvery = repeatEvery,
            repeatDaysOfWeek = repeatDaysOfWeek,
            repeatDay = repeatDay,
            repeatMonth = repeatMonth,
            repeatEndKind = repeatEndKind,
            repeatEndDateMs = repeatEndDateMs,
            repeatEndCount = repeatEndCount,
            exact = exact,
            exactDowngraded = exactDowngraded,
            missedAtMs = missedAtMs,
            missedDismissedAtMs = missedDismissedAtMs,
            loggedAtMs = loggedAtMs,
            updatedAtMs = ++clock,
            deleted = false,
            originGuid = originGuid,
        )
    }

    @Before
    fun clearState() {
        RoomTestReset.resetCarDatabaseSingleton()
    }

    private suspend fun createDatesEvent(title: String, startMs: Long, location: String? = null): Long {
        val db = CarDatabase.getDatabase(context)
        val sch = DatesAspectSeeder.ensureSeeded(context)
        val store = RecordStore(db.engineRecordDao(), db.fieldDefDao(), db.recordTypeDao())
        val result = store.create(
            sch.recordTypeId,
            mapOf(
                sch.fieldIds.getValue(DatesAspectSeeder.FIELD_TITLE) to title,
                sch.fieldIds.getValue(DatesAspectSeeder.FIELD_START) to startMs,
                sch.fieldIds.getValue(DatesAspectSeeder.FIELD_SOURCE) to DatesAspectSeeder.SOURCE_LEGION,
                sch.fieldIds.getValue(DatesAspectSeeder.FIELD_LOCATION) to location,
            ),
            RecordProvenance.USER,
        )
        return (result as RecordStore.WriteResult.Success).recordId
    }

    private suspend fun createNotesItem(
        text: String,
        startsAt: Long? = null,
        done: Boolean = false,
        repeatKind: String? = null,
        repeatEvery: Int? = null,
    ): Long {
        val db = CarDatabase.getDatabase(context)
        val sch = NotesAspectSeeder.ensureSeeded(context)
        val store = RecordStore(db.engineRecordDao(), db.fieldDefDao(), db.recordTypeDao())
        val values = mutableMapOf<Long, Any?>(
            sch.fieldIds.getValue(NotesAspectSeeder.FIELD_TEXT) to text,
            sch.fieldIds.getValue(NotesAspectSeeder.FIELD_DONE) to done,
        )
        if (startsAt != null) values[sch.fieldIds.getValue(NotesAspectSeeder.FIELD_STARTS_AT)] = startsAt
        if (repeatKind != null) values[sch.fieldIds.getValue(NotesAspectSeeder.FIELD_REPEAT_KIND)] = repeatKind
        if (repeatEvery != null) values[sch.fieldIds.getValue(NotesAspectSeeder.FIELD_REPEAT_EVERY)] = repeatEvery
        val result = store.create(sch.recordTypeId, values, RecordProvenance.USER)
        return (result as RecordStore.WriteResult.Success).recordId
    }

    @Test
    fun `a Dates Event and a Notes Item both land as correct events rows, every mapped field carried`() = runBlocking {
        createDatesEvent("Dentist", startMs = 50_000L, location = "Downtown clinic")
        createNotesItem("Buy milk", startsAt = 60_000L, done = true)
        val backend = FakeEventsBackend()

        val report = EventsReconcile.run(context, backend).getOrThrow()

        assertEquals(1, report.datesEngineCount)
        assertEquals(1, report.notesEngineCount)
        assertEquals(2, report.uploaded)
        assertTrue(report.isClean)

        val dentist = backend.rows.values.single { it.title == "Dentist" }
        assertEquals(50_000L, dentist.startsAtMs)
        assertEquals("Downtown clinic", dentist.location)
        assertEquals("legion", dentist.source)
        assertFalse(dentist.done)

        val milk = backend.rows.values.single { it.title == "Buy milk" }
        assertEquals(60_000L, milk.startsAtMs)
        assertTrue(milk.done)
        assertEquals(null, milk.location)

        val replica = CarDatabase.getDatabase(context).eventReplicaDao().getAllActive()
        assertEquals(2, replica.size)
        val replicaMilk = replica.single { it.title == "Buy milk" }
        assertTrue(replicaMilk.done)
        assertEquals(60_000L, replicaMilk.startsAt)
    }

    @Test
    fun `a Notes Item with no startsAt is uploaded with a null start, not skipped`() = runBlocking {
        createNotesItem("Call the vet")
        createNotesItem("Buy milk", startsAt = 60_000L)
        val backend = FakeEventsBackend()

        val report = EventsReconcile.run(context, backend).getOrThrow()

        assertEquals(2, report.notesEngineCount)
        assertEquals(2, report.uploaded)
        assertEquals(1, report.uploadedUndated)
        assertTrue("an undated item is an ordinary row now, not an exception", report.isClean)

        val vet = backend.rows.values.single { it.title == "Call the vet" }
        assertEquals(null, vet.startsAtMs)

        val replicaVet = CarDatabase.getDatabase(context).eventReplicaDao().getAllActive().single { it.title == "Call the vet" }
        assertEquals(null, replicaVet.startsAt)
    }

    @Test
    fun `isClean is still false for a genuine one-sided row left over on the server`() = runBlocking {
        createDatesEvent("Dentist", startMs = 50_000L)
        val backend = FakeEventsBackend()
        // Simulate a migrated row whose engine original has since vanished - onlyOnServer.
        backend.uploadMigratedEvent(
            MigratedEvent(originGuid = "ghost-guid", fields = EventFields(title = "Ghost", startsAtMs = 1_000L)),
        )

        val report = EventsReconcile.run(context, backend).getOrThrow()

        assertFalse("a row on the server with no matching engine guid is a genuine diff, not the undated exception", report.isClean)
        assertTrue(report.onlyOnServer.contains("ghost-guid"))
    }

    @Test
    fun `getAllActive orders dated rows before undated ones - NULLS LAST, not SQLite's default NULLS FIRST`() = runBlocking {
        createNotesItem("undated task")
        createDatesEvent("later dated thing", startMs = 90_000L)
        createNotesItem("earlier dated thing", startsAt = 10_000L)
        val backend = FakeEventsBackend()

        EventsReconcile.run(context, backend).getOrThrow()

        val ordered = CarDatabase.getDatabase(context).eventReplicaDao().getAllActive()
        assertEquals(3, ordered.size)
        // Both dated rows, earliest first, ahead of the undated one - never the raw SQLite
        // default of NULLS FIRST, which would float "undated task" to the head of the list.
        assertEquals("earlier dated thing", ordered[0].title)
        assertEquals("later dated thing", ordered[1].title)
        assertEquals("undated task", ordered[2].title)
        assertEquals(null, ordered[2].startsAt)
    }

    @Test
    fun `running it twice is idempotent - zero new uploads, same server and replica state`() = runBlocking {
        createDatesEvent("Dentist", startMs = 50_000L)
        createNotesItem("Buy milk", startsAt = 60_000L)
        val backend = FakeEventsBackend()

        val first = EventsReconcile.run(context, backend).getOrThrow()
        val second = EventsReconcile.run(context, backend).getOrThrow()

        assertEquals(2, first.uploaded)
        assertEquals(0, second.uploaded)
        assertEquals(first.serverCountAfter, second.serverCountAfter)
        assertEquals(first.replicaCountAfter, second.replicaCountAfter)
        assertTrue(second.isClean)
        assertEquals(2, backend.rows.size)
    }

    @Test
    fun `a repeating item's skips round-trip through the server into the replica`() = runBlocking {
        val itemId = createNotesItem("Take out trash", startsAt = 10_000L, repeatKind = "WEEKLY", repeatEvery = 1)
        CarDatabase.getDatabase(context).listItemSkipDao().insert(ListItemSkip(itemId = itemId, skippedDate = 20_000L))
        val backend = FakeEventsBackend()

        EventsReconcile.run(context, backend).getOrThrow()

        val serverId = backend.rows.values.single { it.title == "Take out trash" }.serverId
        assertEquals(listOf(20_000L), backend.skips[serverId])

        val replicaSkips = CarDatabase.getDatabase(context).eventSkipReplicaDao().forEvent(serverId)
        assertEquals(listOf(20_000L), replicaSkips)
    }

    @Test
    fun `a failed upload short-circuits into failure and touches neither replica nor server further`() = runBlocking {
        createDatesEvent("Dentist", startMs = 50_000L)
        val backend = FakeEventsBackend(uploadFails = true)

        val result = EventsReconcile.run(context, backend)

        assertTrue(result.isFailure)
        assertTrue(CarDatabase.getDatabase(context).eventReplicaDao().getAllActive().isEmpty())
    }

    @Test
    fun `never deletes or trashes either engine record type - the engine stays the truth until the diff is clean`() = runBlocking {
        createDatesEvent("Dentist", startMs = 50_000L)
        createNotesItem("Buy milk", startsAt = 60_000L)
        val backend = FakeEventsBackend()
        val db = CarDatabase.getDatabase(context)
        val datesSch = DatesAspectSeeder.ensureSeeded(context)
        val notesSch = NotesAspectSeeder.ensureSeeded(context)

        EventsReconcile.run(context, backend).getOrThrow()

        assertEquals(1, db.engineRecordDao().activeByRecordType(datesSch.recordTypeId).size)
        assertEquals(1, db.engineRecordDao().activeByRecordType(notesSch.recordTypeId).size)
    }
}

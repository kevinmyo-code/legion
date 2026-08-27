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

        // Mirrors public.events.created_at's real behaviour: a caller-supplied value (a migrated
        // row's real prior creation time, or an update echoing its own unchanged createdAt) wins;
        // failing that, an EXISTING row keeps its own createdAt untouched (an update omitting the
        // column server-side); only a genuinely new row with nothing supplied gets "now" (the
        // clock, standing in for Postgres's own `default now()`) - the exact server behaviour the
        // uploadMigratedEvent fix exists to avoid relying on.
        private fun EventFields.toRemote(id: String, originGuid: String?) = RemoteEvent(
            serverId = id,
            title = title,
            createdAtMs = createdAtMs ?: rows[id]?.createdAtMs ?: ++clock,
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

    /**
     * The assertion whose absence would have shipped the defect the coordinator caught:
     * [SupabaseEventsBackend.uploadMigratedEvent] used to insert with no `created_at` at all,
     * silently taking Postgres's own `default now()` - meaning every migrated row's creation time
     * became "the moment the migration ran" rather than the note's real age. [GoalChecklistSync]'s
     * "already materialized today" gate and [LogDigestBuilder]'s FRESH/AGING/STALE buckets both key
     * off exactly this field, so a wrong value here is not cosmetic. [FakeEventsBackend.toRemote]
     * reproduces the real bug shape faithfully: an omitted `createdAtMs` becomes the fake's own
     * migration-time `clock`, standing in for the server's `now()` - so this test fails before the
     * [EventsReconcile] fix (which threads `record.createdAt` through) and passes after it.
     */
    @Test
    fun `uploadMigratedEvent carries the engine record's real createdAt, not the migration moment`() = runBlocking {
        val dentistId = createDatesEvent("Dentist", startMs = 50_000L)
        val db = CarDatabase.getDatabase(context)
        val originalCreatedAt = db.engineRecordDao().getById(dentistId)!!.createdAt
        val backend = FakeEventsBackend()
        // Stand in for "the migration ran much later than the note was created" - if the fix
        // regresses, the uploaded row's createdAt would read as (approximately) this clock instead
        // of the real, much-earlier originalCreatedAt.
        backend.clock = 999_999_000L

        EventsReconcile.run(context, backend).getOrThrow()

        val dentist = backend.rows.values.single { it.title == "Dentist" }
        assertEquals(
            "a migrated row's created_at must be the engine record's own creation time, not the migration run's clock",
            originalCreatedAt,
            dentist.createdAtMs,
        )
        assertTrue(
            "must not have fallen through to the fake's migration-time clock stand-in",
            dentist.createdAtMs != backend.clock,
        )

        val replicaRow = db.eventReplicaDao().getAllActive().single { it.title == "Dentist" }
        assertEquals(originalCreatedAt, replicaRow.createdAt)
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
    fun `a refilled replica row carries the originating engine record's own id, not a fresh autoincrement one`() = runBlocking {
        val dentistEngineId = createDatesEvent("Dentist", startMs = 50_000L)
        val milkEngineId = createNotesItem("Buy milk", startsAt = 60_000L)
        val backend = FakeEventsBackend()

        EventsReconcile.run(context, backend).getOrThrow()

        val replica = CarDatabase.getDatabase(context).eventReplicaDao().getAllActive()
        val dentist = replica.single { it.title == "Dentist" }
        val milk = replica.single { it.title == "Buy milk" }
        assertEquals("the replica id must equal the engine records.id it came from, not a reminted one", dentistEngineId, dentist.id)
        assertEquals("the replica id must equal the engine records.id it came from, not a reminted one", milkEngineId, milk.id)
    }

    @Test
    fun `ids are stable across two reconciles - this is the regression test for the wholesale-refresh remint defect`() = runBlocking {
        createDatesEvent("Dentist", startMs = 50_000L)
        createNotesItem("Buy milk", startsAt = 60_000L)
        val backend = FakeEventsBackend()

        EventsReconcile.run(context, backend).getOrThrow()
        val idsAfterFirst = CarDatabase.getDatabase(context).eventReplicaDao().getAllActive()
            .associate { it.serverId to it.id }

        EventsReconcile.run(context, backend).getOrThrow()
        val idsAfterSecond = CarDatabase.getDatabase(context).eventReplicaDao().getAllActive()
            .associate { it.serverId to it.id }

        // Before the fix, EventsReconcile.run wipes events_replica and refills it from scratch on
        // EVERY call (`deleteAllForReplicaRefresh` immediately precedes the upsert loop), so
        // EventReplicaDao.upsert's getByServerId lookup always misses and every row reminted a
        // fresh autoincremented id on every single reconcile - exactly the defect ticket 11 exists
        // to fix. A caller holding onto `ListItem.id` (an AlarmManager PendingIntent request code,
        // a notification id, a soft foreign key from list_item_skips/muted_reminders) would have
        // that id silently invalidated under it by the very next background reconcile.
        assertEquals("id-per-serverId must be identical across two reconciles, not just the counts", idsAfterFirst, idsAfterSecond)
    }

    @Test
    fun `a server-only row with no origin_guid still gets a sane autoincremented id and does not throw`() = runBlocking {
        val backend = FakeEventsBackend()
        // Created directly against the server, never through uploadMigratedEvent - originGuid is
        // null, same as anything written post-cutover through EventsBackend.upsert. There is no
        // engine ancestor to derive an id from, so it must fall through to autoincrement rather
        // than throwing on a null lookup.
        backend.upsert(serverId = null, fields = EventFields(title = "Standalone", startsAtMs = 5_000L))

        val report = EventsReconcile.run(context, backend).getOrThrow()

        assertTrue(report.isClean)
        val replica = CarDatabase.getDatabase(context).eventReplicaDao().getAllActive()
        val standalone = replica.single { it.title == "Standalone" }
        assertTrue("a row with no engine ancestor must still get a real, non-zero local id", standalone.id != 0L)
    }

    @Test
    fun `a row with a derivable id is seated before any id is allocated, so the carry wins and the ancestor-less row moves instead`() = runBlocking {
        // The contest this test exists to settle. The orphan has an origin_guid that resolves to
        // NOTHING in the current engine set (its engine original was deleted, or never existed
        // here), so it cannot derive an id and must autoincrement. It is given insertion order
        // AHEAD of the real engine record's upload, so a naive single-pass refill over the
        // just-emptied table would seat it on id 1 first - precisely the id the real record
        // (records.id = 1, first row in a fresh engine table) needs to carry.
        //
        // Getting this wrong is not a cosmetic id shuffle. `upsert`'s collision guard would
        // correctly decline to clobber the orphan and hand the Dentist row a fresh id, which is
        // the exact alarm orphaning the carry exists to prevent, reached by a different route.
        // The two-pass refill removes the contest rather than adjudicating it.
        val backend = FakeEventsBackend()
        backend.uploadMigratedEvent(
            MigratedEvent(originGuid = "no-longer-in-engine", fields = EventFields(title = "Orphaned migrated row", startsAtMs = 1_000L)),
        )
        val dentistEngineId = createDatesEvent("Dentist", startMs = 50_000L)
        assertEquals(1L, dentistEngineId)

        EventsReconcile.run(context, backend).getOrThrow()

        val replica = CarDatabase.getDatabase(context).eventReplicaDao().getAllActive()
        assertEquals("both rows must survive - neither clobbers the other", 2, replica.size)
        val orphan = replica.single { it.title == "Orphaned migrated row" }
        val dentist = replica.single { it.title == "Dentist" }
        assertEquals(
            "the Dentist row has a derivable id and must be seated at it, even though the orphan was ahead of it in server order",
            dentistEngineId,
            dentist.id,
        )
        assertTrue(
            "the orphan has nothing pointing at its local id yet, so it is the row that can afford to move",
            orphan.id != dentistEngineId && orphan.id != 0L,
        )
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

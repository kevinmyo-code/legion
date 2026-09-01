package com.kevin.legion.backend

import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.Event
import com.kevin.legion.data.local.ListItemSkip
import com.kevin.legion.data.local.RecordProvenance
import com.kevin.legion.engine.RecordStore
import com.kevin.legion.engine.dates.DatesAspectSeeder
import com.kevin.legion.engine.notes.NotesAspectSeeder
import com.kevin.legion.testutil.RoomTestReset
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.UUID

/**
 * EventsReconcile - the Phase 4 step 1/2 job for Notes+Dates, cut over TOGETHER because the merge
 * itself only has to happen once (`.scratch/backend-erp/issues/05-migration-path.md`, ticket 01
 * ruling 4). Exercised against an in-memory FakeEventsBackend, a real (Robolectric) engine for the
 * Notes half, and the real (Robolectric) local `events` table for the Dates half - never a network -
 * same posture as [PlacesReconcileTest]/`PantryReconcileTest`.
 *
 * **The merge test (`a Dates Event and a Notes Item both land as correct events rows`) is the one
 * that matters most** - see [EventsReconcile]'s own class doc for the undated-item ruling this
 * suite also covers.
 *
 * **CORRECTED 2026-08-28 (coordinator follow-up on backend-erp ticket 17).** [createDatesEvent]
 * used to build a Dates `Event` through the ENGINE ([RecordStore]) because that was where
 * [EventsReconcile]'s Dates branch read from. That branch now reads the local `events` table
 * directly (`kind = appointment`) - see [EventsReconcile]'s own class doc for why - so this helper
 * was rewritten to insert straight into `events` instead, matching what
 * [com.kevin.legion.calendar.CalendarImportController] itself does post-repoint. Every test below
 * that calls it is exercising the SAME regression the coordinator caught: before this fix, an
 * appointment built this way would have been invisible to [EventsReconcile.run] entirely.
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

        // Mirrors SupabaseEventsBackend.uploadMigratedEvent's own dual-key existence check
        // (`.scratch/backend-erp/issues/20-*.md`): public.events enforces TWO unique keys,
        // origin_guid AND a partial-unique google_event_id (where google_event_id is not null),
        // so "already present" must check both - and the google_event_id check must never fire for
        // a null value, exactly matching the server's own partial-index predicate.
        override suspend fun uploadMigratedEvent(event: MigratedEvent): Result<Boolean> {
            if (uploadFails) return Result.failure(EventsBackendException("simulated network failure"))
            if (rows.values.any { it.originGuid == event.originGuid }) return Result.success(false)
            val googleEventId = event.fields.googleEventId
            if (googleEventId != null && rows.values.any { it.googleEventId == googleEventId }) {
                return Result.success(false)
            }
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
            structuredMeta = structuredMeta,
            source = source,
            kind = kind,
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
        // Ticket 20's Q2 ruling: EventsReconcile now remembers the last WITHHELD retraction set in
        // an object-level var so a second, deliberate run can consent to it (see that field's own
        // doc comment). Robolectric normally reuses this class's classloader across every @Test
        // method in this file, so without this reset one test's withheld warning could leak into
        // an unrelated test's fixture data and be silently "confirmed" by it.
        EventsReconcile.resetPendingRetractionForTests()
    }

    /** Inserts a Dates `Event` row directly into the local `events` table, `kind = appointment` -
     * the shape [com.kevin.legion.calendar.CalendarImportController.buildEventRow] itself produces
     * post-repoint, and the shape [EventsReconcile]'s Dates branch now reads. Mints a fresh [Event.guid]
     * per row, matching [CalendarImportController]'s own "minted once at creation, never reused"
     * posture (see [Event.guid]'s own doc comment) - each call produces a row with its own
     * independent identity, exactly like two separately-imported Google occurrences would. Returns
     * the row's own local [Event.id]. */
    private suspend fun createDatesEvent(
        title: String,
        startMs: Long,
        location: String? = null,
        allDay: Boolean? = null,
        structuredMeta: String? = null,
    ): Long {
        val db = CarDatabase.getDatabase(context)
        val now = System.currentTimeMillis()
        return db.eventDao().insert(
            Event(
                serverId = UUID.randomUUID().toString(),
                guid = UUID.randomUUID().toString(),
                title = title,
                startsAt = startMs,
                allDay = allDay ?: false,
                location = location,
                structuredMeta = structuredMeta,
                source = DatesAspectSeeder.SOURCE_LEGION,
                kind = EventKind.EVENT,
                updatedAtMs = now,
                createdAt = now,
            ),
        )
    }

    /** Burns through [times] worth of `events` table AUTOINCREMENT values (insert then hard
     * delete, real SQLite `AUTOINCREMENT` never reuses a value once issued - confirmed against the
     * generated schema) so a subsequently-created [Event] lands comfortably clear of any small
     * `records.id` a fixture in the SAME test also uses. Exists ONLY to keep the id-STABILITY tests
     * below isolated from the id-COLLISION question `a Dates appointment and a Notes reminder whose
     * ids coincidentally collide never corrupt either row` covers on its own - both `events.id` and
     * `records.id` start fresh at 1 in an empty Robolectric DB regardless of which table a test
     * writes to first, so ordinary fixture ordering cannot avoid the collision by itself (confirmed
     * by hand while building this fix - the first attempt at this, reordering `createNotesItem`
     * before `createDatesEvent`, still collided, cascading into a second collision one row later). */
    private suspend fun burnEventAutoincrement(times: Int = 20) {
        val db = CarDatabase.getDatabase(context)
        repeat(times) {
            val id = db.eventDao().insert(
                Event(
                    serverId = UUID.randomUUID().toString(),
                    guid = UUID.randomUUID().toString(),
                    title = "burn",
                    startsAt = null,
                    source = DatesAspectSeeder.SOURCE_LEGION,
                    kind = EventKind.EVENT,
                    updatedAtMs = 0L,
                    createdAt = 0L,
                ),
            )
            db.eventDao().deleteById(id)
        }
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
    fun `uploadMigratedEvent carries the local row's real createdAt, not the migration moment`() = runBlocking {
        val dentistId = createDatesEvent("Dentist", startMs = 50_000L)
        val db = CarDatabase.getDatabase(context)
        val originalCreatedAt = db.eventDao().getById(dentistId)!!.createdAt
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

        val replicaRow = db.eventDao().getAllActive().single { it.title == "Dentist" }
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

        val replica = CarDatabase.getDatabase(context).eventDao().getAllActive()
        assertEquals(2, replica.size)
        val replicaMilk = replica.single { it.title == "Buy milk" }
        assertTrue(replicaMilk.done)
        assertEquals(60_000L, replicaMilk.startsAt)
    }

    /**
     * Ticket 11's 2026-08-27 ruling #1, and the fix for the 2026-08-26 incident's OTHER root
     * cause (`events.kind` did not exist, so `NotesController`'s read could not tell a reminder
     * from an appointment merged into the same table). Both the server row AND the replica row
     * must carry the right value - a mismatch between the two would silently defeat
     * `NotesController.allEngineItems`'s `getActiveByKind` filter.
     */
    @Test
    fun `a Notes Item uploads kind reminder, a Dates Event uploads kind appointment`() = runBlocking {
        createDatesEvent("Dentist", startMs = 50_000L)
        createNotesItem("Buy milk", startsAt = 60_000L)
        val backend = FakeEventsBackend()

        EventsReconcile.run(context, backend).getOrThrow()

        val dentist = backend.rows.values.single { it.title == "Dentist" }
        val milk = backend.rows.values.single { it.title == "Buy milk" }
        assertEquals(EventKind.EVENT, dentist.kind)
        assertEquals(EventKind.REMINDER, milk.kind)

        val replica = CarDatabase.getDatabase(context).eventDao().getAllActive()
        assertEquals(EventKind.EVENT, replica.single { it.title == "Dentist" }.kind)
        assertEquals(EventKind.REMINDER, replica.single { it.title == "Buy milk" }.kind)
    }

    /**
     * The assertion whose absence let the coordinator-caught defect ship: this file's own Dates
     * `Event` branch used to hardcode `allDay = false` for every row regardless of what the engine
     * field actually held, so an all-day Google import silently uploaded as a timed one. Without
     * this test the hardcoded value and a genuinely-false event were indistinguishable.
     */
    @Test
    fun `an all-day Dates event uploads all_day true, not the old hardcoded false`() = runBlocking {
        createDatesEvent("Company holiday", startMs = 50_000L, allDay = true)
        createDatesEvent("Standup", startMs = 51_000L, allDay = false)
        val backend = FakeEventsBackend()

        EventsReconcile.run(context, backend).getOrThrow()

        val holiday = backend.rows.values.single { it.title == "Company holiday" }
        val standup = backend.rows.values.single { it.title == "Standup" }
        assertTrue("an all-day Dates event must upload all_day = true", holiday.allDay)
        assertFalse("a timed Dates event must still upload all_day = false", standup.allDay)
    }

    /**
     * The `LEGION::v1` block, already parsed by `CalendarImportController` into its own engine
     * field, must reach `public.events.structured_meta` as the same JSON text - not dropped, and
     * not re-encoded into something else on the way through this file's Dates branch.
     */
    @Test
    fun `a Dates event's structured meta block reaches the server as the same JSON text`() = runBlocking {
        val json = """{"course":"COSC4320","source":"canvas_verified"}"""
        createDatesEvent("Midterm", startMs = 50_000L, structuredMeta = json)
        createDatesEvent("Plain event", startMs = 51_000L)
        val backend = FakeEventsBackend()

        EventsReconcile.run(context, backend).getOrThrow()

        val midterm = backend.rows.values.single { it.title == "Midterm" }
        val plain = backend.rows.values.single { it.title == "Plain event" }
        assertEquals(json, midterm.structuredMeta)
        assertEquals(
            "an event with no LEGION::v1 block must upload a null structured_meta, never an invented one",
            null,
            plain.structuredMeta,
        )
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

        val replicaVet = CarDatabase.getDatabase(context).eventDao().getAllActive().single { it.title == "Call the vet" }
        assertEquals(null, replicaVet.startsAt)
    }

    /**
     * SUPERSEDED 2026-08-27 by ticket 11's ruling #2 - this scenario used to be reported as a
     * genuine `onlyOnServer` diff and left lingering server-side forever. It is now the exact case
     * the deletion propagation pass exists to clean up: a server row whose `origin_guid` names an
     * engine record that is not (or is no longer) in the active set gets soft-deleted, so the
     * "leftover" disappears from `onlyOnServer` by being retracted rather than by being ignored.
     * See `a server row whose origin_guid names a trashed engine record is soft-deleted, never a
     * lingering onlyOnServer diff` below for the replacement.
     */
    @Test
    fun `isClean stays true - a one-sided server row is now retracted, not left as a lingering diff`() = runBlocking {
        createDatesEvent("Dentist", startMs = 50_000L)
        val backend = FakeEventsBackend()
        // Simulate a migrated row whose engine original has since vanished.
        backend.uploadMigratedEvent(
            MigratedEvent(originGuid = "ghost-guid", fields = EventFields(title = "Ghost", startsAtMs = 1_000L)),
        )

        val report = EventsReconcile.run(context, backend).getOrThrow()

        assertTrue(
            "the ghost row is retracted (soft-deleted) this run, so it is no longer a lingering diff",
            report.isClean,
        )
        assertTrue(
            "onlyOnServer must not report a row this same run just retracted",
            report.onlyOnServer.isEmpty(),
        )
        assertEquals(1, report.deletedOnServer)
        assertTrue("the ghost row must actually be soft-deleted server-side", backend.rows.getValue("server-0").deleted)
    }

    @Test
    fun `a server row whose origin_guid names a trashed engine record is soft-deleted, never a lingering onlyOnServer diff`() = runBlocking {
        val db = CarDatabase.getDatabase(context)
        val store = RecordStore(db.engineRecordDao(), db.fieldDefDao(), db.recordTypeDao())
        val itemId = createNotesItem("Buy milk", startsAt = 60_000L)
        val backend = FakeEventsBackend()
        EventsReconcile.run(context, backend).getOrThrow()
        val serverIdBeforeTrash = backend.rows.values.single { it.title == "Buy milk" }.serverId
        assertFalse("must not be deleted yet - the engine record is still active", backend.rows.getValue(serverIdBeforeTrash).deleted)

        // Trash the engine record - the phone-side "I deleted this todo" action.
        store.delete(itemId)

        val report = EventsReconcile.run(context, backend).getOrThrow()

        assertEquals(1, report.deletedOnServer)
        assertTrue(
            "a server row whose origin_guid names a now-trashed engine record must be soft-deleted",
            backend.rows.getValue(serverIdBeforeTrash).deleted,
        )
        assertTrue(
            "the replica refill must not resurrect the just-retracted row - this is the actual fix for the 2026-08-26 incident",
            CarDatabase.getDatabase(context).eventDao().getAllActive().none { it.title == "Buy milk" },
        )
    }

    @Test
    fun `a server row with a NULL origin_guid is never soft-deleted, even when nothing local matches it - the safety property`() = runBlocking {
        val backend = FakeEventsBackend()
        // Created directly against the server (no origin_guid) - the phone has no standing to
        // retract this, per ticket 11's ruling #2's own bound: "a row with a NULL origin_guid is
        // never touched - it was created somewhere else and this phone has no standing to delete
        // it." Nothing local matches it (no engine record anywhere names it), which is precisely
        // the condition the bound has to hold under - if it did not, EVERY laptop-authored row
        // would be deleted by the very first phone reconcile that ever ran.
        backend.upsert(serverId = null, fields = EventFields(title = "Authored elsewhere", startsAtMs = 5_000L))

        val report = EventsReconcile.run(context, backend).getOrThrow()

        assertEquals(
            "a NULL origin_guid row must never be counted as retracted",
            0,
            report.deletedOnServer,
        )
        assertFalse(
            "a NULL origin_guid row must survive untouched",
            backend.rows.values.single { it.title == "Authored elsewhere" }.deleted,
        )
    }

    @Test
    fun `getAllActive orders dated rows before undated ones - NULLS LAST, not SQLite's default NULLS FIRST`() = runBlocking {
        createNotesItem("undated task")
        createDatesEvent("later dated thing", startMs = 90_000L)
        createNotesItem("earlier dated thing", startsAt = 10_000L)
        val backend = FakeEventsBackend()

        EventsReconcile.run(context, backend).getOrThrow()

        val ordered = CarDatabase.getDatabase(context).eventDao().getAllActive()
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

        val replicaSkips = CarDatabase.getDatabase(context).eventSkipDao().forEvent(serverId)
        assertEquals(listOf(20_000L), replicaSkips)
    }

    @Test
    fun `a failed upload short-circuits into failure and touches neither replica nor server further`() = runBlocking {
        // CORRECTED 2026-08-28: createDatesEvent now writes straight into `events` (matching
        // CalendarImportController post-repoint), so the local row exists BEFORE the reconcile ever
        // runs - "touches nothing further" now means the source row survives untouched, not that
        // the table stays empty (which it never was, pre-repoint the engine held it and `events`
        // really did start empty).
        val dentistId = createDatesEvent("Dentist", startMs = 50_000L)
        val backend = FakeEventsBackend(uploadFails = true)

        val result = EventsReconcile.run(context, backend)

        assertTrue(result.isFailure)
        val row = CarDatabase.getDatabase(context).eventDao().getById(dentistId)!!
        assertTrue("a failed upload must leave the source appointment row completely untouched", !row.deleted)
        assertEquals("Dentist", row.title)
    }

    @Test
    fun `a refilled replica row carries the originating row's own id, not a fresh autoincrement one`() = runBlocking {
        // Pushes Event.id well clear of the small records.id values this test's Notes fixture will
        // use - see burnEventAutoincrement's own doc comment for why this, and not fixture
        // reordering, is what actually avoids the collision. This test is about id STABILITY when
        // nothing collides; the collision case has its own dedicated test below.
        burnEventAutoincrement()
        // dentistLocalId is the Event's own pre-existing local id (this table's autoincrement,
        // post-repoint); milkEngineId is still the engine's records.id, unchanged.
        val dentistLocalId = createDatesEvent("Dentist", startMs = 50_000L)
        val milkEngineId = createNotesItem("Buy milk", startsAt = 60_000L)
        val backend = FakeEventsBackend()

        EventsReconcile.run(context, backend).getOrThrow()

        val replica = CarDatabase.getDatabase(context).eventDao().getAllActive()
        val dentist = replica.single { it.title == "Dentist" }
        val milk = replica.single { it.title == "Buy milk" }
        assertEquals("the replica id must equal the local events row's own id it came from, not a reminted one", dentistLocalId, dentist.id)
        assertEquals("the replica id must equal the engine records.id it came from, not a reminted one", milkEngineId, milk.id)
    }

    @Test
    fun `ids are stable across two reconciles - this is the regression test for the wholesale-refresh remint defect`() = runBlocking {
        // Same avoidance as the previous test, and for the identical reason - keeps this test
        // isolated to the id-STABILITY question it exists to answer, not the separate colliding-id
        // question the dedicated test below covers.
        burnEventAutoincrement()
        createDatesEvent("Dentist", startMs = 50_000L)
        createNotesItem("Buy milk", startsAt = 60_000L)
        val backend = FakeEventsBackend()

        EventsReconcile.run(context, backend).getOrThrow()
        val idsAfterFirst = CarDatabase.getDatabase(context).eventDao().getAllActive()
            .associate { it.serverId to it.id }

        EventsReconcile.run(context, backend).getOrThrow()
        val idsAfterSecond = CarDatabase.getDatabase(context).eventDao().getAllActive()
            .associate { it.serverId to it.id }

        // Before the fix, EventsReconcile.run wipes events_replica and refills it from scratch on
        // EVERY call (`deleteAllForReplicaRefresh` immediately precedes the upsert loop), so
        // EventDao.upsert's getByServerId lookup always misses and every row reminted a
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
        val replica = CarDatabase.getDatabase(context).eventDao().getAllActive()
        val standalone = replica.single { it.title == "Standalone" }
        assertTrue("a row with no engine ancestor must still get a real, non-zero local id", standalone.id != 0L)
    }

    @Test
    fun `a row with a derivable id is seated before any id is allocated, so the carry wins and the ancestor-less row moves instead`() = runBlocking {
        // The contest this test exists to settle. The orphan has NO origin_guid at all - created
        // directly against the server, same as anything written post-cutover through
        // EventsBackend.upsert - so it cannot derive an id and must autoincrement.
        //
        // CHANGED 2026-08-27 (ticket 11 ruling #2): this used to be a MIGRATED row whose
        // origin_guid resolved to nothing in the engine. That shape is now retracted (soft-deleted)
        // by the SAME reconcile run, by design - see `a server row whose origin_guid names a
        // trashed engine record is soft-deleted...` above. A NULL origin_guid is what keeps this
        // test isolated to the id-seating contest it actually exists to settle, without also
        // tripping the (unrelated, and now correct) deletion propagation pass.
        //
        // It is given insertion order AHEAD of the real engine record's upload, so a naive
        // single-pass refill over the just-emptied table would seat it on id 1 first - precisely
        // the id the real record (records.id = 1, first row in a fresh engine table) needs to
        // carry.
        //
        // Getting this wrong is not a cosmetic id shuffle. `upsert`'s collision guard would
        // correctly decline to clobber the orphan and hand the Dentist row a fresh id, which is
        // the exact alarm orphaning the carry exists to prevent, reached by a different route.
        // The two-pass refill removes the contest rather than adjudicating it.
        val backend = FakeEventsBackend()
        backend.upsert(serverId = null, fields = EventFields(title = "Orphaned migrated row", startsAtMs = 1_000L))
        val dentistEngineId = createDatesEvent("Dentist", startMs = 50_000L)
        assertEquals(1L, dentistEngineId)

        EventsReconcile.run(context, backend).getOrThrow()

        val replica = CarDatabase.getDatabase(context).eventDao().getAllActive()
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
    fun `never deletes or trashes a Dates appointment's local row, and never trashes the Notes engine record either`() = runBlocking {
        // CORRECTED 2026-08-28: this used to assert the Dates half stayed live in the ENGINE - it
        // no longer lives there at all post-repoint (see EventsReconcile's own class doc), so the
        // Dates half of this assertion now checks the local `events` row instead. The Notes half is
        // unchanged - that branch is still engine-sourced.
        val dentistId = createDatesEvent("Dentist", startMs = 50_000L)
        createNotesItem("Buy milk", startsAt = 60_000L)
        val backend = FakeEventsBackend()
        val db = CarDatabase.getDatabase(context)
        val notesSch = NotesAspectSeeder.ensureSeeded(context)

        EventsReconcile.run(context, backend).getOrThrow()

        assertFalse(
            "the Dates appointment's own source row must never be soft-deleted by uploading it",
            db.eventDao().getById(dentistId)!!.deleted,
        )
        assertEquals(1, db.engineRecordDao().activeByRecordType(notesSch.recordTypeId).size)
    }

    /**
     * The assertion whose absence WAS the live regression the coordinator caught (backend-erp
     * ticket 17, 2026-08-28): [com.kevin.legion.calendar.CalendarImportController] was repointed to
     * write the local `events` table directly, but [EventsReconcile]'s Dates branch still read the
     * engine - so a newly imported appointment reached the server by NO route at all, even on a
     * fully configured install. [createDatesEvent] builds the exact row shape the importer now
     * produces; if this branch regresses back to reading the engine, this appointment would never
     * appear in `backend.rows` at all. Mutation-proved in the build report: pointing the Dates
     * branch back at `engineRecordDao()` makes this specific test fail and no other.
     */
    @Test
    fun `an appointment imported after the repoint is uploaded by the reconcile - the regression this fix closes`() = runBlocking {
        createDatesEvent("Team offsite", startMs = 70_000L)
        val backend = FakeEventsBackend()

        val report = EventsReconcile.run(context, backend).getOrThrow()

        assertEquals(1, report.uploaded)
        assertTrue(
            "the imported appointment must actually reach the server, not silently vanish",
            backend.rows.values.any { it.title == "Team offsite" },
        )
    }

    /**
     * **SUPERSEDED 2026-08-28 (coordinator follow-up round 2) - kept as a BELT-AND-BRACES check on
     * a case the allocation now makes impossible, not as documentation of an accepted risk.** The
     * paragraph this doc comment used to carry recommended "a genuinely disjoint id space... rather
     * than relying on this collision being rare" - that recommendation is now built:
     * [Event.APPOINTMENT_ID_BASE] and [com.kevin.legion.calendar.CalendarImportController.nextAppointmentId]
     * make a Dates appointment id and a Notes reminder's carried `records.id` disjoint BY
     * CONSTRUCTION, not by this guard. Every REAL appointment (through [CalendarImportController],
     * the only production writer) now lands at or above [Event.APPOINTMENT_ID_BASE], comfortably
     * clear of any `records.id` this app will ever produce - so the collision this test manufactures
     * (via [createDatesEvent], which bypasses the allocator entirely and seats a Dates row at a raw
     * `id = 1` on purpose) cannot happen through any real write path any more. It stays here
     * because [EventDao.upsert]'s collision guard is itself still real code, still reachable if a
     * FUTURE writer ever bypasses the allocator the way this test deliberately does, and a guard
     * with no test proving it degrades safely is a guard nobody can trust under pressure.
     */
    /**
     * `.scratch/backend-erp/issues/20-*.md` - the actual live defect: `public.events` enforces
     * TWO unique keys, `origin_guid` and a partial-unique `google_event_id`, but the upload guard
     * used to check only the first. A row already present under its `google_event_id` but a
     * DIFFERENT (or absent) `origin_guid` must be reported not-new and never inserted a second
     * time, exactly like an `origin_guid` match already was.
     */
    @Test
    fun `uploadMigratedEvent reports not-new when only the google_event_id matches, origin_guid different`() = runBlocking {
        val backend = FakeEventsBackend()
        backend.uploadMigratedEvent(
            MigratedEvent(
                originGuid = "origin-a",
                fields = EventFields(title = "Team offsite", startsAtMs = 1_000L, googleEventId = "google-1"),
            ),
        ).getOrThrow()
        assertEquals(1, backend.rows.size)

        val wasNew = backend.uploadMigratedEvent(
            MigratedEvent(
                // A rotated origin_guid - exactly what a re-import through the repointed Dates
                // branch produces (a fresh Event.guid minted on every import) - but the SAME
                // google_event_id, meaning the server already holds this row.
                originGuid = "origin-b",
                fields = EventFields(title = "Team offsite", startsAtMs = 1_000L, googleEventId = "google-1"),
            ),
        ).getOrThrow()

        assertFalse("a google_event_id match must be reported not-new, same as an origin_guid match", wasNew)
        assertEquals("must not have inserted a second row for the same google_event_id", 1, backend.rows.size)
    }

    /**
     * A `null` `google_event_id` must never be treated as matching another `null` -
     * `events_google_event_id_idx` is partial (`where google_event_id is not null`), so two
     * genuinely dateless-of-that-field rows are not the same row, and the client-side check must
     * reproduce that predicate rather than short-circuiting on "both null".
     */
    @Test
    fun `two events with null google_event_id never collide with each other`() = runBlocking {
        val backend = FakeEventsBackend()
        val firstNew = backend.uploadMigratedEvent(
            MigratedEvent(originGuid = "origin-a", fields = EventFields(title = "Buy milk", startsAtMs = 1_000L)),
        ).getOrThrow()
        val secondNew = backend.uploadMigratedEvent(
            MigratedEvent(originGuid = "origin-b", fields = EventFields(title = "Call the vet", startsAtMs = 2_000L)),
        ).getOrThrow()

        assertTrue("first row with a null google_event_id must upload as new", firstNew)
        assertTrue("second row with a null google_event_id must ALSO upload as new - a null never matches another null", secondNew)
        assertEquals(2, backend.rows.size)
    }

    /**
     * A row whose `google_event_id` is null is unaffected by the new google_event_id branch and
     * still keys purely on `origin_guid`, exactly as before this fix.
     */
    @Test
    fun `an event with a null google_event_id still keys on origin_guid alone, exactly as before`() = runBlocking {
        val backend = FakeEventsBackend()
        backend.uploadMigratedEvent(
            MigratedEvent(originGuid = "origin-a", fields = EventFields(title = "Buy milk", startsAtMs = 1_000L)),
        ).getOrThrow()

        val wasNewSameOrigin = backend.uploadMigratedEvent(
            MigratedEvent(originGuid = "origin-a", fields = EventFields(title = "Buy milk", startsAtMs = 1_000L)),
        ).getOrThrow()

        assertFalse("a repeat call with the same origin_guid must still report not-new", wasNewSameOrigin)
        assertEquals(1, backend.rows.size)
    }

    /**
     * The retraction danger this ticket exists to close, at the [EventsReconcile.run] level rather
     * than the raw backend call: a server row already migrated once, under an `origin_guid` this
     * run's Dates branch no longer produces (a freshly re-imported [Event] mints its own new
     * `guid` every time - see [Event.guid]'s own doc comment), but carrying the SAME
     * `google_event_id`, must survive this run untouched - never re-uploaded as a duplicate, and
     * critically, never RETRACTED by the origin_guid-bounded deletion pass either. Retracting it
     * would be the exact 213-row incident (`.scratch/backend-erp/issues/20-*.md`) happening again
     * through the diff logic rather than the upload path.
     */
    @Test
    fun `a server row matched only by google_event_id survives the reconcile untouched, never re-uploaded and never retracted`() = runBlocking {
        val backend = FakeEventsBackend()
        // Seed a server row as if a PRIOR reconcile run uploaded this same Google appointment
        // under a different origin_guid than the one the CURRENT local Event row carries.
        backend.uploadMigratedEvent(
            MigratedEvent(
                originGuid = "stale-origin-guid",
                fields = EventFields(
                    title = "Team offsite",
                    startsAtMs = 70_000L,
                    googleEventId = "google-1",
                    kind = EventKind.EVENT,
                ),
            ),
        ).getOrThrow()
        assertEquals(1, backend.rows.size)

        // The CURRENT local row for the same Google event, with its own freshly-minted guid and
        // the same google_event_id - exactly CalendarImportController's real re-import shape.
        val db = CarDatabase.getDatabase(context)
        db.eventDao().insert(
            Event(
                serverId = UUID.randomUUID().toString(),
                guid = UUID.randomUUID().toString(),
                title = "Team offsite",
                startsAt = 70_000L,
                allDay = false,
                source = DatesAspectSeeder.SOURCE_LEGION,
                kind = EventKind.EVENT,
                googleEventId = "google-1",
                updatedAtMs = System.currentTimeMillis(),
                createdAt = System.currentTimeMillis(),
            ),
        )

        val report = EventsReconcile.run(context, backend).getOrThrow()

        assertEquals("the google_event_id match must not be counted as a new upload", 0, report.uploaded)
        assertEquals(
            "the stale-origin_guid row must survive - it is the SAME event under a rotated key, not an orphan",
            0,
            report.deletedOnServer,
        )
        assertTrue(
            "must not be a lingering onlyOnServer diff either - it is genuinely present, just under a different key",
            report.onlyOnServer.isEmpty(),
        )
        assertFalse(
            "the stale-origin_guid server row must never be soft-deleted by this run",
            backend.rows.getValue(backend.rows.keys.single()).deleted,
        )
    }

    // ---------------------------------------------------------------------- ticket 20, Q2's ruling
    // "a routine sync may not perform an unbounded deletion as a side effect" - the 213-of-354
    // incident. Seeds N server-only "ghost" rows directly through the backend (never through the
    // engine), the same shape as the pre-existing ghost test above, so every one of them is a
    // genuine retraction candidate with no matching reconciling record.

    /** Seeds [count] ghost server rows, each with its own unique `origin_guid` and no matching
     * engine record - every one of them is, by construction, a retraction candidate. [prefix]
     * must be distinct across calls within the same test - [FakeEventsBackend.uploadMigratedEvent]
     * treats a repeated `origin_guid` as "already present" and silently declines to insert it
     * again, so two calls sharing a prefix would seed fewer rows than the test asked for. */
    private suspend fun seedGhostRows(backend: FakeEventsBackend, count: Int, prefix: String = "ghost") {
        repeat(count) { i ->
            backend.uploadMigratedEvent(
                MigratedEvent(originGuid = "$prefix-$i", fields = EventFields(title = "Ghost $prefix-$i", startsAtMs = 1_000L)),
            ).getOrThrow()
        }
    }

    @Test
    fun `a small retraction set proceeds and is counted, same as before this ticket`() = runBlocking {
        val backend = FakeEventsBackend()
        seedGhostRows(backend, count = 2)

        val report = EventsReconcile.run(context, backend).getOrThrow()

        assertEquals(2, report.retractionCandidateCount)
        assertEquals(2, report.deletedOnServer)
        assertFalse("a small retraction set must not be withheld", report.retractionWithheld)
        assertTrue("actually deleted, not just counted", backend.rows.values.all { it.deleted })
    }

    @Test
    fun `a retraction set at the bound retracts nothing, is reported in words, and isClean is false`() = runBlocking {
        val backend = FakeEventsBackend()
        seedGhostRows(backend, count = 6)

        val report = EventsReconcile.run(context, backend).getOrThrow()

        assertEquals(6, report.retractionCandidateCount)
        assertEquals("nothing may be retracted this run", 0, report.deletedOnServer)
        assertTrue(report.retractionWithheld)
        assertFalse(
            "a withheld retraction must hold isClean false - the household is genuinely out of step",
            report.isClean,
        )
        assertTrue(
            "the withheld rows must survive untouched, not be soft-deleted anyway",
            backend.rows.values.none { it.deleted },
        )
    }

    @Test
    fun `the immediate re-run with the same candidate set proceeds - the second run is the consent`() = runBlocking {
        val backend = FakeEventsBackend()
        seedGhostRows(backend, count = 6)
        val first = EventsReconcile.run(context, backend).getOrThrow()
        assertTrue(first.retractionWithheld)

        val second = EventsReconcile.run(context, backend).getOrThrow()

        assertFalse("the second, unchanged run must proceed", second.retractionWithheld)
        assertEquals(6, second.retractionCandidateCount)
        assertEquals(6, second.deletedOnServer)
        assertTrue("the six ghost rows must actually be retracted now", backend.rows.values.all { it.deleted })
    }

    @Test
    fun `a re-run whose candidate set has materially changed refuses again, rather than spending the old confirmation`() = runBlocking {
        val backend = FakeEventsBackend()
        seedGhostRows(backend, count = 6)
        val first = EventsReconcile.run(context, backend).getOrThrow()
        assertTrue(first.retractionWithheld)

        // A materially different set - one MORE candidate than the one just warned about, under
        // its own distinct prefix so it does not collide with (and get silently dropped against)
        // the first batch's own origin_guids.
        seedGhostRows(backend, count = 1, prefix = "extra-ghost")
        val second = EventsReconcile.run(context, backend).getOrThrow()

        assertTrue(
            "a changed candidate set must refuse again, not silently spend the earlier warning",
            second.retractionWithheld,
        )
        assertEquals(7, second.retractionCandidateCount)
        assertEquals(0, second.deletedOnServer)
        assertTrue("still nothing actually deleted", backend.rows.values.none { it.deleted })
    }

    @Test
    fun `zero retraction candidates is still reported as a count, not silently omitted`() = runBlocking {
        createDatesEvent("Dentist", startMs = 50_000L)
        val backend = FakeEventsBackend()

        val report = EventsReconcile.run(context, backend).getOrThrow()

        assertEquals(0, report.retractionCandidateCount)
        assertEquals(0, report.deletedOnServer)
        assertFalse(report.retractionWithheld)
        assertTrue(report.isClean)
    }

    @Test
    fun `a Dates appointment and a Notes reminder whose ids coincidentally collide never corrupt either row - now unreachable via any real write path, kept as a safety net`() = runBlocking {
        // Both fixtures deliberately land on Event.id = 1 / records.id = 1 respectively - no
        // throwaway offset this time, so the collision this test exists to exercise actually fires.
        // createDatesEvent bypasses CalendarImportController.nextAppointmentId on purpose here -
        // this is the one place in this file that still exercises the pre-disjoint-range shape.
        val dentistId = createDatesEvent("Dentist", startMs = 50_000L)
        assertEquals(1L, dentistId)
        val milkEngineId = createNotesItem("Buy milk", startsAt = 60_000L)
        assertEquals(1L, milkEngineId)
        val backend = FakeEventsBackend()

        val report = EventsReconcile.run(context, backend).getOrThrow()

        // Both rows survive, with their real content intact - the safety property that matters
        // most: a collision degrades an id, it never destroys or merges two unrelated rows.
        assertTrue(report.isClean)
        val replica = CarDatabase.getDatabase(context).eventDao().getAllActive()
        val dentist = replica.single { it.title == "Dentist" }
        val milk = replica.single { it.title == "Buy milk" }
        assertEquals("the appointment keeps its own known id - it was never wiped in the first place", 1L, dentist.id)
        assertTrue(
            "the reminder must survive at SOME real id rather than being lost or clobbering the appointment",
            milk.id != 0L,
        )
        assertTrue(
            "the reminder's carried id lost the collision and moved - documented cost, not a crash",
            milk.id != dentist.id,
        )
    }

}

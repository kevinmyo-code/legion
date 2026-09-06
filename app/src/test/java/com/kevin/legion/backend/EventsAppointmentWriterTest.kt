package com.kevin.legion.backend

import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.OutboxOperation
import com.kevin.legion.data.local.OutboxTarget
import com.kevin.legion.testutil.RoomTestReset
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * [EventsAppointmentWriter] - the write-through half of the events-outbox ticket. Every test here
 * uses a [FakeEventsBackend] whose [FakeEventsBackend.uploadResult] is settable per test, so
 * "the server is unreachable this call" and "the server accepted this call" are both exercised
 * with no network, same posture as every other backend fake in this suite ([EventsSyncTest]).
 */
@RunWith(RobolectricTestRunner::class)
class EventsAppointmentWriterTest {
    private val context = RuntimeEnvironment.getApplication()

    private class FakeEventsBackend : EventsBackend {
        var uploadResult: Result<Boolean> = Result.success(true)
        val uploadedGuids = mutableListOf<String>()

        /** The FIELDS of each upload, not only its guid - the setDone tests need to see whether a
         * re-pointed create carries the tick, which a guid alone cannot answer. */
        val uploadedFields = mutableListOf<EventFields>()

        /** Settable per test, same posture as [uploadResult] - [EventsAppointmentWriter.updateEvent]'s
         *  own push. */
        var upsertResult: Result<RemoteEvent> = Result.failure(EventsBackendException("not set by this test"))
        val upsertCalls = mutableListOf<Pair<String, EventFields>>()

        /** Settable per test - [EventsAppointmentWriter.deleteEvent]'s own push. */
        var softDeleteResult: Result<Boolean> = Result.success(true)
        val softDeleteCalls = mutableListOf<String>()

        override suspend fun fetchActive(): Result<List<RemoteEvent>> = Result.success(emptyList())
        override suspend fun fetchChangedSince(sinceMs: Long): Result<List<RemoteEvent>> = Result.success(emptyList())
        override suspend fun upsert(serverId: String?, fields: EventFields): Result<RemoteEvent> {
            upsertCalls += (serverId ?: "") to fields
            return upsertResult
        }
        override suspend fun softDelete(serverId: String): Result<Boolean> {
            softDeleteCalls += serverId
            return softDeleteResult
        }
        override suspend fun skipOccurrence(serverId: String, skipDateEpochMs: Long): Result<Unit> =
            Result.failure(EventsBackendException("not used by this test"))
        override suspend fun fetchSkips(serverId: String): Result<List<Long>> = Result.success(emptyList())
        override suspend fun uploadMigratedEvent(event: MigratedEvent): Result<Boolean> {
            uploadedGuids += event.originGuid
            uploadedFields += event.fields
            return uploadResult
        }
    }

    /** A synced calendar-table row, seeded directly (never through [EventsAppointmentWriter.addEvent],
     * which always mints a null [com.kevin.legion.data.local.Event.serverId] - these tests need a
     * row that has ALREADY round-tripped, matching the ordinary state by the time a user renames or
     * deletes something they created a while ago). */
    private suspend fun seedSyncedEvent(
        title: String = "Dentist",
        kind: String = EventKind.EVENT,
    ): com.kevin.legion.data.local.Event {
        val db = CarDatabase.getDatabase(context)
        val now = System.currentTimeMillis()
        val row = com.kevin.legion.data.local.Event(
            id = 0,
            serverId = "server-uuid-1",
            guid = java.util.UUID.randomUUID().toString(),
            title = title,
            startsAt = 1_000L,
            endsAt = 2_000L,
            allDay = false,
            source = "legion",
            kind = kind,
            updatedAtMs = now,
            createdAt = now,
        )
        val id = db.eventDao().insert(row)
        return row.copy(id = id)
    }

    private lateinit var backend: FakeEventsBackend

    @Before
    fun setUp() {
        RoomTestReset.resetCarDatabaseSingleton()
        backend = FakeEventsBackend()
        EventsAppointmentWriter.backendOverride = backend
    }

    @After
    fun tearDown() {
        EventsAppointmentWriter.backendOverride = null
        RoomTestReset.drainArchDiskIoPool()
    }

    @Test
    fun `a new appointment carries a null serverId, never a fake uuid`() = runBlocking {
        val row = EventsAppointmentWriter.addEvent(
            context, "Dentist", startsAtMs = 1000L, endsAtMs = 2000L, allDay = false,
            source = "legion", kind = EventKind.EVENT,
        )
        assertNull(row.serverId)
        assertTrue(row.guid.isNotBlank())
    }

    @Test
    fun `the local row is written even when the server push fails`() = runBlocking {
        backend.uploadResult = Result.failure(EventsBackendException("offline"))
        val row = EventsAppointmentWriter.addEvent(
            context, "Dentist", startsAtMs = 1000L, endsAtMs = 2000L, allDay = false,
            source = "legion", kind = EventKind.EVENT,
        )
        val fromDb = CarDatabase.getDatabase(context).eventDao().getById(row.id)
        assertEquals("Dentist", fromDb?.title)
    }

    @Test
    fun `a push failure enqueues an outbox entry keyed to the row's own guid`() = runBlocking {
        backend.uploadResult = Result.failure(EventsBackendException("offline"))
        val row = EventsAppointmentWriter.addEvent(
            context, "Dentist", startsAtMs = 1000L, endsAtMs = 2000L, allDay = false,
            source = "legion", kind = EventKind.EVENT,
        )
        val outbox = CarDatabase.getDatabase(context).outboxDao().getAll()
        assertEquals(1, outbox.size)
        assertEquals(OutboxTarget.EVENTS, outbox[0].targetTable)
        assertEquals(OutboxOperation.UPSERT, outbox[0].operation)
        assertEquals(row.id, outbox[0].localId)
        assertTrue(outbox[0].payload.contains(row.guid))
    }

    @Test
    fun `a successful push enqueues nothing`() = runBlocking {
        backend.uploadResult = Result.success(true)
        EventsAppointmentWriter.addEvent(
            context, "Dentist", startsAtMs = 1000L, endsAtMs = 2000L, allDay = false,
            source = "legion", kind = EventKind.EVENT,
        )
        assertTrue(CarDatabase.getDatabase(context).outboxDao().getAll().isEmpty())
    }

    @Test
    fun `on an unconfigured install nothing is pushed and nothing is queued`() = runBlocking {
        EventsAppointmentWriter.backendOverride = null
        // No SupabaseClientProvider project configured in this test environment either, so
        // backend(context) resolves to null exactly like a real unconfigured install.
        val row = EventsAppointmentWriter.addEvent(
            context, "Dentist", startsAtMs = 1000L, endsAtMs = 2000L, allDay = false,
            source = "legion", kind = EventKind.EVENT,
        )
        assertNull(row.serverId)
        assertTrue(CarDatabase.getDatabase(context).outboxDao().getAll().isEmpty())
        assertTrue(backend.uploadedGuids.isEmpty())
    }

    // ---------------------------------------------------------------------- updateEvent (rename)

    @Test
    fun `renaming an already-synced appointment reaches the server`() = runBlocking {
        val existing = seedSyncedEvent()
        backend.upsertResult = Result.success(
            RemoteEvent(
                serverId = "server-uuid-1", title = "Root canal", createdAtMs = existing.createdAt,
                startsAtMs = 1000L, endsAtMs = 2000L, allDay = false, location = null, notes = null,
                source = "legion", googleEventId = null, done = false, doneAtMs = null, sortOrder = null,
                triggerPlaceLabel = null, repeatKind = null, repeatEvery = null, repeatDaysOfWeek = null,
                repeatDay = null, repeatMonth = null, repeatEndKind = null, repeatEndDateMs = null,
                repeatEndCount = null, exact = false, exactDowngraded = false, missedAtMs = null,
                missedDismissedAtMs = null, loggedAtMs = null, updatedAtMs = System.currentTimeMillis(),
                deleted = false,
            ),
        )
        val ok = EventsAppointmentWriter.updateEvent(
            context, existing, title = "Root canal", startsAtMs = 1000L, endsAtMs = 2000L, allDay = false,
        )
        assertTrue(ok)
        assertEquals(1, backend.upsertCalls.size)
        assertEquals("server-uuid-1", backend.upsertCalls[0].first)
        assertEquals("Root canal", backend.upsertCalls[0].second.title)
        val fromDb = CarDatabase.getDatabase(context).eventDao().getById(existing.id)
        assertEquals("Root canal", fromDb?.title)
    }

    @Test
    fun `a failed rename push enqueues an OutboxOperation UPDATE entry`() = runBlocking {
        val existing = seedSyncedEvent()
        backend.upsertResult = Result.failure(EventsBackendException("offline"))
        EventsAppointmentWriter.updateEvent(
            context, existing, title = "Root canal", startsAtMs = 1000L, endsAtMs = 2000L, allDay = false,
        )
        val outbox = CarDatabase.getDatabase(context).outboxDao().getAll()
        assertEquals(1, outbox.size)
        assertEquals(OutboxTarget.EVENTS, outbox[0].targetTable)
        assertEquals(OutboxOperation.UPDATE, outbox[0].operation)
        assertTrue(outbox[0].payload.contains("Root canal"))
        // Local write always happens regardless of push outcome - see this object's own class doc.
        assertEquals("Root canal", CarDatabase.getDatabase(context).eventDao().getById(existing.id)?.title)
    }

    @Test
    fun `renaming on an unconfigured install stays local-only`() = runBlocking {
        EventsAppointmentWriter.backendOverride = null
        val existing = seedSyncedEvent()
        EventsAppointmentWriter.updateEvent(
            context, existing, title = "Root canal", startsAtMs = 1000L, endsAtMs = 2000L, allDay = false,
        )
        assertEquals("Root canal", CarDatabase.getDatabase(context).eventDao().getById(existing.id)?.title)
        assertTrue(CarDatabase.getDatabase(context).outboxDao().getAll().isEmpty())
        assertTrue(backend.upsertCalls.isEmpty())
    }

    // ---------------------------------------------------------------------- deleteEvent (delete)

    @Test
    fun `deleting an already-synced appointment produces a tombstone, never a local hard delete`() = runBlocking {
        val existing = seedSyncedEvent()
        val ok = EventsAppointmentWriter.deleteEvent(context, existing)
        assertTrue(ok)
        assertEquals(listOf("server-uuid-1"), backend.softDeleteCalls)
        // Row still exists locally, soft-deleted - a hard deleteById would make getById return null.
        val fromDb = CarDatabase.getDatabase(context).eventDao().getById(existing.id)
        assertTrue(fromDb != null && fromDb.deleted)
    }

    @Test
    fun `a failed delete push enqueues an OutboxOperation SOFT_DELETE entry`() = runBlocking {
        val existing = seedSyncedEvent()
        backend.softDeleteResult = Result.failure(EventsBackendException("offline"))
        EventsAppointmentWriter.deleteEvent(context, existing)
        val outbox = CarDatabase.getDatabase(context).outboxDao().getAll()
        assertEquals(1, outbox.size)
        assertEquals(OutboxTarget.EVENTS, outbox[0].targetTable)
        assertEquals(OutboxOperation.SOFT_DELETE, outbox[0].operation)
        assertTrue(outbox[0].payload.contains("server-uuid-1"))
        // Local soft-delete still happens even though the push failed.
        val fromDb = CarDatabase.getDatabase(context).eventDao().getById(existing.id)
        assertTrue(fromDb != null && fromDb.deleted)
    }

    @Test
    fun `deleting on an unconfigured install hard-deletes locally, matching the old convention`() = runBlocking {
        EventsAppointmentWriter.backendOverride = null
        val existing = seedSyncedEvent()
        EventsAppointmentWriter.deleteEvent(context, existing)
        assertNull(CarDatabase.getDatabase(context).eventDao().getById(existing.id))
        assertTrue(backend.softDeleteCalls.isEmpty())
    }

    // ------------------------------------------------------------------------- setDone (the tick)
    //
    // The defect found on the A25 on 2026-09-06: NotesController.tickAppointment wrote Room and
    // stopped, so a tick reached no server on either transport AND did not even enter the outbox -
    // the sync line read "sent 0, 0 still queued" while the server row stayed done = false. Every
    // test below would have failed before setDone existed, because nothing was called and nothing
    // was queued.

    private fun ackFor(existing: com.kevin.legion.data.local.Event, done: Boolean) = RemoteEvent(
        serverId = "server-uuid-1", title = existing.title, createdAtMs = existing.createdAt,
        startsAtMs = existing.startsAt, endsAtMs = existing.endsAt, allDay = false, location = null,
        notes = null, source = "legion", googleEventId = null, done = done,
        doneAtMs = if (done) 5_000L else null, sortOrder = null, triggerPlaceLabel = null,
        repeatKind = null, repeatEvery = null, repeatDaysOfWeek = null, repeatDay = null,
        repeatMonth = null, repeatEndKind = null, repeatEndDateMs = null, repeatEndCount = null,
        exact = false, exactDowngraded = false, missedAtMs = null, missedDismissedAtMs = null,
        loggedAtMs = null, updatedAtMs = System.currentTimeMillis(), deleted = false,
    )

    @Test
    fun `ticking a synced task pushes done and doneAt to the server`() = runBlocking {
        val task = seedSyncedEvent("Module 3: Discussion - first post due", EventKind.TASK)
        backend.upsertResult = Result.success(ackFor(task, done = true))

        EventsAppointmentWriter.setDone(context, task, done = true)

        assertEquals(1, backend.upsertCalls.size)
        assertEquals("server-uuid-1", backend.upsertCalls[0].first)
        assertTrue(backend.upsertCalls[0].second.done)
        assertNotNull(backend.upsertCalls[0].second.doneAtMs)
        val fromDb = CarDatabase.getDatabase(context).eventDao().getById(task.id)!!
        assertTrue(fromDb.done)
        assertNotNull(fromDb.doneAt)
        // Nothing queued: the push succeeded, so there is nothing left to retry.
        assertTrue(CarDatabase.getDatabase(context).outboxDao().getAll().isEmpty())
    }

    @Test
    fun `unticking clears doneAt on the wire, never only locally`() = runBlocking {
        val task = seedSyncedEvent("Submit essay", EventKind.TASK)
        backend.upsertResult = Result.success(ackFor(task, done = true))
        EventsAppointmentWriter.setDone(context, task, done = true)
        val ticked = CarDatabase.getDatabase(context).eventDao().getById(task.id)!!

        backend.upsertResult = Result.success(ackFor(task, done = false))
        EventsAppointmentWriter.setDone(context, ticked, done = false)

        // A PATCH that merely omitted done_at would leave yesterday's completion instant on a row
        // that is no longer complete - DjangoEventWrite sets explicitNulls for exactly this.
        val lastCall = backend.upsertCalls.last().second
        assertFalse(lastCall.done)
        assertNull(lastCall.doneAtMs)
        val fromDb = CarDatabase.getDatabase(context).eventDao().getById(task.id)!!
        assertFalse(fromDb.done)
        assertNull(fromDb.doneAt)
    }

    @Test
    fun `a failed tick push enqueues an UPDATE entry carrying done, and the local tick stands`() = runBlocking {
        val task = seedSyncedEvent("Submit essay", EventKind.TASK)
        backend.upsertResult = Result.failure(EventsBackendException("offline"))

        EventsAppointmentWriter.setDone(context, task, done = true)

        val outbox = CarDatabase.getDatabase(context).outboxDao().getAll()
        assertEquals(1, outbox.size)
        assertEquals(OutboxTarget.EVENTS, outbox[0].targetTable)
        assertEquals(OutboxOperation.UPDATE, outbox[0].operation)
        assertEquals(task.id, outbox[0].localId)
        assertTrue("the queued payload must carry the tick itself", outbox[0].payload.contains("\"done\":true"))
        // Local write always happens regardless of push outcome - the row is ticked on screen
        // immediately, and the outbox is what makes the mutation survive rather than vanish.
        assertTrue(CarDatabase.getDatabase(context).eventDao().getById(task.id)!!.done)
    }

    @Test
    fun `the drain sends a queued tick and clears the entry`() = runBlocking {
        val task = seedSyncedEvent("Submit essay", EventKind.TASK)
        backend.upsertResult = Result.failure(EventsBackendException("offline"))
        EventsAppointmentWriter.setDone(context, task, done = true)
        assertEquals(1, CarDatabase.getDatabase(context).outboxDao().getAll().size)

        backend.upsertResult = Result.success(ackFor(task, done = true))
        val report = EventsOutboxDrain.drain(context, backend)

        assertEquals(1, report.succeeded)
        assertEquals(0, report.stillPending)
        // The second upsert call is the drain's - and it still carries the tick.
        assertEquals(2, backend.upsertCalls.size)
        assertTrue(backend.upsertCalls.last().second.done)
        assertTrue(CarDatabase.getDatabase(context).outboxDao().getAll().isEmpty())
    }

    @Test
    fun `ticking a task whose own create is still queued re-points that create, tick included`() = runBlocking {
        // The row minted by addEvent has serverId = null, so there is no server row to PATCH -
        // EventsAppointmentWriter re-points the pending create instead. Before EventUpsertOutboxPayload
        // carried done/doneAtMs, that re-point silently dropped the tick.
        backend.uploadResult = Result.failure(EventsBackendException("offline"))
        val row = EventsAppointmentWriter.addEvent(
            context, "Submit essay", startsAtMs = 1000L, endsAtMs = 2000L, allDay = false,
            source = "legion", kind = EventKind.TASK,
        )
        assertEquals(1, CarDatabase.getDatabase(context).outboxDao().getAll().size)

        EventsAppointmentWriter.setDone(context, row, done = true)

        val outbox = CarDatabase.getDatabase(context).outboxDao().getAll()
        assertEquals("still one create, re-pointed rather than joined by an UPDATE", 1, outbox.size)
        assertEquals(OutboxOperation.UPSERT, outbox[0].operation)
        assertTrue(outbox[0].payload.contains("\"done\":true"))

        // And the drain sends it as a create carrying done, not as two writes.
        backend.uploadResult = Result.success(true)
        EventsOutboxDrain.drain(context, backend)
        assertEquals(listOf(row.guid), backend.uploadedGuids.distinct())
        assertTrue(backend.uploadedFields.last().done)
    }

    @Test
    fun `ticking on an unconfigured install stays local-only and queues nothing`() = runBlocking {
        EventsAppointmentWriter.backendOverride = null
        val task = seedSyncedEvent("Submit essay", EventKind.TASK)

        EventsAppointmentWriter.setDone(context, task, done = true)

        assertTrue(CarDatabase.getDatabase(context).eventDao().getById(task.id)!!.done)
        // No server this device will ever talk to, so a queue would only ever grow - the same
        // posture addEvent/updateEvent already take.
        assertTrue(CarDatabase.getDatabase(context).outboxDao().getAll().isEmpty())
        assertTrue(backend.upsertCalls.isEmpty())
    }
}

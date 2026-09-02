package com.kevin.legion.backend

import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.OutboxOperation
import com.kevin.legion.data.local.OutboxTarget
import com.kevin.legion.testutil.RoomTestReset
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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
            return uploadResult
        }
    }

    /** A synced calendar-table row, seeded directly (never through [EventsAppointmentWriter.addEvent],
     * which always mints a null [com.kevin.legion.data.local.Event.serverId] - these tests need a
     * row that has ALREADY round-tripped, matching the ordinary state by the time a user renames or
     * deletes something they created a while ago). */
    private suspend fun seedSyncedEvent(title: String = "Dentist"): com.kevin.legion.data.local.Event {
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
            kind = EventKind.EVENT,
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
}

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

        override suspend fun fetchActive(): Result<List<RemoteEvent>> = Result.success(emptyList())
        override suspend fun fetchChangedSince(sinceMs: Long): Result<List<RemoteEvent>> = Result.success(emptyList())
        override suspend fun upsert(serverId: String?, fields: EventFields): Result<RemoteEvent> =
            Result.failure(EventsBackendException("not used by this test"))
        override suspend fun softDelete(serverId: String): Result<Boolean> =
            Result.failure(EventsBackendException("not used by this test"))
        override suspend fun skipOccurrence(serverId: String, skipDateEpochMs: Long): Result<Unit> =
            Result.failure(EventsBackendException("not used by this test"))
        override suspend fun fetchSkips(serverId: String): Result<List<Long>> = Result.success(emptyList())
        override suspend fun uploadMigratedEvent(event: MigratedEvent): Result<Boolean> {
            uploadedGuids += event.originGuid
            return uploadResult
        }
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
}

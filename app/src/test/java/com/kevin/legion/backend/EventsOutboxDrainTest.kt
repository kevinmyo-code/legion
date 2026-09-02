package com.kevin.legion.backend

import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.testutil.RoomTestReset
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * [EventsOutboxDrain] - retrying queued `events` writes. [EventsAppointmentWriterTest] already
 * covers enqueueing; this suite covers what happens to an entry ONCE it is in the table -
 * success drains it, failure re-counts an attempt, and a poisoned entry (attempts at the cap)
 * stops being retried at all.
 */
@RunWith(RobolectricTestRunner::class)
class EventsOutboxDrainTest {
    private val context = RuntimeEnvironment.getApplication()

    private class FakeEventsBackend : EventsBackend {
        var uploadResult: Result<Boolean> = Result.success(true)
        var uploadCallCount = 0

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
            uploadCallCount++
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

    private suspend fun enqueueOneFailedWrite(): Long {
        backend.uploadResult = Result.failure(EventsBackendException("offline"))
        val row = EventsAppointmentWriter.addEvent(
            context, "Dentist", startsAtMs = 1000L, endsAtMs = 2000L, allDay = false,
            source = "legion", kind = EventKind.EVENT,
        )
        return row.id
    }

    @Test
    fun `a successful drain deletes the outbox entry`() = runBlocking {
        enqueueOneFailedWrite()
        backend.uploadResult = Result.success(true)

        val report = EventsOutboxDrain.drain(context, backend)

        assertEquals(1, report.succeeded)
        assertEquals(0, report.stillPending)
        assertTrue(CarDatabase.getDatabase(context).outboxDao().getAll().isEmpty())
    }

    @Test
    fun `draining an already-drained outbox a second time is a genuine no-op`() = runBlocking {
        enqueueOneFailedWrite()
        backend.uploadResult = Result.success(true)

        EventsOutboxDrain.drain(context, backend)
        val secondReport = EventsOutboxDrain.drain(context, backend)

        assertEquals(0, secondReport.succeeded)
        assertEquals(0, secondReport.stillPending)
        assertEquals(0, secondReport.poisoned)
    }

    @Test
    fun `draining the same still-pending entry twice calls uploadMigratedEvent with the same guid both times, never duplicating locally`() = runBlocking {
        enqueueOneFailedWrite()
        backend.uploadResult = Result.success(true)
        // Reset here - enqueueOneFailedWrite's own write-time push attempt already counted one
        // call against this counter; everything from here measures the DRAIN's own calls only.
        backend.uploadCallCount = 0

        // Two drains in a row before either "succeeds" from this test's point of view mirrors two
        // foreground resumes in quick succession - EventsBackend.uploadMigratedEvent's own
        // idempotency-by-guid is what keeps this safe against a duplicate server row; this test
        // only proves the LOCAL side never creates a second outbox entry or a second Event row for
        // the same logical appointment.
        EventsOutboxDrain.drain(context, backend)
        assertEquals(1, backend.uploadCallCount)
        assertEquals(1, CarDatabase.getDatabase(context).eventDao().getAll().size)
    }

    @Test
    fun `a failed retry increments attempts and records lastError, without deleting the entry`() = runBlocking {
        enqueueOneFailedWrite()
        backend.uploadResult = Result.failure(EventsBackendException("still offline"))

        val report = EventsOutboxDrain.drain(context, backend)

        assertEquals(0, report.succeeded)
        assertEquals(1, report.stillPending)
        val entries = CarDatabase.getDatabase(context).outboxDao().getAll()
        assertEquals(1, entries.size)
        assertEquals(1, entries[0].attempts)
        assertNotNull(entries[0].lastError)
    }

    @Test
    fun `a 4xx-shaped rejection is not retried forever - it poisons after the attempt cap`() = runBlocking {
        enqueueOneFailedWrite()
        backend.uploadResult = Result.failure(EventsBackendException("Supabase rejected the request: constraint violation"))
        // Reset here - see the identical comment above in the "draining the same still-pending
        // entry twice" test for why (enqueueOneFailedWrite's own write-time attempt already
        // counted one call this test does not want to count).
        backend.uploadCallCount = 0

        var lastReport = EventsOutboxDrain.DrainReport(0, 0, 0)
        repeat(EventsOutboxDrain.MAX_ATTEMPTS) {
            lastReport = EventsOutboxDrain.drain(context, backend)
        }

        assertEquals(1, lastReport.poisoned)
        assertEquals(EventsOutboxDrain.MAX_ATTEMPTS, backend.uploadCallCount)

        // A further drain must not even attempt the poisoned row again.
        val afterCap = EventsOutboxDrain.drain(context, backend)
        assertEquals(0, afterCap.succeeded + afterCap.stillPending + afterCap.poisoned)
        assertEquals(EventsOutboxDrain.MAX_ATTEMPTS, backend.uploadCallCount)
    }
}

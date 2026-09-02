package com.kevin.legion.backend

import com.kevin.legion.testutil.RoomTestReset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * [EventsPullCursor]'s own watermark contract, plus [EventsSync.pull]'s use of it end to end -
 * the two things this ticket's brief calls out by name: "a watermark bug that silently syncs zero
 * rows is exactly the shape of failure this whole day has been about."
 *
 * Uses a [SinceAwareFakeEventsBackend] that actually HONOURS `sinceMs` (unlike [EventsSyncTest]'s
 * own `FakeEventsBackend`, which ignores it - that suite is testing merge logic, not watermark
 * narrowing, and says so in its own doc comment). This is what lets this suite prove the watermark
 * genuinely narrows what the next pull asks for, not merely that a repeat merge is idempotent.
 */
@RunWith(RobolectricTestRunner::class)
class EventsPullCursorTest {
    private val context = RuntimeEnvironment.getApplication()

    /** Mirrors the real server's own `updated_at >= sinceMs` semantics (inclusive), unlike
     *  [EventsSyncTest]'s fake - see this class's own doc comment for why. */
    private class SinceAwareFakeEventsBackend : EventsBackend {
        val rows = mutableMapOf<String, RemoteEvent>()
        var lastRequestedSinceMs: Long? = null

        override suspend fun fetchActive(): Result<List<RemoteEvent>> = Result.success(rows.values.toList())

        override suspend fun fetchChangedSince(sinceMs: Long): Result<List<RemoteEvent>> {
            lastRequestedSinceMs = sinceMs
            return Result.success(rows.values.filter { it.updatedAtMs >= sinceMs })
        }

        override suspend fun upsert(serverId: String?, fields: EventFields): Result<RemoteEvent> =
            Result.failure(EventsBackendException("not used by this test"))

        override suspend fun softDelete(serverId: String): Result<Boolean> =
            Result.failure(EventsBackendException("not used by this test"))

        override suspend fun skipOccurrence(serverId: String, skipDateEpochMs: Long): Result<Unit> =
            Result.failure(EventsBackendException("not used by this test"))

        override suspend fun fetchSkips(serverId: String): Result<List<Long>> = Result.success(emptyList())

        override suspend fun uploadMigratedEvent(event: MigratedEvent): Result<Boolean> =
            Result.failure(EventsBackendException("not used by this test"))
    }

    private fun remoteEvent(serverId: String, updatedAtMs: Long, originGuid: String) = RemoteEvent(
        serverId = serverId,
        title = "Row $serverId",
        createdAtMs = 500L,
        startsAtMs = null,
        endsAtMs = null,
        allDay = false,
        location = null,
        notes = null,
        source = "legion",
        googleEventId = null,
        done = false,
        doneAtMs = null,
        sortOrder = null,
        triggerPlaceLabel = null,
        repeatKind = null,
        repeatEvery = null,
        repeatDaysOfWeek = null,
        repeatDay = null,
        repeatMonth = null,
        repeatEndKind = null,
        repeatEndDateMs = null,
        repeatEndCount = null,
        exact = false,
        exactDowngraded = false,
        missedAtMs = null,
        missedDismissedAtMs = null,
        loggedAtMs = null,
        updatedAtMs = updatedAtMs,
        deleted = false,
        kind = EventKind.EVENT,
        originGuid = originGuid,
    )

    @Before
    fun clearState() {
        RoomTestReset.resetCarDatabaseSingleton()
    }

    @Test
    fun `with no stored watermark, the first pull requests sinceMs = 0 and fetches everything`() = runBlocking {
        assertEquals(0L, EventsPullCursor.lastPulledAtMs(context))
        val backend = SinceAwareFakeEventsBackend()
        backend.rows["srv-1"] = remoteEvent("srv-1", updatedAtMs = 1_000L, originGuid = "guid-1")
        backend.rows["srv-2"] = remoteEvent("srv-2", updatedAtMs = 2_000L, originGuid = "guid-2")

        val report = EventsSync.pull(context, backend)

        assertEquals(0L, backend.lastRequestedSinceMs)
        assertEquals(2, report.inserted)
    }

    @Test
    fun `the watermark advances to the newest row seen, and a repeat pull with no new rows is a no-op`() = runBlocking {
        val backend = SinceAwareFakeEventsBackend()
        backend.rows["srv-1"] = remoteEvent("srv-1", updatedAtMs = 1_000L, originGuid = "guid-1")

        val first = EventsSync.pull(context, backend)
        assertEquals(1, first.inserted)
        assertEquals(1_000L, EventsPullCursor.lastPulledAtMs(context))

        val second = EventsSync.pull(context, backend)
        assertEquals(1_000L, backend.lastRequestedSinceMs)
        assertEquals(0, second.inserted)
        assertEquals(0, second.updated)
        assertEquals(0, second.tombstoned)
        assertEquals(1_000L, EventsPullCursor.lastPulledAtMs(context))
    }

    @Test
    fun `a row added after the watermark is picked up on the next pull, without re-inserting the old one`() = runBlocking {
        val backend = SinceAwareFakeEventsBackend()
        backend.rows["srv-1"] = remoteEvent("srv-1", updatedAtMs = 1_000L, originGuid = "guid-1")
        EventsSync.pull(context, backend)
        assertEquals(1_000L, EventsPullCursor.lastPulledAtMs(context))

        backend.rows["srv-2"] = remoteEvent("srv-2", updatedAtMs = 2_000L, originGuid = "guid-2")
        val second = EventsSync.pull(context, backend)

        // sinceMs is inclusive - srv-1 (updatedAtMs = 1_000) is requested again but is already
        // merged, so it contributes nothing to this run's counts.
        assertEquals(1_000L, backend.lastRequestedSinceMs)
        assertEquals(1, second.inserted)
        assertEquals(2_000L, EventsPullCursor.lastPulledAtMs(context))
    }
}

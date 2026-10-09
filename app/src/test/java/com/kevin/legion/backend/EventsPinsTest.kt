package com.kevin.legion.backend

import com.kevin.legion.backend.engine.EngineFailure
import com.kevin.legion.backend.engine.EngineHttpException
import com.kevin.legion.backend.engine.EngineTestSupport
import com.kevin.legion.calendar.EventSuggestions
import com.kevin.legion.calendar.SuggestionPin
import com.kevin.legion.calendar.SuggestionPinActions
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.Event
import com.kevin.legion.data.local.OutboxOperation
import com.kevin.legion.testutil.RoomTestReset
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Suggestion pins, the phone side (Kevin, 2026-10-09): the optimistic local change, the queued
 * op, the terminal refusal, the drain, and what a pull may and may not do to a pinned row.
 * No network: [FakePinBackend] answers pin / unpin / pull however each test sets it.
 */
@RunWith(RobolectricTestRunner::class)
class EventsPinsTest {
    private val context = RuntimeEnvironment.getApplication()
    private val me = "11111111-1111-1111-1111-111111111111"
    private val miaAndMe = "[{\"user_id\":\"u-mia\",\"display_name\":\"Mia\"}," +
        "{\"user_id\":\"$me\",\"display_name\":\"Kevin\"}]"
    private val miaPin = """{"user_id":"u-mia","display_name":"Mia"}"""

    private class FakePinBackend : EventsBackend {
        var pinResult: Result<RemoteEvent> = Result.failure(EventsBackendException("offline"))
        var unpinResult: Result<RemoteEvent> = Result.failure(EventsBackendException("offline"))
        var feed: List<RemoteEvent> = emptyList()
        val calls = mutableListOf<String>()

        override suspend fun fetchActive(): Result<List<RemoteEvent>> = Result.success(feed)
        override suspend fun fetchChangedSince(sinceMs: Long): Result<List<RemoteEvent>> = Result.success(feed)
        override suspend fun upsert(serverId: String?, fields: EventFields): Result<RemoteEvent> =
            Result.failure(EventsBackendException("not used by this test"))
        override suspend fun softDelete(serverId: String): Result<Boolean> =
            Result.failure(EventsBackendException("not used by this test"))
        override suspend fun skipOccurrence(serverId: String, skipDateEpochMs: Long): Result<Unit> =
            Result.failure(EventsBackendException("not used by this test"))
        override suspend fun fetchSkips(serverId: String): Result<List<Long>> = Result.success(emptyList())
        override suspend fun uploadMigratedEvent(event: MigratedEvent): Result<Boolean> =
            Result.failure(EventsBackendException("not used by this test"))
        override suspend fun pin(serverId: String): Result<RemoteEvent> {
            calls += "pin $serverId"
            return pinResult
        }
        override suspend fun unpin(serverId: String): Result<RemoteEvent> {
            calls += "unpin $serverId"
            return unpinResult
        }
    }

    private lateinit var backend: FakePinBackend

    @Before
    fun setUp() {
        RoomTestReset.resetCarDatabaseSingleton()
        EngineTestSupport.signedInConfig(context)
        backend = FakePinBackend()
        EventsAppointmentWriter.backendOverride = backend
    }

    @After
    fun tearDown() {
        EventsAppointmentWriter.backendOverride = null
        RoomTestReset.drainArchDiskIoPool()
    }

    private fun remote(serverId: String, pinnedBy: String?, updatedAtMs: Long, kind: String = EventKind.SUGGESTION) =
        RemoteEvent(
            serverId = serverId, title = "Jazz night", createdAtMs = 1L, startsAtMs = 1_000L, endsAtMs = null,
            allDay = false, location = null, notes = null, source = "engine", googleEventId = null,
            done = false, doneAtMs = null, sortOrder = null, triggerPlaceLabel = null, repeatKind = null,
            repeatEvery = null, repeatDaysOfWeek = null, repeatDay = null, repeatMonth = null,
            repeatEndKind = null, repeatEndDateMs = null, repeatEndCount = null, exact = false,
            exactDowngraded = false, missedAtMs = null, missedDismissedAtMs = null, loggedAtMs = null,
            updatedAtMs = updatedAtMs, deleted = false, kind = kind, pinnedByJson = pinnedBy,
        )

    private suspend fun seed(serverId: String? = "srv-1", pinnedBy: String? = null): Event {
        val dao = CarDatabase.getDatabase(context).eventDao()
        val row = Event(
            serverId = serverId, guid = "guid-$serverId", title = "Jazz night", startsAt = 1_000L,
            source = "engine", kind = EventKind.SUGGESTION, updatedAtMs = 100L, pinnedByJson = pinnedBy,
        )
        return row.copy(id = dao.insert(row))
    }

    private suspend fun stored(id: Long) = CarDatabase.getDatabase(context).eventDao().getById(id)!!

    private suspend fun outbox() = CarDatabase.getDatabase(context).outboxDao().getAll()

    private fun refusal(status: Int) =
        Result.failure<RemoteEvent>(EngineHttpException(EngineFailure.Refused(status, "{\"detail\":\"no\"}")))

    @Test
    fun `an accepted pin shows the engines list and queues nothing`() = runBlocking {
        val row = seed()
        backend.pinResult = Result.success(remote("srv-1", miaAndMe, 200L))

        val write = EventsPins.setPinned(context, row, pinned = true) as PinWrite.Applied

        assertTrue(!write.queued)
        assertEquals(listOf("pin srv-1"), backend.calls)
        assertEquals(
            listOf("Mia", "Kevin"),
            SuggestionPin.parse(stored(row.id).pinnedByJson).map { it.displayName },
        )
        assertEquals(100L, stored(row.id).updatedAtMs)
        assertTrue(outbox().isEmpty())
    }

    @Test
    fun `an unreachable engine keeps the optimistic pin and queues one pin op`() = runBlocking {
        val row = seed(pinnedBy = "[$miaPin]")

        val write = EventsPins.setPinned(context, row, pinned = true) as PinWrite.Applied

        assertTrue(write.queued)
        assertEquals(listOf("Mia", "You"), SuggestionPin.parse(stored(row.id).pinnedByJson).map { it.displayName })
        val queued = outbox().single()
        assertEquals(OutboxOperation.PIN, queued.operation)
        assertEquals(row.id, queued.localId)
        assertTrue(queued.payload.contains("srv-1"))
    }

    @Test
    fun `unpin offline removes only my pin and the latest tap replaces the queued one`() = runBlocking {
        val row = seed(pinnedBy = "[$miaPin]")
        EventsPins.setPinned(context, row, pinned = true)
        val pinned = stored(row.id)

        val write = EventsPins.setPinned(context, pinned, pinned = false) as PinWrite.Applied

        assertTrue(write.queued)
        assertEquals(listOf("Mia"), SuggestionPin.parse(stored(row.id).pinnedByJson).map { it.displayName })
        assertEquals(listOf(OutboxOperation.UNPIN), outbox().map { it.operation })
    }

    @Test
    fun `a 400 or 404 is a terminal refusal that rolls the optimistic pin back and queues nothing`() = runBlocking {
        for (status in listOf(400, 404)) {
            val row = seed(serverId = "srv-$status", pinnedBy = "[$miaPin]")
            backend.pinResult = refusal(status)

            val write = EventsPins.setPinned(context, row, pinned = true)

            assertTrue(write is PinWrite.Refused)
            assertTrue((write as PinWrite.Refused).sentence.startsWith("Nothing was pinned"))
            assertEquals("[$miaPin]", stored(row.id).pinnedByJson)
        }
        assertTrue(outbox().isEmpty())
    }

    @Test
    fun `a row that never reached the engine is refused in words`() = runBlocking {
        val row = seed(serverId = null)

        val write = EventsPins.setPinned(context, row, pinned = true) as PinWrite.Refused

        assertEquals("Not on the engine yet, so it can't be pinned. Try again after it syncs.", write.sentence)
        assertEquals(null, stored(row.id).pinnedByJson)
        assertTrue(backend.calls.isEmpty())
    }

    @Test
    fun `the hands path says queued in words`() = runBlocking {
        val row = seed()

        val outcome = SuggestionPinActions.setPinned(context, row, pinned = true)

        assertEquals(
            EventSuggestions.Outcome.Done("Pinned on this phone. Queued - not on the engine yet.", queued = true),
            outcome,
        )
    }

    @Test
    fun `draining a queued pin adopts the engines list and clears the queue`() = runBlocking {
        val row = seed()
        EventsPins.setPinned(context, row, pinned = true)
        backend.pinResult = Result.success(remote("srv-1", miaAndMe, 300L))

        val report = EventsOutboxDrain.drain(context, backend)

        assertEquals(1, report.succeeded)
        assertTrue(outbox().isEmpty())
        assertEquals(
            listOf("Mia", "Kevin"),
            SuggestionPin.parse(stored(row.id).pinnedByJson).map { it.displayName },
        )
    }

    @Test
    fun `draining a pin the engine refuses for good drops it and takes my pin off the row`() = runBlocking {
        val row = seed(pinnedBy = "[$miaPin]")
        EventsPins.setPinned(context, row, pinned = true)
        backend.pinResult = refusal(400)

        val report = EventsOutboxDrain.drain(context, backend)

        assertEquals(1, report.refused)
        assertEquals(0, report.succeeded)
        assertTrue(outbox().isEmpty())
        assertEquals(listOf("Mia"), SuggestionPin.parse(stored(row.id).pinnedByJson).map { it.displayName })
    }

    @Test
    fun `a pull stores the engines pinned_by and the server list wins over an old local one`() = runBlocking {
        val row = seed(pinnedBy = "[$miaPin]")
        backend.feed = listOf(remote("srv-1", "[]", 200L))

        EventsSync.pull(context, backend)

        assertEquals("[]", stored(row.id).pinnedByJson)
    }

    @Test
    fun `a pull of a row with no pinned_by key leaves the stored pins alone`() = runBlocking {
        val row = seed(pinnedBy = "[$miaPin]")
        backend.feed = listOf(remote("srv-1", null, 200L))

        EventsSync.pull(context, backend)

        assertEquals("[$miaPin]", stored(row.id).pinnedByJson)
    }

    @Test
    fun `a pull inserts a new suggestion with its pins`() = runBlocking {
        backend.feed = listOf(remote("srv-new", "[$miaPin]", 200L))

        EventsSync.pull(context, backend)

        val inserted = CarDatabase.getDatabase(context).eventDao().getByServerId("srv-new")!!
        assertEquals("[$miaPin]", inserted.pinnedByJson)
    }

    @Test
    fun `a pull must not erase a pin still waiting in the outbox`() = runBlocking {
        val row = seed()
        EventsPins.setPinned(context, row, pinned = true)
        val optimistic = stored(row.id).pinnedByJson
        backend.feed = listOf(remote("srv-1", "[]", 200L))

        EventsSync.pull(context, backend)

        assertEquals(optimistic, stored(row.id).pinnedByJson)
        assertEquals(1, outbox().size)
    }
}

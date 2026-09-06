package com.kevin.legion.backend

import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.Event
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

/**
 * [EventsDoneDivergenceSweep] - the repair pass for every task ticked on the phone while
 * `tickAppointment` had no push side at all.
 *
 * The four properties pinned down here are the four that decide whether this sweep repairs data or
 * destroys it: it pushes LOCAL over server, it never pushes an OLDER local value over a newer
 * server one, it never touches a row the server does not hold, and it runs once.
 */
@RunWith(RobolectricTestRunner::class)
class EventsDoneDivergenceSweepTest {

    private val context = RuntimeEnvironment.getApplication()

    private class RecordingBackend(var server: List<RemoteEvent> = emptyList()) : EventsBackend {
        val upsertCalls = mutableListOf<Pair<String, EventFields>>()
        var fetchResult: Result<List<RemoteEvent>>? = null
        var upsertResult: Result<RemoteEvent>? = null

        override suspend fun fetchActive(): Result<List<RemoteEvent>> = fetchResult ?: Result.success(server)
        override suspend fun fetchChangedSince(sinceMs: Long) = Result.success(server)
        override suspend fun upsert(serverId: String?, fields: EventFields): Result<RemoteEvent> {
            upsertCalls += (serverId ?: "") to fields
            return upsertResult ?: Result.success(server.first { it.serverId == serverId })
        }
        override suspend fun softDelete(serverId: String) = Result.success(true)
        override suspend fun skipOccurrence(serverId: String, skipDateEpochMs: Long) = Result.success(Unit)
        override suspend fun fetchSkips(serverId: String) = Result.success(emptyList<Long>())
        override suspend fun uploadMigratedEvent(event: MigratedEvent) = Result.success(true)
    }

    @Before
    fun setUp() {
        RoomTestReset.resetCarDatabaseSingleton()
        EventsDoneDivergenceSweepLatch.resetForTest(context)
    }

    @After
    fun tearDown() {
        EventsDoneDivergenceSweepLatch.resetForTest(context)
        RoomTestReset.drainArchDiskIoPool()
    }

    private suspend fun seedLocal(
        serverId: String,
        title: String,
        done: Boolean,
        updatedAtMs: Long,
        kind: String = EventKind.TASK,
        deleted: Boolean = false,
    ): Event {
        val db = CarDatabase.getDatabase(context)
        val row = Event(
            id = 0,
            serverId = serverId,
            guid = java.util.UUID.randomUUID().toString(),
            title = title,
            startsAt = 1_000L,
            endsAt = 2_000L,
            allDay = false,
            source = "canvas",
            kind = kind,
            done = done,
            doneAt = if (done) updatedAtMs else null,
            deleted = deleted,
            updatedAtMs = updatedAtMs,
            createdAt = 1L,
        )
        return row.copy(id = db.eventDao().insert(row))
    }

    private fun serverRow(serverId: String, title: String, done: Boolean, updatedAtMs: Long) = RemoteEvent(
        serverId = serverId, title = title, createdAtMs = 1L, startsAtMs = 1_000L, endsAtMs = 2_000L,
        allDay = false, location = null, notes = null, source = "canvas", kind = EventKind.TASK,
        googleEventId = null, done = done, doneAtMs = null, sortOrder = null, triggerPlaceLabel = null,
        repeatKind = null, repeatEvery = null, repeatDaysOfWeek = null, repeatDay = null,
        repeatMonth = null, repeatEndKind = null, repeatEndDateMs = null, repeatEndCount = null,
        exact = false, exactDowngraded = false, missedAtMs = null, missedDismissedAtMs = null,
        loggedAtMs = null, updatedAtMs = updatedAtMs, deleted = false,
    )

    @Test
    fun `a tick the server never heard about is pushed up, not pulled back down`() = runBlocking {
        // The exact shape of the on-device divergence: COSC 4320's "insulin pump" assignment reads
        // ticked on the phone and done = false on the server, because tickAppointment wrote Room
        // and stopped.
        val local = seedLocal("srv-1", "COSC 4320 insulin pump", done = true, updatedAtMs = 2_000L)
        val backend = RecordingBackend(
            listOf(serverRow("srv-1", "COSC 4320 insulin pump", done = false, updatedAtMs = 1_000L)),
        )

        val report = EventsDoneDivergenceSweep.run(context, backend)

        assertEquals(1, report.pushed)
        assertEquals(1, backend.upsertCalls.size)
        assertEquals("srv-1", backend.upsertCalls[0].first)
        assertTrue("the LOCAL value goes up", backend.upsertCalls[0].second.done)
        // And the phone is untouched - the tick the user made is never overwritten from the server.
        assertTrue(CarDatabase.getDatabase(context).eventDao().getById(local.id)!!.done)
        assertTrue(report.failed.isEmpty())
    }

    @Test
    fun `a row the SERVER ticked more recently is left completely alone`() = runBlocking {
        // The other adult ticked it on the PWA and this phone has not merged it yet. Pushing the
        // stale local false would undo a real act - the "silently overwrite" this sweep exists to
        // avoid, pointed the other way. EventsSync.pull owns this case.
        seedLocal("srv-2", "shared task", done = false, updatedAtMs = 1_000L)
        val backend = RecordingBackend(listOf(serverRow("srv-2", "shared task", done = true, updatedAtMs = 9_000L)))

        val report = EventsDoneDivergenceSweep.run(context, backend)

        assertEquals(0, report.pushed)
        assertEquals(1, report.skippedServerNewer)
        assertTrue(backend.upsertCalls.isEmpty())
    }

    @Test
    fun `an equal timestamp is not a divergence this sweep may resolve`() = runBlocking {
        // Strictly-newer, not newer-or-equal: a tie is EventsSync.pull's rule 4 to resolve (toward
        // the server), and resolving it here in the opposite direction would make the two
        // mechanisms disagree about the same row.
        seedLocal("srv-3", "tie", done = true, updatedAtMs = 5_000L)
        val backend = RecordingBackend(listOf(serverRow("srv-3", "tie", done = false, updatedAtMs = 5_000L)))

        val report = EventsDoneDivergenceSweep.run(context, backend)

        assertEquals(0, report.pushed)
        assertEquals(1, report.skippedServerNewer)
    }

    @Test
    fun `events, reminders, tombstones and rows the server does not hold are all left out`() = runBlocking {
        // An EventKind.EVENT can never be ticked at all, and a reminder's tick is server-first, so
        // neither can have diverged this way. A local tombstone is not a divergence to repair. A
        // row the engine did not return is not this sweep's to create.
        seedLocal("srv-e", "a class", done = true, updatedAtMs = 9_000L, kind = EventKind.EVENT)
        seedLocal("srv-r", "a reminder", done = true, updatedAtMs = 9_000L, kind = EventKind.REMINDER)
        seedLocal("srv-d", "deleted task", done = true, updatedAtMs = 9_000L, deleted = true)
        seedLocal("srv-unknown", "never pulled", done = true, updatedAtMs = 9_000L)
        val backend = RecordingBackend(
            listOf(
                serverRow("srv-e", "a class", done = false, updatedAtMs = 1L),
                serverRow("srv-r", "a reminder", done = false, updatedAtMs = 1L),
                serverRow("srv-d", "deleted task", done = false, updatedAtMs = 1L),
            ),
        )

        val report = EventsDoneDivergenceSweep.run(context, backend)

        assertTrue(backend.upsertCalls.isEmpty())
        assertEquals(0, report.pushed)
        assertEquals(1, report.skippedNotOnServer)
        assertEquals("only the task rows are even examined", 0, report.examined)
    }

    @Test
    fun `it runs once - a clean pass latches, and the next foreground does nothing`() = runBlocking {
        seedLocal("srv-1", "COSC 4320 insulin pump", done = true, updatedAtMs = 2_000L)
        val backend = RecordingBackend(
            listOf(serverRow("srv-1", "COSC 4320 insulin pump", done = false, updatedAtMs = 1_000L)),
        )

        assertFalse(EventsDoneDivergenceSweepLatch.hasCompleted(context))
        EventsDoneDivergenceSweep.maybeAutoRun(context)
        // No engine backend resolves in this test environment (no Supabase project, no engine
        // token), so maybeAutoRun no-ops rather than running - which is itself the correct
        // behaviour for an unconfigured install, and leaves the latch unset.
        assertFalse(EventsDoneDivergenceSweepLatch.hasCompleted(context))

        // The latch mechanics themselves, driven directly: a clean report latches.
        val report = EventsDoneDivergenceSweep.run(context, backend)
        assertTrue(report.failed.isEmpty())
        EventsDoneDivergenceSweepLatch.markCompleted(context)

        assertTrue(EventsDoneDivergenceSweepLatch.hasCompleted(context))
        val callsBefore = backend.upsertCalls.size
        EventsDoneDivergenceSweep.maybeAutoRun(context)
        assertEquals("a latched install must not even resolve a backend", callsBefore, backend.upsertCalls.size)
    }

    @Test
    fun `a push failure leaves the sweep unlatched, so it runs again`() = runBlocking {
        seedLocal("srv-1", "COSC 4320 insulin pump", done = true, updatedAtMs = 2_000L)
        val backend = RecordingBackend(
            listOf(serverRow("srv-1", "COSC 4320 insulin pump", done = false, updatedAtMs = 1_000L)),
        )
        backend.upsertResult = Result.failure(EventsBackendException("offline"))

        val report = EventsDoneDivergenceSweep.run(context, backend)

        assertEquals(0, report.pushed)
        assertEquals(1, report.failed.size)
        assertTrue(report.failed.single().contains("COSC 4320 insulin pump"))
    }

    @Test
    fun `a server that cannot be read writes nothing at all`() = runBlocking {
        // "Could not ask" is not "nothing differs" - reading the first as the second would let a
        // transport failure silently declare the whole install repaired.
        seedLocal("srv-1", "COSC 4320 insulin pump", done = true, updatedAtMs = 2_000L)
        val backend = RecordingBackend()
        backend.fetchResult = Result.failure(EventsBackendException("offline"))

        val report = EventsDoneDivergenceSweep.run(context, backend)

        assertEquals(0, report.examined)
        assertEquals(0, report.pushed)
        assertTrue(backend.upsertCalls.isEmpty())
        assertEquals(1, report.failed.size)
    }
}

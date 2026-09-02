package com.kevin.legion.backend

import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.Event
import com.kevin.legion.testutil.RoomTestReset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * [EventsSync.pull] - the first live-sync slice for `public.events`, a MERGE pull that never
 * wipes the local table and never treats "the server does not have this row" as "delete it".
 *
 * Exercised against an in-memory [FakeEventsBackend] and the real (Robolectric) local `events`
 * table, same posture as [EventsReconcileTest] - never a network.
 */
@RunWith(RobolectricTestRunner::class)
class EventsSyncTest {
    private val context = RuntimeEnvironment.getApplication()

    /** Deliberately NOT [EventsReconcileTest]'s own fake - this one exposes [rows] directly so a
     * test can seed an arbitrary server snapshot (any serverId/originGuid/updatedAtMs/deleted
     * combination) without going through the upload path at all, which is irrelevant to a
     * pull-only test. */
    private class FakeEventsBackend : EventsBackend {
        val rows = mutableMapOf<String, RemoteEvent>()

        // Deliberately NOT filterNot { it.deleted }, unlike EventsReconcileTest's own fake and
        // unlike SupabaseEventsBackend.fetchActive() itself (`filter("deleted_at", IS, "null")`,
        // server-side). TRACED, and worth stating plainly rather than papering over: the REAL
        // backend's fetchActive() only ever returns rows where deleted_at IS NULL, so a genuine
        // tombstone written by another device is never present in what fetchActive() returns at
        // all - EventsSync.pull's `remote.deleted` branch is correct, defensive code against the
        // EventsBackend interface's own documented shape (RemoteEvent.deleted exists and is
        // meaningful), but it is UNREACHABLE end-to-end against the real backend today. This fake
        // returns tombstoned rows anyway so this suite can still prove pull()'s OWN merge logic is
        // correct for that branch - what it cannot prove, and what no test against the real
        // backend could either without a new backend method, is that a tombstone written on
        // another device ever reaches this phone at all. See EventsSync's own final report/ledger
        // for this gap stated as a blocking open question, not a resolved one.
        override suspend fun fetchActive(): Result<List<RemoteEvent>> =
            Result.success(rows.values.toList())

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

    @Before
    fun clearState() {
        RoomTestReset.resetCarDatabaseSingleton()
    }

    private fun remoteEvent(
        serverId: String,
        title: String,
        updatedAtMs: Long,
        kind: String = EventKind.REMINDER,
        originGuid: String? = null,
        deleted: Boolean = false,
        startsAtMs: Long? = null,
    ) = RemoteEvent(
        serverId = serverId,
        title = title,
        createdAtMs = 500L,
        startsAtMs = startsAtMs,
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
        deleted = deleted,
        kind = kind,
        originGuid = originGuid,
    )

    private suspend fun insertLocal(
        serverId: String,
        title: String,
        updatedAtMs: Long,
        guid: String = "",
        kind: String = EventKind.REMINDER,
        deleted: Boolean = false,
    ): Event {
        val row = Event(
            serverId = serverId,
            title = title,
            startsAt = null,
            source = "legion",
            updatedAtMs = updatedAtMs,
            kind = kind,
            guid = guid,
            deleted = deleted,
        )
        val id = CarDatabase.getDatabase(context).eventDao().insert(row)
        return row.copy(id = id)
    }

    @Test
    fun `a server-only row is inserted`() = runBlocking {
        val backend = FakeEventsBackend()
        backend.rows["server-1"] = remoteEvent("server-1", "COSC 4305 Report 1/7", updatedAtMs = 1_000L, originGuid = "guid-1")

        val report = EventsSync.pull(context, backend)

        assertEquals(1, report.inserted)
        assertEquals(0, report.updated)
        assertEquals(0, report.tombstoned)
        val all = CarDatabase.getDatabase(context).eventDao().getAllActive()
        assertEquals(1, all.size)
        assertEquals("COSC 4305 Report 1/7", all.single().title)
        assertEquals("server-1", all.single().serverId)
    }

    @Test
    fun `a local-only row survives untouched`() = runBlocking {
        val local = insertLocal("local-fake-server-id", "My own reminder", updatedAtMs = 1_000L, guid = "local-guid")
        val backend = FakeEventsBackend() // server has nothing at all

        val report = EventsSync.pull(context, backend)

        assertEquals(0, report.inserted)
        assertEquals(0, report.updated)
        assertEquals(0, report.tombstoned)
        val stored = CarDatabase.getDatabase(context).eventDao().getById(local.id)
        assertNotNull(stored)
        assertEquals("My own reminder", stored!!.title)
        assertEquals(false, stored.deleted)
    }

    @Test
    fun `a newer local row is not overwritten by an older server row`() = runBlocking {
        val local = insertLocal("srv-1", "Locally edited title", updatedAtMs = 5_000L, guid = "guid-2")
        val backend = FakeEventsBackend()
        backend.rows["srv-1"] = remoteEvent("srv-1", "Stale server title", updatedAtMs = 1_000L, originGuid = "guid-2")

        val report = EventsSync.pull(context, backend)

        assertEquals(0, report.updated)
        assertEquals(1, report.skippedLocalNewer)
        val stored = CarDatabase.getDatabase(context).eventDao().getById(local.id)
        assertEquals("Locally edited title", stored!!.title)
    }

    @Test
    fun `a newer server row is applied over an older local row`() = runBlocking {
        val local = insertLocal("srv-2", "Old local title", updatedAtMs = 1_000L, guid = "guid-3")
        val backend = FakeEventsBackend()
        backend.rows["srv-2"] = remoteEvent("srv-2", "Fresh server title", updatedAtMs = 5_000L, originGuid = "guid-3")

        val report = EventsSync.pull(context, backend)

        assertEquals(1, report.updated)
        assertEquals(0, report.skippedLocalNewer)
        val stored = CarDatabase.getDatabase(context).eventDao().getById(local.id)
        assertEquals("Fresh server title", stored!!.title)
        assertEquals("srv-2", stored.serverId)
    }

    @Test
    fun `a server tombstone soft-deletes the matching local row`() = runBlocking {
        val local = insertLocal("srv-3", "About to be deleted", updatedAtMs = 1_000L, guid = "guid-4")
        val backend = FakeEventsBackend()
        backend.rows["srv-3"] = remoteEvent("srv-3", "About to be deleted", updatedAtMs = 5_000L, originGuid = "guid-4", deleted = true)

        val report = EventsSync.pull(context, backend)

        assertEquals(1, report.tombstoned)
        val stored = CarDatabase.getDatabase(context).eventDao().getById(local.id)
        assertNotNull(stored)
        assertTrue(stored!!.deleted)
        // Soft delete, never hard delete - the row is still readable by id.
        assertNull(CarDatabase.getDatabase(context).eventDao().getAllActive().find { it.id == local.id })
    }

    @Test
    fun `an unrecognized kind is carried through, never silently dropped`() = runBlocking {
        val backend = FakeEventsBackend()
        backend.rows["srv-4"] = remoteEvent("srv-4", "Some future row shape", updatedAtMs = 1_000L, kind = "car_task", originGuid = "guid-5")

        val report = EventsSync.pull(context, backend)

        assertEquals(1, report.inserted)
        assertEquals(listOf("car_task"), report.unrecognizedKinds)
        val all = CarDatabase.getDatabase(context).eventDao().getAll()
        assertEquals(1, all.size)
        assertEquals("car_task", all.single().kind)
    }

    @Test
    fun `a second consecutive pull of the same server state is a no-op`() = runBlocking {
        insertLocal("srv-newer-local", "Kept as-is", updatedAtMs = 9_000L, guid = "guid-6")
        insertLocal("srv-5", "Will be overwritten", updatedAtMs = 1_000L, guid = "guid-7")
        val backend = FakeEventsBackend()
        backend.rows["srv-fresh-insert"] = remoteEvent("srv-fresh-insert", "Brand new", updatedAtMs = 1_000L, originGuid = "guid-8")
        backend.rows["srv-5"] = remoteEvent("srv-5", "Overwritten title", updatedAtMs = 5_000L, originGuid = "guid-7")
        backend.rows["srv-to-delete"] = remoteEvent("srv-to-delete", "Going away", updatedAtMs = 5_000L, originGuid = "guid-9", deleted = true)
        // The row this tombstone targets must exist locally first, or it is an ordinary
        // server-only deleted row (never inserted at all - see EventsSync.pull's own class doc:
        // a tombstone with no local match is simply not surfaced, matching an active row's own
        // "no local match -> insert" being the only way a deleted-nowhere-locally row could ever
        // be reintroduced, which this deliberately is not).
        insertLocal("srv-to-delete", "Going away", updatedAtMs = 1_000L, guid = "guid-9")

        val first = EventsSync.pull(context, backend)
        assertEquals(1, first.inserted)
        assertEquals(1, first.updated)
        assertEquals(1, first.tombstoned)

        val beforeSecond = CarDatabase.getDatabase(context).eventDao().getAll().sortedBy { it.id }

        val second = EventsSync.pull(context, backend)
        assertEquals(0, second.inserted)
        assertEquals(0, second.updated)
        assertEquals(0, second.tombstoned)
        assertEquals(0, second.skippedLocalNewer)

        val afterSecond = CarDatabase.getDatabase(context).eventDao().getAll().sortedBy { it.id }
        assertEquals(beforeSecond, afterSecond)
    }
}

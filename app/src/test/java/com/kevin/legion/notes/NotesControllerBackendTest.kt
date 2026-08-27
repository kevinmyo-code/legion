package com.kevin.legion.notes

import com.kevin.legion.backend.EventFields
import com.kevin.legion.backend.EventsBackend
import com.kevin.legion.backend.EventsBackendException
import com.kevin.legion.backend.MigratedEvent
import com.kevin.legion.backend.RemoteEvent
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.testutil.RoomTestReset
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * NotesController's CONFIGURED path (backend-erp Phase 4, aspect 4 of 5 - Notes+Dates merged,
 * `.scratch/backend-erp/issues/11-notes-write-path-rewire.md`). Exercised entirely through
 * [NotesController.backendOverride] and an in-memory [FakeEventsBackend] - never a real
 * `SupabaseClient` - mirroring `PlaceControllerBackendTest`/`PantryControllerBackendTest`'s shape,
 * so ticket 01 ruling 9 ("Room is written on server ACK, never ahead of it") and the CLAUDE.md
 * section 7 outcome-verb rule can both be asserted without a network. `NotesControllerTest` (this
 * package) covers the UNCONFIGURED (engine) path and is untouched by this ticket.
 */
@RunWith(RobolectricTestRunner::class)
class NotesControllerBackendTest {
    private val context = RuntimeEnvironment.getApplication()

    /**
     * Adapted from `EventsReconcileTest`'s own `FakeEventsBackend` - a separate copy rather than a
     * shared one, since that one is private to its file and shaped for a batch job's re-run/
     * idempotency semantics ([uploadMigratedEvent] is its only creation path); this one needs the
     * per-call failure toggles ([upsertFails]/[softDeleteFails]/[skipFails]) a live controller test
     * cares about, and never calls [uploadMigratedEvent] at all - that is `EventsReconcile`'s own
     * job, not [NotesController]'s.
     */
    private class FakeEventsBackend(
        var upsertFails: Boolean = false,
        var softDeleteFails: Boolean = false,
        var skipFails: Boolean = false,
    ) : EventsBackend {
        val rows = mutableMapOf<String, RemoteEvent>()
        val skips = mutableMapOf<String, MutableList<Long>>()
        var clock = 1_000L
        var nextId = 0
        var upsertCalls = 0
        var fetchActiveCalls = 0

        override suspend fun fetchActive(): Result<List<RemoteEvent>> {
            fetchActiveCalls++
            return Result.success(rows.values.filterNot { it.deleted })
        }

        override suspend fun upsert(serverId: String?, fields: EventFields): Result<RemoteEvent> {
            if (upsertFails) return Result.failure(EventsBackendException("simulated network failure"))
            upsertCalls++
            val id = serverId ?: "server-${nextId++}"
            val row = fields.toRemote(id, originGuid = rows[id]?.originGuid)
            rows[id] = row
            return Result.success(row)
        }

        override suspend fun softDelete(serverId: String): Result<Boolean> {
            if (softDeleteFails) return Result.failure(EventsBackendException("simulated network failure"))
            val existing = rows[serverId] ?: return Result.success(false)
            if (existing.deleted) return Result.success(false)
            rows[serverId] = existing.copy(deleted = true)
            return Result.success(true)
        }

        override suspend fun skipOccurrence(serverId: String, skipDateEpochMs: Long): Result<Unit> {
            if (skipFails) return Result.failure(EventsBackendException("simulated network failure"))
            skips.getOrPut(serverId) { mutableListOf() }.add(skipDateEpochMs)
            return Result.success(Unit)
        }

        override suspend fun fetchSkips(serverId: String): Result<List<Long>> =
            Result.success(skips[serverId].orEmpty())

        override suspend fun uploadMigratedEvent(event: MigratedEvent): Result<Boolean> =
            error("NotesControllerBackendTest never exercises the migration path - that is EventsReconcile's own job")

        // Mirrors public.events.created_at's real behaviour, same reasoning as
        // EventsReconcileTest's own identically-named private mapper.
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

    @After
    fun clearOverride() {
        // Drains ArchTaskExecutor's disk-IO pool before anything else in this @After - see
        // RoomTestReset's class doc comment and
        // .scratch/hardening/issues/13-the-suite-is-green-by-luck.md: a DAO write earlier in
        // this test can leave a Room InvalidationTracker refresh in flight, and it must finish
        // before this test method returns or it races Robolectric's per-method reset.
        RoomTestReset.drainArchDiskIoPool()

        NotesController.backendOverride = null
    }

    @Test
    fun `configured addItem writes the replica once, after the ACK`() = runBlocking {
        val backend = FakeEventsBackend()
        NotesController.backendOverride = backend

        val item = NotesController.addItem(context, listId = 1L, text = "buy milk")

        assertEquals("buy milk", item.text)
        assertEquals(1, backend.upsertCalls)
        val replica = CarDatabase.getDatabase(context).eventReplicaDao().getAllActive()
        assertEquals(1, replica.size)
        assertEquals("buy milk", replica.single().title)
    }

    @Test
    fun `a FAILED write leaves the replica byte-identical and returns the no-false-success value`() = runBlocking {
        val backend = FakeEventsBackend()
        NotesController.backendOverride = backend
        val item = NotesController.addItem(context, listId = 1L, text = "keep me")

        backend.upsertFails = true
        val result = NotesController.renameItem(context, item, "renamed")

        assertFalse("a failed remote write must never report success", result)
        val replica = CarDatabase.getDatabase(context).eventReplicaDao().getAllActive().single()
        assertEquals("the replica must be untouched by a failed write", "keep me", replica.title)
    }

    @Test
    fun `ListItem id read back through the configured path equals events_replica id - the alarm request-code contract`() = runBlocking {
        val backend = FakeEventsBackend()
        NotesController.backendOverride = backend

        val item = NotesController.addItem(context, listId = 1L, text = "alarm-bound item")
        val replicaRow = CarDatabase.getDatabase(context).eventReplicaDao().getAllActive().single()

        assertEquals(replicaRow.id, item.id)
        val reread = NotesController.itemById(context, item.id)
        assertEquals(replicaRow.id, reread!!.id)
    }

    @Test
    fun `configured reads come from the replica and never touch the backend`() = runBlocking {
        val backend = FakeEventsBackend()
        NotesController.backendOverride = backend
        val item = NotesController.addItem(context, listId = 1L, text = "seeded")
        backend.fetchActiveCalls = 0 // reset after addItem's own bookkeeping, to isolate the reads under test

        val all = NotesController.allItems(context)
        val byId = NotesController.itemById(context, item.id)

        assertEquals(1, all.size)
        assertEquals("seeded", byId?.text)
        assertEquals(
            "a read must never call EventsBackend.fetchActive - it reads the Room replica",
            0,
            backend.fetchActiveCalls,
        )
    }

    @Test
    fun `skipOccurrence round-trips through the backend into the skip replica`() = runBlocking {
        val backend = FakeEventsBackend()
        NotesController.backendOverride = backend
        val item = NotesController.addItem(context, listId = 1L, text = "recurring errand")
        val withRepeat = NotesController.setRepeat(context, item, RepeatRule.Daily(1), RepeatEnd.Never)!!

        NotesController.skipOccurrence(context, withRepeat, 20_000L)

        assertEquals(listOf(20_000L), backend.skips[withRepeat.syncId])
        val skipped = NotesController.skippedDates(context, withRepeat)
        assertEquals(setOf(20_000L), skipped)
    }

    @Test
    fun `configured start-up sweep never calls markMissed - the 2026-08-26 incident guard`() = runBlocking {
        // Real-world shape of the incident (see AlarmScheduler.rescheduleAll's own doc comment
        // and .scratch/backend-erp/issues/11-notes-write-path-rewire.md): an item whose startsAt
        // is long in the past and whose missedAt is still null, exactly what the replica served
        // back for 50 deleted-on-phone-but-not-on-server todos plus one genuinely overdue one.
        val backend = FakeEventsBackend()
        NotesController.backendOverride = backend
        val item = NotesController.addItem(context, listId = 1L, text = "long overdue")
        NotesController.setTime(context, item, startsAt = 1_000L, endsAt = null, allDay = false)
        backend.upsertCalls = 0 // isolate the sweep's own writes from addItem/setTime's setup calls

        AlarmScheduler.rescheduleAll(context)

        assertEquals(
            "the sweep must not write to the backend at all on the configured path",
            0,
            backend.upsertCalls,
        )
        val reread = NotesController.itemById(context, item.id)
        assertEquals(
            "missedAt must stay null - the app must not assert a miss it never held",
            null,
            reread?.missedAt,
        )
    }

    @Test
    fun `removeItem on a failed softDelete does not delete the replica row and does not report success`() = runBlocking {
        val backend = FakeEventsBackend()
        NotesController.backendOverride = backend
        val item = NotesController.addItem(context, listId = 1L, text = "sticky item")

        backend.softDeleteFails = true
        val removed = NotesController.removeItem(context, item)

        assertFalse("a failed remote delete must never report success", removed)
        val replica = CarDatabase.getDatabase(context).eventReplicaDao().getAllActive()
        assertEquals("the replica row must survive a failed remote delete", 1, replica.size)
    }
}

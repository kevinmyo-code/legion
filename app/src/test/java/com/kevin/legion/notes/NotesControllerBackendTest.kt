package com.kevin.legion.notes

import com.kevin.legion.backend.EventFields
import com.kevin.legion.backend.EventKind
import com.kevin.legion.backend.EventsBackend
import com.kevin.legion.backend.EventsBackendException
import com.kevin.legion.backend.MigratedEvent
import com.kevin.legion.backend.RemoteEvent
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.Event
import com.kevin.legion.data.local.upsert
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
        val replica = CarDatabase.getDatabase(context).eventDao().getAllActive()
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
        val replica = CarDatabase.getDatabase(context).eventDao().getAllActive().single()
        assertEquals("the replica must be untouched by a failed write", "keep me", replica.title)
    }

    @Test
    fun `ListItem id read back through the configured path equals events_replica id - the alarm request-code contract`() = runBlocking {
        val backend = FakeEventsBackend()
        NotesController.backendOverride = backend

        val item = NotesController.addItem(context, listId = 1L, text = "alarm-bound item")
        val replicaRow = CarDatabase.getDatabase(context).eventDao().getAllActive().single()

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
    fun `configured reads exclude an appointment sitting in the same replica table - ticket 11's 2026-08-27 kind filter`() = runBlocking {
        val backend = FakeEventsBackend()
        NotesController.backendOverride = backend
        val reminder = NotesController.addItem(context, listId = 1L, text = "a real reminder")
        // Seeded directly, the same shape a real EventsReconcile refill leaves behind for a Dates
        // Event/Google import - events_replica holds both kinds merged into one table, and this
        // file must never hand one back as a ListItem it owns (the 2026-08-26 incident's other
        // root cause, alongside AlarmScheduler's own sweep - see that file's rescheduleAll doc).
        val db = CarDatabase.getDatabase(context)
        db.eventDao().upsert(
            Event(
                id = 0,
                serverId = "appointment-1",
                title = "Dentist",
                startsAt = 90_000L,
                source = "legion",
                kind = EventKind.APPOINTMENT,
                updatedAtMs = 1L,
                createdAt = 1L,
            ),
        )

        val all = NotesController.allItems(context)
        val appointmentId = db.eventDao().getByServerId("appointment-1")!!.id

        assertEquals("only the reminder must come back, never the appointment", 1, all.size)
        assertEquals("a real reminder", all.single().text)
        assertEquals(
            "itemById must refuse an id that resolves to an appointment, not just allItems",
            null,
            NotesController.itemById(context, appointmentId),
        )
        assertEquals(
            "the reminder itself must still resolve normally",
            "a real reminder",
            NotesController.itemById(context, reminder.id)?.text,
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
    fun `configured start-up sweep marks a genuine overdue reminder missed - the 2026-08-26 incident, fixed for real`() = runBlocking {
        // The 2026-08-26 incident's REAL fix (ticket 11's 2026-08-27 ruling): the sweep withheld
        // every missed-mark on a configured install as a stopgap, because the read it walked could
        // not tell a genuinely-overdue reminder from a deleted todo the server never heard about.
        // Both root causes are fixed now (events.kind + EventsReconcile's deletion propagation),
        // so the read this sweep walks is correct by construction and the stopgap is gone - a real
        // overdue reminder is marked missed on a configured install again, same as unconfigured.
        // The negative half of this incident's regression test (an APPOINTMENT must never be
        // touched) lives in AlarmSchedulerTest, which seeds the replica with both kinds directly
        // and asserts on both in one place - "unmistakable" per this ticket's own verification bar.
        val backend = FakeEventsBackend()
        NotesController.backendOverride = backend
        val item = NotesController.addItem(context, listId = 1L, text = "genuinely overdue")
        NotesController.setTime(context, item, startsAt = 1_000L, endsAt = null, allDay = false)

        AlarmScheduler.rescheduleAll(context)

        val reread = NotesController.itemById(context, item.id)
        assertEquals(
            "a real overdue reminder must be marked missed on the configured path too, now that the read is correct",
            true,
            reread?.missedAt != null,
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
        val replica = CarDatabase.getDatabase(context).eventDao().getAllActive()
        assertEquals("the replica row must survive a failed remote delete", 1, replica.size)
    }
}

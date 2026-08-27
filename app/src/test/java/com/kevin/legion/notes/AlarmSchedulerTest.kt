package com.kevin.legion.notes

import com.kevin.legion.backend.EventFields
import com.kevin.legion.backend.EventKind
import com.kevin.legion.backend.EventsBackend
import com.kevin.legion.backend.EventsBackendException
import com.kevin.legion.backend.MigratedEvent
import com.kevin.legion.backend.RemoteEvent
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.EventReplica
import com.kevin.legion.data.local.upsert
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
 * [AlarmScheduler.rescheduleAll]'s own regression test for the 2026-08-26 incident
 * (`.scratch/backend-erp/issues/11-notes-write-path-rewire.md`) - REWRITTEN 2026-08-27 after the
 * real fix landed (`events.kind` + `EventsReconcile`'s deletion propagation) and the stopgap
 * `shouldSweepMarkMissed` guard this file used to test was removed in the same commit.
 *
 * **Why this file, not `NotesControllerBackendTest`.** The incident's whole shape was "the sweep
 * cannot tell a reminder from something it does not own" - so the sharpest regression test seeds
 * BOTH kinds into the SAME replica and asserts on both in one test, unmistakably: a reminder gets
 * marked missed, an appointment sitting right next to it is never touched at all. `NotesController`
 * has no write path that ever produces an appointment (ticket 11's ruling #1 - only
 * `EventsReconcile`'s Dates branch does), so the appointment row here is seeded directly into
 * `events_replica`, the same shape a real `EventsReconcile` refill would have left behind.
 */
@RunWith(RobolectricTestRunner::class)
class AlarmSchedulerTest {
    private val context = RuntimeEnvironment.getApplication()

    /** Minimal fake, adapted from `NotesControllerBackendTest`'s own copy - this file only ever
     * needs `upsert` (for [NotesController.markMissed]'s write). */
    private class FakeEventsBackend : EventsBackend {
        val rows = mutableMapOf<String, RemoteEvent>()
        var clock = 1_000L
        var upsertCalls = 0

        override suspend fun fetchActive(): Result<List<RemoteEvent>> = Result.success(rows.values.filterNot { it.deleted })

        override suspend fun upsert(serverId: String?, fields: EventFields): Result<RemoteEvent> {
            upsertCalls++
            val id = serverId ?: "server-${rows.size}"
            val row = RemoteEvent(
                serverId = id,
                title = fields.title,
                createdAtMs = fields.createdAtMs ?: ++clock,
                startsAtMs = fields.startsAtMs,
                endsAtMs = fields.endsAtMs,
                allDay = fields.allDay,
                location = fields.location,
                notes = fields.notes,
                structuredMeta = fields.structuredMeta,
                source = fields.source,
                kind = fields.kind,
                googleEventId = fields.googleEventId,
                done = fields.done,
                doneAtMs = fields.doneAtMs,
                sortOrder = fields.sortOrder,
                triggerPlaceLabel = fields.triggerPlaceLabel,
                repeatKind = fields.repeatKind,
                repeatEvery = fields.repeatEvery,
                repeatDaysOfWeek = fields.repeatDaysOfWeek,
                repeatDay = fields.repeatDay,
                repeatMonth = fields.repeatMonth,
                repeatEndKind = fields.repeatEndKind,
                repeatEndDateMs = fields.repeatEndDateMs,
                repeatEndCount = fields.repeatEndCount,
                exact = fields.exact,
                exactDowngraded = fields.exactDowngraded,
                missedAtMs = fields.missedAtMs,
                missedDismissedAtMs = fields.missedDismissedAtMs,
                loggedAtMs = fields.loggedAtMs,
                updatedAtMs = ++clock,
                deleted = false,
                originGuid = rows[id]?.originGuid,
            )
            rows[id] = row
            return Result.success(row)
        }

        override suspend fun softDelete(serverId: String): Result<Boolean> = error("not exercised by this file")
        override suspend fun skipOccurrence(serverId: String, skipDateEpochMs: Long): Result<Unit> = error("not exercised by this file")
        override suspend fun fetchSkips(serverId: String): Result<List<Long>> = Result.success(emptyList())
        override suspend fun uploadMigratedEvent(event: MigratedEvent): Result<Boolean> = error("not exercised by this file")
    }

    @Before
    fun clearState() {
        RoomTestReset.resetCarDatabaseSingleton()
    }

    @After
    fun clearOverride() {
        // See NotesControllerBackendTest's identically-worded @After for why this must run before
        // Robolectric's own per-method reset - a DAO write here can leave a Room
        // InvalidationTracker refresh in flight.
        RoomTestReset.drainArchDiskIoPool()
        NotesController.backendOverride = null
    }

    @Test
    fun `configured sweep marks a genuinely overdue reminder missed, and never touches an appointment sitting next to it`() = runBlocking {
        val backend = FakeEventsBackend()
        NotesController.backendOverride = backend
        val db = CarDatabase.getDatabase(context)

        // The reminder: an ordinary NotesController-authored item, overdue.
        val reminder = NotesController.addItem(context, listId = 1L, text = "genuinely overdue reminder")
        NotesController.setTime(context, reminder, startsAt = 1_000L, endsAt = null, allDay = false)

        // The appointment: seeded directly into events_replica, exactly the shape a real
        // EventsReconcile refill leaves behind for a Dates Event/Google import - overdue too, and
        // with no missedAt field for a Dates Event to even populate meaningfully, matching the
        // incident's own finding ("the Dates Event record type has no missedAt field at all").
        db.eventReplicaDao().upsert(
            EventReplica(
                id = 0,
                serverId = "appointment-server-1",
                title = "Dentist",
                startsAt = 2_000L,
                source = "legion",
                kind = EventKind.APPOINTMENT,
                updatedAtMs = 1L,
                createdAt = 1L,
            ),
        )
        backend.upsertCalls = 0 // isolate the sweep's own writes from the setup above

        AlarmScheduler.rescheduleAll(context)

        val rereadReminder = NotesController.itemById(context, reminder.id)
        assertTrue(
            "the genuinely overdue REMINDER must be marked missed - this is the fix, not the incident",
            rereadReminder?.missedAt != null,
        )

        val appointmentRow = db.eventReplicaDao().getByServerId("appointment-server-1")!!
        assertNull(
            "the APPOINTMENT must never be touched by this sweep - it is not something NotesController owns",
            appointmentRow.missedAt,
        )
        assertEquals(
            "the sweep must write exactly once - for the reminder's own markMissed, never for the appointment",
            1,
            backend.upsertCalls,
        )
    }
}

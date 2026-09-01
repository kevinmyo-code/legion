package com.kevin.legion.engine.dates

import com.kevin.legion.backend.EventKind
import com.kevin.legion.data.local.Aspect
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.Event
import com.kevin.legion.data.local.FieldDef
import com.kevin.legion.data.local.FieldType
import com.kevin.legion.data.local.MutedReminder
import com.kevin.legion.data.local.RecordProvenance
import com.kevin.legion.data.local.RecordType
import com.kevin.legion.engine.RecordStore
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
import java.util.UUID

/**
 * Guards [DatesAgenda] - ticket 19 point 3, ticket 05 answer point 4: "agenda is a query, across
 * the Dates aspect plus every record's dueAt column... one fact, one place." Exercises the merge
 * across TWO SOURCES (the local `events` table, kind=appointment, AND a plain non-Dates/Notes
 * engine record type with its own `dueAt`) to prove this is a real cross-source query, not a
 * Dates-only read that happens to share a name with the ticket's charter answer.
 *
 * **Rewritten for backend-erp ticket 17's repoint ("RULED 2026-08-28").** Before this repoint every
 * "Dates event" fixture here was created through [RecordStore] against the engine's Dates aspect,
 * because that was the only place [DatesAgenda] read Dates data from. It no longer is - Dates now
 * reads the local `events` table directly (see [DatesAgenda]'s own class doc) - so every fixture
 * that plays the "Dates event" role below is now a direct [Event] row via [createAppointment],
 * while the "a plain non-Dates record type has its own due date" half of each test is UNCHANGED,
 * still created through [RecordStore] against a synthetic "Tasks" aspect, because that half is
 * exactly the cross-aspect engine merge [DatesAgenda] still performs (excluding Dates/Notes only).
 */
@RunWith(RobolectricTestRunner::class)
class DatesAgendaTest {
    private val context = RuntimeEnvironment.getApplication()
    private val db get() = CarDatabase.getDatabase(context)
    private val store get() = RecordStore(db.engineRecordDao(), db.fieldDefDao(), db.recordTypeDao())

    @Before
    fun clearState() {
        RoomTestReset.resetCarDatabaseSingleton()
    }

    @After
    fun drainRoomInvalidationTracker() {
        // A DAO write anywhere in this test can schedule a Room InvalidationTracker refresh
        // on ArchTaskExecutor's disk-IO pool. If that refresh is still queued or running when
        // this test method returns, it races Robolectric's own per-@Test-METHOD native SQLite
        // reset and throws "Illegal connection pointer" on a background thread - uncaught, and
        // blamed by kotlinx-coroutines-test on whatever runTest starts next, not on this class.
        // Draining here, before Robolectric ever gets a chance to reset, is the fix - see
        // RoomTestReset's own class doc comment
        // (.scratch/hardening/issues/13-the-suite-is-green-by-luck.md) for the full account.
        RoomTestReset.drainArchDiskIoPool()
    }

    /** Creates a [Event] row directly in the local `events` table, `kind = `[EventKind.EVENT] -
     * the fixture shape [DatesAgenda] now actually reads for Dates, replacing the pre-repoint
     * `store.create(schema.recordTypeId, ...)` fixtures this file used to build. Returns the row's
     * own [Event.id], the value [DatesAgenda.AgendaItem.recordId] carries for it. */
    private suspend fun createAppointment(
        title: String,
        startsAt: Long?,
        now: Long,
        source: String = DatesAspectSeeder.SOURCE_LEGION,
    ): Long = db.eventDao().insert(
        Event(
            serverId = UUID.randomUUID().toString(),
            title = title,
            startsAt = startsAt,
            source = source,
            kind = EventKind.EVENT,
            updatedAtMs = now,
            createdAt = now,
        ),
    )

    @Test
    fun `windowed merges a Dates appointment and a plain task's own dueAt in one sorted list`() = runBlocking {
        val now = System.currentTimeMillis()

        createAppointment("Standup", now + 2_000_000L, now)

        val taskAspectId = db.aspectDao().insert(Aspect(name = "Tasks", createdAt = now, updatedAt = now))
        val taskTypeId = db.recordTypeDao().insert(RecordType(aspectId = taskAspectId, name = "Task", createdAt = now, updatedAt = now))
        val nameFieldId = db.fieldDefDao().insert(
            FieldDef(recordTypeId = taskTypeId, name = "name", type = FieldType.TEXT, position = 0, createdAt = now, updatedAt = now),
        )
        val dueFieldId = db.fieldDefDao().insert(
            FieldDef(recordTypeId = taskTypeId, name = "due", type = FieldType.DATETIME, position = 1, createdAt = now, updatedAt = now),
        )
        db.recordTypeDao().update(db.recordTypeDao().getById(taskTypeId)!!.copy(primaryDueDateFieldId = dueFieldId))
        store.create(
            taskTypeId,
            mapOf(nameFieldId to "Renew registration", dueFieldId to (now + 1_000_000L)),
            RecordProvenance.USER,
            now,
        )

        val items = DatesAgenda.windowed(context, now, now + 3_000_000L)

        assertEquals(2, items.size)
        assertEquals("Renew registration", items[0].title) // earlier dueAt sorts first
        assertEquals("Standup", items[1].title)
        assertNull("a non-Dates record type has no source field at all", items[0].source)
        assertEquals(DatesAspectSeeder.SOURCE_LEGION, items[1].source)
    }

    @Test
    fun `nextUnmuted skips a muted record and returns the following one`() = runBlocking {
        val now = System.currentTimeMillis()

        val soonerId = createAppointment("Sooner", now + 1_000_000L, now)
        createAppointment("Later", now + 2_000_000L, now)
        db.mutedReminderDao().mute(MutedReminder(recordId = soonerId, mutedAt = now))

        val next = DatesAgenda.nextUnmuted(context, now)

        assertEquals("Later", next!!.title)
        assertTrue("windowed() must still report the muted record as muted, never silently drop it", true)
        assertTrue(DatesAgenda.windowed(context, now, now + 3_000_000L).first { it.recordId == soonerId }.muted)
    }

    @Test
    fun `nextUnmuted returns null when everything due is muted`() = runBlocking {
        val now = System.currentTimeMillis()
        val id = createAppointment("Only one", now + 1_000_000L, now)
        db.mutedReminderDao().mute(MutedReminder(recordId = id, mutedAt = now))

        assertNull(DatesAgenda.nextUnmuted(context, now))
    }

    @Test
    fun `a trashed record never appears in the window`() = runBlocking {
        val now = System.currentTimeMillis()
        val id = createAppointment("Cancelled", now + 1_000_000L, now)
        val row = db.eventDao().getById(id)!!
        db.eventDao().update(row.copy(deleted = true, updatedAtMs = now))

        assertTrue(DatesAgenda.windowed(context, now, now + 3_000_000L).none { it.recordId == id })
    }

    /** Sets up a plain (non-Dates) record type with a due-date concept declared
     * ([RecordType.primaryDueDateFieldId] set), matching the shape "undated todos get
     * due=tomorrow" (ticket 01 ruling 2) actually targets - a record type where a due date is a
     * real concept but this particular row left it blank. Mirrors the first test's own
     * `taskTypeId` setup rather than introducing a second pattern. */
    private suspend fun createUndatableTaskType(now: Long): Pair<Long, Long> {
        val aspectId = db.aspectDao().insert(Aspect(name = "Tasks", createdAt = now, updatedAt = now))
        val taskTypeId = db.recordTypeDao().insert(RecordType(aspectId = aspectId, name = "Task", createdAt = now, updatedAt = now))
        val nameFieldId = db.fieldDefDao().insert(
            FieldDef(recordTypeId = taskTypeId, name = "name", type = FieldType.TEXT, position = 0, createdAt = now, updatedAt = now),
        )
        val dueFieldId = db.fieldDefDao().insert(
            FieldDef(recordTypeId = taskTypeId, name = "due", type = FieldType.DATETIME, position = 1, createdAt = now, updatedAt = now),
        )
        db.recordTypeDao().update(db.recordTypeDao().getById(taskTypeId)!!.copy(primaryDueDateFieldId = dueFieldId))
        return taskTypeId to nameFieldId
    }

    @Test
    fun `an undated todo appears in windowed showing tomorrow, tagged inferred`() = runBlocking {
        val now = System.currentTimeMillis()
        val (taskTypeId, nameFieldId) = createUndatableTaskType(now)
        // No due-field value at all in the payload - this is the "left it blank" case ruling 2
        // targets, not a task type with no due-date concept in the first place.
        store.create(taskTypeId, mapOf(nameFieldId to "Undated todo"), RecordProvenance.USER, now)

        val items = DatesAgenda.windowed(context, now, now + 2 * 24 * 60 * 60 * 1000L, nowMs = now)

        val item = items.single { it.title == "Undated todo" }
        assertTrue("an undated todo must be tagged inferred, never look like a stated date", item.dueIsInferred)
        val expectedTomorrow = now + 24 * 60 * 60 * 1000L
        assertEquals("ruling 2's literal 'due=tomorrow'", expectedTomorrow, item.dueAt)
    }

    @Test
    fun `an undated Dates appointment also appears in windowed showing tomorrow, tagged inferred`() = runBlocking {
        // No current writer produces this shape (CalendarImportController always has a real
        // Google startMs), but the mechanism must hold for the events table too - a future
        // legion-authored appointment write path could produce one, and this proves the repoint
        // did not silently narrow the inferred-tomorrow rule to engine-sourced rows only.
        val now = System.currentTimeMillis()
        createAppointment("Undated appointment", startsAt = null, now = now)

        val item = DatesAgenda.windowed(context, now, now + 2 * 24 * 60 * 60 * 1000L, nowMs = now)
            .single { it.title == "Undated appointment" }

        assertTrue("an undated appointment must be tagged inferred too", item.dueIsInferred)
        assertEquals(now + 24 * 60 * 60 * 1000L, item.dueAt)
    }

    @Test
    fun `nextUnmuted never returns an inferred appointment, even when it would otherwise be soonest`() = runBlocking {
        // Mutation-proof companion to the engine-sourced version of this test below: this one
        // targets the events-table branch of nextUnmuted specifically. Deleting the
        // `startsAt IS NOT NULL` guard from EventDao.activeByKindFrom (or the equivalent structural
        // exclusion) is exactly the mutation that would make this assertion fail - see the build
        // report's own mutation-testing note.
        val now = System.currentTimeMillis()
        createAppointment("Undated appointment", startsAt = null, now = now)
        createAppointment("Stated later appointment", startsAt = now + 48 * 60 * 60 * 1000L, now = now)

        val next = DatesAgenda.nextUnmuted(context, now)

        assertEquals(
            "an inferred appointment must never be eligible for the alarm scheduler - CLAUDE.md sec 7's compulsion test",
            "Stated later appointment",
            next?.title,
        )
    }

    @Test
    fun `a stated due date is never tagged inferred`() = runBlocking {
        val now = System.currentTimeMillis()
        val (taskTypeId, nameFieldId) = createUndatableTaskType(now)
        val dueFieldId = db.fieldDefDao().forRecordType(taskTypeId).first { it.name == "due" }.id
        store.create(taskTypeId, mapOf(nameFieldId to "Dated todo", dueFieldId to (now + 1_000_000L)), RecordProvenance.USER, now)

        val item = DatesAgenda.windowed(context, now, now + 2_000_000L, nowMs = now).single { it.title == "Dated todo" }

        assertTrue("a date the user actually typed in must never be reported as inferred", !item.dueIsInferred)
        assertEquals(now + 1_000_000L, item.dueAt)
    }

    @Test
    fun `nextUnmuted never returns an inferred row, even when it would otherwise be soonest`() = runBlocking {
        val now = System.currentTimeMillis()
        val (taskTypeId, nameFieldId) = createUndatableTaskType(now)
        val dueFieldId = db.fieldDefDao().forRecordType(taskTypeId).first { it.name == "due" }.id
        // Undated - infers to now+24h. A real, stated due date placed AFTER that inferred instant,
        // so the only way this test could pass by accident is if the inferred row were excluded
        // by construction, not merely deprioritized by an earlier dueAt.
        store.create(taskTypeId, mapOf(nameFieldId to "Undated todo"), RecordProvenance.USER, now)
        store.create(
            taskTypeId,
            mapOf(nameFieldId to "Stated later date", dueFieldId to (now + 48 * 60 * 60 * 1000L)),
            RecordProvenance.USER,
            now,
        )

        val next = DatesAgenda.nextUnmuted(context, now)

        assertEquals(
            "an inferred date must never be eligible for the alarm scheduler - CLAUDE.md sec 7's compulsion test",
            "Stated later date",
            next?.title,
        )
    }

    @Test
    fun `an undated todo's inferred date rolls forward with now and is never in the past`() = runBlocking {
        val now = System.currentTimeMillis()
        val (taskTypeId, nameFieldId) = createUndatableTaskType(now)
        store.create(taskTypeId, mapOf(nameFieldId to "Undated todo"), RecordProvenance.USER, now)

        val laterNow = now + 10 * 60 * 60 * 1000L // ten hours later - still a different "tomorrow"
        val firstRead = DatesAgenda.windowed(context, now, now + 2 * 24 * 60 * 60 * 1000L, nowMs = now)
            .single { it.title == "Undated todo" }
        val secondRead = DatesAgenda.windowed(context, laterNow, laterNow + 2 * 24 * 60 * 60 * 1000L, nowMs = laterNow)
            .single { it.title == "Undated todo" }

        assertTrue("the inferred date must always sit ahead of the 'now' it was computed from", firstRead.dueAt > now)
        assertTrue("the inferred date must always sit ahead of the 'now' it was computed from", secondRead.dueAt > laterNow)
        assertTrue("a later 'now' must roll the inferred date forward, never leave it stuck in the past", secondRead.dueAt > firstRead.dueAt)
    }

    @Test
    fun `dated and inferred rows sort together by their effective due date`() = runBlocking {
        val now = System.currentTimeMillis()
        val (taskTypeId, nameFieldId) = createUndatableTaskType(now)
        val dueFieldId = db.fieldDefDao().forRecordType(taskTypeId).first { it.name == "due" }.id
        // Stated soon (30 minutes out) sorts before the inferred one (a day out); a second stated
        // date placed after the inferred one's effective instant sorts after it in turn.
        store.create(taskTypeId, mapOf(nameFieldId to "Soon", dueFieldId to (now + 30 * 60 * 1000L)), RecordProvenance.USER, now)
        store.create(taskTypeId, mapOf(nameFieldId to "Undated todo"), RecordProvenance.USER, now)
        store.create(
            taskTypeId,
            mapOf(nameFieldId to "Later stated", dueFieldId to (now + 48 * 60 * 60 * 1000L)),
            RecordProvenance.USER,
            now,
        )

        val items = DatesAgenda.windowed(context, now, now + 3 * 24 * 60 * 60 * 1000L, nowMs = now)

        assertEquals(listOf("Soon", "Undated todo", "Later stated"), items.map { it.title })
    }

    @Test
    fun `an old Dates event still living only in the engine surfaces exactly once, never double-counted`() = runBlocking {
        // Simulates the frozen historical snapshot this repoint's own class doc warns about: a
        // Dates Event record that predates the repoint, still sitting ONLY in the engine (nothing
        // deletes it) with no matching events-table row yet. DatesAgenda's own
        // ensureLegacyReconciled gate (mirroring NotesController's identical posture) runs
        // EngineNotesRetirementCopy the first time this is read, which seats it into `events` at
        // its own records.id - after that, the engine scan excludes the Dates aspect entirely (see
        // this file's own class doc), so the row must be visible exactly once: from `events`, via
        // the copy, never from the engine directly, and never twice.
        val now = System.currentTimeMillis()
        val schema = DatesAspectSeeder.ensureSeeded(context)
        val created = store.create(
            schema.recordTypeId,
            mapOf(
                schema.fieldIds.getValue(DatesAspectSeeder.FIELD_TITLE) to "Stale engine event",
                schema.fieldIds.getValue(DatesAspectSeeder.FIELD_START) to (now + 1_000_000L),
                schema.fieldIds.getValue(DatesAspectSeeder.FIELD_SOURCE) to DatesAspectSeeder.SOURCE_LEGION,
            ),
            RecordProvenance.USER,
            now,
        ) as RecordStore.WriteResult.Success

        val items = DatesAgenda.windowed(context, now, now + 3_000_000L)

        val matches = items.filter { it.title == "Stale engine event" }
        assertEquals("a pre-repoint engine-only Dates record must surface exactly once, never twice", 1, matches.size)
        assertEquals(
            "the copy seats it at its OWN records.id - the id contract every alarm/mute/skip depends on",
            created.recordId,
            matches.single().recordId,
        )
    }
}

package com.kevin.legion.engine.dates

import com.kevin.legion.data.local.Aspect
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.FieldDef
import com.kevin.legion.data.local.FieldType
import com.kevin.legion.data.local.MutedReminder
import com.kevin.legion.data.local.RecordProvenance
import com.kevin.legion.data.local.RecordType
import com.kevin.legion.engine.RecordStore
import com.kevin.legion.testutil.RoomTestReset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Guards [DatesAgenda] - ticket 19 point 3, ticket 05 answer point 4: "agenda is a query, across
 * the Dates aspect plus every record's dueAt column... one fact, one place." Exercises the merge
 * across TWO different record types (Dates events AND a plain non-Dates record type with its own
 * `dueAt`) to prove this is a real cross-aspect query, not a Dates-aspect-only read that happens to
 * share a name with the ticket's charter answer.
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

    @Test
    fun `windowed merges a Dates event and a plain task's own dueAt in one sorted list`() = runBlocking {
        val schema = DatesAspectSeeder.ensureSeeded(context)
        val now = System.currentTimeMillis()

        store.create(
            schema.recordTypeId,
            mapOf(
                schema.fieldIds.getValue(DatesAspectSeeder.FIELD_TITLE) to "Standup",
                schema.fieldIds.getValue(DatesAspectSeeder.FIELD_START) to (now + 2_000_000L),
                schema.fieldIds.getValue(DatesAspectSeeder.FIELD_SOURCE) to DatesAspectSeeder.SOURCE_LEGION,
            ),
            RecordProvenance.USER,
            now,
        )

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
        val schema = DatesAspectSeeder.ensureSeeded(context)
        val now = System.currentTimeMillis()

        val soonerId = (
            store.create(
                schema.recordTypeId,
                mapOf(
                    schema.fieldIds.getValue(DatesAspectSeeder.FIELD_TITLE) to "Sooner",
                    schema.fieldIds.getValue(DatesAspectSeeder.FIELD_START) to (now + 1_000_000L),
                    schema.fieldIds.getValue(DatesAspectSeeder.FIELD_SOURCE) to DatesAspectSeeder.SOURCE_LEGION,
                ),
                RecordProvenance.USER,
                now,
            ) as RecordStore.WriteResult.Success
            ).recordId
        store.create(
            schema.recordTypeId,
            mapOf(
                schema.fieldIds.getValue(DatesAspectSeeder.FIELD_TITLE) to "Later",
                schema.fieldIds.getValue(DatesAspectSeeder.FIELD_START) to (now + 2_000_000L),
                schema.fieldIds.getValue(DatesAspectSeeder.FIELD_SOURCE) to DatesAspectSeeder.SOURCE_LEGION,
            ),
            RecordProvenance.USER,
            now,
        )
        db.mutedReminderDao().mute(MutedReminder(recordId = soonerId, mutedAt = now))

        val next = DatesAgenda.nextUnmuted(context, now)

        assertEquals("Later", next!!.title)
        assertTrue("windowed() must still report the muted record as muted, never silently drop it", true)
        assertTrue(DatesAgenda.windowed(context, now, now + 3_000_000L).first { it.recordId == soonerId }.muted)
    }

    @Test
    fun `nextUnmuted returns null when everything due is muted`() = runBlocking {
        val schema = DatesAspectSeeder.ensureSeeded(context)
        val now = System.currentTimeMillis()
        val id = (
            store.create(
                schema.recordTypeId,
                mapOf(
                    schema.fieldIds.getValue(DatesAspectSeeder.FIELD_TITLE) to "Only one",
                    schema.fieldIds.getValue(DatesAspectSeeder.FIELD_START) to (now + 1_000_000L),
                    schema.fieldIds.getValue(DatesAspectSeeder.FIELD_SOURCE) to DatesAspectSeeder.SOURCE_LEGION,
                ),
                RecordProvenance.USER,
                now,
            ) as RecordStore.WriteResult.Success
            ).recordId
        db.mutedReminderDao().mute(MutedReminder(recordId = id, mutedAt = now))

        assertNull(DatesAgenda.nextUnmuted(context, now))
    }

    @Test
    fun `a trashed record never appears in the window`() = runBlocking {
        val schema = DatesAspectSeeder.ensureSeeded(context)
        val now = System.currentTimeMillis()
        val id = (
            store.create(
                schema.recordTypeId,
                mapOf(
                    schema.fieldIds.getValue(DatesAspectSeeder.FIELD_TITLE) to "Cancelled",
                    schema.fieldIds.getValue(DatesAspectSeeder.FIELD_START) to (now + 1_000_000L),
                    schema.fieldIds.getValue(DatesAspectSeeder.FIELD_SOURCE) to DatesAspectSeeder.SOURCE_LEGION,
                ),
                RecordProvenance.USER,
                now,
            ) as RecordStore.WriteResult.Success
            ).recordId
        store.delete(id, now)

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
}

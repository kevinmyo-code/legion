package com.kevin.legion.service

import com.kevin.legion.backend.EventKind
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.Event
import com.kevin.legion.data.local.activeByKindInLocalWindow
import com.kevin.legion.data.local.nextAppointmentId
import com.kevin.legion.testutil.RoomTestReset
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
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
 * **The regression guard for the defect Kevin found on 2026-09-11**, in his words:
 * *"the AI doesnt see anything thats due on sunday for sch. i asked it and it didnt know."*
 *
 * Seven assignments were due that Sunday. `read_calendar` queried [EventKind.EVENT] only, every
 * assignment is [EventKind.TASK], and the model was handed an empty window - so it answered that
 * nothing was due, which is indistinguishable from the truth and therefore the worst available
 * failure. On the real A25 that day, 145 of 314 rows were tasks and **no tool in the whole toolbox
 * returned one of them.**
 *
 * **The fixture below is the real data, not an invented shape**, taken from the device and from
 * `.scratch/canvas-integration/research/planner-2026-09-01.json`. That matters because the first
 * suspicion was a timezone bug and it was WRONG: Canvas writes an 11:59pm local deadline as
 * `04:59Z the next day`, so a Sunday quiz is stored as `2026-09-14T04:59Z`, and a test that
 * "simplified" this to a midnight-aligned Sunday timestamp would pass against the broken code.
 * The off-by-one and the kind filter have to be exercised together or neither is really tested.
 *
 * These assert against [activeByKindInLocalWindow] - the same call `readCalendar` makes - rather
 * than driving the tool through a live session, which needs a socket and a key. That is the seam;
 * what it cannot cover is the tool's own JSON assembly, and that is stated rather than implied.
 */
@RunWith(RobolectricTestRunner::class)
class ReadCalendarTaskVisibilityTest {
    private val context = RuntimeEnvironment.getApplication()
    private val houston: ZoneId = ZoneId.of("America/Chicago")

    @Before
    fun clearState() {
        RoomTestReset.resetCarDatabaseSingleton()
    }

    @After
    fun drainRoomInvalidationTracker() {
        RoomTestReset.drainArchDiskIoPool()
    }

    /** A row shaped exactly like the ones on the phone: a real instant, `allDay = false`, and a
     * `startsAt` whose UTC date is the day AFTER the day it is really due. */
    private suspend fun insertDue(title: String, dueUtc: String, kind: String, done: Boolean = false): Long {
        val at = LocalDateTime.parse(dueUtc).toInstant(ZoneOffset.UTC).toEpochMilli()
        val db = CarDatabase.getDatabase(context)
        val row = Event(
            id = db.eventDao().nextAppointmentId(),
            serverId = UUID.randomUUID().toString(),
            guid = UUID.randomUUID().toString(),
            title = title,
            startsAt = at,
            endsAt = at,
            allDay = false,
            source = "legion",
            kind = kind,
            done = done,
            updatedAtMs = at,
            createdAt = at,
        )
        return db.eventDao().insert(row)
    }

    private fun dayWindow(date: LocalDate): Pair<Long, Long> {
        val start = date.atStartOfDay(houston).toInstant().toEpochMilli()
        val end = date.plusDays(1).atStartOfDay(houston).toInstant().toEpochMilli() - 1
        return start to end
    }

    private suspend fun readCalendarWouldReturn(date: LocalDate): List<String> {
        val (from, to) = dayWindow(date)
        val dao = CarDatabase.getDatabase(context).eventDao()
        // The exact pair `readCalendar` queries. If someone narrows this back to one kind, the
        // assertions below fail rather than the app going quiet.
        return (
            dao.activeByKindInLocalWindow(EventKind.EVENT, from, to, houston) +
                dao.activeByKindInLocalWindow(EventKind.TASK, from, to, houston)
            ).sortedBy { it.startsAt ?: Long.MAX_VALUE }.map { it.title }
    }

    @Test
    fun `the seven things due Sunday are returned for Sunday, not for Monday`() = runBlocking {
        // Verbatim from the device, 2026-09-11. Every one carries a Monday UTC date.
        insertDue("MATH 3391 - Chapter 2 Quiz", "2026-09-14T04:59:00", EventKind.TASK)
        insertDue("MKTG 3303 - Quiz 3: Ch 8-11", "2026-09-14T04:59:59", EventKind.TASK)
        insertDue("MATH 3391 - Module 3: Assignment (WebAssign)", "2026-09-14T04:59:59", EventKind.TASK)
        insertDue("COSC 4320 - Discussion-2", "2026-09-14T04:59:59", EventKind.TASK)
        insertDue("COSC 4320 - Module 2: Waterfall Model", "2026-09-14T04:59:59", EventKind.TASK)
        insertDue("COSC 4320 - SE Quiz 2", "2026-09-14T04:59:59", EventKind.TASK, done = true)
        insertDue("COSC 3334 - Module1: Assignment", "2026-09-14T04:59:59", EventKind.TASK)

        val sunday = readCalendarWouldReturn(LocalDate.of(2026, 9, 13))
        assertEquals("all seven are due Sunday in Houston, whatever their UTC date says", 7, sunday.size)

        // And they are NOT also reported on the day their raw UTC timestamp names. A fix that
        // widened the window instead of anchoring it would double-report every one of them.
        val monday = readCalendarWouldReturn(LocalDate.of(2026, 9, 14))
        assertTrue("nothing is due Monday - these are Sunday 23:59 local, got $monday", monday.isEmpty())
    }

    @Test
    fun `a task is invisible when only events are queried - the actual defect`() = runBlocking {
        insertDue("MATH 3391 - Chapter 2 Quiz", "2026-09-14T04:59:00", EventKind.TASK)

        val (from, to) = dayWindow(LocalDate.of(2026, 9, 13))
        val dao = CarDatabase.getDatabase(context).eventDao()

        // This is what `read_calendar` did until 2026-09-11, reproduced deliberately. It must keep
        // returning nothing - the point is that the OLD query genuinely could not see the row, so
        // the fix is the second kind and not something incidental that happened alongside it.
        val eventsOnly = dao.activeByKindInLocalWindow(EventKind.EVENT, from, to, houston)
        assertTrue("an EVENT-only query cannot see a TASK - this is the bug, pinned", eventsOnly.isEmpty())

        assertEquals(1, readCalendarWouldReturn(LocalDate.of(2026, 9, 13)).size)
    }

    @Test
    fun `events and tasks come back together, in time order`() = runBlocking {
        // A class at 09:30 Sunday and a quiz due 23:59 Sunday. Both belong to the day and the model
        // is meant to be able to tell them apart by `kind`, which is why both are returned.
        insertDue("COSC 4320 lecture", "2026-09-13T14:30:00", EventKind.EVENT)
        insertDue("MATH 3391 - Chapter 2 Quiz", "2026-09-14T04:59:00", EventKind.TASK)

        assertEquals(
            listOf("COSC 4320 lecture", "MATH 3391 - Chapter 2 Quiz"),
            readCalendarWouldReturn(LocalDate.of(2026, 9, 13)),
        )
    }

    @Test
    fun `the Friday case too, because a fix that only rescues Sunday is not a fix`() = runBlocking {
        // `2026-09-12T04:59:59Z` is Friday 23:59 in Houston - the discussion that was due the very
        // night Kevin asked. Nothing about Sunday is special; the whole class of row is shifted.
        insertDue("MATH 3391 - Module 3: Discussion", "2026-09-12T04:59:59", EventKind.TASK)

        assertEquals(1, readCalendarWouldReturn(LocalDate.of(2026, 9, 11)).size)
        assertTrue(readCalendarWouldReturn(LocalDate.of(2026, 9, 12)).isEmpty())
    }

    @Test
    fun `a done task is still returned, so the model can say it is already finished`() = runBlocking {
        // Dropping done tasks would be a second way to answer "nothing due" wrongly - and one of
        // the seven really was already submitted. Whether to mention it is the model's call; the
        // tool's job is to hand over the fact rather than decide it.
        insertDue("COSC 4320 - SE Quiz 2", "2026-09-14T04:59:59", EventKind.TASK, done = true)

        assertEquals(1, readCalendarWouldReturn(LocalDate.of(2026, 9, 13)).size)
    }
}

package com.kevin.legion.ui.agenda

import com.kevin.legion.backend.EventKind
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.Event
import com.kevin.legion.data.local.nextAppointmentId
import com.kevin.legion.notes.NotesController
import com.kevin.legion.notes.RepeatEnd
import com.kevin.legion.notes.RepeatRule
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
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * [buildDayAgenda] is the single builder that replaced three verbatim copies of the same
 * NotesController + Recurrence + `eventDao().activeByKindInWindow` merge (`ui/TodayScreen.kt`'s
 * AGENDA pane, and `ui/NotesScreen.kt`'s "today" and "month" builds - see that file's own
 * DayAgenda.kt class doc). This suite exercises the day window directly, the same way
 * [com.kevin.legion.notes.NotesControllerAppointmentTest] exercises the appointment funnel -
 * Robolectric + a real (in-memory-backed) Room database, since the builder itself does real DB IO
 * and is not a pure function over plain data the way `TodayGapResolvers.kt`'s builders are.
 */
@RunWith(RobolectricTestRunner::class)
class DayAgendaTest {
    private val context = RuntimeEnvironment.getApplication()
    private val zone: ZoneId = ZoneId.systemDefault()

    @Before
    fun clearState() {
        RoomTestReset.resetCarDatabaseSingleton()
    }

    @After
    fun drainRoomInvalidationTracker() {
        // See NotesControllerTest's identical @After for why this is load-bearing, not tidying.
        RoomTestReset.drainArchDiskIoPool()
    }

    private suspend fun insertAppointment(title: String, startsAt: Long, allDay: Boolean = false): Event {
        val db = CarDatabase.getDatabase(context)
        val row = Event(
            id = db.eventDao().nextAppointmentId(),
            serverId = UUID.randomUUID().toString(),
            guid = UUID.randomUUID().toString(),
            title = title,
            startsAt = startsAt,
            endsAt = startsAt + if (allDay) java.time.Duration.ofDays(1).toMillis() else 3_600_000,
            allDay = allDay,
            source = "legion",
            kind = EventKind.EVENT,
            done = false,
            updatedAtMs = startsAt,
            createdAt = startsAt,
        )
        val id = db.eventDao().insert(row)
        return row.copy(id = id)
    }

    @Test
    fun `a one-off item on the day shows up in that day's agenda`() = runBlocking {
        val day = LocalDate.of(2026, 9, 3)
        val startsAt = day.atTime(9, 0).atZone(zone).toInstant().toEpochMilli()

        val list = NotesController.theList(context)
        NotesController.addItemDue(context, list.id, "Dentist checkup", startsAt, allDay = false)

        val agenda = buildDayAgenda(context, day, zone)

        assertEquals(1, agenda.size)
        assertEquals("Dentist checkup", agenda.single().label)
    }

    @Test
    fun `a recurring item lands on a day it falls on`() = runBlocking {
        val anchor = LocalDate.of(2026, 9, 1)
        val fallsOn = LocalDate.of(2026, 9, 3)
        val startsAt = anchor.atTime(7, 0).atZone(zone).toInstant().toEpochMilli()

        val list = NotesController.theList(context)
        val item = NotesController.addItemDue(context, list.id, "Take out trash", startsAt, allDay = false)
        NotesController.setRepeat(context, item, RepeatRule.Daily(1), RepeatEnd.Never)

        val agenda = buildDayAgenda(context, fallsOn, zone)

        assertEquals(1, agenda.size)
        assertEquals("Take out trash", agenda.single().label)
    }

    @Test
    fun `a recurring item does not appear on a day it does not fall on`() = runBlocking {
        val anchor = LocalDate.of(2026, 9, 1)
        val notDue = LocalDate.of(2026, 9, 3)
        val startsAt = anchor.atTime(7, 0).atZone(zone).toInstant().toEpochMilli()

        val list = NotesController.theList(context)
        val item = NotesController.addItemDue(context, list.id, "Water the plants", startsAt, allDay = false)
        // Every 5 days from 9/1: due on 9/1, 9/6, 9/11... never on 9/3.
        NotesController.setRepeat(context, item, RepeatRule.Daily(5), RepeatEnd.Never)

        val agenda = buildDayAgenda(context, notDue, zone)

        assertTrue(agenda.isEmpty())
    }

    @Test
    fun `an appointment on the day is merged into that day's agenda`() = runBlocking {
        val day = LocalDate.of(2026, 9, 3)
        val startsAt = day.atTime(14, 0).atZone(zone).toInstant().toEpochMilli()

        insertAppointment("Vet visit", startsAt)

        val agenda = buildDayAgenda(context, day, zone)

        assertEquals(1, agenda.size)
        assertEquals("Vet visit", agenda.single().label)
        assertEquals(com.kevin.legion.ui.AgendaSource.GOOGLE, agenda.single().source)
    }

    /**
     * Found on-device 2026-09-01, Kevin: "the due dates seem to be advanced by 1 day some how" -
     * an all-day appointment's [Event.startsAt] is UTC midnight of its calendar date
     * (`LiveToolbox.addAppointment`'s own comment), so [buildDayAgenda]'s window query must be
     * widened and re-anchored through UTC (see [com.kevin.legion.data.local.activeByKindInLocalWindow]'s
     * own doc comment) or the row renders on the PREVIOUS local day at UTC-5 - exactly this test's
     * real figure, `MATH 3391 WebAssign homework` due Sep 6
     * (`.scratch/canvas-integration/research/planner-2026-09-01.json`).
     */
    @Test
    fun `an all-day appointment stored at UTC midnight lands on its own local day, not the day before`() = runBlocking {
        val zone = ZoneId.of("America/Chicago") // UTC-5, Kevin's own device
        val sep6Utc = LocalDate.of(2026, 9, 6).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
        insertAppointment("MATH 3391 WebAssign homework", sep6Utc, allDay = true)

        val sep6Agenda = buildDayAgenda(context, LocalDate.of(2026, 9, 6), zone)
        val sep5Agenda = buildDayAgenda(context, LocalDate.of(2026, 9, 5), zone)

        assertEquals(listOf("MATH 3391 WebAssign homework"), sep6Agenda.map { it.label })
        assertTrue("must not render one day early", sep5Agenda.isEmpty())
    }

    /** Same fixture, a zone EAST of UTC (Asia/Tokyo, UTC+9) - the naive bug pushes an all-day row
     * to the day AFTER in this direction, not before, so a fix verified only at UTC-5 is only half
     * verified. */
    @Test
    fun `an all-day appointment stored at UTC midnight lands on its own local day east of UTC too`() = runBlocking {
        val zone = ZoneId.of("Asia/Tokyo") // UTC+9
        val sep6Utc = LocalDate.of(2026, 9, 6).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
        insertAppointment("MATH 3391 WebAssign homework", sep6Utc, allDay = true)

        val sep6Agenda = buildDayAgenda(context, LocalDate.of(2026, 9, 6), zone)
        val sep7Agenda = buildDayAgenda(context, LocalDate.of(2026, 9, 7), zone)

        assertEquals(listOf("MATH 3391 WebAssign homework"), sep6Agenda.map { it.label })
        assertTrue("must not render one day late", sep7Agenda.isEmpty())
    }
}

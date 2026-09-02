package com.kevin.legion.data.local

import com.kevin.legion.backend.EventKind
import com.kevin.legion.testutil.RoomTestReset
import java.time.LocalDate
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
 * [EventDao.activeByKindInLocalWindow] against real Canvas figures (found on-device 2026-09-01,
 * Kevin: "the due dates seem to be advanced by 1 day some how" -
 * `.scratch/canvas-integration/research/planner-2026-09-01.json` says `MATH 3391 WebAssign
 * homework` is due Sep 6; the app showed Sep 5). Robolectric + a real in-memory-backed Room
 * database, same posture as [com.kevin.legion.ui.agenda.DayAgendaTest].
 *
 * **Tested in TWO zones deliberately** - America/Chicago (UTC-5, Kevin's own device) and a zone
 * EAST of UTC (Asia/Tokyo, UTC+9) - because the naive bug pushes an all-day row to the PREVIOUS
 * local day west of UTC and would push it to the NEXT local day east of UTC; a fix verified in only
 * one direction is only half verified.
 */
@RunWith(RobolectricTestRunner::class)
class EventDaoWindowTest {
    private val context = RuntimeEnvironment.getApplication()

    @Before
    fun clearState() {
        RoomTestReset.resetCarDatabaseSingleton()
    }

    @After
    fun drainRoomInvalidationTracker() {
        RoomTestReset.drainArchDiskIoPool()
    }

    private suspend fun insertEvent(title: String, startsAt: Long, allDay: Boolean): Event {
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
            updatedAtMs = startsAt,
            createdAt = startsAt,
        )
        val id = db.eventDao().insert(row)
        return row.copy(id = id)
    }

    private fun dayWindow(date: LocalDate, zone: ZoneId): Pair<Long, Long> {
        val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
        return start to end
    }

    @Test
    fun `an all-day row stored at UTC midnight Sep 6 buckets as Sep 6 at UTC-5`() = runBlocking {
        val zone = ZoneId.of("America/Chicago")
        val storedUtc = LocalDate.of(2026, 9, 6).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        insertEvent("MATH 3391 WebAssign homework", storedUtc, allDay = true)

        val (sep6Start, sep6End) = dayWindow(LocalDate.of(2026, 9, 6), zone)
        val (sep5Start, sep5End) = dayWindow(LocalDate.of(2026, 9, 5), zone)

        val onSep6 = CarDatabase.getDatabase(context).eventDao()
            .activeByKindInLocalWindow(EventKind.EVENT, sep6Start, sep6End, zone)
        val onSep5 = CarDatabase.getDatabase(context).eventDao()
            .activeByKindInLocalWindow(EventKind.EVENT, sep5Start, sep5End, zone)

        assertEquals(listOf("MATH 3391 WebAssign homework"), onSep6.map { it.title })
        assertTrue("must not render one day early", onSep5.isEmpty())
    }

    @Test
    fun `an all-day row stored at UTC midnight Sep 6 buckets as Sep 6 east of UTC too`() = runBlocking {
        val zone = ZoneId.of("Asia/Tokyo") // UTC+9
        val storedUtc = LocalDate.of(2026, 9, 6).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        insertEvent("MATH 3391 WebAssign homework", storedUtc, allDay = true)

        val (sep6Start, sep6End) = dayWindow(LocalDate.of(2026, 9, 6), zone)
        val (sep7Start, sep7End) = dayWindow(LocalDate.of(2026, 9, 7), zone)

        val onSep6 = CarDatabase.getDatabase(context).eventDao()
            .activeByKindInLocalWindow(EventKind.EVENT, sep6Start, sep6End, zone)
        val onSep7 = CarDatabase.getDatabase(context).eventDao()
            .activeByKindInLocalWindow(EventKind.EVENT, sep7Start, sep7End, zone)

        assertEquals(listOf("MATH 3391 WebAssign homework"), onSep6.map { it.title })
        assertTrue("must not render one day late east of UTC either", onSep7.isEmpty())
    }

    @Test
    fun `a timed row at 14-30 UTC still renders 09-30 local at UTC-5, unaffected`() = runBlocking {
        val zone = ZoneId.of("America/Chicago")
        // COSC 3334, the real figure from the diagnosis: allDay=0, stored utc=09-01 14:30.
        val storedUtc = LocalDate.of(2026, 9, 1).atTime(14, 30).atZone(ZoneOffset.UTC).toInstant().toEpochMilli()
        insertEvent("COSC 3334", storedUtc, allDay = false)

        val (sep1Start, sep1End) = dayWindow(LocalDate.of(2026, 9, 1), zone)
        val rows = CarDatabase.getDatabase(context).eventDao()
            .activeByKindInLocalWindow(EventKind.EVENT, sep1Start, sep1End, zone)

        assertEquals(1, rows.size)
        val localTime = java.time.Instant.ofEpochMilli(rows.single().startsAt!!).atZone(zone).toLocalTime()
        assertEquals(java.time.LocalTime.of(9, 30), localTime)
    }
}

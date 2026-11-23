package com.kevin.legion.service

import com.kevin.legion.backend.EventKind
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.Event
import com.kevin.legion.data.local.nextAppointmentId
import com.kevin.legion.service.tools.day.RelativeDay
import com.kevin.legion.testutil.RoomTestReset
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.TextStyle
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * `read_calendar` says what time it is and where each row stands to it (2026-11-23: an entry
 * starting at 15:00 was described in the morning as "wrapping up soon", because the result carried
 * starts and ends and no present to measure them against, and the agenda block that normally
 * carries the clock had been withdrawn by an earlier write). Titles are made up.
 */
@RunWith(RobolectricTestRunner::class)
class ReadCalendarNowStateTest {
    private val context = RuntimeEnvironment.getApplication()
    private val zone: ZoneId = ZoneId.systemDefault()
    private val day: LocalDate = LocalDate.of(2027, 3, 8)
    private val at = LocalTime.of(10, 25)

    @Before
    fun clearState() {
        RoomTestReset.resetCarDatabaseSingleton()
        RelativeDay.pin(Clock.fixed(day.atTime(at).atZone(zone).toInstant(), zone))
    }

    @After
    fun restore() {
        RelativeDay.pin(null)
        RoomTestReset.drainArchDiskIoPool()
    }

    private suspend fun insert(title: String, from: Int, to: Int) {
        val start = day.atTime(from, 0).atZone(zone).toInstant().toEpochMilli()
        val end = day.atTime(to, 0).atZone(zone).toInstant().toEpochMilli()
        insertRow(title, start, end, allDay = false)
    }

    private suspend fun insertAllDay(title: String, on: LocalDate) {
        val start = on.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        insertRow(title, start, start + java.time.Duration.ofDays(1).toMillis(), allDay = true)
    }

    private suspend fun insertRow(title: String, start: Long, end: Long, allDay: Boolean, kind: String = EventKind.EVENT) {
        val db = CarDatabase.getDatabase(context)
        db.eventDao().insert(
            Event(
                id = db.eventDao().nextAppointmentId(),
                serverId = UUID.randomUUID().toString(),
                guid = UUID.randomUUID().toString(),
                title = title,
                startsAt = start,
                endsAt = end,
                allDay = allDay,
                source = "legion",
                kind = kind,
                done = false,
                updatedAtMs = start,
                createdAt = start,
            ),
        )
    }

    private fun stateOf(rows: JSONArray, title: String): String {
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            if (row.getString("title") == title) return row.optString("state")
        }
        return "no row called $title in $rows"
    }

    @Test
    fun `the result says the time now and whether each entry has started`() = runBlocking {
        insert("Dentist", 15, 16)
        insert("Study block", 10, 11)
        insert("Breakfast", 8, 9)

        val args = JSONObject().put("from", day.toString()).put("to", day.toString())
        val r = LiveToolbox.dispatch(context, "read_calendar", args)!!

        val weekday = day.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.US)
        assertEquals("$weekday 2027-03-08 10:25", r.getString("now"))

        val rows = r.getJSONArray("events")
        assertEquals("not started", stateOf(rows, "Dentist"))
        assertEquals("running", stateOf(rows, "Study block"))
        assertEquals("over", stateOf(rows, "Breakfast"))
    }

    @Test
    fun `a task carries no state - it is due, not running or over`() = runBlocking {
        // An open task whose deadline has passed is OVERDUE, not finished. The due block says so;
        // a "state" of "over" on the row would read as done.
        insertRow("Lab worksheet", day.atTime(8, 0).atZone(zone).toInstant().toEpochMilli(),
            day.atTime(8, 0).atZone(zone).toInstant().toEpochMilli(), allDay = false, kind = EventKind.TASK)
        insert("Study block", 10, 11)

        val args = JSONObject().put("from", day.toString()).put("to", day.toString())
        val rows = LiveToolbox.dispatch(context, "read_calendar", args)!!.getJSONArray("events")

        assertEquals("", stateOf(rows, "Lab worksheet"))
        assertEquals("running", stateOf(rows, "Study block"))
    }

    @Test
    fun `an all-day row is judged by its date, not by its UTC-midnight instant`() = runBlocking {
        insertAllDay("Invented holiday", day)
        insertAllDay("Rubbish collection", day.plusDays(1))

        val args = JSONObject().put("from", day.toString()).put("to", day.plusDays(1).toString())
        val rows = LiveToolbox.dispatch(context, "read_calendar", args)!!.getJSONArray("events")

        assertTrue(rows.toString(), rows.length() >= 2)
        assertEquals("running", stateOf(rows, "Invented holiday"))
        assertEquals("not started", stateOf(rows, "Rubbish collection"))
    }
}

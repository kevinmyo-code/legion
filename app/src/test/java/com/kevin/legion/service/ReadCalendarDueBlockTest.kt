package com.kevin.legion.service

import com.kevin.legion.backend.EventKind
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.Event
import com.kevin.legion.data.local.nextAppointmentId
import com.kevin.legion.testutil.RoomTestReset
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.runBlocking
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
 * `read_calendar` through the real dispatch (2026-11-15): its `due` block holds calendar tasks AND
 * dated to-dos for a one-day window, a handed-in task only counted, and the open task is the first
 * row. Titles are made up.
 */
@RunWith(RobolectricTestRunner::class)
class ReadCalendarDueBlockTest {
    private val context = RuntimeEnvironment.getApplication()
    private val zone: ZoneId = ZoneId.systemDefault()
    private val day: LocalDate = LocalDate.now(zone).plusDays(2)

    @Before
    fun clearState() {
        RoomTestReset.resetCarDatabaseSingleton()
    }

    @After
    fun drain() {
        RoomTestReset.drainArchDiskIoPool()
    }

    private suspend fun insert(title: String, hour: Int, kind: String, meta: String? = null) {
        val at = day.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()
        val db = CarDatabase.getDatabase(context)
        db.eventDao().insert(
            Event(
                id = db.eventDao().nextAppointmentId(),
                serverId = UUID.randomUUID().toString(),
                guid = UUID.randomUUID().toString(),
                title = title,
                startsAt = at,
                endsAt = at,
                allDay = false,
                source = "legion",
                kind = kind,
                done = false,
                updatedAtMs = at,
                createdAt = at,
                structuredMeta = meta,
            ),
        )
    }

    @Test
    fun `the due block merges tasks and dated to-dos, and handed-in work is only counted`() = runBlocking {
        insert("Essay handed in", 9, EventKind.TASK, meta = """{"canvas_submitted":true}""")
        insert("Pick up the parcel", 18, EventKind.REMINDER)
        insert("Lab worksheet", 23, EventKind.TASK)

        val args = JSONObject().put("from", day.toString()).put("to", day.toString())
        val r = LiveToolbox.dispatch(context, "read_calendar", args)!!

        val due = r.getJSONObject("due")
        assertEquals(2, due.getInt("open"))
        assertEquals(1, due.getInt("done"))
        assertEquals(0, due.getInt("overdue"))
        val items = due.getJSONArray("open_items")
        assertEquals("Pick up the parcel", items.getJSONObject(0).getString("title"))
        assertEquals("todo", items.getJSONObject(0).getString("source"))
        assertEquals("Lab worksheet", items.getJSONObject(1).getString("title"))
        assertEquals("calendar", items.getJSONObject(1).getString("source"))
        val says = due.getString("says")
        assertTrue(says, says.startsWith("2 open and due, not done, not overdue yet:"))

        // Rows: the two tasks (reminders are not calendar rows), the open one first.
        assertEquals(2, r.getInt("count"))
        assertEquals(0, r.getInt("events_count"))
        assertEquals("Lab worksheet", r.getJSONArray("events").getJSONObject(0).getString("title"))
    }

    @Test
    fun `an empty calendar window with a dated to-do never says nothing is due`() = runBlocking {
        insert("Pick up the parcel", 18, EventKind.REMINDER)
        val args = JSONObject().put("from", day.toString()).put("to", day.toString())
        val r = LiveToolbox.dispatch(context, "read_calendar", args)!!
        assertEquals(0, r.getInt("count"))
        assertEquals(1, r.getJSONObject("due").getInt("open"))
        assertEquals("Nothing on the calendar in that window; what is due is under due.", r.getString("message"))
    }
}

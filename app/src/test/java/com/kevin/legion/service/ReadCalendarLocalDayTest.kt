@file:Suppress("MaxLineLength") // assertions read best on one line

package com.kevin.legion.service

import com.kevin.legion.backend.EventKind
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.Event
import com.kevin.legion.data.local.nextAppointmentId
import com.kevin.legion.service.tools.day.RelativeDay
import com.kevin.legion.testutil.RoomTestReset
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * `day read_calendar` on an invented Tuesday evening, 21:40 Central, already Wednesday in UTC: "today"
 * and "tonight" read the local Tuesday, a first-posted discussion due later that evening is open and
 * due, and a read of Wednesday says in words that it is tomorrow, not today. Titles are invented.
 */
@RunWith(RobolectricTestRunner::class)
class ReadCalendarLocalDayTest {
    private val context = RuntimeEnvironment.getApplication()
    private val chicago = ZoneId.of("America/Chicago")
    private val tuesday = LocalDate.of(2027, 3, 9)

    @Before
    fun setUp() {
        RoomTestReset.resetCarDatabaseSingleton()
        RelativeDay.pin(Clock.fixed(Instant.parse("2027-03-10T03:40:00Z"), chicago))
    }

    @After
    fun tearDown() {
        RelativeDay.pin(null)
        RoomTestReset.drainArchDiskIoPool()
    }

    private suspend fun insertTask(title: String, at: LocalDateTime, meta: String?) {
        val ms = at.atZone(chicago).toInstant().toEpochMilli()
        val db = CarDatabase.getDatabase(context)
        db.eventDao().insert(
            Event(
                id = db.eventDao().nextAppointmentId(),
                serverId = UUID.randomUUID().toString(),
                guid = UUID.randomUUID().toString(),
                title = title,
                startsAt = ms,
                endsAt = ms,
                allDay = false,
                source = "canvas",
                kind = EventKind.TASK,
                done = false,
                updatedAtMs = ms,
                createdAt = ms,
                structuredMeta = meta,
            ),
        )
    }

    private suspend fun seed() {
        insertTask(
            "Invented course - Week 9 discussion",
            tuesday.atTime(22, 30),
            """{"canvas_assignment_id":7,"submission_types":"discussion_topic","canvas_submitted":true,"submitted_at":"2027-03-07T09:30:00Z"}""",
        )
        insertTask("Invented course - Quiz 4", tuesday.plusDays(1).atTime(10, 0), null)
    }

    private suspend fun read(args: JSONObject) = LiveToolbox.dispatch(context, "read_calendar", args)!!

    private fun openTitles(r: JSONObject): List<String> {
        val items = r.getJSONObject("due").getJSONArray("open_items")
        return (0 until items.length()).map { items.getJSONObject(it).getString("title") }
    }

    @Test
    fun `today, tonight and no date at all read the local Tuesday, with the discussion open`() = runBlocking {
        seed()
        for (args in listOf(
            JSONObject().put("from", "today").put("to", "today"),
            JSONObject().put("from", "tonight").put("to", "tonight"),
            JSONObject(),
        )) {
            val r = read(args)
            assertEquals(r.toString(), "Tuesday 2027-03-09", r.getString("window"))
            assertEquals("today", r.getString("window_is"))
            assertEquals("Tuesday 2027-03-09", r.getJSONObject("due").getString("today"))
            assertEquals(listOf("Invented course - Week 9 discussion"), openTitles(r))
            assertEquals(0, r.getJSONObject("due").getInt("done"))
            assertFalse(r.toString(), r.toString().contains("Quiz 4"))
        }
    }

    @Test
    fun `a read of Wednesday is said to be tomorrow, not today`() = runBlocking {
        seed()
        val r = read(JSONObject().put("from", "2027-03-10").put("to", "2027-03-10"))
        assertEquals("Wednesday 2027-03-10", r.getString("window"))
        assertEquals("tomorrow, not today (today is Tuesday 2027-03-09)", r.getString("window_is"))
        assertFalse(openTitles(r).contains("Invented course - Week 9 discussion"))
    }

    @Test
    fun `on a voice turn about tonight, a model's Wednesday reads Tuesday`() = runBlocking {
        seed()
        val turn = RelativeDay.TurnDates(RelativeDay.today(), java.time.LocalTime.of(21, 40), onlyDateCall = true)
        val args = JSONObject().put("action", "read_calendar").put("from", "2027-03-10").put("to", "2027-03-10")
        val r = withContext(turn) { LiveToolbox.dispatch(context, "read_calendar", args, userText = "anything due tonight?")!! }
        assertEquals("Tuesday 2027-03-09", r.getString("window"))
        assertTrue(r.has("date_note"))
        assertEquals(listOf("Invented course - Week 9 discussion"), openTitles(r))
    }

    @Test
    fun `get_current_time reads the same clock`() = runBlocking {
        val r = LiveToolbox.dispatch(context, "get_current_time", JSONObject())!!
        val message = r.getString("message")
        assertTrue(message, message.contains("Today is 2027-03-09"))
        assertTrue(message, message.startsWith("Tuesday, March 9, 2027 at 9:40 PM"))
    }
}

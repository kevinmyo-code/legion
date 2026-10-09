package com.kevin.legion.calendar

import com.kevin.legion.backend.EventKind
import com.kevin.legion.backend.EventsAppointmentWriter
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.Event
import com.kevin.legion.data.local.activeByKindInLocalWindow
import com.kevin.legion.data.local.nextAppointmentId
import com.kevin.legion.engine.dates.DatesAgenda
import com.kevin.legion.navigation.resolve.EventsRead
import com.kevin.legion.navigation.resolve.PhoneEvents
import com.kevin.legion.navigation.resolve.PhoneSuggestions
import com.kevin.legion.navigation.resolve.SuggestionsRead
import com.kevin.legion.notes.NotesController
import com.kevin.legion.testutil.RoomTestReset
import com.kevin.legion.ui.agenda.buildAgendaInWindow
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * A suggestion is never the user's plan (Kevin, 2026-10-09). Every reader that treats events as
 * plans is run here with a suggestion sitting in its window, and must not see it: the opener,
 * sitrep and alarm scheduler (all through [DatesAgenda]), the Today agenda and month dots
 * ([buildAgendaInWindow]), Outstanding and the digests ([NotesController]), proactive raises'
 * "running now" read, Today's counts and the LOG digest's calendar line (the EVENT/TASK window
 * reads), and the navigation resolver's calendar source. Then the readers that SHOULD see one, and
 * the two hands-path actions.
 */
@RunWith(RobolectricTestRunner::class)
class EventSuggestionsReadersTest {
    private val context = RuntimeEnvironment.getApplication()
    private val zone: ZoneId = ZoneId.systemDefault()
    private val now = System.currentTimeMillis()
    private val today: LocalDate = LocalDate.now(zone)
    private val dayStart = today.atStartOfDay(zone).toInstant().toEpochMilli()
    private val dayEnd = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1

    @Before
    fun clearState() {
        RoomTestReset.resetCarDatabaseSingleton()
        EventsAppointmentWriter.backendOverride = null
    }

    @After
    fun drain() {
        RoomTestReset.drainArchDiskIoPool()
    }

    private suspend fun seed(
        title: String,
        kind: String,
        startsAt: Long = now + 60_000,
        endsAt: Long = now + 3_600_000,
        meta: String? = null,
        location: String? = null,
    ): Event {
        val db = CarDatabase.getDatabase(context)
        val row = Event(
            id = db.eventDao().nextAppointmentId(),
            serverId = null,
            guid = UUID.randomUUID().toString(),
            title = title,
            startsAt = startsAt,
            endsAt = endsAt,
            allDay = false,
            source = "legion",
            kind = kind,
            location = location,
            notes = "https://example.com/x - \$25",
            structuredMeta = meta,
            updatedAtMs = now,
            createdAt = now,
        )
        val id = db.eventDao().insert(row)
        return row.copy(id = id)
    }

    private suspend fun seedBoth() {
        seed("Dentist", EventKind.EVENT)
        // Starts sooner than the plan and is running now, so any reader that let it through would pick it.
        seed("Bach Festival", EventKind.SUGGESTION, startsAt = now - 60_000)
    }

    @Test
    fun `the opener, sitrep and alarm scheduler never see a suggestion`() = runBlocking {
        seedBoth()
        val titles = DatesAgenda.windowed(context, dayStart, dayEnd, now).map { it.title }
        assertEquals(listOf("Dentist"), titles)
        assertEquals("Dentist", DatesAgenda.nextUnmuted(context, now - 120_000)?.title)
    }

    @Test
    fun `the Today agenda and the month dots never see a suggestion`() = runBlocking {
        seedBoth()
        val labels = buildAgendaInWindow(context, dayStart, dayEnd, zone).map { it.label }
        assertTrue(labels.contains("Dentist"))
        assertFalse(labels.contains("Bach Festival"))
    }

    @Test
    fun `Outstanding and the digests never see a suggestion`() = runBlocking {
        seedBoth()
        assertTrue(NotesController.allItems(context).none { it.text == "Bach Festival" })
        assertTrue(NotesController.openAppointments(context).none { it.text == "Bach Festival" })
    }

    @Test
    fun `proactive, Today counts and the log digest's window reads never see a suggestion`() = runBlocking {
        seedBoth()
        val dao = CarDatabase.getDatabase(context).eventDao()
        assertTrue(dao.activeTimedByKindRunningAt(EventKind.EVENT, now).none { it.title == "Bach Festival" })
        for (kind in listOf(EventKind.EVENT, EventKind.TASK)) {
            val rows = dao.activeByKindInLocalWindow(kind, dayStart, dayEnd, zone)
            assertTrue(rows.none { it.title == "Bach Festival" })
        }
    }

    @Test
    fun `the navigation calendar source sees plans only, the suggestion source sees suggestions`() = runBlocking {
        seed("Dentist", EventKind.EVENT)
        seed(
            "Bach Festival", EventKind.SUGGESTION,
            meta = """{"city":"Victoria","venue":"Christ's Kitchen","address":"1 Main St",""" +
                """"url":"u","price":"${'$'}25"}""",
        )
        val events = PhoneEvents(context).upcoming(now) as EventsRead.Events
        assertEquals(listOf("Dentist"), events.events.map { it.title })
        val suggestions = PhoneSuggestions(context).read(now) as SuggestionsRead.Rows
        val row = suggestions.rows.single()
        assertEquals("Bach Festival", row.title)
        assertEquals("Victoria", row.city)
        assertEquals("1 Main St", row.address)
        assertTrue(row.structured)
    }

    @Test
    fun `add to my plans makes it an event with its time place and notes, and then it counts`() = runBlocking {
        val s = seed("Bach Festival", EventKind.SUGGESTION, location = "Christ's Kitchen, Victoria TX")
        val outcome = EventSuggestions.addToPlans(context, s)
        assertTrue(outcome is EventSuggestions.Outcome.Done)
        val stored = CarDatabase.getDatabase(context).eventDao().getById(s.id)!!
        assertEquals(EventKind.EVENT, stored.kind)
        assertEquals(s.startsAt, stored.startsAt)
        assertEquals(s.location, stored.location)
        assertEquals(s.notes, stored.notes)
        assertTrue(DatesAgenda.windowed(context, dayStart, dayEnd, now).any { it.title == "Bach Festival" })
        assertTrue(EventSuggestions.inLocalWindow(context, dayStart, dayEnd, zone).isEmpty())

        // A second tap on the stale row is refused in words, never written over.
        val again = EventSuggestions.addToPlans(context, s)
        assertTrue(again is EventSuggestions.Outcome.Refused)
    }

    @Test
    fun `not interested removes it`() = runBlocking {
        val s = seed("Bach Festival", EventKind.SUGGESTION)
        assertTrue(EventSuggestions.notInterested(context, s) is EventSuggestions.Outcome.Done)
        assertTrue(EventSuggestions.inLocalWindow(context, dayStart, dayEnd, zone).isEmpty())
    }

    @Test
    fun `read_calendar lists two cities' suggestions apart from the plans`() = runBlocking {
        val victoria = """{"city":"Victoria","venue":"Hall","price":"${'$'}25"}"""
        val a = seed("Bach Festival", EventKind.SUGGESTION, meta = victoria)
        val austin = """{"city":"Austin","venue":"Zilker Park","price":"Free"}"""
        val b = seed("ACL Fest", EventKind.SUGGESTION, meta = austin)
        val o = EventSuggestions.attachForModel(JSONObject().put("events", "plans"), listOf(a, b))
        assertEquals("plans", o.getString("events"))
        val list = o.getJSONArray("suggestions")
        assertEquals(2, list.length())
        assertEquals("Zilker Park", list.getJSONObject(1).getString("venue"))
        assertEquals("Free", list.getJSONObject(1).getString("price"))
        assertTrue(o.getString("suggestions_note").contains("NOT plans"))
    }

    @Test
    fun `more than two cities and no city asked leads with the cities and asks first`() = runBlocking {
        val rows = listOf("Victoria", "Austin", "Houston", "Austin").mapIndexed { i, c ->
            seed("Thing $i", EventKind.SUGGESTION, meta = """{"city":"$c"}""")
        }
        val o = EventSuggestions.attachForModel(JSONObject(), rows)
        assertNull(o.optJSONArray("suggestions"))
        assertEquals(2, o.getJSONObject("suggestions_by_city").getInt("Austin"))
        assertTrue(o.getString("suggestions_note").contains("ask which one"))

        val austin = EventSuggestions.attachForModel(JSONObject(), rows, city = "austin")
        assertEquals(2, austin.getJSONArray("suggestions").length())

        val dallas = EventSuggestions.attachForModel(JSONObject(), rows, city = "Dallas")
        assertTrue(dallas.getString("suggestions_note").startsWith("No suggestions in Dallas"))
        assertEquals(3, dallas.getJSONObject("suggestions_by_city").length())
    }

    @Test
    fun `no suggestions adds nothing to a calendar read`() {
        val o = EventSuggestions.attachForModel(JSONObject().put("success", true), emptyList())
        assertFalse(o.has("suggestions"))
        assertFalse(o.has("suggestions_note"))
    }

    @Test
    fun `an all-day suggestion is said as all day`() = runBlocking {
        val s = seed("Fair", EventKind.SUGGESTION, meta = """{"city":"Austin"}""").copy(allDay = true)
        assertTrue(EventSuggestions.toModelJson(s)!!.getString("when").endsWith("all day"))
    }
}

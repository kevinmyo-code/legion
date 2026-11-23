package com.kevin.legion.service.tools.day

import com.kevin.legion.backend.EventFields
import com.kevin.legion.backend.EventKind
import com.kevin.legion.backend.EventsAppointmentWriter
import com.kevin.legion.backend.EventsBackend
import com.kevin.legion.backend.EventsBackendException
import com.kevin.legion.backend.MigratedEvent
import com.kevin.legion.backend.RemoteEvent
import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.Event
import com.kevin.legion.data.local.OutboxOperation
import com.kevin.legion.notes.NotesController
import com.kevin.legion.service.LiveToolbox
import com.kevin.legion.testutil.RoomTestReset
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
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
 * `manage_item schedule` moves a calendar entry, and an add right after one corrects it (2026-11-22:
 * "put an entry tomorrow at seven", "actually, seven thirty" must leave ONE entry at half past).
 * Through the real tool, Room and the Calendar edit's funnel. Every title here is invented.
 */
@RunWith(RobolectricTestRunner::class)
class EventMoveTest {
    private val context = RuntimeEnvironment.getApplication()
    private val zone = ZoneId.systemDefault()
    private val today = LocalDate.of(2027, 3, 9)
    private val tomorrow = today.plusDays(1)

    @Before
    fun setUp() {
        RoomTestReset.resetCarDatabaseSingleton()
        RelativeDay.pin(Clock.fixed(LocalDateTime.of(today, LocalTime.of(10, 0)).atZone(zone).toInstant(), zone))
        EventRemoval.reset()
        EventMove.reset()
        clockMs = 1_000_000L
        EventRemoval.clock = { clockMs }
    }

    private var clockMs = 0L

    @After
    fun tearDown() {
        EventsAppointmentWriter.backendOverride = null
        EventMove.reset()
        EventRemoval.clock = System::currentTimeMillis
        RelativeDay.pin(null)
        RoomTestReset.drainArchDiskIoPool()
    }

    private val dao get() = CarDatabase.getDatabase(context).eventDao()

    private suspend fun seed(
        title: String,
        day: LocalDate = tomorrow,
        hour: Int = 19,
        serverId: String? = null,
        repeatKind: String? = null,
    ): Event {
        val start = day.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()
        val row = Event(
            serverId = serverId, guid = java.util.UUID.randomUUID().toString(), title = title, startsAt = start,
            endsAt = start + 2 * 3_600_000L, source = "legion", kind = EventKind.EVENT,
            updatedAtMs = 1L, createdAt = 1L, repeatKind = repeatKind,
        )
        return row.copy(id = dao.insert(row))
    }

    private suspend fun item(words: String?, vararg args: Pair<String, String>): JSONObject {
        val json = JSONObject()
        args.forEach { (k, v) -> json.put(k, v) }
        return LiveToolbox.dispatch(context, "manage_item", json, userText = words)!!
    }

    private suspend fun schedule(words: String?, time: String? = null, date: String? = null): JSONObject {
        val pairs = listOfNotNull("action" to "schedule", "item" to "team supper", time?.let { "time" to it },
            date?.let { "date" to it })
        return item(words, *pairs.toTypedArray())
    }

    private suspend fun addEntry(words: String, title: String, date: LocalDate, time: String) =
        item(words, "action" to "add", "kind" to "appointment", "item" to title, "date" to "$date", "time" to time)

    /** His next turn, as the voice session starts it. */
    private fun nextTurn() = EventRemoval.turnStarted()

    private suspend fun entries(title: String) = dao.getActiveByKind(EventKind.EVENT).filter { it.title == title }

    private fun Event.at(): LocalDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(startsAt!!), zone)

    @Test
    fun `a time alone moves the entry on its own day and keeps its length`() = runBlocking {
        val row = seed("Team supper")
        val r = schedule("move team supper to seven thirty", time = "19:30")
        assertTrue(r.toString(), r.getBoolean("success"))
        assertEquals("Moved \"Team supper\" to tomorrow at 7:30 pm.", r.getString("message"))
        val moved = dao.getById(row.id)!!
        assertEquals(LocalDateTime.of(tomorrow, LocalTime.of(19, 30)), moved.at())
        assertEquals(2 * 3_600_000L, moved.endsAt!! - moved.startsAt!!)
        assertEquals(1, entries("Team supper").size)
    }

    @Test
    fun `a day alone moves the entry and keeps its clock time`() = runBlocking {
        val row = seed("Team supper")
        val friday = today.plusDays(3)
        val r = item("move team supper to friday", "action" to "schedule", "item" to "team supper", "date" to "$friday")
        assertTrue(r.toString(), r.getBoolean("success"))
        assertEquals(LocalDateTime.of(friday, LocalTime.of(19, 0)), dao.getById(row.id)!!.at())
    }

    @Test
    fun `reschedule follow-up - the same title next turn moves it, one entry at half past`() = runBlocking {
        nextTurn()
        val added = addEntry("put team supper tomorrow at seven", "Team supper", tomorrow, "19:00")
        assertTrue(added.toString(), added.getBoolean("success"))

        nextTurn()
        val again = addEntry("actually seven thirty", "Team supper", tomorrow, "19:30")
        assertTrue(again.toString(), again.getBoolean("success"))
        assertTrue(again.getString("message"), again.getString("message").startsWith("Moved \"Team supper\""))
        val left = entries("Team supper")
        assertEquals(1, left.size)
        assertEquals(LocalDateTime.of(tomorrow, LocalTime.of(19, 30)), left.single().at())
    }

    @Test
    fun `an add with no item right after an entry moves that entry`() = runBlocking {
        nextTurn()
        item("put team supper tomorrow at seven", "action" to "add", "kind" to "appointment", "item" to "Team supper",
            "date" to "$tomorrow", "time" to "19:00")
        nextTurn()
        val r = item("actually seven thirty", "action" to "add", "time" to "19:30", "date" to "$tomorrow")
        assertTrue(r.toString(), r.getBoolean("success"))
        assertEquals(LocalDateTime.of(tomorrow, LocalTime.of(19, 30)), entries("Team supper").single().at())
    }

    @Test
    fun `an add with no item and nothing just discussed asks, and adds nothing`() = runBlocking {
        val r = item("add at seven thirty", "action" to "add", "time" to "19:30", "date" to "$tomorrow")
        assertFalse(r.getBoolean("success"))
        assertTrue(r.optBoolean("needs_answer"))
        assertEquals("Nothing was added: add what?", r.getString("message"))
    }

    @Test
    fun `another of the same, or two in one turn, are real adds`() = runBlocking {
        nextTurn()
        item("add gym tomorrow at seven", "action" to "add", "kind" to "appointment", "item" to "Gym",
            "date" to "$tomorrow", "time" to "07:00")
        item("and gym at five", "action" to "add", "kind" to "appointment", "item" to "Gym",
            "date" to "$tomorrow", "time" to "17:00")
        nextTurn()
        item("add another gym at noon", "action" to "add", "kind" to "appointment", "item" to "Gym",
            "date" to "$tomorrow", "time" to "12:00")
        assertEquals(3, entries("Gym").size)
    }

    @Test
    fun `an entry discussed two turns ago is not corrected by an add`() = runBlocking {
        nextTurn()
        item("put team supper tomorrow at seven", "action" to "add", "kind" to "appointment", "item" to "Team supper",
            "date" to "$tomorrow", "time" to "19:00")
        nextTurn()
        nextTurn()
        val r = item("add at seven thirty", "action" to "add", "time" to "19:30", "date" to "$tomorrow")
        assertFalse(r.getBoolean("success"))
        assertEquals(LocalDateTime.of(tomorrow, LocalTime.of(19, 0)), entries("Team supper").single().at())
    }

    @Test
    fun `several matches ask which one, unless one is on the day being discussed`() = runBlocking {
        seed("Team supper", day = tomorrow)
        val friday = seed("Team supper", day = today.plusDays(3))
        val which = schedule("move team supper to eight", time = "20:00")
        assertFalse(which.getBoolean("success"))
        assertTrue(which.optBoolean("needs_answer"))
        assertTrue(which.getString("message"), which.getString("message").startsWith("Nothing was moved. Which one?"))

        val about = RelativeDay.TurnDates(today, LocalTime.of(10, 0), true, aboutDay = today.plusDays(3))
        val r = withContext(about) {
            item(null, "action" to "schedule", "item" to "team supper", "time" to "20:00")
        }
        assertTrue(r.toString(), r.getBoolean("success"))
        assertEquals(LocalDateTime.of(today.plusDays(3), LocalTime.of(20, 0)), dao.getById(friday.id)!!.at())
    }

    @Test
    fun `a reminder that matches better keeps its own schedule`() = runBlocking {
        NotesController.addItemDue(context, NotesController.theList(context).id, "Team supper", null, allDay = true)
        val prep = seed("Team supper prep")
        schedule("move team supper to friday", date = "${today.plusDays(3)}")
        assertEquals(LocalDateTime.of(tomorrow, LocalTime.of(19, 0)), dao.getById(prep.id)!!.at())
    }

    @Test
    fun `a synced entry's move is pushed, and queued when the push fails`() = runBlocking {
        val backend = FakeEventsBackend()
        EventsAppointmentWriter.backendOverride = backend
        seed("Team supper", serverId = "server-1")
        val r = schedule("move team supper to seven thirty", time = "19:30")
        assertTrue(r.toString(), r.getBoolean("success"))
        assertEquals(listOf("server-1"), backend.upserts)
        assertEquals("queued before the push", listOf(true), backend.queuedAtPush)
        val queued = CarDatabase.getDatabase(context).outboxDao().getAll()
        assertEquals(listOf(OutboxOperation.UPDATE), queued.map { it.operation })
    }

    @Test
    fun `a reminder add after an entry makes a reminder and never moves the entry`() = runBlocking {
        nextTurn()
        addEntry("add team supper tomorrow at seven", "Team supper", tomorrow, "19:00")
        nextTurn()
        val r = item("remind me about team supper tomorrow at two", "action" to "add", "kind" to "reminder",
            "item" to "Team supper", "date" to "$tomorrow", "time" to "14:00")
        assertTrue(r.toString(), r.getBoolean("success"))
        assertEquals(LocalDateTime.of(tomorrow, LocalTime.of(19, 0)), entries("Team supper").single().at())
        assertTrue(NotesController.allItems(context).any { it.text == "Team supper" })
    }

    @Test
    fun `a match on a shared word only asks, and moves nothing`() = runBlocking {
        val row = seed("Team call")
        val r = item("move the call with the accountant to four", "action" to "schedule",
            "item" to "call with the accountant", "time" to "16:00")
        assertFalse(r.getBoolean("success"))
        assertTrue(r.optBoolean("needs_answer"))
        assertTrue(r.getString("message"), r.getString("message").contains("\"Team call\""))
        assertEquals(LocalDateTime.of(tomorrow, LocalTime.of(19, 0)), dao.getById(row.id)!!.at())
    }

    @Test
    fun `a repeating entry is not moved, and the result says the whole series would move`() = runBlocking {
        val row = seed("Team supper", repeatKind = "WEEKLY")
        val r = schedule("move team supper to friday", date = "${today.plusDays(3)}")
        assertFalse(r.getBoolean("success"))
        assertTrue(r.getString("message"), r.getString("message").contains("the whole series"))
        assertEquals(LocalDateTime.of(tomorrow, LocalTime.of(19, 0)), dao.getById(row.id)!!.at())
    }

    @Test
    fun `a reminder and an entry matching equally ask which one, and his words settle it`() = runBlocking {
        NotesController.addItemDue(context, NotesController.theList(context).id, "Team supper", null, allDay = true)
        val row = seed("Team supper")
        val which = schedule("move team supper to eight", time = "20:00")
        assertFalse(which.getBoolean("success"))
        assertTrue(which.getString("message"), which.getString("message").contains("the reminder \"Team supper\""))
        assertEquals(LocalDateTime.of(tomorrow, LocalTime.of(19, 0)), dao.getById(row.id)!!.at())

        val moved = schedule("the calendar one", time = "20:00")
        assertTrue(moved.toString(), moved.getBoolean("success"))
        assertEquals(LocalDateTime.of(tomorrow, LocalTime.of(20, 0)), dao.getById(row.id)!!.at())
    }

    @Test
    fun `the entry just discussed is forgotten after three minutes, turns counted or not`() = runBlocking {
        addEntry("put team supper tomorrow at seven", "Team supper", tomorrow, "19:00")
        clockMs += EventMove.RECENT_MS + 1
        val r = item("at seven thirty", "action" to "add", "time" to "19:30", "date" to "$tomorrow")
        assertFalse(r.getBoolean("success"))
        assertEquals(LocalDateTime.of(tomorrow, LocalTime.of(19, 0)), entries("Team supper").single().at())
    }

    private inner class FakeEventsBackend : EventsBackend {
        val upserts = mutableListOf<String>()
        val queuedAtPush = mutableListOf<Boolean>()
        override suspend fun fetchActive(): Result<List<RemoteEvent>> = Result.success(emptyList())
        override suspend fun fetchChangedSince(sinceMs: Long): Result<List<RemoteEvent>> = Result.success(emptyList())
        override suspend fun upsert(serverId: String?, fields: EventFields): Result<RemoteEvent> {
            upserts += serverId.orEmpty()
            queuedAtPush += CarDatabase.getDatabase(context).outboxDao().getAll()
                .any { it.operation == OutboxOperation.UPDATE }
            return Result.failure(EventsBackendException("offline"))
        }
        override suspend fun softDelete(serverId: String): Result<Boolean> = Result.success(true)
        override suspend fun skipOccurrence(serverId: String, skipDateEpochMs: Long): Result<Unit> =
            Result.failure(EventsBackendException("not used by this test"))
        override suspend fun fetchSkips(serverId: String): Result<List<Long>> = Result.success(emptyList())
        override suspend fun uploadMigratedEvent(event: MigratedEvent): Result<Boolean> = Result.success(true)
    }
}

package com.kevin.legion.service.tools.day

import com.kevin.legion.assistant.cards.ConversationCard
import com.kevin.legion.assistant.cards.ConversationCards
import com.kevin.legion.assistant.cards.LiveCards
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
import com.kevin.legion.data.local.OutboxTarget
import com.kevin.legion.notes.NotesController
import com.kevin.legion.service.LiveCardReads
import com.kevin.legion.service.LiveToolbox
import com.kevin.legion.testutil.RoomTestReset
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * `manage_item remove` for a calendar entry (2026-11-22), through the real tool, the real tables
 * and the Calendar screen's own delete funnel: asked first, removed only on his yes in a later turn.
 * Every title here is invented.
 */
@RunWith(RobolectricTestRunner::class)
class EventRemovalTest {
    private val context = RuntimeEnvironment.getApplication()
    private val zone = ZoneId.systemDefault()
    private val today = LocalDate.of(2027, 3, 9)
    private val now = LocalDateTime.of(today, java.time.LocalTime.of(10, 0))
    private var clockMs = 0L

    @Before
    fun setUp() {
        RoomTestReset.resetCarDatabaseSingleton()
        RelativeDay.pin(Clock.fixed(now.atZone(zone).toInstant(), zone))
        EventRemoval.reset()
        clockMs = 1_000_000L
        EventRemoval.clock = { clockMs }
        ConversationCards.end()
        ConversationCards.begin()
    }

    @After
    fun tearDown() {
        EventsAppointmentWriter.backendOverride = null
        EventRemoval.reset()
        EventRemoval.clock = System::currentTimeMillis
        ConversationCards.end()
        RelativeDay.pin(null)
        RoomTestReset.drainArchDiskIoPool()
    }

    private val dao get() = CarDatabase.getDatabase(context).eventDao()

    private suspend fun seed(
        title: String,
        day: LocalDate = today,
        hour: Int = 19,
        kind: String = EventKind.EVENT,
        serverId: String? = null,
        repeatKind: String? = null,
        allDay: Boolean = false,
    ): Event {
        val start = if (allDay) {
            day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        } else {
            day.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()
        }
        val row = Event(
            serverId = serverId,
            guid = java.util.UUID.randomUUID().toString(),
            title = title,
            startsAt = start,
            endsAt = start + 3_600_000L,
            allDay = allDay,
            source = "legion",
            kind = kind,
            repeatKind = repeatKind,
            updatedAtMs = 1L,
            createdAt = 1L,
        )
        return row.copy(id = dao.insert(row))
    }

    private suspend fun remove(item: String, words: String?, date: String? = null): JSONObject {
        val args = JSONObject().put("action", "remove").put("item", item)
        if (date != null) args.put("date", date)
        return LiveToolbox.dispatch(context, "manage_item", args, userText = words)!!
    }

    private suspend fun alive(row: Event): Boolean = dao.getById(row.id)?.deleted == false

    @Test
    fun `remove by title asks first and removes on his yes in the next turn`() = runBlocking {
        val row = seed("Team supper")

        val asked = remove("team supper", "remove team supper from my calendar")
        assertFalse(asked.toString(), asked.getBoolean("success"))
        assertTrue(asked.optBoolean("needs_answer"))
        assertEquals("Remove \"Team supper\", today at 7 pm?", asked.getString("message"))
        assertTrue("nothing removed on the ask", alive(row))

        val done = remove("team supper", "yes please")
        assertTrue(done.toString(), done.getBoolean("success"))
        assertEquals("Team supper", done.getString("title"))
        assertTrue(done.getString("message"), done.getString("message").startsWith("Removed \"Team supper\""))
        assertFalse(alive(row))
    }

    @Test
    fun `no removal without a yes - the asking turn again, no words, or not a yes`() = runBlocking {
        val row = seed("Team supper")
        remove("team supper", "remove team supper")

        // The asking turn calling twice is not an answer.
        assertFalse(remove("team supper", "remove team supper").getBoolean("success"))
        assertTrue(alive(row))
        // A call with no turn behind it (no words) cannot be a yes.
        assertFalse(remove("team supper", null).getBoolean("success"))
        assertTrue(alive(row))
        // A question back is not a yes.
        remove("team supper", "remove team supper")
        val r = remove("team supper", "wait, what time is it?")
        assertFalse(r.getBoolean("success"))
        assertTrue("asked again", r.optBoolean("needs_answer"))
        assertTrue(alive(row))
    }

    @Test
    fun `a no keeps the entry, and a later yes does not reach back to the question`() = runBlocking {
        val row = seed("Team supper")
        remove("team supper", "remove team supper")

        val no = remove("team supper", "no, keep it")
        assertFalse(no.getBoolean("success"))
        assertTrue(no.getString("message"), no.getString("message").contains("stays on the calendar"))
        assertTrue(alive(row))

        // The question is spent: a yes now only asks again.
        val yes = remove("team supper", "yes")
        assertFalse(yes.getBoolean("success"))
        assertTrue(alive(row))
    }

    @Test
    fun `a yes naming another entry removes nothing and asks about that one`() = runBlocking {
        val supper = seed("Team supper")
        val lunch = seed("Book club lunch", hour = 12)
        remove("team supper", "remove team supper")

        val r = remove("book club lunch", "yes and remove book club lunch")
        assertFalse(r.getBoolean("success"))
        assertTrue(r.getString("message"), r.getString("message").contains("Book club lunch"))
        assertTrue(alive(supper))
        assertTrue(alive(lunch))
    }

    @Test
    fun `a question older than its window is not answered by a yes`() = runBlocking {
        val row = seed("Team supper")
        remove("team supper", "remove team supper")
        clockMs += EventRemoval.TTL_MS + 1

        assertFalse(remove("team supper", "yes").getBoolean("success"))
        assertTrue(alive(row))
    }

    @Test
    fun `several matches ask which one, naming at most three, and remove nothing`() = runBlocking {
        val rows = (1L..4L).map { seed("Team supper", day = today.plusDays(it)) }

        val r = remove("team supper", "remove team supper")
        assertFalse(r.getBoolean("success"))
        assertTrue(r.optBoolean("needs_answer"))
        val message = r.getString("message")
        assertTrue(message, message.startsWith("Nothing was removed. Which one?"))
        assertEquals(message, 3, Regex("\"Team supper\"").findAll(message).count())
        assertTrue(message, message.contains("and 1 more"))
        // A yes to a which-one question removes nothing.
        assertFalse(remove("team supper", "yes").getBoolean("success"))
        rows.forEach { assertTrue(alive(it)) }
    }

    @Test
    fun `a day narrows several matches to one`() = runBlocking {
        seed("Team supper", day = today.plusDays(1))
        val friday = seed("Team supper", day = today.plusDays(3))

        val r = remove("team supper", "remove team supper on friday", date = today.plusDays(3).toString())
        assertTrue(r.getString("message"), r.getString("message").startsWith("Remove \"Team supper\""))
        assertTrue(r.getString("message"), r.getString("message").contains("the other \"Team supper\" entry stays"))
        assertTrue(remove("team supper", "yes").getBoolean("success"))
        assertFalse(alive(friday))
    }

    @Test
    fun `a repeating title removes only the one asked about, and says the others stay`() = runBlocking {
        val first = seed("Pottery class", day = today)
        val rest = (1L..2L).map { seed("Pottery class", day = today.plusWeeks(it)) }

        val asked = remove("pottery class", "remove pottery class today", date = today.toString())
        assertTrue(asked.getString("message"), asked.getString("message").contains("Only this one goes; the other 2"))
        assertTrue(remove("pottery class", "yes").getBoolean("success"))
        assertFalse(alive(first))
        rest.forEach { assertTrue(alive(it)) }
    }

    @Test
    fun `a row that carries its own repeat says the whole series goes`() = runBlocking {
        seed("Pottery class", repeatKind = "WEEKLY")

        val asked = remove("pottery class", "remove pottery class")
        assertTrue(asked.getString("message"), asked.getString("message").contains("the whole series goes"))
    }

    @Test
    fun `coursework is not removed, as on the Calendar screen`() = runBlocking {
        val task = seed("Invented essay draft", kind = EventKind.TASK)

        val r = remove("invented essay draft", "remove the invented essay draft")
        assertFalse(r.getBoolean("success"))
        assertTrue(r.getString("message"), r.getString("message").contains("coursework"))
        assertTrue(alive(task))
    }

    @Test
    fun `a reminder and an event both matching ask which one`() = runBlocking {
        val list = NotesController.theList(context)
        NotesController.addItemDue(context, list.id, "call the team", null, allDay = true)
        val row = seed("Team supper")

        val r = remove("team", "remove team")
        assertFalse(r.getBoolean("success"))
        assertTrue(r.getString("message"), r.getString("message").contains("the reminder \"call the team\""))
        assertTrue(alive(row))
        assertEquals(1, NotesController.allItems(context).count { it.text == "call the team" })
    }

    @Test
    fun `nothing matching says where it looked`() = runBlocking {
        val r = remove("invented gala", "remove the invented gala")
        assertFalse(r.getBoolean("success"))
        assertTrue(r.getString("message"), r.getString("message").contains("no reminder, to-do or calendar entry"))
    }

    @Test
    fun `a synced entry is tombstoned and the delete is sent to the engine`() = runBlocking {
        val backend = FakeEventsBackend()
        EventsAppointmentWriter.backendOverride = backend
        val row = seed("Team supper", serverId = "server-1")

        remove("team supper", "remove team supper")
        assertTrue(remove("team supper", "yes").getBoolean("success"))

        val stored = dao.getById(row.id)
        assertNotNull("kept as a tombstone", stored)
        assertTrue(stored!!.deleted)
        assertEquals(listOf("server-1"), backend.softDeleteCalls)
    }

    @Test
    fun `a delete the engine did not take is queued, and the tombstone stands`() = runBlocking {
        val backend = FakeEventsBackend().apply { softDeleteResult = Result.failure(EventsBackendException("offline")) }
        EventsAppointmentWriter.backendOverride = backend
        val row = seed("Team supper", serverId = "server-1")

        remove("team supper", "remove team supper")
        assertTrue(remove("team supper", "yes").getBoolean("success"))

        assertTrue(dao.getById(row.id)!!.deleted)
        val queued = CarDatabase.getDatabase(context).outboxDao().getAll()
            .single { it.targetTable == OutboxTarget.EVENTS }
        assertEquals(OutboxOperation.SOFT_DELETE, queued.operation)
        assertEquals(row.id, queued.localId)
    }

    @Test
    fun `an entry not yet synced is kept as a tombstone for the pull to send`() = runBlocking {
        EventsAppointmentWriter.backendOverride = FakeEventsBackend()
        val row = seed("Team supper", serverId = null)

        remove("team supper", "remove team supper")
        assertTrue(remove("team supper", "yes").getBoolean("success"))

        assertTrue(dao.getById(row.id)!!.deleted)
    }

    @Test
    fun `the day card on screen drops the entry in place`() = runBlocking {
        seed("Team supper")
        LiveToolbox.dispatch(context, "read_calendar", JSONObject().put("from", "$today").put("to", "$today"))
        fun titles() =
            (ConversationCards.state.value.cards.single() as ConversationCard.Timeline).items.map { it.title }
        assertEquals(listOf("Team supper"), titles())

        remove("team supper", "remove team supper")
        LiveCards.refresh { LiveCardReads.read(context, it) }
        assertEquals("unchanged on the ask", listOf("Team supper"), titles())

        remove("team supper", "yes")
        LiveCards.refresh { LiveCardReads.read(context, it) }
        assertEquals(emptyList<String>(), titles())
    }

    @Test
    fun `the read-back says the day and time in words`() {
        fun row(start: Long, allDay: Boolean) = Event(
            serverId = null, title = "x", startsAt = start, source = "legion", allDay = allDay, updatedAtMs = 1L,
        )
        fun timed(days: Long, h: Int, m: Int) = EventRemovalWords.whenPhrase(
            row(today.plusDays(days).atTime(h, m).atZone(zone).toInstant().toEpochMilli(), false), today, zone,
        )
        assertEquals("today at 7 pm", timed(0, 19, 0))
        assertEquals("tomorrow at 9:30 am", timed(1, 9, 30))
        assertEquals("tomorrow at 12 pm", timed(1, 12, 0))
        assertEquals("Fri Mar 12 at 12 am", timed(3, 0, 0))
        val allDay = today.plusDays(3).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        assertEquals("Fri Mar 12, all day", EventRemovalWords.whenPhrase(row(allDay, true), today, zone))
    }

    @Test
    fun `a plain no is a no, and a yes or a question is not`() {
        assertTrue(EventRemovalWords.isPlainNo("no"))
        assertTrue(EventRemovalWords.isPlainNo("nah, leave it"))
        assertTrue(EventRemovalWords.isPlainNo("never mind"))
        assertFalse(EventRemovalWords.isPlainNo("yes"))
        assertFalse(EventRemovalWords.isPlainNo("what time is it"))
    }

    private class FakeEventsBackend : EventsBackend {
        var softDeleteResult: Result<Boolean> = Result.success(true)
        val softDeleteCalls = mutableListOf<String>()

        override suspend fun fetchActive(): Result<List<RemoteEvent>> = Result.success(emptyList())
        override suspend fun fetchChangedSince(sinceMs: Long): Result<List<RemoteEvent>> = Result.success(emptyList())
        override suspend fun upsert(serverId: String?, fields: EventFields): Result<RemoteEvent> =
            Result.failure(EventsBackendException("not used by this test"))
        override suspend fun softDelete(serverId: String): Result<Boolean> {
            softDeleteCalls += serverId
            return softDeleteResult
        }
        override suspend fun skipOccurrence(serverId: String, skipDateEpochMs: Long): Result<Unit> =
            Result.failure(EventsBackendException("not used by this test"))
        override suspend fun fetchSkips(serverId: String): Result<List<Long>> = Result.success(emptyList())
        override suspend fun uploadMigratedEvent(event: MigratedEvent): Result<Boolean> = Result.success(true)
    }
}

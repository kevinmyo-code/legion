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
import com.kevin.legion.vehicle.ClearCodesVoiceGate
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * `manage_item remove` for a calendar entry (2026-11-22), through the real tool, the real tables
 * and the Calendar screen's own delete funnel: asked first, removed only on a plain yes in his next
 * turn. [say] starts a turn the way the voice session does; [again] is a second call in the same
 * turn. Every title here is invented.
 */
@RunWith(RobolectricTestRunner::class)
class EventRemovalTest {
    private val context = RuntimeEnvironment.getApplication()
    private val zone = ZoneId.systemDefault()
    private val today = LocalDate.of(2027, 3, 9)
    private val now = LocalDateTime.of(today, LocalTime.of(10, 0))
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
    ): Event {
        val start = day.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()
        val row = Event(
            serverId = serverId,
            guid = java.util.UUID.randomUUID().toString(),
            title = title,
            startsAt = start,
            endsAt = start + 3_600_000L,
            source = "legion",
            kind = kind,
            repeatKind = repeatKind,
            updatedAtMs = 1L,
            createdAt = 1L,
        )
        return row.copy(id = dao.insert(row))
    }

    private suspend fun reminder(text: String) {
        NotesController.addItemDue(context, NotesController.theList(context).id, text, null, allDay = true)
    }

    private fun reminders() = runBlocking { NotesController.allItems(context).map { it.text } }

    /** A new turn of his, then the model's remove call in it. */
    private suspend fun say(words: String?, item: String, date: String? = null): JSONObject {
        EventRemoval.turnStarted()
        return again(words, item, date)
    }

    /** Another remove call in the same turn. */
    private suspend fun again(words: String?, item: String, date: String? = null): JSONObject {
        val args = JSONObject().put("action", "remove").put("item", item)
        if (date != null) args.put("date", date)
        return LiveToolbox.dispatch(context, "manage_item", args, userText = words)!!
    }

    private suspend fun alive(row: Event): Boolean = dao.getById(row.id)?.deleted == false

    private fun JSONObject.ok() = getBoolean("success")
    private fun JSONObject.says() = getString("message")

    // ------------------------------------------------------------------ remove by title, confirmed

    @Test
    fun `remove by title asks first and removes on a plain yes in the next turn`() = runBlocking {
        val row = seed("Team supper")

        val asked = say("remove team supper from my calendar", "team supper")
        assertFalse(asked.toString(), asked.ok())
        assertTrue(asked.optBoolean("needs_answer"))
        assertEquals("Remove \"Team supper\", today at 7 pm?", asked.says())
        assertTrue("nothing removed on the ask", alive(row))

        val done = say("Yes.", "team supper")
        assertTrue(done.toString(), done.ok())
        assertEquals("Team supper", done.getString("title"))
        assertTrue(done.says(), done.says().startsWith("Removed \"Team supper\""))
        assertFalse(alive(row))
    }

    @Test
    fun `a yes that was itself the asking turn still lets the next yes answer`() = runBlocking {
        val row = seed("Team supper")
        // He said yes to the model's own offer; the remove it then called only asks.
        assertFalse(say("Yes.", "team supper").ok())
        assertTrue(alive(row))
        assertTrue(say("Yes.", "team supper").ok())
        assertFalse(alive(row))
    }

    // ------------------------------------------------------------------ confirmation required

    @Test
    fun `no removal in the asking turn, however its words grow`() = runBlocking {
        val row = seed("Team supper")
        say("remove team supper", "team supper")
        assertFalse(again("remove team supper", "team supper").ok())
        assertFalse(again("remove team supper yes remove it", "team supper").ok())
        assertFalse(again("yes", "team supper").ok())
        assertTrue(alive(row))
    }

    @Test
    fun `no removal without words, or on a question back`() = runBlocking {
        val row = seed("Team supper")
        say("remove team supper", "team supper")
        assertFalse(say(null, "team supper").ok())
        assertTrue(alive(row))

        say("remove team supper", "team supper")
        val r = say("wait, what time is it?", "team supper")
        assertFalse(r.ok())
        assertTrue("asked again", r.optBoolean("needs_answer"))
        assertTrue(alive(row))
    }

    @Test
    fun `a no keeps the entry, and a later yes does not reach back to the question`() = runBlocking {
        val row = seed("Team supper")
        say("remove team supper", "team supper")

        val no = say("no, keep it", "team supper")
        assertFalse(no.ok())
        assertTrue(no.says(), no.says().contains("stays on the calendar"))
        assertTrue(alive(row))

        assertFalse(say("yes", "team supper").ok())
        assertTrue(alive(row))
    }

    @Test
    fun `a qualified yes is a new request - the asked row stays`() = runBlocking {
        val tuesday = seed("Team supper")
        val friday = seed("Team supper", day = today.plusDays(3))
        say("remove team supper today", "team supper", date = today.toString())

        val r = say("yes, the Friday one", "team supper", date = today.plusDays(3).toString())
        assertFalse(r.ok())
        assertTrue(r.says(), r.says().startsWith("Remove \"Team supper\", Fri Mar 12"))
        assertTrue(alive(tuesday))
        assertTrue(alive(friday))
    }

    @Test
    fun `yeah, the other one removes nothing`() = runBlocking {
        val tuesday = seed("Team supper")
        val friday = seed("Team supper", day = today.plusDays(3))
        say("remove team supper today", "team supper", date = today.toString())

        assertFalse(say("yeah, the other one", "team supper").ok())
        assertTrue(alive(tuesday))
        assertTrue(alive(friday))
    }

    @Test
    fun `a bare yes on a call for another day does not remove the asked row`() = runBlocking {
        val tuesday = seed("Team supper")
        val friday = seed("Team supper", day = today.plusDays(3))
        say("remove team supper today", "team supper", date = today.toString())

        assertFalse(say("yes", "team supper", date = today.plusDays(3).toString()).ok())
        assertTrue(alive(tuesday))
        assertTrue(alive(friday))
    }

    @Test
    fun `a yes on a call naming another entry, or sharing one word, removes nothing`() = runBlocking {
        val supper = seed("Team supper")
        val lunch = seed("Book club lunch", hour = 12)
        say("remove team supper", "team supper")
        val r = say("yes", "book club lunch")
        assertFalse(r.ok())
        assertTrue(r.says(), r.says().contains("Book club lunch"))

        say("remove team supper", "team supper")
        assertFalse(say("yes", "team lunch").ok())
        assertTrue(alive(supper))
        assertTrue(alive(lunch))
    }

    @Test
    fun `the question is spent by a second turn, another tool, the end, or its age`() = runBlocking {
        val row = seed("Team supper")

        say("remove team supper", "team supper")
        EventRemoval.turnStarted() // a turn that did not answer
        assertFalse(say("yes", "team supper").ok())

        say("remove team supper", "team supper")
        EventRemoval.turnStarted()
        LiveToolbox.dispatch(context, "read_calendar", JSONObject().put("from", "$today").put("to", "$today"))
        assertFalse(again("yes", "team supper").ok())

        say("remove team supper", "team supper")
        EventRemoval.reset() // the conversation ended
        assertFalse(say("yes", "team supper").ok())

        say("remove team supper", "team supper")
        clockMs += EventRemoval.TTL_MS + 1
        assertFalse(say("yes", "team supper").ok())

        assertTrue(alive(row))
    }

    @Test
    fun `an entry changed since the question is asked about again`() = runBlocking {
        val row = seed("Team supper")
        say("remove team supper", "team supper")
        dao.update(row.copy(startsAt = row.startsAt!! + 3_600_000L, updatedAtMs = 2L))

        val r = say("yes", "team supper")
        assertFalse(r.ok())
        assertTrue(r.says(), r.says().startsWith("It changed since I asked. Remove \"Team supper\", today at 8 pm?"))
        assertTrue(alive(row))
        assertTrue(say("yes", "team supper").ok())
        assertFalse(alive(row))
    }

    @Test
    fun `a yes with too little voice under it removes nothing`() = runBlocking {
        val row = seed("Team supper")
        say("remove team supper", "team supper")
        val quiet = withContext(ClearCodesVoiceGate.HeardVoice(ClearCodesVoiceGate.YES_MIN_VOICED_MS - 1)) {
            say("yes", "team supper")
        }
        assertFalse(quiet.ok())
        assertTrue(alive(row))

        say("remove team supper", "team supper")
        val heard = withContext(ClearCodesVoiceGate.HeardVoice(ClearCodesVoiceGate.YES_MIN_VOICED_MS)) {
            say("yes", "team supper")
        }
        assertTrue(heard.ok())
        assertFalse(alive(row))
    }

    // ------------------------------------------------------------------ ambiguity and series

    @Test
    fun `several matches ask which one, naming at most three, and remove nothing`() = runBlocking {
        val rows = (1L..4L).map { seed("Team supper", day = today.plusDays(it)) }

        val r = say("remove team supper", "team supper")
        assertFalse(r.ok())
        assertTrue(r.optBoolean("needs_answer"))
        assertTrue(r.says(), r.says().startsWith("Nothing was removed. Which one?"))
        assertEquals(r.says(), 3, Regex("\"Team supper\"").findAll(r.says()).count())
        assertTrue(r.says(), r.says().contains("and 1 more"))
        assertFalse(say("yes", "team supper").ok())
        rows.forEach { assertTrue(alive(it)) }
    }

    @Test
    fun `a day narrows several matches to one, and the other stays`() = runBlocking {
        val wednesday = seed("Team supper", day = today.plusDays(1))
        val friday = seed("Team supper", day = today.plusDays(3))

        val r = say("remove team supper on friday", "team supper", date = today.plusDays(3).toString())
        assertTrue(r.says(), r.says().contains("the other \"Team supper\" entry stays"))
        assertTrue(say("yes", "team supper").ok())
        assertFalse(alive(friday))
        assertTrue(alive(wednesday))
    }

    @Test
    fun `a repeating title removes only the one asked about`() = runBlocking {
        val first = seed("Pottery class")
        val rest = (1L..2L).map { seed("Pottery class", day = today.plusWeeks(it)) }

        val asked = say("remove pottery class today", "pottery class", date = today.toString())
        assertTrue(asked.says(), asked.says().contains("Only this one goes; the other 2"))
        assertTrue(say("yes", "pottery class").ok())
        assertFalse(alive(first))
        rest.forEach { assertTrue(alive(it)) }
    }

    @Test
    fun `a row that carries its own repeat says the whole series goes`() = runBlocking {
        seed("Pottery class", repeatKind = "WEEKLY")
        val asked = say("remove pottery class", "pottery class")
        assertTrue(asked.says(), asked.says().contains("the whole series goes"))
    }

    @Test
    fun `coursework is not removed, as on the Calendar screen`() = runBlocking {
        val task = seed("Invented essay draft", kind = EventKind.TASK)
        val r = say("remove the invented essay draft", "invented essay draft")
        assertFalse(r.ok())
        assertTrue(r.says(), r.says().contains("coursework"))
        assertTrue(alive(task))
    }

    // ------------------------------------------------------------------ reminders keep their remove

    @Test
    fun `an exact reminder is removed as before, whatever entry shares a word`() = runBlocking {
        reminder("Call the landlord")
        val game = seed("The big game")
        val r = say("remove call the landlord", "call the landlord")
        assertTrue(r.toString(), r.ok())
        assertFalse(reminders().contains("Call the landlord"))
        assertTrue(alive(game))
    }

    @Test
    fun `an exact reminder beats an entry that only contains the words`() = runBlocking {
        reminder("Team supper")
        val prep = seed("Team supper prep")
        assertTrue(say("remove team supper", "team supper").ok())
        assertFalse(reminders().contains("Team supper"))
        assertTrue(alive(prep))
    }

    @Test
    fun `an exact entry beats a reminder that only contains the words`() = runBlocking {
        reminder("Buy a gift for team supper")
        seed("Team supper")
        val r = say("remove team supper", "team supper")
        assertTrue(r.says(), r.says().startsWith("Remove \"Team supper\""))
        assertTrue(reminders().contains("Buy a gift for team supper"))
    }

    @Test
    fun `equal matches ask which one, and his words settle it without asking again`() = runBlocking {
        reminder("Team supper")
        val row = seed("Team supper")

        val which = say("remove team supper", "team supper")
        assertFalse(which.ok())
        assertTrue(which.says(), which.says().contains("the reminder \"Team supper\""))
        assertTrue(which.says(), which.says().contains("\"the calendar one\""))

        val entry = say("the calendar one", "team supper")
        assertTrue(entry.says(), entry.says().startsWith("Remove \"Team supper\""))
        assertTrue(say("yes", "team supper").ok())
        assertFalse(alive(row))
        assertTrue(reminders().contains("Team supper"))

        seed("Team supper")
        assertTrue(say("the reminder", "team supper").ok())
        assertFalse(reminders().contains("Team supper"))
    }

    @Test
    fun `a date that does not read leaves the reminder path as it was`() = runBlocking {
        reminder("Call the landlord")
        val r = say("remove call the landlord", "call the landlord", date = "next blursday")
        assertTrue(r.toString(), r.ok())
        assertFalse(reminders().contains("Call the landlord"))
    }

    @Test
    fun `nothing matching says where it looked`() = runBlocking {
        val r = say("remove the invented gala", "invented gala")
        assertFalse(r.ok())
        assertTrue(r.says(), r.says().contains("no reminder, to-do or calendar entry"))
    }

    // ------------------------------------------------------------------ tombstone sync

    @Test
    fun `a synced entry is tombstoned, the delete sent, and nothing left queued`() = runBlocking {
        val backend = FakeEventsBackend()
        EventsAppointmentWriter.backendOverride = backend
        val row = seed("Team supper", serverId = "server-1")

        say("remove team supper", "team supper")
        assertTrue(say("yes", "team supper").ok())

        val stored = dao.getById(row.id)
        assertNotNull("kept as a tombstone", stored)
        assertTrue(stored!!.deleted)
        assertEquals(listOf("server-1"), backend.softDeleteCalls)
        assertTrue(outbox().isEmpty())
        assertTrue("queued before the push", backend.queuedAtPush.single())
    }

    @Test
    fun `a delete the engine did not take stays queued, and the tombstone stands`() = runBlocking {
        val backend = FakeEventsBackend().apply { softDeleteResult = Result.failure(EventsBackendException("offline")) }
        EventsAppointmentWriter.backendOverride = backend
        val row = seed("Team supper", serverId = "server-1")

        say("remove team supper", "team supper")
        assertTrue(say("yes", "team supper").ok())

        assertTrue(dao.getById(row.id)!!.deleted)
        val queued = outbox().single()
        assertEquals(OutboxOperation.SOFT_DELETE, queued.operation)
        assertEquals(row.id, queued.localId)
        assertEquals("offline", queued.lastError)
    }

    @Test
    fun `an entry not yet synced is kept as a tombstone and nothing is sent`() = runBlocking {
        val backend = FakeEventsBackend()
        EventsAppointmentWriter.backendOverride = backend
        val row = seed("Team supper", serverId = null)

        say("remove team supper", "team supper")
        assertTrue(say("yes", "team supper").ok())

        assertTrue(dao.getById(row.id)!!.deleted)
        assertTrue(backend.softDeleteCalls.isEmpty())
        assertTrue(outbox().none { it.operation == OutboxOperation.SOFT_DELETE })
    }

    private suspend fun outbox() =
        CarDatabase.getDatabase(context).outboxDao().getAll().filter { it.targetTable == OutboxTarget.EVENTS }

    // ------------------------------------------------------------------ live card

    @Test
    fun `the day card on screen drops the entry in place`() = runBlocking {
        seed("Team supper")
        LiveToolbox.dispatch(context, "read_calendar", JSONObject().put("from", "$today").put("to", "$today"))
        fun titles() =
            (ConversationCards.state.value.cards.single() as ConversationCard.Timeline).items.map { it.title }
        assertEquals(listOf("Team supper"), titles())

        say("remove team supper", "team supper")
        LiveCards.refresh { LiveCardReads.read(context, it) }
        assertEquals("unchanged on the ask", listOf("Team supper"), titles())

        say("yes", "team supper")
        LiveCards.refresh { LiveCardReads.read(context, it) }
        assertEquals(emptyList<String>(), titles())
    }

    // ------------------------------------------------------------------ words

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
    fun `only a yes and nothing more is a bare yes`() {
        listOf("Yes.", "yes please", "yeah go ahead", "okay, remove it", "sure", "yes Alfred", "do it").forEach {
            assertTrue(it, EventRemovalWords.isBareYes(it, "Alfred"))
        }
        listOf(
            "yeah, the other one", "yes, the Friday one", "sure, the one on Thursday", "yes?", "no",
            "remove team supper", "yes and remove book club lunch", "",
        ).forEach { assertFalse(it, EventRemovalWords.isBareYes(it, "Alfred")) }
    }

    @Test
    fun `a plain no, and which pool his words name`() {
        assertTrue(EventRemovalWords.isPlainNo("nah, leave it"))
        assertTrue(EventRemovalWords.isPlainNo("never mind"))
        assertFalse(EventRemovalWords.isPlainNo("yes"))
        assertEquals(EventRemovalWords.Kind.REMINDER, EventRemovalWords.kindNamed("the reminder"))
        assertEquals(EventRemovalWords.Kind.REMINDER, EventRemovalWords.kindNamed("the one on my to-do list"))
        assertEquals(EventRemovalWords.Kind.ENTRY, EventRemovalWords.kindNamed("the calendar one"))
        assertEquals(null, EventRemovalWords.kindNamed("remove team supper"))
    }

    private inner class FakeEventsBackend : EventsBackend {
        var softDeleteResult: Result<Boolean> = Result.success(true)
        val softDeleteCalls = mutableListOf<String>()

        /** Per push: was its delete already queued when the push began? */
        val queuedAtPush = mutableListOf<Boolean>()

        override suspend fun fetchActive(): Result<List<RemoteEvent>> = Result.success(emptyList())
        override suspend fun fetchChangedSince(sinceMs: Long): Result<List<RemoteEvent>> = Result.success(emptyList())
        override suspend fun upsert(serverId: String?, fields: EventFields): Result<RemoteEvent> =
            Result.failure(EventsBackendException("not used by this test"))
        override suspend fun softDelete(serverId: String): Result<Boolean> {
            softDeleteCalls += serverId
            queuedAtPush += outbox().any { it.operation == OutboxOperation.SOFT_DELETE }
            return softDeleteResult
        }
        override suspend fun skipOccurrence(serverId: String, skipDateEpochMs: Long): Result<Unit> =
            Result.failure(EventsBackendException("not used by this test"))
        override suspend fun fetchSkips(serverId: String): Result<List<Long>> = Result.success(emptyList())
        override suspend fun uploadMigratedEvent(event: MigratedEvent): Result<Boolean> = Result.success(true)
    }
}

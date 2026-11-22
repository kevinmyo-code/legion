package com.kevin.legion.service

import com.kevin.legion.assistant.cards.ConversationCard
import com.kevin.legion.assistant.cards.ConversationCards
import com.kevin.legion.assistant.cards.LiveCards
import com.kevin.legion.checklists.ChecklistController
import com.kevin.legion.service.tools.day.RelativeDay
import com.kevin.legion.testutil.RoomTestReset
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneId
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
 * 2026-11-22: with a card up, a write by the assistant did not show until the card was asked for
 * again. Through the real tools and the real tables: the card a read put up, re-read the way
 * [LiveCardReads.follow] does on a table change, shows the write in place. Names are invented.
 */
@RunWith(RobolectricTestRunner::class)
class LiveCardsWriteThroughTest {
    private val context = RuntimeEnvironment.getApplication()
    private val zone = ZoneId.systemDefault()
    private val now = LocalDateTime.of(2027, 3, 9, 10, 0)

    @Before
    fun setUp() {
        RoomTestReset.resetCarDatabaseSingleton()
        RelativeDay.pin(Clock.fixed(now.atZone(zone).toInstant(), zone))
        ConversationCards.end()
        ConversationCards.begin()
    }

    @After
    fun tearDown() {
        ConversationCards.end()
        RelativeDay.pin(null)
        RoomTestReset.drainArchDiskIoPool()
    }

    private suspend fun tool(name: String, args: JSONObject) = LiveToolbox.dispatch(context, name, args)!!

    private suspend fun refresh() = LiveCards.refresh { LiveCardReads.read(context, it) }

    private fun titles(card: ConversationCard) = (card as ConversationCard.Timeline).items.map { it.title }

    @Test
    fun `an appointment added to the day on screen shows on that card in place`() = runBlocking {
        tool("read_calendar", JSONObject().put("from", "2027-03-10").put("to", "2027-03-10"))
        val serial = ConversationCards.state.value.serial
        assertEquals(emptyList<String>(), titles(ConversationCards.state.value.cards.single()))

        val r = tool(
            "manage_item",
            JSONObject().put("action", "add").put("kind", "appointment").put("item", "Dinner with Lee")
                .put("date", "2027-03-10").put("time", "19:00"),
        )
        assertTrue(r.toString(), r.getBoolean("success"))
        refresh()

        val state = ConversationCards.state.value
        assertEquals("no second card for a day already on screen", 1, state.cards.size)
        assertEquals(listOf("Dinner with Lee"), titles(state.cards.single()))
        assertEquals(serial, state.serial)
    }

    @Test
    fun `an appointment on a day no card shows puts that day up`() = runBlocking {
        tool("read_calendar", JSONObject().put("from", "2027-03-10").put("to", "2027-03-10"))
        tool(
            "manage_item",
            JSONObject().put("action", "add").put("kind", "appointment").put("item", "Dinner with Lee")
                .put("date", "2027-03-09").put("time", "19:00"),
        )
        val cards = ConversationCards.state.value.cards
        assertEquals(2, cards.size)
        assertEquals(emptyList<String>(), titles(cards[0]))
        assertEquals(listOf("Dinner with Lee"), titles(cards[1]))
        assertEquals("2027-03-09", (cards[1] as ConversationCard.Timeline).date)
    }

    @Test
    fun `an item added to the list on screen shows on that card in place`() = runBlocking {
        ChecklistController.createChecklist(context, "Camping", recursDaily = false)
        tool("manage_checklist", JSONObject().put("action", "read").put("list", "Camping"))
        tool("manage_checklist", JSONObject().put("action", "add").put("list", "Camping").put("text", "tent pegs"))
        refresh()

        val card = ConversationCards.state.value.cards.single() as ConversationCard.Checklist
        assertEquals(listOf("tent pegs"), card.items.map { it.text })
    }

    @Test
    fun `a reminder added while the to-dos are up shows on that card`() = runBlocking {
        tool("read_list", JSONObject())
        tool(
            "manage_item",
            JSONObject().put("action", "add").put("kind", "reminder").put("item", "renew the permit")
                .put("date", "2027-03-12"),
        )
        refresh()

        val card = ConversationCards.state.value.cards.single() as ConversationCard.Checklist
        assertTrue(card.items.map { it.text }.toString(), card.items.any { it.text == "renew the permit" })
    }
}

package com.kevin.legion.assistant.cards

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The holder's lifecycle: open with a conversation, newest wins, dismiss, cleared at the end. */
class ConversationCardsTest {

    private fun list(name: String) = PhoneCards.checklist(name, listOf(ChecklistLine("milk", false)))

    /** A car card that read only the parts given a value. */
    private fun car(
        recalls: List<Recall>? = null,
        codes: List<TroubleCode>? = null,
        due: NextServiceDue? = null,
    ) = ConversationCard.Car(
        title = "Jeep", name = "Jeep", mileage = 1000, mileageAsOf = null,
        nextService = due, recalls = recalls.orEmpty(), codes = codes.orEmpty(),
        parts = buildMap {
            if (due != null) put(CarPart.SERVICE, "service note")
            if (recalls != null) put(CarPart.RECALLS, "recalls note")
            if (codes != null) put(CarPart.CODES, "codes note")
        },
    )

    @Before
    fun setUp() = ConversationCards.end()

    @After
    fun tearDown() = ConversationCards.end()

    @Test
    fun `a card published with no conversation open is dropped`() {
        ConversationCards.publish(list("Groceries"))
        assertNull(ConversationCards.state.value.latest)
        assertFalse(ConversationCards.state.value.open)
    }

    @Test
    fun `during a conversation the newest card is the one shown`() {
        ConversationCards.begin()
        ConversationCards.publish(list("Groceries"))
        ConversationCards.publish(list("Packing"))
        assertEquals("Packing", (ConversationCards.state.value.latest as ConversationCard.Checklist).listName)
        assertEquals(2, ConversationCards.state.value.cards.size)
    }

    @Test
    fun `a typed question gets the same cards as voice`() {
        ConversationCards.beginTyped()
        ConversationCards.publish(list("Groceries"))
        assertEquals("Groceries", (ConversationCards.state.value.latest as ConversationCard.Checklist).listName)
    }

    @Test
    fun `a voice conversation ending keeps a typed ask's cards, the Home button clears them`() {
        ConversationCards.beginTyped()
        ConversationCards.publish(list("Groceries"))
        ConversationCards.endVoice()
        assertEquals(1, ConversationCards.state.value.cards.size)
        ConversationCards.end()
        assertNull(ConversationCards.state.value.latest)
        assertFalse(ConversationCards.state.value.open)
    }

    @Test
    fun `a merged car card keeps a source line only when both reads share it`() {
        ConversationCards.begin()
        ConversationCards.publish(car(due = NextServiceDue("Oil change")))
        ConversationCards.publish(car(codes = emptyList()).copy(provenance = CardProvenance("OBD port", readAtMs = 1L)))
        assertNull("service data is not from the OBD port", ConversationCards.state.value.latest?.provenance)
    }

    @Test
    fun `a voice conversation adopts a typed ask's cards, and its end clears them`() {
        ConversationCards.beginTyped()
        ConversationCards.publish(list("Groceries"))
        ConversationCards.begin()
        assertEquals(1, ConversationCards.state.value.cards.size)
        ConversationCards.endVoice()
        assertFalse(ConversationCards.state.value.open)
    }

    @Test
    fun `a voice conversation ending clears its own cards`() {
        ConversationCards.begin()
        ConversationCards.publish(list("Groceries"))
        ConversationCards.endVoice()
        assertFalse(ConversationCards.state.value.open)
        assertEquals(0, ConversationCards.state.value.cards.size)
    }

    @Test
    fun `begin while open keeps the conversation's cards`() {
        ConversationCards.begin()
        ConversationCards.publish(list("Groceries"))
        ConversationCards.begin()
        assertEquals(1, ConversationCards.state.value.cards.size)
    }

    @Test
    fun `dismiss hides the card until the next one arrives`() {
        ConversationCards.begin()
        ConversationCards.publish(list("Groceries"))
        ConversationCards.dismiss()
        assertNull(ConversationCards.state.value.latest)
        ConversationCards.publish(list("Packing"))
        assertEquals("Packing", (ConversationCards.state.value.latest as ConversationCard.Checklist).listName)
    }

    @Test
    fun `the end of the conversation clears every card and closes the holder`() {
        ConversationCards.begin()
        ConversationCards.publish(list("Groceries"))
        ConversationCards.end()
        assertTrue(ConversationCards.state.value.cards.isEmpty())
        assertFalse(ConversationCards.state.value.open)
        ConversationCards.publish(list("Packing"))
        assertNull(ConversationCards.state.value.latest)
    }

    @Test
    fun `at most eight cards are kept`() {
        ConversationCards.begin()
        repeat(10) { ConversationCards.publish(list("L$it")) }
        assertEquals(8, ConversationCards.state.value.cards.size)
        assertEquals("L2", (ConversationCards.state.value.cards.first() as ConversationCard.Checklist).listName)
    }

    @Test
    fun `car cards for the same car merge, keeping what each tool looked up`() {
        ConversationCards.begin()
        val due = NextServiceDue("Oil change", dueMiles = 3000)
        ConversationCards.publish(car(due = due))
        ConversationCards.publish(car(recalls = emptyList()))
        ConversationCards.publish(car(codes = listOf(TroubleCode("P0420", "Catalyst efficiency"))))
        val merged = ConversationCards.state.value.latest as ConversationCard.Car
        assertEquals(1, ConversationCards.state.value.cards.size)
        assertEquals(due, merged.nextService)
        assertEquals(emptyList<Recall>(), merged.recalls)
        assertEquals("P0420", merged.codes.single().code)
        assertEquals(setOf(CarPart.SERVICE, CarPart.RECALLS, CarPart.CODES), merged.parts.keys)
        assertEquals("service note codes note recalls note", merged.note)
    }

    @Test
    fun `a later call that did not read a part keeps the earlier part, a later read replaces it`() {
        ConversationCards.begin()
        ConversationCards.publish(car(recalls = listOf(Recall("Fuel hose"))))
        ConversationCards.publish(car(codes = emptyList()))
        ConversationCards.publish(car(recalls = emptyList()))
        val merged = ConversationCards.state.value.latest as ConversationCard.Car
        assertEquals(emptyList<Recall>(), merged.recalls)
        assertEquals(setOf(CarPart.RECALLS, CarPart.CODES), merged.parts.keys)
        assertFalse(merged.read(CarPart.SERVICE))
    }

    @Test
    fun `a car card for a different car does not merge`() {
        ConversationCards.begin()
        ConversationCards.publish(car(recalls = emptyList()))
        ConversationCards.publish(car().copy(name = "BMW", title = "BMW"))
        assertEquals(2, ConversationCards.state.value.cards.size)
        assertFalse((ConversationCards.state.value.latest as ConversationCard.Car).read(CarPart.RECALLS))
    }
}

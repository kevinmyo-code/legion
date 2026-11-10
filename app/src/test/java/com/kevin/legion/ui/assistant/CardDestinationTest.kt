package com.kevin.legion.ui.assistant

import com.kevin.legion.assistant.cards.BarPoint
import com.kevin.legion.assistant.cards.ChecklistLine
import com.kevin.legion.assistant.cards.ConversationCard
import com.kevin.legion.assistant.cards.PhoneCards
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Which full screen sits behind each card's Open link. */
class CardDestinationTest {

    @Test
    fun `money bars open Money, count bars open nothing`() {
        val spend = ConversationCard.Bars("Spend", "usd", listOf(BarPoint("a", 1L)))
        val counts = ConversationCard.Bars("Visits", "", listOf(BarPoint("a", 1L)))
        assertEquals(CardDestination.MONEY, cardDestination(spend))
        assertNull(cardDestination(counts))
    }

    @Test
    fun `a day or a week opens Today, a car opens Car`() {
        val day = ConversationCard.Timeline("Calendar", "2026-11-04", "UTC-06:00", emptyList())
        val car = ConversationCard.Car("Test car", "Test car", null, null, null, emptyList(), emptyList(), emptyMap())
        assertEquals(CardDestination.TODAY, cardDestination(day))
        assertEquals(CardDestination.CAR, cardDestination(car))
    }

    @Test
    fun `the to-do list opens Today, any other list opens Lists`() {
        val todo = ConversationCard.Checklist("To-dos", PhoneCards.TODO_LIST_NAME, listOf(ChecklistLine("x", false)))
        val other = ConversationCard.Checklist("Groceries", "Groceries", listOf(ChecklistLine("x", false)))
        assertEquals(CardDestination.TODAY, cardDestination(todo))
        assertEquals(CardDestination.LISTS, cardDestination(other))
    }

    @Test
    fun `a run of steps has no screen behind it`() {
        assertNull(cardDestination(ConversationCard.Steps("Ran", "x", emptyList(), null)))
    }
}

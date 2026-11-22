@file:Suppress("MaxLineLength") // fixtures and phrase lists read best on one line

package com.kevin.legion.assistant.cards

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 2026-11-22: an open card did not change when the assistant wrote to the data it showed. */
class LiveCardsTest {

    private val groceries = CardSource.Checklist(7L)

    private fun list(vararg lines: String, source: CardSource? = groceries) =
        PhoneCards.checklist("Groceries", lines.map { ChecklistLine(it, false) }).copy(source = source)

    @Before
    fun setUp() = ConversationCards.end()

    @After
    fun tearDown() = ConversationCards.end()

    @Test
    fun `replace swaps the card in place and is not a new card`() {
        ConversationCards.begin()
        val old = list("milk")
        ConversationCards.publish(old)
        ConversationCards.dismiss()
        val before = ConversationCards.state.value

        ConversationCards.replace(old, list("milk", "eggs"))

        val after = ConversationCards.state.value
        assertEquals(listOf("milk", "eggs"), (after.cards.single() as ConversationCard.Checklist).items.map { it.text })
        assertEquals(before.serial, after.serial)
        assertTrue("a hidden stack stays hidden", after.dismissed)
    }

    @Test
    fun `replace leaves a closed card closed`() {
        ConversationCards.begin()
        val old = list("milk")
        ConversationCards.publish(old)
        ConversationCards.close(old)
        ConversationCards.replace(old, list("milk", "eggs"))
        assertTrue(ConversationCards.state.value.cards.isEmpty())
    }

    @Test
    fun `refresh re-reads sourced cards only, and a failed read keeps the card`() = runTest {
        ConversationCards.begin()
        val snapshot = list("bread", source = null)
        val live = list("milk")
        val todo = PhoneCards.checklist("To-dos", emptyList()).copy(source = CardSource.Reminders)
        ConversationCards.publish(snapshot)
        ConversationCards.publish(live)
        ConversationCards.publish(todo)

        LiveCards.refresh { source ->
            when (source) {
                groceries -> list("milk", "eggs")
                CardSource.Reminders -> error("read failed")
                else -> null
            }
        }

        val cards = ConversationCards.state.value.cards
        assertSame(snapshot, cards[0])
        assertEquals(listOf("milk", "eggs"), (cards[1] as ConversationCard.Checklist).items.map { it.text })
        assertSame(todo, cards[2])
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `follow re-reads on each change while a conversation is open, and not after it ends`() =
        runTest(UnconfinedTestDispatcher()) {
            val changes = MutableSharedFlow<Unit>()
            var lines = listOf("milk")
            var reads = 0
            val job = launch {
                LiveCards.follow(changes) {
                    reads++
                    list(*lines.toTypedArray())
                }
            }
            changes.emit(Unit)
            assertEquals("closed: nothing collected", 0, reads)

            ConversationCards.begin()
            ConversationCards.publish(list("milk"))
            lines = listOf("milk", "eggs")
            changes.emit(Unit)
            assertEquals(1, reads)
            val shown = ConversationCards.state.value.cards.single() as ConversationCard.Checklist
            assertEquals(listOf("milk", "eggs"), shown.items.map { it.text })

            ConversationCards.end()
            changes.emit(Unit)
            assertEquals(1, reads)
            job.cancel()
        }

    @Test
    fun `a calendar card in the stack covers the days of its window`() {
        ConversationCards.begin()
        ConversationCards.publish(
            PhoneCards.calendar(emptyList(), java.time.ZoneOffset.UTC, 1_000L).copy(source = CardSource.Calendar(1_000L, 2_000L)),
        )
        val state = ConversationCards.state.value
        assertTrue(LiveCards.calendarShows(state, 1_500L))
        assertTrue(!LiveCards.calendarShows(state, 2_500L))
    }
}

package com.kevin.legion.ui.assistant

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.kevin.legion.assistant.cards.CardSource
import com.kevin.legion.assistant.cards.ChecklistLine
import com.kevin.legion.assistant.cards.ConversationCard
import com.kevin.legion.assistant.cards.PhoneCards
import com.kevin.legion.ui.theme.soft.SoftTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * Review of 79c642b9: the stage turns to page one only when a card is published (the store's
 * serial), never when a card closes or a live card is read again in place, and still does at the
 * stack's cap when the new card has the newest card's source. Invented lists.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CardStageArrivalTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun list(name: String, vararg lines: String, id: Long = name.length.toLong()) =
        PhoneCards.checklist(name, lines.map { ChecklistLine(it, false) }).copy(source = CardSource.Checklist(id))

    private var cards by mutableStateOf(emptyList<ConversationCard>())
    private var serial by mutableStateOf(0L)

    private fun show(vararg initial: ConversationCard) {
        cards = initial.toList()
        serial = initial.size.toLong()
        rule.setContent { SoftTheme { CardStage(cards, onClose = {}, arrival = serial) } }
    }

    private fun older() {
        rule.onNodeWithText("Older").performClick()
        rule.waitForIdle()
    }

    @Test
    fun `closing a middle card keeps the page he is on`() {
        val a = list("Camping", "tent", id = 1)
        val b = list("Packing", "socks", id = 2)
        val c = list("Errands", "stamps", id = 3)
        show(a, b, c)
        older()
        older()
        rule.onNodeWithText("3 of 3").assertExists()

        cards = listOf(a, c)
        rule.waitForIdle()

        rule.onNodeWithText("2 of 2").assertExists()
        rule.onNodeWithText("Camping").assertExists()
    }

    @Test
    fun `a card read again in place keeps the page, a published one turns to it`() {
        val a = list("Camping", "tent", id = 1)
        val b = list("Packing", "socks", id = 2)
        show(a, b)
        older()
        rule.onNodeWithText("2 of 2").assertExists()

        cards = listOf(a, list("Packing", "socks", "hat", id = 2))
        rule.waitForIdle()
        rule.onNodeWithText("2 of 2").assertExists()

        // At the cap the count stays the same and the newest source repeats: the serial turns the page.
        cards = listOf(list("Packing", "socks", "hat", id = 2), list("Packing", "socks", "hat", "gloves", id = 2))
        serial += 1
        rule.waitForIdle()
        rule.onNodeWithText("1 of 2").assertExists()
        rule.onNodeWithText("gloves", substring = true).assertExists()
    }

    @Test
    fun `page keys follow the source, stay unique, and fall back to the card`() {
        val a = list("Camping", "tent", id = 1)
        val again = list("Camping", "tent", "rope", id = 1)
        val snapshot = PhoneCards.checklist("Old", emptyList())
        val keys = pageKeys(listOf(a, again, snapshot))
        assertEquals(3, keys.toSet().size)
        assertEquals(pageKeys(listOf(a))[0], pageKeys(listOf(again))[0])
        assertNotEquals(pageKeys(listOf(snapshot))[0], pageKeys(listOf(snapshot.copy()))[0])
    }
}

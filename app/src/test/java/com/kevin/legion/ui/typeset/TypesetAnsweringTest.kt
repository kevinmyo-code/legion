package com.kevin.legion.ui.typeset

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.kevin.legion.assistant.cards.ChecklistLine
import com.kevin.legion.assistant.cards.ConversationCard
import com.kevin.legion.ui.assistant.CardDestination
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The front page while Alfred's cards are up (Kevin, 2026-11-10: "like how midcon dash does it"):
 * the cards take the page, the orb stays the tap to talk, the caption's last line and "Type" stay
 * at the foot, and the glance, groups and dock step aside until the cards are put away.
 * Made-up data only: the repo is public.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp")
class TypesetAnsweringTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val card = ConversationCard.Checklist(
        "Due today", "To-dos", listOf(ChecklistLine("Essay draft", false), ChecklistLine("Return the book", true)),
    )

    private fun show(cards: List<ConversationCard>, actions: TypesetHomeActions, caption: String? = null) {
        rule.setContent {
            TypesetPage {
                TypesetHomeContent(
                    glance = GLANCE,
                    phase = OrbPhase.SPEAKING,
                    status = "Speaking…",
                    night = false,
                    cards = cards,
                    actions = actions,
                    groups = { androidx.compose.material3.Text("GROUPS ROW") },
                    caption = caption,
                )
            }
        }
    }

    @Test
    fun `the card is drawn whole, and the glance, groups and dock step aside`() {
        show(listOf(card), TypesetHomeActions(), caption = "Two things are due today.")
        rule.onNodeWithText("To do: Essay draft").assertExists()
        rule.onNodeWithText("Done: Return the book").assertExists()
        rule.onNodeWithText("Two things are due today.").assertExists()
        rule.onNodeWithText("Speaking…").assertExists()
        rule.onNodeWithText("14:20").assertDoesNotExist()
        rule.onNodeWithText("GROUPS ROW").assertDoesNotExist()
        rule.onNodeWithText("ALL APPS").assertDoesNotExist()
    }

    @Test
    fun `the orb still talks and Type still types`() {
        var talked = 0
        var typed = 0
        show(listOf(card), TypesetHomeActions(onTalk = { talked++ }, onType = { typed++ }))
        rule.onNodeWithContentDescription("Talk to Alfred").performClick()
        rule.onNodeWithText("Type").performClick()
        assertEquals(1, talked)
        assertEquals(1, typed)
    }

    @Test
    fun `close takes that card, Open goes to the screen behind`() {
        val closed = mutableListOf<ConversationCard>()
        val opened = mutableListOf<CardDestination>()
        show(listOf(card), TypesetHomeActions(onCloseCard = { closed += it }, onOpenCard = { opened += it }))
        rule.onNodeWithText("OPEN TODAY").performClick()
        rule.onNodeWithContentDescription("Close card").performClick()
        assertTrue(closed.single() === card)
        assertEquals(listOf(CardDestination.TODAY), opened)
    }

    @Test
    fun `with no cards the glance, groups and dock are back`() {
        show(emptyList(), TypesetHomeActions())
        // FitTiers measures more than one tier, so a word can be in the tree more than once.
        listOf("14:20", "GROUPS ROW", "ALL APPS").forEach {
            assertTrue("$it is missing", rule.onAllNodesWithText(it).fetchSemanticsNodes().isNotEmpty())
        }
    }

    @Test
    fun `the answering orb is Mid-Continent's 96 on a tall page and 64 on a short one`() {
        assertEquals(96.dp, answerOrb(700.dp))
        assertEquals(64.dp, answerOrb(540.dp))
    }

    private companion object {
        val GLANCE = TypesetGlance(
            dateline = "Monday, 9 November",
            time = "14:20",
            next = TypesetFigure("15:30", null, "Study group"),
            due = TypesetFigure("2", null, "due today"),
            left = TypesetFigure("\$1,240", null, "left for November"),
        )
    }
}

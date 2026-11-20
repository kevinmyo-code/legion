package com.kevin.legion.ui.typeset

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.kevin.legion.assistant.cards.ChecklistLine
import com.kevin.legion.assistant.cards.ConversationCard
import com.kevin.legion.service.turn.OutputChoice
import com.kevin.legion.service.turn.OutputToggle
import com.kevin.legion.service.turn.VoiceOutlet
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The SPEAKER / CALL toggle beside the orb: it says where Alfred's voice is, flips on a tap, is a
 * 48 dp target, shows on the front page and while cards are up, and says in words what holds the
 * route when it cannot flip. Made-up data only: the repo is public.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp")
class TypesetOutputToggleTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val speaker = OutputToggle(OutputChoice.SPEAKER, VoiceOutlet.LOUDSPEAKER, enabled = true, label = "Speaker")
    private val call = OutputToggle(OutputChoice.CALL, VoiceOutlet.EARPIECE, enabled = true, label = "Call")

    private fun show(output: OutputToggle?, actions: TypesetHomeActions, cards: List<ConversationCard> = emptyList()) {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            TypesetPage {
                TypesetHomeContent(
                    glance = GLANCE,
                    phase = OrbPhase.LISTENING,
                    status = "Listening…",
                    night = false,
                    cards = cards,
                    actions = actions,
                    output = output,
                )
            }
        }
        rule.mainClock.advanceTimeBy(FRAME_MS)
    }

    @Test
    fun `the toggle beside the orb says speaker and flips on a tap`() {
        var flips = 0
        show(speaker, TypesetHomeActions(onFlipOutput = { flips++ }))
        rule.onNodeWithText("Speaker").assertExists()
        rule.onNodeWithContentDescription(outputDescription(speaker))
            .assertHasClickAction()
            .assertWidthIsAtLeast(TapTarget)
            .assertHeightIsAtLeast(TapTarget)
            .performClick()
        assertEquals(1, flips)
        rule.onAllNodesWithContentDescription("Talk to Alfred").onFirst().assertExists()
    }

    @Test
    fun `in call mode it says call, also while cards are up`() {
        val card = ConversationCard.Checklist("Due today", "To-dos", listOf(ChecklistLine("Essay draft", false)))
        show(call, TypesetHomeActions(), cards = listOf(card))
        rule.onNodeWithText("Call").assertExists()
        rule.onNodeWithContentDescription(outputDescription(call)).assertHasClickAction()
    }

    @Test
    fun `headphones hold the route: it says so and does not flip`() {
        var flips = 0
        val held = OutputToggle(OutputChoice.CALL, VoiceOutlet.WIRED, enabled = false, label = "Headphones")
        show(held, TypesetHomeActions(onFlipOutput = { flips++ }))
        rule.onNodeWithText("Headphones").assertExists()
        rule.onNodeWithContentDescription(outputDescription(held)).assertIsNotEnabled().performClick()
        assertEquals(0, flips)
    }

    @Test
    fun `no earpiece, no toggle`() {
        show(null, TypesetHomeActions())
        rule.onNodeWithText("Speaker").assertDoesNotExist()
        rule.onNodeWithText("Call").assertDoesNotExist()
        rule.onAllNodesWithContentDescription("Talk to Alfred").onFirst().assertExists()
    }

    @Test
    fun `a tap saves the other choice`() {
        assertEquals(OutputChoice.CALL, flipped(OutputChoice.SPEAKER))
        assertEquals(OutputChoice.SPEAKER, flipped(OutputChoice.CALL))
    }

    private companion object {
        const val FRAME_MS = 800L
        val GLANCE = TypesetGlance(
            dateline = "Monday, 9 November",
            time = "14:20",
            next = TypesetFigure("15:30", null, "Study group"),
            due = TypesetFigure("2", null, "due today"),
            left = TypesetFigure("\$1,240", null, "left for November"),
        )
    }
}

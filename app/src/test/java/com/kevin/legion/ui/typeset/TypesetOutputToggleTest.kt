package com.kevin.legion.ui.typeset

import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
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
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The SPEAKER / CALL toggle under the orb: it says where Alfred's voice is, flips on a tap, is a 48dp
 * target, never covers the orb (phones 360 to 411dp wide), shows on the front page and while cards are
 * up, and says in words what holds the route when it cannot flip. Made-up data only: the repo is public.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp")
class TypesetOutputToggleTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val speaker = OutputToggle(OutputChoice.SPEAKER, VoiceOutlet.LOUDSPEAKER, true, "Speaker", "speaker now")
    private val call = OutputToggle(OutputChoice.CALL, VoiceOutlet.EARPIECE, true, "Call", "earpiece now")
    private val longest =
        OutputToggle(OutputChoice.CALL, VoiceOutlet.LOUDSPEAKER, true, "Earpiece unavailable", "refused")

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
    fun `the toggle under the orb says speaker and flips on a tap`() {
        var flips = 0
        show(speaker, TypesetHomeActions(onFlipOutput = { flips++ }))
        rule.onNodeWithText("Speaker").assertExists()
        rule.onNodeWithContentDescription("speaker now")
            .assertHasClickAction()
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
        rule.onNodeWithContentDescription("earpiece now").assertHasClickAction()
    }

    @Test
    fun `headphones hold the route, it says so and does not flip`() {
        var flips = 0
        val held = OutputToggle(OutputChoice.CALL, VoiceOutlet.WIRED, false, "Headphones", "wired")
        show(held, TypesetHomeActions(onFlipOutput = { flips++ }))
        rule.onNodeWithText("Headphones").assertExists()
        rule.onNodeWithContentDescription("wired").assertIsNotEnabled().performClick()
        assertEquals(0, flips)
    }

    @Test
    fun `no earpiece, no toggle`() {
        show(null, TypesetHomeActions())
        rule.onNodeWithText("Speaker").assertDoesNotExist()
        rule.onNodeWithText("Call").assertDoesNotExist()
        rule.onAllNodesWithContentDescription("Talk to Alfred").onFirst().assertExists()
    }

    @Test @Config(qualifiers = "w360dp-h640dp")
    fun `at 360dp the toggle never covers the orb`() = assertApart()

    @Test @Config(qualifiers = "w384dp-h832dp")
    fun `at 384dp the toggle never covers the orb`() = assertApart()

    @Test @Config(qualifiers = "w411dp-h891dp")
    fun `at 411dp the toggle never covers the orb`() = assertApart()

    @Test @Config(qualifiers = "w360dp-h640dp")
    fun `at 360dp with cards up the toggle never covers the orb`() {
        val card = ConversationCard.Checklist("Due today", "To-dos", listOf(ChecklistLine("Essay draft", false)))
        assertApart(cards = listOf(card))
    }

    /** The longest label, on the page: the toggle's touch target and the orb's do not intersect. */
    private fun assertApart(cards: List<ConversationCard> = emptyList()) {
        show(longest, TypesetHomeActions(), cards)
        val orb = placed(hasContentDescription("Talk to Alfred")).boundsInRoot
        val toggle = placed(hasContentDescription("refused")).boundsInRoot
        assertFalse("toggle $toggle overlaps orb $orb", orb.overlaps(toggle))
    }

    private fun placed(matcher: SemanticsMatcher): SemanticsNode =
        rule.onAllNodes(matcher).fetchSemanticsNodes().first { it.layoutInfo.isPlaced && !it.boundsInRoot.isEmpty }

    private fun Rect.overlaps(other: Rect): Boolean =
        left < other.right && other.left < right && top < other.bottom && other.top < bottom

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

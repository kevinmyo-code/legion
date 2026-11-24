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
import com.kevin.legion.service.turn.AudioRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Call button under the orb (Kevin, 2026-11-24): it sits beside "Type", calls on a tap, is a 48 dp
 * target, never covers the orb (phones 360 to 411 dp wide), shows on the front page and while cards
 * are up, is absent on a phone with no earpiece, and says in words why it cannot be pressed during a
 * real phone call. Made-up data only: the repo is public.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp")
class TypesetCallButtonTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val earpiece = CallButtonRule.face(hasEarpiece = true, route = AudioRoute.PHONE_SPEAKER, inCall = false)!!
    private val onHeadset = CallButtonRule.face(hasEarpiece = true, route = AudioRoute.BLUETOOTH, inCall = false)!!
    private val duringCall = CallButtonRule.face(hasEarpiece = true, route = AudioRoute.PHONE_SPEAKER, inCall = true)!!

    private fun show(call: CallButtonUi?, actions: TypesetHomeActions, cards: List<ConversationCard> = emptyList()) {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            TypesetPage {
                TypesetHomeContent(
                    glance = GLANCE,
                    phase = OrbPhase.LISTENING,
                    status = "Listening...",
                    night = false,
                    cards = cards,
                    actions = actions,
                    call = call,
                )
            }
        }
        rule.mainClock.advanceTimeBy(FRAME_MS)
    }

    @Test
    fun `the button under the orb says Call and calls on a tap`() {
        var calls = 0
        show(earpiece, TypesetHomeActions(onCall = { calls++ }))
        rule.onNodeWithText("Call").assertExists()
        rule.onNodeWithText("Type").assertExists()
        rule.onNodeWithContentDescription(earpiece.detail)
            .assertHasClickAction()
            .assertHeightIsAtLeast(TapTarget)
            .performClick()
        assertEquals(1, calls)
        rule.onAllNodesWithContentDescription("Talk to Alfred").onFirst().assertExists()
    }

    @Test
    fun `it is there while cards are up too`() {
        val card = ConversationCard.Checklist("Due today", "To-dos", listOf(ChecklistLine("Essay draft", false)))
        show(earpiece, TypesetHomeActions(), cards = listOf(card))
        rule.onNodeWithContentDescription(earpiece.detail).assertHasClickAction()
    }

    @Test
    fun `a headset says so and still calls`() {
        var calls = 0
        show(onHeadset, TypesetHomeActions(onCall = { calls++ }))
        rule.onNodeWithText("Call on headset").assertExists()
        rule.onNodeWithContentDescription(onHeadset.detail).performClick()
        assertEquals("a call on a headset is still a call", 1, calls)
    }

    @Test
    fun `during a real phone call it says so and does not call`() {
        var calls = 0
        show(duringCall, TypesetHomeActions(onCall = { calls++ }))
        rule.onNodeWithContentDescription(duringCall.detail).assertIsNotEnabled().performClick()
        assertEquals(0, calls)
    }

    @Test
    fun `no earpiece, no button`() {
        assertNull("nothing to hold to an ear", CallButtonRule.face(false, AudioRoute.PHONE_SPEAKER, false))
        show(null, TypesetHomeActions())
        rule.onNodeWithText("Call").assertDoesNotExist()
        rule.onAllNodesWithContentDescription("Talk to Alfred").onFirst().assertExists()
    }

    @Test @Config(qualifiers = "w360dp-h640dp")
    fun `at 360dp the button never covers the orb`() = assertApart()

    @Test @Config(qualifiers = "w384dp-h832dp")
    fun `at 384dp the button never covers the orb`() = assertApart()

    @Test @Config(qualifiers = "w411dp-h891dp")
    fun `at 411dp the button never covers the orb`() = assertApart()

    @Test @Config(qualifiers = "w360dp-h640dp")
    fun `at 360dp with cards up the button never covers the orb`() {
        val card = ConversationCard.Checklist("Due today", "To-dos", listOf(ChecklistLine("Essay draft", false)))
        assertApart(cards = listOf(card))
    }

    /** The longest label, on the page: the button's touch target and the orb's do not intersect. */
    private fun assertApart(cards: List<ConversationCard> = emptyList()) {
        val longest = CallButtonRule.face(hasEarpiece = true, route = AudioRoute.WIRED_HEADSET, inCall = false)!!
        show(longest, TypesetHomeActions(), cards)
        val orb = placed(hasContentDescription("Talk to Alfred")).boundsInRoot
        val button = placed(hasContentDescription(longest.detail)).boundsInRoot
        assertFalse("the button $button overlaps the orb $orb", orb.overlaps(button))
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

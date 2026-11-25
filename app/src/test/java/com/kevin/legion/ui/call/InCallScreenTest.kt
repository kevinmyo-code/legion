package com.kevin.legion.ui.call

import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.kevin.legion.service.cascade.InCallRule
import com.kevin.legion.service.turn.VoiceOutlet
import com.kevin.legion.ui.typeset.TypesetPage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The in-call screen (plan 1.3): the persona's name, the status line, and three 48 dp controls.
 * Made-up data only; the name is passed in, never read from a literal in the screen.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp")
class InCallScreenTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private var muteTaps = 0
    private var speakerTaps = 0
    private var hangUps = 0

    private fun show(
        answeredAtMs: Long? = null,
        muted: Boolean = false,
        outlet: VoiceOutlet = VoiceOutlet.EARPIECE,
        name: String = "Alfred",
        nowMs: Long = NOW,
    ) {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            TypesetPage {
                InCallScreen(
                    name = name,
                    answeredAtMs = answeredAtMs,
                    muted = muted,
                    outlet = outlet,
                    onMute = { muteTaps++ },
                    onSpeaker = { speakerTaps++ },
                    onHangUp = { hangUps++ },
                    clock = { nowMs },
                )
            }
        }
    }

    @Test
    fun `it says who answered, by the name it is given`() {
        show(name = "Jarvis")
        rule.onNodeWithText("Jarvis").assertExists()
    }

    @Test
    fun `before the pickup it says connecting`() {
        show(answeredAtMs = null)
        rule.onNodeWithText(InCallRule.CONNECTING).assertExists()
    }

    @Test
    fun `once answered it shows how long the call has been up`() {
        // Answered 65 s before the held clock reads NOW: a phone would say 01:05.
        show(answeredAtMs = NOW - 65_000)
        rule.onNodeWithText("01:05").assertExists()
    }

    @Test
    fun `the three controls are there, each a 48 dp target`() {
        show()
        for (control in listOf(InCallRule.mute(false), InCallRule.speaker(VoiceOutlet.EARPIECE), InCallRule.hangUp())) {
            rule.onNodeWithContentDescription(control.detail)
                .assertHasClickAction()
                .assertHeightIsAtLeast(48.dp)
        }
        rule.onNodeWithText("Mute").assertExists()
        rule.onNodeWithText("Speaker").assertExists()
        rule.onNodeWithText("Hang up").assertExists()
    }

    @Test
    fun `each control reports its own press`() {
        show()
        rule.onNodeWithText("Mute").performClick()
        rule.onNodeWithText("Speaker").performClick()
        rule.onNodeWithText("Hang up").performClick()
        assertEquals(1, muteTaps)
        assertEquals(1, speakerTaps)
        assertEquals(1, hangUps)
    }

    @Test
    fun `muted says so and offers the way back`() {
        show(muted = true)
        rule.onNodeWithText("Unmute").assertExists()
        rule.onNodeWithContentDescription(InCallRule.mute(true).detail).assertHasClickAction()
    }

    /**
     * Every touch stops at the call screen. Without it a tap on the empty middle reaches whatever is
     * under the overlay, and on the home page that is the orb, which interrupts or ends the call.
     */
    @Test
    fun `a tap on the screen never reaches what is under it`() {
        var underneath = 0
        rule.mainClock.autoAdvance = false
        rule.setContent {
            TypesetPage {
                Box(Modifier.fillMaxSize().clickable { underneath++ }.testTag("under"))
                InCallScreen(
                    name = "Alfred",
                    answeredAtMs = NOW,
                    muted = false,
                    outlet = VoiceOutlet.EARPIECE,
                    onMute = { muteTaps++ },
                    onSpeaker = { speakerTaps++ },
                    onHangUp = { hangUps++ },
                    clock = { NOW },
                )
            }
        }
        // The middle of the screen: between the name and the controls, over no control of its own.
        rule.onNodeWithTag("under").performTouchInput { click(center) }
        assertEquals("the page under the call must not be tapped", 0, underneath)
        assertEquals(0, muteTaps + speakerTaps + hangUps)
    }

    @Test
    fun `on the loudspeaker the control offers the ear again`() {
        show(outlet = VoiceOutlet.LOUDSPEAKER)
        rule.onNodeWithText("Earpiece").assertExists()
    }

    @Test
    fun `on a headset the route is not ours to move, and the control says so`() {
        show(outlet = VoiceOutlet.BLUETOOTH)
        rule.onNodeWithText("Headset").assertExists()
        rule.onNodeWithContentDescription(InCallRule.speaker(VoiceOutlet.BLUETOOTH).detail)
            .assertIsNotEnabled()
            .performClick()
        assertEquals("a hands-free link takes no ask", 0, speakerTaps)
        // Hang up is still his: the call ends on the phone even when the audio is not on it.
        rule.onNodeWithText("Hang up").performClick()
        assertEquals(1, hangUps)
    }

    private companion object {
        /** A held clock, so the timer's text is exact rather than a race with the wall clock. */
        const val NOW = 1_764_000_000_000L
    }
}

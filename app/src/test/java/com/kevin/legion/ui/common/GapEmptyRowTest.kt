package com.kevin.legion.ui.common

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.kevin.legion.ui.theme.LegionTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * [GapEmptyRow]'s inverted hierarchy (command-center ticket 13 finding 3).
 *
 * The regression this guards is subtle and would otherwise be invisible: the voice phrase drifting
 * back INTO the message string. Nothing would fail - the copy would still be correct English, the
 * screen would still explain itself - but the failing path (voice) would be back in the largest
 * text with the working one (the button) small underneath, which is the whole thing ADR 0035 says
 * not to do. So the assertions are about WHERE the phrase is, not merely that it is present.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GapEmptyRowTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private fun setContent(content: @androidx.compose.runtime.Composable () -> Unit) {
        composeTestRule.setContent {
            LegionTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    content()
                }
            }
        }
    }

    @Test
    fun `the action is rendered and fires`() {
        var taps = 0
        setContent {
            GapEmptyRow(
                label = "Bodyweight",
                message = "Nothing logged yet.",
                actionLabel = "+ LOG WEIGHT",
                onAction = { taps++ },
                voiceHint = "log my weight",
            )
        }
        composeTestRule.onNodeWithText("+ LOG WEIGHT").assertIsDisplayed()
        composeTestRule.onNodeWithText("+ LOG WEIGHT").performClick()
        assertEquals(1, taps)
    }

    @Test
    fun `the voice phrase is a caption, never part of the message`() {
        setContent {
            GapEmptyRow(
                label = "Bodyweight",
                message = "Nothing logged yet.",
                actionLabel = "+ LOG WEIGHT",
                onAction = {},
                voiceHint = "log my weight",
            )
        }
        // The message stands alone as a plain statement of the empty fact...
        composeTestRule.onNodeWithText("Nothing logged yet.").assertIsDisplayed()
        // ...and the phrase appears only in the subordinate "or say:" caption.
        composeTestRule.onNodeWithText("or say: “log my weight”").assertIsDisplayed()
    }

    @Test
    fun `a row with no hands path yet renders the hint and no button`() {
        setContent {
            GapEmptyRow(
                label = "History",
                message = "Nothing logged yet.",
                voiceHint = "log a meal",
            )
        }
        composeTestRule.onNodeWithText("or say: “log a meal”").assertIsDisplayed()
        composeTestRule.onNodeWithText("+ LOG MEAL").assertDoesNotExist()
    }

    @Test
    fun `the original two-argument shape still renders`() {
        setContent { GapEmptyRow(label = "Groceries", message = "No budget set yet.") }
        composeTestRule.onNodeWithText("Groceries").assertIsDisplayed()
        composeTestRule.onNodeWithText("No budget set yet.").assertIsDisplayed()
    }
}

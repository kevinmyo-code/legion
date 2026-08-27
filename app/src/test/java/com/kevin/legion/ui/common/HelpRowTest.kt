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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * [HelpRow]'s disclosure behaviour (command-center ticket 13 finding 2).
 *
 * The load-bearing assertion is the FIRST one: collapsed by default. The whole point of this
 * component is that permanently-rendered explainer prose stopped being furniture, and a regression
 * that quietly flipped the default would restore the exact problem the ticket was filed about while
 * leaving every other test green - the text would still be present, still correct, still reachable.
 * Only its absence-until-asked is the behaviour worth pinning.
 *
 * Same runner and graphics mode as the screenshot suite; no device qualifier, since nothing here
 * asserts on layout at a particular size.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HelpRowTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val explanation =
        "A file that doesn't state its own account takes the account mapped to the folder it's in."

    private fun setHelpRow(label: String = HELP_ROW_DEFAULT_LABEL) {
        composeTestRule.setContent {
            LegionTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    HelpRow(explanation, label = label)
                }
            }
        }
    }

    @Test
    fun `the explanation is not rendered until asked for`() {
        setHelpRow()
        composeTestRule.onNodeWithText(explanation).assertDoesNotExist()
        // The stamp itself IS present - the words are re-housed, never deleted, so there has to be
        // something on screen saying they exist.
        composeTestRule.onNodeWithText("+ ${HELP_ROW_DEFAULT_LABEL}").assertIsDisplayed()
    }

    @Test
    fun `tapping the stamp reveals the explanation`() {
        setHelpRow()
        composeTestRule.onNodeWithText("+ ${HELP_ROW_DEFAULT_LABEL}").performClick()
        composeTestRule.onNodeWithText(explanation).assertIsDisplayed()
    }

    @Test
    fun `tapping again hides it`() {
        setHelpRow()
        composeTestRule.onNodeWithText("+ ${HELP_ROW_DEFAULT_LABEL}").performClick()
        composeTestRule.onNodeWithText("- ${HELP_ROW_DEFAULT_LABEL}").performClick()
        composeTestRule.onNodeWithText(explanation).assertDoesNotExist()
    }

    @Test
    fun `a custom label replaces the default entirely`() {
        setHelpRow(label = "HOW MAPPING WORKS")
        composeTestRule.onNodeWithText("+ HOW MAPPING WORKS").assertIsDisplayed()
        composeTestRule.onNodeWithText("+ ${HELP_ROW_DEFAULT_LABEL}").assertDoesNotExist()
    }
}

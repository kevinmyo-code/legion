package com.kevin.legion.ui.browser

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextReplacement
import com.kevin.legion.browser.BrowserUiState
import com.kevin.legion.browser.FeedBlock
import com.kevin.legion.dumb.FeedDomains
import com.kevin.legion.ui.theme.soft.SoftTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * ADR 0071's hand path: the browser screen's bar and buttons, with a stand-in for the WebView. Each
 * button reaches its action, disabled ones say so, and a feed block covers the page and the buttons.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BrowserScreenTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val calls = mutableListOf<String>()
    private val actions = BrowserActions(
        onSubmit = { calls += "submit:$it" },
        onBack = { calls += "back" },
        onForward = { calls += "forward" },
        onReload = { calls += "reload" },
        onClose = { calls += "close" },
        onClearNotice = { calls += "clear" },
    )

    private fun show(state: BrowserUiState) {
        rule.setContent {
            SoftTheme {
                BrowserScreen(
                    state = state,
                    actions = actions,
                    page = { Text("PAGE", Modifier.fillMaxSize().testTag("page")) },
                    blocked = { Text("BLOCKED ${it.site.label}") },
                )
            }
        }
    }

    @Test fun `typing in the bar and pressing Go submits it`() {
        show(BrowserUiState())
        rule.onNodeWithTag(TAG_ADDRESS).performTextReplacement("joe's pizza menu")
        rule.onNodeWithTag(TAG_ADDRESS).performImeAction()
        assertEquals(listOf("submit:joe's pizza menu"), calls)
    }

    @Test fun `back forward reload and close each reach their action`() {
        show(BrowserUiState(url = "https://example.com", title = "Example", canGoBack = true, canGoForward = true))
        rule.onNodeWithText("https://example.com").assertIsDisplayed()
        rule.onNodeWithContentDescription("Back").performClick()
        rule.onNodeWithContentDescription("Forward").performClick()
        rule.onNodeWithContentDescription("Reload").performClick()
        rule.onNodeWithContentDescription("Close browser").performClick()
        assertEquals(listOf("back", "forward", "reload", "close"), calls)
    }

    @Test fun `back and forward are off with no history`() {
        show(BrowserUiState(url = "https://example.com"))
        rule.onNodeWithContentDescription("Back").assertIsNotEnabled()
        rule.onNodeWithContentDescription("Forward").assertIsNotEnabled()
        rule.onNodeWithContentDescription("Reload").assertIsEnabled()
    }

    @Test fun `an empty browser says what to do`() {
        show(BrowserUiState())
        rule.onNodeWithText("Ask for a page, a picture or a menu, or type above.").assertIsDisplayed()
    }

    @Test fun `a feed block covers the page and hides the buttons`() {
        val site = FeedDomains.siteFor("instagram.com")!!
        val block = FeedBlock(site, "https://instagram.com", matrix = false)
        show(BrowserUiState(url = "https://example.com", blocked = block))
        rule.onNodeWithText("BLOCKED Instagram").assertIsDisplayed()
        rule.onNodeWithContentDescription("Back").assertDoesNotExist()
    }

    @Test fun `a notice is shown and tapping it clears it`() {
        show(BrowserUiState(url = "https://example.com", notice = "Downloads are off, so nothing was downloaded."))
        rule.onNodeWithTag(TAG_NOTICE).performClick()
        assertEquals(listOf("clear"), calls)
    }
}

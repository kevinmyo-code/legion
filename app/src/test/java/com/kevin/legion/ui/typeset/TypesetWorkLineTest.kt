package com.kevin.legion.ui.typeset

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.kevin.legion.ui.apps.PAUSED_ELSEWHERE
import com.kevin.legion.ui.apps.PAUSED_TYPESET
import com.kevin.legion.ui.theme.soft.SoftTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Stage 6 (2026-11-16): the Typeset app index carries the work-apps line the Matrix grid had, so a
 * paused work app's message points at something on screen (ADR 0050, ADR 0035).
 */
@RunWith(RobolectricTestRunner::class)
class TypesetWorkLineTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private fun show(workPaused: Boolean?, onToggle: () -> Unit = {}) {
        composeTestRule.setContent {
            SoftTheme {
                TypesetAppsIndex(
                    folders = emptyList(),
                    query = "",
                    onQueryChange = {},
                    blocked = { false },
                    onOpen = {},
                    onLongPress = {},
                    workPaused = workPaused,
                    onToggleWork = onToggle,
                )
            }
        }
    }

    @Test
    fun `no work profile, no work line`() {
        show(workPaused = null)
        composeTestRule.onNodeWithText(WORK_PAUSED_LINE).assertDoesNotExist()
        composeTestRule.onNodeWithText(WORK_PAUSE_LINE).assertDoesNotExist()
    }

    @Test
    fun `paused work apps read as paused and a tap asks to turn them on`() {
        var taps = 0
        show(workPaused = true) { taps++ }
        composeTestRule.onNodeWithText(WORK_PAUSE_LINE).assertDoesNotExist()
        composeTestRule.onNodeWithText(WORK_PAUSED_LINE).assertExists().performClick()
        assertEquals(1, taps)
    }

    @Test
    fun `running work apps offer to pause, and a tap asks for it`() {
        var taps = 0
        show(workPaused = false) { taps++ }
        composeTestRule.onNodeWithText(WORK_PAUSED_LINE).assertDoesNotExist()
        composeTestRule.onNodeWithText(WORK_PAUSE_LINE).assertExists().performClick()
        assertEquals(1, taps)
    }

    @Test
    fun `the paused message names what the screen shows`() {
        assertTrue(PAUSED_TYPESET.contains("\"Work apps are paused\""))
        assertTrue(WORK_PAUSED_LINE.startsWith("Work apps are paused"))
        assertTrue(PAUSED_TYPESET.contains("Find an app"))
        assertTrue(PAUSED_ELSEWHERE.contains("Apps screen"))
    }
}

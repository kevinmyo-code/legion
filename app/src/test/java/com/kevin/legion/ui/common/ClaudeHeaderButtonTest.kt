package com.kevin.legion.ui.common

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.kevin.legion.ui.theme.soft.SoftTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The header's Claude sessions button (Kevin 2026-11-04): left of the gear, a worded count badge. */
@RunWith(RobolectricTestRunner::class)
class ClaudeHeaderButtonTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private var opened = 0

    private fun show(running: Int, withClaude: Boolean = true) {
        rule.setContent {
            SoftTheme {
                StatusLine(
                    synced = true,
                    obdConnected = false,
                    clock = "09:00",
                    onOpenSettings = {},
                    onOpenPhoneSettings = {},
                    onOpenApps = {},
                    onOpenClaude = if (withClaude) ({ opened++ }) else null,
                    claudeRunning = running,
                )
            }
        }
    }

    @Test
    fun `the button sits immediately left of the gear, with the running count as a badge`() {
        show(running = 2)
        val claude = rule.onNodeWithTag(CLAUDE_BUTTON_TAG).getBoundsInRoot()
        val gear = rule.onNodeWithContentDescription("Settings").getBoundsInRoot()
        val apps = rule.onNodeWithContentDescription("Apps").getBoundsInRoot()
        assertTrue("Claude button left of the gear", claude.right <= gear.left)
        assertTrue("and right of Apps, so nothing sits between it and the gear", apps.right <= claude.left)
        rule.onNodeWithContentDescription("Claude sessions").assertExists()
        rule.onNodeWithText("2").assertExists()
        rule.onNodeWithTag(CLAUDE_BUTTON_TAG)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "2 running"))
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        assertEquals(1, opened)
    }

    @Test
    fun `nothing running shows no badge and says so`() {
        show(running = 0)
        rule.onNodeWithText("0").assertDoesNotExist()
        rule.onNodeWithTag(CLAUDE_BUTTON_TAG)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "nothing running"))
    }

    @Test
    fun `no callback, no button`() {
        show(running = 3, withClaude = false)
        rule.onNodeWithTag(CLAUDE_BUTTON_TAG).assertDoesNotExist()
    }
}

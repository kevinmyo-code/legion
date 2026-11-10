package com.kevin.legion.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.kevin.legion.service.ProactiveCategory
import com.kevin.legion.service.ProactiveSettings
import com.kevin.legion.spokenbrief.SpokenBrief
import com.kevin.legion.testutil.RoomTestReset
import com.kevin.legion.ui.theme.soft.SoftTheme
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Integration 2026-11-10: `ProactiveSpeechScreen` was edited by `feat/proactive-voice` (the two
 * spoken-brief switches under Digest, ADR 0082) and `feat/watches` (the Watches category row and the
 * "See and change your watches" button, ADR 0083). Both merged cleanly; this checks every merged
 * row is on the screen and that the button is wired (its parameter defaults to a no-op, so a
 * dropped wire would still compile).
 */
@RunWith(RobolectricTestRunner::class)
class ProactiveSpeechScreenMergeTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun setUp() {
        RoomTestReset.resetCarDatabaseSingleton()
    }

    @After
    fun tearDown() {
        ProactiveSettings.resetForTest()
        RoomTestReset.drainArchDiskIoPool()
    }

    @Test
    fun `both branches' rows are present and the watches button opens the watches screen`() {
        var opened = false
        compose.setContent {
            SoftTheme { ProactiveSpeechScreen(onBack = {}, onOpenWatches = { opened = true }) }
        }
        compose.waitForIdle()
        for (title in listOf(ProactiveCategory.DIGEST.title, ProactiveCategory.WATCHES.title)) {
            compose.onNodeWithText(title).performScrollTo().assertIsDisplayed()
        }
        for (brief in SpokenBrief.entries) {
            compose.onNodeWithText(brief.title).performScrollTo().assertIsDisplayed()
        }
        compose.onNodeWithText("See and change your watches").performScrollTo().performClick()
        assertTrue(opened)
    }
}

package com.kevin.legion.screenshot

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.kevin.legion.dumb.FakeFeedPassStore
import com.kevin.legion.dumb.FeedPassController
import com.kevin.legion.ui.dumb.FeedBlockedPage
import com.kevin.legion.ui.dumb.FeedBlockedScreen
import com.kevin.legion.ui.theme.soft.LocalTypesetEdition
import com.kevin.legion.ui.theme.soft.TypesetEdition
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The feed-blocked page outside Matrix (home-ui review 2026-11-18): ink on paper by day, the black
 * Matrix-style page by night, as the bounce activity frames it ([FeedBlockedPage]). Made-up app.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FeedBlockedScreenshotTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private fun shot(file: String, edition: TypesetEdition) {
        val pass = FeedPassController(FakeFeedPassStore(), matrixOn = { false }) { NOW }
        composeTestRule.setContent {
            CompositionLocalProvider(LocalTypesetEdition provides edition) {
                FeedBlockedPage {
                    FeedBlockedScreen(
                        appLabel = "Scroller",
                        pass = pass,
                        packageName = "example.feed",
                        onBack = {},
                        onOpen = { false },
                        onBreathe = {},
                    )
                }
            }
        }
        composeTestRule.onRoot().captureRoboImage(file)
    }

    @Test
    @Config(qualifiers = ScreenshotDeviceConfig.QUALIFIERS)
    fun `feed blocked, day paper`() = shot("feed-blocked-day.png", TypesetEdition.DAY)

    @Test
    @Config(qualifiers = ScreenshotDeviceConfig.QUALIFIERS + "-night")
    fun `feed blocked, night as before`() = shot("feed-blocked-night.png", TypesetEdition.NIGHT)

    private companion object {
        const val NOW = 1_790_000_000_000L
    }
}

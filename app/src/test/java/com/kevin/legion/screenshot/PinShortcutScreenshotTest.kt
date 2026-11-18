package com.kevin.legion.screenshot

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.kevin.legion.ui.apps.PinShortcutSheet
import com.kevin.legion.ui.theme.soft.LocalTypesetEdition
import com.kevin.legion.ui.theme.soft.SoftTheme
import com.kevin.legion.ui.theme.soft.TypesetEdition
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The pin-shortcut confirmation (2026-11-18): an app such as a browser asks LEGION, as the home app,
 * to add a shortcut; LEGION asks first, in Typeset. Made-up names.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PinShortcutScreenshotTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private fun shot(file: String, edition: TypesetEdition) {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalTypesetEdition provides edition) {
                SoftTheme { PinShortcutSheet(label = "Weather board", from = "Browser", onAdd = {}, onCancel = {}) }
            }
        }
        composeTestRule.onRoot().captureRoboImage(file)
    }

    @Test
    @Config(qualifiers = ScreenshotDeviceConfig.QUALIFIERS)
    fun `add to home screen, day`() = shot("pin-shortcut-confirm-day.png", TypesetEdition.DAY)

    @Test
    @Config(qualifiers = ScreenshotDeviceConfig.QUALIFIERS + "-night")
    fun `add to home screen, night`() = shot("pin-shortcut-confirm-night.png", TypesetEdition.NIGHT)
}

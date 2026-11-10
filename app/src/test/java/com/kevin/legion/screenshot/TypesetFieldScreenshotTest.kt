package com.kevin.legion.screenshot

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.kevin.legion.R
import com.kevin.legion.field.CompassSource
import com.kevin.legion.field.FieldUiState
import com.kevin.legion.field.GpsStatus
import com.kevin.legion.field.HeadingUi
import com.kevin.legion.ui.field.FieldActions
import com.kevin.legion.ui.field.FieldContent
import com.kevin.legion.ui.theme.soft.SoftTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Field screen in the day and night editions, drawn over the activity's real window ground
 * (`instrument_ground`, near-black in both modes). Before the fix the screen painted no ground and no
 * content colour, so its text was Compose's default black on that window: unreadable at night (Kevin,
 * 2026-11-10). Made-up heading, no position.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TypesetFieldScreenshotTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun shot(file: String) {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            Box(Modifier.fillMaxSize().background(colorResource(R.color.instrument_ground))) {
                SoftTheme { FieldContent(STATE, ACTIONS) }
            }
        }
        rule.mainClock.advanceTimeBy(FRAME_MS)
        rule.onRoot().captureRoboImage(file)
    }

    @Test
    @Config(qualifiers = ScreenshotDeviceConfig.QUALIFIERS)
    fun `field, day`() = shot("typeset-field-day.png")

    @Test
    @Config(qualifiers = ScreenshotDeviceConfig.QUALIFIERS + "-night")
    fun `field, night`() = shot("typeset-field-night.png")

    private companion object {
        const val FRAME_MS = 500L
        val STATE = FieldUiState(
            heading = HeadingUi(
                magneticDeg = 30.0,
                trueDeg = null,
                point = "NNE",
                backAzimuthMagDeg = 210.0,
                backAzimuthTrueDeg = null,
                northArrowDeg = null,
                upright = false,
            ),
            compassSource = CompassSource.ROTATION_VECTOR,
            gps = GpsStatus.SEARCHING,
        )
        val ACTIONS = FieldActions(onBack = {}, onGrantLocation = {}, onMark = {}, onMargin = {})
    }
}

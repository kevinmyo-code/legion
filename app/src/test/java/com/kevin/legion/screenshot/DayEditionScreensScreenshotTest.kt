package com.kevin.legion.screenshot

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.kevin.legion.ui.DrivingModeContent
import com.kevin.legion.ui.fleet.DueRowView
import com.kevin.legion.ui.FleetContent
import com.kevin.legion.ui.FleetUiState
import com.kevin.legion.ui.fleet.LiveRowView
import com.kevin.legion.ui.SettingsScreen
import com.kevin.legion.ui.theme.LegionTheme
import com.kevin.legion.ui.theme.LocalMatrixMode
import com.kevin.legion.ui.theme.soft.LocalTypesetEdition
import com.kevin.legion.ui.theme.soft.TypesetEdition
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Screens still on `LegionTheme`, drawn the way `MainActivity` nests them, with the Typeset edition
 * provided (home-ui review 2026-11-18): by day they are paper and ink, by night the old VACUUM look.
 * Before 2026-11-17 these were black by day; only the review renders (`DaySweepShotsTest`) showed the
 * fix, and those record nothing. Settings, Car and Driving, each in both editions. Made-up values.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = ScreenshotDeviceConfig.QUALIFIERS)
class DayEditionScreensScreenshotTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private fun shot(file: String, edition: TypesetEdition, screen: @Composable () -> Unit) {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalMatrixMode provides false, LocalTypesetEdition provides edition) {
                LegionTheme {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { screen() }
                }
            }
        }
        composeTestRule.onRoot().captureRoboImage(file)
    }

    @Composable
    private fun Settings() = SettingsScreen(
        onOpenAssistant = {},
        onOpenProactiveSpeech = {},
        onOpenConnections = {},
        onOpenDataPrivacy = {},
        onOpenPermissionsDiagnostics = {},
    )

    @Composable
    private fun Car() = FleetContent(
        FleetUiState(
            loading = false,
            vehicleLabel = "Test hatchback",
            mileageValueText = "42,000 mi",
            connected = false,
            liveRows = listOf(
                LiveRowView("Coolant", "88 C", "3 days ago"),
                LiveRowView("Battery", "12.4", "3 days ago"),
            ),
            dueRows = listOf(
                DueRowView("Oil change", "OVERDUE", "every 5,000 mi - last at 36,400", overdue = true),
                DueRowView("Cabin air filter", "in 240 days", "every 12 mo", overdue = false),
            ),
            faults = emptyList(),
        ),
        onOpenPlaces = {},
        onOpenCars = {},
        onOpenUplink = {},
        onOpenMaintenance = {},
        onOpenDrives = {},
    )

    @Composable
    private fun Driving() = DrivingModeContent(
        vehicleName = "Test hatchback",
        linkLive = false,
        clock = "16:40",
        rpm = null,
        speed = null,
        coolant = null,
        lastDrive = null,
        alfredStatus = "Tap to talk",
        tickCounter = 0,
        protocolName = null,
        onExit = {},
    )

    @Test
    fun `settings, day paper`() = shot("legion-settings-day.png", TypesetEdition.DAY) { Settings() }

    @Test
    @Config(qualifiers = ScreenshotDeviceConfig.QUALIFIERS + "-night")
    fun `settings, night`() = shot("legion-settings-night.png", TypesetEdition.NIGHT) { Settings() }

    @Test
    fun `car, day paper`() = shot("legion-car-day.png", TypesetEdition.DAY) { Car() }

    @Test
    @Config(qualifiers = ScreenshotDeviceConfig.QUALIFIERS + "-night")
    fun `car, night`() = shot("legion-car-night.png", TypesetEdition.NIGHT) { Car() }

    @Test
    fun `driving, day paper`() = shot("legion-driving-day.png", TypesetEdition.DAY) { Driving() }

    @Test
    @Config(qualifiers = ScreenshotDeviceConfig.QUALIFIERS + "-night")
    fun `driving, night`() = shot("legion-driving-night.png", TypesetEdition.NIGHT) { Driving() }
}

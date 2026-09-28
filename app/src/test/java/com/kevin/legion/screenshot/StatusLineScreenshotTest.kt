package com.kevin.legion.screenshot

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.kevin.legion.ui.common.StatusLine
import com.kevin.legion.ui.theme.soft.SoftTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Home-launcher ticket 02's own verification step 4: "status line (normal; 2 alarms + key; sync
 * off)". Follows [ReadStateBannerScreenshotTest] exactly - same runner, graphics mode, device
 * qualifier, `capture` shape - wrapped in [SoftTheme] rather than
 * [com.kevin.legion.ui.theme.LegionTheme] since that is what `MainActivity.kt`'s real call site
 * wraps [StatusLine] in now.
 *
 * Two more states were added mid-ticket on the orchestrator's own instruction (see this ticket's
 * own report for what each was and was not verified against): "apps button present"
 * ([StatusLine]'s `onOpenApps`, checked against a real commit on `origin/dev`), and "quiet on,
 * apps present, 1 alarm" ([StatusLine]'s `onToggleQuiet`/`quietOn`, taken on the instruction alone
 * - the right side fitting three controls plus the clock at 384dp is exactly what this state
 * proves or disproves).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = ScreenshotDeviceConfig.QUALIFIERS)
class StatusLineScreenshotTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `normal - synced, OBD off, no key issue, no alarm`() {
        capture("status-line-normal.png") {
            StatusLine(
                synced = true,
                obdConnected = false,
                clock = "14:02",
                onOpenSettings = {},
            )
        }
    }

    @Test
    fun `2 alarms and a key clause survive together`() {
        capture("status-line-2-alarms-and-key.png") {
            StatusLine(
                synced = true,
                obdConnected = true,
                clock = "09:41",
                keyLabel = "Key not set",
                alarmCount = 2,
                onOpenAlarm = {},
                onOpenSettings = {},
            )
        }
    }

    @Test
    fun `sync off reads in words, not colour alone`() {
        capture("status-line-sync-off.png") {
            StatusLine(
                synced = false,
                obdConnected = false,
                clock = "23:58",
                onOpenSettings = {},
            )
        }
    }

    @Test
    fun `apps button present`() {
        capture("status-line-apps-button.png") {
            StatusLine(
                synced = true,
                obdConnected = true,
                clock = "14:02",
                onOpenApps = {},
                onOpenSettings = {},
            )
        }
    }

    @Test
    fun `quiet on, apps present, 1 alarm - the right side's worst case at 384dp`() {
        capture("status-line-quiet-on-apps-alarm.png") {
            StatusLine(
                synced = true,
                obdConnected = true,
                clock = "14:02",
                onOpenApps = {},
                onToggleQuiet = {},
                quietOn = true,
                alarmCount = 1,
                onOpenAlarm = {},
                onOpenSettings = {},
            )
        }
    }

    private fun capture(fileName: String, content: @androidx.compose.runtime.Composable () -> Unit) {
        composeTestRule.setContent {
            SoftTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    content()
                }
            }
        }
        composeTestRule.onRoot().captureRoboImage(fileName)
    }
}

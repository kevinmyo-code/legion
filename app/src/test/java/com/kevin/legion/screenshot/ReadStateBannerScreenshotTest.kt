package com.kevin.legion.screenshot

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.kevin.legion.ui.ReadState
import com.kevin.legion.ui.ReadStateBanner
import com.kevin.legion.ui.theme.LegionTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Backend-erp phase 3 (`.scratch/backend-erp/issues/05-migration-path.md`), read-path honesty.
 * [ReadStateBanner] is the one Compose rendering of [com.kevin.legion.ui.readStateLine], shared by
 * LedgerScreen/PantryScreen/FleetScreen - see that composable's own doc for why one implementation
 * exists rather than three. Two states, per the brief:
 *
 * - **first-load failure**: no data has ever loaded ([ReadState.loadedAtMs] null) and the one
 *   attempt failed - the loudest case, "Couldn't load this."
 * - **stale-with-data**: a load succeeded once, long enough ago to cross
 *   [com.kevin.legion.ui.READ_STALE_AFTER_MS], and nothing has failed since - Kevin's "fresh data
 *   says nothing, stale data says how old" rule made visible.
 *
 * Follows [EngineWidgetStatesScreenshotTest] exactly: same runner, same graphics mode, same device
 * qualifier, same `capture` shape (this one has no "LOADING" text to wait out, since
 * [ReadStateBanner] renders synchronously off a plain data class with no LaunchedEffect of its
 * own - so the wait step that test needs is correctly absent here rather than silently copied in).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = ScreenshotDeviceConfig.QUALIFIERS)
class ReadStateBannerScreenshotTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `first-load failure`() {
        val state = ReadState(loading = false, loadedAtMs = null, failure = "no connection")
        capture("read-state-first-load-failure.png") {
            ReadStateBanner(state, nowMs = FIXED_NOW)
        }
    }

    @Test
    fun `stale with data`() {
        // 20 minutes old, well past READ_STALE_AFTER_MS's 10-minute threshold, no failure.
        val state = ReadState(loading = false, loadedAtMs = FIXED_NOW - 20 * 60_000L, failure = null)
        capture("read-state-stale-with-data.png") {
            ReadStateBanner(state, nowMs = FIXED_NOW)
        }
    }

    private fun capture(fileName: String, content: @androidx.compose.runtime.Composable () -> Unit) {
        composeTestRule.setContent {
            LegionTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    content()
                }
            }
        }
        composeTestRule.onRoot().captureRoboImage(fileName)
    }

    private companion object {
        const val FIXED_NOW = 1_735_000_000_000L
    }
}

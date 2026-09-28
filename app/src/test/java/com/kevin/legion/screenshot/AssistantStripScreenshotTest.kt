package com.kevin.legion.screenshot

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.kevin.legion.service.Phase
import com.kevin.legion.ui.assistant.AssistantOffRow
import com.kevin.legion.ui.assistant.AssistantStripContent
import com.kevin.legion.ui.assistant.AssistantStripResolver
import com.kevin.legion.ui.theme.soft.SoftTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Home-launcher ticket 02's own verification step 4: "talk bar (idle; listening; mic blocked;
 * assistant off)". Follows [ReadStateBannerScreenshotTest] exactly - same runner, graphics mode,
 * device qualifier, `capture` shape - wrapped in [SoftTheme] rather than
 * [com.kevin.legion.ui.theme.LegionTheme], matching `MainActivity.kt`'s real call site and the
 * `AssistantStrip.kt` previews this test mirrors (see [AssistantStripContent]/[AssistantOffRow]'s
 * own doc for why they are `internal` now - this test is the reason).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = ScreenshotDeviceConfig.QUALIFIERS)
class AssistantStripScreenshotTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `idle`() {
        capture("assistant-strip-idle.png") {
            AssistantStripContent(
                state = AssistantStripResolver.resolve(
                    Phase.IDLE, "", null, micGranted = true, silenced = false,
                ),
                onTap = {},
            )
        }
    }

    @Test
    fun `listening pulses the mic icon`() {
        capture("assistant-strip-listening.png") {
            AssistantStripContent(
                state = AssistantStripResolver.resolve(
                    Phase.LISTENING, "how's the oil holding up?", null, micGranted = true, silenced = false,
                ),
                onTap = {},
            )
        }
    }

    @Test
    fun `mic blocked turns caution-toned in words, not colour alone`() {
        capture("assistant-strip-mic-blocked.png") {
            AssistantStripContent(
                state = AssistantStripResolver.resolve(
                    Phase.IDLE, "", null, micGranted = false, silenced = false,
                ),
                onTap = {},
            )
        }
    }

    @Test
    fun `assistant off`() {
        capture("assistant-strip-off.png") {
            AssistantOffRow(onTap = {})
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

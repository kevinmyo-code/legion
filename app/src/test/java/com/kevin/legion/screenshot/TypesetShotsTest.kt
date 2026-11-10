package com.kevin.legion.screenshot

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.kevin.legion.clock.AlarmMath
import com.kevin.legion.clock.Bedtime
import com.kevin.legion.clock.ClockAlarm
import com.kevin.legion.clock.ClockState
import com.kevin.legion.clock.FakeTime
import com.kevin.legion.ui.clock.ClockActions
import com.kevin.legion.ui.clock.ClockScreen
import com.kevin.legion.ui.clock.ClockUi
import com.kevin.legion.ui.theme.soft.LocalTypesetEdition
import com.kevin.legion.ui.theme.soft.SoftColors
import com.kevin.legion.ui.theme.soft.SoftTheme
import com.kevin.legion.ui.theme.soft.TypesetEdition
import com.kevin.legion.ui.theme.soft.TypesetType
import com.kevin.legion.ui.typeset.OrbPhase
import com.kevin.legion.ui.typeset.TypesetOrb
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Review renders for the Typeset build (ADR 0081), NOT baselines: written as plain PNGs to the
 * directory in the `TYPESET_SHOTS_DIR` environment variable, outside the repo, and skipped when it
 * is unset (so the normal suite never runs them and Roborazzi never records them). Covers what the
 * committed screenshot tests cannot show: the night edition (Robolectric is not-night) and the orb.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = ScreenshotDeviceConfig.QUALIFIERS)
class TypesetShotsTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val dir: File? = System.getenv("TYPESET_SHOTS_DIR")?.let(::File)

    private fun shot(name: String, content: @Composable () -> Unit) {
        assumeTrue("TYPESET_SHOTS_DIR unset: review renders skipped", dir != null)
        composeTestRule.setContent(content)
        val bitmap = composeTestRule.onRoot().captureToImage().asAndroidBitmap()
        dir!!.mkdirs()
        File(dir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Composable
    private fun edition(e: TypesetEdition, content: @Composable () -> Unit) =
        CompositionLocalProvider(LocalTypesetEdition provides e) { SoftTheme(content) }

    private val time = FakeTime.at("2026-11-09T22:00")
    private val clockState = ClockState(
        alarms = listOf(
            ClockAlarm(1, 6, 30, AlarmMath.WEEKDAYS, "Work"),
            ClockAlarm(2, 9, 0, AlarmMath.WEEKEND, "", enabled = false),
        ),
        bedtime = Bedtime(enabled = true, hour = 22, minute = 30, active = true),
        nextId = 3,
    )

    @Test
    fun `clock alarms, night edition`() = shot("after-clock-alarms-night.png") {
        edition(TypesetEdition.NIGHT) {
            ClockScreen(ClockUi.from(clockState, time, 0, emptyList(), null, notice = null), ClockActions())
        }
    }

    @Test
    fun `orb phases, day`() = shot("after-orb-day.png") { edition(TypesetEdition.DAY) { OrbRow() } }

    @Test
    fun `orb phases, night`() = shot("after-orb-night.png") { edition(TypesetEdition.NIGHT) { OrbRow() } }

    @Test
    fun `type scale, day`() = shot("after-type-scale-day.png") {
        edition(TypesetEdition.DAY) {
            Column(Modifier.background(SoftColors.ground).padding(16.dp)) {
                Text("10:42", style = TypesetType.time, color = SoftColors.text)
                Text("\$1,240", style = TypesetType.hero, color = SoftColors.text)
                Text("3", style = TypesetType.fig, color = SoftColors.text)
                Text("Checking the calendar", style = TypesetType.lead, color = SoftColors.text)
                Text("Dentist at 3:30 PM, the clinic on Main", style = TypesetType.body, color = SoftColors.text)
                Text("Bank feed, 2 h ago", style = TypesetType.smallItalic, color = SoftColors.text2)
                Text("DUE TODAY", style = TypesetType.caps, color = SoftColors.text2)
                Text("Estimate: 4,200 mi", style = TypesetType.small, color = SoftColors.caution)
            }
        }
    }

    @Composable
    private fun OrbRow() {
        Row(Modifier.background(SoftColors.ground).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            for (p in OrbPhase.entries) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    TypesetOrb(p, Modifier.size(72.dp))
                    Text(p.name.lowercase(), style = TypesetType.caps, color = SoftColors.text2)
                }
            }
        }
    }
}

package com.kevin.legion.screenshot

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.kevin.legion.service.Phase
import com.kevin.legion.ui.dumb.MatrixTalkDisplay
import com.kevin.legion.ui.dumb.MatrixTalkLine
import com.kevin.legion.ui.dumb.MatrixTalkResolver
import com.kevin.legion.ui.dumb.TalkButton
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Matrix HOME's push-to-talk line and button, one golden per state (Kevin, 2026-10-27). Stateless
 * pieces only, on black, at the still frame: HOME itself draws the code rain through `nativeCanvas`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = ScreenshotDeviceConfig.QUALIFIERS)
class MatrixTalkScreenshotTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private fun phase(p: Phase) = MatrixTalkResolver.resolve(p, notice = null, micGranted = true, silenced = false)

    @Test
    fun `every phase, mic blocked and a notice, stacked`() {
        val rows = Phase.entries.map { p -> phase(p) to (if (p == Phase.LISTENING) 5 else null) } + listOf(
            MatrixTalkResolver.resolve(Phase.IDLE, "CONNECTION LOST - TAP TO RETRY", true, false) to null,
            MatrixTalkResolver.resolve(Phase.IDLE, null, micGranted = false, silenced = false) to null,
            MatrixTalkResolver.resolve(Phase.LISTENING, null, micGranted = true, silenced = true) to null,
        )
        capture("matrix-talk-states.png", rows)
    }

    private fun capture(fileName: String, rows: List<Pair<MatrixTalkDisplay, Int?>>) {
        composeTestRule.setContent {
            Column(
                Modifier.fillMaxWidth().background(Color.Black).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                rows.forEach { (display, level) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        MatrixTalkLine(display, tick = null, levelCells = level, modifier = Modifier.weight(1f))
                        Spacer(Modifier.width(12.dp))
                        TalkButton(onClick = {}, display = display)
                    }
                }
            }
        }
        composeTestRule.onRoot().captureRoboImage(fileName)
    }
}

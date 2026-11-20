package com.kevin.midnightai.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.geometry.Rect
import com.kevin.midnightai.ui.theme.AriaDimens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * Recheck: the digit pad really composes and lays out on the unit's 800x456 screen inside the
 * Settings pane chrome (back link, title, padding): all 12 keys placed, drive-size, inside the
 * window and not overlapping. PairingPadTest only checks the arithmetic.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w800dp-h456dp-land-mdpi")
class PairingPadRenderTest {
    @Test fun theDigitPadRendersInsideTheUnitScreenWithDriveSizeKeys() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val placed = linkedMapOf<String, Rect>()
        activity.setContent {
            SectionScaffold("Car link", onBack = {}) {
                CodePad(triesLeft = 2, onSubmit = {}, onKeyPlaced = { k, r -> placed[k] = r })
            }
        }
        ShadowLooper.idleMainLooper()

        val density = activity.resources.displayMetrics.density
        val windowW = activity.window.decorView.width.toFloat()
        val windowH = activity.window.decorView.height.toFloat()
        assertEquals(800f, windowW / density, 1f)
        assertEquals(456f, windowH / density, 1f)

        assertEquals(listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "DEL", "0", "OK"), placed.keys.toList())
        placed.forEach { (k, r) ->
            assertTrue("$k is ${r.width / density} dp wide", r.width / density >= AriaDimens.driveTouchMin.value - 0.5f)
            assertTrue("$k is ${r.height / density} dp tall", r.height / density >= AriaDimens.driveTouchMin.value - 0.5f)
            assertTrue("$k at $r is off the ${windowW}x$windowH window", r.left >= 0f && r.top >= 0f && r.right <= windowW && r.bottom <= windowH)
        }
        val keys = placed.values.toList()
        for (i in keys.indices) for (j in i + 1 until keys.size) {
            assertTrue("keys $i and $j overlap", !keys[i].overlaps(keys[j]))
        }
    }
}

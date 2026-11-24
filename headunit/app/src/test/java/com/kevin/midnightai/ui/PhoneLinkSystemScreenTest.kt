package com.kevin.midnightai.ui

import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.kevin.midnightai.bluetooth.PhoneLinkManager
import com.kevin.midnightai.util.ExternalScreen
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * Settings > Phones & audio > Phones, on a head unit whose ROM has no Bluetooth pairing or settings
 * activity (the real one: phones are owned by a vendor app, and `ACTION_REQUEST_DISCOVERABLE` and
 * `ACTION_BLUETOOTH_SETTINGS` resolve to nothing). Tapping PAIR NEW PHONE used to throw
 * [android.content.ActivityNotFoundException] out of the tap and kill the launcher; the driver got
 * the home screen crashing when he asked to pair his phone. `checkActivities` is what makes
 * Robolectric refuse an unresolvable intent the way a device does, so this test fails on the old
 * code and the words below are what it must say instead.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w800dp-h456dp-land-mdpi")
class PhoneLinkSystemScreenTest {
    @org.junit.After fun closeActivities() = TestActivities.closeAll()

    @Test fun `pair new phone with no pairing screen on this unit says so and does not crash`() =
        assertTapSays("PAIR NEW PHONE", ExternalScreen.PAIR_NEW_PHONE.missingWords())

    @Test fun `system settings with no settings screen on this unit says so and does not crash`() =
        assertTapSays("SYSTEM SETTINGS", ExternalScreen.BLUETOOTH_SETTINGS.missingWords())

    private fun assertTapSays(label: String, expected: String) {
        shadowOf(RuntimeEnvironment.getApplication()).checkActivities(true)
        val activity = TestActivities.start()
        activity.setContent { BluetoothManagerScreen(onBack = {}) }
        idle()
        clickable(activity).getValue(label).invoke()
        idle()
        assertEquals(expected, PhoneLinkManager.state.value.lastResult)
    }

    private fun idle() = repeat(5) { ShadowLooper.idleMainLooper(20, TimeUnit.MILLISECONDS) }

    /** Every on-screen text that carries a click action, by its text. */
    private fun clickable(activity: androidx.activity.ComponentActivity): Map<String, () -> Unit> {
        fun root(v: View): ViewRootForTest? = (v as? ViewRootForTest)
            ?: (v as? ViewGroup)?.let { g -> (0 until g.childCount).firstNotNullOfOrNull { root(g.getChildAt(it)) } }
        val out = linkedMapOf<String, () -> Unit>()
        fun walk(n: SemanticsNode) {
            val text = n.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }
            val click = n.config.getOrNull(SemanticsActions.OnClick)?.action
            if (text != null && click != null) out[text] = { click() }
            n.children.forEach { walk(it) }
        }
        walk(root(activity.window.decorView)!!.semanticsOwner.unmergedRootSemanticsNode)
        return out
    }
}

package com.kevin.midnightai.ui

import androidx.activity.compose.setContent
import com.kevin.midnightai.bluetooth.PhoneLinkManager
import com.kevin.midnightai.util.ExternalScreen
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Settings > Phones & audio > Phones, on a head unit whose ROM has no Bluetooth pairing or settings
 * activity (the real one: phones are owned by a vendor app, and `ACTION_REQUEST_DISCOVERABLE` and
 * `ACTION_BLUETOOTH_SETTINGS` resolve to nothing). Tapping PAIR NEW PHONE used to throw
 * [android.content.ActivityNotFoundException] out of the tap and kill the launcher; the driver got
 * the home screen crashing when he asked to pair his phone. `checkActivities` is what makes
 * Robolectric refuse an unresolvable intent the way a device does, so this test fails on the old
 * code and the words below are what it must say instead. The tree walk is [ComposeTaps], shared
 * with the tray's own door test.
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
        ComposeTaps.idle()
        ComposeTaps.clickable(activity).getValue(label).invoke()
        ComposeTaps.idle()
        assertEquals(expected, PhoneLinkManager.state.value.lastResult)
    }
}

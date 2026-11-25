package com.kevin.midnightai.ui

import androidx.activity.compose.setContent
import com.kevin.midnightai.util.ExternalScreen
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Settings > System > Permissions > Microphone & location > Grant, on a ROM that cannot show a
 * permission dialog.
 *
 * The launch is already inside [com.kevin.midnightai.util.ExternalDoor], so this tap cannot crash
 * the launcher. Its words were thrown away: the callback was a `() -> Unit`, which made it the one
 * door with a screen to show a sentence on and no wire to carry it, and a dead tap is the thing a
 * home app may never give (root `CLAUDE.md` section 1). The door's own words are substituted here,
 * because what is under test is the wire, not which sentence the ROM earns.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w800dp-h456dp-land-mdpi")
class PermissionRowDoorTest {
    @org.junit.After fun closeActivities() = TestActivities.closeAll()

    @Test fun `granting the microphone with no permission screen says so`() {
        val words = ExternalScreen.PERMISSION_PROMPT.failedWords()
        val activity = TestActivities.start()
        activity.setContent {
            ControlPanel(onRequestRuntimePermissions = { words }, startInSetup = true)
        }
        ComposeTaps.idle()

        ComposeTaps.clickable(activity).getValue("Grant").invoke()
        ComposeTaps.idle()

        assertTrue(
            "the permission door's words never reached the row that was tapped",
            ComposeTaps.texts(activity).contains(words),
        )
    }
}

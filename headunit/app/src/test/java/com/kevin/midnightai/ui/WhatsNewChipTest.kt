package com.kevin.midnightai.ui

import androidx.activity.compose.setContent
import com.kevin.midnightai.update.WhatsNewSeen
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Ticket 467, head unit: the Cruise chip says "TAP FOR WHAT'S NEW", so the tap has to land on the
 * What's new notes, and the version counts as seen only once notes were actually put on screen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w800dp-h456dp-land-mdpi")
class WhatsNewChipTest {
    @After fun closeActivities() = TestActivities.closeAll()

    private val prompt = UpdatePrompt("UPDATED, TAP FOR WHAT'S NEW", "line", tappable = true, whatsNew = true)

    @Test fun `tapping the chip opens what's new and does not burn the once`() {
        val context = RuntimeEnvironment.getApplication()
        var opened = 0
        val activity = TestActivities.start()
        activity.setContent { UpdateChip(prompt, onWhatsNew = { opened++ }) }
        ComposeTaps.idle()

        ComposeTaps.clickable(activity).getValue("UPDATED, TAP FOR WHAT'S NEW").invoke()
        ComposeTaps.idle()

        assertEquals(1, opened)
        assertEquals("seen is marked by the notes being shown, not by the tap", 0L, WhatsNewSeen(context).code)
    }

    @Test fun `the chip's entry opens the control panel on the what's new section, and a failed load is not seen`() {
        val context = RuntimeEnvironment.getApplication()
        val activity = TestActivities.start()
        activity.setContent {
            ControlPanel(onRequestRuntimePermissions = { null }, startInWhatsNew = true)
        }
        val failure = "Could not load the release notes"
        ComposeTaps.waitFor { ComposeTaps.texts(activity).any { it.startsWith(failure) } }

        val sentence = ComposeTaps.texts(activity).firstOrNull { it.startsWith(failure) }
        assertTrue("What's new did not open from the chip's entry: ${ComposeTaps.texts(activity)}", sentence != null)
        assertTrue(sentence, sentence!!.contains("not signed in"))
        assertFalse(sentence, sentence.contains("reach"))
        assertEquals("a failed load must not use up the once", 0L, WhatsNewSeen(context).code)
    }
}

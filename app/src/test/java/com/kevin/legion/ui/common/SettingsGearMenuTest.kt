package com.kevin.legion.ui.common

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ActivityNotFoundException
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.kevin.legion.ui.theme.soft.SoftTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Home-launcher ticket 07 section 1: the gear is a dropdown with two worded items. */
@RunWith(RobolectricTestRunner::class)
class SettingsGearMenuTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private var phone = 0
    private var legion = 0

    private fun show(withPhone: Boolean = true) {
        rule.setContent {
            SoftTheme {
                StatusLine(
                    synced = true,
                    obdConnected = false,
                    clock = "09:00",
                    onOpenSettings = { legion++ },
                    onOpenPhoneSettings = if (withPhone) ({ phone++ }) else null,
                )
            }
        }
    }

    @Test
    fun `the gear opens a menu with both items, subtitles included`() {
        show()
        rule.onNodeWithContentDescription("Settings").performClick()
        rule.onNodeWithText("Phone settings").assertIsDisplayed()
        rule.onNodeWithText("Wi-Fi, display, battery").assertIsDisplayed()
        rule.onNodeWithText("LEGION settings").assertIsDisplayed()
        rule.onNodeWithText("Assistant, connections, privacy").assertIsDisplayed()
        // The gear alone did not navigate anywhere.
        assertEquals(0, phone + legion)
    }

    @Test
    fun `each item runs its own callback and closes the menu`() {
        show()
        rule.onNodeWithContentDescription("Settings").performClick()
        rule.onNodeWithText("Phone settings").performClick()
        assertEquals(1, phone)
        assertEquals(0, legion)
        rule.onNodeWithContentDescription("Settings").performClick()
        rule.onNodeWithText("LEGION settings").performClick()
        assertEquals(1, legion)
        rule.onAllNodesWithTextCount("Phone settings").let { assertEquals(0, it) }
    }

    @Test
    fun `with no phone-settings callback the gear still goes straight to LEGION settings`() {
        show(withPhone = false)
        rule.onNodeWithContentDescription("Settings").performClick()
        assertEquals(1, legion)
    }

    @Test
    fun `a refused start of phone settings says so in words`() {
        val refusing = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun startActivity(intent: Intent?) = throw ActivityNotFoundException("no settings app")
        }
        val message = startPhoneSettings(refusing)
        assertNotNull(message)
        assertTrue(message!!.startsWith("Couldn't open the phone's settings"))
    }

    @Test
    fun `a start that is handed to the system returns no message`() {
        val ok = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            var started: Intent? = null
            override fun startActivity(intent: Intent?) { started = intent }
        }
        assertNull(startPhoneSettings(ok))
        assertEquals(android.provider.Settings.ACTION_SETTINGS, ok.started?.action)
        assertTrue(ok.started!!.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextCount(text: String): Int =
        onAllNodesWithText(text).fetchSemanticsNodes().size
}

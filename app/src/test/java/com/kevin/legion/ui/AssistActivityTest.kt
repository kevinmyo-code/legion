package com.kevin.legion.ui

import android.app.Application
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.kevin.legion.service.AriaForegroundService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The side key while the phone is locked (2026-09-27). Before AssistActivity, the request parked
 * behind the lock screen and opened the mic on Kevin's NEXT unlock. These pin that it waits for the
 * unlock prompt, starts only once the phone is actually unlocked, and never before.
 */
@RunWith(RobolectricTestRunner::class)
class AssistActivityTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val keyguard get() = shadowOf(app.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager)

    private fun press() = Robolectric.buildActivity(AssistActivity::class.java, Intent(Intent.ACTION_ASSIST)).setup().get()

    @Test
    fun `unlocked, the one-shot conversation starts at once`() {
        keyguard.setKeyguardLocked(false)
        val activity = press()
        assertEquals(AriaForegroundService.ACTION_ASSIST_ONE_SHOT, shadowOf(app).nextStartedService?.action)
        assertTrue(activity.isFinishing)
    }

    @Test
    fun `locked, nothing starts until the phone is unlocked`() {
        keyguard.setKeyguardLocked(true)
        press()
        assertNull("the mic must not open on a locked phone", shadowOf(app).nextStartedService)

        keyguard.setKeyguardLocked(false) // the user clears the unlock prompt
        assertEquals(AriaForegroundService.ACTION_ASSIST_ONE_SHOT, shadowOf(app).nextStartedService?.action)
    }
}

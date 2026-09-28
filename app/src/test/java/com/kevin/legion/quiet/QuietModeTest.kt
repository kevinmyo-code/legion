package com.kevin.legion.quiet

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QuietModeTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val audio get() = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val nm get() = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    @Before
    fun reset() {
        app.getSharedPreferences("quiet_mode", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun `off puts back the ringer he had, not a default`() {
        shadowOf(nm).setNotificationPolicyAccessGranted(true)
        audio.ringerMode = AudioManager.RINGER_MODE_SILENT
        assertEquals(QuietMode.Result.On, QuietMode.enable(app))
        assertEquals(AudioManager.RINGER_MODE_VIBRATE, audio.ringerMode)
        assertEquals(QuietMode.Result.Off, QuietMode.disable(app))
        assertEquals(AudioManager.RINGER_MODE_SILENT, audio.ringerMode)
    }

    @Test
    fun `switching on twice does not strand the phone on vibrate`() {
        shadowOf(nm).setNotificationPolicyAccessGranted(true)
        audio.ringerMode = AudioManager.RINGER_MODE_NORMAL
        QuietMode.enable(app)
        QuietMode.enable(app) // a second "on" must not remember VIBRATE as the thing to go back to
        QuietMode.disable(app)
        assertEquals(AudioManager.RINGER_MODE_NORMAL, audio.ringerMode)
    }

    @Test
    fun `without DND access nothing changes and it says why`() {
        shadowOf(nm).setNotificationPolicyAccessGranted(false)
        audio.ringerMode = AudioManager.RINGER_MODE_NORMAL
        val r = QuietMode.enable(app)
        assertTrue(r is QuietMode.Result.Refused)
        assertEquals(QuietMode.NEEDS_ACCESS, (r as QuietMode.Result.Refused).message)
        assertEquals(AudioManager.RINGER_MODE_NORMAL, audio.ringerMode)
        assertFalse(QuietMode.isOn(app))
    }

    @Test
    fun `ringer bookkeeping`() {
        assertEquals(AudioManager.RINGER_MODE_SILENT,
            QuietState.ringerToRemember(isOn = false, current = AudioManager.RINGER_MODE_SILENT, alreadySaved = -1))
        assertEquals(AudioManager.RINGER_MODE_NORMAL,
            QuietState.ringerToRemember(isOn = true, current = AudioManager.RINGER_MODE_VIBRATE, alreadySaved = AudioManager.RINGER_MODE_NORMAL))
        assertEquals(AudioManager.RINGER_MODE_NORMAL, QuietState.ringerToRestore(-1))
    }
}

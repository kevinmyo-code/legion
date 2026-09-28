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

    @Test
    fun `off puts every volume back, and none stays at zero`() {
        // Kevin, 2026-09-27: "when i toggle it off, the phone stays quiet, like everything is at 0".
        shadowOf(nm).setNotificationPolicyAccessGranted(true)
        audio.ringerMode = AudioManager.RINGER_MODE_NORMAL
        audio.setStreamVolume(AudioManager.STREAM_RING, 5, 0)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, 10, 0)
        audio.setStreamVolume(AudioManager.STREAM_NOTIFICATION, 0, 0) // already silent before Quiet
        QuietMode.enable(app)
        // What vibrate does to the phone: ring and notification zeroed.
        audio.setStreamVolume(AudioManager.STREAM_RING, 0, 0)
        audio.setStreamVolume(AudioManager.STREAM_NOTIFICATION, 0, 0)
        QuietMode.disable(app)
        assertEquals("ring back to what it was", 5, audio.getStreamVolume(AudioManager.STREAM_RING))
        assertEquals("media back to what it was", 10, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
        val notifMax = audio.getStreamMaxVolume(AudioManager.STREAM_NOTIFICATION)
        assertEquals("a stream at 0 comes back at half", QuietState.volumeToRestore(0, notifMax),
            audio.getStreamVolume(AudioManager.STREAM_NOTIFICATION))
        assertTrue(audio.getStreamVolume(AudioManager.STREAM_NOTIFICATION) > 0)
    }

    @Test
    fun `volume rule - what it was, or half, never zero`() {
        assertEquals(6, QuietState.volumeToRestore(saved = 6, max = 15))
        assertEquals(8, QuietState.volumeToRestore(saved = 0, max = 15))
        assertEquals(8, QuietState.volumeToRestore(saved = -1, max = 15)) // never recorded
        assertEquals(15, QuietState.volumeToRestore(saved = 40, max = 15)) // clamped
        assertEquals(1, QuietState.volumeToRestore(saved = 0, max = 1))
    }
}

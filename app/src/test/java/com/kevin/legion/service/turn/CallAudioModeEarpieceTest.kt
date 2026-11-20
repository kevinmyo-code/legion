package com.kevin.legion.service.turn

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.AudioDeviceInfoBuilder

/**
 * Call audio on the earpiece: entered, switched mid-conversation without leaving the mode, and
 * restored exactly on exit, with [CallAudioMode.ownCommActive] marking LEGION's own mode throughout.
 */
@RunWith(RobolectricTestRunner::class)
class CallAudioModeEarpieceTest {

    private val audio = ApplicationProvider.getApplicationContext<Context>()
        .getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val earpiece = device(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)
    private val speaker = device(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)

    private fun device(type: Int) = AudioDeviceInfoBuilder.newBuilder().setType(type).build()

    @Before
    fun phone() {
        shadowOf(audio).setAvailableCommunicationDevices(listOf(earpiece, speaker))
        audio.mode = AudioManager.MODE_NORMAL
    }

    @Test
    fun `call mode enters on the earpiece and marks the mode as LEGION's own`() {
        val mode = CallAudioMode(audio)
        mode.enter(earpiece = true)
        assertEquals(AudioManager.MODE_IN_COMMUNICATION, audio.mode)
        assertEquals(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE, audio.communicationDevice?.type)
        assertTrue(mode.onEarpiece)
        assertTrue(CallAudioMode.ownCommActive)
        mode.exit("test")
    }

    @Test
    fun `speaker mode enters on the loudspeaker as before`() {
        val mode = CallAudioMode(audio)
        mode.enter()
        assertEquals(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, audio.communicationDevice?.type)
        assertFalse(mode.onEarpiece)
        mode.exit("test")
    }

    @Test
    fun `a flip mid-conversation switches the device and keeps the mode`() {
        val mode = CallAudioMode(audio)
        mode.enter()
        mode.retarget(earpiece = true)
        assertEquals(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE, audio.communicationDevice?.type)
        assertTrue(mode.onEarpiece)
        mode.retarget(earpiece = false)
        assertEquals(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, audio.communicationDevice?.type)
        assertFalse(mode.onEarpiece)
        assertEquals(AudioManager.MODE_IN_COMMUNICATION, audio.mode)
        assertTrue(CallAudioMode.ownCommActive)
        mode.exit("test")
    }

    @Test
    fun `exit restores the mode exactly, clears the device and the own-mode mark`() {
        val mode = CallAudioMode(audio)
        mode.enter(earpiece = true)
        mode.exit("closed")
        assertEquals(AudioManager.MODE_NORMAL, audio.mode)
        assertNull(audio.communicationDevice)
        assertFalse(mode.onEarpiece)
        assertFalse(mode.isActive)
        assertFalse(CallAudioMode.ownCommActive)
    }

    @Test
    fun `retarget outside call audio touches nothing`() {
        val mode = CallAudioMode(audio)
        mode.retarget(earpiece = true)
        assertEquals(AudioManager.MODE_NORMAL, audio.mode)
        assertNull(audio.communicationDevice)
        assertFalse(CallAudioMode.ownCommActive)
    }
}

package com.kevin.legion.service.cascade

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.PowerManager
import androidx.test.core.app.ApplicationProvider
import com.kevin.legion.service.turn.CallAudioMode
import com.kevin.legion.service.turn.OutputChoice
import com.kevin.legion.service.turn.VoiceOutlet
import com.kevin.legion.service.turn.VoiceOutput
import kotlinx.coroutines.runBlocking
import org.junit.After
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
import org.robolectric.shadows.ShadowPowerManager

/**
 * The conversation's side of the toggle: a flip re-applies inside call audio without leaving it,
 * asks for call audio only for the earpiece, keeps its hands off in a phone call, and the proximity
 * lock is held only on the earpiece and released on every exit.
 */
@RunWith(RobolectricTestRunner::class)
class EarpieceRouteTest {

    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var inCall = false
    private val route = EarpieceRoute(ctx, audio) { inCall }
    private var enterAsked = 0
    private val enter: suspend () -> Unit = { enterAsked++ }

    private fun device(type: Int) = AudioDeviceInfoBuilder.newBuilder().setType(type).build()

    @Before
    fun phone() {
        val earpiece = device(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)
        val speaker = device(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
        shadowOf(audio).setAvailableCommunicationDevices(listOf(earpiece, speaker))
        shadowOf(audio).setOutputDevices(listOf(earpiece, speaker))
        shadowOf(ctx.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .setIsWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, true)
        ShadowPowerManager.clearWakeLocks()
        audio.mode = AudioManager.MODE_NORMAL
    }

    @After
    fun speakerAgain() {
        route.stop()
        VoiceOutput.set(ctx, OutputChoice.SPEAKER)
    }

    @Test
    fun `a flip to call inside call audio moves to the earpiece and holds the proximity lock`() = runBlocking {
        val mode = CallAudioMode(audio).also { it.enter() }
        VoiceOutput.set(ctx, OutputChoice.CALL)
        route.reapply({ mode }, enter)
        assertTrue(mode.onEarpiece)
        assertEquals(0, enterAsked)
        assertTrue(ShadowPowerManager.getLatestWakeLock()!!.isHeld)
        mode.exit("test")
    }

    @Test
    fun `a flip back to speaker stays in call audio on the loudspeaker and lets the screen back`() = runBlocking {
        VoiceOutput.set(ctx, OutputChoice.CALL)
        val mode = CallAudioMode(audio).also { it.enter(earpiece = true) }
        route.sync(mode)
        VoiceOutput.set(ctx, OutputChoice.SPEAKER)
        route.reapply({ mode }, enter)
        assertFalse(mode.onEarpiece)
        assertTrue(mode.isActive)
        assertEquals(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, audio.communicationDevice?.type)
        assertFalse(ShadowPowerManager.getLatestWakeLock()!!.isHeld)
        mode.exit("test")
    }

    @Test
    fun `outside call audio only the earpiece asks to enter it`() = runBlocking {
        VoiceOutput.set(ctx, OutputChoice.SPEAKER)
        route.reapply({ null }, enter)
        assertEquals(0, enterAsked)
        VoiceOutput.set(ctx, OutputChoice.CALL)
        route.reapply({ null }, enter)
        assertEquals(1, enterAsked)
    }

    @Test
    fun `a midway entry into call audio takes the proximity lock on the new call audio`() = runBlocking {
        var session: CallAudioMode? = null
        VoiceOutput.set(ctx, OutputChoice.CALL)
        route.reapply({ session }) { session = CallAudioMode(audio).also { it.enter(earpiece = true) } }
        assertTrue(session!!.onEarpiece)
        assertTrue(ShadowPowerManager.getLatestWakeLock()!!.isHeld)
        session!!.exit("test")
    }

    @Test
    fun `a phone call on the line means hands off`() = runBlocking {
        inCall = true
        VoiceOutput.set(ctx, OutputChoice.CALL)
        assertEquals(VoiceOutlet.PHONE_CALL, route.outlet())
        route.reapply({ null }, enter)
        assertEquals(0, enterAsked)
        assertEquals(AudioManager.MODE_NORMAL, audio.mode)
        assertNull(ShadowPowerManager.getLatestWakeLock())
    }

    @Test
    fun `stop releases the proximity lock on every exit`() {
        VoiceOutput.set(ctx, OutputChoice.CALL)
        val mode = CallAudioMode(audio).also { it.enter(earpiece = true) }
        route.sync(mode)
        assertTrue(ShadowPowerManager.getLatestWakeLock()!!.isHeld)
        route.stop()
        assertFalse(ShadowPowerManager.getLatestWakeLock()!!.isHeld)
        route.stop()
        mode.exit("test")
    }
}

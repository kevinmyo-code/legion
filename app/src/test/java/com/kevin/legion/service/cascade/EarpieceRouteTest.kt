package com.kevin.legion.service.cascade

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.PowerManager
import androidx.test.core.app.ApplicationProvider
import com.kevin.legion.service.turn.CallAudioMode
import com.kevin.legion.service.turn.LiveOutput
import com.kevin.legion.service.turn.Opening
import com.kevin.legion.service.turn.OutputChoice
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
 * The conversation's side of the toggle: the earpiece only for a conversation opened by a tap in the
 * app (until he taps the toggle in another), a flip re-applied inside call audio without leaving it,
 * hands off in a phone call, the readback published for the toggle, and the proximity lock held only
 * while the platform routes to the earpiece and never again after stop.
 */
@RunWith(RobolectricTestRunner::class)
class EarpieceRouteTest {

    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var inCall = false
    private var screenOn = true
    private val route = EarpieceRoute(ctx, audio, { inCall }, { screenOn })
    private var enterAsked = 0
    private val enter: suspend () -> Unit = { enterAsked++ }

    private fun device(type: Int) = AudioDeviceInfoBuilder.newBuilder().setType(type).build()

    private fun lockHeld() = ShadowPowerManager.getLatestWakeLock()?.isHeld == true

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
        route.begin(Opening.APP_TAP)
    }

    @After
    fun speakerAgain() {
        route.stop()
        VoiceOutput.set(ctx, OutputChoice.SPEAKER)
    }

    @Test
    fun `CALL in a conversation opened by an app tap wants the earpiece`() {
        VoiceOutput.set(ctx, OutputChoice.CALL)
        assertTrue(route.wantsEarpiece())
    }

    @Test
    fun `CALL in any other opening, or with the screen off, stays on the normal route`() {
        VoiceOutput.set(ctx, OutputChoice.CALL)
        for (opening in Opening.entries - Opening.APP_TAP) {
            route.begin(opening)
            assertFalse("$opening", route.wantsEarpiece())
        }
        route.begin(Opening.APP_TAP)
        screenOn = false
        assertFalse("screen off", route.wantsEarpiece())
    }

    @Test
    fun `SPEAKER never wants the earpiece`() {
        assertFalse(route.wantsEarpiece())
    }

    @Test
    fun `a flip to call inside call audio moves to the earpiece and holds the proximity lock`() = runBlocking {
        val mode = CallAudioMode(audio).also { it.enter() }
        VoiceOutput.set(ctx, OutputChoice.CALL)
        route.reapply({ mode }, enter)
        assertTrue(mode.onEarpiece)
        assertEquals(0, enterAsked)
        assertTrue(lockHeld())
        assertEquals(LiveOutput(onEarpiece = true, refused = false), VoiceOutput.live.value)
        mode.exit("test")
    }

    @Test
    fun `a refused earpiece holds no lock and is published as refused`() = runBlocking {
        VoiceOutput.set(ctx, OutputChoice.CALL)
        val mode = CallAudioMode(audio) { AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }.also { it.enter(earpiece = true) }
        route.reapply({ mode }, enter)
        assertFalse(mode.onEarpiece)
        assertFalse(lockHeld())
        assertEquals(LiveOutput(onEarpiece = false, refused = true), VoiceOutput.live.value)
        mode.exit("test")
    }

    @Test
    fun `a flip back to speaker stays in call audio on the loudspeaker and lets the screen back`() = runBlocking {
        VoiceOutput.set(ctx, OutputChoice.CALL)
        val mode = CallAudioMode(audio).also { it.enter(earpiece = true) }
        route.sync(mode)
        assertTrue(lockHeld())
        VoiceOutput.set(ctx, OutputChoice.SPEAKER)
        route.reapply({ mode }, enter)
        assertFalse(mode.onEarpiece)
        assertTrue(mode.isActive)
        assertEquals(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, audio.communicationDevice?.type)
        assertFalse(lockHeld())
        mode.exit("test")
    }

    @Test
    fun `SPEAKER with the toggle untouched leaves call audio exactly as entered`() = runBlocking {
        val mode = CallAudioMode(audio).also { it.enter() }
        shadowOf(audio).setAvailableCommunicationDevices(listOf(device(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)))
        route.reapply({ mode }, enter)
        // Had the route re-selected, the speaker (no longer offered) could not be the device now.
        assertEquals(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, audio.communicationDevice?.type)
        assertEquals(0, enterAsked)
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
        assertTrue(lockHeld())
        session!!.exit("test")
    }

    @Test
    fun `a phone call on the line means hands off`() = runBlocking {
        inCall = true
        VoiceOutput.set(ctx, OutputChoice.CALL)
        route.reapply({ null }, enter)
        assertEquals(0, enterAsked)
        assertEquals(AudioManager.MODE_NORMAL, audio.mode)
        assertNull(ShadowPowerManager.getLatestWakeLock())
    }

    @Test
    fun `stop releases the proximity lock on every exit and nothing takes it again`() = runBlocking {
        VoiceOutput.set(ctx, OutputChoice.CALL)
        val mode = CallAudioMode(audio).also { it.enter(earpiece = true) }
        route.sync(mode)
        assertTrue(lockHeld())
        route.stop()
        assertFalse(lockHeld())
        assertNull("the toggle stops reading a closed conversation", VoiceOutput.live.value)
        // A device-change job or a crash-path release landing after stop: no lock, no entry.
        route.sync(mode)
        route.reapply({ mode }, enter)
        assertFalse(lockHeld())
        assertEquals(0, enterAsked)
        route.stop()
        mode.exit("test")
    }
}

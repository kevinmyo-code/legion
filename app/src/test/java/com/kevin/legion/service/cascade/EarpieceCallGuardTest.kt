package com.kevin.legion.service.cascade

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.PowerManager
import androidx.test.core.app.ApplicationProvider
import com.kevin.legion.service.turn.CallAudioMode
import com.kevin.legion.service.turn.Opening
import com.kevin.legion.service.turn.VoiceOutput
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
 * The call's earpiece and the call guard ([CallPause]) together. Every state a call's own call
 * audio produces (entering it, switching speaker and earpiece mid-call, a midway entry,
 * leaving it) is read the way the guard reads the phone and fed through its two-reading debounce: none
 * may close the conversation. A real call while on the earpiece closes it quietly (no chime), and the
 * release puts the route, the mode and the screen back.
 */
@RunWith(RobolectricTestRunner::class)
class EarpieceCallGuardTest {

    private val ctx = ApplicationProvider.getApplicationContext<Context>()
    private val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val route = EarpieceRoute(ctx, audio, call = true, inCall = { false }, screenOn = { true })
    private val debounce = CallPause.Debounce()

    private fun device(type: Int) = AudioDeviceInfoBuilder.newBuilder().setType(type).build()

    /** One poll as [CallWatch] reads it, with our own voice playing and our own mic capturing. */
    private fun reading(cellularCall: Boolean = false) = CallPause.Signals(
        cellularCall = cellularCall,
        ringing = false,
        telecomCall = false,
        audioMode = audio.mode,
        ownComm = CallAudioMode.ownCommActive,
        otherCallAudio = true,
        callCapture = true,
    )

    /** Feeds one reading twice (the guard fires on two in a row) and returns what it fired. */
    private fun poll(cellularCall: Boolean = false): CallPause.Reason? {
        debounce.feed(CallPause.reason(reading(cellularCall)))
        return debounce.feed(CallPause.reason(reading(cellularCall)))
    }

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
    }

    @Test
    fun `entering call audio and moving speaker and earpiece mid-call never trips the guard`() = runBlocking {
        val mode = CallAudioMode(audio)
        assertNull(poll())
        mode.enter()
        assertNull("speaker call audio", poll())
        route.reapply({ mode }, {})
        assertTrue(mode.onEarpiece)
        assertNull("switched to the earpiece", poll())
        route.askRoute(earpiece = false)
        route.reapply({ mode }, {})
        assertFalse(mode.onEarpiece)
        assertNull("back on the speaker", poll())
        mode.exit("test")
        assertNull("left call audio", poll())
    }

    @Test
    fun `a midway entry onto the earpiece and its exit never trip the guard`() = runBlocking {
        var session: CallAudioMode? = null
        route.reapply({ session }) { session = CallAudioMode(audio).also { it.enter(earpiece = true) } }
        assertTrue(session!!.onEarpiece)
        assertNull(poll())
        session!!.exit("test")
        assertNull(poll())
    }

    @Test
    fun `the own-mode mark brackets the mode on the way in and the way out`() {
        // Each half of the bracket as one reading: the mode never reads as another app's.
        val half = CallPause.Signals(
            cellularCall = false,
            ringing = false,
            telecomCall = false,
            audioMode = AudioManager.MODE_NORMAL,
            ownComm = true,
        )
        assertNull("marked, mode not yet changed", CallPause.reason(half))
        val inside = half.copy(audioMode = AudioManager.MODE_IN_COMMUNICATION)
        assertNull("in our own mode", CallPause.reason(inside))
        val mode = CallAudioMode(audio).also { it.enter(earpiece = true) }
        mode.exit("test")
        assertEquals(AudioManager.MODE_NORMAL, audio.mode)
        assertFalse("unmarked once the mode is back", CallAudioMode.ownCommActive)
    }

    @Test
    fun `a real call on the earpiece closes quietly and the release puts everything back`() = runBlocking {
        val mode = CallAudioMode(audio).also { it.enter(earpiece = true) }
        route.sync(mode)
        assertTrue(ShadowPowerManager.getLatestWakeLock()!!.isHeld)

        val fired = poll(cellularCall = true)
        assertEquals(CallPause.Reason.CALL, fired)
        val close = CallPause.closeReason(fired!!)
        val skip = CloseCue.decide(
            close,
            quiet = false,
            onCall = true,
            audioOpened = true,
            alreadyPlayed = false,
            rateMatches = true,
        )
        assertNotNull("no closing chime into a call", skip)

        // releaseNow's order: the earpiece follower, then call audio.
        route.stop()
        mode.exit("cascade $close")
        assertFalse("screen back", ShadowPowerManager.getLatestWakeLock()!!.isHeld)
        assertNull("the UI stops reading the closed conversation", VoiceOutput.live.value)
        assertEquals(AudioManager.MODE_NORMAL, audio.mode)
        assertNull("no stuck earpiece", audio.communicationDevice)
        assertFalse("no stuck own communication mode", CallAudioMode.ownCommActive)
    }
}

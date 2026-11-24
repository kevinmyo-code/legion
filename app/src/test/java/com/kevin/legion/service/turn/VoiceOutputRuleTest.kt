package com.kevin.legion.service.turn

import android.media.AudioDeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The earpiece's decisions: which conversations may use it, which way Alfred's voice goes for a call
 * and for a plain tap on each connected device and call state, and which device call audio selects.
 */
class VoiceOutputRuleTest {

    private fun outlet(
        call: Boolean,
        route: AudioRoute = AudioRoute.PHONE_SPEAKER,
        inCall: Boolean = false,
        earpiece: Boolean = true,
    ) = VoiceOutputRule.outlet(call, route, inCall, earpiece)

    @Test
    fun `every opening kind is named from what the session knows`() {
        fun op(hand: Boolean = false, wake: Boolean = false, opener: Boolean = false, test: Boolean = false) =
            VoiceOutputRule.opening(inHand = hand, fromWakeWord = wake, hasOpener = opener, test = test)
        assertEquals(Opening.APP_TAP, op(hand = true))
        assertEquals(Opening.WAKE_WORD, op(wake = true))
        // Brief me, a check-in, a proactive line that listens, the incoming-call announcement.
        assertEquals(Opening.LINE, op(opener = true))
        // A car media button, a notification's Talk, a quick start: a tap, but not in the app.
        assertEquals(Opening.OTHER_TAP, op())
        assertEquals(Opening.TEST, op(test = true))
        // An opener always wins over a flag that came with it.
        assertEquals(Opening.LINE, op(hand = true, opener = true))
    }

    @Test
    fun `only a tap in the app with the screen on may use the earpiece`() {
        for (opening in Opening.entries) {
            val allowed = VoiceOutputRule.earpieceAllowed(opening, true)
            assertEquals("$opening, screen on", opening == Opening.APP_TAP, allowed)
            assertFalse("$opening, screen off", VoiceOutputRule.earpieceAllowed(opening, false))
        }
    }

    @Test
    fun `a call puts him on the earpiece, a plain tap on the loudspeaker`() {
        assertEquals(VoiceOutlet.EARPIECE, outlet(call = true))
        assertEquals(VoiceOutlet.LOUDSPEAKER, outlet(call = false))
    }

    @Test
    fun `a headset or Bluetooth wins over a call and over a plain tap`() {
        for (call in listOf(true, false)) {
            assertEquals(VoiceOutlet.WIRED, outlet(call, AudioRoute.WIRED_HEADSET))
            assertEquals(VoiceOutlet.BLUETOOTH, outlet(call, AudioRoute.BLUETOOTH))
        }
    }

    @Test
    fun `a phone call on the line wins over everything, so routing is never touched`() {
        for (call in listOf(true, false)) {
            for (route in AudioRoute.entries) {
                assertEquals(VoiceOutlet.PHONE_CALL, outlet(call, route, inCall = true))
            }
        }
    }

    @Test
    fun `a phone with no earpiece stays on the loudspeaker even for a call`() {
        assertEquals(VoiceOutlet.LOUDSPEAKER, outlet(call = true, earpiece = false))
    }

    @Test
    fun `call audio is entered for barge-in as before, and for the earpiece even with barge-in off`() {
        assertTrue(VoiceOutputRule.callAudio(barge = true, earpiece = false))
        assertTrue(VoiceOutputRule.callAudio(barge = false, earpiece = true))
        assertFalse(VoiceOutputRule.callAudio(barge = false, earpiece = false))
    }

    @Test
    fun `the device pick puts a wired headset first, then the earpiece only when asked, else the loudspeaker`() {
        val ear = AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
        val spk = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        val wired = AudioDeviceInfo.TYPE_WIRED_HEADSET
        assertEquals(ear, VoiceOutputRule.device(listOf(ear, spk), earpiece = true))
        assertEquals(spk, VoiceOutputRule.device(listOf(ear, spk), earpiece = false))
        assertEquals(wired, VoiceOutputRule.device(listOf(ear, spk, wired), earpiece = true))
        assertEquals("no earpiece offered: the loudspeaker", spk, VoiceOutputRule.device(listOf(spk), earpiece = true))
        assertNull(VoiceOutputRule.device(emptyList(), earpiece = true))
    }
}

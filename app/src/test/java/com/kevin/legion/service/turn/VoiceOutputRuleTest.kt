package com.kevin.legion.service.turn

import android.media.AudioDeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The earpiece toggle's decisions: which conversations may use the earpiece, which way Alfred's voice
 * goes for each choice, connected device and call state, what the toggle shows (the real route), and
 * which device call audio selects.
 */
class VoiceOutputRuleTest {

    private fun outlet(
        choice: OutputChoice,
        route: AudioRoute = AudioRoute.PHONE_SPEAKER,
        inCall: Boolean = false,
        earpiece: Boolean = true,
    ) = VoiceOutputRule.outlet(choice, route, inCall, earpiece)

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
    fun `call mode puts him on the earpiece, speaker mode on the loudspeaker`() {
        assertEquals(VoiceOutlet.EARPIECE, outlet(OutputChoice.CALL))
        assertEquals(VoiceOutlet.LOUDSPEAKER, outlet(OutputChoice.SPEAKER))
    }

    @Test
    fun `a headset or Bluetooth wins over either choice`() {
        for (choice in OutputChoice.entries) {
            assertEquals(VoiceOutlet.WIRED, outlet(choice, AudioRoute.WIRED_HEADSET))
            assertEquals(VoiceOutlet.BLUETOOTH, outlet(choice, AudioRoute.BLUETOOTH))
        }
    }

    @Test
    fun `a phone call on the line wins over everything, so routing is never touched`() {
        for (choice in OutputChoice.entries) {
            for (route in AudioRoute.entries) {
                assertEquals(VoiceOutlet.PHONE_CALL, outlet(choice, route, inCall = true))
            }
        }
    }

    @Test
    fun `a phone with no earpiece stays on the loudspeaker in call mode`() {
        assertEquals(VoiceOutlet.LOUDSPEAKER, outlet(OutputChoice.CALL, earpiece = false))
    }

    @Test
    fun `call audio is entered for barge-in as before, and for the earpiece even with barge-in off`() {
        assertTrue(VoiceOutputRule.callAudio(barge = true, earpiece = false))
        assertTrue(VoiceOutputRule.callAudio(barge = false, earpiece = true))
        assertFalse(VoiceOutputRule.callAudio(barge = false, earpiece = false))
    }

    @Test
    fun `the toggle shows the choice and flips while nothing else decides`() {
        val speaker = VoiceOutputRule.toggle(OutputChoice.SPEAKER, AudioRoute.PHONE_SPEAKER, false, true)!!
        assertTrue(speaker.enabled)
        assertEquals("Speaker", speaker.label)
        val call = VoiceOutputRule.toggle(OutputChoice.CALL, AudioRoute.PHONE_SPEAKER, false, true)!!
        assertTrue(call.enabled)
        assertEquals("Call", call.label)
        assertEquals(OutputChoice.CALL, call.choice)
        assertEquals(OutputChoice.CALL, VoiceOutputRule.flipped(OutputChoice.SPEAKER))
        assertEquals(OutputChoice.SPEAKER, VoiceOutputRule.flipped(OutputChoice.CALL))
    }

    @Test
    fun `the toggle says in words what holds the route, and is disabled`() {
        val cases = mapOf(
            Triple(AudioRoute.WIRED_HEADSET, false, OutputChoice.CALL) to "Headphones",
            Triple(AudioRoute.BLUETOOTH, false, OutputChoice.SPEAKER) to "Bluetooth",
            Triple(AudioRoute.PHONE_SPEAKER, true, OutputChoice.CALL) to "On a call",
        )
        for ((case, words) in cases) {
            val t = VoiceOutputRule.toggle(case.third, case.first, case.second, true)!!
            assertFalse("$case must be disabled", t.enabled)
            assertEquals(words, t.label)
            assertEquals("the saved choice is kept underneath", case.third, t.choice)
        }
    }

    @Test
    fun `a refused earpiece reads Earpiece unavailable on the speaker, and a tap gives speaker mode`() {
        val live = LiveOutput(onEarpiece = false, refused = true)
        val t = VoiceOutputRule.toggle(OutputChoice.CALL, AudioRoute.PHONE_SPEAKER, false, true, live)!!
        assertEquals("Earpiece unavailable", t.label)
        assertEquals(VoiceOutlet.LOUDSPEAKER, t.outlet)
        assertTrue(t.detail.contains("speaker"))
        assertEquals("a tap stops asking for the earpiece", OutputChoice.SPEAKER, VoiceOutputRule.flipped(t.choice))
    }

    @Test
    fun `CALL saved in a conversation not on the earpiece shows Speaker, and a tap moves it`() {
        val live = LiveOutput(onEarpiece = false, refused = false)
        val t = VoiceOutputRule.toggle(OutputChoice.CALL, AudioRoute.PHONE_SPEAKER, false, true, live)!!
        assertEquals("Speaker", t.label)
        assertEquals(OutputChoice.SPEAKER, t.choice)
        val next = VoiceOutputRule.flipped(t.choice)
        assertEquals("a tap saves CALL and moves this conversation", OutputChoice.CALL, next)
    }

    @Test
    fun `on the earpiece in a conversation the toggle says Call`() {
        val live = LiveOutput(onEarpiece = true, refused = false)
        val t = VoiceOutputRule.toggle(OutputChoice.CALL, AudioRoute.PHONE_SPEAKER, false, true, live)!!
        assertEquals("Call", t.label)
    }

    @Test
    fun `no earpiece hides the toggle`() {
        assertNull(VoiceOutputRule.toggle(OutputChoice.SPEAKER, AudioRoute.PHONE_SPEAKER, false, false))
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

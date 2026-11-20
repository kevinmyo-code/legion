package com.kevin.legion.service.turn

import android.media.AudioDeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The earpiece toggle's decisions: which way Alfred's voice goes for each choice, connected device
 * and call state, what the toggle beside the orb shows, and which device call audio selects.
 */
class VoiceOutputRuleTest {

    private fun outlet(choice: OutputChoice, route: AudioRoute = AudioRoute.PHONE_SPEAKER, inCall: Boolean = false, earpiece: Boolean = true) =
        VoiceOutputRule.outlet(choice, route, inCall, earpiece)

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
        assertTrue(VoiceOutputRule.callAudio(barge = true, VoiceOutlet.LOUDSPEAKER))
        assertTrue(VoiceOutputRule.callAudio(barge = false, VoiceOutlet.EARPIECE))
        assertFalse(VoiceOutputRule.callAudio(barge = false, VoiceOutlet.LOUDSPEAKER))
        assertFalse(VoiceOutputRule.callAudio(barge = false, VoiceOutlet.WIRED))
        assertFalse(VoiceOutputRule.callAudio(barge = false, VoiceOutlet.BLUETOOTH))
        assertFalse(VoiceOutputRule.callAudio(barge = false, VoiceOutlet.PHONE_CALL))
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

package com.kevin.legion.service.cascade

import com.kevin.legion.service.turn.VoiceOutlet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the in-call screen says (plan 1.3): `Connecting...` until the pickup is heard, then the
 * elapsed time as a phone shows it, and three controls whose words match the route the call is
 * really on. Pure, so every case is here rather than on a device.
 */
class InCallRuleTest {

    @Test
    fun `the status line says connecting until the call is answered`() {
        assertEquals(InCallRule.CONNECTING, InCallRule.status(answeredAtMs = null, nowMs = 1_000))
        assertEquals("00:00", InCallRule.status(answeredAtMs = 1_000, nowMs = 1_000))
    }

    @Test
    fun `the timer counts from the pickup, not from the tap`() {
        // Pressed at 1_000, answered at 3_500: five seconds later the call is 5 seconds old, not 7.5.
        assertEquals("00:05", InCallRule.status(answeredAtMs = 3_500, nowMs = 8_500))
    }

    @Test
    fun `the timer reads mm colon ss, and minutes are not folded into hours`() {
        assertEquals("00:00", InCallRule.elapsed(0))
        assertEquals("00:09", InCallRule.elapsed(9_400))
        assertEquals("01:00", InCallRule.elapsed(60_000))
        assertEquals("09:59", InCallRule.elapsed(599_000))
        assertEquals("63:20", InCallRule.elapsed(3_800_000))
    }

    @Test
    fun `a clock that went backwards reads zero, never a negative`() {
        assertEquals("00:00", InCallRule.elapsed(-5_000))
    }

    @Test
    fun `mute says which way it goes and is always pressable`() {
        val off = InCallRule.mute(muted = false)
        assertEquals("Mute", off.label)
        assertTrue(off.enabled)
        val on = InCallRule.mute(muted = true)
        assertEquals("Unmute", on.label)
        assertTrue("a call he cannot unmute is a call he cannot come back to", on.enabled)
        assertTrue("TalkBack says he is not heard", on.detail.contains("cannot hear you"))
    }

    @Test
    fun `speaker offers the move the call is not already on`() {
        assertEquals("Speaker", InCallRule.speaker(VoiceOutlet.EARPIECE).label)
        assertEquals("Earpiece", InCallRule.speaker(VoiceOutlet.LOUDSPEAKER).label)
        assertTrue(InCallRule.speaker(VoiceOutlet.EARPIECE).enabled)
        assertTrue(InCallRule.speaker(VoiceOutlet.LOUDSPEAKER).enabled)
    }

    @Test
    fun `speaker never claims a move a headset or a phone call has already taken`() {
        for (outlet in listOf(VoiceOutlet.BLUETOOTH, VoiceOutlet.WIRED, VoiceOutlet.PHONE_CALL)) {
            val control = InCallRule.speaker(outlet)
            assertFalse("$outlet is hands off", control.enabled)
            assertTrue("$outlet says why", control.detail.isNotBlank())
        }
        assertEquals("Headset", InCallRule.speaker(VoiceOutlet.BLUETOOTH).label)
        assertEquals("Headphones", InCallRule.speaker(VoiceOutlet.WIRED).label)
    }

    @Test
    fun `hang up is a word, and no control says a colour carries it`() {
        val hangUp = InCallRule.hangUp()
        assertEquals("Hang up", hangUp.label)
        assertTrue(hangUp.enabled)
        // ADR 0081 spends the one accent on flags: nothing here may lean on red.
        for (control in listOf(hangUp, InCallRule.mute(false), InCallRule.speaker(VoiceOutlet.EARPIECE))) {
            assertNull("no colour word in ${control.label}", Regex("\\bred\\b").find(control.detail))
        }
    }

    /** Kevin, 2026-11-28: the controls are a dialer's icons, lit while on. */
    @Test
    fun `each control has a dialer's icon, lit while it is on`() {
        assertEquals(CallGlyph.MIC_OFF, InCallRule.mute(false).glyph)
        assertFalse(InCallRule.mute(false).active)
        assertTrue("muted lights the mute button", InCallRule.mute(true).active)
        assertEquals(CallGlyph.SPEAKER, InCallRule.speaker(VoiceOutlet.EARPIECE).glyph)
        assertFalse(InCallRule.speaker(VoiceOutlet.EARPIECE).active)
        assertTrue("the loudspeaker lights the speaker button", InCallRule.speaker(VoiceOutlet.LOUDSPEAKER).active)
        assertEquals(CallGlyph.SPEAKER, InCallRule.speaker(VoiceOutlet.LOUDSPEAKER).glyph)
        assertEquals(CallGlyph.HEADPHONES, InCallRule.speaker(VoiceOutlet.BLUETOOTH).glyph)
        assertEquals(CallGlyph.HEADPHONES, InCallRule.speaker(VoiceOutlet.WIRED).glyph)
        assertEquals(CallGlyph.HANG_UP, InCallRule.hangUp().glyph)
    }
}

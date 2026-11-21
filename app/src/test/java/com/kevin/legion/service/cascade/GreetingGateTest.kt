@file:Suppress("MaxLineLength") // test fixtures read best on one line

package com.kevin.legion.service.cascade

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The held greeting's fate, read just before any of it is spoken. */
class GreetingGateTest {
    @Test
    fun `he spoke while it was held, so it is dropped and nothing is said over him`() {
        listOf(true to false, false to false, true to true).forEach { (whole, named) ->
            assertEquals(GreetingGate.HE_SPOKE, GreetingGate.decide(heSpoke = true, whole = whole, named = named))
        }
        assertFalse(GreetingGate.HE_SPOKE.scripted)
        assertEquals("greeting_dropped reason=he_spoke", GreetingGate.HE_SPOKE.logLine)
    }

    @Test
    fun `not whole in time or a wrong name plays the scripted hello`() {
        assertEquals(GreetingGate.SLOW, GreetingGate.decide(heSpoke = false, whole = false, named = false))
        assertEquals(GreetingGate.NAMED, GreetingGate.decide(heSpoke = false, whole = true, named = true))
        assertTrue(GreetingGate.SLOW.scripted)
        assertTrue(GreetingGate.NAMED.scripted)
        assertEquals("greeting_name_blocked", GreetingGate.NAMED.logLine)
    }

    @Test
    fun `a dropped greeting plays the earcon unless he is talking right now`() {
        assertTrue(GreetingGate.earconAfterDrop(nowMs = 10_000, lastSpeechAtMs = 10_000 - GreetingGate.MID_SPEECH_MS - 1))
        assertFalse(GreetingGate.earconAfterDrop(nowMs = 10_000, lastSpeechAtMs = 10_000 - GreetingGate.MID_SPEECH_MS))
        assertFalse(GreetingGate.earconAfterDrop(nowMs = 10_000, lastSpeechAtMs = 9_900))
        assertTrue("no speech heard yet counts as quiet", GreetingGate.earconAfterDrop(nowMs = 10_000, lastSpeechAtMs = 0))
        assertEquals("greeting_dropped reason=he_spoke earcon=yes", GreetingGate.HE_SPOKE.logLine(earcon = true))
        assertEquals("greeting_dropped reason=he_spoke earcon=no", GreetingGate.HE_SPOKE.logLine(earcon = false))
        assertEquals("greeting_name_blocked", GreetingGate.NAMED.logLine(earcon = false))
        assertNull(GreetingGate.SPEAK.logLine(earcon = false))
    }

    @Test
    fun `a whole line with no wrong name is spoken, with nothing logged`() {
        assertEquals(GreetingGate.SPEAK, GreetingGate.decide(heSpoke = false, whole = true, named = false))
        assertNull(GreetingGate.SPEAK.logLine)
        assertFalse(GreetingGate.SPEAK.scripted)
    }
}

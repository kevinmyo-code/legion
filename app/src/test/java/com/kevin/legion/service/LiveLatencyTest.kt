package com.kevin.legion.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveLatencyTest {

    @Test
    fun `cold start reports once per start`() {
        val l = LiveLatency()
        assertNull(l.setupComplete(500))
        l.start(1_000)
        assertEquals(850L, l.setupComplete(1_850))
        assertNull(l.setupComplete(2_000))
    }

    @Test
    fun `reply runs from the last transcript chunk to the first audio`() {
        val l = LiveLatency()
        l.inputTranscript(10_000)
        l.inputTranscript(10_400)
        assertEquals(1_100L, l.firstAudio(11_500))
        // Consumed: a second audio chunk in the same turn reports nothing.
        assertNull(l.firstAudio(11_600))
    }

    @Test
    fun `a turn with no transcript reports no reply time`() {
        val l = LiveLatency()
        assertNull(l.firstAudio(5_000))
    }

    @Test
    fun `a late transcript chunk does not seed the next turn`() {
        val l = LiveLatency()
        l.inputTranscript(1_000)
        l.firstAudio(2_000)
        l.inputTranscript(2_100)
        l.turnComplete()
        assertNull(l.firstAudio(9_000))
    }

    @Test
    fun `tool time is per call id and carries the name`() {
        val l = LiveLatency()
        l.toolCall("a", "ask_fleet", 100)
        l.toolCall("b", "get_weather", 150)
        assertEquals("get_weather" to 250L, l.toolResponse("b", 400))
        assertEquals("ask_fleet" to 900L, l.toolResponse("a", 1_000))
        assertNull(l.toolResponse("a", 1_100))
        assertNull(l.toolResponse("never", 1_100))
    }

    @Test
    fun `eot reply runs from the activityEnd to the first audio, once`() {
        val l = LiveLatency()
        assertNull(l.firstAudioAfterActivityEnd(5_000))
        l.activityEnd(10_000)
        assertEquals(640L, l.firstAudioAfterActivityEnd(10_640))
        assertNull(l.firstAudioAfterActivityEnd(10_700))
    }
}

package com.kevin.legion.service.speaker

import com.kevin.legion.service.speaker.SpeakerPolicy.Band
import com.kevin.legion.service.speaker.SpeakerPolicy.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The bands, shadow versus gated, and the log line. Scores are invented numbers. */
class SpeakerPolicyTest {
    private val failures = listOf(
        SpeakerResult.NoVoiceprint,
        SpeakerResult.ModelMissing,
        SpeakerResult.ModelLoading,
        SpeakerResult.NoAudio,
        SpeakerResult.Busy,
        SpeakerResult.TimedOut(300),
        SpeakerResult.Failed("IllegalStateException"),
    )

    @Test
    fun `the thresholds are the studied ones`() {
        assertEquals(0.45f, SpeakerPolicy.OPEN_AT)
        assertEquals(0.30f, SpeakerPolicy.IGNORE_BELOW)
        assertTrue(SpeakerPolicy.CHECK_DEADLINE_MS < 800)
    }

    @Test
    fun `band edges - at the open line opens, at the ignore line asks, below it ignores`() {
        assertEquals(Band.OPEN, SpeakerPolicy.band(0.45f))
        assertEquals(Band.OPEN, SpeakerPolicy.band(0.9f))
        assertEquals(Band.ASK, SpeakerPolicy.band(0.4499f))
        assertEquals(Band.ASK, SpeakerPolicy.band(0.30f))
        assertEquals(Band.IGNORE, SpeakerPolicy.band(0.2999f))
        assertEquals(Band.IGNORE, SpeakerPolicy.band(-0.5f))
    }

    @Test
    fun `the switch off is shadow, on is gated`() {
        assertEquals(Mode.SHADOW, SpeakerPolicy.mode(false))
        assertEquals(Mode.GATED, SpeakerPolicy.mode(true))
    }

    @Test
    fun `shadow never holds a wake back, whatever the score`() {
        for (s in listOf(0.9f, 0.4f, 0.1f)) {
            assertEquals(Band.OPEN, SpeakerPolicy.act(Mode.SHADOW, SpeakerResult.Scored(s, 40)))
        }
        failures.forEach { assertEquals(Band.OPEN, SpeakerPolicy.act(Mode.SHADOW, it)) }
    }

    @Test
    fun `gated follows the score`() {
        assertEquals(Band.OPEN, SpeakerPolicy.act(Mode.GATED, SpeakerResult.Scored(0.6f, 40)))
        assertEquals(Band.ASK, SpeakerPolicy.act(Mode.GATED, SpeakerResult.Scored(0.35f, 40)))
        assertEquals(Band.IGNORE, SpeakerPolicy.act(Mode.GATED, SpeakerResult.Scored(0.1f, 40)))
    }

    @Test
    fun `gated opens as today on every failure`() {
        failures.forEach { assertEquals(it.word, Band.OPEN, SpeakerPolicy.act(Mode.GATED, it)) }
    }

    @Test
    fun `the shadow line says what it would have done and that it opened`() {
        val line = SpeakerPolicy.line(Mode.SHADOW, SpeakerResult.Scored(0.123f, 87), Band.OPEN)
        assertEquals("speaker_check_ms=87 mode=shadow result=scored score=0.123 would=ignore acted=open", line)
    }

    @Test
    fun `only a scored check carries a ms key`() {
        failures.forEach { r ->
            val line = SpeakerPolicy.line(Mode.GATED, r, Band.OPEN)
            assertFalse(line, Regex("""\w+_ms=""").containsMatchIn(line))
            assertTrue(line, line.contains("result=") && line.endsWith("acted=open"))
        }
        assertEquals(
            "speaker_check mode=gated result=no_voiceprint acted=open",
            SpeakerPolicy.line(Mode.GATED, SpeakerResult.NoVoiceprint, Band.OPEN),
        )
        assertEquals(
            "speaker_check mode=gated result=timeout waited=300 acted=open",
            SpeakerPolicy.line(Mode.GATED, SpeakerResult.TimedOut(300), Band.OPEN),
        )
    }
}

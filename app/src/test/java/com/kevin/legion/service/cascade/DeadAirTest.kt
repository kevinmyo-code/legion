package com.kevin.legion.service.cascade

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The dead-air rule ([DeadAir], 2026-11-21): a wait line fills every gap until the answer's AUDIO is
 * in hand, and never holds that audio up by more than [DeadAir.FINISH_MAX_MS]. Times in ms from his
 * end of speech; all numbers invented.
 */
class DeadAirTest {
    private val cloud = DeadAir.CLOUD_FIRST_AUDIO_MS

    private fun at(now: Long, asked: Long? = null, ready: Boolean = false) =
        DeadAir.State(nowMs = now, answerAudioReady = ready, answerAskedAtMs = asked, expectedFirstAudioMs = cloud)

    @Test
    fun `first text early and audio late, a wait line fills the gap and ends before the audio`() {
        // Backchannel ended at 1,000; the model's first sentence went to the voice at 2,600 (expected
        // audio 3,400). The old rule: text in, no wait line, silence from 1,000 to the audio.
        val due = WaitFillers.nextAtMs(lastFillerEndAtMs = 1_000, waitSaid = 0, fillersSaid = 1)!!
        assertEquals(1_000 + WaitFillers.FIRST_GAP_MS, due)
        // Due at 2,800: a 600 ms line ends at 3,400, by the expected audio (300 ms of slack allowed).
        assertNull(DeadAir.waitLine(at(2_800, asked = 2_600), clipMs = 600))
        // Its audio comes in early, with 450 ms of the line left: the line is cut, the answer is not held.
        assertTrue(DeadAir.cutPlaying(answerAudioReady = true, remainingMs = 450))
        // With 250 ms left it finishes: the answer waits at most FINISH_MAX_MS.
        assertFalse(DeadAir.cutPlaying(answerAudioReady = true, remainingMs = 250))
        assertFalse("no answer audio: it plays out", DeadAir.cutPlaying(answerAudioReady = false, remainingMs = 900))
    }

    @Test
    fun `a line that would run past the expected audio does not start, and is asked again later`() {
        // Asked at 1,700, expected audio 2,500: a 1,000 ms line from 2,800 would end at 3,800.
        val block = DeadAir.waitLine(at(2_000, asked = 1_700), clipMs = 1_000)
        assertEquals(DeadAir.Block.NO_FIT, block)
        assertFalse("NO_FIT ends nothing", DeadAir.endsWait(block!!))
        // The audio is late: 700 ms past its expected time the estimate is wrong, a line may start.
        assertEquals(DeadAir.Block.NO_FIT, DeadAir.waitLine(at(3_100, asked = 1_700), clipMs = 1_000))
        assertNull(DeadAir.waitLine(at(3_201, asked = 1_700), clipMs = 1_000))
    }

    @Test
    fun `the answer's audio in hand, no wait line, for good`() {
        val block = DeadAir.waitLine(at(2_800, asked = 2_000, ready = true), clipMs = 300)
        assertEquals(DeadAir.Block.ANSWER_AUDIO, block)
        assertTrue(DeadAir.endsWait(block!!))
        // A short code-spoken line already playing is answer audio too.
        assertEquals(DeadAir.Block.ANSWER_AUDIO, DeadAir.waitLine(at(1_900, ready = true), clipMs = 0))
    }

    @Test
    fun `a tool round silent for 3 s gets a filler`() {
        // The model asked for a tool and wrote nothing speakable (a dropped "Checking."): no clip asked.
        assertTrue(
            WaitFillers.dueNow(3_000, lastFillerEndAtMs = 1_000, waitSaid = 0, fillersSaid = 1, fillerPlaying = false),
        )
        assertNull(DeadAir.waitLine(at(3_000), clipMs = 1_200))
        // The task line still playing moves it back (the WaitFillers rule, unchanged).
        assertFalse(
            WaitFillers.dueNow(3_000, lastFillerEndAtMs = 1_000, waitSaid = 0, fillersSaid = 2, fillerPlaying = true),
        )
    }

    @Test
    fun `never on a goodbye, a stop, the greeting or a close`() {
        val base = at(3_000)
        for ((state, want) in listOf(
            base.copy(goodbye = true) to DeadAir.Block.GOODBYE,
            base.copy(stopped = true) to DeadAir.Block.STOPPED,
            base.copy(greetingHeld = true) to DeadAir.Block.GREETING,
            base.copy(closing = true) to DeadAir.Block.CLOSING,
        )) {
            val block = DeadAir.waitLine(state, clipMs = 500)
            assertEquals(want, block)
            assertTrue(want.token, DeadAir.endsWait(block!!))
        }
    }

    @Test
    fun `the expected audio is this session's measure, bounded, else the voice's default`() {
        assertEquals(800L, DeadAir.expectedFirstAudioMs(cloud = true, measuredMs = null))
        assertEquals(1_000L, DeadAir.expectedFirstAudioMs(cloud = false, measuredMs = null))
        assertEquals(1_400L, DeadAir.expectedFirstAudioMs(cloud = false, measuredMs = 1_400))
        assertEquals(DeadAir.MIN_FIRST_AUDIO_MS, DeadAir.expectedFirstAudioMs(cloud = true, measuredMs = 5))
        assertEquals(DeadAir.MAX_FIRST_AUDIO_MS, DeadAir.expectedFirstAudioMs(cloud = true, measuredMs = 9_000))
    }

    @Test
    fun `the case from the brief has no gap over the wait-line timing`() {
        // "Mm." 600 to 1,000, then about 3.9 s to the answer's audio. A wait line is due at 2,800.
        // Nothing asked of the voice yet (a slow model): it plays.
        assertNull(DeadAir.waitLine(at(2_800), clipMs = 900))
        // The first sentence went to the voice at 2,000 (the old rule went silent here): expected audio
        // 2,800, so a 900 ms line does not fit at 2,800; once the audio is 700 ms late (3,500), one plays.
        assertEquals(DeadAir.Block.NO_FIT, DeadAir.waitLine(at(2_800, asked = 2_000), clipMs = 900))
        assertNull(DeadAir.waitLine(at(3_550, asked = 2_000), clipMs = 900))
    }
}

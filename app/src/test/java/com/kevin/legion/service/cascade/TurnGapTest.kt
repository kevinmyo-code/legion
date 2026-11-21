@file:Suppress("MaxLineLength") // test fixtures read best on one line

package com.kevin.legion.service.cascade

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The bench's per-turn gap line ([TurnGap]): the longest silence before the answer's first audio. */
class TurnGapTest {

    @Test
    fun `tonight's shape - a backchannel then 3,9 s of nothing`() {
        val g = TurnGap(eotAtMs = 10_000)
        val bc = g.started(TurnGap.BACKCHANNEL, 10_600)
        g.ended(bc, 11_000)
        assertEquals("gap_max_ms=3900 gap_after=backchannel fillers=backchannel answer_ms=4900 first_sound_ms=600", g.line(14_900))
    }

    @Test
    fun `wait lines fill the gap, and the longest stretch is named by what came before it`() {
        val g = TurnGap(eotAtMs = 0)
        g.ended(g.started(TurnGap.BACKCHANNEL, 600), 1_000)
        g.ended(g.started(TurnGap.WAIT, 2_800), 3_600)
        g.ended(g.started(TurnGap.TOOL, 3_700), 4_500)
        val line = g.line(5_000)
        assertEquals("gap_max_ms=1800 gap_after=backchannel fillers=backchannel+wait+tool answer_ms=5000 first_sound_ms=600", line)
    }

    @Test
    fun `no filler at all, the gap is the whole wait from his end of speech`() {
        val g = TurnGap(eotAtMs = 100)
        assertEquals("gap_max_ms=1900 gap_after=eot fillers=none answer_ms=1900 first_sound_ms=1900", g.line(2_000))
    }

    @Test
    fun `a line still playing when the answer's audio is in covers the time up to it, and one line a turn`() {
        val g = TurnGap(eotAtMs = 0)
        g.ended(g.started(TurnGap.BACKCHANNEL, 600), 1_000)
        g.started(TurnGap.WAIT, 2_800)
        assertEquals("gap_max_ms=1800 gap_after=backchannel fillers=backchannel+wait answer_ms=3100 first_sound_ms=600", g.line(3_100))
        assertNull(g.line(3_200))
    }

    @Test
    fun `the first sound is the acknowledgement at end of turn, then the first wait line about 2 s later`() {
        val g = TurnGap(eotAtMs = 0)
        g.ended(g.started(TurnGap.BACKCHANNEL, 40), 500)
        g.ended(g.started(TurnGap.WAIT, 500 + FillerTiming.FIRST_WAIT_GAP_MS), 2_800)
        assertEquals(
            "gap_max_ms=1500 gap_after=backchannel fillers=backchannel+wait answer_ms=3000 first_sound_ms=40",
            g.line(3_000),
        )
    }

    @Test
    fun `every turn gets one first-sound line, from end of turn and from his last voiced frame`() {
        val ack = TurnGap(eotAtMs = 10_700, speechEndAtMs = 10_000)
        ack.started(TurnGap.BACKCHANNEL, 10_740)
        assertEquals("first_sound_ms=40 first_sound_after_speech_ms=740 kind=ack skip=-", ack.firstSoundLine())
        assertNull("once a turn", ack.firstSoundLine())
        assertNull(ack.silentLine())

        val wait = TurnGap(eotAtMs = 0, speechEndAtMs = 0)
        wait.noteSkip("no_clip_fits")
        wait.started(TurnGap.WAIT, 1_500)
        assertEquals("first_sound_ms=1500 first_sound_after_speech_ms=none kind=wait skip=no_clip_fits", wait.firstSoundLine())

        val answer = TurnGap(eotAtMs = 0, speechEndAtMs = 0)
        answer.noteSkip("answer_first")
        answer.answerHeard(900)
        assertEquals("first_sound_ms=900 first_sound_after_speech_ms=none kind=answer skip=answer_first", answer.firstSoundLine())

        val none = TurnGap(eotAtMs = 0)
        none.noteSkip("goodbye")
        assertNull(none.firstSoundLine())
        assertEquals("first_sound_ms=none first_sound_after_speech_ms=none kind=none skip=goodbye", none.silentLine())
        assertNull(none.silentLine())
    }
}

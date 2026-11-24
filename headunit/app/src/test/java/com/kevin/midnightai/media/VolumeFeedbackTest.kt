package com.kevin.midnightai.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A volume press that moves nothing says why (Kevin, 2026-11-23: "the volume button still doesnt
 * work"). Pure; which branch this unit takes is only knowable in the car.
 */
class VolumeFeedbackTest {

    @Test fun aPressThatMovedTheStreamSaysNothing() {
        assertNull(VolumeFeedback.pressWords(before = 7, after = 8, max = 15, up = true, volumeFixed = false))
        assertNull(VolumeFeedback.pressWords(before = 7, after = 6, max = 15, up = false, volumeFixed = false))
        // Even on a fixed output: if it moved, it moved.
        assertNull(VolumeFeedback.pressWords(before = 7, after = 8, max = 15, up = true, volumeFixed = true))
    }

    @Test fun pushingPastEitherEndIsOrdinaryAndNeedsNoSentence() {
        assertNull(VolumeFeedback.pressWords(before = 15, after = 15, max = 15, up = true, volumeFixed = false))
        assertNull(VolumeFeedback.pressWords(before = 0, after = 0, max = 15, up = false, volumeFixed = false))
    }

    @Test fun aFixedOutputNamesItselfAndPointsAtTheKnob() {
        val words = VolumeFeedback.pressWords(before = 7, after = 7, max = 15, up = true, volumeFixed = true)
        assertEquals(VolumeFeedback.FIXED_OUTPUT, words)
        assertTrue(words!!, "fixed volume" in words)
        assertTrue(words, "knob" in words)
    }

    @Test fun aDeadPressOnANormalOutputPointsAtTheProbePanel() {
        val words = VolumeFeedback.pressWords(before = 7, after = 7, max = 15, up = false, volumeFixed = false)
        assertEquals(VolumeFeedback.NO_MOVEMENT, words)
        assertTrue(words!!, "Volume check" in words)
    }

    /**
     * The case the end-of-range branches would have swallowed: AudioService pins a fixed-volume
     * output's stream at max, so the press Kevin makes looks exactly like "already at the top".
     */
    @Test fun aFixedOutputPinnedAtMaxStillSaysSo() {
        assertEquals(
            VolumeFeedback.FIXED_OUTPUT,
            VolumeFeedback.pressWords(before = 15, after = 15, max = 15, up = true, volumeFixed = true),
        )
        assertEquals(
            VolumeFeedback.FIXED_OUTPUT,
            VolumeFeedback.pressWords(before = 0, after = 0, max = 0, up = true, volumeFixed = true),
        )
        assertEquals(
            VolumeFeedback.FIXED_OUTPUT,
            VolumeFeedback.pressWords(before = 0, after = 0, max = 15, up = false, volumeFixed = true),
        )
    }

    @Test fun theReadoutNamesAFixedOutputSoTheProbesAreNotAmbiguous() {
        assertEquals(
            "STREAM_MUSIC index 7 of 15",
            VolumeFeedback.readout(7, 15, muted = false, volumeFixed = false),
        )
        assertEquals(
            "STREAM_MUSIC index 0 of 15, muted",
            VolumeFeedback.readout(0, 15, muted = true, volumeFixed = false),
        )
        val fixed = VolumeFeedback.readout(7, 15, muted = false, volumeFixed = true)
        assertTrue(fixed, "FIXED" in fixed && "no-op" in fixed)
    }
}

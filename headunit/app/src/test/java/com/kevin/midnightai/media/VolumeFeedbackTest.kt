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

    @Test fun aCallThisUnitRefusedIsSaidAsARefusalAndNotAsADeadButton() {
        val words = VolumeFeedback.pressWords(
            before = 7, after = 7, max = 15, up = true, volumeFixed = false,
            readsBack = true, refused = "NoSuchMethodError",
        )
        assertEquals(VolumeFeedback.REFUSED, words)
        assertTrue(words!!, "Volume check" in words)
    }

    /**
     * A press handed to another process (the player's own session, a key event) cannot be judged
     * from the index at all, so it claims nothing either way (2026-11-23).
     */
    @Test fun aPressHandedOnSaysNothingAboutMovement() {
        assertNull(
            VolumeFeedback.pressWords(
                before = 7, after = 7, max = 15, up = true, volumeFixed = false, readsBack = false,
            ),
        )
        assertEquals(
            VolumeFeedback.REFUSED,
            VolumeFeedback.pressWords(
                before = 7, after = 7, max = 15, up = true, volumeFixed = false,
                readsBack = false, refused = "no player",
            ),
        )
    }

    @Test fun theDriveLogLineNamesTheCallThatCarriedThePress() {
        assertEquals(
            "raise via SUGGESTED_STREAM",
            VolumeFeedback.writeWhat(up = true, path = VolumePath.SUGGESTED_STREAM, refused = null),
        )
        assertEquals(
            "lower via MEDIA_KEY refused=no player",
            VolumeFeedback.writeWhat(up = false, path = VolumePath.MEDIA_KEY, refused = "no player"),
        )
    }

    @Test fun eachProbeSaysWhatItSentAndNeverClaimsTheAmpHeardIt() {
        val sent = VolumeFeedback.probeWords(VolumePath.SUGGESTED_STREAM, refused = null)
        assertTrue(sent, VolumePath.SUGGESTED_STREAM.label in sent && "sound" in sent)
        val handedOn = VolumeFeedback.probeWords(VolumePath.MEDIA_SESSION, refused = null)
        assertTrue(handedOn, "ears" in handedOn)
        val refused = VolumeFeedback.probeWords(VolumePath.MASTER, refused = "SecurityException")
        assertTrue(refused, "refused" in refused && "SecurityException" in refused)
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

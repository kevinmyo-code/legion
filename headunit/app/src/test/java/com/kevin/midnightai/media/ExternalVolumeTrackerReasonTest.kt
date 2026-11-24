package com.kevin.midnightai.media

import com.kevin.midnightai.media.ExternalVolumeTracker.Decision
import com.kevin.midnightai.media.ExternalVolumeTracker.Reason
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * HU-1 T2 volume probe: every classification also says WHY, so the drive log can show which
 * branch each observed index took. The decisions themselves are ExternalVolumeTrackerTest's.
 */
class ExternalVolumeTrackerReasonTest {

    @Test fun firstSightingAndUnchanged() {
        val t = ExternalVolumeTracker(quietMs = 600)
        assertEquals(ExternalVolumeTracker.Verdict(Decision.IGNORE, Reason.FIRST_SIGHTING), t.classify(7, 1_000))
        assertEquals(ExternalVolumeTracker.Verdict(Decision.IGNORE, Reason.UNCHANGED), t.classify(7, 2_000))
    }

    @Test fun externalChangeIsReassertedAndSaysSo() {
        val t = ExternalVolumeTracker(quietMs = 600)
        t.classify(7, 1_000)
        assertEquals(ExternalVolumeTracker.Verdict(Decision.REASSERT, Reason.EXTERNAL_CHANGE), t.classify(8, 2_000))
    }

    @Test fun ownWriteWindowIsNamed() {
        val t = ExternalVolumeTracker(quietMs = 600)
        t.classify(7, 1_000)
        t.onOwnWrite(8, 2_000)
        assertEquals(ExternalVolumeTracker.Verdict(Decision.DEFER, Reason.OWN_WRITE_WINDOW), t.classify(8, 2_100))
    }

    @Test fun voiceHoldIsNamed() {
        val t = ExternalVolumeTracker(quietMs = 600, holdGraceMs = 3_000)
        t.classify(7, 1_000)
        t.setHeld(true, 1_500)
        assertEquals(ExternalVolumeTracker.Verdict(Decision.IGNORE, Reason.VOICE_HOLD), t.classify(4, 2_000))
        t.setHeld(false, 2_500)
        assertEquals(ExternalVolumeTracker.Verdict(Decision.IGNORE, Reason.VOICE_HOLD), t.classify(7, 3_000))
    }

    @Test fun breakerTripAndOpenAreNamed() {
        val t = ExternalVolumeTracker(quietMs = 0, breakerWindowMs = 10_000, breakerMax = 2, breakerOpenMs = 5_000)
        t.classify(1, 0)
        assertEquals(Reason.EXTERNAL_CHANGE, t.classify(2, 100).reason)
        assertEquals(Reason.EXTERNAL_CHANGE, t.classify(3, 200).reason)
        assertEquals(ExternalVolumeTracker.Verdict(Decision.IGNORE, Reason.BREAKER_TRIPPED), t.classify(4, 300))
        assertEquals(ExternalVolumeTracker.Verdict(Decision.IGNORE, Reason.BREAKER_OPEN), t.classify(5, 400))
    }

    @Test fun onObservedStillReturnsTheSameDecisionAsClassify() {
        val a = ExternalVolumeTracker(quietMs = 600)
        val b = ExternalVolumeTracker(quietMs = 600)
        listOf(7 to 1_000L, 8 to 2_000L, 8 to 2_010L, 3 to 2_500L).forEach { (i, at) ->
            assertEquals(a.classify(i, at).decision, b.onObserved(i, at))
        }
    }
}

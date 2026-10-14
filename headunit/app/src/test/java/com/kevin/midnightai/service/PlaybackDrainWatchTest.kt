package com.kevin.midnightai.service

import com.kevin.midnightai.service.PlaybackDrainWatch.Verdict
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackDrainWatchTest {

    private fun watch() = PlaybackDrainWatch(stallMs = 1_500, capMs = 45_000, startMs = 0)

    @Test
    fun emptyBacklogIsDrainedImmediately() {
        assertEquals(Verdict.DRAINED, watch().step(0, 0))
    }

    @Test
    fun longReplyThatKeepsPlayingIsWaitedOutPastTheOldFixedCap() {
        val w = watch()
        // 6s of backlog draining steadily: the old 1.5s cap would have opened the mic at 1.5s.
        var backlog = 144_000L
        var t = 0L
        while (backlog > 0) {
            assertEquals(Verdict.WAIT, w.step(backlog, t))
            t += 20; backlog -= 480
        }
        assertEquals(Verdict.DRAINED, w.step(0, t))
    }

    @Test
    fun wedgedTrackStopsWaitingAfterStallWindow() {
        val w = watch()
        assertEquals(Verdict.WAIT, w.step(5_000, 0))
        assertEquals(Verdict.WAIT, w.step(5_000, 1_499))
        assertEquals(Verdict.STALLED, w.step(5_000, 1_500))
    }

    @Test
    fun progressResetsTheStallClock() {
        val w = watch()
        w.step(5_000, 0)
        assertEquals(Verdict.WAIT, w.step(4_000, 1_400))
        assertEquals(Verdict.WAIT, w.step(4_000, 2_800))
        assertEquals(Verdict.STALLED, w.step(4_000, 2_900))
    }

    @Test
    fun hardCapBoundsAnEndlesslyMovingBacklog() {
        val w = watch()
        assertEquals(Verdict.CAPPED, w.step(1, 45_000))
    }
}

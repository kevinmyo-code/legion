package com.kevin.midnightai.vehicle

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [MusicHistoryRecorder.classify], the full-play vs skip decision.
 * There is no track-end event in the MediaSession framework, so this is derived
 * from measured wall-clock dwell against the track's reported duration - these
 * cases pin down the thresholds without needing a real MediaSession.
 */
class MusicHistoryRecorderTest {

    @Test fun under_min_listen_is_always_skip() {
        // 15s dwell on a 4-minute track - never had a real chance to play.
        assertFalse(MusicHistoryRecorder.classify(15_000, 240_000))
    }

    @Test fun mostly_played_4min_track_is_skip() {
        // 55% of a 4-minute (240s) track = 132s dwell.
        assertFalse(MusicHistoryRecorder.classify(132_000, 240_000))
    }

    @Test fun nearly_full_4min_track_is_completed() {
        // 95% of a 4-minute track = 228s dwell.
        assertTrue(MusicHistoryRecorder.classify(228_000, 240_000))
    }

    @Test fun duration_unknown_long_dwell_is_completed() {
        // No duration reported; a 2-minute dwell reads as a full listen.
        assertTrue(MusicHistoryRecorder.classify(120_000, 0))
    }

    @Test fun duration_unknown_short_dwell_is_skip() {
        // No duration reported; 30s dwell is well short of the fallback threshold.
        assertFalse(MusicHistoryRecorder.classify(30_000, 0))
    }
}

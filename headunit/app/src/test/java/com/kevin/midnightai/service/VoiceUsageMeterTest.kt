package com.kevin.midnightai.service

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [VoiceUsageMeter] - the pure accumulator behind broker voice
 * billing (see LiveSessionController). No device needed.
 */
class VoiceUsageMeterTest {

    @Test
    fun singleActiveSegment_billsItsLength() {
        val meter = VoiceUsageMeter()
        meter.startSegment(0L)
        val seconds = meter.stopAndReportSeconds(5_000L)
        assertEquals(5L, seconds)
    }

    @Test
    fun activeThenPauseThenStop_excludesNothingExtra() {
        val meter = VoiceUsageMeter()
        meter.startSegment(0L)
        meter.pause(3_000L)
        // Nothing further accrues after pause, even if stop is called much later.
        val seconds = meter.stopAndReportSeconds(30_000L)
        assertEquals(3L, seconds)
    }

    @Test
    fun activePauseGapActiveStop_excludesTheGap() {
        val meter = VoiceUsageMeter()
        meter.startSegment(0L)          // talk starts
        meter.pause(3_000L)             // talk ends at t=3s (3s active)
        // A long warm-idle gap here (e.g. the ~3min warm hold) - must not count.
        meter.startSegment(180_000L)    // resume talk at t=180s
        val seconds = meter.stopAndReportSeconds(184_000L) // talk ends at t=184s (4s active)
        assertEquals(7L, seconds) // 3s + 4s, gap excluded
    }

    @Test
    fun neverStarted_prewarmOnly_yieldsZero() {
        val meter = VoiceUsageMeter()
        val seconds = meter.stopAndReportSeconds(180_000L)
        assertEquals(0L, seconds)
    }

    @Test
    fun stopWhileStillActive_includesTheFinalSegment() {
        val meter = VoiceUsageMeter()
        meter.startSegment(1_000L)
        val seconds = meter.stopAndReportSeconds(6_000L) // never paused, still active at stop
        assertEquals(5L, seconds)
    }

    @Test
    fun subSecondActive_truncatesToZeroSeconds() {
        val meter = VoiceUsageMeter()
        meter.startSegment(0L)
        val seconds = meter.stopAndReportSeconds(999L)
        assertEquals(0L, seconds)
    }

    @Test
    fun zeroLengthSegment_isZero() {
        val meter = VoiceUsageMeter()
        meter.startSegment(1_000L)
        meter.pause(1_000L)
        val seconds = meter.stopAndReportSeconds(1_000L)
        assertEquals(0L, seconds)
    }

    @Test
    fun negativeDelta_isGuardedAgainstAndDoesNotUnderflow() {
        val meter = VoiceUsageMeter()
        // A caller passing a nowMs earlier than the segment start (clock skew,
        // or a stale timestamp) must not produce a negative contribution.
        meter.startSegment(5_000L)
        val seconds = meter.stopAndReportSeconds(1_000L)
        assertEquals(0L, seconds)
    }

    @Test
    fun pauseWithoutActiveSegment_isNoOp() {
        val meter = VoiceUsageMeter()
        meter.pause(1_000L) // never started - must not throw or accrue
        meter.startSegment(2_000L)
        val seconds = meter.stopAndReportSeconds(7_000L)
        assertEquals(5L, seconds)
    }

    @Test
    fun startSegmentCalledTwice_doesNotResetTheClock() {
        val meter = VoiceUsageMeter()
        meter.startSegment(0L)
        meter.startSegment(4_000L) // e.g. Connected racing an already-active resume
        val seconds = meter.stopAndReportSeconds(5_000L)
        assertEquals(5L, seconds) // still measured from the original t=0 start
    }

    @Test
    fun reusedAfterStop_startsFreshForTheNextSocket() {
        val meter = VoiceUsageMeter()
        meter.startSegment(0L)
        assertEquals(5L, meter.stopAndReportSeconds(5_000L))
        // A brand new socket's lifetime begins with no leftover state.
        meter.startSegment(100_000L)
        assertEquals(2L, meter.stopAndReportSeconds(102_000L))
    }
}

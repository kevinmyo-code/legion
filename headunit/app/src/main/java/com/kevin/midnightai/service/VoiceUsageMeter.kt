package com.kevin.midnightai.service

/**
 * Accumulates ACTIVE conversation/speech duration for Live voice sessions.
 * Dormant since the entitlement broker was removed (CLAUDE.md sec 2/8,
 * 2026-07-16 rewrite: two tiers, no trial, no subscription, no broker) - there
 * is nothing left to report the total to - but kept for a future local spend
 * readout, since it already excludes the warm-idle tail a socket sits
 * connected-but-quiet for after a chat (see [LiveSessionController]'s
 * WARM_HOLD_MS) and a prewarmed socket that was never spoken into at all.
 *
 * Pure/no-Android so it's cheaply unit-testable: an "active segment" starts
 * when the driver or companion begins talking (mic open or Zero speaking)
 * and pauses when the conversation goes idle/parks warm or a proactive line
 * finishes. Multiple segments across one socket's lifetime (talk, idle gap,
 * resume, talk again) accumulate; only the summed active time is billed.
 *
 * [segmentStartMs] is nullable rather than a `0 = inactive` sentinel: real
 * callers only ever pass [System.currentTimeMillis], but a `0L` sentinel
 * would collide with legitimate `t=0` inputs in tests (and, in principle,
 * any epoch-adjacent clock), silently treating an active segment as idle.
 */
class VoiceUsageMeter {
    // Wall-clock ms the current segment began, or null when not in one.
    private var segmentStartMs: Long? = null
    private var accumMs: Long = 0L

    /** Marks the start of active talk/speech. No-op if already active (never resets). */
    fun startSegment(nowMs: Long) {
        if (segmentStartMs == null) segmentStartMs = nowMs
    }

    /** Marks the end of active talk/speech, folding its length into the running total. */
    fun pause(nowMs: Long) {
        val start = segmentStartMs ?: return
        accumMs += (nowMs - start).coerceAtLeast(0L)
        segmentStartMs = null
    }

    /**
     * Closes out the meter (folding in a still-open final segment, if any),
     * returns the total active seconds, and resets state for reuse on the
     * controller's next socket.
     */
    fun stopAndReportSeconds(nowMs: Long): Long {
        segmentStartMs?.let { start ->
            accumMs += (nowMs - start).coerceAtLeast(0L)
            segmentStartMs = null
        }
        val seconds = accumMs / 1000L
        accumMs = 0L
        return seconds
    }
}

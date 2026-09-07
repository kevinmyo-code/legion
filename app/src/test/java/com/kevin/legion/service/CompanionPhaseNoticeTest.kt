package com.kevin.legion.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the delivery half of the 2026-09-07 silent-tap defect.
 *
 * A refusal that is raised and never rendered is exactly as useless as one that was never raised.
 * [CompanionPhase.notice] carried `replay = 0`, and [kotlinx.coroutines.flow.MutableSharedFlow]
 * with no replay and no active collector accepts `tryEmit` and DROPS the value - so on the two
 * doors into [LiveSessionController.onTap] that fire while the app is backgrounded (the wake word,
 * and the Android Auto voice button), every worded refusal went nowhere at all. The app said why;
 * nobody could hear it.
 *
 * Replay fixes that and introduces its own risk: a replayed notice is evidence of a PAST moment,
 * and shown without a check it reads as a live failure. [CompanionPhase.noticeStillWorthShowing] is
 * that check, and this is its test.
 */
class CompanionPhaseNoticeTest {

    private val window = CompanionPhase.NOTICE_REPLAY_MAX_AGE_MS

    @Test
    fun `a notice raised this instant is shown`() {
        // The live case, and the overwhelmingly common one: a tap while the strip is on screen.
        assertTrue(CompanionPhase.noticeStillWorthShowing(atMs = 1_000L, nowMs = 1_000L))
    }

    @Test
    fun `a notice from moments ago is still shown`() {
        // The case replay exists for: refused by a wake word, phone pulled out of a pocket, app
        // opened to find out why nothing happened.
        assertTrue(CompanionPhase.noticeStillWorthShowing(atMs = 0L, nowMs = window / 2))
    }

    @Test
    fun `a notice older than the window is dropped rather than flashed`() {
        // Opening the app after lunch must not flash something about a call that ended an hour ago.
        // A stale notice is a worse lie than silence, because it describes a failure that is over.
        assertFalse(CompanionPhase.noticeStillWorthShowing(atMs = 0L, nowMs = window * 4))
    }

    @Test
    fun `the boundary is a strict less-than`() {
        assertTrue(CompanionPhase.noticeStillWorthShowing(atMs = 0L, nowMs = window - 1))
        assertFalse(CompanionPhase.noticeStillWorthShowing(atMs = 0L, nowMs = window))
    }

    @Test
    fun `a clock that went backwards still shows the notice`() {
        // A wall clock can move backwards (NTP correction, timezone-independent though that is,
        // and a user setting the date). Reading a notice as thousands of years old and hiding it
        // would silently reopen the exact defect this whole change exists to close, so a negative
        // age is treated as fresh rather than as expired.
        assertTrue(CompanionPhase.noticeStillWorthShowing(atMs = 10_000L, nowMs = 0L))
    }

    @Test
    fun `the window is long enough to walk from a pocket to the app, and no longer`() {
        // GUESSED, not measured - pinned here so a change to it is a decision somebody makes rather
        // than a literal that drifts. See CompanionPhase.NOTICE_REPLAY_MAX_AGE_MS's own comment.
        assertTrue("under half a minute cannot survive taking the phone out", window >= 30_000L)
        assertTrue("a notice older than five minutes describes a finished problem", window <= 300_000L)
    }
}

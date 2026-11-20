package com.kevin.legion.service.cascade

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [CloseCue]: which closes chime, the skips, once only, and the bounded wait (Kevin, 2026-11-20). */
class CloseCueTest {
    private fun decide(
        reason: String,
        quiet: Boolean = false,
        onCall: Boolean = false,
        audioOpened: Boolean = true,
        played: Boolean = false,
        rateMatches: Boolean = true,
    ) = CloseCue.decide(reason, quiet, onCall, audioOpened, played, rateMatches)

    @Test
    fun `every close of a conversation he was in chimes`() {
        for (r in listOf("stopped", "sleep_phrase", "follow_up", "dismissed", "noise", "missed_twice",
            "check_in_no_reply", "new conversation", "voice_failed_tts")) {
            assertNull(r, decide(r))
        }
    }

    @Test
    fun `teardown, a takeover, a reopen and lines he was not in do not`() {
        for (r in listOf("destroyed", "preempted", "crisis", "mic_held", "ring stopped", "spoken",
            "check_in_statement", "check_in_dropped", "brief", "look", "test_real_tap", "tap over a proactive line")) {
            assertEquals(r, CloseCue.Skip.NOT_A_CLOSE, decide(r))
        }
    }

    @Test
    fun `Quiet, a call, no audio, a wrong rate and a second time skip it`() {
        assertEquals(CloseCue.Skip.QUIET, decide("stopped", quiet = true))
        assertEquals(CloseCue.Skip.ON_CALL, decide("stopped", onCall = true))
        assertEquals(CloseCue.Skip.NO_AUDIO, decide("stopped", audioOpened = false))
        assertEquals(CloseCue.Skip.RATE, decide("stopped", rateMatches = false))
        assertEquals(CloseCue.Skip.PLAYED, decide("stopped", played = true))
    }

    @Test
    fun `the audit line is numbers and fixed tokens`() {
        assertEquals("close_cue played=1 reason=follow_up skipped=none", CloseCue.auditLine("follow_up", null))
        assertEquals(
            "close_cue played=0 reason=new_conversation skipped=quiet",
            CloseCue.auditLine("new conversation", CloseCue.Skip.QUIET),
        )
    }

    @Test
    fun `the wait is the chime plus the route's tail, never past the hard cap`() {
        assertEquals(290L + 60L, CloseCue.waitCapMs(chimeMs = 290, settleMs = 60))
        assertEquals(290L + 700L, CloseCue.waitCapMs(chimeMs = 290, settleMs = 700))
        assertEquals(CloseCue.HARD_CAP_MS, CloseCue.waitCapMs(chimeMs = 900, settleMs = 900))
    }

    @Test
    fun `a playback that never drains is cut at the cap, a normal one completes`() = runTest {
        val start = testScheduler.currentTime
        assertFalse(CloseCue.playBounded(500, write = {}, drained = { delay(60_000) }))
        assertEquals(500L, testScheduler.currentTime - start)
        assertTrue(CloseCue.playBounded(500, write = {}, drained = { delay(300) }))
    }
}

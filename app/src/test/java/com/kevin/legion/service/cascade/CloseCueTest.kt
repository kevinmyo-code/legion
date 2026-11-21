@file:Suppress("MaxLineLength") // test fixtures read best on one line

package com.kevin.legion.service.cascade

import com.kevin.legion.service.ListenCue
import com.kevin.legion.service.turn.AudioRoute
import com.kevin.legion.service.turn.MicReopen
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
    fun `each way a conversation ends chimes exactly once`() {
        // goodbye (Farewells), end_conversation, the follow-up window, a tap, noise, two misses,
        // an unanswered check-in, a new conversation, and a failed voice with no fallback (Live retired).
        listOf(
            "sleep_phrase", "dismissed", "follow_up", "stopped", "noise", "missed_twice", "check_in_no_reply",
            "new conversation", CloseCue.voiceFailedReason("cloud_down", liveRetired = true, background = false),
        ).forEach { reason ->
            assertNull(reason, CloseCue.decide(reason, false, false, true, alreadyPlayed = false, rateMatches = true))
            assertEquals(reason, CloseCue.Skip.PLAYED, CloseCue.decide(reason, false, false, true, alreadyPlayed = true, rateMatches = true))
        }
    }

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

    // Review of 546cce67 (F2): the hand-over released with the raw reason, so a failed voice closed in
    // silence. The release now gets the reason the chime rule knows, unless it was a background line.
    @Test
    fun `a failed voice's close chimes, a background line's does not`() {
        val r = CloseCue.voiceFailedReason("tts", liveRetired = true, background = false)
        assertEquals("voice_failed_tts", r)
        assertNull(decide(r))
        assertEquals(CloseCue.Skip.NOT_A_CLOSE, decide(CloseCue.voiceFailedReason("tts", liveRetired = true, background = true)))
        assertEquals("tts", CloseCue.voiceFailedReason("tts", liveRetired = false, background = false))
    }

    // Review of 546cce67 (F3): the order of the close, in numbers, for the Bluetooth check in the car.
    @Test
    fun `the close's order is logged in ms from the release's start`() {
        assertEquals("close_order mic_released_ms=12 chime_end_ms=1004 route_released_ms=1030", CloseCue.orderLine(12, 1004, 1030))
        assertEquals("close_order mic_released_ms=12 chime_end_ms=none route_released_ms=40", CloseCue.orderLine(12, null, 40))
    }

    @Test
    fun `the audit line is numbers and fixed tokens`() {
        assertEquals(
            "close_cue played ms=540 reason=sleep_phrase route=speaker drained=1",
            CloseCue.playedLine("sleep_phrase", 540, "speaker", drained = true),
        )
        assertEquals(
            "close_cue skipped reason=quiet close=new_conversation",
            CloseCue.skippedLine("new conversation", CloseCue.Skip.QUIET),
        )
        assertEquals("bt", CloseCue.routeToken(AudioRoute.BLUETOOTH, earpiece = true))
        assertEquals("earpiece", CloseCue.routeToken(AudioRoute.PHONE_SPEAKER, earpiece = true))
        assertEquals("speaker", CloseCue.routeToken(AudioRoute.PHONE_SPEAKER, earpiece = false))
        assertEquals("wired", CloseCue.routeToken(AudioRoute.WIRED_HEADSET, earpiece = false))
    }

    @Test
    fun `the wait covers the chime, its output latency and the route's tail, never past the hard cap`() {
        assertEquals(290L + CloseCue.OUTPUT_LATENCY_MS + 60L, CloseCue.waitCapMs(chimeMs = 290, settleMs = 60))
        assertEquals(CloseCue.HARD_CAP_MS, CloseCue.waitCapMs(chimeMs = 290, settleMs = 700))
        assertEquals(CloseCue.HARD_CAP_MS, CloseCue.waitCapMs(chimeMs = 900, settleMs = 900))
        // On the speaker the cap leaves room to release only after the chime was heard (2026-11-21: the
        // shipped cap was the chime plus a 60 ms settle, and the release cut it).
        val chime = ListenCue.durationMs(ListenCue.closeChimePcm())
        assertTrue(CloseCue.waitCapMs(chime, MicReopen.SETTLE_MS) >= CloseCue.minPlayMs(chime) + MicReopen.SETTLE_MS)
        assertTrue(CloseCue.waitCapMs(chime, MicReopen.BLUETOOTH_TAIL_MS) >= CloseCue.minPlayMs(chime))
    }

    @Test
    fun `the player is released only after the whole chime has had time to be heard`() = runTest {
        // A head position that says "drained" at once (it runs ahead of the speaker): the release still waits.
        val chime = ListenCue.durationMs(ListenCue.closeChimePcm())
        var written = 0
        val start = testScheduler.currentTime
        val done = CloseCue.playBounded(
            CloseCue.waitCapMs(chime, MicReopen.SETTLE_MS),
            CloseCue.minPlayMs(chime),
            write = { written = ListenCue.closeChimePcm().size },
            drained = {},
            now = { testScheduler.currentTime },
        )
        assertTrue(done)
        assertEquals(ListenCue.closeChimePcm().size, written)
        assertTrue(testScheduler.currentTime - start >= chime + CloseCue.OUTPUT_LATENCY_MS)
    }

    @Test
    fun `a playback that never drains is cut at the cap, a normal one completes`() = runTest {
        val start = testScheduler.currentTime
        val now = { testScheduler.currentTime }
        assertFalse(CloseCue.playBounded(500, 0, write = {}, drained = { delay(60_000) }, now = now))
        assertEquals(500L, testScheduler.currentTime - start)
        assertTrue(CloseCue.playBounded(500, 0, write = {}, drained = { delay(300) }, now = now))
    }
}

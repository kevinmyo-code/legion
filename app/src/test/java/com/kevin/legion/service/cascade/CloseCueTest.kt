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
            "close_cue played ms=430 reason=sleep_phrase route=speaker by=timestamp latency_ms=140 base=9000 consumed_frames=6960 of=6960 drained=1",
            CloseCue.playedLine("sleep_phrase", "speaker", CloseCue.Heard(CloseCue.By.TIMESTAMP, 430, 9_000, 6_960, 6_960), chimeMs = 290),
        )
        assertEquals(
            "close_cue played ms=540 reason=sleep_phrase route=bt by=floor latency_ms=none base=none consumed_frames=-1 of=0 drained=0",
            CloseCue.playedLine("sleep_phrase", "bt", CloseCue.Heard(CloseCue.By.FLOOR, 540, null), chimeMs = 290),
        )
        // The 14:42 shape: the wait ended on the floor and the mixer took none of it. The line says so.
        assertEquals(
            "close_cue played ms=546 reason=sleep_phrase route=speaker by=floor latency_ms=none base=0 consumed_frames=0 of=6960 drained=0",
            CloseCue.playedLine("sleep_phrase", "speaker", CloseCue.Heard(CloseCue.By.FLOOR, 546, 0, 0, 6_960), chimeMs = 290),
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
    fun `the timestamp decides only on frames presented since its own baseline, never before the chime's length`() {
        val spec = CloseCue.Spec(chimeFrames = 6_960, chimeMs = 290, floorMs = 540, capMs = 600)
        // Resets on flush (baseline 0): not yet, then the chime's frames presented decides, before the floor.
        assertNull(CloseCue.heardBy(elapsedMs = 200, presented = 3_000, base = 0, stalledMs = 0, spec = spec))
        assertEquals(CloseCue.By.TIMESTAMP, CloseCue.heardBy(elapsedMs = 400, presented = 6_960, base = 0, stalledMs = 0, spec = spec))
        // Does NOT reset on flush (closing confirm T1): far above the chime's count from the start, it still
        // waits for base + chimeFrames, and never accepts before the chime's own length.
        val base = 5_000_000L
        assertNull(CloseCue.heardBy(elapsedMs = 10, presented = base + 100, base = base, stalledMs = 0, spec = spec))
        assertNull(CloseCue.heardBy(elapsedMs = 100, presented = base + 7_000, base = base, stalledMs = 0, spec = spec))
        assertEquals(CloseCue.By.TIMESTAMP, CloseCue.heardBy(elapsedMs = 290, presented = base + 6_960, base = base, stalledMs = 0, spec = spec))
        // No baseline: the floor, even if a timestamp appears later.
        assertNull(CloseCue.heardBy(elapsedMs = 500, presented = 9_999_999, base = null, stalledMs = 0, spec = spec))
        assertEquals(CloseCue.By.FLOOR, CloseCue.heardBy(elapsedMs = 540, presented = 9_999_999, base = null, stalledMs = 0, spec = spec))
        // None at all: the floor.
        assertEquals(CloseCue.By.FLOOR, CloseCue.heardBy(elapsedMs = 540, presented = null, base = 0, stalledMs = 0, spec = spec))
        // Stalled short of the end: the floor once it has not moved for STALL_MS.
        assertNull(CloseCue.heardBy(elapsedMs = 560, presented = 5_000, base = 0, stalledMs = 100, spec = spec))
        assertEquals(CloseCue.By.FLOOR, CloseCue.heardBy(elapsedMs = 560, presented = 5_000, base = 0, stalledMs = CloseCue.STALL_MS, spec = spec))
        // Moving but slow: the cap.
        assertEquals(CloseCue.By.CAP, CloseCue.heardBy(elapsedMs = 600, presented = 6_000, base = 0, stalledMs = 0, spec = spec))
    }

    @Test
    fun `the wait follows a fake device timestamp, reset or not, stalled or missing`() = runTest {
        val now = { testScheduler.currentTime }
        val chime = ListenCue.durationMs(ListenCue.closeChimePcm())
        val frames = ListenCue.closeChimePcm().size / 2L
        val spec = CloseCue.Spec(frames, chime, CloseCue.minPlayMs(chime), CloseCue.waitCapMs(chime, MicReopen.SETTLE_MS))
        // A source that presents 24 frames a ms after 100 ms of output latency, from [offset].
        fun source(offset: Long): () -> Long? {
            val start = now()
            return { offset + ((now() - start - 100).coerceAtLeast(0) * 24) }
        }
        // Resets on flush: done when the chime's last frame is presented.
        val reset = CloseCue.playHeard(write = {}, presented = source(0), spec = spec, now = now)
        assertEquals(CloseCue.By.TIMESTAMP, reset.by)
        assertTrue("${reset.ms}", reset.ms in chime + 100..chime + 100 + 20)
        // Does not reset on flush: starts far above the chime's count, and still waits the same.
        val kept = CloseCue.playHeard(write = {}, presented = source(5_000_000), spec = spec, now = now)
        assertEquals(CloseCue.By.TIMESTAMP, kept.by)
        assertEquals(5_000_000L, kept.base)
        assertTrue("${kept.ms}", kept.ms >= chime && kept.ms in chime + 100..chime + 100 + 20)
        // Missing: the floor.
        val none = CloseCue.playHeard(write = {}, presented = { null }, spec = spec, now = now)
        assertEquals(CloseCue.By.FLOOR, none.by)
        assertTrue("${none.ms}", none.ms in spec.floorMs..spec.floorMs + 20)
        // Stalled: the floor, once it has stopped moving.
        assertEquals(CloseCue.By.FLOOR, CloseCue.playHeard(write = {}, presented = { 42L }, spec = spec, now = now).by)
        // A write that never returns: the cap.
        val cap = CloseCue.playHeard(write = { delay(60_000) }, presented = { null }, spec = spec, now = now)
        assertEquals(CloseCue.By.CAP, cap.by)
        assertEquals(spec.capMs, cap.ms)
    }
}

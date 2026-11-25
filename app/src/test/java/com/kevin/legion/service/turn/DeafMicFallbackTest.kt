package com.kevin.legion.service.turn

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Kevin, 2026-11-24, in the car: with the head unit's Bluetooth connected, Alfred did not hear him at
 * all while the phone's log read `bluetooth mic routed=true` on every turn. The silence window that
 * notices, and the claim that stops a second capture opening while the switch to the phone's own mic
 * is still running. Made-up levels and clocks.
 */
class DeafMicFallbackTest {

    private val silence = 0
    private val roomNoise = 240

    /** One buffer at [peak], [atMs] on the conversation's clock, on a routed hands-free mic. */
    private fun DeafMicFallback.handsFree(
        peak: Int,
        atMs: Long,
        judging: Boolean = true,
        inLiveCall: Boolean = false,
    ) = read(peak, atMs, judging = judging, onHandsFreeMic = true, inLiveCall = inLiveCall)

    @Test
    fun `a routed hands-free mic that sends digital silence loses the conversation`() {
        val f = DeafMicFallback()
        assertFalse("short of the window, a silent capture is a link finding its feet", f.handsFree(silence, 0))
        assertFalse(f.handsFree(silence, BluetoothMic.DEAF_MS - 1))
        assertTrue("nothing audible for the whole window", f.handsFree(silence, BluetoothMic.DEAF_MS))
    }

    @Test
    fun `the verdict comes once per conversation, however long the silence runs`() {
        val f = DeafMicFallback()
        f.handsFree(silence, 0)
        assertTrue(f.handsFree(silence, BluetoothMic.DEAF_MS))
        // A deaf phone mic after the switch must not bounce the route back and forth.
        assertFalse(f.handsFree(silence, BluetoothMic.DEAF_MS * 4))
        assertFalse(f.handsFree(silence, BluetoothMic.DEAF_MS * 40))
    }

    @Test
    fun `a hands-free mic that hears the room keeps the conversation`() {
        val f = DeafMicFallback()
        f.handsFree(roomNoise, 0)
        assertFalse("a man saying nothing is not a dead microphone", f.handsFree(silence, BluetoothMic.DEAF_MS * 4))
        assertEquals("and the loudest sample is kept", roomNoise, f.peakSinceOpen)
    }

    @Test
    fun `the phone's own mic never falls back, because there is nowhere to fall`() {
        val f = DeafMicFallback()
        f.read(silence, 0, judging = true, onHandsFreeMic = false, inLiveCall = false)
        assertFalse(
            f.read(silence, BluetoothMic.DEAF_MS * 4, judging = true, onHandsFreeMic = false, inLiveCall = false),
        )
    }

    /**
     * Mute keeps the capture open on purpose (reopening it mid-call is a click and a claim fight), and
     * a man who chose silence must never be read as a broken microphone. Nor may the time he spent
     * muted be spent again the instant he comes back: the window starts over.
     */
    @Test
    fun `muted is never deaf, and unmuting starts a whole new window`() {
        val f = DeafMicFallback()
        f.handsFree(silence, 0)
        for (atMs in 100L..10_000L step 100L) {
            assertFalse("muted at $atMs ms", f.handsFree(silence, atMs, judging = false))
        }
        assertFalse("the first buffer back is the start of the window", f.handsFree(silence, 10_100))
        assertFalse("not the end of it", f.handsFree(silence, 10_100 + BluetoothMic.DEAF_MS - 1))
        assertTrue("a whole window of silence after the unmute does fall back", f.handsFree(silence, 12_600))
    }

    /**
     * A hands-free unit's own echo canceller can hold its mic to nearly nothing while the far end
     * speaks, and that is a working mic doing its job.
     */
    @Test
    fun `Alfred playing holds the window, however long he talks`() {
        val f = DeafMicFallback()
        f.handsFree(silence, 0)
        for (atMs in 100L..9_000L step 100L) {
            assertFalse("playing at $atMs ms", f.handsFree(silence, atMs, judging = false))
        }
        assertEquals("and the peak he had no chance to make is forgotten", 0, f.peakSinceOpen)
        assertFalse(f.handsFree(silence, 9_100))
    }

    /**
     * A Call Alfred call in the car takes the hands-free branch of the route, so this fallback can see
     * one. It must not act: exiting call audio mid-call drops the call's own hands-free route, and the
     * call screen already gives him Mute and Speaker. The deaf-mic rescue is for a conversation, not
     * for a call in progress.
     */
    @Test
    fun `a live call keeps its hands-free route, silent or not`() {
        val f = DeafMicFallback()
        f.handsFree(silence, 0, inLiveCall = true)
        assertFalse(f.handsFree(silence, BluetoothMic.DEAF_MS, inLiveCall = true))
        assertFalse(f.handsFree(silence, BluetoothMic.DEAF_MS * 10, inLiveCall = true))
    }

    @Test
    fun `the fallback waits longer than the link's own settle`() {
        assertTrue("a link still coming up is never called deaf", BluetoothMic.DEAF_MS > BluetoothMic.SCO_SETTLE_MS)
    }

    /**
     * The 2026-11-24 review's blocker: the switch suspends for seconds (the dying capture's join and
     * the new route's settle) on the same single thread as the open path's "nothing is capturing, open
     * one" check, and a hand-over that let go of the capture before taking the next one
     * let that check open a SECOND capture inside the gap, on exactly the failure this fallback is for.
     */
    @Test
    fun `the hand-over holds the capture for the whole switch, its suspensions included`() = runTest {
        val f = DeafMicFallback()
        assertFalse("nothing is switching to begin with", f.switching)
        val opened = mutableListOf<Long>()
        val switch = launch {
            f.handOver {
                delay(700) // the dying capture's join
                delay(900) // the new route's settle
            }
        }
        // The open path's own check, inside the switch's suspensions, on the same thread.
        launch {
            var last = 0L
            for (atMs in listOf(1L, 300L, 700L, 1_200L)) {
                delay(atMs - last)
                last = atMs
                if (!f.switching) opened += atMs
            }
        }
        switch.join()
        assertEquals("no second capture opened inside the switch", emptyList<Long>(), opened)
        assertFalse("and the slot is free again once the switch is done", f.switching)
    }

    @Test
    fun `a switch that throws still releases the capture`() = runTest {
        val f = DeafMicFallback()
        runCatching { f.handOver { error("the route went while we were switching") } }
        assertFalse("a failed switch must not wedge the conversation mic shut", f.switching)
    }

    @Test
    fun `the window reports how long it has been counting, and nothing when it is not`() {
        val f = DeafMicFallback()
        assertEquals("not counting yet", 0L, f.heldMs(5_000))
        f.handsFree(silence, 1_000)
        assertEquals(1_500L, f.heldMs(2_500))
        f.reset()
        assertEquals("a new capture starts over", 0L, f.heldMs(2_500))
    }
}

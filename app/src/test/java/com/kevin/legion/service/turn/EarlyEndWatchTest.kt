package com.kevin.legion.service.turn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** He spoke again soon after an early end: the drive log's false-early-end count. */
class EarlyEndWatchTest {
    private val w = EndOfTurnPolicy.WINDOW_MS

    @Test
    fun `speech starting within 1_5 s of an early end is reported with its delay`() {
        val watch = EarlyEndWatch()
        watch.onEnd(early = true)
        repeat(20) { watch.onWindow() }
        // Confirmed now, begun 7 windows ago: 13 windows after the end.
        assertEquals(13 * w, watch.onStart(onsetWindows = 7))
        // Once only.
        assertNull(watch.onStart(onsetWindows = 7))
    }

    @Test
    fun `speech after 1_5 s, or with no early end, is not reported`() {
        val late = EarlyEndWatch()
        late.onEnd(early = true)
        repeat(EarlyEndWatch.LIMIT_MS / w + 8) { late.onWindow() }
        assertNull(late.onStart(onsetWindows = 7))
        val none = EarlyEndWatch()
        repeat(5) { none.onWindow() }
        assertNull(none.onStart(onsetWindows = 1))
    }

    @Test
    fun `the limit is 1_5 s and the edge counts`() {
        assertEquals(1_500, EarlyEndWatch.LIMIT_MS)
        val edge = EarlyEndWatch()
        edge.onEnd(early = true)
        repeat(46) { edge.onWindow() } // 1,472 ms
        assertEquals(46 * w, edge.onStart(onsetWindows = 0))
    }

    @Test
    fun `the log line the bench counts`() {
        assertEquals("eot_early_resumed=1 after_ms=416", EarlyEndWatch.line(416))
    }
}

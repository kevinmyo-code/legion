package com.kevin.legion.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ObdHistory] was ported from Midnight AI with the rest of the OBD stack and
 * then sat with NO caller until `ui/TelemetryScreen.kt` was built (2026-08-04),
 * which is why it arrived untested. The chart now depends on
 * [ObdHistory.downsample] being both bounded and correctly ordered, so it gets
 * pinned here before anything is built on top of it.
 */
class ObdHistoryTest {
    private val start = 1_753_000_000_000L

    @Test
    fun `downsample leaves a series that already fits alone, but sorts it`() {
        val points = listOf(start + 2_000 to 3.0, start to 1.0, start + 1_000 to 2.0)
        assertEquals(
            listOf(start to 1.0, start + 1_000 to 2.0, start + 2_000 to 3.0),
            ObdHistory.downsample(points, maxBuckets = 200),
        )
    }

    @Test
    fun `downsample caps a long series and averages within each bucket`() {
        val points = (0 until 10_000).map { (start + it * 30_000L) to it.toDouble() }
        val bucketed = ObdHistory.downsample(points, maxBuckets = 100)
        // Bucket width is integer-divided, so the final partial bucket can add
        // one - the contract is "at most maxBuckets" to within that rounding,
        // not an exact count.
        assertTrue("got ${bucketed.size} buckets", bucketed.size in 1..101)
        assertEquals(bucketed, bucketed.sortedBy { it.first })
        // First bucket averages the earliest run of values, so it must sit far
        // below the last - a bucketing that lost order would blur them together.
        assertTrue(bucketed.first().second < bucketed.last().second)
    }

    @Test
    fun `downsample of nothing is nothing`() {
        assertEquals(emptyList<Pair<Long, Double>>(), ObdHistory.downsample(emptyList()))
    }

    @Test
    fun `splitDrives cuts on a gap longer than the parked threshold`() {
        val newestFirst = listOf(
            start + ObdHistory.DRIVE_GAP_MS + 90_000,
            start + ObdHistory.DRIVE_GAP_MS + 60_000,
            start + 30_000,
            start,
        )
        val drives = ObdHistory.splitDrives(newestFirst)
        assertEquals(2, drives.size)
        assertEquals(start + ObdHistory.DRIVE_GAP_MS + 60_000, drives[0].fromMs)
        assertEquals(start, drives[1].fromMs)
        assertEquals(start + 30_000, drives[1].toMs)
    }

    @Test
    fun `a single sample is one drive of zero length, and no samples are no drives`() {
        assertEquals(1, ObdHistory.splitDrives(listOf(start)).size)
        assertEquals(start, ObdHistory.splitDrives(listOf(start)).first().fromMs)
        assertEquals(start, ObdHistory.splitDrives(listOf(start)).first().toMs)
        assertEquals(emptyList<ObdHistory.DriveWindow>(), ObdHistory.splitDrives(emptyList()))
    }

    @Test
    fun `an unknown PID keeps its raw code rather than being dropped or crashing`() {
        assertEquals("RPM", ObdHistory.pidLabel("010C"))
        assertEquals("0199", ObdHistory.pidLabel("0199"))
    }
}

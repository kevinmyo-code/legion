package com.kevin.legion.ui.fleet

import com.kevin.legion.data.local.PidSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic coverage for the TELEMETRY screen. Plain JVM, no Room, no
 * Android - same posture as [FleetRowsTest] and [CarRowsTest].
 *
 * The load-bearing case here is [summaryLine]'s zero-count guard: SQL's
 * aggregate over an empty window is NULL, Room reads NULL into
 * [PidSummary]'s non-null `Double`s as 0.0, and rendering that would put a
 * confident, entirely fabricated "min 0.0 / avg 0.0 / max 0.0" in front of
 * the driver.
 */
class TelemetryRowsTest {
    private val now = 1_753_000_000_000L

    @Test
    fun `a bounded range starts one span back`() {
        assertEquals(now - 7L * 24 * 60 * 60 * 1000, rangeStartMs(TelemetryRange.WEEK, now))
        assertEquals(now - 365L * 24 * 60 * 60 * 1000, rangeStartMs(TelemetryRange.YEAR, now))
    }

    @Test
    fun `ALL has no lower bound`() {
        assertEquals(0L, rangeStartMs(TelemetryRange.ALL, now))
    }

    @Test
    fun `a range longer than the clock floors at zero rather than going negative`() {
        assertEquals(0L, rangeStartMs(TelemetryRange.YEAR, 1_000L))
    }

    @Test
    fun `readings are formatted to their unit's precision, not their magnitude`() {
        assertEquals("1,847 rpm", formatReading(1847.4, "rpm"))
        assertEquals("800 rpm", formatReading(800.0, "rpm"))
        assertEquals("12.4 V", formatReading(12.4, "V"))
        assertEquals("-2.3 %", formatReading(-2.3, "%"))
        assertEquals("88 °C", formatReading(88.0, "°C"))
    }

    @Test
    fun `a unitless reading renders without a trailing space`() {
        assertEquals("42.0", formatReading(42.0, ""))
    }

    @Test
    fun `known PIDs sort into reading order and unknown ones are kept at the end`() {
        val recorded = listOf("ATRV", "SOMETHING_NEW", "0105", "010C")
        assertEquals(listOf("010C", "0105", "ATRV", "SOMETHING_NEW"), orderedPids(recorded))
    }

    @Test
    fun `orderedPids never invents a PID the car did not record`() {
        assertEquals(listOf("0105"), orderedPids(listOf("0105")))
        assertEquals(emptyList<String>(), orderedPids(emptyList()))
    }

    @Test
    fun `a summary over a real window states min, avg, max and its own count`() {
        val summary = PidSummary(min = 612.0, max = 3410.0, avg = 1847.4, count = 5242, firstMs = 1L, lastMs = 2L)
        assertEquals(
            "min 612 rpm  ·  avg 1,847 rpm  ·  max 3,410 rpm  ·  5,242 readings",
            summaryLine(summary, "rpm"),
        )
    }

    @Test
    fun `a zero-count summary is absent, not a row of confident zeroes`() {
        assertNull(summaryLine(PidSummary(0.0, 0.0, 0.0, 0, 0L, 0L), "rpm"))
        assertNull(summaryLine(null, "rpm"))
    }

    @Test
    fun `a series that hit the row cap reports itself truncated`() {
        val samples = (0 until 50).map { (now + it * 1000L) to it.toDouble() }
        val truncated = buildSeries(samples, "rpm", rawCount = 20_000, rowCap = 20_000)
        assertTrue(truncated.truncated)

        val whole = buildSeries(samples, "rpm", rawCount = 50, rowCap = 20_000)
        assertFalse(whole.truncated)
    }

    @Test
    fun `a series is downsampled to the bucket cap and ordered oldest-first`() {
        val samples = (0 until 5_000).map { (now + it * 30_000L) to it.toDouble() }.reversed()
        val series = buildSeries(samples, "rpm", rawCount = 5_000, rowCap = 20_000, maxPoints = 100)
        assertTrue("expected at most ~100 buckets, got ${series.points.size}", series.points.size <= 101)
        assertEquals(series.points, series.points.sortedBy { it.first })
    }

    @Test
    fun `a span with one date does not repeat itself`() {
        assertEquals(com.kevin.legion.util.shortDate(now), spanLine(now, now))
    }

    @Test
    fun `a span with no endpoints is absent`() {
        assertNull(spanLine(null, now))
        assertNull(spanLine(now, null))
    }
}

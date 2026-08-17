package com.kevin.legion.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [DrivingDialMath.kt]'s pure layer. Plain JUnit, no Robolectric, no Canvas - the
 * things this suite exists to pin: [dialFraction]'s clamping, [litSegmentCount]'s rounding,
 * [redlineSegmentStartIndex]'s ceil-not-round guarantee, [scaleTicks]'s exact tick count, and the
 * one surviving unit-conversion factor the speed column depends on.
 */
class DrivingDialMathTest {

    // ------------------------------------------------------------------------ dialFraction

    @Test
    fun `a mid-scale value fractions correctly`() {
        assertEquals(0.5f, dialFraction(4000f, 8000f))
    }

    @Test
    fun `a value at zero fractions to zero`() {
        assertEquals(0f, dialFraction(0f, 8000f))
    }

    @Test
    fun `a value at the scale ceiling fractions to one`() {
        assertEquals(1f, dialFraction(8000f, 8000f))
    }

    @Test
    fun `a value past the scale ceiling clamps to one, never overshoots`() {
        assertEquals(1f, dialFraction(9200f, 8000f))
    }

    @Test
    fun `a negative value clamps to zero, never goes negative`() {
        assertEquals(0f, dialFraction(-40f, 8000f))
    }

    @Test
    fun `a non-positive scale floors to zero rather than dividing by zero or going negative`() {
        assertEquals(0f, dialFraction(4000f, 0f))
        assertEquals(0f, dialFraction(4000f, -8000f))
    }

    // -------------------------------------------------------------------- litSegmentCount

    @Test
    fun `zero fraction lights nothing`() {
        assertEquals(0, litSegmentCount(0f, 20))
    }

    @Test
    fun `full fraction lights every segment`() {
        assertEquals(20, litSegmentCount(1f, 20))
    }

    @Test
    fun `a fraction rounds to the nearest segment, not the floor`() {
        // 0.49 * 20 = 9.8 - rounds up to 10 rather than flooring to 9.
        assertEquals(10, litSegmentCount(0.49f, 20))
        // 0.40 * 20 = 8.0 exactly - no rounding ambiguity, a clean floor-equals-round case.
        assertEquals(8, litSegmentCount(0.40f, 20))
    }

    @Test
    fun `an out-of-range fraction clamps instead of desyncing the lit count from the array bounds`() {
        assertEquals(20, litSegmentCount(1.4f, 20))
        assertEquals(0, litSegmentCount(-0.2f, 20))
    }

    @Test
    fun `zero or negative total segments lights nothing rather than dividing by zero`() {
        assertEquals(0, litSegmentCount(0.5f, 0))
        assertEquals(0, litSegmentCount(0.5f, -4))
    }

    // -------------------------------------------------------------- redlineSegmentStartIndex

    @Test
    fun `redline starts at the ceiling of the redline fraction times the segment count`() {
        // 0.85 * 20 = 17.0 exactly - the top three of twenty segments (17, 18, 19) are redline.
        assertEquals(17, redlineSegmentStartIndex(20, 0.85f))
    }

    @Test
    fun `a fractional boundary rounds the redline zone to cover it, never to shrink it`() {
        // 0.85 * 14 = 11.9 - ceil guarantees the zone starts at 12, not 11 (which would under-
        // promise "top 15%" by rounding the zone smaller than printed).
        assertEquals(12, redlineSegmentStartIndex(14, 0.85f))
    }

    @Test
    fun `zero or negative total segments starts the redline zone at zero`() {
        assertEquals(0, redlineSegmentStartIndex(0, 0.85f))
        assertEquals(0, redlineSegmentStartIndex(-4, 0.85f))
    }

    // ---------------------------------------------------------------------- isRedlineSegment

    @Test
    fun `a segment below the redline start index is not redline`() {
        assertEquals(false, isRedlineSegment(16, 17))
    }

    @Test
    fun `a segment at or above the redline start index is redline`() {
        assertEquals(true, isRedlineSegment(17, 17))
        assertEquals(true, isRedlineSegment(19, 17))
    }

    // ---------------------------------------------------------------------------- scaleTicks

    @Test
    fun `scale ticks land on exact fractions with no drift`() {
        val ticks = scaleTicks(120f, 40f)
        assertEquals(4, ticks.size)
        assertEquals(0f to 0f, ticks[0])
        assertEquals((40f / 120f) to 40f, ticks[1])
        assertEquals((80f / 120f) to 80f, ticks[2])
        assertEquals(1f to 120f, ticks[3])
    }

    @Test
    fun `scale ticks cover a scale the step does not divide evenly`() {
        // 5500 / 1000 = 5.5 - six ticks (0..5000), the last one short of the ceiling itself.
        val ticks = scaleTicks(5500f, 1000f)
        assertEquals(6, ticks.size)
        assertEquals(5000f, ticks.last().second)
    }

    @Test
    fun `a non-positive scale or step returns no ticks rather than looping or dividing by zero`() {
        assertEquals(emptyList<Pair<Float, Float>>(), scaleTicks(0f, 40f))
        assertEquals(emptyList<Pair<Float, Float>>(), scaleTicks(120f, 0f))
        assertEquals(emptyList<Pair<Float, Float>>(), scaleTicks(-120f, 40f))
    }

    // ---------------------------------------------------------------------- conversions

    @Test
    fun `kmh to mph matches the factor already used at CarToolbelt`() {
        // 100 km/h is the canonical check value; CarToolbelt's own conversion rounds the
        // same 0.621371 factor to 62mph for a 100 km/h reading.
        assertEquals(62.1371f, kmhToMph(100f), 0.0001f)
    }
}

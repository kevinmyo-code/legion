package com.kevin.legion.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [DrivingDialMath.kt]'s pure layer. Plain JUnit, no Robolectric, no Canvas - the
 * things this suite exists to pin: [selectDialSource]'s RPM-leads-speed-fallback-NONE ladder, and
 * [dialFraction]'s clamping (including the degenerate non-positive scale case), plus the two
 * literal unit-conversion factors the cockpit's SPEED/COOLANT pods depend on.
 */
class DrivingDialMathTest {

    // ------------------------------------------------------------ dial source selection

    @Test
    fun `RPM leads when both PIDs have been recorded`() {
        assertEquals(DialSource.RPM, selectDialSource(hasRpm = true, hasSpeed = true))
    }

    @Test
    fun `RPM alone selects RPM`() {
        assertEquals(DialSource.RPM, selectDialSource(hasRpm = true, hasSpeed = false))
    }

    @Test
    fun `speed is RPM's fallback when RPM has never been recorded`() {
        assertEquals(DialSource.SPEED, selectDialSource(hasRpm = false, hasSpeed = true))
    }

    @Test
    fun `neither PID recorded selects NONE`() {
        assertEquals(DialSource.NONE, selectDialSource(hasRpm = false, hasSpeed = false))
    }

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

    // ------------------------------------------------------------------- sweepAngleDegrees

    @Test
    fun `sweep angle scales linearly with fraction across the full 270-degree sweep`() {
        assertEquals(0f, sweepAngleDegrees(0f))
        assertEquals(135f, sweepAngleDegrees(0.5f))
        assertEquals(270f, sweepAngleDegrees(1f))
    }

    @Test
    fun `sweep angle clamps an out-of-range fraction instead of overshooting the dial`() {
        assertEquals(270f, sweepAngleDegrees(1.4f))
        assertEquals(0f, sweepAngleDegrees(-0.2f))
    }

    // ---------------------------------------------------------------------- conversions

    @Test
    fun `kmh to mph matches the factor already used at CarToolbelt`() {
        // 100 km/h is the canonical check value; CarToolbelt's own conversion rounds the
        // same 0.621371 factor to 62mph for a 100 km/h reading.
        assertEquals(62.1371f, kmhToMph(100f), 0.0001f)
    }

    @Test
    fun `celsius to fahrenheit matches the c times 9 over 5 plus 32 formula`() {
        assertEquals(32f, celsiusToFahrenheit(0f), 0.0001f)
        assertEquals(212f, celsiusToFahrenheit(100f), 0.0001f)
        assertEquals(197.6f, celsiusToFahrenheit(92f), 0.0001f)
    }
}

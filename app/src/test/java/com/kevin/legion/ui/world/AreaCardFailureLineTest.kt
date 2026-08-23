package com.kevin.legion.ui.world

import com.kevin.legion.location.AirNow
import com.kevin.legion.service.LiveToolbox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-function coverage for [AreaCard]'s two failure-mapping functions (command-center ticket
 * 08) - [locationFailureMessage] (no permission / providers off / no fix, the three ways
 * [LiveToolbox.resolveCurrentLocation] can fail before there is even an area to check) and
 * [aqiLine] (the ticket's own "a missing reading is NEVER rendered as clean air" rule, at the
 * function that actually decides what gets rendered). Same posture as
 * [PackageFlightCardFailureLineTest] - no Compose harness in this repo, so the mapping function
 * IS the testable unit.
 */
class AreaCardFailureLineTest {

    // ------------------------------------------------------------------ locationFailureMessage

    @Test
    fun `the three location failure branches produce distinct lines`() {
        val lines = listOf(
            locationFailureMessage(LiveToolbox.LocationReadout.NoPermission),
            locationFailureMessage(LiveToolbox.LocationReadout.ProvidersOff),
            locationFailureMessage(LiveToolbox.LocationReadout.NoFix),
        )
        assertEquals(lines.size, lines.toSet().size)
    }

    @Test
    fun `NoPermission names the fix, not just the symptom`() {
        assertTrue(locationFailureMessage(LiveToolbox.LocationReadout.NoPermission).contains("permission"))
    }

    // ------------------------------------------------------------------ aqiLine (never renders "clean air" for a miss)

    @Test
    fun `a real AQI reading is rendered with its number and category`() {
        val reading = AirNow.Reading.Ok(aqi = 42, category = "Good", pollutant = "PM2.5", reportingArea = "Houston")
        val line = aqiLine(reading)
        assertTrue(line.contains("42"))
        assertTrue(line.contains("Good"))
    }

    @Test
    fun `NoKey, Unreachable, and NoData each say so distinctly - never rendered as clean air`() {
        val noKey = aqiLine(AirNow.Reading.NoKey)
        val unreachable = aqiLine(AirNow.Reading.Unreachable)
        val noData = aqiLine(AirNow.Reading.NoData)
        val lines = listOf(noKey, unreachable, noData)

        assertEquals("all three miss-states must be textually distinct", lines.size, lines.toSet().size)
        for (line in lines) {
            assertNotEquals(
                "a missing AQI reading must never collapse into the wording a real 'Good' reading would use",
                aqiLine(AirNow.Reading.Ok(aqi = 10, category = "Good", pollutant = "O3", reportingArea = "Nowhere")),
                line,
            )
            assertTrue(
                "\"$line\" must not itself claim a category like \"Good\"",
                !line.contains("Good", ignoreCase = false),
            )
        }
    }

    @Test
    fun `NoKey reads as a setup problem, not a network problem`() {
        assertTrue(aqiLine(AirNow.Reading.NoKey).contains("set up"))
    }
}

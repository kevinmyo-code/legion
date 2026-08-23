package com.kevin.legion.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure parse tests for [AirNow.parse] (command-center ticket 08) - no network, no Context, same
 * posture as [AreaInfoTest]. The ticket's own rule under test: **a missing reading is never
 * rendered as clean air**, so an empty/malformed body must come back `null` (which [AirNow.current]
 * turns into [AirNow.Reading.NoData], never a fabricated "Good").
 */
class AirNowTest {

    @Test
    fun `parse picks the row with the highest AQI as the headline reading`() {
        val body = """
            [
                {"ReportingArea":"Houston","StateCode":"TX","ParameterName":"O3","AQI":31,"CategoryName":"Good"},
                {"ReportingArea":"Houston","StateCode":"TX","ParameterName":"PM2.5","AQI":58,"CategoryName":"Moderate"}
            ]
        """.trimIndent()
        val reading = AirNow.parse(body)
        assertEquals(58, reading?.aqi)
        assertEquals("Moderate", reading?.category)
        assertEquals("PM2.5", reading?.pollutant)
        assertEquals("Houston", reading?.reportingArea)
    }

    @Test
    fun `parse on an empty array returns null, never a fabricated reading`() {
        assertNull(AirNow.parse("[]"))
    }

    @Test
    fun `parse on an unparseable body returns null`() {
        assertNull(AirNow.parse("not json"))
    }

    @Test
    fun `parse skips a row with no AQI field rather than treating it as zero`() {
        val body = """[{"ReportingArea":"Nowhere","ParameterName":"O3","CategoryName":"Good"}]"""
        assertNull(AirNow.parse(body))
    }

    @Test
    fun `parse falls back to plain labels when a field is blank`() {
        val body = """[{"AQI":10}]"""
        val reading = AirNow.parse(body)
        assertEquals("unknown category", reading?.category)
        assertEquals("unknown pollutant", reading?.pollutant)
        assertEquals("the nearest station", reading?.reportingArea)
    }
}

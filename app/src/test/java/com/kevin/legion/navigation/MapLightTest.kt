package com.kevin.legion.navigation

import java.time.Instant
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure sunrise/sunset to look choice (mapbox-plus ticket 01): across midnight, polar, no fix. */
class MapLightTest {
    private fun ms(iso: String) = Instant.parse(iso).toEpochMilli()

    // Houston, 2026-10-23: sunrise about 12:22Z (07:22 CDT), sunset about 23:30Z (18:30 CDT).
    private val houstonLat = 29.76
    private val houstonLon = -95.37

    private fun houston(iso: String, mode: MapStyleMode = MapStyleMode.AUTO) =
        MapLightPicker.choose(mode, ms(iso), houstonLat, houstonLon, systemDark = false)

    private fun auckland(iso: String) = MapLightPicker.choose(MapStyleMode.AUTO, ms(iso), -36.85, 174.76, false)

    @Test
    fun `the computed Houston sunrise and sunset land within a quarter hour of the almanac`() {
        val day = SunTimes.solarDay(ms("2026-10-23T17:00:00Z"), houstonLat, houstonLon) as SolarDay.Normal
        val quarterHour = 15 * 60_000L
        assertTrue(abs(day.riseMs - ms("2026-10-23T12:22:00Z")) < quarterHour)
        assertTrue(abs(day.setMs - ms("2026-10-23T23:30:00Z")) < quarterHour)
    }

    @Test
    fun `day between sunrise and sunset, night otherwise`() {
        assertEquals(MapLight.NIGHT, houston("2026-10-23T12:00:00Z"))
        assertEquals(MapLight.DAY, houston("2026-10-23T13:00:00Z"))
        assertEquals(MapLight.DAY, houston("2026-10-23T17:00:00Z"))
        assertEquals(MapLight.DAY, houston("2026-10-23T23:00:00Z"))
        assertEquals(MapLight.NIGHT, houston("2026-10-24T00:00:00Z"))
    }

    @Test
    fun `night holds across local midnight on both sides of the date boundary`() {
        // 23:00 CDT on the 23rd, local midnight (05:00Z), and 02:00 CDT on the 24th.
        assertEquals(MapLight.NIGHT, houston("2026-10-24T04:00:00Z"))
        assertEquals(MapLight.NIGHT, houston("2026-10-24T05:00:00Z"))
        assertEquals(MapLight.NIGHT, houston("2026-10-24T07:00:00Z"))
        // UTC midnight is early evening here and already dark by 00:30Z.
        assertEquals(MapLight.NIGHT, houston("2026-10-24T00:30:00Z"))
    }

    @Test
    fun `east of the date line the sun rises on the previous UTC date`() {
        // Auckland, NZDT: sunrise about 06:00 local on the 23rd is about 17:00Z on the 22nd.
        assertEquals(MapLight.DAY, auckland("2026-10-22T20:00:00Z"))
        assertEquals(MapLight.NIGHT, auckland("2026-10-23T12:00:00Z"))
        assertEquals(MapLight.NIGHT, auckland("2026-10-22T14:00:00Z"))
    }

    @Test
    fun `polar night has no sunrise and stays night, even at solar noon`() {
        // Tromso, near the winter solstice.
        val noon = ms("2026-12-21T11:00:00Z")
        assertEquals(SolarDay.AlwaysDown, SunTimes.solarDay(noon, 69.65, 18.96))
        assertEquals(MapLight.NIGHT, MapLightPicker.choose(MapStyleMode.AUTO, noon, 69.65, 18.96, systemDark = false))
    }

    @Test
    fun `midnight sun has no sunset and stays day, even at local midnight`() {
        val midnight = ms("2026-06-21T22:00:00Z")
        assertEquals(SolarDay.AlwaysUp, SunTimes.solarDay(midnight, 69.65, 18.96))
        assertEquals(MapLight.DAY, MapLightPicker.choose(MapStyleMode.AUTO, midnight, 69.65, 18.96, systemDark = true))
    }

    @Test
    fun `no fix follows the system dark theme and ignores the clock`() {
        val noon = ms("2026-10-23T17:00:00Z")
        assertEquals(MapLight.NIGHT, MapLightPicker.choose(MapStyleMode.AUTO, noon, null, null, systemDark = true))
        assertEquals(MapLight.DAY, MapLightPicker.choose(MapStyleMode.AUTO, noon, null, null, systemDark = false))
        // Half a fix is no fix.
        assertEquals(MapLight.NIGHT, MapLightPicker.choose(MapStyleMode.AUTO, noon, 29.7, null, systemDark = true))
    }

    @Test
    fun `Day and Night pin the look whatever the clock, the fix or the system theme say`() {
        assertEquals(MapLight.DAY, houston("2026-10-24T05:00:00Z", MapStyleMode.DAY))
        assertEquals(MapLight.NIGHT, houston("2026-10-23T17:00:00Z", MapStyleMode.NIGHT))
        assertEquals(MapLight.DAY, MapLightPicker.choose(MapStyleMode.DAY, 0L, null, null, systemDark = true))
    }

    @Test
    fun `the tap cycle runs Auto, Day, Night and round`() {
        assertEquals(MapStyleMode.DAY, MapStyleMode.AUTO.next())
        assertEquals(MapStyleMode.NIGHT, MapStyleMode.DAY.next())
        assertEquals(MapStyleMode.AUTO, MapStyleMode.NIGHT.next())
    }
}

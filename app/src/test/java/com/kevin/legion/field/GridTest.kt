package com.kevin.legion.field

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * UTM and MGRS against an independent implementation: the `mgrs` and `utm` (0.9) Python packages,
 * run 2026-11-10 in a scratch venv (never a dependency of this repo). Public landmarks and grid
 * edge cases only; no household location.
 */
class GridTest {
    private data class Case(val lat: Double, val lon: Double, val mgrs: String, val e: Double, val n: Double)

    private val cases = listOf(
        Case(0.0, 0.0, "31N AA 66021 00000", 166021.44, 0.0),
        Case(39.7392, -104.9903, "13S ED 00831 98811", 500831.13, 4398811.67),
        Case(-33.8688, 151.2093, "56H LH 34368 50948", 334368.63, 6250948.35),
        // Norway's widened 32V.
        Case(60.0, 5.5, "32V LM 04838 56575", 304838.83, 6656575.86),
        // Svalbard's odd zones.
        Case(78.2, 15.6, "33X WG 13696 80760", 513696.95, 8680760.05),
        Case(64.1466, -21.9426, "27W VM 54138 13689", 454138.38, 7113689.87),
        Case(-54.8, -68.3, "19F EV 45000 27239", 545000.05, 3927239.38),
        Case(83.9, -30.0, "26X MU 64424 17856", 464424.32, 9317856.47),
    )

    @Test
    fun `mgrs matches the reference to the metre, digits truncated`() {
        for (c in cases) assertEquals("${c.lat},${c.lon}", c.mgrs, Grid.mgrs(c.lat, c.lon))
    }

    @Test
    fun `utm easting and northing within 5 cm of the reference`() {
        for (c in cases) {
            val u = Grid.utm(c.lat, c.lon)!!
            assertEquals("${c.lat},${c.lon} E", c.e, u.easting, 0.05)
            assertEquals("${c.lat},${c.lon} N", c.n, u.northing, 0.05)
        }
    }

    @Test
    fun `round trip back to latitude and longitude within a centimetre`() {
        for (c in cases) {
            val (lat, lon) = Grid.toLatLon(Grid.utm(c.lat, c.lon)!!)
            assertEquals(c.lat, lat, 1e-7)
            assertEquals(c.lon, lon, 1e-7)
        }
    }

    @Test
    fun `lower precision truncates`() {
        assertEquals("13S ED 008 988", Grid.mgrs(39.7392, -104.9903, digits = 3))
        assertEquals("13S ED 0 9", Grid.mgrs(39.7392, -104.9903, digits = 1))
    }

    @Test
    fun `the poles are outside UTM and say so with null`() {
        assertNull(Grid.mgrs(85.0, 10.0))
        assertNull(Grid.utm(-81.0, 10.0))
    }

    @Test
    fun `utm text reads like a map`() {
        assertEquals("13S 500831E 4398811N", Grid.utm(39.7392, -104.9903)!!.text())
    }
}

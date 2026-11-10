package com.kevin.legion.field

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sun and moon against an independent ephemeris: PyEphem 4.2.1, run 2026-11-10 in a scratch venv
 * (pressure 0; sun centre at -50 arcmin, -6 and -12 deg; moon upper limb at -34 arcmin). Public
 * places only. Tolerances: 4 min for the sun (the sunrise equation's own accuracy), 5 min for the
 * moon (the truncated theory measured within about a minute).
 */
class AlmanacTest {
    private val sunTol = 4 * 60_000L
    private val moonTol = 5 * 60_000L

    private data class Ref(
        val lat: Double,
        val lon: Double,
        val startMs: Long,
        val sun: LongArray,
        val moonrise: Long,
        val moonset: Long,
    )

    // sun = sunrise, sunset, civil dawn, civil dusk, nautical dawn, nautical dusk.
    private val refs = mapOf(
        "denver" to Ref(
            39.7392, -104.9903, 1794294000000,
            longArrayOf(1794317940995, 1794354501142, 1794316226608, 1794356214902, 1794314285933, 1794358154652),
            1794323818285, 1794356540412,
        ),
        "sydney" to Ref(
            -33.8688, 151.2093, 1781964000000,
            longArrayOf(1781989197555, 1782024828905, 1781987533770, 1782026492690, 1781985658929, 1782028367531),
            1782005098658, 1782049133308,
        ),
        "quito" to Ref(
            -0.1807, -78.4678, 1773550800000,
            longArrayOf(1773573564199, 1773617157677, 1773572323627, 1773618398081, 1773570882910, 1773619838577),
            1773563470802, 1773608089335,
        ),
    )

    private fun near(label: String, expected: Long, actual: Long?, tol: Long) {
        assertNotNull(label, actual)
        assertTrue("$label off by ${(actual!! - expected) / 1000} s", abs(actual - expected) <= tol)
    }

    @Test
    fun `sunrise, sunset and both twilights`() {
        for ((name, r) in refs) {
            val noon = (r.sun[0] + r.sun[1]) / 2
            val d = Almanac.sunDay(noon, r.lat, r.lon)
            near("$name sunrise", r.sun[0], d.sunrise, sunTol)
            near("$name sunset", r.sun[1], d.sunset, sunTol)
            near("$name civil dawn", r.sun[2], d.civilDawn, sunTol)
            near("$name civil dusk", r.sun[3], d.civilDusk, sunTol)
            near("$name nautical dawn", r.sun[4], d.nauticalDawn, sunTol)
            near("$name nautical dusk", r.sun[5], d.nauticalDusk, sunTol)
            assertNull(d.polarNote)
        }
    }

    @Test
    fun `moonrise and moonset`() {
        for ((name, r) in refs) {
            val m = Almanac.moonDay(r.startMs, r.startMs, r.lat, r.lon)
            near("$name moonrise", r.moonrise, m.rise, moonTol)
            near("$name moonset", r.moonset, m.set, moonTol)
        }
    }

    @Test
    fun `illumination and phase names`() {
        // PyEphem moon_phase at noon UTC on 2026-11-10, 11-24, 12-01 and 11-17.
        assertEquals(0.016, Almanac.phase(1794312000000).first, 0.02)
        assertEquals(0.9979, Almanac.phase(1795521600000).first, 0.02)
        assertEquals(0.4747, Almanac.phase(1796126400000).first, 0.02)
        assertEquals(0.5026, Almanac.phase(1794916800000).first, 0.02)
        assertEquals("New moon", Almanac.phase(1794312000000).second)
        assertEquals("Full moon", Almanac.phase(1795521600000).second)
        val firstQuarter = Almanac.phase(1794916800000)
        assertEquals("First quarter", firstQuarter.second)
        assertTrue(firstQuarter.third)
        val lastQuarter = Almanac.phase(1796126400000)
        assertEquals("Last quarter", lastQuarter.second)
        assertFalse(lastQuarter.third)
    }

    @Test
    fun `polar night and polar day say so`() {
        // 69.65 N at the December solstice (2026-12-21 12:00 UTC): no sunrise; June: no sunset.
        val dec = Almanac.sunDay(1797854400000, 69.65, 18.96)
        assertNull(dec.sunrise)
        assertTrue(dec.polarNote!!.contains("does not rise"))
        val jun = Almanac.sunDay(1782043200000, 69.65, 18.96)
        assertNull(jun.sunset)
        assertTrue(jun.polarNote!!.contains("does not set"))
    }

    @Test
    fun `turnaround is sunset less the margin`() {
        val sunset = 1_000_000_000L
        val ahead = Almanac.turnaround(sunset - 3 * 3_600_000L, sunset, 60)
        assertEquals(sunset - 3_600_000L, ahead.atMs)
        assertEquals(TurnaroundStatus.AHEAD, ahead.status)
        assertEquals(TurnaroundStatus.PAST_TURNAROUND, Almanac.turnaround(sunset - 1_000L, sunset, 60).status)
        assertEquals(TurnaroundStatus.AFTER_SUNSET, Almanac.turnaround(sunset + 1L, sunset, 60).status)
        assertEquals(TurnaroundStatus.NO_SUNSET, Almanac.turnaround(0L, null, 60).status)
    }
}

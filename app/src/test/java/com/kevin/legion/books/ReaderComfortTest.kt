package com.kevin.legion.books

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderComfortTest {
    private fun apply(m: FloatArray, r: Float, g: Float, b: Float) =
        Triple(
            m[0] * r + m[1] * g + m[2] * b + m[4],
            m[5] * r + m[6] * g + m[7] * b + m[9],
            m[10] * r + m[11] * g + m[12] * b + m[14],
        )

    @Test fun `day draws a page as rendered`() {
        assertNull(PageColorMatrix.of(ReaderTheme.DAY))
        assertFalse(PageColorMatrix.changesPage(ReaderTheme.DAY))
    }

    @Test fun `night turns a white page near black and black text light grey`() {
        val m = requireNotNull(PageColorMatrix.of(ReaderTheme.NIGHT))
        assertEquals(20f, apply(m, 255f, 255f, 255f).first, 0.01f)
        assertEquals(216f, apply(m, 0f, 0f, 0f).first, 0.01f)
        assertEquals(20, m.size)
        assertEquals(1f, m[18], 0f)
    }

    @Test fun `sepia multiplies white down to the sepia paper and keeps black`() {
        val m = requireNotNull(PageColorMatrix.of(ReaderTheme.SEPIA))
        val white = apply(m, 255f, 255f, 255f)
        assertEquals(236f, white.first, 0.01f)
        assertEquals(224f, white.second, 0.01f)
        assertEquals(200f, white.third, 0.01f)
        assertEquals(Triple(0f, 0f, 0f), apply(m, 0f, 0f, 0f))
        assertNotNull(m)
    }

    @Test fun `warm alpha is linear from 0 to the 35 percent cap and clamps`() {
        assertEquals(0f, WarmFilter.alpha(0), 0f)
        assertEquals(0.175f, WarmFilter.alpha(50), 0.0001f)
        assertEquals(0.35f, WarmFilter.alpha(100), 0.0001f)
        assertEquals(0.35f, WarmFilter.alpha(400), 0.0001f)
        assertEquals(0f, WarmFilter.alpha(-5), 0f)
    }

    // Houston, 2026-10-23: sunrise about 12:50 UTC, sunset about 23:35 UTC.
    private val lat = 29.76
    private val lon = -95.37
    private fun utc(year: Int, month: Int, day: Int, h: Int) = java.time.ZonedDateTime
        .of(year, month, day, h, 0, 0, 0, java.time.ZoneOffset.UTC).toInstant().toEpochMilli()

    private fun utc(h: Int, m: Int = 0) = utc(2026, 10, 23, h) + m * 60_000L

    @Test fun `auto is on at night and off at midday`() {
        assertTrue(WarmFilter.isNight(utc(3), lat, lon))
        assertFalse(WarmFilter.isNight(utc(18), lat, lon))
        assertTrue(WarmFilter.isNight(utc(23, 55), lat, lon))
    }

    @Test fun `auto window holds across midnight UTC at a far east longitude`() {
        // Tokyo: local noon is 03:00 UTC, local midnight 15:00 UTC; the window straddles UTC midnight.
        assertFalse(WarmFilter.isNight(utc(3), 35.68, 139.69))
        assertTrue(WarmFilter.isNight(utc(15), 35.68, 139.69))
        assertTrue(WarmFilter.isNight(utc(20), 35.68, 139.69))
    }

    @Test fun `auto with a fix ignores the on-off and with no fix follows it`() {
        val auto = ReaderSettings(warmAuto = true, warmOn = false, warmStrength = 100)
        assertEquals(0.35f, WarmFilter.effectiveAlpha(auto, utc(3), lat, lon), 0.0001f)
        assertEquals(0f, WarmFilter.effectiveAlpha(auto, utc(18), lat, lon), 0f)
        assertEquals(0f, WarmFilter.effectiveAlpha(auto, utc(3), null, null), 0f)
        assertEquals(0.35f, WarmFilter.effectiveAlpha(auto.copy(warmOn = true), utc(18), null, null), 0.0001f)
    }

    @Test fun `manual on draws at the set strength and off draws nothing`() {
        assertEquals(0.175f, WarmFilter.effectiveAlpha(ReaderSettings(warmOn = true), 0L, null, null), 0.0001f)
        assertEquals(0f, WarmFilter.effectiveAlpha(ReaderSettings(warmOn = false), 0L, lat, lon), 0f)
    }

    @Test fun `polar day is never night and polar night always is`() {
        val june = utc(2026, 6, 21, 0)
        val december = utc(2026, 12, 21, 12)
        assertFalse(WarmFilter.isNight(june, 78.0, 15.0))
        assertTrue(WarmFilter.isNight(december, 78.0, 15.0))
    }

    @Test fun `the theme cycles day sepia night day`() {
        assertEquals(ReaderTheme.SEPIA, ReaderTheme.DAY.next())
        assertEquals(ReaderTheme.NIGHT, ReaderTheme.SEPIA.next())
        assertEquals(ReaderTheme.DAY, ReaderTheme.NIGHT.next())
    }

    @Test fun `the middle third shows the bars and the outer quarters turn pages`() {
        assertEquals(TapZone.PREVIOUS, TapZone.of(10f, 100f))
        assertEquals(TapZone.NEXT, TapZone.of(90f, 100f))
        assertEquals(TapZone.CHROME, TapZone.of(50f, 100f))
        assertEquals(TapZone.CHROME, TapZone.of(34f, 100f))
        assertEquals(TapZone.NONE, TapZone.of(28f, 100f))
        assertEquals(TapZone.NONE, TapZone.of(5f, 0f))
    }

    @Test fun `the descriptions say the state in words`() {
        assertEquals("Reading theme: Day. Tap for Sepia.", themeDescription(ReaderTheme.DAY))
        assertTrue(warmDescription(ReaderSettings(warmOn = true)).contains("on"))
        assertTrue(warmDescription(ReaderSettings(warmAuto = true)).contains("sunset"))
        assertTrue(warmDescription(ReaderSettings()).contains("off"))
    }
}

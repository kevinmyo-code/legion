package com.kevin.legion.field

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FieldMathTest {
    @Test
    fun `bearing and distance on known pairs`() {
        // Due east along the equator, one degree: 111.2 km at bearing 090.
        assertEquals(90.0, FieldMath.bearingDeg(0.0, 0.0, 0.0, 1.0), 1e-9)
        assertEquals(111_195.0, FieldMath.distanceM(0.0, 0.0, 0.0, 1.0), 5.0)
        // Due north and due south.
        assertEquals(0.0, FieldMath.bearingDeg(10.0, 20.0, 11.0, 20.0), 1e-9)
        assertEquals(180.0, FieldMath.bearingDeg(11.0, 20.0, 10.0, 20.0), 1e-9)
        // A textbook pair (Kansas City to St Louis): initial bearing about 96.5 deg, about 382 km.
        assertEquals(96.51, FieldMath.bearingDeg(39.099912, -94.581213, 38.627089, -90.200203), 0.05)
        assertEquals(382_900.0, FieldMath.distanceM(39.099912, -94.581213, 38.627089, -90.200203), 1_500.0)
    }

    @Test
    fun `back azimuth and wrap at 0 and 360`() {
        assertEquals(180.0, FieldMath.backAzimuth(0.0), 1e-9)
        assertEquals(10.0, FieldMath.backAzimuth(190.0), 1e-9)
        assertEquals(359.0, FieldMath.normalize(-1.0), 1e-9)
        assertEquals(0.0, FieldMath.normalize(360.0), 1e-9)
    }

    @Test
    fun `declination turns magnetic into true and back`() {
        assertEquals(5.0, FieldMath.trueFromMagnetic(357.0, 8.0), 1e-9)
        assertEquals(357.0, FieldMath.magneticFromTrue(5.0, 8.0), 1e-9)
        assertEquals(350.0, FieldMath.trueFromMagnetic(2.0, -12.0), 1e-9)
    }

    @Test
    fun `the arrow is the bearing less the heading, wrapped`() {
        assertEquals(0.0, FieldMath.arrowDeg(212.0, 212.0), 1e-9)
        assertEquals(20.0, FieldMath.arrowDeg(10.0, 350.0), 1e-9)
        assertEquals(340.0, FieldMath.arrowDeg(350.0, 10.0), 1e-9)
    }

    @Test
    fun `points and words`() {
        assertEquals("N", FieldMath.point16(355.0))
        assertEquals("SSW", FieldMath.point16(200.0))
        assertEquals("southwest", FieldMath.word8(225.0))
        assertEquals("007", FieldWords.degText(6.6))
        assertEquals("000", FieldWords.degText(359.7))
        assertEquals("3.2° east", FieldWords.declinationText(3.24))
        assertEquals("12.0° west", FieldWords.declinationText(-12.0))
        assertEquals("29.97012 N, 98.70011 W", FieldWords.latLonText(29.970118, -98.700111))
        assertEquals("420 m (0.3 mi)", FieldWords.distanceText(420.0))
        assertEquals("4.2 km (2.6 mi)", FieldWords.distanceText(4_200.0))
        assertEquals("520 m (1706 ft)", FieldWords.altitudeText(520.0))
        assertTrue(FieldWords.altitudeText(null).startsWith("unknown"))
    }

    @Test
    fun `accuracy in words`() {
        assertEquals("excellent, within about 4 m", FieldWords.accuracyWords(4f))
        assertEquals("good, within about 12 m", FieldWords.accuracyWords(12f))
        assertTrue(FieldWords.accuracyWords(500f).startsWith("very poor"))
        assertEquals("accuracy unknown", FieldWords.accuracyWords(null))
    }

    @Test
    fun `smoothing survives the 359 to 0 crossing`() {
        val s = HeadingSmoother(alpha = 0.5)
        s.update(359.0)
        val next = s.update(1.0)
        // A naive average of 359 and 1 would be 180; the vector average is 0.
        assertTrue("got $next", next !in 2.0..358.0)
    }
}

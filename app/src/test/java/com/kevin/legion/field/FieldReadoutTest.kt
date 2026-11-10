package com.kevin.legion.field

import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Round 5: the Field screen's state and the spoken readout, from fixed inputs. Made-up places. */
class FieldReadoutTest {
    private val now = 1794330000000L // 2026-11-10 ~19:40 UTC
    private val fix =
        FixInput(lat = 39.7392, lon = -104.9903, altitudeM = 1600.0, accuracyM = 8f, timeMs = now - 12_000)
    private val zone = ZoneOffset.ofHours(-7)
    private val decl = { _: Double, _: Double, _: Double, _: Long -> 8.0 }
    private val car = Waypoint("car", 39.7410, -104.9930, pending = true)
    private val far = Waypoint("cabin", 40.0, -105.5, pending = false)

    private fun build(
        compass: CompassReading? =
            CompassReading(350.0, CompassAccuracy.HIGH, CompassSource.ROTATION_VECTOR, upright = false),
        f: FixInput? = fix,
    ) = FieldReadout.build(
        compass, CompassSource.ROTATION_VECTOR, GpsStatus.SEARCHING, f, listOf(far, car), now, zone, 60, decl,
    )

    @Test
    fun `true heading is magnetic plus declination, with the back azimuth and north arrow`() {
        val h = build().heading!!
        assertEquals(350.0, h.magneticDeg, 1e-9)
        assertEquals(358.0, h.trueDeg!!, 1e-9)
        assertEquals("N", h.point)
        assertEquals(178.0, h.backAzimuthTrueDeg!!, 1e-9)
        assertEquals(170.0, h.backAzimuthMagDeg, 1e-9)
        assertEquals(2.0, h.northArrowDeg!!, 1e-9)
    }

    @Test
    fun `waypoints sort by distance and carry true, magnetic and arrow`() {
        val s = build()
        assertEquals(listOf("car", "cabin"), s.waypoints.map { it.label })
        val w = s.waypoints.first()
        assertEquals(FieldMath.bearingDeg(fix.lat, fix.lon, car.lat, car.lon), w.bearingTrueDeg, 1e-9)
        assertEquals(FieldMath.normalize(w.bearingTrueDeg - 8.0), w.bearingMagDeg!!, 1e-9)
        assertEquals(FieldMath.normalize(w.bearingTrueDeg - 358.0), w.arrowDeg!!, 1e-9)
        assertTrue(w.pending)
    }

    @Test
    fun `position, grid and words`() {
        val p = build().position!!
        assertEquals("13S ED 00831 98811", p.mgrsText)
        assertEquals("13S 500831E 4398811N", p.utmText)
        assertEquals("good, within about 8 m", p.accuracyText)
        assertEquals(12_000L, p.fixAgeMs)
    }

    @Test
    fun `no fix means no true north, no bearings and no sun, said as such`() {
        val s = build(f = null)
        assertNull(s.heading!!.trueDeg)
        assertNull(s.position)
        assertTrue(s.waypoints.isEmpty())
        assertNull(s.sun)
        assertEquals(GpsStatus.SEARCHING, s.gps)
        val words = FieldVoice.voiceText(s, zone)
        assertTrue(words, words.contains("no GPS fix"))
        assertTrue(words, words.contains("true north unknown"))
    }

    @Test
    fun `low magnetometer accuracy asks for the figure 8`() {
        val shaky = CompassReading(10.0, CompassAccuracy.UNRELIABLE, CompassSource.ACCEL_MAG, upright = true)
        val s = build(compass = shaky)
        assertTrue(s.needsCalibration)
        assertTrue(FieldVoice.voiceText(s, zone).contains("figure 8"))
    }

    @Test
    fun `no compass reading is said, never guessed`() {
        val s = build(compass = null)
        assertNull(s.heading)
        assertFalse(s.needsCalibration)
        assertTrue(FieldVoice.voiceText(s, zone).contains("Compass: no reading"))
    }

    @Test
    fun `sun, moon and turnaround come out of the fix`() {
        val s = build()
        assertNotNull(s.sun!!.sunsetMs)
        assertEquals(s.sun!!.sunsetMs!! - 3_600_000L, s.turnaround!!.atMs)
        assertEquals(TurnaroundStatus.AHEAD, s.turnaround!!.status)
        assertTrue(s.moon!!.illuminationPct in 0..10) // new moon on 2026-11-10
    }

    @Test
    fun `the spoken readout answers north, sunset and the car`() {
        val words = FieldVoice.voiceText(build(), zone)
        assertTrue(words, words.contains("true north is straight ahead"))
        assertTrue(words, words.contains("Sun: sunrise"))
        assertTrue(words, words.contains("Turnaround"))
        assertTrue(words, words.contains("car "))
        assertTrue(words, words.contains("not synced yet"))
        assertTrue(words, words.contains("MGRS 13S ED 00831 98811"))
        assertTrue(words, words.endsWith("no network."))
    }

    @Test
    fun `north in words`() {
        assertEquals("straight ahead", FieldVoice.northWords(3.0))
        assertEquals("40° to your right", FieldVoice.northWords(40.0))
        assertEquals("40° to your left", FieldVoice.northWords(320.0))
        assertEquals("behind you", FieldVoice.northWords(180.0))
    }
}

package com.kevin.legion.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises `vehicle/PidSpec.kt` - the PID registry, its decode formulas, capability intersection,
 * and sensor-name matching. Plain JUnit, no hardware and no `Context`.
 *
 * Decode expectations are computed from SAE J1979's published formulas by hand, not by running the
 * code and recording what it produced. A test that asserts whatever the implementation happens to
 * do is not a test of the formula.
 */
class PidRegistryTest {

    private fun decode(pid: Int, vararg bytes: Int): Double? =
        pidSpec(pid)!!.decode!!(bytes.toList())

    // ------------------------------------------------------------------ registry integrity

    @Test
    fun `every pid key is unique`() {
        val keys = PID_REGISTRY.map { it.key }
        assertEquals("duplicate keys would make pidSpecByKey silently pick one", keys.size, keys.toSet().size)
    }

    @Test
    fun `every pid number is unique`() {
        val pids = PID_REGISTRY.map { it.pid }
        assertEquals(pids.size, pids.toSet().size)
    }

    @Test
    fun `command and response prefix are formatted as the protocol expects`() {
        val oil = pidSpec(0x5C)!!
        assertEquals("015C", oil.command)
        assertEquals("41 5C", oil.responsePrefix)
        // Single-digit PIDs must zero-pad, or the request is malformed.
        assertEquals("0105", pidSpec(0x05)!!.command)
        assertEquals("41 05", pidSpec(0x05)!!.responsePrefix)
    }

    @Test
    fun `the 0x60 support window is probed`() {
        // Its absence is what hid the entire turbo and torque range until 2026-08-12.
        assertTrue(SUPPORT_PROBES.any { it.first == "0160" && it.second == 0x60 })
        assertEquals(listOf("0100", "0120", "0140", "0160"), SUPPORT_PROBES.map { it.first })
    }

    // ------------------------------------------------------------------ decode formulas

    @Test
    fun `coolant temp subtracts the 40 degree offset`() {
        assertEquals(-40.0, decode(0x05, 0)!!, 0.001)
        assertEquals(0.0, decode(0x05, 40)!!, 0.001)
        assertEquals(90.0, decode(0x05, 130)!!, 0.001)
    }

    @Test
    fun `rpm is the two-byte value divided by four`() {
        // 0x0C with bytes 0x1A 0xF8 = 6904 / 4 = 1726 rpm
        assertEquals(1726.0, decode(0x0C, 0x1A, 0xF8)!!, 0.001)
    }

    @Test
    fun `fuel trim is centred on 128 and can go negative`() {
        assertEquals(0.0, decode(0x06, 128)!!, 0.001)
        assertEquals(-100.0, decode(0x06, 0)!!, 0.001)
        assertEquals(25.0, decode(0x06, 160)!!, 0.001)
    }

    @Test
    fun `oil temperature uses the same 40 degree offset as coolant`() {
        assertEquals(100.0, decode(0x5C, 140)!!, 0.001)
    }

    @Test
    fun `fuel rate is the two-byte value over twenty litres per hour`() {
        // 0x5E with bytes 0x01 0x90 = 400 / 20 = 20 L/h
        assertEquals(20.0, decode(0x5E, 0x01, 0x90)!!, 0.001)
    }

    @Test
    fun `control module voltage is millivolts`() {
        // 0x42 with bytes 0x36 0xB0 = 14000 mV = 14.0 V
        assertEquals(14.0, decode(0x42, 0x36, 0xB0)!!, 0.001)
    }

    @Test
    fun `manifold pressure is a raw kPa byte`() {
        // The boost reading on a turbo engine. 101 kPa is roughly atmospheric.
        assertEquals(101.0, decode(0x0B, 101)!!, 0.001)
        assertEquals(210.0, decode(0x0B, 210)!!, 0.001)
    }

    @Test
    fun `percentage pids span zero to one hundred across the full byte`() {
        assertEquals(0.0, decode(0x2F, 0)!!, 0.001)
        assertEquals(100.0, decode(0x2F, 255)!!, 0.001)
    }

    @Test
    fun `timing advance is halved and offset by 64 degrees`() {
        assertEquals(0.0, decode(0x0E, 128)!!, 0.001)
        assertEquals(-64.0, decode(0x0E, 0)!!, 0.001)
    }

    @Test
    fun `catalyst temperature scales by ten and offsets by 40`() {
        // 0x3C bytes 0x0F 0xA0 = 4000 / 10 - 40 = 360 C
        assertEquals(360.0, decode(0x3C, 0x0F, 0xA0)!!, 0.001)
    }

    @Test
    fun `torque pids are centred on 125`() {
        assertEquals(0.0, decode(0x62, 125)!!, 0.001)
        assertEquals(75.0, decode(0x62, 200)!!, 0.001)
    }

    // ------------------------------------------------------------------ undecoded pids

    @Test
    fun `composite turbo pids are named but explicitly not decoded`() {
        // Honesty rule: we report that a car supports these, and do not invent a value for them.
        val cact = pidSpec(0x77)!!
        assertEquals("charge_air_cooler_temp", cact.key)
        assertFalse(cact.readable)
        assertNull(cact.decode)
    }

    @Test
    fun `every readable pid actually has a decoder and vice versa`() {
        for (spec in PID_REGISTRY) {
            assertEquals("readable must agree with decode for ${spec.key}", spec.decode != null, spec.readable)
        }
    }

    // ------------------------------------------------------------------ capabilities

    @Test
    fun `capabilities split readable, undecoded and unknown`() {
        // 0x05 readable, 0x77 known-but-undecoded, 0xC4 not in the registry at all.
        val caps = capabilitiesFor(setOf(0x05, 0x77, 0xC4))
        assertEquals(listOf("coolant_temp"), caps.readable.map { it.key })
        assertEquals(listOf("charge_air_cooler_temp"), caps.undecodedPids.map { it.key })
        assertEquals(listOf(0xC4), caps.unknownPids)
    }

    @Test
    fun `the support-window pids are not reported as unknown sensors`() {
        // 0x00/0x20/0x40/0x60 are bitmask windows, not readings - listing them as mysterious
        // unrecognised PIDs would be noise on every single vehicle.
        val caps = capabilitiesFor(setOf(0x00, 0x20, 0x40, 0x60, 0x05))
        assertTrue(caps.unknownPids.isEmpty())
    }

    @Test
    fun `a car supporting nothing yields empty capabilities rather than throwing`() {
        val caps = capabilitiesFor(emptySet())
        assertTrue(caps.readable.isEmpty())
        assertTrue(caps.undecodedPids.isEmpty())
        assertTrue(caps.unknownPids.isEmpty())
    }

    @Test
    fun `two different cars resolve to different capability sets from identical code`() {
        // The whole multi-vehicle thesis in one assertion: no per-model branching, just different
        // bitmasks in and different capabilities out.
        val modern = capabilitiesFor(setOf(0x05, 0x0C, 0x5C, 0x5E, 0x0B))
        val old = capabilitiesFor(setOf(0x05, 0x0C))
        assertTrue("oil_temp" in modern.readable.map { it.key })
        assertFalse("oil_temp" in old.readable.map { it.key })
        assertEquals(2, old.readable.size)
    }

    // ------------------------------------------------------------------ sensor matching

    private val readable = PID_REGISTRY.filter { it.readable }

    @Test
    fun `an exact key matches`() {
        assertEquals(listOf("oil_temp"), matchPid("oil_temp", readable).map { it.key })
    }

    @Test
    fun `a spoken phrase with spaces matches the underscored key`() {
        assertEquals(listOf("oil_temp"), matchPid("oil temp", readable).map { it.key })
    }

    @Test
    fun `a label matches case-insensitively`() {
        assertEquals(listOf("fuel_rate"), matchPid("fuelrate", readable).map { it.key })
    }

    @Test
    fun `an unknown word matches nothing rather than guessing`() {
        assertTrue(matchPid("flux capacitor", readable).isEmpty())
    }

    @Test
    fun `a blank query matches nothing`() {
        assertTrue(matchPid("   ", readable).isEmpty())
    }

    @Test
    fun `matching is scoped to the candidates given, so a car is never offered what it lacks`() {
        val poorCar = listOf(pidSpec(0x05)!!, pidSpec(0x0C)!!)
        assertTrue(matchPid("oil temp", poorCar).isEmpty())
        assertEquals(listOf("coolant_temp"), matchPid("coolant", poorCar).map { it.key })
    }

    @Test
    fun `an exact label match wins outright even though the key is longer`() {
        // "ltft" is the LTFT label verbatim, so it resolves rather than dragging in bank 2.
        assertEquals(listOf("ltft_b1"), matchPid("ltft", readable).map { it.key })
    }

    @Test
    fun `an ambiguous query returns every equal candidate rather than picking one`() {
        // "trim" reaches all four bank/term combinations through their descriptions and none is a
        // better match than the others. The caller must ask, not silently read out one of them.
        val matches = matchPid("trim", readable)
        assertTrue("expected several fuel-trim candidates, got ${matches.map { it.key }}", matches.size > 1)
        assertTrue(matches.all { "trim" in it.description.lowercase() })
    }

    @Test
    fun `lookup by key round-trips for every registry entry`() {
        for (spec in PID_REGISTRY) {
            assertNotNull("pidSpecByKey failed for ${spec.key}", pidSpecByKey(spec.key))
            assertEquals(spec.pid, pidSpecByKey(spec.key)!!.pid)
        }
    }
}

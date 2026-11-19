package com.kevin.legion.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** [CarFingerprint]: what a car answers at connect, made into a print. Made-up answers. */
class CarFingerprintTest {
    @Test
    fun `protocol and the 0100 bitmask lines make the print, ECU order and spacing aside`() {
        val a = CarFingerprint.of("A6", "41 00 12 34 56 78\r41 00 AB CD EF 01\r\r>")
        val b = CarFingerprint.of("6", "4100ABCDEF 01\n4100 12345678")
        assertEquals("p6|410012345678,4100ABCDEF01", a)
        assertEquals(a, b)
    }

    @Test
    fun `no protocol or no real answer is no print`() {
        assertNull(CarFingerprint.of("A0", "41 00 12 34 56 78"))
        assertNull(CarFingerprint.of("", "41 00 12 34 56 78"))
        assertNull(CarFingerprint.of("6", "NO DATA"))
        assertNull(CarFingerprint.of("6", "UNABLE TO CONNECT"))
        assertNull(CarFingerprint.of("6", null))
    }

    @Test
    fun `a print matching one car names it, two cars holding one print are unknown`() {
        val prints = mapOf("car:a" to "p6|x", "car:b" to "p2|y")
        assertEquals("car:a", CarFingerprint.match(prints, "p6|x", setOf("car:a", "car:b")))
        assertNull(CarFingerprint.match(prints, "p6|x", setOf("car:b")))
        // Twins: a VIN taught car c the print car a already had; both keep it, so it names neither.
        val twins = prints + ("car:c" to "p6|x")
        assertNull(CarFingerprint.match(twins, "p6|x", setOf("car:a", "car:b", "car:c")))
        assertNull(CarFingerprint.match(prints, null, setOf("car:a")))
    }
}

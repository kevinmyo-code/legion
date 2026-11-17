package com.kevin.legion.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** [CarFingerprint]: what a car answers at connect, made into a print. Made-up answers. */
class CarFingerprintTest {
    @Test
    fun `protocol and the 0100 bitmask lines make the print, ECU order and spacing aside`() {
        val a = CarFingerprint.of("A6", "41 00 BE 3E B8 11\r41 00 98 18 80 11\r\r>")
        val b = CarFingerprint.of("6", "4100981880 11\n4100BE3EB811")
        assertEquals("p6|410098188011,4100BE3EB811", a)
        assertEquals(a, b)
    }

    @Test
    fun `no protocol or no real answer is no print`() {
        assertNull(CarFingerprint.of("A0", "41 00 BE 3E B8 11"))
        assertNull(CarFingerprint.of("", "41 00 BE 3E B8 11"))
        assertNull(CarFingerprint.of("6", "NO DATA"))
        assertNull(CarFingerprint.of("6", "UNABLE TO CONNECT"))
        assertNull(CarFingerprint.of("6", null))
    }

    @Test
    fun `a print matching one car names it, two cars with one print are unknown`() {
        val prints = mapOf("car:a" to "p6|x", "car:b" to "p2|y")
        assertEquals("car:a", CarFingerprint.match(prints, "p6|x", setOf("car:a", "car:b")))
        assertNull(CarFingerprint.match(prints, "p6|x", setOf("car:b")))
        assertNull(CarFingerprint.match(prints + ("car:c" to "p6|x"), "p6|x", setOf("car:a", "car:b", "car:c")))
        assertNull(CarFingerprint.match(prints, null, setOf("car:a")))
    }

    @Test
    fun `learning moves a print to the named car and off any other`() {
        val prints = mapOf("car:a" to "p6|x", "car:b" to "p2|y")
        assertEquals(mapOf("car:b" to "p6|x"), CarFingerprint.learn(prints, "car:b", "p6|x"))
        assertEquals(prints, CarFingerprint.learn(prints, "car:b", null))
    }
}

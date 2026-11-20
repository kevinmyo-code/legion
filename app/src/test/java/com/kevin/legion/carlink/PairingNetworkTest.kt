package com.kevin.legion.carlink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Inet4Address
import java.net.InetAddress

/** P-D: the network the phone paired on, recorded and recognised again. Invented addresses only. */
class PairingNetworkTest {
    private fun ip(s: String) = InetAddress.getByName(s) as Inet4Address
    private val hotspot = LocalIface("ap-test0", ip("10.20.30.1"), 24)
    private val cafe = LocalIface("wl-test0", ip("10.99.0.7"), 16)

    @Test fun `pairing records the interface whose subnet holds the unit`() {
        val n = PairingNetwork.recordFor("10.20.30.44", listOf(cafe, hotspot))!!
        assertEquals("ap-test0", n.name)
        assertEquals("10.20.30.0", n.base)
        assertEquals(24, n.prefix)
        assertNull(PairingNetwork.recordFor("10.50.0.1", listOf(cafe, hotspot)))
    }

    @Test fun `present only when that interface is up with that subnet`() {
        val n = PairingNetwork("ap-test0", "10.20.30.0", 24)
        assertTrue(n.present(listOf(cafe, hotspot)))
        assertFalse(n.present(listOf(cafe)))
        assertFalse(n.present(listOf(LocalIface("ap-test0", ip("10.20.31.1"), 24))))
        assertFalse(n.present(listOf(LocalIface("wl-test0", ip("10.20.30.9"), 24))))
    }

    @Test fun `contains only addresses inside the recorded subnet`() {
        val n = PairingNetwork("ap-test0", "10.20.30.0", 24)
        assertTrue(n.contains(ip("10.20.30.200")))
        assertFalse(n.contains(ip("10.20.31.200")))
        assertFalse(n.contains(ip("10.99.0.8")))
    }
}

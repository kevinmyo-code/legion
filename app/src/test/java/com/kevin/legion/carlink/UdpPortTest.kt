package com.kevin.legion.carlink

import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.BindException
import java.net.DatagramSocket

/** R3: the car link's UDP port is never shared: a second bind must fail, not co-bind. */
class UdpPortTest {
    @Test fun `a second bind of the same port fails instead of sharing it`() {
        val free = DatagramSocket(0).use { it.localPort }
        val first = UdpPort.open(free)
        try {
            val second = runCatching { UdpPort.open(free) }
            val shared = second.getOrNull() != null
            second.getOrNull()?.close()
            assertTrue("a second socket co-bound the port", !shared)
            assertTrue(second.exceptionOrNull() is BindException)
        } finally {
            first?.close()
        }
    }
}

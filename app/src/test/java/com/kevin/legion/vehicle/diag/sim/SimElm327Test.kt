package com.kevin.legion.vehicle.diag.sim

import com.kevin.legion.vehicle.diag.BusKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The simulated adapter itself: AT answers, protocol search, frame printing, flow control. */
class SimElm327Test {

    private fun connected(car: SimCar, p: Personality = Personality()): Pair<SimElm327, SimTransport> {
        val elm = SimElm327(car, p)
        val t = SimTransport(elm)
        t.handshake()
        return elm to t
    }

    private fun send(t: SimTransport, cmd: String) = runBlocking { t.link.send(cmd, 1_000) }

    @Test
    fun `answers identity and refuses what it does not know`() {
        val (_, t) = connected(SimCars.canTruck())
        assertEquals("ELM327 v1.5", send(t, "ATI").trim())
        assertEquals("?", send(t, "STI").trim())
        assertEquals("?", send(t, "ATXYZ").trim())
        assertEquals("OK", send(t, "ATH1").trim())
    }

    @Test
    fun `an STN chip answers STI and a clone refuses flow control`() {
        val (_, stn) = connected(SimCars.canTruck(), Personality(ati = "ELM327 v1.4b", sti = "STN1110 v4.2.0"))
        assertEquals("STN1110 v4.2.0", send(stn, "STI").trim())
        val (_, clone) = connected(SimCars.canTruck(), Personality(rejects = setOf("ATFC")))
        assertEquals("?", send(clone, "ATFCSD300000").trim())
    }

    @Test
    fun `first request searches, then the protocol is reported`() {
        val (elm, t) = connected(SimCars.canTruck())
        assertEquals("A0", send(t, "ATDPN").trim())
        assertTrue(send(t, "0100").startsWith("SEARCHING..."))
        assertEquals("A6", send(t, "ATDPN").trim())
        assertEquals(BusKind.CAN_11, elm.detected)
    }

    @Test
    fun `key off cannot connect`() {
        val car = SimCars.canTruck().apply { ignitionOn = false }
        val (_, t) = connected(car)
        assertTrue("UNABLE TO CONNECT" in send(t, "0100"))
    }

    @Test
    fun `headers on print the id and PCI, multi-frame needs flow control to reach the module`() {
        val (_, t) = connected(SimCars.canTruck())
        send(t, "0100")
        listOf("ATH1", "ATSH760", "ATCRA768").forEach { send(t, it) }
        val auto = send(t, "1902FF")
        assertTrue(auto, auto.lines().first().startsWith("768 10 13 59 02 FF"))
        assertEquals("first frame plus two consecutive frames", 3, auto.trim().split('\r').size)

        val (_, broken) = connected(SimCars.canTruck(), Personality(autoFlowControlBroken = true))
        send(broken, "0100")
        listOf("ATH1", "ATSH760", "ATCRA768").forEach { send(broken, it) }
        assertEquals("only the first frame", 1, send(broken, "1902FF").trim().split('\r').size)
        listOf("ATFCSH760", "ATFCSD300000", "ATFCSM1").forEach { send(broken, it) }
        assertEquals(3, send(broken, "1902FF").trim().split('\r').size)
    }

    @Test
    fun `without a receive filter a non-OBD reply id is not shown`() {
        val (_, t) = connected(SimCars.canTruck())
        send(t, "0100")
        listOf("ATH1", "ATSH760").forEach { send(t, it) }
        assertEquals("NO DATA", send(t, "3E00").trim())
    }

    @Test
    fun `a legacy car answers generic modes with three codes a line and no count byte`() {
        val (_, t) = connected(SimCars.preCan(BusKind.ISO_9141))
        val r = send(t, "03")
        assertTrue(r, "BUS INIT: ...OK" in r)
        assertTrue(r, "43 04 42 13 91 01 35" in r)
        assertTrue(r, "43 04 55 00 00 00 00" in r)
        assertEquals("NO DATA", send(t, "0A").trim())
    }
}

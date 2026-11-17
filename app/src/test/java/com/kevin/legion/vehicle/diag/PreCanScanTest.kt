package com.kevin.legion.vehicle.diag

import com.kevin.legion.vehicle.diag.sim.SimCars
import com.kevin.legion.vehicle.diag.sim.SimElm327
import com.kevin.legion.vehicle.diag.sim.SimTransport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The XJ path: a pre-CAN car gets generic OBD only, slowly, and says what it cannot reach. */
class PreCanScanTest {

    private fun scan(bus: BusKind): Pair<ScanReport, SimElm327> {
        val elm = SimElm327(SimCars.preCan(bus))
        val t = SimTransport(elm)
        t.handshake()
        val r = runBlocking {
            ModuleScanner(t.link, clock = { elm.nowMs }, pause = { elm.nowMs += it })
                .scan(ModuleScanner.Options(make = "Jeep"))
        }
        return r to elm
    }

    private fun check(bus: BusKind) {
        val (r, elm) = scan(bus)
        assertEquals(bus, r.bus)
        val e = r.engine!!
        assertEquals(
            "all four codes over two lines, no count byte",
            listOf("P0442", "P1391", "P0135", "P0455"),
            e.stored.dtcs.map { it.code },
        )
        assertEquals(listOf("P0171"), e.pending.dtcs.map { it.code })
        assertEquals(Outcome.NO_ANSWER, e.permanent.outcome)
        assertEquals(EngineCodes.PERMANENT_LEGACY, e.permanent.detail)
        assertTrue(r.modules.isEmpty())
        assertEquals(listOf(ModuleScanner.preCanWords(bus)), r.notes)
        val addressing = listOf("ATSH", "ATH1", "ATCRA")
        assertTrue("no module addressing on a legacy bus", elm.log.none { c -> addressing.any { c.startsWith(it) } })
        val words = ScanWords.summary(r)
        assertTrue(words, "only the engine's generic codes are reachable" in words)
        assertTrue(words, "P1391" in words)
    }

    @Test fun `J1850 VPW reads generic codes only and says so`() = check(BusKind.J1850_VPW)

    @Test fun `ISO 9141 reads generic codes only and says so`() = check(BusKind.ISO_9141)

    @Test
    fun `slow bus requests get the long wait`() {
        val (_, elm) = scan(BusKind.ISO_9141)
        assertTrue("simulated time reflects the slow bus", elm.nowMs >= 3 * 400)
    }
}

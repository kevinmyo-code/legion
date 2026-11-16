package com.kevin.legion.vehicle.diag

import com.kevin.legion.vehicle.diag.sim.Personality
import com.kevin.legion.vehicle.diag.sim.SimCar
import com.kevin.legion.vehicle.diag.sim.SimCars
import com.kevin.legion.vehicle.diag.sim.SimElm327
import com.kevin.legion.vehicle.diag.sim.SimTransport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The CAN module scan against the simulated truck, adapter clones, a 29-bit car, and its limits. */
class ModuleScannerTest {

    private fun scan(
        car: SimCar,
        p: Personality = Personality(),
        options: ModuleScanner.Options = ModuleScanner.Options(make = "Ford"),
    ): Pair<ScanReport, SimElm327> {
        val elm = SimElm327(car, p)
        val t = SimTransport(elm)
        t.handshake()
        val report = runBlocking {
            ModuleScanner(t.link, clock = { elm.nowMs }, pause = { elm.nowMs += it }).scan(options)
        }
        return report to elm
    }

    private fun ScanReport.at(address: String) = modules.first { it.address == address }

    @Test
    fun `finds every answering module and reads each one honestly`() {
        val (r, _) = scan(SimCars.canTruck())
        assertNull(r.stopped)
        assertEquals(BusKind.CAN_11, r.bus)
        assertEquals(listOf("720", "726", "733", "737", "760", "7D0", "7E0"), r.modules.map { it.address }.sorted())
        assertTrue(r.complete)

        val pcm = r.at("7E0")
        assertEquals(Outcome.READ, pcm.codes.outcome)
        assertEquals(listOf("P0420", "P0171"), pcm.codes.dtcs.filter { it.fault }.map { it.code })
        assertFalse("test-not-complete bits alone are not a fault", pcm.codes.dtcs.first { it.code == "P0300" }.fault)
        assertEquals(listOf("confirmed", "pending", "failing now", "test failed this cycle"), pcm.codes.dtcs[0].words)
        assertEquals(SimCars.FAKE_VIN, pcm.ids.first { it.did == "F190" }.value)
        assertEquals("request out of range", pcm.ids.first { it.did == "F197" }.value)

        val abs = r.at("760")
        assertEquals("multi-frame list read whole", 4, abs.codes.dtcs.size)
        assertEquals(listOf("confirmed", "history"), abs.codes.dtcs.first { it.code == "U0415" }.words)

        assertTrue(r.at("737").restraint)
        assertEquals(Outcome.REFUSED, r.at("726").codes.outcome)
        assertEquals("conditions not correct", r.at("726").codes.detail)
        assertEquals(ModuleReader.KWP_STATUS_NAME, r.at("720").codes.service)
        assertEquals(listOf("U0100"), r.at("720").codes.dtcs.map { it.code })
        assertEquals("pending answer caught", listOf("U0184"), r.at("7D0").codes.dtcs.map { it.code })
        assertEquals(Outcome.NO_ANSWER, r.at("733").codes.outcome)
    }

    @Test
    fun `generic engine codes come from the functional address with the answering id`() {
        val (r, _) = scan(SimCars.canTruck())
        val e = r.engine!!
        assertEquals(listOf("P0420"), e.stored.dtcs.map { it.code })
        assertEquals(listOf("P0171"), e.pending.dtcs.map { it.code })
        assertEquals(listOf("P0420"), e.permanent.dtcs.map { it.code })
        assertEquals(listOf("7E8"), e.responders)
    }

    @Test
    fun `adapter is put back the way the poller left it`() {
        val (_, elm) = scan(SimCars.canTruck())
        assertFalse(elm.headers)
        assertNull(elm.cra)
        assertEquals(0, elm.fcMode)
        assertEquals(SimElm327.DEFAULT_ST, elm.st)
        assertTrue(elm.log.last { it.startsWith("ATSH") } == "ATSH7DF")
    }

    @Test
    fun `nothing a read-only scan must never send was sent`() {
        val full = ModuleScanner.Options(make = "Ford", full = true, budgetMs = 600_000)
        val (_, elm) = scan(SimCars.canTruck(), options = full)
        val requests = elm.log.filterNot { it.startsWith("AT") || it.startsWith("ST") }
        val forbidden = listOf("10", "27", "2E", "2F", "31", "14", "04", "3B")
        assertTrue(requests.joinToString(), requests.none { r -> forbidden.any { r.startsWith(it) } })
    }

    @Test
    fun `a clone without manual flow control and a broken automatic one gets cut-off replies, said so`() {
        val (r, _) = scan(SimCars.canTruck(), Personality(rejects = setOf("ATFC"), autoFlowControlBroken = true))
        assertEquals(Outcome.PARTIAL, r.at("760").codes.outcome)
        assertEquals(ModuleRequest.CUT_WORDS, r.at("760").codes.detail)
        assertTrue(ModuleScanner.FLOW_CONTROL_NOTE in r.notes)
    }

    @Test
    fun `a clone without manual flow control still reads whole replies when its automatic one works`() {
        val (r, _) = scan(SimCars.canTruck(), Personality(rejects = setOf("ATFC")))
        assertEquals(Outcome.READ, r.at("760").codes.outcome)
        assertEquals(4, r.at("760").codes.dtcs.size)
    }

    @Test
    fun `a clone that refuses the receive filter stops the scan in words, not as empty addresses`() {
        val (r, _) = scan(SimCars.canTruck(), Personality(rejects = setOf("ATCRA7")))
        assertEquals(ModuleScanner.ADDRESS_REFUSED, r.coverage.interrupted)
        assertFalse(r.complete)
        assertTrue(ScanWords.summary(r), ModuleScanner.ADDRESS_REFUSED in ScanWords.summary(r))
    }

    @Test
    fun `a link that dies mid-scan stops it in words`() {
        val (r, _) = scan(SimCars.canTruck(), Personality(dieAfter = 60))
        assertEquals(ModuleScanner.LINK_TROUBLE, r.coverage.interrupted)
        assertFalse(r.complete)
        assertTrue(r.coverage.probed < r.coverage.planned)
    }

    @Test
    fun `adapter that refuses headers stops with words`() {
        val (r, _) = scan(SimCars.canTruck(), Personality(rejects = setOf("ATH1")))
        assertEquals(ModuleScanner.HEADERS_REFUSED, r.stopped)
    }

    @Test
    fun `key off and moving are said, not scanned`() {
        val off = SimCars.canTruck().apply { ignitionOn = false }
        assertEquals(ModuleScanner.IGNITION_WORDS, scan(off).first.stopped)
        val moving = SimCars.canTruck().apply { speedKmh = 40 }
        assertEquals(ModuleScanner.MOVING_WORDS, scan(moving).first.stopped)
    }

    @Test
    fun `out of time says how far it got`() {
        val (r, _) = scan(SimCars.canTruck(), options = ModuleScanner.Options(make = "Ford", budgetMs = 2_000))
        assertTrue(r.coverage.timeBudgetHit)
        assertFalse(r.complete)
        assertTrue(r.coverage.probed < r.coverage.planned)
        val words = ScanWords.summary(r)
        assertTrue(words, "Partial scan: ${r.coverage.probed} of ${r.coverage.planned}" in words)
    }

    @Test
    fun `full sweep probes every 11-bit request id`() {
        val full = ModuleScanner.Options(make = null, full = true, budgetMs = 600_000)
        val (r, _) = scan(SimCars.canTruck(), options = full)
        assertEquals(0x7F7 - 0x700, r.coverage.planned)
        assertEquals(7, r.modules.size)
        assertNull("no make table, so the BCM is unnamed", r.at("726").name)
        assertEquals("Emissions ECU 7E0", r.at("7E0").name)
    }

    @Test
    fun `29-bit car is addressed with priority and the SRS module names itself`() {
        val (r, elm) = scan(SimCars.can29(), options = ModuleScanner.Options(make = null))
        assertEquals(BusKind.CAN_29, r.bus)
        assertEquals(listOf("18DA10F1", "18DA58F1"), r.modules.map { it.address })
        assertTrue(r.at("18DA58F1").restraint)
        assertEquals("SRS", r.at("18DA58F1").name)
        assertEquals(listOf("P0128"), r.engine!!.stored.dtcs.map { it.code })
        assertTrue(elm.log.contains("ATCP18"))
    }

    @Test
    fun `mitsubishi pack readings are shown raw and unverified`() {
        val packs = DiagPacks.forMake(DiagPacks.parse(PackFiles.text()), "Mitsubishi")
        val (r, _) = scan(SimCars.outlanderLike(), options = ModuleScanner.Options(make = "Mitsubishi", packs = packs))
        assertEquals(2, r.readings.size)
        r.readings.forEach {
            assertEquals(Outcome.READ, it.outcome)
            assertFalse(it.verified)
            assertTrue(it.value!!.startsWith("raw "))
        }
        assertEquals("CVT ECU (transmission)", r.at("7E1").name)
    }
}

package com.kevin.legion.vehicle.diag

import com.kevin.legion.vehicle.diag.sim.SimCar
import com.kevin.legion.vehicle.diag.sim.SimCars
import com.kevin.legion.vehicle.diag.sim.SimElm327
import com.kevin.legion.vehicle.diag.sim.SimObd
import com.kevin.legion.vehicle.diag.sim.SimTransport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The spoken scan answer is built by code and can never lose what was not reached (2026-11-16,
 * stage 7 fleet eval: the main model said "Two modules probed on the Fit, and the engine's clear"
 * for a car whose module 760 never answered). Simulated cars, made-up data only.
 */
class DiagVoiceSayTest {

    private fun scan(car: SimCar, make: String? = null): ScanReport = runBlocking {
        val elm = SimElm327(car)
        val t = SimTransport(elm).also { it.handshake() }
        ModuleScanner(t.link, clock = { elm.nowMs }, pause = { elm.nowMs += it })
            .scan(ModuleScanner.Options(make = make))
    }

    /** Engine plus one module that answers TesterPresent and then never answers a code read. */
    private fun unreachedCar(): SimCar {
        val pcm = SimCars.uds(0x7E0, emptyList()).apply { obd = SimObd() }
        return SimCar(BusKind.CAN_11, listOf(pcm, SimCars.mute(0x760)))
    }

    private fun assertSaysAll(r: ScanReport) {
        val answer = ScanSay.answer("test car", r, null)
        val say = answer.getString("say")
        for (line in ScanWords.mustSay(r)) assertTrue("say lacks \"$line\": $say", line in say)
        assertTrue(answer.getString("rule"), answer.getString("rule").startsWith(ScanSay.SAY_RULE))
        assertFalse("the per-module JSON no longer rides on the voice result", answer.has("scan"))
    }

    @Test
    fun `a module that never answers is named as not reached and never clear`() {
        val r = scan(unreachedCar())
        val must = ScanWords.mustSay(r)
        assertTrue(must.toString(), ScanWords.UNREACHED in must)
        assertTrue(must.toString(), ScanWords.NOT_READ_LEAD in must)
        assertTrue(must.toString(), must.any { "760" in it })
        assertSaysAll(r)
        val say = ScanSay.answer("test car", r, null).getString("say")
        assertTrue(say, "760" in say)
    }

    @Test
    fun `the full truck names its refused and silent modules too`() {
        val r = scan(SimCars.canTruck(), "Ford")
        assertTrue(ScanWords.mustSay(r).size > 2)
        assertSaysAll(r)
    }

    @Test
    fun `a pre-CAN car needs no CAN line and still says the rest are not known to be clear`() {
        val r = scan(SimCars.preCan(BusKind.ISO_9141))
        assertEquals(emptyList<String>(), ScanWords.mustSay(r))
        assertTrue(ScanSay.answer("test car", r, null).getString("say").contains("not known to be clear"))
    }

    @Test
    fun `module scan questions are recognised, plain code reads are not`() {
        for (q in listOf(
            "scan every module", "scan every module for codes", "any codes in any module?",
            "check all the modules", "read the codes in every computer", "do a module scan",
            "check the modules", "scan the truck",
        )) {
            assertTrue(q, ScanSay.isModuleScanQuestion(q))
        }
        for (q in listOf("any fault codes?", "check the codes", "read the codes again", "what's the coolant temp")) {
            assertFalse(q, ScanSay.isModuleScanQuestion(q))
        }
    }
}

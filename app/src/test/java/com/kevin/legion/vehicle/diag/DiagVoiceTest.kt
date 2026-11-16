package com.kevin.legion.vehicle.diag

import com.kevin.legion.testutil.RoomTestReset
import com.kevin.legion.vehicle.ActiveVehicle
import com.kevin.legion.vehicle.DtcClearController
import com.kevin.legion.vehicle.diag.sim.SimCars
import com.kevin.legion.vehicle.diag.sim.SimElm327
import com.kevin.legion.vehicle.diag.sim.SimTransport
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The voice module clear: the question, the yes-check on the user's own words, and routing (a
 * confirmed call with no `modules` still reaches the module question that is waiting). No dongle
 * is connected here, so a real yes ends at "not connected": nothing can be sent.
 */
@RunWith(RobolectricTestRunner::class)
class DiagVoiceTest {
    private val context = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        RoomTestReset.resetCarDatabaseSingleton()
        DiagVoice.forget()
        DtcClearController.voiceGate.reset()
    }

    @After
    fun tearDown() {
        DiagVoice.forget()
        RoomTestReset.drainArchDiskIoPool()
    }

    private fun storeFreshScan() = runBlocking {
        val elm = SimElm327(SimCars.canTruck())
        val t = SimTransport(elm).also { it.handshake() }
        val report = ModuleScanner(t.link, clock = { elm.nowMs }, pause = { elm.nowMs += it })
            .scan(ModuleScanner.Options(make = "Ford"))
        val now = System.currentTimeMillis()
        val fresh = report.copy(startedAtMs = now, finishedAtMs = now)
        DiagScanStore.save(context, ActiveVehicle.recordingId(context), fresh)
    }

    @Test
    fun `ask, a not-yes, then a yes, routed without modules`() = runBlocking {
        storeFreshScan()
        val ask = DiagVoice.clear(context, "all", false, "clear the codes in every module")
        assertTrue(ask.getBoolean("needsConfirmation"))
        assertTrue(ask.getString("message"), ask.getString("message").startsWith("On the "))
        assertTrue(ask.getString("message"), ClearPlanner.RESTRAINT_RULE in ask.getString("message"))

        assertTrue("a yes turn with no modules still reaches the module question", DiagVoice.handles("", true))
        val notYes = DiagVoice.clear(context, "", true, "can you try it again?")
        assertFalse(notYes.getBoolean("success"))
        assertTrue(notYes.getString("message"), "not a yes" in notYes.getString("message"))

        val yes = DiagVoice.clear(context, "", true, "yes, do it")
        assertEquals(DiagScanController.NOT_CONNECTED, yes.getString("message"))
        assertFalse("one yes buys one clear", DiagVoice.handles("", true))
    }

    @Test
    fun `the engine clear asking voids a waiting module question`() = runBlocking {
        storeFreshScan()
        DiagVoice.clear(context, "all", false, "clear every module")
        assertTrue(DiagVoice.handles("", true))
        DiagVoice.forget()
        assertFalse(DiagVoice.handles("", true))
        assertTrue("a plain engine call is not a module clear", !DiagVoice.handles("", false))
    }

    @Test
    fun `no scan on file says so and asks nothing`() = runBlocking {
        val r = DiagVoice.clear(context, "all", false, "clear all modules")
        assertFalse(r.getBoolean("success"))
        assertTrue(r.getString("message"), "no module scan" in r.getString("message"))
        assertTrue(r.getString("message"), "scan every module" in r.getString("message"))
        assertFalse(DiagVoice.handles("", true))
    }

    @Test
    fun `asking for the airbag module asks nothing and says why`() = runBlocking {
        storeFreshScan()
        val r = DiagVoice.clear(context, "airbag", false, "clear the airbag codes")
        assertFalse(r.has("needsConfirmation"))
        assertTrue(r.getString("message"), ClearPlanner.RESTRAINT_RULE in r.getString("message"))
    }
}

package com.kevin.legion.vehicle.diag

import com.kevin.legion.service.LiveToolbox
import com.kevin.legion.testutil.RoomTestReset
import com.kevin.legion.vehicle.ActiveVehicle
import com.kevin.legion.vehicle.DtcClearController
import com.kevin.legion.vehicle.ObdBluetoothManager
import com.kevin.legion.vehicle.ObdResponseParser
import com.kevin.legion.vehicle.diag.sim.SimCar
import com.kevin.legion.vehicle.diag.sim.SimCars
import com.kevin.legion.vehicle.diag.sim.SimElm327
import com.kevin.legion.vehicle.diag.sim.SimObd
import com.kevin.legion.vehicle.diag.sim.SimTransport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
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
 * `clear_codes` called on words that only read (2026-11-16, stage 7 fleet eval): the read runs there
 * and its answer comes back marked as a read, with zero clear commands sent; a real clear request
 * still asks first. The simulated car stands behind the real ObdBluetoothManager link (attached by
 * reflection, as the fleet eval harness does). Made-up cars and codes only.
 */
@RunWith(RobolectricTestRunner::class)
class ClearReadInsteadTest {
    private val context = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        RoomTestReset.resetCarDatabaseSingleton()
        ActiveVehicle.select(context, CAR_ID)
        DiagVoice.forget()
        DtcClearController.voiceGate.reset()
        idle()
    }

    @After
    fun tearDown() {
        attach(null)
        ActiveVehicle.select(context, null)
        DiagVoice.forget()
        DtcClearController.voiceGate.reset()
        idle()
        RoomTestReset.drainArchDiskIoPool()
    }

    private fun field(name: String) =
        ObdBluetoothManager::class.java.getDeclaredField(name).apply { isAccessible = true }

    private fun attach(t: SimTransport?) {
        field("transport").set(null, t)
        field("connectedDeviceAddress").set(null, if (t == null) null else CAR_ID)
        field("busKind").set(null, if (t == null) BusKind.UNKNOWN else BusKind.CAN_11)
        ObdResponseParser.canLink = t != null
    }

    @Suppress("UNCHECKED_CAST")
    private fun idle() {
        val f = DiagScanController::class.java.getDeclaredField("_state").apply { isAccessible = true }
        (f.get(DiagScanController) as MutableStateFlow<Any>).value = DiagScanController.State.Idle
    }

    private fun connect(car: SimCar): SimElm327 {
        val elm = SimElm327(car)
        attach(SimTransport(elm).also { it.handshake() })
        return elm
    }

    private fun clearsSent(car: SimCar, elm: SimElm327): Int =
        car.modules.sumOf { m -> m.received.count { it.first() == Uds.CLEAR_DTC } } +
            elm.log.count { it.trim().equals("04", ignoreCase = true) }

    private fun clearCodes(words: String): JSONObject = runBlocking {
        LiveToolbox.dispatch(context, "clear_codes", JSONObject(), false, words)!!
    }

    /** Engine plus one module that answers TesterPresent and then never answers a code read. */
    private fun unreachedCar(): SimCar {
        val pcm = SimCars.uds(0x7E0, emptyList()).apply { obd = SimObd() }
        return SimCar(BusKind.CAN_11, listOf(pcm, SimCars.mute(0x760)))
    }

    @Test
    fun `a scan request on clear_codes runs the scan and says what was not reached`() {
        val car = unreachedCar()
        val elm = connect(car)
        val r = clearCodes("scan every module for codes")
        val say = r.optString("say")
        assertTrue(r.toString(), ScanWords.UNREACHED in say)
        assertTrue(say, ScanWords.NOT_READ_LEAD in say)
        assertTrue(say, "760" in say)
        assertEquals(say, com.kevin.legion.ai.VerbatimSpeech.of(r))
        assertFalse(r.getBoolean("cleared"))
        assertEquals(LiveToolbox.READ_NOT_CLEAR, r.getString("read_not_clear"))
        assertFalse("no clear question", r.has("needsConfirmation"))
        assertEquals("zero clear commands", 0, clearsSent(car, elm))
    }

    @Test
    fun `a read request on clear_codes reads the engine codes and sends no clear`() {
        val car = SimCars.canTruck()
        val elm = connect(car)
        val r = clearCodes("read the codes again")
        assertTrue(r.toString(), r.optBoolean("success"))
        assertTrue(r.toString(), "P0420" in r.toString())
        assertFalse(r.getBoolean("cleared"))
        assertFalse("no clear question", r.has("needsConfirmation"))
        assertFalse("not a module scan", r.has("say"))
        assertEquals("zero clear commands", 0, clearsSent(car, elm))
    }

    @Test
    fun `an empty engine read says stored engine codes only, never everything`() {
        val pcm = SimCars.uds(0x7E0, emptyList()).apply { obd = SimObd() }
        connect(SimCar(BusKind.CAN_11, listOf(pcm)))
        val r = clearCodes("any codes?")
        assertTrue(r.toString(), r.optBoolean("success"))
        assertEquals(LiveToolbox.NO_ENGINE_CODES, r.getString("message"))
        val spoken = com.kevin.legion.ai.VerbatimSpeech.of(r)
        assertEquals("spoken by the phone, word for word", LiveToolbox.NO_ENGINE_CODES, spoken)
        assertFalse(r.getString("message").contains("everything", ignoreCase = true))
    }

    @Test
    fun `a failed engine read says so, never no codes`() {
        val car = SimCars.canTruck()
        val elm = connect(car)
        elm.car.ignitionOn = false
        val r = clearCodes("read the codes")
        assertFalse(r.toString(), r.optBoolean("success"))
        assertEquals(LiveToolbox.ENGINE_CODES_NOT_READ, r.getString("message"))
        assertEquals(LiveToolbox.ENGINE_CODES_NOT_READ, com.kevin.legion.ai.VerbatimSpeech.of(r))
    }

    @Test
    fun `a clear request still asks first and sends nothing`() {
        val car = SimCars.canTruck()
        val elm = connect(car)
        val r = clearCodes("clear the codes")
        assertTrue(r.toString(), r.optBoolean("needsConfirmation"))
        assertTrue(r.getString("message"), r.getString("message").endsWith("Do you want me to clear?"))
        assertFalse(r.has("read_not_clear"))
        assertEquals("zero clear commands before a yes", 0, clearsSent(car, elm))
    }

    @Test
    fun `an unconfirmed clear_codes is never counted as a write done, a confirmed one is`() {
        assertFalse(LiveToolbox.isMutatingCall("clear_codes", JSONObject()))
        assertFalse(LiveToolbox.isMutatingCall("clear_codes", JSONObject().put("confirmed", false)))
        assertTrue(LiveToolbox.isMutatingCall("clear_codes", JSONObject().put("confirmed", true)))
    }

    private companion object {
        const val CAR_ID = "SIM:00:00:00:00:07"
    }
}

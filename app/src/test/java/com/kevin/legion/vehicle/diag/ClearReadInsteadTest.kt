package com.kevin.legion.vehicle.diag

import com.kevin.legion.service.LiveToolbox
import com.kevin.legion.service.VehicleCodesToolbox
import com.kevin.legion.testutil.RoomTestReset
import com.kevin.legion.vehicle.ActiveVehicle
import com.kevin.legion.vehicle.DongleCar
import com.kevin.legion.vehicle.DongleCarController
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
        // The connection's car is decided (a VIN proved it): a clear is never allowed on an undecided car.
        @Suppress("UNCHECKED_CAST")
        val state = DongleCarController::class.java.getDeclaredField("_state").apply { isAccessible = true }
            .get(DongleCarController) as MutableStateFlow<DongleCar?>
        state.value = if (t == null) null else DongleCar.OnCar(CAR_ID, CAR_ID, null, DongleCar.Basis.VIN)
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

    /** The read itself, as `car read_codes` calls it. */
    private fun readCodes(): JSONObject = runBlocking {
        LiveToolbox.dispatch(context, "car", JSONObject().put("action", "read_codes"), false, "any codes?")!!
    }

    /** Engine plus one module that answers TesterPresent and then never answers a code read. */
    private fun unreachedCar(): SimCar {
        val pcm = SimCars.uds(0x7E0, emptyList()).apply { obd = SimObd() }
        return SimCar(BusKind.CAN_11, listOf(pcm, SimCars.mute(0x760)))
    }

    /**
     * Review 2026-11-19 (P2-1): a clear_codes the gate judges to be a read returns no code data at
     * all, only where the read is: car read_codes, whose answer code speaks through the diagnostics
     * filter. Nothing is read, cleared or asked here.
     */
    private fun assertSentToReadCodes(r: JSONObject, car: SimCar, elm: SimElm327) {
        assertFalse(r.toString(), r.optBoolean("success"))
        assertEquals("car read_codes", r.getString("call_instead"))
        assertTrue(r.getString("message"), r.getString("message").contains("car read_codes"))
        assertFalse("no code data", "P0420" in r.toString())
        assertFalse("no scan words", r.has("say"))
        assertEquals(null, com.kevin.legion.ai.VerbatimSpeech.of(r))
        assertFalse(r.getBoolean("cleared"))
        assertFalse("no clear question", r.has("needsConfirmation"))
        assertEquals("zero clear commands", 0, clearsSent(car, elm))
        assertTrue("nothing was even read from the car", elm.log.none { it.trim() == "03" })
    }

    @Test
    fun `a scan request on clear_codes reads nothing and names read_codes with all_modules`() {
        val car = unreachedCar()
        val elm = connect(car)
        val r = clearCodes("scan every module for codes")
        assertSentToReadCodes(r, car, elm)
        assertTrue(r.getBoolean("all_modules"))
    }

    @Test
    fun `a read request on clear_codes returns no codes and names read_codes`() {
        val car = SimCars.canTruck()
        val elm = connect(car)
        val r = clearCodes("read the codes again")
        assertSentToReadCodes(r, car, elm)
        assertFalse(r.optBoolean("all_modules"))
    }

    @Test
    fun `an empty engine read says stored engine codes only, never everything`() {
        val pcm = SimCars.uds(0x7E0, emptyList()).apply { obd = SimObd() }
        connect(SimCar(BusKind.CAN_11, listOf(pcm)))
        val r = readCodes()
        assertTrue(r.toString(), r.optBoolean("success"))
        assertEquals(VehicleCodesToolbox.NO_ENGINE_CODES, r.getString("message"))
        val spoken = com.kevin.legion.ai.VerbatimSpeech.of(r)
        assertEquals("spoken by the phone, word for word", VehicleCodesToolbox.NO_ENGINE_CODES, spoken)
        assertFalse(r.getString("message").contains("everything", ignoreCase = true))
    }

    @Test
    fun `a failed engine read says so, never no codes`() {
        val car = SimCars.canTruck()
        val elm = connect(car)
        elm.car.ignitionOn = false
        val r = readCodes()
        assertFalse(r.toString(), r.optBoolean("success"))
        assertEquals(VehicleCodesToolbox.ENGINE_CODES_NOT_READ, r.getString("message"))
        // Heard without the "(OBD mode 03)" parenthesis; the same claim (2026-11-17).
        assertEquals(VehicleCodesToolbox.ENGINE_CODES_NOT_READ_SPOKEN, com.kevin.legion.ai.VerbatimSpeech.of(r))
    }

    @Test
    fun `clear_codes called on talk about something else returns no codes and names read_codes`() {
        // Phone audit 2026-11-19: the guard's read was spoken word for word in talk about something else.
        val car = SimCars.canTruck()
        val elm = connect(car)
        assertSentToReadCodes(clearCodes("the bakery on the corner closed early today"), car, elm)
    }

    @Test
    fun `car read_codes is a read on the car tool that code speaks`() {
        val car = SimCars.canTruck()
        val elm = connect(car)
        val r = readCodes()
        assertTrue(r.toString(), r.optBoolean("success"))
        assertTrue(com.kevin.legion.ai.VerbatimSpeech.of(r).orEmpty().startsWith("Stored engine code"))
        assertFalse(r.has("read_not_clear"))
        assertEquals(0, clearsSent(car, elm))
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
    fun `a real clear still clears after his yes, through the gate`() {
        val car = SimCars.canTruck()
        val elm = connect(car)
        assertTrue(clearCodes("clear the codes").optBoolean("needsConfirmation"))
        val yes = JSONObject().put("confirmed", true)
        val r = runBlocking { LiveToolbox.dispatch(context, "clear_codes", yes, false, "yes, clear them")!! }
        assertTrue(r.toString(), r.optBoolean("success"))
        assertFalse(r.has("call_instead"))
        assertTrue("a clear went out", clearsSent(car, elm) > 0)
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

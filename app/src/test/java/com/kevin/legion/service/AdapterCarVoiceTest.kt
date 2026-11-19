package com.kevin.legion.service

import com.kevin.legion.data.local.Vehicle
import com.kevin.legion.testutil.RoomTestReset
import com.kevin.legion.vehicle.ActiveVehicle
import com.kevin.legion.vehicle.AdapterBinding
import com.kevin.legion.vehicle.CarFingerprint
import com.kevin.legion.vehicle.DongleCar
import com.kevin.legion.vehicle.DongleCarController
import com.kevin.legion.vehicle.FleetEngineStore
import com.kevin.legion.vehicle.ObdBluetoothManager
import com.kevin.legion.vehicle.ObdTransport
import com.kevin.legion.vehicle.VinResponse
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
import java.io.ByteArrayInputStream
import java.io.OutputStream

/**
 * What the voice is told while the OBD adapter's car is unknown or only recognised (one adapter moved
 * between cars, Kevin 2026-11-17), through [LiveToolbox.dispatch]. Invented cars, MAC and print.
 */
@RunWith(RobolectricTestRunner::class)
class AdapterCarVoiceTest {
    private val context = RuntimeEnvironment.getApplication()
    private val mac = "00:11:22:33:44:66"
    private val print = "p6|410012345678"

    @Before
    fun setUp() {
        RoomTestReset.resetCarDatabaseSingleton()
        DongleCarController.resetForTest()
        AdapterBinding.resetForTest()
        setLink(null, null)
    }

    @After
    fun tearDown() {
        RoomTestReset.drainArchDiskIoPool()
        DongleCarController.resetForTest()
        AdapterBinding.resetForTest()
        setLink(null, null)
    }

    private suspend fun seed() {
        for ((id, name) in listOf("car:zephyr" to "Zephyr", "car:comet" to "Comet")) {
            FleetEngineStore.createVehicle(
                context,
                Vehicle(obdMac = id, name = name, make = "Acme", model = name, year = 2010, personaPrompt = ""),
            )
        }
    }

    /** The adapter's address, and a link only when [withLink] (a link with nothing behind it). */
    private fun setLink(address: String?, withLink: Boolean?) {
        val transport = ObdBluetoothManager::class.java.getDeclaredField("transport").apply { isAccessible = true }
        transport.set(
            ObdBluetoothManager,
            if (withLink == true) {
                object : ObdTransport {
                    override val inputStream = ByteArrayInputStream(ByteArray(0))
                    override val outputStream = object : OutputStream() { override fun write(b: Int) {} }
                    override val label = mac
                    override fun close() {}
                }
            } else {
                null
            },
        )
        ObdBluetoothManager::class.java.getDeclaredField("connectedDeviceAddress").apply { isAccessible = true }
            .set(ObdBluetoothManager, address)
    }

    @Test
    fun `an unknown car - a live-car result asks once a connection, then says he was asked`() = runBlocking {
        seed()
        assertTrue(DongleCarController.onConnected(context, mac, null) { VinResponse.Result.NoData } is DongleCar.Held)
        AdapterBinding.resetForTest() // the connect-time spoken ask did not happen (no proactive voice here)
        setLink(mac, withLink = false)

        val first = LiveToolbox.dispatch(context, "get_codes", JSONObject())!!
        assertEquals(AdapterBinding.ASK_ONCE, first.getString("adapter_car"))
        val second = LiveToolbox.dispatch(context, "get_codes", JSONObject())!!
        assertEquals(AdapterBinding.ASKED, second.getString("adapter_car"))
    }

    @Test
    fun `an unknown car - a read for a named car is refused with how to name it, never a dead end`() = runBlocking {
        seed()
        DongleCarController.onConnected(context, mac, null) { VinResponse.Result.NoData }
        setLink(mac, withLink = true)

        val r = LiveToolbox.dispatch(context, "get_health", JSONObject().put("vehicle", "Comet"))!!
        assertFalse(r.getBoolean("success"))
        assertEquals(AdapterBinding.UNKNOWN_CAR_ERROR, r.getString("error"))
        assertTrue(r.getString("message").contains("car switch_vehicle vehicle=Comet"))
        assertFalse(r.has("adapter_car"))
        assertFalse(r.has("batteryVolts"))
    }

    @Test
    fun `a recognised car - said once a connection as a recognition, not a VIN`() = runBlocking {
        seed()
        CarFingerprint.save(context, "car:zephyr", print)
        val d = DongleCarController.onConnected(context, mac, print) { VinResponse.Result.NoData } as DongleCar.OnCar
        assertEquals(DongleCar.Basis.ASSUMED, d.basis)
        setLink(mac, withLink = false)

        val first = LiveToolbox.dispatch(context, "get_codes", JSONObject())!!
        val note = first.getString("adapter_car")
        assertTrue(note, note.contains("Looks like the") && note.contains("Zephyr") && note.contains("not by a VIN"))
        assertFalse(LiveToolbox.dispatch(context, "get_codes", JSONObject())!!.has("adapter_car"))
        assertEquals("car:zephyr", ActiveVehicle.recordingId(context))
    }

    @Test
    fun `a car he named on this connection gets no note`() = runBlocking {
        seed()
        DongleCarController.onConnected(context, mac, print) { VinResponse.Result.NoData }
        DongleCarController.answer(context, "car:comet")
        setLink(mac, withLink = false)
        assertFalse(LiveToolbox.dispatch(context, "get_codes", JSONObject())!!.has("adapter_car"))
    }
}

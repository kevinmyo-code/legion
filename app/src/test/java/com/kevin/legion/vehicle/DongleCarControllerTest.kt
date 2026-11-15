package com.kevin.legion.vehicle

import com.kevin.legion.data.local.CarDatabase
import com.kevin.legion.data.local.CodeEvent
import com.kevin.legion.data.local.Drive
import com.kevin.legion.data.local.OdbSample
import com.kevin.legion.data.local.Vehicle
import com.kevin.legion.data.local.VehicleSpec
import com.kevin.legion.testutil.RoomTestReset
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The three cases end to end against Room, with fake dongle reads (OBD VIN attribution,
 * 2026-11-15): matched, unknown, unreadable. Every car, MAC and VIN here is made up.
 */
@RunWith(RobolectricTestRunner::class)
class DongleCarControllerTest {
    private val context = RuntimeEnvironment.getApplication()
    private val db get() = CarDatabase.getDatabase(context)

    private val mac = "00:11:22:33:44:55"
    private val truckVin = "1A2B3C4D5E6F7G8H9"
    private val suvVin = "9Z8Y7X6W5V4U3T2S1"
    private val newVin = "FAKECARVXN0000003"

    @Before
    fun setUp() {
        RoomTestReset.resetCarDatabaseSingleton()
        DongleCarController.resetForTest()
        ActiveVehicle.select(context, null)
    }

    @After
    fun tearDown() {
        RoomTestReset.drainArchDiskIoPool()
        DongleCarController.resetForTest()
    }

    private suspend fun seed() {
        for ((id, model, vin) in listOf(
            Triple("car:truck", "F-150", truckVin),
            Triple("car:suv", "Outlander", suvVin),
            Triple("car:hybrid", "RAV4", ""),
        )) {
            db.vehicleDao().upsert(
                Vehicle(obdMac = id, name = model, make = "Make", model = model, year = 2020, personaPrompt = ""),
            )
            if (vin.isNotEmpty()) db.vehicleSpecDao().upsert(VehicleSpec(vehicleId = id, vin = vin))
        }
    }

    private suspend fun connect(read: VinResponse.Result): DongleCar =
        DongleCarController.onConnected(context, mac) { read }

    private suspend fun sample(vehicleId: String, ts: Long) =
        db.odbSampleDao().insert(
            OdbSample(vehicleId = vehicleId, pid = "010C", value = 800.0, unit = "rpm", timestamp = ts),
        )

    /** A disconnect long enough ago that the next connect is a new drive, not a Bluetooth blip. */
    private fun longAgo() = System.currentTimeMillis() - 2 * DongleCarController.SESSION_CONTINUES_MS

    private suspend fun specVin(carId: String) = db.vehicleSpecDao().get(carId)?.vin.orEmpty()

    // ---- matched ------------------------------------------------------------------------------

    @Test
    fun `matched - the VIN's car is the connected and recording car, over a hand pick`() = runBlocking {
        seed()
        ActiveVehicle.select(context, "car:truck")
        val d = connect(VinResponse.Result.Vin(suvVin)) as DongleCar.OnCar

        assertEquals("car:suv", d.carId)
        assertEquals(DongleCar.Basis.VIN, d.basis)
        assertEquals("car:truck", d.pickOverridden)
        assertEquals("car:suv", ActiveVehicle.recordingId(context))
        assertEquals("car:suv", ActiveVehicle.current(context))
        assertEquals("car:suv", DongleCarController.lastConfirmed(context, mac))
        assertEquals("car:suv", ActiveVehicle.lastConnected(context))
    }

    @Test
    fun `matched - a switch to another car keeps live data on the VIN's car and says so`() = runBlocking {
        seed()
        connect(VinResponse.Result.Vin(suvVin))
        val out = VehicleController.switchToOutcome(context, "car:truck")

        assertTrue(out.success)
        assertTrue(out.message.contains("live readings stay on the"))
        assertEquals("car:suv", ActiveVehicle.recordingId(context))
        assertEquals("car:truck", ActiveVehicle.selected(context)) // still the default when unplugged
    }

    // ---- unknown VIN --------------------------------------------------------------------------

    @Test
    fun `unknown - data is held under the VIN, never on the picked car`() = runBlocking {
        seed()
        ActiveVehicle.select(context, "car:truck")
        val d = connect(VinResponse.Result.Vin(newVin))

        assertTrue(d is DongleCar.Held)
        assertEquals("pending:vin:$newVin", ActiveVehicle.recordingId(context))
        assertEquals("car:truck", ActiveVehicle.current(context)) // questions still go to the pick
        assertFalse(ActiveVehicle.recordingId(context) == ActiveVehicle.current(context))
    }

    @Test
    fun `unknown - the answer moves readings, drives and codes and saves the VIN, so next time matches`() =
        runBlocking {
        seed()
        connect(VinResponse.Result.Vin(newVin))
        val pending = ActiveVehicle.recordingId(context)
        sample(pending, 1_000L)
        sample(pending, 2_000L)
        db.driveDao().insert(
            Drive(vehicleId = pending, startedAt = 1_000L, endedAt = 2_000L, miles = 3.0, endReason = "ENGINE_OFF"),
        )
        db.codeEventDao().insert(
            CodeEvent(vehicleId = pending, timestamp = 1_500L, mileage = null, codesJson = "[]", freezeFrameJson = ""),
        )

        val out = DongleCarController.answer(context, "car:hybrid")

        assertTrue(out.message, out.ok)
        assertEquals(2, db.odbSampleDao().totalCount("car:hybrid"))
        assertEquals(0, db.odbSampleDao().totalCount(pending))
        assertEquals(1, db.driveDao().getRecent("car:hybrid", 10).size)
        assertEquals(1, db.codeEventDao().getAll("car:hybrid").size)
        assertEquals(newVin, specVin("car:hybrid"))
        assertEquals("car:hybrid", ActiveVehicle.recordingId(context))
        assertTrue(db.odbSampleDao().pendingVehicleIds().isEmpty())

        DongleCarController.onDisconnected(context)
        val again = connect(VinResponse.Result.Vin(newVin)) as DongleCar.OnCar
        assertEquals("car:hybrid", again.carId)
        assertEquals(DongleCar.Basis.VIN, again.basis)
    }

    @Test
    fun `unknown - a car with a different VIN on file is not overwritten until he says replace`() = runBlocking {
        seed()
        connect(VinResponse.Result.Vin(newVin))
        sample(ActiveVehicle.recordingId(context), 1_000L)

        val first = DongleCarController.answer(context, "car:truck")
        assertFalse(first.ok)
        assertTrue(first.message.contains("Nothing changed"))
        assertEquals(truckVin, specVin("car:truck"))
        assertEquals(0, db.odbSampleDao().totalCount("car:truck"))
        assertTrue(DongleCarController.conflict.value != null)

        // At once again is a model retry, not his yes: still nothing.
        assertFalse(DongleCarController.answer(context, "car:truck").ok)
        assertEquals(truckVin, specVin("car:truck"))

        // Saying it again a moment later is the explicit yes (the voice path has no replace parameter).
        DongleCarController.ageReplaceAskForTest(DongleCarController.REPLACE_MIN_GAP_MS)
        val second = DongleCarController.answer(context, "car:truck")
        assertTrue(second.message, second.ok)
        assertEquals(newVin, specVin("car:truck"))
        assertEquals(1, db.odbSampleDao().totalCount("car:truck"))
    }

    @Test
    fun `unknown - a write of this car's details is refused while nobody knows which car it is`() = runBlocking {
        seed()
        connect(VinResponse.Result.Vin(newVin))
        val out = VehicleController.registerDirect(context, 2026, "Toyota", "RAV4")
        assertFalse(out.success)
        assertNull(db.vehicleDao().getByMac("pending:vin:$newVin"))
    }

    @Test
    fun `unknown - a VIN read for specs is not saved onto the picked car`() = runBlocking {
        seed()
        ActiveVehicle.select(context, "car:hybrid")
        connect(VinResponse.Result.Vin(newVin))
        val refusal = VehicleSpecController.obdVinRefusal(context, newVin)
        assertTrue(refusal != null)
        assertEquals("", specVin("car:hybrid"))
    }

    // ---- unreadable VIN -----------------------------------------------------------------------

    @Test
    fun `unreadable with no earlier answer is held under the dongle, then the answer is remembered`() = runBlocking {
        seed()
        val d = connect(VinResponse.Result.NoData)
        assertTrue(d is DongleCar.Held)
        val held = ActiveVehicle.recordingId(context)
        assertTrue(held, held.startsWith("pending:obd:$mac@"))
        sample(held, 1_000L)

        val out = DongleCarController.answer(context, "car:truck")
        assertTrue(out.message, out.ok)
        assertEquals(1, db.odbSampleDao().totalCount("car:truck"))
        assertEquals("car:truck", DongleCarController.lastConfirmed(context, mac))
        assertEquals("", specVin("car:hybrid")) // no VIN to write
    }

    @Test
    fun `unreadable assumes the last confirmed car and holds its window from upload`() = runBlocking {
        seed()
        connect(VinResponse.Result.NoData)
        DongleCarController.answer(context, "car:truck")
        DongleCarController.onDisconnected(context, at = longAgo())
        DongleCarController.resetForTest() // a new process, a later drive

        val d = DongleCarController.onConnected(context, mac) { VinResponse.Result.Unreadable("garbage") }
        assertEquals(DongleCar.OnCar(mac, "car:truck", null, DongleCar.Basis.ASSUMED), d)
        assertEquals("car:truck", ActiveVehicle.recordingId(context))
        val window = DongleCarController.heldWindow(context)!!
        assertEquals("car:truck", window.first)
        assertTrue(DongleCarController.uploadHeld("car:truck", window.second + 1, window))
        assertFalse(DongleCarController.uploadHeld("car:truck", window.second - 1, window))
        assertFalse(DongleCarController.uploadHeld("car:suv", window.second + 1, window))
    }

    @Test
    fun `unreadable - correcting the assumption moves the window as a correction rule`() = runBlocking {
        seed()
        connect(VinResponse.Result.NoData)
        DongleCarController.answer(context, "car:truck")
        DongleCarController.onDisconnected(context, at = longAgo())
        DongleCarController.resetForTest()
        connect(VinResponse.Result.NoResponse)
        val from = DongleCarController.heldWindow(context)!!.second
        sample("car:truck", from - 10_000L) // an older truck reading, outside the window: stays
        sample("car:truck", from + 1)

        val out = DongleCarController.answer(context, "car:suv")

        assertTrue(out.message, out.ok)
        assertEquals(1, db.odbSampleDao().totalCount("car:suv"))
        assertEquals(1, db.odbSampleDao().totalCount("car:truck"))
        assertEquals(1, db.driveReassignmentDao().getAll().size)
        assertNull(DongleCarController.heldWindow(context))
        assertEquals("car:suv", DongleCarController.lastConfirmed(context, mac))
    }

    @Test
    fun `unreadable - confirming the assumption releases the hold and moves nothing`() = runBlocking {
        seed()
        connect(VinResponse.Result.NoData)
        DongleCarController.answer(context, "car:truck")
        DongleCarController.onDisconnected(context, at = longAgo())
        DongleCarController.resetForTest()
        connect(VinResponse.Result.Unsupported)
        sample("car:truck", System.currentTimeMillis())

        val out = DongleCarController.answer(context, "car:truck")

        assertTrue(out.ok)
        assertNull(DongleCarController.heldWindow(context))
        assertEquals(DongleCar.Basis.ANSWER, (DongleCarController.state.value as DongleCar.OnCar).basis)
        assertTrue(db.driveReassignmentDao().getAll().isEmpty())
    }

    @Test
    fun `a reconnect blip after an answer is not a fresh assumption`() {
        assertTrue(
            DongleCarController.sessionContinues("car:truck", "car:truck", disconnectedAt = 1_000L, now = 61_000L),
        )
        assertFalse(DongleCarController.sessionContinues("car:truck", "car:suv", 1_000L, 61_000L))
        assertFalse(
            DongleCarController.sessionContinues(
                "car:truck", "car:truck", 1_000L, 1_000L + DongleCarController.SESSION_CONTINUES_MS + 1,
            ),
        )
        assertFalse(DongleCarController.sessionContinues("car:truck", "car:truck", 0L, 61_000L))
    }

    @Test
    fun `a failed or slow VIN read still decides, as no VIN`() = runBlocking {
        seed()
        val d = DongleCarController.onConnected(context, mac) { throw java.io.IOException("socket closed") }
        assertTrue(d is DongleCar.Held)
    }

    @Test
    fun `leftover pending readings from an earlier connection can be answered later`() = runBlocking {
        seed()
        sample("pending:vin:$newVin", 1_000L)
        assertEquals(listOf("pending:vin:$newVin"), DongleCarController.openQuestions(context))

        val out = DongleCarController.answer(context, "car:hybrid", subjectId = "pending:vin:$newVin")

        assertTrue(out.message, out.ok)
        assertEquals(1, db.odbSampleDao().totalCount("car:hybrid"))
        assertEquals(newVin, specVin("car:hybrid"))
    }

    @Test
    fun `a second answer to readings already filed changes nothing, the VIN included`() = runBlocking {
        seed()
        sample("pending:vin:$newVin", 1_000L)
        assertTrue(DongleCarController.answer(context, "car:hybrid", subjectId = "pending:vin:$newVin").ok)

        // A stale card or a second tap names another car for the same, now empty, pending id.
        val out = DongleCarController.answer(context, "car:truck", subjectId = "pending:vin:$newVin", replaceVin = true)

        assertFalse(out.message, out.ok)
        assertTrue(out.message, out.message.contains("nothing changed"))
        assertEquals(newVin, specVin("car:hybrid"))
        assertEquals(truckVin, specVin("car:truck"))
        assertEquals(1, db.odbSampleDao().totalCount("car:hybrid"))
    }

    @Test
    fun `a switch with the dongle gone does not file held readings, and says they still wait`() = runBlocking {
        seed()
        connect(VinResponse.Result.Vin(newVin))
        val held = ActiveVehicle.recordingId(context)
        sample(held, 1_000L)
        DongleCarController.onDisconnected(context)

        val out = VehicleController.switchToOutcome(context, "car:truck")

        assertTrue(out.success)
        assertEquals("car:truck", ActiveVehicle.selected(context))
        assertTrue(out.message, out.message.contains("did not file them"))
        assertEquals(listOf(held), DongleCarController.openQuestions(context))
        assertEquals(0, db.odbSampleDao().totalCount("car:truck"))
    }

    @Test
    fun `a switch with nothing held says only the pick`() = runBlocking {
        seed()
        val out = VehicleController.switchToOutcome(context, "car:truck")
        assertTrue(out.message, out.message.endsWith(" now."))
        assertFalse(out.message, out.message.contains("did not file them"))
    }

    // ---- answers after the dongle is gone, and the VIN read late ----------------------------

    @Test
    fun `a held connection can be answered after the dongle disconnects`() = runBlocking {
        seed()
        connect(VinResponse.Result.Vin(newVin))
        val held = ActiveVehicle.recordingId(context)
        sample(held, 1_000L)
        DongleCarController.onDisconnected(context)

        val out = DongleCarController.answer(context, "car:hybrid", subjectId = held)

        assertTrue(out.message, out.ok)
        assertEquals(1, db.odbSampleDao().totalCount("car:hybrid"))
        assertEquals(newVin, specVin("car:hybrid"))
    }

    @Test
    fun `an assumed drive can be corrected after the dongle disconnects`() = runBlocking {
        seed()
        connect(VinResponse.Result.NoData)
        DongleCarController.answer(context, "car:truck")
        DongleCarController.onDisconnected(context, at = longAgo())
        DongleCarController.resetForTest()
        connect(VinResponse.Result.NoData)
        val from = DongleCarController.heldWindow(context)!!.second
        sample("car:truck", from + 1)
        DongleCarController.onDisconnected(context) // parked; now he taps

        val out = DongleCarController.answer(context, "car:suv", subjectId = "car:truck")

        assertTrue(out.message, out.ok)
        assertEquals(1, db.odbSampleDao().totalCount("car:suv"))
        assertNull(DongleCarController.heldWindow(context))
        assertEquals("car:suv", DongleCarController.lastConfirmed(context, mac))
    }

    @Test
    fun `no VIN at connect, then the VIN at engine start, moves the assumed window to the VIN's car`() = runBlocking {
        seed()
        connect(VinResponse.Result.NoData)
        DongleCarController.answer(context, "car:truck")
        DongleCarController.onDisconnected(context, at = longAgo())
        DongleCarController.resetForTest()
        connect(VinResponse.Result.NoData) // key on, engine off: assumed truck
        val from = DongleCarController.heldWindow(context)!!.second
        sample("car:truck", from + 1)

        DongleCarController.retryVinIfMissing(context) { VinResponse.Result.Vin(suvVin) }

        val now = DongleCarController.state.value as DongleCar.OnCar
        assertEquals("car:suv", now.carId)
        assertEquals(DongleCar.Basis.VIN, now.basis)
        assertEquals(1, db.odbSampleDao().totalCount("car:suv"))
        assertNull(DongleCarController.heldWindow(context))
        // Once per connection.
        var reads = 0
        DongleCarController.retryVinIfMissing(context) { reads++; VinResponse.Result.Vin(truckVin) }
        assertEquals(0, reads)
    }

    @Test
    fun `no VIN at connect, then an unknown VIN at engine start, holds the data under the VIN`() = runBlocking {
        seed()
        connect(VinResponse.Result.NoResponse)
        val dongleHeld = ActiveVehicle.recordingId(context)
        sample(dongleHeld, 1_000L)

        DongleCarController.retryVinIfMissing(context) { VinResponse.Result.Vin(newVin) }

        assertEquals("pending:vin:$newVin", ActiveVehicle.recordingId(context))
        assertEquals(1, db.odbSampleDao().totalCount("pending:vin:$newVin"))
        assertEquals(0, db.odbSampleDao().totalCount(dongleHeld))
    }

    @Test
    fun `a reconnect blip that reads the VIN carries the no-VIN hold to the VIN's car`() = runBlocking {
        seed()
        connect(VinResponse.Result.NoData)
        val dongleHeld = ActiveVehicle.recordingId(context)
        sample(dongleHeld, 1_000L)
        DongleCarController.onDisconnected(context) // a blip, seconds later it reconnects

        val d = connect(VinResponse.Result.Vin(truckVin)) as DongleCar.OnCar

        assertEquals("car:truck", d.carId)
        assertEquals(1, db.odbSampleDao().totalCount("car:truck"))
        assertTrue(db.odbSampleDao().pendingVehicleIds().isEmpty())
    }

    @Test
    fun `two unanswered no-VIN drives never pool under one id`() = runBlocking {
        seed()
        connect(VinResponse.Result.NoData)
        val first = ActiveVehicle.recordingId(context)
        DongleCarController.onDisconnected(context, at = longAgo())
        Thread.sleep(2)
        connect(VinResponse.Result.NoData)
        val second = ActiveVehicle.recordingId(context)
        assertFalse(first == second)
    }

    @Test
    fun `a VIN on the wrong car on file can be replaced by his explicit word`() = runBlocking {
        seed()
        connect(VinResponse.Result.Vin(truckVin)) // the truck's VIN on file is really the hybrid's
        val first = DongleCarController.answer(context, "car:hybrid")
        assertFalse(first.ok)
        assertTrue(DongleCarController.conflict.value != null)

        val replaced = DongleCarController.answer(context, "car:hybrid", replaceVin = true)

        assertTrue(replaced.message, replaced.ok)
        assertEquals(truckVin, specVin("car:hybrid"))
        assertEquals("", specVin("car:truck"))
        assertEquals("car:hybrid", ActiveVehicle.recordingId(context))
    }
}

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

    /** Two made-up car fingerprints ([CarFingerprint.of]): protocol, then 0100 bitmask lines. */
    private val printA = "p6|410012345678"
    private val printB = "p2|4100ABCDEF01"

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

    private suspend fun connect(read: VinResponse.Result, print: String? = printA): DongleCar =
        DongleCarController.onConnected(context, mac, print) { read }

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
        assertEquals("car:suv", DongleCarController.recognised(context, printA))
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
        // Nothing was rebound: never changed=true over a message that says nothing changed (review A3).
        assertEquals(false, out.changed)
        assertTrue(out.message, out.message.contains("Nothing changed"))
    }

    @Test
    fun `a plain switch with nothing connected is a change`() = runBlocking {
        seed()
        ActiveVehicle.select(context, "car:suv")
        val out = VehicleController.switchToOutcome(context, "car:truck")
        assertTrue(out.success)
        assertEquals(true, out.changed)
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
        assertEquals("car:truck", DongleCarController.recognised(context, printA))
        assertEquals("", specVin("car:hybrid")) // no VIN to write
    }

    @Test
    fun `unreadable assumes the last confirmed car and holds its window from upload`() = runBlocking {
        seed()
        connect(VinResponse.Result.NoData)
        DongleCarController.answer(context, "car:truck")
        DongleCarController.onDisconnected(context, at = longAgo())
        DongleCarController.resetForTest() // a new process, a later drive

        val d = DongleCarController.onConnected(context, mac, printA) { VinResponse.Result.Unreadable("garbage") }
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
        // His correction moves the print off the truck (review A7): next time it is the SUV.
        assertEquals(mapOf("car:suv" to printA), CarFingerprint.all(context))
        assertEquals("car:suv", DongleCarController.recognised(context, printA))
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
        val d = DongleCarController.onConnected(context, mac, printA) { throw java.io.IOException("socket closed") }
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
        // His correction moves the print off the truck (review A7): next time it is the SUV.
        assertEquals(mapOf("car:suv" to printA), CarFingerprint.all(context))
        assertEquals("car:suv", DongleCarController.recognised(context, printA))
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
    fun `adapter moved from a car with no VIN to a VIN car minutes later - the held drive stays held`() = runBlocking {
        seed()
        connect(VinResponse.Result.NoData, printA)
        val held = ActiveVehicle.recordingId(context)
        sample(held, 1_000L)
        DongleCarController.onDisconnected(context, at = System.currentTimeMillis() - 60_000L)

        val d = connect(VinResponse.Result.Vin(truckVin), printB) as DongleCar.OnCar

        assertEquals("car:truck", d.carId)
        assertEquals(0, db.odbSampleDao().totalCount("car:truck"))
        assertEquals(listOf(held), DongleCarController.openQuestions(context))
    }

    @Test
    fun `a print-less car then a VIN car right after - no evidence it is the same car, nothing carried`() =
        runBlocking {
            seed()
            connect(VinResponse.Result.NoData, null)
            val held = ActiveVehicle.recordingId(context)
            sample(held, 1_000L)
            DongleCarController.onDisconnected(context)

            connect(VinResponse.Result.Vin(truckVin), null)

            assertEquals(0, db.odbSampleDao().totalCount("car:truck"))
            assertEquals(listOf(held), DongleCarController.openQuestions(context))
        }

    @Test
    fun `twins - the same print with a late VIN read inside a blip is carried (indistinguishable, a stated limit)`() =
        runBlocking {
            seed()
            connect(VinResponse.Result.NoData, printA)
            sample(ActiveVehicle.recordingId(context), 1_000L)
            DongleCarController.onDisconnected(context)

            connect(VinResponse.Result.Vin(truckVin), printA)

            assertEquals(1, db.odbSampleDao().totalCount("car:truck"))
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

    // ---- one adapter, moved between cars (Kevin, 2026-11-17) ------------------------------------

    @Test
    fun `fingerprint - learned when he names the car, then the car is recognised on reconnect`() = runBlocking {
        seed()
        val first = connect(VinResponse.Result.NoData, printA)
        assertTrue(first is DongleCar.Held)
        assertTrue(DongleCarController.answer(context, "car:truck").ok)
        assertEquals("car:truck", DongleCarController.recognised(context, printA))
        DongleCarController.onDisconnected(context, at = longAgo())
        assertEquals(null, DongleCarController.state.value)
        val again = connect(VinResponse.Result.NoData, printA) as DongleCar.OnCar
        assertEquals("car:truck", again.carId)
        assertEquals(DongleCar.Basis.ASSUMED, again.basis)
    }

    @Test
    fun `fingerprint - two cars swapped back and forth are told apart, and an unknown print is asked`() = runBlocking {
        seed()
        connect(VinResponse.Result.NoData, printA)
        DongleCarController.answer(context, "car:truck")
        DongleCarController.onDisconnected(context, at = longAgo())
        connect(VinResponse.Result.NoData, printB)
        DongleCarController.answer(context, "car:suv")
        DongleCarController.onDisconnected(context, at = longAgo())
        assertEquals("car:truck", (connect(VinResponse.Result.NoData, printA) as DongleCar.OnCar).carId)
        DongleCarController.onDisconnected(context, at = longAgo())
        assertEquals("car:suv", (connect(VinResponse.Result.NoData, printB) as DongleCar.OnCar).carId)
        DongleCarController.onDisconnected(context, at = longAgo())
        // A car that answers like neither, or gives no print at all: unknown, he is asked.
        assertTrue(connect(VinResponse.Result.NoData, "p3|4100FFFFFFFF") is DongleCar.Held)
        DongleCarController.onDisconnected(context, at = longAgo())
        assertTrue(connect(VinResponse.Result.NoData, null) is DongleCar.Held)
    }

    @Test
    fun `fingerprint - his correction moves the print, so next time the car he named is recognised`() =
        runBlocking {
            seed()
            connect(VinResponse.Result.NoData, printA)
            DongleCarController.answer(context, "car:truck")
            DongleCarController.onDisconnected(context, at = longAgo())
            assertEquals("car:truck", (connect(VinResponse.Result.NoData, printA) as DongleCar.OnCar).carId)
            assertTrue(DongleCarController.answer(context, "car:suv").ok)
            assertEquals("car:suv", (DongleCarController.state.value as DongleCar.OnCar).carId)
            assertEquals(mapOf("car:suv" to printA), CarFingerprint.all(context))
            DongleCarController.onDisconnected(context, at = longAgo())
            assertEquals("car:suv", (connect(VinResponse.Result.NoData, printA) as DongleCar.OnCar).carId)
        }

    @Test
    fun `archiving a car forgets its print`() = runBlocking {
        seed()
        connect(VinResponse.Result.NoData, printA)
        DongleCarController.answer(context, "car:truck")
        assertEquals("car:truck", DongleCarController.recognised(context, printA))
        VehicleController.archive(context, "car:truck")
        assertEquals(emptyMap<String, String>(), CarFingerprint.all(context))
    }

    @Test
    fun `fingerprint - an answer about an earlier held connection teaches nothing about the car plugged in now`() =
        runBlocking {
            seed()
            val heldA = connect(VinResponse.Result.NoData, printA) as DongleCar.Held
            DongleCarController.onDisconnected(context, at = longAgo())
            connect(VinResponse.Result.NoData, printB)
            // On the Cars screen he answers the OLD held readings: they were the truck.
            DongleCarController.answer(context, "car:truck", subjectId = heldA.recordingId)
            assertEquals(null, DongleCarController.recognised(context, printB))
            assertEquals(null, DongleCarController.recognised(context, printA))
        }

    @Test
    fun `a short drop with the same print keeps his answer, the connection and the clear permission`() = runBlocking {
        seed()
        connect(VinResponse.Result.NoData, printA)
        DongleCarController.answer(context, "car:truck")
        val key = DongleCarController.connectionKey()
        DongleCarController.onDisconnected(context, at = System.currentTimeMillis() - 30_000L)
        val again = connect(VinResponse.Result.NoData, printA) as DongleCar.OnCar
        assertEquals("car:truck", again.carId)
        assertEquals(DongleCar.Basis.ANSWER, again.basis)
        assertEquals(key, DongleCarController.connectionKey())
        assertNull(DongleCarController.heldWindow(context))
    }

    @Test
    fun `two print-less cars swapped inside two minutes - the second is asked, files nothing under the first`() =
        runBlocking {
            seed()
            connect(VinResponse.Result.NoData, null)
            assertTrue(DongleCarController.answer(context, "car:hybrid").ok)
            val firstKey = DongleCarController.connectionKey()
            DongleCarController.onDisconnected(context, at = System.currentTimeMillis() - 30_000L)

            val second = connect(VinResponse.Result.NoData, null)
            assertTrue(second is DongleCar.Held)
            assertTrue(DongleCar.isPending(ActiveVehicle.recordingId(context).orEmpty()))
            assertTrue(firstKey != DongleCarController.connectionKey())
            assertTrue(AdapterBinding.clearRefusal(second, connected = true)!!.startsWith("Nothing was cleared"))
        }

    @Test
    fun `a clear yes asked before a print-less drop is void after it`() = runBlocking {
        seed()
        connect(VinResponse.Result.NoData, null)
        DongleCarController.answer(context, "car:hybrid")
        val gate = ClearCodesVoiceGate()
        gate.asked("clear the codes", DongleCarController.connectionKey())
        DongleCarController.onDisconnected(context, at = System.currentTimeMillis() - 30_000L)
        connect(VinResponse.Result.NoData, null)
        val now = DongleCarController.connectionKey()
        assertEquals(ClearCodesVoiceGate.OTHER_CAR, gate.checkConfirmed("yes", subject = now))
    }

    @Test
    fun `a same-print short drop continues without a new ask`() = runBlocking {
        seed()
        val first = connect(VinResponse.Result.NoData, printA) as DongleCar.Held
        val key = DongleCarController.connectionKey()
        DongleCarController.onDisconnected(context)
        val again = connect(VinResponse.Result.NoData, printA) as DongleCar.Held
        assertEquals(first.recordingId, again.recordingId)
        assertEquals(key, DongleCarController.connectionKey())
    }

    @Test
    fun `a reconnect after more than two minutes is a new connection, decided afresh`() = runBlocking {
        seed()
        connect(VinResponse.Result.NoData, printA)
        DongleCarController.answer(context, "car:truck")
        val key = DongleCarController.connectionKey()
        DongleCarController.onDisconnected(context, at = System.currentTimeMillis() - 3 * 60_000L)
        val again = connect(VinResponse.Result.NoData, printA) as DongleCar.OnCar
        assertEquals(DongleCar.Basis.ASSUMED, again.basis)
        assertTrue(key != DongleCarController.connectionKey())
    }

    @Test
    fun `a short drop then a VIN of another car - the VIN decides, never his earlier answer`() = runBlocking {
        seed()
        connect(VinResponse.Result.NoData, printA)
        DongleCarController.answer(context, "car:hybrid")
        DongleCarController.onDisconnected(context)
        val d = connect(VinResponse.Result.Vin(truckVin), printA) as DongleCar.OnCar
        assertEquals(DongleCar.OnCar(mac, "car:truck", truckVin, DongleCar.Basis.VIN), d.copy(pickOverridden = null))
    }

    @Test
    fun `no print and no VIN - every reconnect is its own held drive, even a short drop`() = runBlocking {
        seed()
        val first = connect(VinResponse.Result.NoData, null) as DongleCar.Held
        DongleCarController.onDisconnected(context)
        Thread.sleep(5)
        val second = connect(VinResponse.Result.NoData, null) as DongleCar.Held
        assertTrue(first.recordingId != second.recordingId)
    }

    @Test
    fun `each connection has its own key, even to the same car`() = runBlocking {
        seed()
        assertNull(DongleCarController.connectionKey())
        connect(VinResponse.Result.Vin(suvVin), printA)
        val k1 = DongleCarController.connectionKey()!!
        DongleCarController.onDisconnected(context, at = longAgo())
        assertNull(DongleCarController.connectionKey())
        connect(VinResponse.Result.Vin(suvVin), printA)
        assertTrue(k1 != DongleCarController.connectionKey())
    }

    @Test
    fun `a clear question asked on one connection cannot be answered on the next`() = runBlocking {
        seed()
        connect(VinResponse.Result.Vin(suvVin), printA)
        val k1 = DongleCarController.connectionKey()!!
        DongleCarController.onDisconnected(context, at = longAgo())
        connect(VinResponse.Result.Vin(truckVin), printB)
        val gate = ClearCodesVoiceGate()
        gate.asked("clear the codes", k1)
        val now = DongleCarController.connectionKey()
        assertEquals(ClearCodesVoiceGate.OTHER_CAR, gate.checkConfirmed("yes", subject = now))
    }

    @Test
    fun `an old per-adapter binding is never read, and the rows it filed are untouched`() = runBlocking {
        seed()
        // What the phone stored before 1b: the adapter bound to the truck for good.
        context.getSharedPreferences("dongle_car", android.content.Context.MODE_PRIVATE).edit()
            .putString("last_car:$mac", "car:truck").commit()
        sample("car:truck", 1_000L)
        db.driveDao().insert(
            Drive(vehicleId = "car:truck", startedAt = 1_000L, endedAt = 2_000L, miles = 3.0, endReason = "ENGINE_OFF"),
        )
        db.serviceRecordDao().insert(
            com.kevin.legion.data.local.ServiceRecord(
                vehicleId = "car:truck", serviceName = "Oil change", mileage = 1000, date = 1_000L,
            ),
        )

        // No VIN, no learned print: the adapter is in a car nobody has named, whatever it was in before.
        assertTrue(connect(VinResponse.Result.NoData, printA) is DongleCar.Held)

        assertEquals(1, db.odbSampleDao().totalCount("car:truck"))
        assertEquals(1, db.driveDao().getRecent("car:truck", 10).size)
        assertEquals(1, db.serviceRecordDao().getRecordsForVehicleOnce("car:truck").size)
        // Left in place, not deleted (nothing reads it).
        assertEquals(
            "car:truck",
            context.getSharedPreferences("dongle_car", android.content.Context.MODE_PRIVATE)
                .getString("last_car:$mac", null),
        )
    }

    @Test
    fun `a VIN wins over a print learned for another car, and the print is then asked about`() = runBlocking {
        seed()
        connect(VinResponse.Result.NoData, printA)
        DongleCarController.answer(context, "car:hybrid")
        DongleCarController.onDisconnected(context, at = longAgo())
        assertEquals("car:hybrid", DongleCarController.recognised(context, printA))

        // Same print, but this time the VIN reads, and it is the SUV's.
        val d = connect(VinResponse.Result.Vin(suvVin), printA) as DongleCar.OnCar
        assertEquals("car:suv", d.carId)
        assertEquals(DongleCar.Basis.VIN, d.basis)
        // The SUV learned the print; the hybrid keeps it too, so a print-only connect is asked, never guessed.
        assertEquals(mapOf("car:hybrid" to printA, "car:suv" to printA), CarFingerprint.all(context))
        assertNull(DongleCarController.recognised(context, printA))
        DongleCarController.onDisconnected(context, at = longAgo())
        assertTrue(connect(VinResponse.Result.NoData, printA) is DongleCar.Held)
    }

    @Test
    fun `twins - two cars a VIN showed to share one print are never guessed`() = runBlocking {
        seed()
        connect(VinResponse.Result.NoData, printA)
        DongleCarController.answer(context, "car:hybrid")
        DongleCarController.onDisconnected(context, at = longAgo())
        // The twin reads its VIN and answers exactly the same: both keep the print.
        connect(VinResponse.Result.Vin(truckVin), printA)
        DongleCarController.onDisconnected(context, at = longAgo())
        assertTrue(connect(VinResponse.Result.NoData, printA) is DongleCar.Held)
    }

    @Test
    fun `fingerprint - a quick reconnect to a car that answers differently is a new connection`() = runBlocking {
        seed()
        connect(VinResponse.Result.NoData, printA)
        DongleCarController.answer(context, "car:truck")
        DongleCarController.onDisconnected(context)
        // Seconds later, plugged into another car: nothing of the truck's binding carries over.
        val moved = connect(VinResponse.Result.NoData, printB)
        assertTrue(moved is DongleCar.Held)
    }
}

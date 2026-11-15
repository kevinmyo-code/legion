package com.kevin.legion.vehicle

import com.kevin.legion.vehicle.DongleCar.AnswerPlan
import com.kevin.legion.vehicle.DongleCar.Basis
import com.kevin.legion.vehicle.DongleCar.CarVin
import com.kevin.legion.vehicle.DongleCar.Held
import com.kevin.legion.vehicle.DongleCar.HeldReason
import com.kevin.legion.vehicle.DongleCar.OnCar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The decision and the answer, with made-up cars, MACs and VINs. */
class DongleCarTest {

    private val mac = "00:11:22:33:44:55"
    private val truckVin = "1A2B3C4D5E6F7G8H9"
    private val suvVin = "9Z8Y7X6W5V4U3T2S1"
    private val newVin = "FAKECARVXN0000003"

    private val truck = CarVin("car:truck", truckVin)
    private val suv = CarVin("car:suv", suvVin)
    private val noVinCar = CarVin("car:hybrid", "")
    private val cars = listOf(truck, suv, noVinCar)

    private fun vin(v: String) = VinResponse.Result.Vin(v)

    // ---- decide -----------------------------------------------------------------------------

    @Test
    fun `a VIN on one car makes that car the connected car`() {
        val d = DongleCar.decide(mac, vin(suvVin), cars, lastConfirmedForDongle = "car:truck", selectedId = null)
        assertEquals(OnCar(mac, "car:suv", suvVin, Basis.VIN), d)
        assertEquals("car:suv", d.recordingId)
    }

    @Test
    fun `a VIN match wins over a hand pick of another car, and says which pick it overrode`() {
        val d = DongleCar.decide(mac, vin(suvVin), cars, null, selectedId = "car:truck") as OnCar
        assertEquals("car:suv", d.carId)
        assertEquals("car:truck", d.pickOverridden)
    }

    @Test
    fun `a pick that agrees with the VIN overrides nothing`() {
        val d = DongleCar.decide(mac, vin(suvVin), cars, null, selectedId = "car:suv") as OnCar
        assertEquals(null, d.pickOverridden)
    }

    @Test
    fun `VINs match whatever their case and spacing on file`() {
        val sloppy = listOf(CarVin("car:suv", " ${suvVin.lowercase()} "))
        val d = DongleCar.decide(mac, vin(suvVin), sloppy, null, null)
        assertEquals("car:suv", (d as OnCar).carId)
    }

    @Test
    fun `an unknown VIN is held under a pending id keyed by the VIN, never a guess`() {
        val d = DongleCar.decide(mac, vin(newVin), cars, lastConfirmedForDongle = "car:truck", selectedId = "car:truck")
        assertEquals(Held(mac, newVin, HeldReason.UNKNOWN_VIN), d)
        assertEquals("pending:vin:$newVin", d.recordingId)
        assertTrue(DongleCar.isPending(d.recordingId))
        assertEquals(newVin, DongleCar.vinOfPending(d.recordingId))
    }

    @Test
    fun `a VIN on two cars is held, never a pick between them`() {
        val twins = listOf(truck, CarVin("car:clone", truckVin))
        val d = DongleCar.decide(mac, vin(truckVin), twins, null, null)
        assertEquals(Held(mac, truckVin, HeldReason.SHARED_VIN), d)
    }

    @Test
    fun `no VIN assumes the last car confirmed for this dongle`() {
        for (r in listOf(VinResponse.Result.NoData, VinResponse.Result.NoResponse,
            VinResponse.Result.Unsupported, VinResponse.Result.Unreadable("garbage"))) {
            val d = DongleCar.decide(mac, r, cars, lastConfirmedForDongle = "car:truck", selectedId = "car:suv")
            assertEquals(OnCar(mac, "car:truck", null, Basis.ASSUMED), d)
        }
    }

    @Test
    fun `no VIN and no earlier answer is held under a dongle-keyed pending id`() {
        val d = DongleCar.decide(mac, VinResponse.Result.NoData, cars, null, selectedId = "car:truck")
        assertEquals(Held(mac, null, HeldReason.NO_VIN), d)
        assertEquals("pending:obd:$mac", d.recordingId)
        assertEquals(null, DongleCar.vinOfPending(d.recordingId))
    }

    @Test
    fun `an earlier answer naming a car no longer on file is not assumed`() {
        val d = DongleCar.decide(mac, VinResponse.Result.NoData, cars, lastConfirmedForDongle = "car:sold", selectedId = null)
        assertEquals(Held(mac, null, HeldReason.NO_VIN), d)
    }

    @Test
    fun `car ids are never pending`() {
        assertFalse(DongleCar.isPending("car:truck"))
        assertFalse(DongleCar.isPending(null))
        assertFalse(DongleCar.isPending(mac))
    }

    // ---- planAnswer -------------------------------------------------------------------------

    @Test
    fun `answering an unknown VIN moves the held data and writes the VIN to the car`() {
        val held = Held(mac, newVin, HeldReason.UNKNOWN_VIN)
        val plan = DongleCar.planAnswer(held, "car:hybrid", cars, replaceVin = false)
        assertEquals(AnswerPlan.Move("pending:vin:$newVin", "car:hybrid", asCorrection = false, writeVin = newVin), plan)
    }

    @Test
    fun `answering with a car that has a different VIN on file asks before replacing it`() {
        val held = Held(mac, newVin, HeldReason.UNKNOWN_VIN)
        assertEquals(
            AnswerPlan.VinConflict("car:truck", truckVin, newVin),
            DongleCar.planAnswer(held, "car:truck", cars, replaceVin = false),
        )
        val replaced = DongleCar.planAnswer(held, "car:truck", cars, replaceVin = true)
        assertEquals(AnswerPlan.Move("pending:vin:$newVin", "car:truck", false, newVin), replaced)
    }

    @Test
    fun `answering a shared VIN clears it from the other car that wrongly held it`() {
        val twins = listOf(truck, CarVin("car:clone", truckVin))
        val held = Held(mac, truckVin, HeldReason.SHARED_VIN)
        val plan = DongleCar.planAnswer(held, "car:truck", twins, replaceVin = false) as AnswerPlan.Move
        assertEquals(null, plan.writeVin) // already on the truck
        assertEquals(listOf("car:clone"), plan.clearVinFrom)
    }

    @Test
    fun `answering a no-VIN connection moves the dongle-keyed data and writes no VIN`() {
        val held = Held(mac, null, HeldReason.NO_VIN)
        val plan = DongleCar.planAnswer(held, "car:truck", cars, replaceVin = false)
        assertEquals(AnswerPlan.Move("pending:obd:$mac", "car:truck", asCorrection = false, writeVin = null), plan)
    }

    @Test
    fun `confirming an assumption settles it and moves nothing`() {
        val assumed = OnCar(mac, "car:truck", null, Basis.ASSUMED)
        assertEquals(AnswerPlan.Keep("car:truck", settles = true), DongleCar.planAnswer(assumed, "car:truck", cars, false))
    }

    @Test
    fun `correcting an assumption moves the data as a correction`() {
        val assumed = OnCar(mac, "car:truck", null, Basis.ASSUMED)
        assertEquals(
            AnswerPlan.Move("car:truck", "car:suv", asCorrection = true, writeVin = null),
            DongleCar.planAnswer(assumed, "car:suv", cars, false),
        )
    }

    @Test
    fun `a car the VIN proved is never moved by a pick`() {
        val matched = OnCar(mac, "car:suv", suvVin, Basis.VIN)
        assertEquals(AnswerPlan.VinSaysOtherCar("car:suv"), DongleCar.planAnswer(matched, "car:truck", cars, false))
        assertEquals(AnswerPlan.Keep("car:suv", settles = false), DongleCar.planAnswer(matched, "car:suv", cars, false))
    }

    @Test
    fun `a wrong answer can be corrected, and the VIN follows to the right car`() {
        // He answered "truck" for an unknown VIN; the VIN was written to the truck; then he corrects it.
        val answered = OnCar(mac, "car:hybrid", newVin, Basis.ANSWER)
        val afterWrongAnswer = listOf(truck, suv, CarVin("car:hybrid", newVin), CarVin("car:spare", ""))
        val plan = DongleCar.planAnswer(answered, "car:spare", afterWrongAnswer, false) as AnswerPlan.Move
        assertEquals("car:hybrid", plan.fromId)
        assertEquals(true, plan.asCorrection)
        assertEquals(newVin, plan.writeVin)
        assertEquals(listOf("car:hybrid"), plan.clearVinFrom)
    }

    @Test
    fun `a car not on file is refused`() {
        val held = Held(mac, null, HeldReason.NO_VIN)
        assertEquals(AnswerPlan.NotOnFile("car:sold"), DongleCar.planAnswer(held, "car:sold", cars, false))
    }

    @Test
    fun `the VIN is spoken by its tail only`() {
        assertEquals("VIN ending F7G8H9", DongleCar.vinTail(truckVin))
    }

    @Test
    fun `his explicit replace moves a VIN that sits on the wrong car`() {
        val matched = OnCar(mac, "car:suv", suvVin, Basis.VIN)
        val plan = DongleCar.planAnswer(matched, "car:hybrid", cars, replaceVin = true) as AnswerPlan.Move
        assertEquals("car:suv", plan.fromId)
        assertEquals(true, plan.asCorrection)
        assertEquals(suvVin, plan.writeVin)
        assertEquals(listOf("car:suv"), plan.clearVinFrom)
    }

    @Test
    fun `a no-VIN hold is keyed by dongle and session, and both read back`() {
        val held = Held(mac, null, HeldReason.NO_VIN, since = 1_700_000_000_000L)
        assertEquals("pending:obd:$mac@1700000000000", held.recordingId)
        assertEquals(mac, DongleCar.macOfPending(held.recordingId))
        assertEquals(1_700_000_000_000L, DongleCar.sinceOfPending(held.recordingId))
        assertEquals("pending:obd:$mac", DongleCar.sessionKey(held.recordingId))
        assertEquals(null, DongleCar.macOfPending("pending:vin:$newVin"))
        assertEquals("car:truck", DongleCar.sessionKey("car:truck"))
    }
}

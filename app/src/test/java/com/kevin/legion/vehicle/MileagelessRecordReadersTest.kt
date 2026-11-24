package com.kevin.legion.vehicle

import com.kevin.legion.advisor.digest.FleetDigestBuilder
import com.kevin.legion.advisor.digest.HomeDigestBuilder
import com.kevin.legion.car.CarAspectSummaries
import com.kevin.legion.data.local.MaintenanceItem
import com.kevin.legion.data.local.ServiceRecord
import com.kevin.legion.data.local.Vehicle
import com.kevin.legion.ui.dumb.MatrixFleetText
import com.kevin.legion.ui.fleet.MileageEntry
import com.kevin.legion.ui.fleet.parseMileageEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * No reader of [ServiceRecord.mileage] may turn a null into a stated `0 mi` - blocker 2 of the RED
 * review on PR #5, gathered here because it is one rule across several surfaces rather than one
 * surface's own concern.
 *
 * Since the car plan's slice 0 a logged service carries a mileage only when the DRIVER stated one,
 * so a null is real data meaning "he gave no reading". Four readers coalesced it to `0` and
 * rendered it as a figure - one of them read aloud, one of them into a model's own prompt, one of
 * them on a line tagged `TrustTier.REPORTED`, and the edit dialog pre-filled that `0` and wrote it
 * back on SAVE. Each of them carried, or sat beside, a comment asserting the invariant ("an
 * OBSERVED row always carries both, non-null, by construction") that slice 0 deleted.
 *
 * **The "never a 0" half is in [MileagelessServiceRegressionTest]**, which is written against the
 * API the unfixed code already had so a revert reads as a failing test. This file holds the half
 * that needs the new seams: what each surface says INSTEAD, and the write side's refusal.
 *
 * Pure seams only, no Room and no `Context`.
 */
class MileagelessRecordReadersTest {

    private val vehicleId = "test-mac"
    private val now = 1_700_000_000_000L
    private val monthMs = 30L * 24 * 60 * 60 * 1000L

    private fun vehicle(odometerBaseline: Int = 100_000) = Vehicle(
        obdMac = vehicleId, name = "", make = "Honda", model = "Civic", year = 2018,
        personaPrompt = "", odometerBaseline = odometerBaseline,
    )

    private fun record(id: Long, mileage: Int?, date: Long) =
        ServiceRecord(id = id, vehicleId = vehicleId, serviceName = "Oil Change", mileage = mileage, date = date)

    // --- what a record with no reading reads as, in words ---------------------------------------

    @Test
    fun `the digest says a record carries no mileage, rather than leaving the gap unexplained`() {
        val text = FleetDigestBuilder.buildDigestText(
            vehicle = vehicle(),
            currentMileage = 106_000,
            items = emptyList(),
            unknownNames = emptyList(),
            nextService = null,
            codeEvents = emptyList(),
            recentServices = listOf(
                record(id = 2, mileage = null, date = now - monthMs),
                record(id = 1, mileage = 100_000, date = now - 5 * monthMs),
            ),
            now = now,
        )

        assertTrue("the row with no reading says so: $text", text.contains("mileage not logged"))
    }

    @Test
    fun `the maintenance prompt says no mileage was logged, in those words`() {
        val item = MaintenanceItem(
            vehicleId = vehicleId, serviceName = "Oil Change",
            intervalSource = "CONFIRMED", intervalMiles = 5_000,
            lastDoneMileage = null, lastDoneDate = null,
        )
        val line = MaintenanceAgent.describeItem(item, record(id = 1, mileage = null, date = now))

        assertTrue(line, line.contains("no mileage logged"))
    }

    // --- the one-line car surfaces --------------------------------------------------------------

    @Test
    fun `the car row says a reading is needed rather than nothing due yet`() {
        val next = VehicleController.NextService(
            byMiles = null, byTime = null, unknownCount = 0, unknownNames = emptyList(),
            odometerUnset = false, allDue = false, needsMileage = listOf("Oil Change"),
        )
        val subtitle = CarAspectSummaries.serviceSubtitle("100,000 mi", next)

        assertFalse("nothing can be timed, so 'nothing due yet' is a claim about a schedule: $subtitle", subtitle.contains("nothing due yet"))
        assertTrue(subtitle, subtitle.contains("Oil Change"))
        assertTrue(subtitle, subtitle.contains("mileage reading"))
    }

    @Test
    fun `the home headline names it rather than reading as on track`() {
        val next = VehicleController.NextService(
            byMiles = null, byTime = null, unknownCount = 0, unknownNames = emptyList(),
            odometerUnset = false, allDue = false, needsMileage = listOf("Oil Change"),
        )
        val line = HomeDigestBuilder.fleetHeadline(
            items = listOf(MaintenanceItem(vehicleId = vehicleId, serviceName = "Oil Change", intervalMiles = 5_000)),
            due = emptyList(),
            next = next,
        )

        assertFalse(line, line.contains("on track"))
        assertTrue(line, line.contains("Oil Change"))
    }

    @Test
    fun `matrix mode names it alongside the no-anchor items, never instead of them`() {
        val next = VehicleController.NextService(
            byMiles = null, byTime = null, unknownCount = 1, unknownNames = listOf("Coolant Flush"),
            odometerUnset = false, allDue = false, needsMileage = listOf("Oil Change"),
        )
        val note = MatrixFleetText.carOf("Civic", "100,000 mi", next).unknown

        assertTrue("both shapes of 'not known to be fine' belong here: $note", note!!.contains("Coolant Flush"))
        assertTrue(note, note.contains("Oil Change"))
    }

    // --- the write half: the edit form may not invent a reading ---------------------------------

    @Test
    fun `a blank mileage box keeps the record with no reading`() {
        assertEquals(MileageEntry.None, parseMileageEntry(""))
        assertEquals(MileageEntry.None, parseMileageEntry("   "))
    }

    @Test
    fun `a zero is refused, never saved as a stated reading of zero`() {
        assertEquals(MileageEntry.Invalid, parseMileageEntry("0"))
        assertEquals(MileageEntry.Invalid, parseMileageEntry("-5"))
        assertEquals(MileageEntry.Invalid, parseMileageEntry("abc"))
    }

    @Test
    fun `a real reading parses to itself`() {
        assertEquals(MileageEntry.Reading(42_300), parseMileageEntry(" 42300 "))
    }
}

package com.kevin.legion.vehicle

import com.kevin.legion.data.local.MaintenanceItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A service logged with NO mileage must not take an item off the schedule, and must not read as
 * "everything is already due" - the car plan's slice 0, blocker 1 of the RED review on PR #5.
 *
 * The shape: an item whose only interval is a mileage one ([MaintenanceItem.intervalMonths] null is
 * legal everywhere - `setMaintenanceInterval` requires only one of the two) gets a plain "I got my
 * oil changed today". That writes a dated OBSERVED row with no mileage, which projects onto the
 * anchor as `lastDoneDate` set and `lastDoneMileage` null. [VehicleController.isDue]'s mileage axis
 * then has no anchor and its time axis has no interval, so the item is never due again; and because
 * [VehicleController.isUnknown] is false (the DATE is known), it was counted on no axis and as no
 * unknown either, which is exactly the condition [VehicleController.NextService.allDue] reads as
 * "everything I've got a record for is already due".
 *
 * Pure functions only, no Context and no Room - same posture as [VehicleControllerIsDueTest].
 */
class VehicleControllerNeedsMileageTest {

    private val vehicleId = "test-mac"
    private val now = 1_764_000_000_000L
    private val yesterday = now - 86_400_000L

    private fun milesOnly(lastDoneMileage: Int?, lastDoneDate: Long?) = MaintenanceItem(
        vehicleId = vehicleId,
        serviceName = "Oil Change",
        intervalMiles = 5_000,
        intervalMonths = null,
        lastDoneMileage = lastDoneMileage,
        lastDoneDate = lastDoneDate,
    )

    // --- needsMileageReading, the predicate itself -----------------------------------------------

    @Test
    fun `a miles-only item logged with no reading needs a mileage reading`() {
        assertTrue(VehicleController.needsMileageReading(milesOnly(null, yesterday)))
    }

    @Test
    fun `a miles-only item with a reading does not`() {
        assertFalse(VehicleController.needsMileageReading(milesOnly(40_000, yesterday)))
    }

    @Test
    fun `an item with a months interval and a date is timed by date, so it needs nothing`() {
        val dualAxis = milesOnly(null, yesterday).copy(intervalMonths = 6)
        assertFalse(VehicleController.needsMileageReading(dualAxis))
    }

    @Test
    fun `an item with no anchor at all stays UNKNOWN, and is never counted twice`() {
        val noAnchor = milesOnly(null, null)
        assertTrue(VehicleController.isUnknown(noAnchor))
        assertFalse(
            "unknownNames and needsMileage must never name the same item",
            VehicleController.needsMileageReading(noAnchor),
        )
    }

    @Test
    fun `a never-done item is already due, so it needs no reading to be timed`() {
        assertFalse(VehicleController.needsMileageReading(milesOnly(null, null).copy(neverDone = true)))
    }

    // --- computeNextService, the state the surfaces read ----------------------------------------

    @Test
    fun `a miles-only item logged with no reading is named, and never flips the schedule to all due`() {
        val next = VehicleController.computeNextService(
            items = listOf(milesOnly(null, yesterday)),
            currentMileage = 42_300,
            now = now,
            odometerBaseline = 42_300,
        )

        assertEquals(listOf("Oil Change"), next?.needsMileage)
        // The `allDue` half of this is pinned in MileagelessServiceRegressionTest, against the API
        // the unfixed code already had, so a revert reads as a failing test rather than a compile
        // error. Here it is the company it keeps: named, and not mistaken for an unknown anchor.
        assertEquals(false, next?.allDue)
        assertEquals("it has an anchor date, so it is not the no-anchor UNKNOWN state", 0, next?.unknownCount)
    }

    @Test
    fun `an item that can still be timed by date is not named as needing a reading`() {
        val next = VehicleController.computeNextService(
            items = listOf(milesOnly(null, yesterday).copy(intervalMonths = 6)),
            currentMileage = 42_300,
            now = now,
            odometerBaseline = 42_300,
        )

        assertEquals(emptyList<String>(), next?.needsMileage)
        assertEquals("Oil Change", next?.byTime?.serviceName)
    }

    @Test
    fun `a logged reading anchors the item again and the ask goes away`() {
        val next = VehicleController.computeNextService(
            items = listOf(milesOnly(42_300, yesterday)),
            currentMileage = 42_400,
            now = now,
            odometerBaseline = 42_300,
        )

        assertEquals(emptyList<String>(), next?.needsMileage)
        assertEquals("Oil Change", next?.byMiles?.serviceName)
        assertEquals(false, next?.allDue)
    }

    // --- what it SAYS ----------------------------------------------------------------------------

    @Test
    fun `the no-mileage confirmation never claims a date timing an item has no interval for`() {
        val said = loggedServiceMessage("Oil Change", statedMileage = null, timedByDate = false, timedByMiles = true)

        assertFalse("there is no months interval to time this by: $said", said.contains("by date"))
        assertTrue("it must say why it cannot say when it is next due: $said", said.contains("mileage"))
    }

    @Test
    fun `an item with no interval at all is told nothing times it, never that it is on a mileage interval`() {
        // The shape an earlier unmatched log creates: an item with an anchor and no intervals.
        val said = loggedServiceMessage("Oil Change", statedMileage = null, timedByDate = false, timedByMiles = false)

        assertFalse("there is no mileage interval to be tracked on: $said", said.contains("mileage interval"))
        assertFalse(said, said.contains("by date"))
        assertTrue("it must say there is no interval: $said", said.contains("interval"))
    }

    @Test
    fun `an item with a months interval is still told it is timed by date`() {
        val said = loggedServiceMessage("Oil Change", statedMileage = null, timedByDate = true, timedByMiles = true)
        assertTrue(said, said.contains("date"))
    }

    @Test
    fun `a stated reading is named in the confirmation, as the figure on the row`() {
        val said = loggedServiceMessage("Oil Change", statedMileage = 42_300, timedByDate = false, timedByMiles = true)
        assertTrue(said, said.contains("42300"))
    }

    @Test
    fun `the low-reading note rides in front of the log confirmation, never dropped`() {
        val note = VehicleController.odometerQuestionNote(miles = 42_300, priorEstimate = 50_000, baselineSet = true)
        val said = noteThen(note, loggedServiceMessage("Oil Change", 42_300, timedByDate = false, timedByMiles = true))

        assertTrue("the driver must hear that his reading is below the estimate: $said", said.contains("50000"))
        assertTrue(said, said.contains("42300"))
    }

    @Test
    fun `with nothing to question the confirmation is unchanged`() {
        val message = loggedServiceMessage("Oil Change", 42_300, timedByDate = false, timedByMiles = true)
        assertEquals(message, noteThen(null, message))
    }

    // --- the backfill half ----------------------------------------------------------------------

    @Test
    fun `a miles-ago backfill resolves an anchor from the estimate, and that is the only place it may go`() {
        // The anchor may carry it (an ASSERTED row IS a driver statement); the OBSERVED record may
        // not, which is why logPastServiceDirect files `mileage` and never `merged.lastDoneMileage`.
        assertEquals(
            42_000,
            VehicleController.resolveBackfillMileage(mileage = null, milesAgo = 8_000, currentMileage = 50_000),
        )
        assertEquals(
            "a stated reading wins outright",
            42_300,
            VehicleController.resolveBackfillMileage(mileage = 42_300, milesAgo = 8_000, currentMileage = 50_000),
        )
    }
}

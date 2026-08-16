package com.kevin.legion.vehicle

import com.kevin.legion.data.local.MaintenanceItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-function regression for
 * `.scratch/fleet-maintenance/issues/15-isdue-and-the-digest-inherit-the-same-two-gaps.md` gap 1:
 * [VehicleController.isDue] had no odometer-unset guard on its own mileage-axis arithmetic, even
 * though the render path ([com.kevin.legion.ui.fleet.chooseDueAxis]) already refused the miles axis
 * in the same situation - so a row could read `OVERDUE` (sorted by [VehicleController.isDue]) and
 * "odometer not set" (rendered by [com.kevin.legion.ui.fleet.chooseDueAxis]) at the same time.
 *
 * No Robolectric needed - [VehicleController.isDue] is pure, `internal`, and takes no Context/DB
 * (same posture as [VehicleControllerOdometerLabelTest]).
 */
class VehicleControllerIsDueTest {

    private val vehicleId = "test-mac"
    private val now = 1_700_000_000_000L

    @Test
    fun `a mileage-only item reads due when the odometer is confirmed and the interval has elapsed`() {
        val item = MaintenanceItem(vehicleId = vehicleId, serviceName = "Oil Change", intervalMiles = 5000, lastDoneMileage = 0)
        assertTrue(VehicleController.isDue(item, currentMileage = 6000, odometerUnset = false, now = now))
    }

    @Test
    fun `the same item reads NOT due when the odometer is unset, even though the raw arithmetic alone would cross the interval`() {
        // The exact case that fooled ticket 09's own review: odometerBaseline == 0 (odometerUnset =
        // true) with tripMilesSinceBaseline > 0, so currentMileage reads positive
        // (VehicleController.currentMileage = odometerBaseline + tripMilesSinceBaseline) while the
        // driver has never confirmed an odometer reading at all. Before this fix, isDue computed
        // `6000 - 0 >= 5000` = true regardless, and buildDueRows/buildScheduleRows/dueItems would
        // have sorted this into OVERDUE off an odometer nobody set.
        val item = MaintenanceItem(vehicleId = vehicleId, serviceName = "Oil Change", intervalMiles = 5000, lastDoneMileage = 0)
        assertFalse(VehicleController.isDue(item, currentMileage = 6000, odometerUnset = true, now = now))
    }

    @Test
    fun `the guard is scoped to the mileage axis only - a time axis on the same item still fires while the odometer is unset`() {
        val item = MaintenanceItem(
            vehicleId = vehicleId, serviceName = "Cabin Air Filter",
            intervalMiles = 5000, lastDoneMileage = 0,
            intervalMonths = 6, lastDoneDate = now - 7L * 30 * 24 * 60 * 60 * 1000,
        )
        assertTrue(
            "the time axis is unaffected by odometerUnset - only the miles figure is untrustworthy while the odometer is unconfirmed",
            VehicleController.isDue(item, currentMileage = 6000, odometerUnset = true, now = now),
        )
    }

    @Test
    fun `neverDone is always due regardless of the odometer guard - a known fact, not a computed axis`() {
        val item = MaintenanceItem(vehicleId = vehicleId, serviceName = "Tire Rotation", neverDone = true)
        assertTrue(VehicleController.isDue(item, currentMileage = 0, odometerUnset = true, now = now))
    }

    @Test
    fun `an unanchored item (no lastDone at all, not neverDone) is UNKNOWN, never due, odometer state notwithstanding`() {
        val item = MaintenanceItem(vehicleId = vehicleId, serviceName = "Coolant Flush", intervalMiles = 30_000)
        assertFalse(VehicleController.isDue(item, currentMileage = 100_000, odometerUnset = false, now = now))
        assertFalse(VehicleController.isDue(item, currentMileage = 100_000, odometerUnset = true, now = now))
    }

    @Test
    fun `computeNextService's candidate filter also routes through the guard - a dual-axis item stays a byTime candidate while its unconfirmed mileage axis alone would have flagged it due`() {
        // A dual-axis item whose MILEAGE arithmetic alone crosses the interval (6,000 - 0 >= 5,000)
        // while its TIME axis is nowhere close (1 month elapsed against a 24-month interval), with
        // odometerBaseline == 0 (odometerUnset). Before this fix, isDue's unguarded mileageDue check
        // alone made the item read "due" and computeNextService.candidates dropped it entirely - so
        // its perfectly healthy time axis never reached the byTime ranking loop either, collapsing
        // NextService into allDue = true (the "everything anchored is already due" state) when
        // nothing of the sort was true. With the guard, mileageDue is suppressed, isDue reads false,
        // the item stays a candidate, and its time axis (unaffected by odometerUnset) populates
        // byTime normally.
        val monthMs = 30L * 24 * 60 * 60 * 1000
        val item = MaintenanceItem(
            vehicleId = vehicleId, serviceName = "Timing Belt",
            intervalMiles = 5000, lastDoneMileage = 0,
            intervalMonths = 24, lastDoneDate = now - monthMs,
        )
        val next = VehicleController.computeNextService(
            items = listOf(item), currentMileage = 6000, now = now, odometerBaseline = 0,
        )
        assertEquals(true, next?.odometerUnset)
        assertEquals(null, next?.byMiles) // the miles axis itself stays refused, per computeNextService's own odometerUnset gate
        assertEquals("Timing Belt", next?.byTime?.serviceName) // but the time axis survives - the item was never wrongly marked "due"
        assertEquals(false, next?.allDue)
    }
}

package com.kevin.legion.vehicle

import com.kevin.legion.advisor.digest.FleetDigestBuilder
import com.kevin.legion.data.local.MaintenanceItem
import com.kevin.legion.data.local.ServiceRecord
import com.kevin.legion.data.local.Vehicle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The three blockers of the RED review on PR #5, each stated against behaviour the code ALREADY
 * had an API for - so this file compiles and FAILS on the unfixed source, and a revert proof reads
 * as "these tests fail" rather than as a compile error. The tests that need the new seams
 * ([VehicleController.NextService.needsMileage], the edit form's nullable mileage, the reply sets)
 * live beside the fixes, in [VehicleControllerNeedsMileageTest],
 * [MileagelessRecordReadersTest] and [ServiceRecordNullMileageEditTest].
 *
 * What the review found, in one sentence each:
 *
 *  1. A service logged with NO mileage (the car plan's slice 0) takes a miles-only item off the
 *     schedule forever AND flips the whole schedule to "everything is already due".
 *  2. Four readers turn that null mileage into a stated `0 mi` - one spoken, one into a model's own
 *     prompt, one on a line tagged `TrustTier.REPORTED`.
 *  3. (phone half) The anchor must come off the newest row, which it already does here - the
 *     engine's half is in `server/tests/test_maintenance_mileageless.py` and the cross-side
 *     agreement in [FleetAnchorParityTest].
 */
class MileagelessServiceRegressionTest {

    private val vehicleId = "test-mac"
    private val now = 1_764_000_000_000L
    private val monthMs = 30L * 24 * 60 * 60 * 1000L
    private val yesterday = now - 86_400_000L

    private fun vehicle() = Vehicle(
        obdMac = vehicleId, name = "", make = "Honda", model = "Civic", year = 2018,
        personaPrompt = "", odometerBaseline = 100_000,
    )

    private fun record(id: Long, mileage: Int?, date: Long) =
        ServiceRecord(id = id, vehicleId = vehicleId, serviceName = "Oil Change", mileage = mileage, date = date)

    // --- blocker 1 ------------------------------------------------------------------------------

    @Test
    fun `one item waiting on a mileage reading never means everything is already due`() {
        // "I got my oil changed today" on an item whose only interval is a mileage one: the date
        // anchor moves, the mileage anchor is unknown, and isDue can then never fire on either
        // axis. isUnknown is false too (the DATE is known), so the item counted on no axis and as
        // no unknown - and `allDue` is derived from exactly that absence.
        val milesOnly = MaintenanceItem(
            vehicleId = vehicleId, serviceName = "Oil Change",
            intervalMiles = 5_000, intervalMonths = null,
            lastDoneMileage = null, lastDoneDate = yesterday,
        )

        val next = VehicleController.computeNextService(
            items = listOf(milesOnly), currentMileage = 42_300, now = now, odometerBaseline = 42_300,
        )

        assertEquals(
            "the one item we know we cannot time is not evidence that everything is due",
            false,
            next?.allDue,
        )
    }

    // --- blocker 2 ------------------------------------------------------------------------------

    @Test
    fun `the digest never states a 0 mi reading for a record that carries none`() {
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

        assertFalse("LAST SERVICE must not name a figure the row does not hold: $text", text.contains("at 0 mi"))
        assertFalse(
            "and the odometer trend must not average against a zero nobody recorded: $text",
            text.contains("-100,000 mi"),
        )
    }

    @Test
    fun `a digest bucket whose only service states no mileage reads not logged, never zero`() {
        val text = FleetDigestBuilder.buildDigestText(
            vehicle = vehicle(),
            currentMileage = 106_000,
            items = emptyList(),
            unknownNames = emptyList(),
            nextService = null,
            codeEvents = emptyList(),
            recentServices = listOf(record(id = 1, mileage = null, date = now - monthMs / 2)),
            now = now,
        )

        assertTrue("this function's own doc: there is no zero on an odometer that has moved: $text", text.contains("current not logged"))
    }

    @Test
    fun `the maintenance prompt states no figure for a record with no reading`() {
        val item = MaintenanceItem(
            vehicleId = vehicleId, serviceName = "Oil Change",
            intervalSource = "CONFIRMED", intervalMiles = 5_000,
            lastDoneMileage = null, lastDoneDate = null,
        )

        val line = MaintenanceAgent.describeItem(item, record(id = 1, mileage = null, date = now))

        assertFalse("a stated 0 mi is a reading this row does not hold: $line", line.contains("at 0 mi"))
        assertFalse(
            "and coalescing to 0 failed the drift check, speaking a disagreement that does not exist: $line",
            line.contains("the maintenance clock was later set to"),
        )
    }
}

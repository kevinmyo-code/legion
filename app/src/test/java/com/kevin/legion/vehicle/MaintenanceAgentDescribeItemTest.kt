package com.kevin.legion.vehicle

import com.kevin.legion.data.local.MaintenanceItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-function regression for [MaintenanceAgent.describeItem] - THE live formatter that pre-seeds
 * [MaintenanceAgent.answer]'s prompt (mission-control ticket 16,
 * `.scratch/fleet-maintenance/issues/16-ticket-06-audited-a-dead-surface-and-missed-a-live-one.md`).
 * Ticket 06 required a seeded-interval disclosure on every model-facing surface and instead audited
 * the dead `CarToolbelt.maintenanceSchedule` (zero callers, since deleted); this is the regression
 * against the function that was actually missed.
 *
 * `internal` on [MaintenanceAgent.describeItem] (widened from `private` for exactly this test - see
 * its own doc comment) means no Context/Room/network is needed here, same posture as
 * [VehicleControllerIsDueTest]'s direct calls into [VehicleController]'s own internal pure
 * functions.
 */
class MaintenanceAgentDescribeItemTest {

    private val vehicleId = "test-mac"

    @Test
    fun `a SEEDED interval carries LEGION's-guess wording, in full words, in the interval clause`() {
        val item = MaintenanceItem(
            vehicleId = vehicleId, serviceName = "Oil Change",
            intervalSource = "SEEDED", intervalMiles = 7500, intervalMonths = 6,
            lastDoneMileage = 100_000,
        )
        val line = MaintenanceAgent.describeItem(item)
        assertTrue(
            "expected the guess wording after the interval clause, got: $line",
            line.contains("every 7,500 mi / every 6 mo (LEGION's guess, unconfirmed by the driver)"),
        )
    }

    @Test
    fun `a LOOKUP interval carries the distinct factory-lookup wording, not the SEEDED wording`() {
        val item = MaintenanceItem(
            vehicleId = vehicleId, serviceName = "Tire Rotation",
            intervalSource = "LOOKUP", intervalMiles = 7500,
            lastDoneMileage = 50_000,
        )
        val line = MaintenanceAgent.describeItem(item)
        assertTrue(
            "expected the factory-lookup wording, got: $line",
            line.contains("every 7,500 mi (from a factory lookup, unconfirmed by the driver)"),
        )
        assertFalse("must not also carry the SEEDED wording", line.contains("LEGION's guess"))
    }

    @Test
    fun `a CONFIRMED interval carries no suffix at all - the driver stated it`() {
        val item = MaintenanceItem(
            vehicleId = vehicleId, serviceName = "Coolant Flush",
            intervalSource = "CONFIRMED", intervalMiles = 30_000,
            lastDoneMileage = 10_000,
        )
        val line = MaintenanceAgent.describeItem(item)
        assertEquals("Coolant Flush: every 30,000 mi; last done at 10,000 mi", line)
    }

    @Test
    fun `an item with no interval on either axis gets no suffix either, regardless of intervalSource`() {
        // SEEDED by column default, nothing to doubt - intervalIsUnconfirmed's own second clause,
        // the exact orphan VehicleController.logServiceDirect creates for a hand-logged service with
        // no schedule yet.
        val item = MaintenanceItem(vehicleId = vehicleId, serviceName = "Brake Fluid", intervalSource = "SEEDED")
        val line = MaintenanceAgent.describeItem(item)
        assertEquals("Brake Fluid: no interval on file; last done UNKNOWN", line)
    }
}

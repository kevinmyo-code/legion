package com.kevin.legion.vehicle

import com.kevin.legion.data.local.MaintenanceItem
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-function regression for [VehicleController.ServiceCandidate.isGuess] being populated off
 * [MaintenanceItem.intervalIsUnconfirmed] inside [VehicleController.computeNextService] (mission-
 * control ticket 16, `.scratch/fleet-maintenance/issues/16-ticket-06-audited-a-dead-surface-and-
 * missed-a-live-one.md`, "the flag travels WITH the candidate"). Same posture as
 * [VehicleControllerIsDueTest] - no Context/Room, direct calls into an `internal` pure function.
 */
class VehicleControllerServiceCandidateIsGuessTest {

    private val vehicleId = "test-mac"
    private val now = 1_700_000_000_000L
    private val monthMs = 30L * 24 * 60 * 60 * 1000

    @Test
    fun `a SEEDED candidate's isGuess is true on both axes it leads`() {
        val item = MaintenanceItem(
            vehicleId = vehicleId, serviceName = "Oil Change", intervalSource = "SEEDED",
            intervalMiles = 5000, lastDoneMileage = 0,
            intervalMonths = 6, lastDoneDate = now - monthMs,
        )
        val next = VehicleController.computeNextService(items = listOf(item), currentMileage = 1000, now = now, odometerBaseline = 1000)
        assertEquals(true, next?.byMiles?.isGuess)
        assertEquals(true, next?.byTime?.isGuess)
    }

    @Test
    fun `a LOOKUP candidate's isGuess is true too - ticket 18 widened the rule past just SEEDED`() {
        val item = MaintenanceItem(
            vehicleId = vehicleId, serviceName = "Tire Rotation", intervalSource = "LOOKUP",
            intervalMiles = 7500, lastDoneMileage = 0,
        )
        val next = VehicleController.computeNextService(items = listOf(item), currentMileage = 1000, now = now, odometerBaseline = 1000)
        assertEquals(true, next?.byMiles?.isGuess)
    }

    @Test
    fun `a CONFIRMED candidate's isGuess is false - the driver stated this interval`() {
        val item = MaintenanceItem(
            vehicleId = vehicleId, serviceName = "Coolant Flush", intervalSource = "CONFIRMED",
            intervalMiles = 30_000, lastDoneMileage = 0,
        )
        val next = VehicleController.computeNextService(items = listOf(item), currentMileage = 1000, now = now, odometerBaseline = 1000)
        assertEquals(false, next?.byMiles?.isGuess)
    }

    @Test
    fun `two different items leading different axes each carry their OWN isGuess independently`() {
        val guessedMilesLeader = MaintenanceItem(
            vehicleId = vehicleId, serviceName = "Oil Change", intervalSource = "SEEDED",
            intervalMiles = 5000, lastDoneMileage = 0,
        )
        val confirmedTimeLeader = MaintenanceItem(
            vehicleId = vehicleId, serviceName = "Wiper Blades", intervalSource = "CONFIRMED",
            intervalMonths = 6, lastDoneDate = now - monthMs,
        )
        val next = VehicleController.computeNextService(
            items = listOf(guessedMilesLeader, confirmedTimeLeader), currentMileage = 1000, now = now, odometerBaseline = 1000,
        )
        assertEquals("Oil Change", next?.byMiles?.serviceName)
        assertEquals(true, next?.byMiles?.isGuess)
        assertEquals("Wiper Blades", next?.byTime?.serviceName)
        assertEquals(false, next?.byTime?.isGuess)
    }
}

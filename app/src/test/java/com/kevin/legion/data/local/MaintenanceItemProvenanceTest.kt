package com.kevin.legion.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-function regression for [MaintenanceItem.intervalIsUnconfirmed]/[MaintenanceItem.provenanceWords]/
 * [provenanceWordsForSource] on their new home (mission-control ticket 16,
 * `.scratch/fleet-maintenance/issues/16-ticket-06-audited-a-dead-surface-and-missed-a-live-one.md`).
 * These were moved off `ui/fleet/FleetRows.kt`'s `isGuessTag`/`provenanceWords`/`provenanceWordsForSource`
 * onto the entity so `vehicle/` and `advisor/` can use them without importing `ui.fleet` - the rule
 * itself is unchanged, only its address. [FleetRowsTest][com.kevin.legion.ui.fleet.FleetRowsTest]
 * still exercises the SAME rule through those now-delegating functions; this file is the direct
 * regression against the entity property itself, no Context/Room needed - a plain JVM test.
 */
class MaintenanceItemProvenanceTest {

    private val vehicleId = "test-mac"

    @Test
    fun `intervalIsUnconfirmed - all three intervalSource values, plus the no-interval case`() {
        val seededWithInterval = MaintenanceItem(vehicleId = vehicleId, serviceName = "Oil Change", intervalSource = "SEEDED", intervalMiles = 5000)
        val lookupWithInterval = MaintenanceItem(vehicleId = vehicleId, serviceName = "Oil Change", intervalSource = "LOOKUP", intervalMiles = 5000)
        val confirmedWithInterval = MaintenanceItem(vehicleId = vehicleId, serviceName = "Oil Change", intervalSource = "CONFIRMED", intervalMiles = 5000)
        // SEEDED by column default (never touched), no interval on either axis - the "nothing to
        // doubt" orphan VehicleController.logServiceDirect creates (a Brake Fluid/Brake Pads row
        // with no interval at all).
        val seededNoInterval = MaintenanceItem(vehicleId = vehicleId, serviceName = "Brake Fluid")

        assertTrue("SEEDED with an interval is unconfirmed", seededWithInterval.intervalIsUnconfirmed)
        assertTrue("LOOKUP with an interval is unconfirmed - ticket 18's whole point", lookupWithInterval.intervalIsUnconfirmed)
        assertFalse("CONFIRMED is never unconfirmed regardless of interval", confirmedWithInterval.intervalIsUnconfirmed)
        assertFalse("no interval on either axis - nothing to doubt, even though intervalSource defaults to SEEDED", seededNoInterval.intervalIsUnconfirmed)
    }

    @Test
    fun `intervalIsUnconfirmed - a months-only interval is still enough to qualify, mileage need not be present`() {
        val monthsOnly = MaintenanceItem(vehicleId = vehicleId, serviceName = "Wiper Blades", intervalSource = "SEEDED", intervalMonths = 6)
        assertTrue(monthsOnly.intervalIsUnconfirmed)
    }

    @Test
    fun `provenanceWords names each provenance in words, CONFIRMED has nothing to disclose`() {
        assertEquals("LEGION's guess", MaintenanceItem(vehicleId = vehicleId, serviceName = "x", intervalSource = "SEEDED").provenanceWords)
        assertEquals("from a factory lookup", MaintenanceItem(vehicleId = vehicleId, serviceName = "x", intervalSource = "LOOKUP").provenanceWords)
        assertNull(MaintenanceItem(vehicleId = vehicleId, serviceName = "x", intervalSource = "CONFIRMED").provenanceWords)
    }

    @Test
    fun `provenanceWordsForSource - the raw-string entry point PopulateDrilldown's row types actually call`() {
        assertEquals("LEGION's guess", provenanceWordsForSource("SEEDED"))
        assertEquals("from a factory lookup", provenanceWordsForSource("LOOKUP"))
        assertNull(provenanceWordsForSource("CONFIRMED"))
        assertNull("an unrecognized future value defaults to null (no disclosure), never a crash", provenanceWordsForSource("SOMETHING_FUTURE"))
    }
}

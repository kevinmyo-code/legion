package com.kevin.legion.vehicle

import com.kevin.legion.data.local.Vehicle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-function regression for ticket 12
 * (`.scratch/fleet-maintenance/issues/12-a-recall-button.md`): [identityPresent] /
 * [missingIdentityFields], the gate that replaced [Vehicle.confirmed] on both recall paths (the
 * on-request `check_recalls` voice tool and [VehicleSpecController.recalls], which the proactive
 * startup push reads too).
 *
 * No Robolectric needed - both functions under test are pure and take no Context/DB, same posture
 * as `VehicleControllerOdometerLabelTest` (CLAUDE.md §11's testing convention). Lives in
 * `RecallCheckResultTest`'s sibling spot (same file the functions themselves live in,
 * `RecallCheckResult.kt`), not `VehicleControllerXTest`, because the functions are top-level here,
 * not on [VehicleController] - see that file's doc for why.
 */
class RecallGateTest {

    private fun vehicle(year: Int, make: String, model: String, confirmed: Boolean = false) = Vehicle(
        obdMac = "AA:BB:CC:DD:EE:FF",
        name = "Test Car",
        make = make,
        model = model,
        year = year,
        personaPrompt = "",
        confirmed = confirmed,
    )

    @Test
    fun `identityPresent is true once year, make and model are all set`() {
        val v = vehicle(year = 1998, make = "Jeep", model = "Cherokee")
        assertTrue(identityPresent(v))
        assertEquals(emptyList<String>(), missingIdentityFields(v))
    }

    @Test
    fun `identityPresent does NOT require confirmed - the whole point of ticket 12`() {
        // A car identified purely from its own VIN decode (ticket 04's write-back) deliberately
        // has confirmed = false - a decode filling in blanks must not claim the driver's
        // confirmation on their behalf. It must still pass this gate.
        val v = vehicle(year = 1998, make = "Jeep", model = "Cherokee", confirmed = false)
        assertTrue(identityPresent(v))
    }

    @Test
    fun `confirmed alone, with a blank identity, does NOT pass - the premise that expired`() {
        // a09aa68 blanked the default seed for every id; a row that is confirmed=true but has
        // never actually had its identity filled in must still refuse. This is the exact
        // collapse ticket 12 exists to close.
        val v = vehicle(year = 0, make = "", model = "", confirmed = true)
        assertFalse(identityPresent(v))
    }

    @Test
    fun `the default seed (blank, unconfirmed) fails the gate`() {
        val v = vehicle(year = 0, make = "", model = "", confirmed = false)
        assertFalse(identityPresent(v))
        assertEquals(listOf("year", "make", "model"), missingIdentityFields(v))
    }

    @Test
    fun `missingIdentityFields names only the absent ones, in year-make-model order`() {
        assertEquals(listOf("make"), missingIdentityFields(vehicle(year = 1998, make = "", model = "Cherokee")))
        assertEquals(listOf("model"), missingIdentityFields(vehicle(year = 1998, make = "Jeep", model = "")))
        assertEquals(listOf("year"), missingIdentityFields(vehicle(year = 0, make = "Jeep", model = "Cherokee")))
        assertEquals(listOf("year", "model"), missingIdentityFields(vehicle(year = 0, make = "Jeep", model = "")))
    }

    @Test
    fun `a zero or negative year is treated as absent, not as a real model year`() {
        assertFalse(identityPresent(vehicle(year = -1, make = "Jeep", model = "Cherokee")))
    }
}

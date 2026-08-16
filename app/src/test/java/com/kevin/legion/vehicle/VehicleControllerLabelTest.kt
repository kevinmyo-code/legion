package com.kevin.legion.vehicle

import com.kevin.legion.data.local.Vehicle
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-function regression for ticket 04's label rule
 * (`.scratch/fleet-maintenance/issues/04-one-car-label-rule.md`): [VehicleController.label] and
 * its `spec` building block [VehicleController.identitySpec].
 *
 * No Robolectric needed - both functions are pure and take no Context/DB (same posture as
 * [VehicleControllerOdometerLabelTest]). [com.kevin.legion.ui.fleet.CarRowsTest] covers the
 * two-line narrow-width variant ([com.kevin.legion.ui.fleet.CarRows.carLabel] /
 * [com.kevin.legion.ui.fleet.CarRows.carSpecPrefix]) built on the same [identitySpec]; this file
 * covers the single-string rule every other surface (screens with room, and every spoken reply)
 * renders through.
 */
class VehicleControllerLabelTest {

    private fun vehicle(
        name: String = "",
        make: String = "",
        model: String = "",
        year: Int = 0,
        trim: String = "",
    ) = Vehicle(
        obdMac = "AA:BB:CC:DD:EE:FF", name = name, make = make, model = model, year = year,
        trim = trim, personaPrompt = "",
    )

    @Test
    fun `nickname blank, spec blank - the one last-resort string`() {
        assertEquals("a car you haven't named yet", VehicleController.label(vehicle()))
    }

    @Test
    fun `nickname blank, spec present - the spec alone`() {
        val v = vehicle(make = "Mitsubishi", model = "Outlander", year = 2020)
        assertEquals("2020 Mitsubishi Outlander", VehicleController.label(v))
    }

    @Test
    fun `spec blank, nickname present - the nickname alone`() {
        assertEquals("the truck", VehicleController.label(vehicle(name = "the truck")))
    }

    @Test
    fun `spec contains nickname - the spec wins, no duplication`() {
        // Kevin's real row (ticket 04's Answer section): name = "1998 Jeep Cherokee", spec (year
        // make model, NOT trim) = "1998 Jeep Cherokee" too - the bare rule without this clause
        // would render "1998 Jeep Cherokee (1998 Jeep Cherokee)".
        val v = vehicle(name = "1998 Jeep Cherokee", make = "Jeep", model = "Cherokee", year = 1998)
        assertEquals("1998 Jeep Cherokee", VehicleController.label(v))
    }

    @Test
    fun `spec contains nickname case-insensitively - still de-duplicates`() {
        val v = vehicle(name = "outlander", make = "Mitsubishi", model = "Outlander", year = 2020)
        assertEquals("2020 Mitsubishi Outlander", VehicleController.label(v))
    }

    @Test
    fun `distinct nickname and spec - both, nickname first`() {
        val v = vehicle(name = "the truck", make = "Mitsubishi", model = "Outlander", year = 2020)
        assertEquals("the truck (2020 Mitsubishi Outlander)", VehicleController.label(v))
    }

    @Test
    fun `trim is excluded from the spec - it is what makes the de-duplication clause fire`() {
        // The de-duplication clause's whole reason to exist: displayLabel (WITH trim) on this exact
        // row is "1998 Jeep Cherokee Limited", which does NOT contain the name "1998 Jeep Cherokee" -
        // so a trim-inclusive spec would render "1998 Jeep Cherokee (1998 Jeep Cherokee Limited)"
        // instead of collapsing to one string.
        val v = vehicle(name = "1998 Jeep Cherokee", make = "Jeep", model = "Cherokee", year = 1998, trim = "Limited")
        assertEquals("1998 Jeep Cherokee", VehicleController.label(v))
        assertEquals("1998 Jeep Cherokee", VehicleController.identitySpec(v))
        assertEquals("1998 Jeep Cherokee Limited", VehicleController.displayLabel(v))
    }

    @Test
    fun `identitySpec omits trim, displayLabel keeps it - same inputs, different outputs`() {
        val v = vehicle(make = "BMW", model = "330i", year = 2003, trim = "ZHP")
        assertEquals("2003 BMW 330i", VehicleController.identitySpec(v))
        assertEquals("2003 BMW 330i ZHP", VehicleController.displayLabel(v))
    }

    @Test
    fun `a name that is only whitespace counts as blank, same as empty`() {
        assertEquals("a car you haven't named yet", VehicleController.label(vehicle(name = "   ")))
    }

    @Test
    fun `label never uppercases or otherwise transforms a driver-typed nickname`() {
        // Ticket 04's casing rule: uppercasing is a chrome concern, never applied to data. label()
        // itself must not do it either - callers rely on this to safely wrap it in UI without a
        // second transform reintroducing the DrivingModeScreen-shaped bug.
        val v = vehicle(name = "the Wagon")
        assertEquals("the Wagon", VehicleController.label(v))
    }
}

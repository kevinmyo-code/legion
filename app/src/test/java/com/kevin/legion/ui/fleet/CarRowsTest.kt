package com.kevin.legion.ui.fleet

import com.kevin.legion.data.local.Vehicle
import com.kevin.legion.util.shortDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic coverage for the CARS roster ([buildCarRows] / [carLabel] /
 * [telemetrySub]). No Room, no Android - plain JVM, same posture as
 * [FleetRowsTest].
 *
 * The cases here are the ones the 2026-08-04 import actually produced: a
 * re-keyed imported car with thousands of samples, a blank `default`
 * placeholder seeded by [com.kevin.legion.vehicle.VehicleController], and an
 * archived car that must stay hidden until asked for.
 */
class CarRowsTest {
    private fun vehicle(
        id: String,
        name: String = "",
        make: String = "",
        model: String = "",
        year: Int = 0,
        archived: Boolean = false,
    ) = Vehicle(
        obdMac = id, name = name, make = make, model = model, year = year,
        personaPrompt = "", archived = archived,
    )

    private val outlander = vehicle(
        "imported-mitsubishi-outlander-2020",
        name = "Outlander", make = "Mitsubishi", model = "Outlander", year = 2020,
    )
    private val placeholder = vehicle("default")
    private val mazda = vehicle("car:0d1f", name = "Mazda", make = "Mazda", model = "3", year = 2014, archived = true)

    private val telemetry = mapOf(
        "imported-mitsubishi-outlander-2020" to CarTelemetry(5_242, 1_753_000_000_000L),
        "default" to CarTelemetry(0, null),
    )

    @Test
    fun `a fully specified car is labelled from its own facts`() {
        assertEquals("2020 Mitsubishi Outlander", carLabel(outlander))
    }

    @Test
    fun `a driver's own nickname wins over the spec, because RENAME has to visibly do something`() {
        // Kevin, 2026-08-13. Before this, carLabel preferred the computed spec unconditionally, so
        // renaming a car that had a make and model changed nothing on screen - invisible while
        // renaming was voice-only, a button that does nothing once the UI offered RENAME.
        val nicknamed = vehicle(
            "imported-mitsubishi-outlander-2020",
            name = "the truck", make = "Mitsubishi", model = "Outlander", year = 2020,
        )
        assertEquals("the truck", carLabel(nicknamed))
    }

    @Test
    fun `a name the spec already contains does not downgrade the label`() {
        // Import sets `name` from the model, so the Outlander arrives already called "Outlander".
        // Preferring it would lose the year and the make for no gain.
        assertEquals("2020 Mitsubishi Outlander", carLabel(outlander))
        assertEquals("", carSpecPrefix(outlander))
    }

    @Test
    fun `a nicknamed car keeps its spec on the sub-line rather than losing it`() {
        val nicknamed = vehicle(
            "imported-mitsubishi-outlander-2020",
            name = "the truck", make = "Mitsubishi", model = "Outlander", year = 2020,
        )
        assertEquals("2020 Mitsubishi Outlander", carSpecPrefix(nicknamed))
    }

    @Test
    fun `a placeholder with nothing at all gets the one last-resort string, never an invented make or model`() {
        // Ticket 04's label rule (`.scratch/fleet-maintenance/issues/04-one-car-label-rule.md`):
        // seedVehicle no longer writes the "this car" sentinel, and a raw id is no longer the
        // fallback either - both nickname and spec blank means the ONE last-resort string.
        assertEquals("a car you haven't named yet", carLabel(placeholder))
    }

    @Test
    fun `nickname blank, spec present - the spec is the label`() {
        assertEquals(
            "2020 Mitsubishi Outlander",
            carLabel(vehicle("AA:BB:CC", make = "Mitsubishi", model = "Outlander", year = 2020)),
        )
    }

    @Test
    fun `spec blank, nickname present - the nickname is the label`() {
        assertEquals("the truck", carLabel(vehicle("AA:BB:CC", name = "the truck")))
    }

    @Test
    fun `carLabel excludes trim - a nickname matching the untrimmed spec still de-duplicates`() {
        // Kevin's real row (ticket 04's Answer section): name = "1998 Jeep Cherokee",
        // full displayLabel (WITH trim) = "1998 Jeep Cherokee Limited" - which does NOT contain the
        // name, so a trim-inclusive spec would render "1998 Jeep Cherokee (1998 Jeep Cherokee
        // Limited)" instead of de-duplicating. carLabel/carSpecPrefix use
        // VehicleController.identitySpec (no trim) precisely so this still collapses to one line.
        val jeep = Vehicle(
            obdMac = "REAL:MAC", name = "1998 Jeep Cherokee", make = "Jeep", model = "Cherokee",
            year = 1998, trim = "Limited", personaPrompt = "",
        )
        assertEquals("1998 Jeep Cherokee", carLabel(jeep))
        assertEquals("", carSpecPrefix(jeep))
    }

    @Test
    fun `telemetry subtitle states the count and the last reading`() {
        // The date half is deliberately built with the same formatter rather
        // than hardcoded: shortDate renders in the DEVICE's zone, so a literal
        // would pass here and fail on a machine far enough west.
        val lastMs = 1_753_000_000_000L
        assertEquals(
            "5,242 readings - last ${shortDate(lastMs)}",
            telemetrySub(CarTelemetry(5_242, lastMs)),
        )
    }

    @Test
    fun `a count with no timestamp still reports the count`() {
        assertEquals("812 readings", telemetrySub(CarTelemetry(812, null)))
    }

    @Test
    fun `a car with no telemetry says so plainly, and a missing entry is the same as zero`() {
        assertEquals("no telemetry recorded", telemetrySub(CarTelemetry(0, null)))
        assertEquals("no telemetry recorded", telemetrySub(null))
    }

    @Test
    fun `the active car sorts first`() {
        val rows = buildCarRows(
            vehicles = listOf(placeholder, outlander),
            telemetry = telemetry,
            selectedId = outlander.obdMac,
            resolvedId = outlander.obdMac,
            showArchived = false,
        )
        assertEquals(listOf(outlander.obdMac, placeholder.obdMac), rows.map { it.vehicleId })
        assertTrue(rows[0].active)
        assertTrue(rows[0].explicit)
    }

    @Test
    fun `an active car nobody picked is active but not explicit - it is the adapter's`() {
        val rows = buildCarRows(
            vehicles = listOf(outlander, placeholder),
            telemetry = telemetry,
            selectedId = null,
            resolvedId = placeholder.obdMac,
            showArchived = false,
        )
        val active = rows.first { it.active }
        assertEquals(placeholder.obdMac, active.vehicleId)
        assertFalse(active.explicit)
    }

    @Test
    fun `archived cars are hidden until asked for, and then sort last`() {
        val hidden = buildCarRows(
            vehicles = listOf(outlander, mazda),
            telemetry = telemetry,
            selectedId = null,
            resolvedId = outlander.obdMac,
            showArchived = false,
        )
        assertEquals(listOf(outlander.obdMac), hidden.map { it.vehicleId })

        val shown = buildCarRows(
            vehicles = listOf(mazda, outlander),
            telemetry = telemetry,
            selectedId = null,
            resolvedId = outlander.obdMac,
            showArchived = true,
        )
        assertEquals(listOf(outlander.obdMac, mazda.obdMac), shown.map { it.vehicleId })
        assertTrue(shown.last().archived)
    }

    @Test
    fun `inactive cars sort by label, case-insensitively`() {
        val zed = vehicle("z", name = "aardvark")
        val alpha = vehicle("a", name = "Zebra")
        val rows = buildCarRows(
            vehicles = listOf(alpha, zed),
            telemetry = emptyMap(),
            selectedId = null,
            resolvedId = "nothing-resolves-here",
            showArchived = false,
        )
        assertEquals(listOf("z", "a"), rows.map { it.vehicleId })
    }
}

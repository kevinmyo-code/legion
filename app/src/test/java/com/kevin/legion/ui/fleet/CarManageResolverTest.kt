package com.kevin.legion.ui.fleet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic coverage for [CarManageResolver] - no Room, no Android, plain JVM, same posture as
 * [CarRowsTest]. Mirrors [com.kevin.legion.vehicle.VehicleController.addVehicle]'s and
 * [com.kevin.legion.vehicle.VehicleController.correctVehicle]'s own preconditions rather than
 * inventing new ones (CLAUDE.md L10/L11 in spirit: a validator that drifts from the controller it
 * gates is worse than no validator).
 */
class CarManageResolverTest {

    // --- validateAddCar -------------------------------------------------

    @Test
    fun `add car - blank make is refused`() {
        val v = CarManageResolver.validateAddCar(make = "", model = "F-150", yearText = "", name = "", existingLabels = emptyList())
        assertEquals("Make is required.", v.error)
    }

    @Test
    fun `add car - whitespace-only make is refused`() {
        val v = CarManageResolver.validateAddCar(make = "   ", model = "F-150", yearText = "", name = "", existingLabels = emptyList())
        assertEquals("Make is required.", v.error)
    }

    @Test
    fun `add car - blank model is refused`() {
        val v = CarManageResolver.validateAddCar(make = "Ford", model = "", yearText = "", name = "", existingLabels = emptyList())
        assertEquals("Model is required.", v.error)
    }

    @Test
    fun `add car - non-numeric year is refused`() {
        val v = CarManageResolver.validateAddCar(make = "Ford", model = "F-150", yearText = "202O", name = "", existingLabels = emptyList())
        assertEquals("Year must be a whole number, e.g. 2020.", v.error)
    }

    @Test
    fun `add car - blank year is allowed`() {
        val v = CarManageResolver.validateAddCar(make = "Ford", model = "F-150", yearText = "", name = "", existingLabels = emptyList())
        assertTrue(v.isValid)
        assertNull(v.error)
    }

    @Test
    fun `add car - make, model and a valid year is valid`() {
        val v = CarManageResolver.validateAddCar(make = "Ford", model = "F-150", yearText = "2017", name = "", existingLabels = emptyList())
        assertTrue(v.isValid)
    }

    @Test
    fun `add car - a name matching an existing car label is refused, case-insensitively`() {
        val v = CarManageResolver.validateAddCar(
            make = "Ford", model = "F-150", yearText = "", name = "the truck",
            existingLabels = listOf("The Truck"),
        )
        assertEquals("You've already got a car named \"the truck\".", v.error)
    }

    @Test
    fun `add car - blank name never collides`() {
        val v = CarManageResolver.validateAddCar(
            make = "Ford", model = "F-150", yearText = "", name = "  ",
            existingLabels = listOf("The Truck"),
        )
        assertTrue(v.isValid)
    }

    // --- parseYear -------------------------------------------------

    @Test
    fun `parseYear - blank is null, never a silent zero`() {
        assertNull(CarManageResolver.parseYear(""))
        assertNull(CarManageResolver.parseYear("   "))
    }

    @Test
    fun `parseYear - a real year round-trips`() {
        assertEquals(2020, CarManageResolver.parseYear("2020"))
        assertEquals(2020, CarManageResolver.parseYear(" 2020 "))
    }

    @Test
    fun `parseYear - garbage is null, not a partial parse`() {
        assertNull(CarManageResolver.parseYear("202O"))
    }

    // --- validateRename -------------------------------------------------

    @Test
    fun `rename - blank is refused`() {
        val v = CarManageResolver.validateRename("", otherLabels = emptyList())
        assertEquals("Name can't be blank.", v.error)
    }

    @Test
    fun `rename - whitespace-only is refused`() {
        val v = CarManageResolver.validateRename("   ", otherLabels = emptyList())
        assertEquals("Name can't be blank.", v.error)
    }

    @Test
    fun `rename - a name matching another car is refused, case-insensitively`() {
        val v = CarManageResolver.validateRename("outlander", otherLabels = listOf("Outlander"))
        assertEquals("Another car is already named \"outlander\".", v.error)
    }

    @Test
    fun `rename - matching this car's OWN current label is fine, otherLabels excludes it`() {
        // The caller is responsible for excluding the car being renamed from otherLabels, so
        // renaming a car to the name it already has is a no-op the controller reports, not a
        // validation failure here.
        val v = CarManageResolver.validateRename("Outlander", otherLabels = listOf("F-150"))
        assertTrue(v.isValid)
    }

    @Test
    fun `rename - a fresh, non-colliding name is valid`() {
        val v = CarManageResolver.validateRename("The Truck", otherLabels = listOf("Outlander", "Mazda 3"))
        assertTrue(v.isValid)
    }
}

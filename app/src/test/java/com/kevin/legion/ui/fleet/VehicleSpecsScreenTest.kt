package com.kevin.legion.ui.fleet

import com.kevin.legion.data.local.VehicleSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SPECS' row builder. vPIC returns partial data constantly - imports, older
 * vehicles, and anything outside the US market come back with most fields
 * blank - so the rule that matters is that an unknown field is ABSENT, never
 * rendered as an empty string, a dash, or a zero that reads like a measurement.
 */
class VehicleSpecsScreenTest {

    private fun spec(build: VehicleSpec.() -> VehicleSpec) =
        VehicleSpec(vehicleId = "default").build()

    @Test
    fun `a null spec has no rows`() {
        assertTrue(specRows(null).isEmpty())
    }

    @Test
    fun `a spec with nothing decoded has no rows`() {
        assertTrue(specRows(spec { this }).isEmpty())
    }

    @Test
    fun `blank strings are dropped rather than rendered empty`() {
        val rows = specRows(spec { copy(manufacturer = "   ", bodyClass = "") })
        assertTrue(rows.isEmpty())
    }

    @Test
    fun `null numbers are omitted, not zeroed`() {
        val rows = specRows(spec { copy(engineCylinders = null, engineHp = null, doors = null) })
        assertFalse(rows.any { it.second.contains("0") })
    }

    @Test
    fun `transmission joins speeds and style, and survives one being blank`() {
        val both = specRows(spec { copy(transmissionSpeeds = "4-Speed", transmissionStyle = "Automatic") })
        assertEquals("4-Speed Automatic", both.first { it.first == "Transmission" }.second)

        val styleOnly = specRows(spec { copy(transmissionSpeeds = "", transmissionStyle = "Automatic") })
        assertEquals("Automatic", styleOnly.first { it.first == "Transmission" }.second)
    }

    @Test
    fun `plant joins city and country, and is dropped when both are blank`() {
        val joined = specRows(spec { copy(plantCity = "Toledo", plantCountry = "USA") })
        assertEquals("Toledo, USA", joined.first { it.first == "Assembled in" }.second)
        assertTrue(specRows(spec { copy(plantCity = "", plantCountry = "") })
            .none { it.first == "Assembled in" })
    }

    @Test
    fun `displacement carries its unit and one decimal`() {
        assertEquals("4.0L", specRows(spec { copy(displacementL = 4.0) })
            .first { it.first == "Displacement" }.second)
    }

    @Test
    fun `driver-entered paint survives alongside decoded fields`() {
        val rows = specRows(spec { copy(engineHp = 190, paintCode = "PSC") })
        assertTrue(rows.any { it.first == "Horsepower" })
        assertTrue(rows.any { it.first == "Paint code" && it.second == "PSC" })
    }
}

package com.kevin.legion.ui.fleet

import com.kevin.legion.data.local.VehicleSpec
import com.kevin.legion.vehicle.RecallCheckResult
import com.kevin.legion.vehicle.VinDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    // --- recallAnnouncement: ticket 12's CHECK RECALLS button stateDescription -----------------
    //
    // Same "every outcome sayable, and the four must never collapse" rule the button itself is
    // built to (`.scratch/fleet-maintenance/issues/12-a-recall-button.md`), asserted here on the
    // TalkBack-facing text rather than the on-screen pane.

    @Test
    fun `recallAnnouncement is null before any check has run`() {
        assertNull(recallAnnouncement(null))
    }

    @Test
    fun `recallAnnouncement names the missing fields distinctly from a failed lookup`() {
        val missing = recallAnnouncement(RecallCheckResult.IdentityMissing(listOf("year", "make")))
        val failed = recallAnnouncement(RecallCheckResult.LookupFailed)
        assertEquals("Missing year, make", missing)
        assertEquals("Recall lookup failed", failed)
        assertTrue("missing and failed must not read alike", missing != failed)
    }

    @Test
    fun `recallAnnouncement distinguishes zero recalls from a failed or missing check`() {
        val none = recallAnnouncement(RecallCheckResult.Checked(emptyList()))
        val failed = recallAnnouncement(RecallCheckResult.LookupFailed)
        val missing = recallAnnouncement(RecallCheckResult.IdentityMissing(listOf("model")))
        assertEquals("No open recalls", none)
        // The exact defect ticket 12 opened on: an empty answer must never share wording (or
        // meaning) with a refusal to even try.
        assertTrue(none != failed && none != missing)
    }

    @Test
    fun `recallAnnouncement counts recalls found, singular and plural`() {
        val recall = VinDecoder.Recall(campaign = "98V123000", component = "STEERING", summary = "", remedy = "")
        assertEquals("1 open recall", recallAnnouncement(RecallCheckResult.Checked(listOf(recall))))
        assertEquals("2 open recalls", recallAnnouncement(RecallCheckResult.Checked(listOf(recall, recall))))
    }
}

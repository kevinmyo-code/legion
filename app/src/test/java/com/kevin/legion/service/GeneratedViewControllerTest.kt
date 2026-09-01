package com.kevin.legion.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [GeneratedViewController]'s ephemeral state and [parseGeneratedViewSpec]'s validation - the
 * generated-view sibling's own version of [VoiceModalControllerTest]/[GlanceCardControllerTest]'s
 * coverage. Pure Kotlin, no Robolectric - the query RUNNER (Room-backed) has its own test class.
 */
class GeneratedViewControllerTest {

    @Test
    fun `show sets the current payload`() {
        GeneratedViewController.dismiss()
        val payload = GeneratedViewPayload(
            shape = GeneratedViewShape.TOTAL_WITH_ROWS,
            title = "Test",
            totalLabel = "USD 12.00",
            provenanceText = "Counted everything.",
        )
        GeneratedViewController.show(payload)
        assertEquals(payload, GeneratedViewController.current.value)
    }

    @Test
    fun `dismiss clears the current payload`() {
        GeneratedViewController.show(
            GeneratedViewPayload(
                shape = GeneratedViewShape.TOTAL_WITH_ROWS,
                title = "Test",
                provenanceText = "Counted everything.",
            ),
        )
        GeneratedViewController.dismiss()
        assertNull(GeneratedViewController.current.value)
    }

    @Test
    fun `a payload with no points, no rows and no total label is empty`() {
        val payload = GeneratedViewPayload(
            shape = GeneratedViewShape.TOTAL_WITH_ROWS,
            title = "Test",
            provenanceText = "Nothing matched.",
        )
        assertTrue(payload.isEmpty)
    }

    @Test
    fun `a payload carrying a total label is not empty`() {
        val payload = GeneratedViewPayload(
            shape = GeneratedViewShape.TOTAL_WITH_ROWS,
            title = "Test",
            totalLabel = "USD 0.00",
            provenanceText = "Counted everything.",
        )
        assertTrue(!payload.isEmpty)
    }

    // ------------------------------------------------------------------------ parseGeneratedViewSpec

    @Test
    fun `a fully valid spec parses into every field named`() {
        val result = parseGeneratedViewSpec(
            shape = "BAR_SERIES",
            source = "LEDGER",
            aggregation = "SUM",
            window = "LAST_3_MONTHS",
            grouping = "BY_MONTH",
            title = "Spend by month",
        )
        val valid = result as GeneratedViewSpecParse.Valid
        assertEquals(GeneratedViewShape.BAR_SERIES, valid.spec.shape)
        assertEquals(QuerySource.LEDGER, valid.spec.source)
        assertEquals(QueryAggregation.SUM, valid.spec.aggregation)
        assertEquals(QueryWindow.LAST_3_MONTHS, valid.spec.window)
        assertEquals(QueryGrouping.BY_MONTH, valid.spec.grouping)
        assertEquals("Spend by month", valid.spec.title)
    }

    @Test
    fun `an out-of-vocabulary shape is refused and names the bad value`() {
        val result = parseGeneratedViewSpec(
            shape = "PIE_CHART",
            source = "LEDGER",
            aggregation = "SUM",
            window = "THIS_MONTH",
            grouping = "NONE",
            title = "Test",
        )
        val invalid = result as GeneratedViewSpecParse.Invalid
        assertTrue(invalid.reason.contains("PIE_CHART"))
    }

    @Test
    fun `an out-of-vocabulary source is refused and names the bad value`() {
        val result = parseGeneratedViewSpec(
            shape = "TOTAL_WITH_ROWS",
            source = "CRYPTO_WALLET",
            aggregation = "SUM",
            window = "THIS_MONTH",
            grouping = "NONE",
            title = "Test",
        )
        val invalid = result as GeneratedViewSpecParse.Invalid
        assertTrue(invalid.reason.contains("CRYPTO_WALLET"))
    }

    @Test
    fun `an out-of-vocabulary window is refused and names the bad value`() {
        val result = parseGeneratedViewSpec(
            shape = "TOTAL_WITH_ROWS",
            source = "LEDGER",
            aggregation = "SUM",
            window = "LAST_WEEK",
            grouping = "NONE",
            title = "Test",
        )
        val invalid = result as GeneratedViewSpecParse.Invalid
        assertTrue(invalid.reason.contains("LAST_WEEK"))
    }

    @Test
    fun `an out-of-vocabulary grouping is refused and names the bad value`() {
        val result = parseGeneratedViewSpec(
            shape = "TOTAL_WITH_ROWS",
            source = "LEDGER",
            aggregation = "SUM",
            window = "THIS_MONTH",
            grouping = "BY_STORE",
            title = "Test",
        )
        val invalid = result as GeneratedViewSpecParse.Invalid
        assertTrue(invalid.reason.contains("BY_STORE"))
    }

    @Test
    fun `a blank title falls back to a default rather than an empty label`() {
        val result = parseGeneratedViewSpec(
            shape = "TOTAL_WITH_ROWS",
            source = "LEDGER",
            aggregation = "SUM",
            window = "THIS_MONTH",
            grouping = "NONE",
            title = "",
        )
        val valid = result as GeneratedViewSpecParse.Valid
        assertTrue(valid.spec.title.isNotBlank())
    }
}

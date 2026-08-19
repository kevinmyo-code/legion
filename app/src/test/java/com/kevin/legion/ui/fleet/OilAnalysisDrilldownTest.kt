package com.kevin.legion.ui.fleet

import com.kevin.legion.data.local.OilAnalysis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic coverage for [OilAnalysisDrilldown.kt]'s ordering and small-
 * multiples mapping (quant-viz ticket 06). No Room, no Android dependency,
 * same posture as [FleetRowsTest].
 */
class OilAnalysisDrilldownTest {
    private val vehicleId = "test-mac"

    private fun analysis(
        date: Long,
        mileage: Int? = null,
        iron: Int? = null,
        copper: Int? = null,
        tbn: Double? = null,
    ) = OilAnalysis(vehicleId = vehicleId, date = date, mileage = mileage, iron = iron, copper = copper, tbn = tbn)

    // ---------------------------------------------------------------- axis

    @Test
    fun `oilOrderAxis picks mileage when every analysis has one`() {
        val analyses = listOf(analysis(date = 200L, mileage = 140_000), analysis(date = 100L, mileage = 130_000))
        assertEquals(OilOrderAxis.MILEAGE, oilOrderAxis(analyses))
    }

    @Test
    fun `oilOrderAxis falls back to date when even one analysis has no mileage`() {
        val analyses = listOf(analysis(date = 200L, mileage = 140_000), analysis(date = 100L, mileage = null))
        assertEquals(OilOrderAxis.DATE, oilOrderAxis(analyses))
    }

    @Test
    fun `oilOrderAxis on an empty list falls back to date rather than throwing`() {
        assertEquals(OilOrderAxis.DATE, oilOrderAxis(emptyList()))
    }

    @Test
    fun `oilAnalysesOrdered sorts oldest-first on whichever axis was chosen`() {
        val byMileage = oilAnalysesOrdered(listOf(analysis(date = 100L, mileage = 140_000), analysis(date = 200L, mileage = 130_000)))
        assertEquals(listOf(130_000, 140_000), byMileage.map { it.mileage })

        val byDate = oilAnalysesOrdered(listOf(analysis(date = 200L, mileage = null), analysis(date = 100L, mileage = null)))
        assertEquals(listOf(100L, 200L), byDate.map { it.date })
    }

    // -------------------------------------------------------- analyte series

    @Test
    fun `buildOilAnalyteSeries carries a null field through as a gap point, not a dropped row`() {
        val ordered = listOf(analysis(date = 100L, iron = 10), analysis(date = 200L, iron = null), analysis(date = 300L, iron = 14))
        val (rows, hidden) = buildOilAnalyteSeries(ordered)
        val iron = rows.first { it.label == "IRON (PPM)" }
        assertEquals(listOf(10f, null, 14f), iron.points)
        assertEquals("14", iron.latestValue)
        assertTrue(hidden < OIL_ANALYTES.size)
    }

    @Test
    fun `buildOilAnalyteSeries skips an analyte that is null across every analysis and counts it hidden`() {
        // No analysis ever reports lead, tin, etc - only iron and copper are ever set.
        val ordered = listOf(analysis(date = 100L, iron = 10, copper = 2), analysis(date = 200L, iron = 12, copper = 3))
        val (rows, hidden) = buildOilAnalyteSeries(ordered)
        assertTrue(rows.none { it.label == "LEAD (PPM)" })
        // 16 total analytes, 2 reported (iron, copper) -> 14 hidden.
        assertEquals(OIL_ANALYTES.size - 2, hidden)
        assertEquals(2, rows.size)
    }

    @Test
    fun `buildOilAnalyteSeries reports the LATEST non-null value, not the first`() {
        val ordered = listOf(analysis(date = 100L, iron = 30), analysis(date = 200L, iron = null), analysis(date = 300L, iron = 8))
        val (rows, _) = buildOilAnalyteSeries(ordered)
        assertEquals("8", rows.first { it.label == "IRON (PPM)" }.latestValue)
    }

    @Test
    fun `buildOilAnalyteSeries on no analyses hides every analyte`() {
        val (rows, hidden) = buildOilAnalyteSeries(emptyList())
        assertEquals(emptyList<OilAnalyteSeries>(), rows)
        assertEquals(OIL_ANALYTES.size, hidden)
    }

    @Test
    fun `a decimal analyte formats to one decimal place`() {
        val ordered = listOf(analysis(date = 100L, tbn = 4.3))
        val (rows, _) = buildOilAnalyteSeries(ordered)
        assertEquals("4.3", rows.first { it.label == "TBN" }.latestValue)
    }
}

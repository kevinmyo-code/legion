package com.kevin.legion.location

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure tests for [CrimeHistory]'s agency-flattening, agency-matching, and complete-year-sum logic -
 * ticket 02 §E. `historyNear` itself is network-bound and left to the on-phone verification pass
 * (CLAUDE.md L11: the ticket's verification steps are gates, and "live calls from the phone" is
 * this ticket's own). These three functions are what a bad response body or an ambiguous place name
 * would actually break, so they get the coverage.
 */
class CrimeHistoryTest {

    private fun agency(name: String, county: String, lat: Double, lon: Double, ori: String) = JSONObject()
        .put("agency_name", name)
        .put("counties", county)
        .put("latitude", lat)
        .put("longitude", lon)
        .put("ori", ori)

    // --- flattenAgencies ---------------------------------------------------------------------

    @Test
    fun `flattenAgencies flattens the county-keyed dict-of-arrays into one list`() {
        val body = """
            {
              "LEE": [{"agency_name":"Pennington Gap PD","ori":"VA1"}],
              "BATH": [{"agency_name":"Bath County SO","ori":"VA2"}, {"agency_name":"Hot Springs PD","ori":"VA3"}]
            }
        """.trimIndent()
        val flat = CrimeHistory.flattenAgencies(body)
        assertEquals(3, flat.size)
        assertEquals(setOf("VA1", "VA2", "VA3"), flat.map { it.optString("ori") }.toSet())
    }

    @Test
    fun `flattenAgencies degrades to empty on unparseable body`() {
        assertTrue(CrimeHistory.flattenAgencies("not json").isEmpty())
    }

    // --- pickAgency ----------------------------------------------------------------------------

    @Test
    fun `pickAgency with no place picks the nearest agency to the fix`() {
        val near = agency("Near PD", "NEAR", 29.77, -95.37, "ORI_NEAR")
        val far = agency("Far PD", "FAR", 39.77, -95.37, "ORI_FAR")
        val picked = CrimeHistory.pickAgency(listOf(far, near), place = null, lat = 29.7604, lon = -95.3698)
        assertEquals("ORI_NEAR", picked?.optString("ori"))
    }

    @Test
    fun `pickAgency with a place matches by agency name, case-insensitively`() {
        val houston = agency("Houston Police Department", "HARRIS", 29.76, -95.36, "TX_HOUSTON")
        val other = agency("Other City PD", "OTHER", 30.0, -95.0, "TX_OTHER")
        val picked = CrimeHistory.pickAgency(listOf(other, houston), place = "houston", lat = 29.7604, lon = -95.3698)
        assertEquals("TX_HOUSTON", picked?.optString("ori"))
    }

    @Test
    fun `pickAgency with a place matches by county name too`() {
        val sheriff = agency("County Sheriff's Office", "HARRIS", 29.76, -95.36, "TX_HARRIS")
        val picked = CrimeHistory.pickAgency(listOf(sheriff), place = "Harris County", lat = 29.7604, lon = -95.3698)
        assertEquals("TX_HARRIS", picked?.optString("ori"))
    }

    @Test
    fun `pickAgency returns null for a named place that matches nothing - never falls back to nearest`() {
        val agencies = listOf(agency("Houston Police Department", "HARRIS", 29.76, -95.36, "TX_HOUSTON"))
        val picked = CrimeHistory.pickAgency(agencies, place = "Nonexistentville", lat = 29.7604, lon = -95.3698)
        assertNull(picked)
    }

    @Test
    fun `pickAgency returns null for an empty agency list`() {
        assertNull(CrimeHistory.pickAgency(emptyList(), place = null, lat = 0.0, lon = 0.0))
    }

    // --- latestCompleteYearTotal -----------------------------------------------------------------

    @Test
    fun `latestCompleteYearTotal sums the most recent year with all twelve months present`() {
        val monthly = JSONObject()
        // 2024: complete, sums to 12 (1 each month).
        for (m in 1..12) monthly.put("%02d-2024".format(m), 1)
        // 2025: only 7 months so far - incomplete, must not be picked.
        for (m in 1..7) monthly.put("%02d-2025".format(m), 100)
        for (m in 8..12) monthly.put("%02d-2025".format(m), JSONObject.NULL)

        val body = JSONObject()
            .put("offenses", JSONObject().put("actuals", JSONObject()
                .put("Pennington Gap Police Department Offenses", monthly)
                .put("Pennington Gap Police Department Clearances", JSONObject())))
            .toString()

        val result = CrimeHistory.latestCompleteYearTotal(body)
        assertEquals(2024 to 12, result)
    }

    @Test
    fun `latestCompleteYearTotal returns null when no year is ever complete`() {
        val monthly = JSONObject()
        for (m in 1..5) monthly.put("%02d-2026".format(m), 3)
        val body = JSONObject()
            .put("offenses", JSONObject().put("actuals", JSONObject().put("Some Agency Offenses", monthly)))
            .toString()
        assertNull(CrimeHistory.latestCompleteYearTotal(body))
    }

    @Test
    fun `latestCompleteYearTotal degrades to null on unparseable body`() {
        assertNull(CrimeHistory.latestCompleteYearTotal("not json"))
    }

    @Test
    fun `latestCompleteYearTotal finds the Offenses series regardless of agency name`() {
        val monthly = JSONObject()
        for (m in 1..12) monthly.put("%02d-2023".format(m), 2)
        val body = JSONObject()
            .put("offenses", JSONObject().put("actuals", JSONObject()
                .put("Some Random Sheriff's Office Offenses", monthly)))
            .toString()
        assertEquals(2023 to 24, CrimeHistory.latestCompleteYearTotal(body))
    }
}

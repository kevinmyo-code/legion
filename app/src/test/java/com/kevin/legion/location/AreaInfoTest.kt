package com.kevin.legion.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure format-function tests for ticket 02 (`.scratch/location-intelligence/issues/02-area-info-tool.md`).
 * Every one of these takes a canned JSON body - no network, no Context, no Robolectric shadow, same
 * posture as [GeofenceManagerTest]. What's under test is the two things the ticket calls out
 * explicitly: every category's string carries its source name, and an empty result reads as
 * "nothing reported" rather than a blank or a zero.
 */
class AreaInfoTest {

    // --- Weather (NWS) ---------------------------------------------------------------------

    @Test
    fun `formatWeatherAlerts names NWS and speaks the event with its expiry`() {
        val body = """
            {"features":[{"properties":{"event":"Tornado Warning","expires":"2026-08-21T18:15:00-05:00"}}]}
        """.trimIndent()
        val out = AreaInfo.formatWeatherAlerts(body)
        assertEquals("NWS: Tornado Warning until 6:15pm", out)
    }

    @Test
    fun `formatWeatherAlerts joins multiple active alerts under one NWS prefix`() {
        val body = """
            {"features":[
                {"properties":{"event":"Heat Advisory","expires":"2026-08-22T19:00:00-05:00"}},
                {"properties":{"event":"Flood Watch","expires":"2026-08-22T07:00:00-05:00"}}
            ]}
        """.trimIndent()
        val out = AreaInfo.formatWeatherAlerts(body)
        assertTrue(out.startsWith("NWS: "))
        assertTrue(out.contains("Heat Advisory until 7:00pm"))
        assertTrue(out.contains("Flood Watch until 7:00am"))
    }

    @Test
    fun `formatWeatherAlerts with no active alerts reads as nothing reported, not blank`() {
        val out = AreaInfo.formatWeatherAlerts("""{"features":[]}""")
        assertEquals("NWS: nothing reported.", out)
    }

    @Test
    fun `formatWeatherAlerts degrades on unparseable body without throwing`() {
        val out = AreaInfo.formatWeatherAlerts("not json")
        assertEquals("NWS: nothing reported.", out)
    }

    @Test
    fun `formatWeatherAlerts speaks the bare event when expires is missing or unparseable`() {
        val body = """{"features":[{"properties":{"event":"Special Weather Statement"}}]}"""
        assertEquals("NWS: Special Weather Statement", AreaInfo.formatWeatherAlerts(body))
    }

    // --- Quake (USGS) ------------------------------------------------------------------------

    private fun quakeFeature(mag: Double, lat: Double, lon: Double, timeMs: Long): String =
        """{"properties":{"mag":$mag,"time":$timeMs},"geometry":{"coordinates":[$lon,$lat,10.0]}}"""

    @Test
    fun `formatQuakes names USGS and reports magnitude, distance, bearing and age`() {
        val now = 1_000_000_000_000L
        // Roughly 1 degree of latitude north of the origin (~69 miles) - due north, so bearing is N.
        val body = """{"features":[${quakeFeature(4.6, 30.7604, -95.3698, now - 14 * 60_000L)}]}"""
        val out = AreaInfo.formatQuakes(body, originLat = 29.7604, originLon = -95.3698, now = now)
        assertTrue("expected source name, got: $out", out.startsWith("USGS: M4.6, "))
        assertTrue("expected a north bearing, got: $out", out.contains(" N, "))
        assertTrue("expected a relative age, got: $out", out.contains("14 minutes ago"))
    }

    @Test
    fun `formatQuakes excludes anything outside the radius`() {
        val now = 1_000_000_000_000L
        // ~690 miles north - well outside the default 200mi radius.
        val body = """{"features":[${quakeFeature(5.0, 39.7604, -95.3698, now)}]}"""
        val out = AreaInfo.formatQuakes(body, originLat = 29.7604, originLon = -95.3698, now = now, radiusMiles = 200.0)
        assertEquals("USGS: nothing reported.", out)
    }

    @Test
    fun `formatQuakes with no features reads as nothing reported`() {
        assertEquals("USGS: nothing reported.", AreaInfo.formatQuakes("""{"features":[]}""", 29.76, -95.37))
    }

    @Test
    fun `formatQuakes caps at the limit and sorts nearest first`() {
        val now = 1_000_000_000_000L
        val features = (1..8).joinToString(",") { i ->
            // Each one degree further north (~69 miles further), all within the wide test radius.
            quakeFeature(3.0 + i * 0.1, 29.7604 + i * 0.05, -95.3698, now)
        }
        val body = """{"features":[$features]}"""
        val out = AreaInfo.formatQuakes(body, 29.7604, -95.3698, now = now, radiusMiles = 500.0, limit = 3)
        // Exactly 3 clauses, separated by "; ".
        assertEquals(3, out.removePrefix("USGS: ").split("; ").size)
    }

    // --- Fire (NIFC) -------------------------------------------------------------------------

    @Test
    fun `formatFire names NIFC and speaks name, size and containment`() {
        val body = """
            {"features":[{"properties":{"IncidentName":"Cataract","IncidentSize":1200,"PercentContained":40}}]}
        """.trimIndent()
        assertEquals("NIFC: Cataract, 1200 acres, 40% contained", AreaInfo.formatFire(body))
    }

    @Test
    fun `formatFire speaks not-yet-reported rather than a false zero for a null field`() {
        val body = """
            {"features":[{"properties":{"IncidentName":"Green","IncidentSize":null,"PercentContained":null}}]}
        """.trimIndent()
        assertEquals("NIFC: Green, size not yet reported, containment not yet reported", AreaInfo.formatFire(body))
    }

    @Test
    fun `formatFire with no features reads as nothing reported`() {
        assertEquals("NIFC: nothing reported.", AreaInfo.formatFire("""{"features":[]}"""))
    }

    // --- Disaster (FEMA) ---------------------------------------------------------------------

    @Test
    fun `formatDisaster names FEMA and never says warning or alert`() {
        val body = """
            {"DisasterDeclarationsSummaries":[{
                "declarationTitle":"FLOODING","incidentType":"Flood",
                "designatedArea":"Maverick (County)","declarationDate":"2026-07-18T00:00:00.000Z"
            }]}
        """.trimIndent()
        val out = AreaInfo.formatDisaster(body)
        assertTrue(out.startsWith("FEMA: "))
        assertTrue(out.contains("FLOODING (Flood) declared"))
        assertTrue(out.contains("for Maverick (County)"))
        assertTrue("declarations must never read as an alert", !out.lowercase().contains("warning"))
        assertTrue("declarations must never read as an alert", !out.lowercase().contains("alert"))
    }

    @Test
    fun `formatDisaster with no rows reads as nothing reported`() {
        assertEquals("FEMA: nothing reported.", AreaInfo.formatDisaster("""{"DisasterDeclarationsSummaries":[]}"""))
    }

    @Test
    fun `formatDisaster degrades on unparseable body without throwing`() {
        assertEquals("FEMA: nothing reported.", AreaInfo.formatDisaster("not json"))
    }
}

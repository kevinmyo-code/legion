package com.kevin.legion.conditions

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Round 5: the engine's `check_conditions` structuredContent, read the way the phone reads it.
 * The JSON is the shape `server/conditions/report.py` builds (`tests/test_conditions.py`). */
@RunWith(RobolectricTestRunner::class)
class ConditionsControllerParseTest {

    private val structured = JSONObject(
        """{"where":"the given point","lat":30.05,"lon":-98.85,"sections":[
          {"kind":"alerts","status":"found","text":"NWS alerts, active now: ...","rows":[
            {"id":"urn:oid:a","event":"Storm Warning","severity":"Severe","headline":"h","area":"Coast",
             "sender":"NWS Example","ends":"","expires":"2026-11-10T05:00:00-09:00","severe":true},
            {"id":"urn:oid:b","event":"Flood Advisory","severity":"Minor","headline":"","area":"",
             "sender":"NWS","ends":"","expires":"","severe":false},
            {"id":"","event":"Tornado Warning","severity":"Extreme","severe":true}]},
          {"kind":"roads","status":"not_configured","text":"Road closures: DriveTexas is not set up","rows":[]}]}""",
    )

    @Test
    fun `sections keep kind, status and the engine's words`() {
        val sections = ConditionsController.sections(structured)
        assertEquals(listOf("alerts", "roads"), sections.map { it.kind })
        assertEquals("not_configured", sections[1].status)
    }

    @Test
    fun `only severe alerts with an id are raised, ends falling back to expires`() {
        val severe = ConditionsController.severe(structured)
        assertEquals(listOf("urn:oid:a"), severe.map { it.id })
        assertEquals("2026-11-10T05:00:00-09:00", severe[0].ends)
    }

    @Test
    fun `no structured content is no alerts, never a crash`() {
        assertEquals(emptyList<SevereAlert>(), ConditionsController.severe(null))
        assertEquals(emptyList<ConditionsSection>(), ConditionsController.sections(JSONObject()))
    }
}

package com.kevin.legion.service

import com.kevin.legion.ai.AgentResult
import com.kevin.legion.ai.AgentTool
import com.kevin.legion.ai.RestOutcome
import com.kevin.legion.ai.SubAgent
import com.kevin.legion.data.local.Vehicle
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Ticket 04 run 2 (A25, 2026-10-28): "Are there any trouble codes on the Jeep?" reached `ask_fleet`,
 * whose router gets ONE round, and it spent that round on `list_vehicles` because nothing told it
 * which car the dongle was in. [LiveToolbox.fleetRouteContext] now tells it before it picks.
 *
 * What this proves: the line names every car under a string the resolver matches, says where the
 * dongle is, and reaches the router's one POST; and a codes question answered in that one round
 * can carry the car's name to a stored-record codes tool. What it cannot prove: that the real
 * router model picks that tool. That is owed to the next phone run. Robolectric because
 * [LiveToolbox] builds its declarations as JSON on load.
 */
@RunWith(RobolectricTestRunner::class)
class LiveToolboxFleetRouteContextTest {

    private fun car(mac: String, name: String, year: Int, make: String, model: String) = Vehicle(
        obdMac = mac, name = name, make = make, model = model, year = year, personaPrompt = "",
    )

    private val outlander = car("AA:00", "", 2020, "Mitsubishi", "Outlander")
    private val jeep = car("BB:00", "1998 Jeep Cherokee", 1998, "Jeep", "Cherokee")
    private val truck = car("CC:00", "the truck", 2004, "Ford", "F-150")
    private val fleet = listOf(outlander, jeep, truck)

    @Test
    fun `three cars and no dongle - every car named, live tools ruled out`() {
        val line = LiveToolbox.fleetRouteContext(fleet, activeId = "BB:00", connectedId = null)
        // A blank nickname falls back to the spec, which VehicleResolver's contains tier matches.
        assertTrue(line, line.contains("\"2020 Mitsubishi Outlander\""))
        // Nickname equal to the spec is written once, not twice.
        assertTrue(line, line.contains("\"1998 Jeep Cherokee\""))
        assertFalse(line, line.contains("\"1998 Jeep Cherokee\" (1998 Jeep Cherokee)"))
        // A real nickname is the key (VehicleResolver's exact-name tier), the spec rides beside it.
        assertTrue(line, line.contains("\"the truck\" (2004 Ford F-150)"))
        assertTrue(line, line.contains("Currently driving: \"1998 Jeep Cherokee\"."))
        assertTrue(line, line.contains("not connected to any car"))
        assertTrue(line, line.contains("pass its quoted name as `vehicle`"))
        assertTrue(line, line.contains("in this same round"))
        assertTrue(line, line.contains("call list_vehicles only when the question is about the cars themselves"))
        // Names no id: no fleet tool takes one.
        assertFalse(line, line.contains("BB:00"))
    }

    @Test
    fun `dongle in a car on file is named, one not on file is said so`() {
        val inJeep = LiveToolbox.fleetRouteContext(fleet, activeId = "BB:00", connectedId = "BB:00")
        assertTrue(inJeep, inJeep.contains("The OBD dongle is plugged into \"1998 Jeep Cherokee\""))
        val elsewhere = LiveToolbox.fleetRouteContext(fleet, activeId = null, connectedId = "ZZ:99")
        assertTrue(elsewhere, elsewhere.contains("plugged into a car that is not on file"))
        assertFalse(elsewhere, elsewhere.contains("Currently driving"))
    }

    @Test
    fun `no car on file says so`() {
        val line = LiveToolbox.fleetRouteContext(emptyList(), activeId = null, connectedId = null)
        assertTrue(line, line.contains("No car is on file yet"))
    }

    @Test
    fun `the line reaches the router and a codes question reaches a codes tool in one round`() = runBlocking {
        val line = LiveToolbox.fleetRouteContext(fleet, activeId = "BB:00", connectedId = null)
        val posts = mutableListOf<JSONObject>()
        // Scripted reply: what a router that read the line can now do in its single round.
        val reply = RestOutcome.Ok(
            JSONObject().put(
                "candidates",
                JSONArray().put(
                    JSONObject().put(
                        "content",
                        JSONObject().put("role", "model").put(
                            "parts",
                            JSONArray().put(
                                JSONObject().put(
                                    "functionCall",
                                    JSONObject().put("name", "get_code_history")
                                        .put("args", JSONObject().put("vehicle", "1998 Jeep Cherokee")),
                                ),
                            ),
                        ),
                    ),
                ),
            ).toString(),
        )
        val sub = SubAgent("grounding." + line, false, SubAgent.DEFAULT_MODEL) { _, body ->
            posts.add(body)
            reply
        }
        var askedFor: String? = null
        val codeHistory = AgentTool(name = "get_code_history", description = "d", timeoutMs = 5_000) { args ->
            askedFor = args.optString("vehicle")
            """{"success":true,"vehicle":"1998 Jeep Cherokee","history":[{"code":"P0442"}]}"""
        }
        val listVehicles = AgentTool(name = "list_vehicles", description = "d", timeoutMs = 5_000) { "{}" }

        val r = sub.route("Are there any trouble codes on the Jeep?", listOf(codeHistory, listVehicles))

        assertEquals(1, posts.size)
        val sys = posts[0].getJSONObject("systemInstruction").getJSONArray("parts")
            .getJSONObject(0).getString("text")
        assertTrue(sys, sys.endsWith(line))
        assertEquals("1998 Jeep Cherokee", askedFor)
        val text = (r as AgentResult.Success).text
        // The frame still echoes only the user's words, never the fleet line.
        val frame = "Results for the user's question \"Are there any trouble codes on the Jeep?\"."
        assertTrue(text, text.startsWith(frame))
        assertFalse(text, text.contains("Cars on file"))
        assertTrue(text, text.contains("P0442"))
    }
}

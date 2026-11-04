package com.kevin.legion.service

import com.kevin.legion.assistant.cards.ConversationCard
import com.kevin.legion.assistant.cards.ConversationCards
import com.kevin.legion.data.local.MaintenanceItem
import com.kevin.legion.data.local.Vehicle
import com.kevin.legion.testutil.RoomTestReset
import com.kevin.legion.vehicle.ActiveVehicle
import com.kevin.legion.vehicle.FleetEngineStore
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The phone test of 2026-11-04 15:55: Alfred answered service, codes and recalls through
 * `ask_fleet`, whose router runs the fleet tools as [com.kevin.legion.ai.AgentTool]s from
 * [LiveToolbox.agentToolsFor]. A card must come from that path exactly as from a direct call.
 */
@RunWith(RobolectricTestRunner::class)
class FleetCardsThroughAskFleetTest {
    private val context = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        RoomTestReset.resetCarDatabaseSingleton()
        context.getSharedPreferences("active_vehicle", android.content.Context.MODE_PRIVATE).edit().clear().apply()
        ConversationCards.end()
    }

    @After
    fun tearDown() {
        ConversationCards.end()
        RoomTestReset.drainArchDiskIoPool()
    }

    private suspend fun seed() {
        FleetEngineStore.createVehicle(
            context,
            Vehicle(
                obdMac = "AA:BB", name = "", make = "Jeep", model = "Cherokee", year = 1998,
                personaPrompt = "", odometerBaseline = 2000, odometerBaselineAt = 1L,
            ),
        )
        FleetEngineStore.upsertNewItem(
            context,
            MaintenanceItem(
                vehicleId = "AA:BB", serviceName = "Oil Change", intervalSource = "CONFIRMED",
                intervalMiles = 5000, lastDoneMileage = 1000,
            ),
        )
        ActiveVehicle.select(context, "AA:BB")
    }

    private suspend fun runFleetTool(name: String, args: JSONObject = JSONObject()): String {
        val tool = LiveToolbox.agentToolsFor("fleet", context).first { it.name == name }
        return tool.run(args)
    }

    @Test
    fun `get_next_service run by the fleet router publishes a car card`() = runBlocking {
        seed()
        ConversationCards.begin()
        runFleetTool("get_next_service")
        val card = ConversationCards.state.value.latest as? ConversationCard.Car
        assertNotNull("no car card from ask_fleet's get_next_service", card)
        assertEquals("Oil Change", card!!.nextService?.label)
    }

    @Test
    fun `get_code_history run by the fleet router publishes the codes part`() = runBlocking {
        seed()
        ConversationCards.begin()
        runFleetTool("get_code_history", JSONObject().put("vehicle", "AA:BB"))
        val card = ConversationCards.state.value.latest as? ConversationCard.Car
        assertNotNull("no car card from ask_fleet's get_code_history", card)
        assertEquals(emptyList<Any>(), card!!.codes)
    }
}

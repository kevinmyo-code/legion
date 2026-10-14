package com.kevin.midnightai.vehicle

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure unit tests for the garage feature's decision logic - no network, no
 * Context/Android. [GarageController.resolveDoor] and
 * [GarageController.dispatchVoiceActivate] are both plain/suspend functions
 * with everything Context-shaped (SharedPrefs, the real [ShellyCloudOpener])
 * injected by the caller, so the confirm-gate and name-resolution rules are
 * exercised directly here exactly as [com.kevin.midnightai.service.LiveToolbox]'s
 * activate_garage tool calls them. [buildSetSwitchUrl]/[buildSetSwitchBody]
 * are likewise pure request builders, tested directly below.
 */
class GarageLogicTest {

    private fun door(id: String, name: String, enabled: Boolean = true) =
        GarageDoorConfig(id = id, deviceId = "shelly-device-$id", relayId = 0, friendlyName = name, enabled = enabled)

    // --- resolveDoor: name resolution ---------------------------------------

    @Test fun single_door_no_name_resolves_to_it() {
        val doors = listOf(door("1", "Main Garage"))
        assertEquals(doors[0], GarageController.resolveDoor(doors, defaultDoorId = null, nameOrNull = null))
    }

    @Test fun single_door_blank_name_resolves_to_it() {
        val doors = listOf(door("1", "Main Garage"))
        assertEquals(doors[0], GarageController.resolveDoor(doors, defaultDoorId = null, nameOrNull = "   "))
    }

    @Test fun fuzzy_match_hit_exact() {
        val doors = listOf(door("1", "Main Garage"), door("2", "Side Gate"))
        assertEquals(doors[1], GarageController.resolveDoor(doors, defaultDoorId = null, nameOrNull = "Side Gate"))
    }

    @Test fun fuzzy_match_hit_partial() {
        val doors = listOf(door("1", "Main Garage"), door("2", "Side Gate"))
        assertEquals(doors[0], GarageController.resolveDoor(doors, defaultDoorId = null, nameOrNull = "garage"))
        assertEquals(doors[1], GarageController.resolveDoor(doors, defaultDoorId = null, nameOrNull = "gate"))
    }

    @Test fun multiple_doors_no_name_no_default_is_null() {
        val doors = listOf(door("1", "Main Garage"), door("2", "Side Gate"))
        assertNull(GarageController.resolveDoor(doors, defaultDoorId = null, nameOrNull = null))
    }

    @Test fun no_match_is_null() {
        val doors = listOf(door("1", "Main Garage"), door("2", "Side Gate"))
        assertNull(GarageController.resolveDoor(doors, defaultDoorId = null, nameOrNull = "back fence"))
    }

    @Test fun ambiguous_match_is_null() {
        val doors = listOf(door("1", "Front Garage"), door("2", "Back Garage"))
        assertNull(GarageController.resolveDoor(doors, defaultDoorId = null, nameOrNull = "garage"))
    }

    @Test fun no_doors_is_null() {
        assertNull(GarageController.resolveDoor(emptyList(), defaultDoorId = null, nameOrNull = null))
        assertNull(GarageController.resolveDoor(emptyList(), defaultDoorId = null, nameOrNull = "garage"))
    }

    // --- default-door selection ----------------------------------------------

    @Test fun default_door_wins_when_multiple_and_no_name() {
        val doors = listOf(door("1", "Main Garage"), door("2", "Side Gate"))
        assertEquals(doors[1], GarageController.resolveDoor(doors, defaultDoorId = "2", nameOrNull = null))
    }

    @Test fun default_door_id_not_present_falls_back_to_null_when_multiple() {
        val doors = listOf(door("1", "Main Garage"), door("2", "Side Gate"))
        assertNull(GarageController.resolveDoor(doors, defaultDoorId = "not-a-real-id", nameOrNull = null))
    }

    // --- dispatchVoiceActivate: confirm gate ---------------------------------

    private class FakeOpener {
        val calls = mutableListOf<GarageDoorConfig>()
        var throwOnActivate: GarageException? = null
        suspend fun activate(door: GarageDoorConfig) {
            throwOnActivate?.let { throw it }
            calls.add(door)
        }
    }

    @Test fun unconfirmed_does_not_activate_and_asks_to_confirm() = runBlocking {
        val doors = listOf(door("1", "Main Garage"))
        val fake = FakeOpener()
        val result = GarageController.dispatchVoiceActivate(
            doors = doors,
            defaultDoorId = null,
            doorName = null,
            confirmed = false,
            activate = { fake.activate(it) },
        )
        assertTrue(fake.calls.isEmpty())
        assertFalse(result.success)
        assertTrue(result.message.contains("confirm", ignoreCase = true))
        assertTrue(result.message.contains("Main Garage"))
    }

    @Test fun confirmed_activates_and_reports_triggered() = runBlocking {
        val doors = listOf(door("1", "Main Garage"))
        val fake = FakeOpener()
        val result = GarageController.dispatchVoiceActivate(
            doors = doors,
            defaultDoorId = null,
            doorName = null,
            confirmed = true,
            activate = { fake.activate(it) },
        )
        assertEquals(1, fake.calls.size)
        assertEquals(doors[0], fake.calls[0])
        assertTrue(result.success)
        assertTrue(result.message.contains("Triggered"))
        // Never claims a direction - single-button toggle, see GarageOpener KDoc.
        assertFalse(result.message.contains("open", ignoreCase = true))
        assertFalse(result.message.contains("clos", ignoreCase = true))
    }

    @Test fun unresolved_door_does_not_activate_even_if_confirmed() = runBlocking {
        val doors = listOf(door("1", "Main Garage"), door("2", "Side Gate"))
        val fake = FakeOpener()
        val result = GarageController.dispatchVoiceActivate(
            doors = doors,
            defaultDoorId = null,
            doorName = null,
            confirmed = true,
            activate = { fake.activate(it) },
        )
        assertTrue(fake.calls.isEmpty())
        assertFalse(result.success)
        assertTrue(result.message.contains("Which door", ignoreCase = true))
    }

    @Test fun no_doors_configured_does_not_activate() = runBlocking {
        val fake = FakeOpener()
        val result = GarageController.dispatchVoiceActivate(
            doors = emptyList(),
            defaultDoorId = null,
            doorName = null,
            confirmed = true,
            activate = { fake.activate(it) },
        )
        assertTrue(fake.calls.isEmpty())
        assertFalse(result.success)
        assertTrue(result.message.contains("Settings"))
    }

    @Test fun device_error_on_activate_is_reported_and_not_success() = runBlocking {
        val doors = listOf(door("1", "Main Garage"))
        val fake = FakeOpener().apply { throwOnActivate = GarageException.NotConfigured() }
        val result = GarageController.dispatchVoiceActivate(
            doors = doors,
            defaultDoorId = null,
            doorName = null,
            confirmed = true,
            activate = { fake.activate(it) },
        )
        assertFalse(result.success)
        assertTrue(result.message.contains("set up", ignoreCase = true))
    }

    // --- ShellyCloudOpener request builders: pure, no network -----------------

    @Test fun set_switch_url_uses_server_host_and_auth_key() {
        val url = buildSetSwitchUrl(serverHost = "shelly-12-eu.shelly.cloud", authKey = "abc123")
        assertEquals("https://shelly-12-eu.shelly.cloud/v2/devices/api/set/switch?auth_key=abc123", url)
    }

    @Test fun set_switch_body_has_id_channel_and_on() {
        val body = JSONObject(buildSetSwitchBody(deviceId = "device-42", channel = 1, on = true))
        assertEquals("device-42", body.getString("id"))
        assertEquals(1, body.getInt("channel"))
        assertTrue(body.getBoolean("on"))
    }

    @Test fun set_switch_body_off_sets_on_false() {
        val body = JSONObject(buildSetSwitchBody(deviceId = "device-42", channel = 0, on = false))
        assertFalse(body.getBoolean("on"))
    }

    @Test fun set_switch_body_default_channel_is_zero_when_passed_explicitly() {
        val body = JSONObject(buildSetSwitchBody(deviceId = "device-1", channel = 0, on = true))
        assertEquals(0, body.getInt("channel"))
    }

    // --- pulseRelay: guaranteed momentary release ----------------------------

    @Test fun pulse_sends_on_then_off_in_order() = runBlocking {
        val events = mutableListOf<String>()
        pulseRelay(pulseMs = 1, sendOn = { events.add("on") }, sendOff = { events.add("off") })
        assertEquals(listOf("on", "off"), events)
    }

    @Test fun pulse_release_fires_even_if_hold_is_cancelled() = runBlocking {
        // The blocking bug: if the coroutine is cancelled mid-hold (sheet
        // dismissed, app backgrounded), the relay must still be released or a
        // real wall-button contact stays latched closed.
        val events = mutableListOf<String>()
        val job = launch {
            pulseRelay(
                pulseMs = 10_000,
                sendOn = { events.add("on") },
                sendOff = { events.add("off") },
            )
        }
        while (!events.contains("on")) yield() // wait until we're holding
        job.cancelAndJoin()
        assertEquals(listOf("on", "off"), events)
    }

    @Test fun pulse_attempts_release_even_if_engage_throws() = runBlocking {
        // The on-call can fail its response read AFTER the relay actuated (lost
        // response over the hotspot path), so the idempotent release must still
        // be attempted; the original error still propagates to the caller.
        val events = mutableListOf<String>()
        val boom = RuntimeException("engage response lost")
        val thrown = runCatching {
            pulseRelay(
                pulseMs = 1,
                sendOn = { events.add("on"); throw boom },
                sendOff = { events.add("off") },
            )
        }.exceptionOrNull()
        assertEquals(boom, thrown)
        assertEquals(listOf("on", "off"), events)
    }
}

package com.kevin.midnightai.service

import com.kevin.midnightai.vehicle.VehicleController
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveSessionToolPolicyTest {

    @Test fun `blocking network tools get the 45 second tier`() {
        for (name in listOf("recommend_music", "register_vehicle", "check_recalls", "lookup_vin")) {
            assertEquals(name, 45_000L, LiveSessionController.toolTimeoutMs(name))
        }
    }

    @Test fun `sub-agent tools and the engine keep their tiers`() {
        assertEquals(45_000L, LiveSessionController.toolTimeoutMs("diagnose_codes"))
        assertEquals(150_000L, LiveSessionController.toolTimeoutMs("ask_engine"))
    }

    @Test fun `play_music keeps the short budget it manages itself`() {
        assertEquals(10_000L, LiveSessionController.toolTimeoutMs("play_music"))
        assertEquals(10_000L, LiveSessionController.toolTimeoutMs("control_music"))
    }

    @Test fun `transport actions and source tools change playback`() {
        for (a in listOf("play", "pause", "next", "previous")) {
            assertTrue(a, LiveSessionController.changesPlayback("control_music", JSONObject().put("action", a)))
        }
        for (t in listOf("set_music_source", "play_music", "play_mixtape", "open_music")) {
            assertTrue(t, LiveSessionController.changesPlayback(t, JSONObject()))
        }
    }

    @Test fun `likes shuffles and unrelated tools leave playback alone`() {
        assertFalse(LiveSessionController.changesPlayback("control_music", JSONObject().put("action", "shuffle_on")))
        assertFalse(LiveSessionController.changesPlayback("control_music", JSONObject().put("action", "like")))
        assertFalse(LiveSessionController.changesPlayback("get_time", JSONObject()))
    }

    @Test fun `register_vehicle never claims the schedule was found`() {
        val m = VehicleController.registeredMessage(2003, "BMW", "330i")
        assertTrue(m.contains("2003 BMW 330i"))
        assertTrue(m.contains("isn't done yet"))
        assertFalse(m.contains("Pulled up"))
    }
}

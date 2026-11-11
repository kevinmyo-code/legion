package com.kevin.legion.service.local

import com.kevin.legion.service.ClockToolbox
import com.kevin.legion.ui.LegionRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Each local command is the same tool call Alfred would make (ADR 0035), with valid arguments. */
class LocalCommandPlanTest {

    @Test
    fun `timers and alarms are clock calls the clock tool accepts`() {
        val t = LocalCommandPlan.of(LocalCommand.Timer(600)) as LocalAction.Tool
        assertEquals("clock", t.name)
        assertEquals("timer_start", t.args["action"])
        assertEquals(600, t.args["seconds"])
        val a = LocalCommandPlan.of(LocalCommand.Alarm(6, 5)) as LocalAction.Tool
        assertEquals("06:05", a.args["time"])
        assertEquals("none", a.args["days"])
        for (call in listOf(t, a)) assertTrue(call.args["action"] in ClockToolbox.ACTIONS)
    }

    @Test
    fun `playback and volume are the music tools' own actions`() {
        assertEquals(
            LocalAction.Tool("control_music", mapOf("action" to "pause")),
            LocalCommandPlan.of(LocalCommand.Media(MediaAction.PAUSE)),
        )
        assertEquals(
            LocalAction.Tool("control_music", mapOf("action" to "play")),
            LocalCommandPlan.of(LocalCommand.Media(MediaAction.RESUME)),
        )
        assertEquals(
            LocalAction.Tool("control_volume", mapOf("action" to "set", "level" to 40)),
            LocalCommandPlan.of(LocalCommand.Volume(VolumeAction.SET, 40)),
        )
        assertEquals(
            LocalAction.Tool("control_volume", mapOf("action" to "mute")),
            LocalCommandPlan.of(LocalCommand.Volume(VolumeAction.MUTE, null)),
        )
    }

    @Test
    fun `clearing ticked lines names the real list`() {
        assertEquals(
            LocalAction.Tool("manage_checklist", mapOf("action" to "clear_ticked", "list" to "Groceries")),
            LocalCommandPlan.of(LocalCommand.ClearTicked("groceries")),
        )
    }

    @Test
    fun `every screen the matcher knows is a real route`() {
        val routes = setOf(
            LegionRoute.SETTINGS, LegionRoute.CALENDAR, LegionRoute.CHECKLISTS, LegionRoute.MONEY,
            LegionRoute.FLEET, LegionRoute.BODY, LegionRoute.NEWS, LegionRoute.HOME, LegionRoute.APPS,
            LegionRoute.FLEET_PLACES, LegionRoute.BOUGHT,
        )
        for ((_, route) in LocalCommandPlan.ROUTES) assertTrue("$route is not a LegionRoute", route in routes)
        assertEquals(LocalAction.Open("settings", "settings"), LocalCommandPlan.of(LocalCommand.OpenScreen("settings")))
    }

    @Test
    fun `that's all only stops listening`() {
        assertEquals(LocalAction.Dismiss, LocalCommandPlan.of(LocalCommand.Sleep))
    }
}

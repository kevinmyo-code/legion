package com.kevin.legion.clock

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.kevin.legion.service.ClockToolbox
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

/**
 * The clock's Android edges under Robolectric (alfred-clock tickets 01 and 06): the JSON store,
 * the re-arm receiver on every system broadcast it listens for, the fire receiver, and the `clock`
 * voice tool's action parsing.
 */
@RunWith(RobolectricTestRunner::class)
class ClockAndroidTest {
    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()

    @After
    fun tearDown() {
        ClockGraph.factory = null
        ClockGraph.reset()
    }

    private fun oneAlarm() =
        ClockRig(
            store = MemoryClockStore(ClockState(alarms = listOf(ClockAlarm(1, 7, 0, AlarmMath.EVERY_DAY)), nextId = 2)),
        )

    private fun install(rig: ClockRig) {
        ClockGraph.reset()
        ClockGraph.factory = { rig.clock }
    }

    @Test
    fun `the store round-trips every field`() {
        val state = ClockState(
            alarms = listOf(
                ClockAlarm(3, 6, 45, AlarmMath.WEEKDAYS, "Gym", true, null, 123L),
                ClockAlarm(4, 7, 0, emptySet(), "", false, LocalDate.parse("2026-11-10")),
            ),
            timers = listOf(ClockTimer(5, "tea", 180_000, TimerState.PAUSED, 90_000, 1, 2, 3)),
            stopwatch = Stopwatch(true, 10, 20, 30, 4, listOf(5L, 6L)),
            bedtime = Bedtime(
                true, 23, 15, AlarmMath.WEEKEND, 20, active = true, quietByBedtime = true, feedByBedtime = false,
            ),
            nextId = 6,
        )
        assertEquals(state, ClockJson.decode(ClockJson.encode(state)))
        val store = PrefsClockStore.forContext(context)
        store.save(state)
        assertEquals(state, store.load())
    }

    @Test
    fun `a corrupt store reads as empty rather than crashing the ring path`() {
        val prefs = context.createDeviceProtectedStorageContext().getSharedPreferences("alfred_clock", 0)
        prefs.edit().putString("state", "{not json").commit()
        assertEquals(ClockState(), PrefsClockStore.forContext(context).load())
    }

    @Test
    fun `every re-arm broadcast re-arms from the store, and nothing else does`() {
        for (action in ClockRearmReceiver.ACTIONS) {
            val rig = oneAlarm()
            install(rig)
            ClockRearmReceiver().onReceive(context, Intent(action))
            assertEquals(action, rig.ms("2026-11-10T07:00"), rig.scheduler.alarms[1])
        }
        val rig = oneAlarm()
        install(rig)
        ClockRearmReceiver().onReceive(context, Intent("com.example.SPOOF"))
        assertTrue(rig.scheduler.alarms.isEmpty())
    }

    @Test
    fun `the fire receiver rings an armed alarm and ignores a deleted one`() {
        val rig = oneAlarm()
        install(rig)
        val fire = { id: Int ->
            val i = Intent(ClockFireReceiver.ACTION_ALARM).putExtra(ClockFireReceiver.EXTRA_ID, id)
            ClockFireReceiver().onReceive(context, i)
        }
        fire(9)
        assertTrue(rig.ringer.ringing.isEmpty())
        fire(1)
        assertEquals(RingRequest(RingKind.ALARM, 1, "07:00"), rig.ringer.ringing.single())
        val dismiss = Intent(ClockActionReceiver.ACTION_DISMISS).putExtra(ClockFireReceiver.EXTRA_ID, 1)
        ClockActionReceiver().onReceive(context, dismiss)
        assertTrue(rig.ringer.ringing.isEmpty())
    }

    @Test
    fun `the bedtime fire starts bedtime and arms the next night`() {
        val store = MemoryClockStore(ClockState(bedtime = Bedtime(enabled = true)))
        val rig = ClockRig(time = FakeTime.at("2026-11-09T22:30"), store = store)
        install(rig)
        ClockFireReceiver().onReceive(context, Intent(ClockFireReceiver.ACTION_BEDTIME))
        assertTrue(rig.store.load().bedtime.active)
        assertEquals(rig.ms("2026-11-10T22:30"), rig.scheduler.bedtime?.second)
    }

    private fun tool(rig: ClockRig, vararg kv: Pair<String, Any>): JSONObject {
        ClockToolbox.controllerFactory = { rig.clock }
        val args = JSONObject().apply { kv.forEach { (k, v) -> put(k, v) } }
        return ClockToolbox.dispatch(context, "clock", args)!!
    }

    @Test
    fun `the clock tool parses every action and says what happened`() {
        val rig = ClockRig()
        try {
            val set = tool(rig, "action" to "alarm_set", "time" to "6:30", "days" to "weekdays", "label" to "Work")
            assertTrue(set.getBoolean("success"))
            assertEquals(rig.ms("2026-11-10T06:30"), rig.scheduler.alarms[1])
            assertTrue(tool(rig, "action" to "alarm_list").getString("message").contains("06:30 weekdays, Work"))
            assertFalse(tool(rig, "action" to "alarm_set", "time" to "teatime").getBoolean("success"))
            assertFalse(tool(rig, "action" to "alarm_set", "time" to "7", "days" to "someday").getBoolean("success"))
            assertTrue(tool(rig, "action" to "timer_start", "seconds" to 300, "label" to "pasta").getBoolean("success"))
            val left = tool(rig, "action" to "timer_left", "label" to "pasta").getString("message")
            assertTrue(left.contains("5 minutes"))
            assertTrue(tool(rig, "action" to "timer_pause").getBoolean("success"))
            assertTrue(tool(rig, "action" to "timer_resume").getBoolean("success"))
            assertTrue(tool(rig, "action" to "timer_cancel", "label" to "pasta").getBoolean("success"))
            assertTrue(tool(rig, "action" to "stopwatch_start").getBoolean("success"))
            assertTrue(tool(rig, "action" to "stopwatch_lap").getBoolean("success"))
            assertTrue(tool(rig, "action" to "stopwatch_stop").getBoolean("success"))
            assertTrue(tool(rig, "action" to "stopwatch_reset").getBoolean("success"))
            assertTrue(tool(rig, "action" to "bedtime_on").getBoolean("success"))
            assertTrue(tool(rig, "action" to "bedtime_off").getString("message").startsWith("Bedtime is over"))
            assertTrue(tool(rig, "action" to "alarm_cancel", "time" to "06:30").getBoolean("success"))
            val unknown = tool(rig, "action" to "make_coffee")
            assertFalse(unknown.getBoolean("success"))
            assertTrue(unknown.getString("message").contains("Nothing was changed"))
        } finally {
            ClockToolbox.controllerFactory = ClockGraph::controller
        }
    }

    @Test
    fun `the clock declaration lists exactly the actions the tool handles`() {
        val decl = ClockToolbox.declarations().getJSONObject(0)
        assertEquals("clock", decl.getString("name"))
        val props = decl.getJSONObject("parameters").getJSONObject("properties")
        val enum = props.getJSONObject("action").getJSONArray("enum")
        val names = (0 until enum.length()).map { enum.getString(it) }
        assertEquals(ClockToolbox.ACTIONS, names)
        val rig = ClockRig()
        for (a in names) {
            val r = ClockToolbox.run(rig.clock, JSONObject().put("action", a))
            assertFalse(a, r.message.contains("is not a clock action"))
        }
        assertTrue(ClockToolbox.dispatch(context, "not_clock", JSONObject()) == null)
    }
}

package com.kevin.legion.service

import android.content.Context
import com.kevin.legion.clock.AlarmMath
import com.kevin.legion.clock.ClockController
import com.kevin.legion.clock.ClockGraph
import com.kevin.legion.clock.ClockResult
import org.json.JSONArray
import org.json.JSONObject

/**
 * Alfred's clock by voice (alfred-clock ticket 06, ADR 0079): ONE `clock` tool with an action enum,
 * the trade `books`, `browser` and `app_update` made, because [LiveSetupPayloadSizeTest] bills every
 * declaration on every reconnect. Every action calls [ClockController], the same controller as the
 * Clock screen (ADR 0035). Works offline; the result's `message` says what changed, and a failure
 * says nothing changed (CLAUDE.md sec 7).
 */
object ClockToolbox {

    const val TOOL_NAME = "clock"

    internal val ACTIONS = listOf(
        "alarm_set", "alarm_cancel", "alarm_list",
        "timer_start", "timer_pause", "timer_resume", "timer_cancel", "timer_left",
        "stopwatch_start", "stopwatch_stop", "stopwatch_lap", "stopwatch_reset",
        "bedtime_on", "bedtime_off",
    )

    /** Test seam. */
    internal var controllerFactory: (Context) -> ClockController = ClockGraph::controller

    private const val DESCRIPTION =
        "Phone clock, offline: alarms (wake-ups), named timers, stopwatch, bedtime (Quiet + feed block till " +
            "the next alarm). time HH:mm; days e.g. weekdays or mon,fri, none = once. " +
            "bedtime_on with time = nightly, else now. Say only what the result says."

    fun declarations(): JSONArray = JSONArray().put(
        fn(
            name = "clock",
            description = DESCRIPTION,
            params = JSONObject()
                .put("action", prop("string", ACTIONS))
                .put("time", prop("string"))
                .put("days", prop("string"))
                .put("label", prop("string"))
                .put("seconds", prop("integer")),
            required = listOf("action"),
        ),
    )

    // `name = "..."` written out in the call above: tools/voice_guide.py and VoiceGuideDataTest find
    // tool names by scanning for that exact shape.
    private fun fn(name: String, description: String, params: JSONObject, required: List<String>): JSONObject =
        JSONObject()
            .put("name", name)
            .put("description", description)
            .put(
                "parameters",
                JSONObject().put("type", "object").put("properties", params).put("required", JSONArray(required)),
            )

    private fun prop(type: String, enum: List<String>? = null): JSONObject =
        JSONObject().put("type", type).apply { if (enum != null) put("enum", JSONArray(enum)) }

    /** Null when [name] is not this tool, so [LiveToolbox.dispatch] can fall through. */
    fun dispatch(context: Context, name: String, args: JSONObject): JSONObject? {
        if (name != TOOL_NAME) return null
        val r = runCatching { run(controllerFactory(context), args) }
            .getOrElse { ClockResult(false, "The clock hit an error, so nothing was changed.") }
        return JSONObject().put("success", r.ok).put("message", r.message)
    }

    /** One action, parsed. Pure over the controller so it is tested without Android. */
    internal fun run(clock: ClockController, args: JSONObject): ClockResult {
        val label = args.optString("label").takeIf { it.isNotBlank() }
        val time = args.optString("time").takeIf { it.isNotBlank() }
        return when (val action = args.optString("action")) {
            "alarm_set" -> setAlarm(clock, time, args.optString("days"), label)
            "alarm_cancel" -> clock.alarms.cancel(time, label)
            "alarm_list" -> clock.alarms.list()
            "timer_start" -> clock.timers.start(args.optLong("seconds", 0L), label)
            "timer_pause" -> clock.timers.pause(label)
            "timer_resume" -> clock.timers.resume(label)
            "timer_cancel" -> clock.timers.cancel(label)
            "timer_left" -> clock.timers.left(label)
            "stopwatch_start" -> clock.stopwatch.start()
            "stopwatch_stop" -> clock.stopwatch.stop()
            "stopwatch_lap" -> clock.stopwatch.lap()
            "stopwatch_reset" -> clock.stopwatch.reset()
            "bedtime_on" -> clock.bedtime.voiceOn(time)
            "bedtime_off" -> clock.bedtime.voiceOff()
            else -> ClockResult(false, "\"$action\" is not a clock action. Nothing was changed.")
        }
    }

    private fun setAlarm(clock: ClockController, time: String?, daysRaw: String, label: String?): ClockResult {
        val hm = time?.let(AlarmMath::parseTime)
            ?: return ClockResult(false, "An alarm needs a time of day, like 07:00. Nothing was set.")
        val days = AlarmMath.parseDays(daysRaw)
            ?: return ClockResult(false, "\"$daysRaw\" is not a set of days. Nothing was set.")
        return clock.alarms.set(hm.first, hm.second, days, label.orEmpty())
    }
}

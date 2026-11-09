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
        "Phone clock, offline: alarms (wake-ups), named timers, stopwatch, bedtime (feed block on, Quiet till " +
            "next alarm). time HH:mm; days e.g. weekdays or mon,fri, none = once. " +
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

    /** The arguments every action may read. */
    private data class Args(val time: String?, val days: String, val label: String?, val seconds: Long)

    private val handlers: Map<String, (ClockController, Args) -> ClockResult> = mapOf(
        "alarm_set" to { c, a -> setAlarm(c, a) },
        "alarm_cancel" to { c, a -> c.alarms.cancel(a.time, a.label) },
        "alarm_list" to { c, _ -> c.alarms.list() },
        "timer_start" to { c, a -> c.timers.start(a.seconds, a.label) },
        "timer_pause" to { c, a -> c.timers.pause(a.label) },
        "timer_resume" to { c, a -> c.timers.resume(a.label) },
        "timer_cancel" to { c, a -> c.timers.cancel(a.label) },
        "timer_left" to { c, a -> c.timers.left(a.label) },
        "stopwatch_start" to { c, _ -> c.stopwatch.start() },
        "stopwatch_stop" to { c, _ -> c.stopwatch.stop() },
        "stopwatch_lap" to { c, _ -> c.stopwatch.lap() },
        "stopwatch_reset" to { c, _ -> c.stopwatch.reset() },
        "bedtime_on" to { c, a -> c.bedtime.voiceOn(a.time) },
        "bedtime_off" to { c, _ -> c.bedtime.voiceOff() },
    )

    /** One action, parsed. Pure over the controller so it is tested without Android. */
    internal fun run(clock: ClockController, args: JSONObject): ClockResult {
        val action = args.optString("action")
        val handler = handlers[action]
            ?: return ClockResult(false, "\"$action\" is not a clock action. Nothing was changed.")
        val parsed = Args(
            time = args.optString("time").takeIf { it.isNotBlank() },
            days = args.optString("days"),
            label = args.optString("label").takeIf { it.isNotBlank() },
            seconds = args.optLong("seconds", 0L),
        )
        return handler(clock, parsed)
    }

    private fun setAlarm(clock: ClockController, a: Args): ClockResult {
        val hm = a.time?.let(AlarmMath::parseTime)
        val days = AlarmMath.parseDays(a.days)
        return when {
            hm == null -> ClockResult(false, "An alarm needs a time of day, like 07:00. Nothing was set.")
            days == null -> ClockResult(false, "\"${a.days}\" is not a set of days. Nothing was set.")
            else -> clock.alarms.set(hm.first, hm.second, days, a.label.orEmpty())
        }
    }
}

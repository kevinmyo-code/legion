package com.kevin.legion.clock

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate

/** Where the clock's one [ClockState] lives. Read and written whole; it is a few hundred bytes. */
interface ClockStore {
    fun load(): ClockState
    fun save(state: ClockState)
}

/** Test and preview store. */
class MemoryClockStore(private var state: ClockState = ClockState()) : ClockStore {
    override fun load(): ClockState = state
    override fun save(state: ClockState) { this.state = state }
}

/**
 * The clock in DEVICE-PROTECTED storage (ADR 0079): readable after a reboot before the first
 * unlock, which is when `LOCKED_BOOT_COMPLETED` re-arms the alarms and an alarm may have to ring.
 * Synchronous `commit()`: a receiver's process may die the moment it returns.
 */
class PrefsClockStore(private val prefs: SharedPreferences) : ClockStore {
    override fun load(): ClockState =
        prefs.getString(KEY, null)?.let { runCatching { ClockJson.decode(it) }.getOrNull() } ?: ClockState()

    override fun save(state: ClockState) {
        prefs.edit().putString(KEY, ClockJson.encode(state)).commit()
    }

    companion object {
        private const val FILE = "alfred_clock"
        private const val KEY = "state"

        fun forContext(context: Context): PrefsClockStore {
            val de = context.applicationContext.createDeviceProtectedStorageContext()
            return PrefsClockStore(de.getSharedPreferences(FILE, Context.MODE_PRIVATE))
        }
    }
}

/** [ClockState] to and from JSON. Unknown or missing fields fall back to the model's defaults. */
object ClockJson {

    fun encode(s: ClockState): String = JSONObject()
        .put("nextId", s.nextId)
        .put("alarms", JSONArray(s.alarms.map(::alarm)))
        .put("timers", JSONArray(s.timers.map(::timer)))
        .put("stopwatch", stopwatch(s.stopwatch))
        .put("bedtime", bedtime(s.bedtime))
        .toString()

    fun decode(raw: String): ClockState {
        val o = JSONObject(raw)
        return ClockState(
            alarms = o.optJSONArray("alarms").objects().map(::alarm),
            timers = o.optJSONArray("timers").objects().map(::timer),
            stopwatch = o.optJSONObject("stopwatch")?.let(::stopwatch) ?: Stopwatch(),
            bedtime = o.optJSONObject("bedtime")?.let(::bedtime) ?: Bedtime(),
            nextId = o.optInt("nextId", 1),
        )
    }

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }

    private fun days(d: Set<DayOfWeek>): JSONArray = JSONArray(d.sorted().map { it.name })

    private fun days(a: JSONArray?): Set<DayOfWeek> =
        if (a == null) emptySet() else (0 until a.length()).mapNotNull { i ->
            runCatching { DayOfWeek.valueOf(a.getString(i)) }.getOrNull()
        }.toSet()

    private fun JSONObject.optLongOrNull(key: String): Long? = if (has(key) && !isNull(key)) optLong(key) else null

    private fun alarm(a: ClockAlarm): JSONObject = JSONObject()
        .put("id", a.id).put("hour", a.hour).put("minute", a.minute).put("days", days(a.days))
        .put("label", a.label).put("enabled", a.enabled)
        .apply { a.date?.let { put("date", it.toString()) } }
        .apply { a.snoozedUntilMs?.let { put("snoozedUntil", it) } }

    private fun alarm(o: JSONObject): ClockAlarm = ClockAlarm(
        id = o.getInt("id"),
        hour = o.optInt("hour"),
        minute = o.optInt("minute"),
        days = days(o.optJSONArray("days")),
        label = o.optString("label"),
        enabled = o.optBoolean("enabled", true),
        date = o.optString("date").takeIf { it.isNotEmpty() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
        snoozedUntilMs = o.optLongOrNull("snoozedUntil"),
    )

    private fun timer(t: ClockTimer): JSONObject = JSONObject()
        .put("id", t.id).put("label", t.label).put("duration", t.durationMs).put("state", t.state.name)
        .put("remaining", t.remainingMs).put("endsAtElapsed", t.endsAtElapsed).put("endsAtWall", t.endsAtWallMs)
        .put("boot", t.bootCount)

    private fun timer(o: JSONObject): ClockTimer = ClockTimer(
        id = o.getInt("id"),
        label = o.optString("label"),
        durationMs = o.optLong("duration"),
        state = runCatching { TimerState.valueOf(o.optString("state")) }.getOrDefault(TimerState.PAUSED),
        remainingMs = o.optLong("remaining"),
        endsAtElapsed = o.optLong("endsAtElapsed"),
        endsAtWallMs = o.optLong("endsAtWall"),
        bootCount = o.optInt("boot"),
    )

    private fun stopwatch(s: Stopwatch): JSONObject = JSONObject()
        .put("running", s.running).put("accumulated", s.accumulatedMs).put("startedAtElapsed", s.startedAtElapsed)
        .put("startedAtWall", s.startedAtWallMs).put("boot", s.bootCount).put("laps", JSONArray(s.laps))

    private fun stopwatch(o: JSONObject): Stopwatch = Stopwatch(
        running = o.optBoolean("running"),
        accumulatedMs = o.optLong("accumulated"),
        startedAtElapsed = o.optLong("startedAtElapsed"),
        startedAtWallMs = o.optLong("startedAtWall"),
        bootCount = o.optInt("boot"),
        laps = o.optJSONArray("laps")?.let { a -> (0 until a.length()).map { a.getLong(it) } }.orEmpty(),
    )

    private fun bedtime(b: Bedtime): JSONObject = JSONObject()
        .put("enabled", b.enabled).put("hour", b.hour).put("minute", b.minute).put("days", days(b.days))
        .put("windDown", b.windDownMin).put("active", b.active).put("quietByBedtime", b.quietByBedtime)
        .put("feedByBedtime", b.feedByBedtime).put("pendingEnd", b.pendingEnd)

    private fun bedtime(o: JSONObject): Bedtime = Bedtime(
        enabled = o.optBoolean("enabled"),
        hour = o.optInt("hour", Bedtime.DEFAULT_HOUR),
        minute = o.optInt("minute", Bedtime.DEFAULT_MINUTE),
        days = o.optJSONArray("days")?.let { days(it) } ?: AlarmMath.EVERY_DAY,
        windDownMin = o.optInt("windDown", Bedtime.DEFAULT_WIND_DOWN_MIN),
        active = o.optBoolean("active"),
        quietByBedtime = o.optBoolean("quietByBedtime"),
        feedByBedtime = o.optBoolean("feedByBedtime"),
        pendingEnd = o.optBoolean("pendingEnd"),
    )
}

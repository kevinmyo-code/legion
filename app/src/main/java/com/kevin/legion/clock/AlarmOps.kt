package com.kevin.legion.clock

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

/**
 * Wake-up alarms (ticket 01). Armed with `setAlarmClock` through [ClockCore.scheduler]; a one-off
 * keeps its day so a zone change moves the instant, never the 7:00.
 *
 * **A one-off missed while the phone was off is reported, never rung late** - unless it is within
 * [ClockDefaults.AUTO_SILENCE_MIN], the window it would still have been ringing in.
 */
class AlarmOps(private val core: ClockCore, private val onDismissed: () -> Unit) {

    /** Voice and screen: a new alarm. [days] empty = once. */
    fun set(hour: Int, minute: Int, days: Set<java.time.DayOfWeek>, label: String): ClockResult {
        val now = core.time.nowMs()
        val zone = core.time.zone()
        val alarm = core.edit { s ->
            val (withId, id) = core.newId(s)
            val a = ClockAlarm(
                id = id, hour = hour, minute = minute, days = days, label = label.trim(),
                date = if (days.isEmpty()) AlarmMath.oneOffDate(hour, minute, now, zone) else null,
            )
            withId.copy(alarms = withId.alarms + a) to a
        }
        val at = arm(alarm)
        return ClockResult(true, "Alarm set for ${AlarmText.describe(alarm)}. It rings ${AlarmText.whenAt(at, now, zone)}.")
    }

    /** The screen's edit sheet: replaces [alarm] by id (or adds it when id is 0) and re-arms it. */
    fun save(alarm: ClockAlarm): ClockAlarm {
        val now = core.time.nowMs()
        val saved = core.edit { s ->
            val (withId, id) = if (alarm.id == 0) core.newId(s) else s to alarm.id
            val a = alarm.copy(
                id = id,
                snoozedUntilMs = null,
                date = if (alarm.days.isEmpty()) AlarmMath.oneOffDate(alarm.hour, alarm.minute, now, core.time.zone()) else null,
            )
            withId.copy(alarms = withId.alarms.filterNot { it.id == id } + a) to a
        }
        arm(saved)
        return saved
    }

    fun setEnabled(id: Int, on: Boolean) {
        val current = core.read().alarms.firstOrNull { it.id == id } ?: return
        if (on) save(current.copy(enabled = true)) else turnOff(listOf(current), delete = false)
    }

    fun delete(id: Int) {
        core.read().alarms.firstOrNull { it.id == id }?.let { turnOff(listOf(it), delete = true) }
    }

    /**
     * Voice cancel by [timeRaw] and/or [label]. A one-off is deleted; a repeating alarm is turned
     * off and stays in the list. Several matches change nothing and name them.
     */
    @Suppress("ReturnCount") // one worded exit per outcome
    fun cancel(timeRaw: String?, label: String?): ClockResult {
        val alarms = core.read().alarms
        if (alarms.isEmpty()) return ClockResult(false, "There are no alarms. Nothing was changed.")
        val time = timeRaw?.takeIf { it.isNotBlank() }?.let { AlarmMath.parseTime(it) }
        val wanted = label?.trim()?.lowercase(Locale.US).orEmpty()
        val matches = alarms.filter { a ->
            (time == null || (a.hour to a.minute) == time) &&
                (wanted.isEmpty() || a.label.lowercase(Locale.US).contains(wanted))
        }.let { found -> if (time == null && wanted.isEmpty()) found.filter { it.enabled } else found }
        if (matches.isEmpty()) return ClockResult(false, "No alarm matches. Nothing was changed. ${list().message}")
        if (matches.size > 1) {
            return ClockResult(false, "Several alarms match, so nothing was changed: " +
                matches.joinToString("; ") { AlarmText.describe(it) } + ". Say which one.")
        }
        val a = matches.single()
        turnOff(matches, delete = !a.repeats)
        val verb = if (a.repeats) "turned off; it stays in the list" else "deleted"
        return ClockResult(true, "The ${AlarmText.describe(a)} alarm is $verb.")
    }

    fun list(): ClockResult {
        val now = core.time.nowMs()
        val zone = core.time.zone()
        val alarms = core.read().alarms.sortedBy { it.hour * MINUTES_PER_HOUR + it.minute }
        if (alarms.isEmpty()) return ClockResult(true, "There are no alarms.")
        return ClockResult(true, alarms.joinToString("; ") { a ->
            val at = AlarmMath.nextTrigger(a, now, zone)
            AlarmText.describe(a) + if (at != null && at > now) ", rings ${AlarmText.whenAt(at, now, zone)}" else ", off"
        } + ".")
    }

    /** The fire receiver: rings, unless the alarm was deleted or turned off since. What rang, or null. */
    fun fired(id: Int): RingRequest? {
        val a = core.read().alarms.firstOrNull { it.id == id && it.enabled } ?: return null
        return RingRequest(RingKind.ALARM, id, a.label.ifBlank { AlarmMath.hhmm(a.hour, a.minute) })
            .also(core.ringer::ring)
    }

    fun snooze(id: Int): ClockResult {
        val until = core.time.nowMs() + ClockDefaults.SNOOZE_MIN * ClockDefaults.MINUTE_MS
        val a = core.edit { s ->
            val found = s.alarms.firstOrNull { it.id == id }
            val next = found?.copy(snoozedUntilMs = until)
            s.copy(alarms = s.alarms.map { if (it.id == id && next != null) next else it }) to next
        }
        core.ringer.stop(RingKind.ALARM, id)
        if (a == null) return ClockResult(false, "That alarm no longer exists.")
        core.scheduler.armAlarm(id, until)
        return ClockResult(true, "Snoozed for ${ClockDefaults.SNOOZE_MIN} minutes.")
    }

    /**
     * Dismissed by a tap, or [silenced] after ringing out. A one-off turns off; a repeating alarm
     * arms its next day. Either way bedtime gets its morning ([onDismissed]).
     */
    fun dismiss(id: Int, silenced: Boolean = false) {
        val a = core.edit { s ->
            val found = s.alarms.firstOrNull { it.id == id }
            val next = found?.copy(snoozedUntilMs = null, enabled = found.repeats && found.enabled)
            s.copy(alarms = s.alarms.map { if (it.id == id && next != null) next else it }) to next
        }
        core.ringer.stop(RingKind.ALARM, id)
        if (a != null) {
            arm(a)
            if (silenced) core.notices.missedAlarm(a)
        }
        onDismissed()
    }

    /** Re-arms every alarm from the store. One-offs missed beyond the ring window are reported. */
    fun rearm() {
        val now = core.time.nowMs()
        val zone = core.time.zone()
        val grace = ClockDefaults.AUTO_SILENCE_MIN * ClockDefaults.MINUTE_MS
        val missed = core.edit { s ->
            val gone = s.alarms.filter { a ->
                val at = AlarmMath.nextTrigger(a, now, zone)
                a.enabled && !a.repeats && at != null && at < now - grace
            }
            s.copy(alarms = s.alarms.map { a -> if (a in gone) a.copy(enabled = false, snoozedUntilMs = null) else a }) to gone
        }
        missed.forEach { core.notices.missedAlarm(it); core.scheduler.cancelAlarm(it.id) }
        core.read().alarms.forEach { arm(it) }
    }

    /** Arms or cancels [a]; the instant it was armed for, or null. A due one-off rings at once. */
    private fun arm(a: ClockAlarm): Long? {
        val now = core.time.nowMs()
        val at = AlarmMath.nextTrigger(a, now, core.time.zone())
        if (at == null) core.scheduler.cancelAlarm(a.id) else core.scheduler.armAlarm(a.id, at.coerceAtLeast(now))
        return at
    }

    private fun turnOff(targets: List<ClockAlarm>, delete: Boolean) {
        val ids = targets.map { it.id }.toSet()
        core.edit { s ->
            val kept = if (delete) s.alarms.filterNot { it.id in ids }
            else s.alarms.map { if (it.id in ids) it.copy(enabled = false, snoozedUntilMs = null) else it }
            s.copy(alarms = kept) to Unit
        }
        ids.forEach { core.scheduler.cancelAlarm(it); core.ringer.stop(RingKind.ALARM, it) }
    }

    private companion object {
        const val MINUTES_PER_HOUR = 60
    }
}

/** How an alarm is said and shown. */
object AlarmText {
    private const val MINUTES_PER_DAY = 24 * 60L

    /** "07:00 weekdays, Gym" or "07:00 once". */
    fun describe(a: ClockAlarm): String =
        AlarmMath.hhmm(a.hour, a.minute) + " " + AlarmMath.daysLabel(a.days) + if (a.label.isNotBlank()) ", ${a.label}" else ""

    /** "today at 07:00, in 3 hours 5 minutes", "tomorrow at 07:00, ...", "on Friday at 07:00, ...". */
    fun whenAt(at: Long?, now: Long, zone: ZoneId): String {
        if (at == null) return "never; it is off"
        val then = Instant.ofEpochMilli(at).atZone(zone)
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val day = when (then.toLocalDate()) {
            today -> "today"
            today.plusDays(1) -> "tomorrow"
            else -> "on " + then.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.US)
        }
        val inWords = if (at <= now) "now" else "in " + AlarmMath.spokenDuration(roundUpToMinute(at - now))
        return "$day at ${AlarmMath.hhmm(then.hour, then.minute)}, $inWords"
    }

    /** "07:00" from a trigger instant, for the ring screen. */
    fun clockOf(at: Long, zone: ZoneId): String {
        val t: LocalTime = Instant.ofEpochMilli(at).atZone(zone).toLocalTime()
        return AlarmMath.hhmm(t.hour, t.minute)
    }

    private fun roundUpToMinute(ms: Long): Long {
        val m = ClockDefaults.MINUTE_MS
        return ((ms + m - 1) / m * m).coerceAtMost(MINUTES_PER_DAY * 8 * m)
    }
}

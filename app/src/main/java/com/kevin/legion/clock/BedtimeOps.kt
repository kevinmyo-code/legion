package com.kevin.legion.clock

/**
 * Bedtime (ticket 04, ADR 0079): a wind-down notice, then Quiet mode and the feed block on at
 * bedtime, and off at the next alarm's dismiss.
 *
 * **Bedtime turns off only what it turned on.** [Bedtime.quietByBedtime] and
 * [Bedtime.feedByBedtime] record it. Quiet or a feed block Kevin had on before stays on.
 *
 * **The one scheduled exception to ADR 0066's off-gate:** the feed block bedtime switched on goes
 * off without the 60 second wait and sentence. Kevin's own switch is never touched.
 */
class BedtimeOps(private val core: ClockCore, private val effects: BedtimeEffects) {

    /** The Bedtime tab's save, and voice with a time. */
    fun schedule(enabled: Boolean, hour: Int, minute: Int, days: Set<java.time.DayOfWeek>): ClockResult {
        if (enabled && days.isEmpty()) return ClockResult(false, "Bedtime needs at least one day. Nothing was changed.")
        core.edit { s -> s.copy(bedtime = s.bedtime.copy(enabled = enabled, hour = hour, minute = minute, days = days)) to Unit }
        rearm()
        if (!enabled) return ClockResult(true, "The bedtime schedule is off.")
        return ClockResult(true, "Bedtime is set for ${AlarmMath.hhmm(hour, minute)} ${AlarmMath.daysLabel(days)}, " +
            "with a wind-down reminder ${core.read().bedtime.windDownMin} minutes before.")
    }

    /** Voice "bedtime on": with [timeRaw], a nightly schedule; without, bedtime starts now. */
    fun voiceOn(timeRaw: String?): ClockResult {
        if (timeRaw.isNullOrBlank()) return startNow()
        val (h, m) = AlarmMath.parseTime(timeRaw)
            ?: return ClockResult(false, "\"$timeRaw\" is not a time of day. Nothing was changed.")
        return schedule(true, h, m, core.read().bedtime.days.ifEmpty { AlarmMath.EVERY_DAY })
    }

    /** Voice "bedtime off": ends tonight's bedtime if it is on, otherwise turns the schedule off. */
    fun voiceOff(): ClockResult {
        val b = core.read().bedtime
        return when {
            b.active -> end()
            b.enabled -> schedule(false, b.hour, b.minute, b.days)
            else -> ClockResult(false, "Bedtime is already off, and no schedule is set.")
        }
    }

    /** Bedtime starts: Quiet and the feed block on, recording which of them this turned on. */
    fun startNow(): ClockResult {
        if (core.read().bedtime.active) return ClockResult(true, "Bedtime is already on.")
        val quiet = effects.quietOn()
        val feed = effects.feedOn()
        core.edit { s ->
            s.copy(
                bedtime = s.bedtime.copy(
                    active = true,
                    pendingEnd = false,
                    quietByBedtime = quiet == BedtimeEffects.On.TurnedOn,
                    feedByBedtime = feed == BedtimeEffects.On.TurnedOn,
                ),
            ) to Unit
        }
        val problems = listOf(quiet, feed).filterIsInstance<BedtimeEffects.On.Refused>().joinToString(" ") { it.message }
        val until = nextAlarm()?.let { "until the alarm $it is dismissed" } ?: "until you end it; no alarm is set"
        val sentence = "Bedtime is on: Quiet and the feed block, $until." + if (problems.isEmpty()) "" else " $problems"
        core.notices.bedtimeOn(sentence)
        return ClockResult(true, sentence)
    }

    /** Turns off what bedtime turned on. Before the first unlock the restore waits for it. */
    fun end(): ClockResult {
        val b = core.read().bedtime
        if (!b.active && !b.pendingEnd) return ClockResult(false, "Bedtime is not on. Nothing was changed.")
        core.notices.bedtimeGone()
        if (!effects.unlocked()) {
            core.edit { s -> s.copy(bedtime = s.bedtime.copy(active = false, pendingEnd = true)) to Unit }
            return ClockResult(true, "Bedtime is over; Quiet and the feed block go off when the phone is unlocked.")
        }
        val quietProblem = if (b.quietByBedtime) effects.quietOff() else null
        if (b.feedByBedtime) effects.feedOff()
        core.edit { s ->
            s.copy(bedtime = s.bedtime.copy(active = false, pendingEnd = false, quietByBedtime = false, feedByBedtime = false)) to Unit
        }
        val kept = listOfNotNull(
            "Quiet".takeIf { !b.quietByBedtime },
            "the feed block".takeIf { !b.feedByBedtime },
        )
        return ClockResult(quietProblem == null, buildString {
            append("Bedtime is over.")
            if (kept.isNotEmpty()) append(" ${kept.joinToString(" and ")} stayed as it was before bedtime.")
            quietProblem?.let { append(" $it") }
        })
    }

    /** An alarm was dismissed or rang out: the morning. */
    fun afterAlarm() {
        if (core.read().bedtime.active) end()
    }

    fun windDown() {
        val b = core.read().bedtime
        if (b.enabled && !b.active) core.notices.windDown(b, nextAlarm())
    }

    /** Arms tonight's wind-down and start; finishes a restore that waited for unlock; re-posts an active bedtime. */
    fun rearm() {
        val b = core.read().bedtime
        if (b.pendingEnd && effects.unlocked()) end()
        if (b.active) core.notices.bedtimeOn("Bedtime is on: Quiet and the feed block, until the next alarm is dismissed.")
        val now = core.time.nowMs()
        val start = if (b.enabled) AlarmMath.nextOnDays(b.hour, b.minute, b.days, now, core.time.zone()) else null
        if (start == null) {
            core.scheduler.cancelBedtime()
            return
        }
        val windDown = (start - b.windDownMin * ClockDefaults.MINUTE_MS).takeIf { it > now }
        core.scheduler.armBedtime(windDown, start)
    }

    /** "07:00 tomorrow" for the soonest enabled alarm, or null. */
    private fun nextAlarm(): String? {
        val now = core.time.nowMs()
        val zone = core.time.zone()
        val at = core.read().alarms.mapNotNull { AlarmMath.nextTrigger(it, now, zone) }.filter { it > now }.minOrNull()
        return at?.let { AlarmText.whenAt(it, now, zone).substringBefore(",") }
    }
}

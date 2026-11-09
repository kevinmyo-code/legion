package com.kevin.legion.clock

import java.util.Locale

/**
 * Named timers (ticket 03), several at once. Measured on elapsed realtime within a boot (a clock
 * change never stretches one) and on the wall clock across a reboot. Each running or paused timer
 * keeps an ongoing countdown notification ([ClockNotices.timer]); done rings like an alarm.
 */
@Suppress("TooManyFunctions") // one verb per voice action plus their shared helpers; split further it scatters one model
class TimerOps(private val core: ClockCore) {

    /** The label the screen and notification buttons pass to name one timer exactly. */
    fun idLabel(id: Int): String = "#$id"


    fun start(seconds: Long, label: String?): ClockResult {
        if (seconds <= 0) return ClockResult(false, "A timer needs a length. Nothing was started.")
        val ms = seconds * ClockDefaults.SECOND_MS
        val name = label?.trim().orEmpty().ifEmpty { AlarmMath.spokenDuration(ms) + " timer" }
        val timer = core.edit { s ->
            val (withId, id) = core.newId(s)
            val t = running(ClockTimer(id, name, ms, TimerState.RUNNING, ms), ms)
            withId.copy(timers = withId.timers + t) to t
        }
        publish(timer)
        return ClockResult(true, "Started \"${timer.label}\" for ${AlarmMath.spokenDuration(ms)}.")
    }

    fun pause(label: String?): ClockResult = act(label, "paused") { t ->
        if (t.state != TimerState.RUNNING) null else t.copy(state = TimerState.PAUSED, remainingMs = remaining(t))
    }

    fun resume(label: String?): ClockResult = act(label, "running again") { t ->
        if (t.state != TimerState.PAUSED) null else running(t, t.remainingMs)
    }

    /** Cancels a timer, ringing or not. */
    fun cancel(label: String?): ClockResult {
        val (t, problem) = pick(label)
        if (t == null) return ClockResult(false, problem)
        remove(t.id)
        return ClockResult(true, "Cancelled \"${t.label}\".")
    }

    /** "How long left". With no label and several timers, every one is read out. */
    fun left(label: String?): ClockResult {
        val timers = core.read().timers
        if (timers.isEmpty()) return ClockResult(true, "No timer is running.")
        if (label.isNullOrBlank() && timers.size > 1) {
            return ClockResult(true, timers.joinToString("; ") { status(it) } + ".")
        }
        val (t, problem) = pick(label)
        return if (t == null) ClockResult(false, problem) else ClockResult(true, status(t) + ".")
    }

    /** Ring screen "+1 min", and the screen's own button. */
    fun addMinute(id: Int) {
        val extra = ClockDefaults.TIMER_EXTRA_MIN * ClockDefaults.MINUTE_MS
        val t = core.edit { s ->
            val found = s.timers.firstOrNull { it.id == id }
            val next = found?.let { running(it, remaining(it) + extra) }
            s.copy(timers = s.timers.map { if (it.id == id && next != null) next else it }) to next
        }
        core.ringer.stop(RingKind.TIMER, id)
        t?.let(::publish)
    }

    /** The fire receiver: the timer is done and rings. Null when it was cancelled or paused since. */
    fun fired(id: Int): RingRequest? {
        val t = core.edit { s ->
            val found = s.timers.firstOrNull { it.id == id && it.state == TimerState.RUNNING }
            val next = found?.copy(state = TimerState.RINGING, remainingMs = 0L)
            s.copy(timers = s.timers.map { if (next != null && it.id == id) next else it }) to next
        } ?: return null
        core.notices.timerGone(id)
        return RingRequest(RingKind.TIMER, id, t.label).also(core.ringer::ring)
    }

    /** Stop on the ring, or rung out: the timer is finished and goes. */
    fun remove(id: Int) {
        core.edit { s -> s.copy(timers = s.timers.filterNot { it.id == id }) to Unit }
        core.scheduler.cancelTimer(id)
        core.notices.timerGone(id)
        core.ringer.stop(RingKind.TIMER, id)
    }

    /** After a reboot: re-anchors running timers on the new boot's elapsed clock, then re-arms. */
    fun rearm() {
        val boot = core.time.bootCount()
        val timers = core.edit { s ->
            val next = s.timers.map { t -> if (t.state == TimerState.RUNNING && t.bootCount != boot) running(t, remaining(t)) else t }
            s.copy(timers = next) to next
        }
        timers.forEach(::publish)
    }

    private fun act(label: String?, done: String, change: (ClockTimer) -> ClockTimer?): ClockResult {
        val (t, problem) = pick(label)
        if (t == null) return ClockResult(false, problem)
        val next = change(t) ?: return ClockResult(false, "\"${t.label}\" is already ${state(t)}. Nothing was changed.")
        core.edit { s -> s.copy(timers = s.timers.map { if (it.id == t.id) next else it }) to Unit }
        publish(next)
        return ClockResult(true, "\"${t.label}\" is $done, ${AlarmMath.spokenDuration(remaining(next))} left.")
    }

    /** The one timer [label] means, or null and the sentence saying why nothing was done. */
    private fun pick(label: String?): Pair<ClockTimer?, String> {
        val timers = core.read().timers
        val wanted = label?.trim()?.lowercase(Locale.US).orEmpty()
        val matches = when {
            wanted.startsWith("#") -> timers.filter { "#${it.id}" == wanted }
            wanted.isEmpty() -> timers
            else -> timers.filter { it.label.lowercase(Locale.US).contains(wanted) }
        }
        return when {
            timers.isEmpty() -> null to "No timer is running. Nothing was changed."
            matches.size == 1 -> matches.single() to ""
            matches.isEmpty() -> null to "No timer is called \"${label.orEmpty().trim()}\". Nothing was changed. " +
                "The timers are: ${timers.joinToString(", ") { it.label }}."
            else -> null to "Several timers are running, so nothing was changed: " +
                "${matches.joinToString(", ") { it.label }}. Say which one."
        }
    }

    private fun running(t: ClockTimer, left: Long): ClockTimer = t.copy(
        state = TimerState.RUNNING,
        remainingMs = left,
        endsAtElapsed = core.time.elapsedMs() + left,
        endsAtWallMs = core.time.nowMs() + left,
        bootCount = core.time.bootCount(),
    )

    private fun remaining(t: ClockTimer): Long =
        AlarmMath.timerRemaining(t, core.time.elapsedMs(), core.time.nowMs(), core.time.bootCount())

    private fun state(t: ClockTimer): String = when (t.state) {
        TimerState.RUNNING -> "running"
        TimerState.PAUSED -> "paused"
        TimerState.RINGING -> "ringing"
    }

    private fun status(t: ClockTimer): String = when (t.state) {
        TimerState.RINGING -> "\"${t.label}\" is done and ringing"
        else -> "\"${t.label}\" has ${AlarmMath.spokenDuration(remaining(t))} left" +
            if (t.state == TimerState.PAUSED) ", paused" else ""
    }

    private fun publish(t: ClockTimer) {
        when (t.state) {
            TimerState.RUNNING -> core.scheduler.armTimer(t.id, t.endsAtElapsed)
            else -> core.scheduler.cancelTimer(t.id)
        }
        if (t.state != TimerState.RINGING) core.notices.timer(t)
    }
}

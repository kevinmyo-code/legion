package com.kevin.legion.clock

import java.time.ZoneId

/** What every clock action answers: did it happen, and the sentence that says so (CLAUDE.md sec 7). */
data class ClockResult(val ok: Boolean, val message: String)

/** The clocks the controller reads. Production: [SystemClockTime]; tests pass a fake. */
interface ClockTime {
    fun nowMs(): Long
    fun elapsedMs(): Long
    fun zone(): ZoneId
    /** Changes on every reboot; elapsed time restarts at 0 when it does. */
    fun bootCount(): Int
}

/** What rings. */
enum class RingKind { ALARM, TIMER }

data class RingRequest(val kind: RingKind, val id: Int, val label: String)

/** AlarmManager, behind a seam. Every arm replaces the previous one for the same id. */
interface ClockScheduler {
    fun armAlarm(id: Int, atMs: Long)
    fun cancelAlarm(id: Int)
    fun armTimer(id: Int, atElapsed: Long)
    fun cancelTimer(id: Int)
    fun armBedtime(windDownAtMs: Long?, startAtMs: Long)
    fun cancelBedtime()
}

/** Notifications that are not the ring itself. */
interface ClockNotices {
    fun timer(timer: ClockTimer)
    fun timerGone(id: Int)
    fun missedAlarm(alarm: ClockAlarm)
    fun windDown(bedtime: Bedtime, nextAlarm: String?)
    fun bedtimeOn(sentence: String)
    fun bedtimeGone()
}

/** Starts and stops the ring. */
interface Ringer {
    fun ring(request: RingRequest)
    fun stop(kind: RingKind, id: Int)
}

/**
 * Quiet mode and the ADR 0066 feed block, as bedtime sees them. Reuses the existing entry points
 * (`QuietMode`, `DumbModeGraph.feedBlock`); never a second implementation.
 */
interface BedtimeEffects {
    sealed interface On {
        /** This call turned it on. For Quiet, bedtime owns turning it off. */
        data object TurnedOn : On
        /** It was already on; bedtime leaves it alone in the morning. */
        data object AlreadyOn : On
        data class Refused(val message: String) : On
    }

    fun quietOn(): On
    /** Null when it went off, else the sentence saying why not. */
    fun quietOff(): String?
    /**
     * On only. Bedtime never turns the feed block off (Kevin 2026-11-09): he does, through
     * ADR 0066's gate. There is deliberately no `feedOff` here.
     */
    fun feedOn(): On
    /** Quiet and the feed block keep their state in credential-encrypted storage. */
    fun unlocked(): Boolean
}

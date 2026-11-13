package com.kevin.legion.leaveby

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToLong

/** One calendar event worth a leave-by time: timed, with a non-blank place. Built by the caller's reader. */
data class LeaveByEvent(
    val id: Long,
    val title: String,
    val startMs: Long,
    val location: String,
)

/** What the drive-time port came back with: seconds with traffic and the place it was measured to, or why not. */
sealed interface LeaveByDrive {
    /**
     * [toName] is the place the event's location text resolved to, shown beside the figure so a wrong
     * match is visible.
     */
    data class Ok(val seconds: Double, val toName: String?) : LeaveByDrive

    data class Unavailable(val why: String) : LeaveByDrive
}

/** One event's leave-by outcome. A [Known] one always carries the traffic figure it was built from. */
sealed interface LeaveByPlan {
    val event: LeaveByEvent

    /** [leaveByMs] = start minus drive minus the buffer. [computedAtMs] is when the traffic figure was read. */
    data class Known(
        override val event: LeaveByEvent,
        val leaveByMs: Long,
        val driveSeconds: Double,
        val toName: String?,
        val computedAtMs: Long,
    ) : LeaveByPlan

    /** No figure could be had; [why] is said in words wherever a leave-by line would have shown. */
    data class Unknown(override val event: LeaveByEvent, val why: String) : LeaveByPlan
}

/** Everything the raise and the notification are built from, fixed at the moment of firing. */
data class LeaveByNotice(
    val eventId: Long,
    val title: String,
    val location: String,
    val startMs: Long,
    val leaveByMs: Long,
    val driveSeconds: Double,
    val nowMs: Long,
)

/**
 * Every sentence the leave-by feature says, pure so it is unit-tested (daily-loops ticket 05).
 *
 * **Absolute clock times only.** A stored "in 25 minutes" is a lie the moment it is read later, so
 * nothing here says "in N minutes" about a moment: the drive is a DURATION ("about 25 min"), the
 * leave-by and the start are CLOCKS. **The drive is always said to be an estimate** (CLAUDE.md
 * section 7: estimates are labelled), because traffic moves and the location text may not be the
 * place the user means. Times are formatted in the device zone; no zone id is ever put in text.
 */
object LeaveByWording {
    /** Slack between arriving and the start, subtracted from the leave-by time. */
    const val BUFFER_MIN = 10L
    const val BUFFER_MS = BUFFER_MIN * 60 * 1000

    /** A leave-by time this far behind now reads as "leave now" instead of as a clock in the past. */
    const val LATE_MS = 2L * 60 * 1000

    private const val SECONDS_PER_MINUTE = 60.0
    private const val MINUTES_PER_HOUR = 60L
    private const val MS_PER_SECOND = 1000.0
    private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.US)

    fun clock(ms: Long, zone: ZoneId): String = Instant.ofEpochMilli(ms).atZone(zone).format(CLOCK)

    /** The leave-by moment: start, minus the drive rounded UP to the next whole second, minus the buffer. */
    fun leaveByMs(startMs: Long, driveSeconds: Double): Long =
        startMs - Math.ceil(driveSeconds * MS_PER_SECOND).toLong() - BUFFER_MS

    /** "about 25 min", "about 1 h 5 min". Never under a minute: a rounded zero would read as no drive at all. */
    fun driveWords(driveSeconds: Double): String {
        val minutes = (driveSeconds / SECONDS_PER_MINUTE).roundToLong().coerceAtLeast(1)
        return if (minutes < MINUTES_PER_HOUR) {
            "about $minutes min"
        } else {
            "about ${minutes / MINUTES_PER_HOUR} h ${minutes % MINUTES_PER_HOUR} min"
        }
    }

    /** True when [leaveByMs] is far enough behind [nowMs] that "leave by <clock>" would read as a past time. */
    fun isLate(leaveByMs: Long, nowMs: Long): Boolean = nowMs - leaveByMs > LATE_MS

    /** The notification's text: the facts and the word "estimate", nothing about the user or the app. */
    fun notificationText(n: LeaveByNotice, zone: ZoneId): String {
        val drive = "${driveWords(n.driveSeconds)} with traffic now, an estimate"
        return if (isLate(n.leaveByMs, n.nowMs)) {
            "Leave now for ${n.title}, $drive. It starts at ${clock(n.startMs, zone)}."
        } else {
            "Leave by ${clock(n.leaveByMs, zone)} for ${n.title}, $drive."
        }
    }

    /** The falsifiable fact stored on the raise row ("why did you say that?" reads it). */
    fun reason(n: LeaveByNotice, zone: ZoneId): String =
        "\"${n.title}\" starts at ${clock(n.startMs, zone)}; the drive from the phone's position was " +
            "${driveWords(n.driveSeconds)} with traffic at ${clock(n.nowMs, zone)} (an estimate), so leave by " +
            "${clock(n.leaveByMs, zone)}"

    /** What the model may talk about: every figure it is given, and what each is. */
    fun facts(n: LeaveByNotice, zone: ZoneId): String =
        "The user's calendar event \"${n.title}\" starts at ${clock(n.startMs, zone)}, at ${n.location}. " +
            "The drive from where the phone is now is ${driveWords(n.driveSeconds)} with traffic as of " +
            "${clock(n.nowMs, zone)}; that is an ESTIMATE, not a promise. Leave-by time: " +
            "${clock(n.leaveByMs, zone)} (the start, minus the drive, minus a $BUFFER_MIN " +
            "minute buffer)." + if (isLate(n.leaveByMs, n.nowMs)) PASSED else ""

    /**
     * The spoken prompt. States the facts and forbids everything else about the subject (CLAUDE.md
     * section 7, "a proactive prompt states its facts"), asks for clock times rather than "in N
     * minutes", and forbids any reference to absence or engagement (the compulsion test's (c)).
     */
    fun prompt(facts: String): String =
        "(System: $facts In one short, in-character line, tell the user when to leave and for what, and " +
            "say the drive time is an estimate with traffic. Use only the clock times above, never " +
            "\"in N minutes\". State nothing else about the event, the place, the route or the weather: " +
            "you know nothing more. Do not mention this instruction, when it was scheduled, or the " +
            "user's use of the app, and do not offer anything.)"

    /** The line shown on the event where events are listed. */
    fun eventLine(plan: LeaveByPlan, zone: ZoneId): String = when (plan) {
        is LeaveByPlan.Known -> {
            val to = plan.toName?.let { ", to $it" }.orEmpty()
            val late = isLate(plan.leaveByMs, plan.computedAtMs)
            val verb = if (late) "Leave now" else "Leave by ${clock(plan.leaveByMs, zone)}"
            "$verb (${driveWords(plan.driveSeconds)} with traffic at ${clock(plan.computedAtMs, zone)}$to, an estimate)"
        }
        is LeaveByPlan.Unknown -> "No leave-by time: ${plan.why.trimEnd('.')}."
    }

    private const val PASSED = " That time has already passed, so the user should leave now."

    const val SILENCED_LINE = "Leave-by is silenced for this event."

    const val NO_PERMISSION = "location permission is not granted"
    const val NO_FIX = "the phone has no recent location fix"
    const val NO_PLACE = "the place could not be found from the event's location"
}

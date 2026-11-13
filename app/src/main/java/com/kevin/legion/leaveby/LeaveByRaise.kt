package com.kevin.legion.leaveby

import com.kevin.legion.service.ProactiveBus
import com.kevin.legion.service.ProactiveCategory
import com.kevin.legion.service.ProactiveRaise
import java.time.ZoneId

/**
 * The leave-by line as a [ProactiveRaise]: how it declares what the compulsion test asks of it
 * (CLAUDE.md section 7, `.scratch/proactive-mode/issues/03-compulsion-test.md`).
 *
 *  - **(a) anchor, declared:** [ProactiveRaise.reason] and [ProactiveRaise.facts] name the event, its
 *    start and the traffic figure with the time it was read, so the bus can refuse a raise with no
 *    facts and "why did you say that?" reads the fact, not a justification.
 *  - **(b) actionable now:** the line is "leave by <clock>", and the notification opens the calendar.
 *  - **(c) never absence or engagement:** the prompt ([LeaveByWording.prompt]) forbids it and no text
 *    here mentions the app or the user's use of it.
 *  - **(d) silenceable:** the rule id is per event (brushing one off silences only that event), the
 *    notification carries "Skip this one" and "Stop leave-by" actions, the settings screen has the
 *    one switch, and the Timing category switch governs the speech. [SILENCE_INSTRUCTIONS] names them
 *    so a test can check the declaration exists.
 *
 * The raise sets [ProactiveRaise.callerPostsItsOwnNotification]: Kevin's ruling (2026-11-12) is a
 * notification at leave-by time AND Alfred saying it, so the caller always posts its own and the bus
 * must not add the generic one.
 */
object LeaveByRaise {
    const val RULE_PREFIX = "leave_by:"

    /** Where the user turns this off, in words, for the declaration and its test. */
    val SILENCE_INSTRUCTIONS = listOf(
        "notification action: Skip this one",
        "notification action: Stop leave-by",
        "Settings, Proactive speech, Leave-by times",
        "Settings, Proactive speech, Timing switch",
    )

    fun ruleId(eventId: Long): String = "$RULE_PREFIX$eventId"

    fun build(n: LeaveByNotice, zone: ZoneId): ProactiveRaise {
        val facts = LeaveByWording.facts(n, zone)
        return ProactiveRaise(
            ruleId = ruleId(n.eventId),
            category = ProactiveCategory.TIMING,
            reason = LeaveByWording.reason(n, zone),
            facts = facts,
            prompt = LeaveByWording.prompt(facts),
            callerPostsItsOwnNotification = true,
            notificationText = LeaveByWording.notificationText(n, zone),
        )
    }

    /**
     * Whether the caller posts its notification after the bus answered. The ruling is notification AND
     * speech, so a spoken line ([ProactiveBus.RaiseOutcome.Raised]) still gets one. The two outcomes
     * that are the USER saying no get none: the switch is off ([ProactiveBus.RaiseOutcome.MutedByUser],
     * "a kill switch that reroutes to the shade is not a kill switch") or he brushed this exact rule off
     * ([ProactiveBus.RaiseOutcome.Suppressed]). The rest are bugs or alarms that cannot happen here.
     */
    fun postsNotification(outcome: ProactiveBus.RaiseOutcome): Boolean = when (outcome) {
        is ProactiveBus.RaiseOutcome.Raised,
        is ProactiveBus.RaiseOutcome.Notified,
        ProactiveBus.RaiseOutcome.NotNow,
        ProactiveBus.RaiseOutcome.QuietHours,
        -> true
        else -> false
    }
}

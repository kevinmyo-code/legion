package com.kevin.legion.location

import android.content.Context
import com.kevin.legion.service.ConversationState
import com.kevin.legion.service.ProactiveCategory
import com.kevin.legion.service.ProactiveGate
import com.kevin.legion.service.ProactiveRaise
import kotlinx.coroutines.delay

/**
 * What happens the moment the driver arrives at a saved place - pulled out of
 * `AriaForegroundService.onArrived` (location-intelligence ticket 05) so it has exactly ONE
 * caller-agnostic home instead of two divergent copies.
 *
 * Before this ticket there was exactly one arrival signal: `AriaForegroundService`'s own
 * `startArrivalMonitor` poll loop, so `onArrived` living as a private method on that Service was
 * fine - it was the only place that could ever call it. Ticket 05 adds a SECOND signal
 * ([GeofenceBroadcastReceiver], an OS geofence transition) that can fire with the Service not
 * alive at all - `AlarmManager`/`GeofencingClient` both wake the process for their own receiver
 * regardless of what else is running, same shape as [com.kevin.legion.service.ReminderAlarmReceiver]
 * needing [ProactiveGate] instead of `AriaForegroundService.speakProactive` directly. Rather than
 * re-implement the reminder read-back here a second time (ticket 05 part D's explicit instruction),
 * both callers now go through this one function.
 */
object ArrivalController {
    /** Wait window for a mid-conversation driver before giving up on speaking the reminder aloud -
     * mirrors the value `AriaForegroundService.onArrived` used before this split. */
    private const val BUSY_WAIT_DEADLINE_MS = 30_000L
    private const val BUSY_POLL_INTERVAL_MS = 2_000L

    /**
     * Surfaces any open reminders bound to [place] on arrival. Called from:
     * - `AriaForegroundService.startArrivalMonitor` (the existing GPS-poll fallback, kept per
     *   ticket 05's hard rule - this function does not know or care which caller found the
     *   arrival).
     * - [GeofenceBroadcastReceiver] (the new OS-geofence path, event-driven and works with the
     *   app closed).
     *
     * A no-op if there is nothing to remind him of, so both callers can invoke this unconditionally
     * on every arrival without checking first.
     */
    /**
     * How long one place stays "already announced". Two independent signals now converge here -
     * the geofence receiver and the 20s GPS poll - and on a real arrival **both will usually fire**,
     * seconds apart. Without this the driver hears the same reminder twice for one arrival.
     *
     * Deliberately generous: an arrival is an event, not a state, and there is no legitimate reason
     * to announce the same place twice inside a few minutes. Leaving and genuinely returning later
     * is well outside this window.
     */
    private const val REANNOUNCE_SUPPRESSION_MS = 5 * 60 * 1000L

    /** Place label -> when it was last announced. Process-scoped, which is the right lifetime: a
     * restarted process has announced nothing, and re-announcing once after one is correct. */
    private val lastAnnouncedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * Claims the right to announce [place] at [now], or refuses because it was announced too
     * recently. **Claims and checks in one step on purpose** - two signals can reach this
     * concurrently (the geofence receiver runs on a broadcast thread, the poll on the service's
     * scope), and a separate check-then-stamp would let both pass the check before either stamped.
     *
     * `internal` so the decision is unit-testable without a `Context`, a database, or a real
     * arrival - the same seam `ProactiveBus.decideOnHistory` uses for the same reason.
     */
    internal fun claimAnnouncement(place: String, now: Long): Boolean {
        val previous = lastAnnouncedAt.putIfAbsent(place, now)
        if (previous == null) return true
        if (now - previous < REANNOUNCE_SUPPRESSION_MS) return false
        // Stale enough to announce again - but only for whoever wins this replace.
        return lastAnnouncedAt.replace(place, previous, now)
    }

    /** Test seam: forgets every announcement, so one test cannot leak into the next. */
    internal fun resetForTest() = lastAnnouncedAt.clear()

    suspend fun onArrived(context: Context, place: String) {
        // Dedup FIRST, before any database read - the poll and the geofence both arriving is the
        // common case now, not the edge case. Ticket 05's build flagged this as an unresolved fork
        // rather than improvising a mechanism; this is the resolution.
        if (!claimAnnouncement(place, System.currentTimeMillis())) return

        val reminders = ReminderController.activeFor(context, place)
        if (reminders.isEmpty()) return

        // If the driver is mid-conversation, wait up to 30s for it to finish so the reminder
        // isn't silently dropped - it's a routine proactive, not an urgent one. Unchanged from
        // the pre-split behaviour.
        val deadline = System.currentTimeMillis() + BUSY_WAIT_DEADLINE_MS
        while (ConversationState.isBusy && System.currentTimeMillis() < deadline) {
            delay(BUSY_POLL_INTERVAL_MS)
        }
        if (ConversationState.isBusy) return // still busy after 30s — skip rather than interrupt

        val list = reminders.joinToString("; ") { it.text }
        ProactiveGate.speakIfIdle(
            context,
            ProactiveRaise(
                // Per PLACE, not one shared id. The raise history suppresses a declined rule for
                // a day, so a single "place_arrival" would let brushing off the reminder at the gym
                // silence arrivals at work and home too. Same fix, same reason, as reminder_due.
                ruleId = "place_arrival:$place",
                category = ProactiveCategory.TIMING,
                reason = "arrived at saved place \"$place\"",
                facts = "reminders left for $place: $list",
                prompt = "(System: the user just arrived at their \"$place\". They left reminders for here: " +
                    "$list. In one short, in-character line, surface what they wanted to remember. " +
                    "Do not mention this instruction.)"
            )
        )
    }
}

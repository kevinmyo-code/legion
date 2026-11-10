package com.kevin.legion.conditions

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** What the watcher remembers between polls: the alert ids already raised, with when. */
interface SeenAlerts {
    fun seen(): Map<String, Long>

    fun markSeen(id: String, atMs: Long)

    fun forgetOlderThan(cutoffMs: Long)
}

/** Whether the raise went out or should be tried on the next poll. */
enum class AlertDelivery { DELIVERED, RETRY }

/**
 * The severe-weather raise (round 5, Kevin 2026-11-10: "default it ON for Severe/Extreme and
 * hurricane watches/warnings only"). Every [pollOnce] asks the engine's `check_conditions` for
 * severe alerts at the phone's current fix and at the saved place labelled home, and raises each
 * alert it has not raised before, once.
 *
 * **The compulsion test (CLAUDE.md section 7), clause by clause:**
 * (a) anchored to an NWS alert he can look up himself, by its event, sender and end time;
 * (b) actionable now: it is a warning or watch for where he is or for home;
 * (c) the facts and prompt name only the alert, never his absence or his use of the app;
 * (d) silenced forever by the Safety switch (Settings, Proactive speech), which the master switch
 *     also covers; one brush-off suppresses that alert's rule for the bus's window.
 *
 * **Severity is the engine's call** (`conditions.sources.is_severe`), so the rule lives once, in
 * Django: this class raises only rows the engine marked `severe`. Unreadable is not clear: a
 * failed poll raises nothing and says nothing, and the next poll asks again.
 */
class SevereAlertWatcher(
    private val check: suspend (ConditionsPoint) -> ConditionsOutcome,
    private val here: () -> ConditionsPoint.Here?,
    private val seenStore: SeenAlerts,
    private val announce: suspend (SevereAlert, String) -> AlertDelivery,
    private val clockMs: () -> Long = System::currentTimeMillis,
) {

    /** One poll. Returns the alerts raised this time (for tests and logs). */
    suspend fun pollOnce(): List<SevereAlert> {
        val now = clockMs()
        seenStore.forgetOlderThan(now - FORGET_AFTER_MS)
        val seen = seenStore.seen().keys
        val found = linkedMapOf<String, Pair<SevereAlert, String>>()
        val points = listOfNotNull<Pair<ConditionsPoint, String>>(
            here()?.let { it to "where you are" },
            ConditionsPoint.Place(HOME_LABEL) to "at home",
        )
        for ((point, where) in points) {
            val outcome = check(point)
            if (outcome !is ConditionsOutcome.Read) continue
            for (alert in outcome.severe) {
                if (alert.id !in seen && alert.id !in found) found[alert.id] = alert to where
            }
        }
        val raised = mutableListOf<SevereAlert>()
        for ((alert, where) in found.values) {
            if (announce(alert, where) == AlertDelivery.DELIVERED) {
                seenStore.markSeen(alert.id, now)
                raised += alert
            }
        }
        return raised
    }

    /** Polls until cancelled; a failed poll never ends the loop. */
    @Suppress("TooGenericExceptionCaught") // a poll must never end the loop; cancellation is rethrown first
    suspend fun run(intervalMs: Long = POLL_INTERVAL_MS, onCrash: (Throwable) -> Unit = {}) {
        while (currentCoroutineContext().isActive) {
            try {
                pollOnce()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                onCrash(e)
            }
            delay(intervalMs)
        }
    }

    companion object {
        const val HOME_LABEL = "home"
        const val POLL_INTERVAL_MS = 15 * 60_000L

        /** An alert id is kept two days, longer than any NWS warning runs, then forgotten. */
        const val FORGET_AFTER_MS = 48 * 60 * 60_000L

        /** The fact the raise rests on, in the words a person can check against NWS. */
        fun facts(alert: SevereAlert, where: String): String = buildString {
            append("The National Weather Service has an active ${alert.event} (${alert.severity}) $where")
            if (alert.area.isNotBlank()) append(", for ${alert.area.take(AREA_CAP)}")
            if (alert.ends.isNotBlank()) append(", until ${alert.ends}")
            append(", issued by ${alert.sender.ifBlank { "the NWS" }}.")
            if (alert.headline.isNotBlank()) append(" Headline: ${alert.headline}")
        }

        fun prompt(facts: String): String =
            "A severe weather alert is active. The facts, and the only things you may say: $facts " +
                "Tell the user in one or two short sentences: the alert, where, and until when, and " +
                "that it is from the National Weather Service. Give no advice the alert does not " +
                "give, and mention no other subject."

        private const val AREA_CAP = 160
    }
}

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
        // An alert is raised again only when its ring level ROSE (ADR 0082): Severe then Extreme.
        val found = linkedMapOf<String, Pair<SevereAlert, String>>()
        val points = listOfNotNull<Pair<ConditionsPoint, String>>(
            here()?.let { it to "where you are" },
            ConditionsPoint.Place(HOME_LABEL) to "at home",
        )
        for ((point, where) in points) {
            val read = check(point) as? ConditionsOutcome.Read ?: continue
            read.severe.filter { !alreadyRaised(seen, it) && it.id !in found }.forEach { found[it.id] = it to where }
        }
        val raised = mutableListOf<SevereAlert>()
        for ((alert, where) in found.values) {
            if (announce(alert, where) == AlertDelivery.DELIVERED) {
                seenStore.markSeen(seenKey(alert), now)
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

        /**
         * The emergency ring level an alert carries (ADR 0082, Kevin: "if any emergency in the area
         * is noted, alarm me"): Extreme 2, Severe 1. Anything else the engine marks severe (a
         * hurricane watch at a lower NWS severity) stays an ordinary Safety raise, null here.
         */
        fun alarmLevel(severity: String): Int? = RING_LEVELS[severity.trim().lowercase()]

        /** True when an alert of this NWS [event] may RING ([RINGING_EVENTS]); any other is posted. */
        fun mayRing(event: String): Boolean = event.trim().lowercase() in RINGING_EVENTS

        /**
         * The NWS event names that may ring through silent and at night (the owner, 2026-11-21:
         * "ring only for dangerous events"; freeze, frost, heat, air quality and the like are
         * posted). Still only at Severe or Extreme ([RING_LEVELS]). Names as api.weather.gov
         * /alerts/types lists them; lowercase here. Fail safe: a name not listed (a new or renamed
         * event) is posted, never rung. Watches never ring: nothing is happening yet. Each one is
         * a short-fuse threat to life where minutes matter:
         * - tornado warning: a tornado is sighted or on radar (NWS's "tornado emergency" is this
         *   event with a tag, not its own name);
         * - flash flood warning: flooding within minutes ("flash flood emergency" likewise);
         * - severe thunderstorm warning: destructive wind or large hail now;
         * - extreme wind warning: 115 mph or more, the eyewall arriving;
         * - hurricane warning, typhoon warning: hurricane-force wind expected, the last call to
         *   finish preparing or leave;
         * - storm surge warning: life-threatening inundation;
         * - tsunami warning: a dangerous wave is coming;
         * - earthquake warning: shaking is seconds away;
         * - dust storm warning: visibility near zero, deadly on a road;
         * - shelter in place warning, evacuation immediate: officials ordering an action now.
         * One line to add or drop one.
         */
        private val RINGING_EVENTS = setOf(
            "tornado warning", "flash flood warning", "severe thunderstorm warning", "extreme wind warning",
            "hurricane warning", "typhoon warning", "storm surge warning", "tsunami warning",
            "earthquake warning", "dust storm warning", "shelter in place warning", "evacuation immediate",
        )

        /**
         * Which NWS severities ring (through silent, vibrate and DND, at night) and at what level.
         * Today: Severe and Extreme. Owner decision pending (phantom alarm report 2026-11-21): drop
         * "severe" here to ring for Extreme only. One line to change.
         */
        private val RING_LEVELS = mapOf("extreme" to 2, "severe" to 1)

        /** What [SeenAlerts] holds for a raised alert: its id, plus its ring level when it has one. */
        fun seenKey(alert: SevereAlert): String = alarmLevel(alert.severity)?.let { "${alert.id}#$it" } ?: alert.id

        /** True when [alert] was raised before at its ring level or higher (a plain id is level 0). */
        fun alreadyRaised(seen: Set<String>, alert: SevereAlert): Boolean {
            val level = alarmLevel(alert.severity) ?: 0
            return seen.any { key ->
                when {
                    key == alert.id -> level == 0
                    key.startsWith("${alert.id}#") -> (key.substringAfterLast('#').toIntOrNull() ?: 0) >= level
                    else -> false
                }
            }
        }

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

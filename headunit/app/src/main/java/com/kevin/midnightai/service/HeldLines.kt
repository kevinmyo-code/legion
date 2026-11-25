package com.kevin.midnightai.service

/**
 * A short, bounded holding area for proactive lines that could not be spoken the moment they were
 * asked for (voice-pipeline audit B8, B11, B12, 2026-10-15).
 *
 * Before this, three situations lost the line silently: a proactive requested while a socket was
 * still connecting overwrote the conversation that was about to start (B12); one folded into a
 * running conversation cut the model off mid-reply (B11); and one queued on a socket that then
 * failed to connect was dropped by the Closed handler with no trace, which is how a late
 * "Spotify is playing now" report vanished (B8).
 *
 * Three rules keep this honest. A held line is only ever spoken while it is still FRESH: a report
 * that is minutes old describes a moment that has passed, and saying it would be an assertion
 * about the present that nobody observed. A line has a bounded number of FAILED attempts, so a
 * socket that keeps refusing cannot loop the same line forever. And the area is ordered by
 * [ProactiveKind], not by arrival: the most urgent line it holds is the one it gives up, and the
 * least urgent is the one it evicts when full, so three pieces of idle chatter cannot push out an
 * overheat warning or make it wait its turn behind them. Everything that is dropped is reported
 * through `onDropped` so the caller can leave a breadcrumb; nothing leaves silently.
 *
 * Pure (time is passed in), so the rules are JVM tests.
 */
internal class HeldLines(
    private val maxAgeMs: Long,
    private val capacity: Int,
    private val maxFailures: Int = 1,
) {
    data class Held(
        val request: ProactiveBus.SpeakRequest,
        val heldAtMs: Long,
        val failures: Int,
    ) {
        val prompt: String get() = request.prompt
        val kind: ProactiveKind get() = request.kind
    }

    // Insertion order, so "oldest first" within one kind is just list order.
    private val items = mutableListOf<Held>()

    val size: Int get() = items.size

    /**
     * Holds [request]. [failures] is how many times speaking it has already failed. Returns the
     * reason it was NOT held, or null when it was. When the area is full the least urgent line in
     * it is evicted and reported through [onDropped] - unless [request] is itself less urgent than
     * everything held, in which case the incoming line is the one dropped.
     */
    fun hold(
        request: ProactiveBus.SpeakRequest,
        nowMs: Long,
        failures: Int = 0,
        onDropped: (ProactiveBus.SpeakRequest, String) -> Unit,
    ): String? {
        if (failures > maxFailures) {
            onDropped(request, "failed $failures times")
            return "failed $failures times"
        }
        while (items.size >= capacity) {
            val leastUrgent = items.maxOf { it.kind.ordinal }
            if (request.kind.ordinal > leastUrgent) {
                val why = "area full of more urgent lines"
                onDropped(request, why)
                return why
            }
            onDropped(items.removeAt(items.indexOfFirst { it.kind.ordinal == leastUrgent }).request, "area full")
        }
        items.add(Held(request, nowMs, failures))
        return null
    }

    /**
     * The most urgent line still fresh at [nowMs] (oldest first within one kind), or null. Every
     * stale line is dropped, wherever it sits: a stale one behind a fresh one would otherwise
     * linger and be spoken later, which is the very thing the freshness rule exists to prevent.
     */
    fun takeFresh(nowMs: Long, onDropped: (ProactiveBus.SpeakRequest, String) -> Unit): Held? {
        items.removeAll { stale ->
            (nowMs - stale.heldAtMs > maxAgeMs).also {
                if (it) onDropped(stale.request, "stale after ${nowMs - stale.heldAtMs} ms")
            }
        }
        if (items.isEmpty()) return null
        val mostUrgent = items.minOf { it.kind.ordinal }
        return items.removeAt(items.indexOfFirst { it.kind.ordinal == mostUrgent })
    }

    /**
     * What a park takes from the holding area. A conversation that went quiet on the idle timer
     * gets its most urgent fresh line spoken on the warm socket ([takeFresh]); one the driver ENDED
     * drops its ROUTINE lines first (ruled 2026-11-24: a goodbye closes the conversation, so a
     * routine line kept back for a turn boundary has missed its moment and saying it after the
     * sign-off would reopen a conversation he just ended).
     *
     * **An URGENT or REQUESTED line survives the goodbye and is spoken on the warm socket**, which
     * is the ruling's word "routine" taken literally. A fresh trouble code, an overheat, or an
     * answer the driver himself asked for and that arrived late is not chatter: dropping it would
     * be a silence about the car, and for a trouble code a PERMANENT one, since the health
     * monitor's baseline would have moved on. See [ProactiveKind] and [AnnouncedCodes].
     *
     * [afterDismissal] is the only difference between the two, and it is the caller's one read of
     * `LiveEvent.Idle.afterDismissal`.
     */
    fun onPark(
        afterDismissal: Boolean,
        nowMs: Long,
        onDropped: (ProactiveBus.SpeakRequest, String) -> Unit,
    ): Held? {
        if (afterDismissal) {
            items.removeAll { held ->
                (!held.kind.survivesGoodbye).also {
                    if (it) onDropped(held.request, "the driver said goodbye")
                }
            }
        }
        return takeFresh(nowMs, onDropped)
    }

    /** Drops everything (session ended); each line is reported with [why]. */
    fun clear(why: String, onDropped: (ProactiveBus.SpeakRequest, String) -> Unit) {
        while (items.isNotEmpty()) onDropped(items.removeAt(0).request, why)
    }
}

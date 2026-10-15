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
 * Two rules keep this honest. A held line is only ever spoken while it is still FRESH: a report
 * that is minutes old describes a moment that has passed, and saying it would be an assertion
 * about the present that nobody observed. And a line has a bounded number of FAILED attempts, so
 * a socket that keeps refusing cannot loop the same line forever. Everything that is dropped is
 * reported through [onDropped] so the caller can leave a breadcrumb; nothing leaves silently.
 *
 * Pure (time is passed in), so the rules are JVM tests.
 */
internal class HeldLines(
    private val maxAgeMs: Long,
    private val capacity: Int,
    private val maxFailures: Int = 1,
) {
    data class Held(val prompt: String, val heldAtMs: Long, val failures: Int)

    private val items = ArrayDeque<Held>()

    val size: Int get() = items.size

    /**
     * Holds [prompt]. [failures] is how many times speaking it has already failed. Returns the
     * reason it was NOT held (too many failures), or null when it was. When the area is full the
     * oldest line is evicted and reported through [onDropped].
     */
    fun hold(prompt: String, nowMs: Long, failures: Int = 0, onDropped: (String, String) -> Unit): String? {
        if (failures > maxFailures) {
            onDropped(prompt, "failed $failures times")
            return "failed $failures times"
        }
        while (items.size >= capacity) {
            onDropped(items.removeFirst().prompt, "area full")
        }
        items.addLast(Held(prompt, nowMs, failures))
        return null
    }

    /** The oldest line still fresh at [nowMs], or null. Stale lines ahead of it are dropped. */
    fun takeFresh(nowMs: Long, onDropped: (String, String) -> Unit): Held? {
        while (items.isNotEmpty()) {
            val h = items.removeFirst()
            if (nowMs - h.heldAtMs <= maxAgeMs) return h
            onDropped(h.prompt, "stale after ${nowMs - h.heldAtMs} ms")
        }
        return null
    }

    /** Drops everything (session ended); each line is reported with [why]. */
    fun clear(why: String, onDropped: (String, String) -> Unit) {
        while (items.isNotEmpty()) onDropped(items.removeFirst().prompt, why)
    }
}

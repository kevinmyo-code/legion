package com.kevin.legion.dumb

/**
 * Dumb phone mode's whole persisted state (ADR 0062). Three facts: is it on, since when, and has
 * Kevin asked to leave (and when). Every time is WALL-CLOCK epoch millis, never an uptime or a
 * running counter, so a locked phone, a backgrounded app and a killed process all see the same
 * answer to "how long has the leave timer run".
 */
data class DumbModeState(
    val on: Boolean = false,
    /** When the current run began; meaningless while [on] is false. */
    val startedAtMs: Long = 0L,
    /** When "Leave" was tapped; null when no leave is pending. Cleared by Stay. */
    val leaveRequestedAtMs: Long? = null,
)

/** One finished run of dumb mode. History is kept from slice 1 because the streak (slice 5) needs it. */
data class DumbInterval(val startMs: Long, val endMs: Long) {
    init {
        require(endMs >= startMs) { "interval ends before it starts" }
    }
}

/** What is persisted: the live state plus the closed runs, oldest first. */
data class DumbModeSnapshot(
    val state: DumbModeState = DumbModeState(),
    val history: List<DumbInterval> = emptyList(),
)

/**
 * The persistence seam. It earns an interface because the wall-clock and recreation tests must
 * read the same state through a SECOND controller, which needs a fake, not Robolectric.
 */
interface DumbModeStore {
    fun read(): DumbModeSnapshot
    fun write(snapshot: DumbModeSnapshot)
}

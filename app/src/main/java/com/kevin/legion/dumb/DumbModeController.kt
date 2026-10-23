package com.kevin.legion.dumb

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Why a leave attempt did or did not go through. Every refusal is worded for the screen. */
sealed interface LeaveResult {
    data object Left : LeaveResult
    data object NotOn : LeaveResult
    data object NotRequested : LeaveResult
    /** The 60 second wait has not finished. */
    data class TooEarly(val remainingMs: Long) : LeaveResult
    data object WrongSentence : LeaveResult
}

/** The leave rules, pure so the screen and the tests read the same ones. */
object DumbLeaveRule {
    const val WAIT_MS = 60_000L
    const val SENTENCE = "I am choosing to leave dumb mode"

    /** Case-insensitive and trimmed (Kevin, ADR 0062). Inner spacing must match. */
    fun sentenceMatches(typed: String): Boolean = typed.trim().equals(SENTENCE, ignoreCase = true)

    /**
     * Time left on the wait. Clamped to [0, WAIT_MS]: a wall clock set BACKWARDS must not stretch
     * the wait past a minute. (Set forwards it can shorten it; this is friction, not a lock.)
     */
    fun remainingMs(requestedAtMs: Long, nowMs: Long): Long = (WAIT_MS - (nowMs - requestedAtMs)).coerceIn(0L, WAIT_MS)
}

/**
 * Dumb phone mode's state machine (ADR 0062): off, on, on with a leave pending. A plain class
 * with its clock and store passed in; Hilt is not in the build yet (architecture ticket 02), so
 * `MidnightApplication.dumbMode` holds the one process-wide instance and is the injection point.
 *
 * **The leave timer is the persisted WALL-CLOCK instant of the tap**, not a countdown held in
 * memory: "remaining" is always recomputed from `now - leaveRequestedAt`, so locking the phone,
 * backgrounding the app or losing the process cannot reset or pause it. That is Kevin's ruling and
 * the reason the state lives in a [DumbModeStore].
 */
class DumbModeController(
    private val store: DumbModeStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private var history: List<DumbInterval>
    private val _state: MutableStateFlow<DumbModeState>

    init {
        val snapshot = store.read()
        history = snapshot.history
        _state = MutableStateFlow(snapshot.state)
    }

    val state: StateFlow<DumbModeState> = _state.asStateFlow()

    /** Closed runs, oldest first. The current run is [DumbModeState.startedAtMs] and is not in here. */
    fun history(): List<DumbInterval> = synchronized(lock) { history }

    /** Turns the mode on. Returns false when it already was (the run keeps its original start). */
    fun turnOn(): Boolean = synchronized(lock) {
        if (_state.value.on) return false
        commit(DumbModeState(on = true, startedAtMs = clock(), leaveRequestedAtMs = null), history)
        true
    }

    /**
     * Starts the leave wait. Idempotent: a second tap, or the screen being recreated, keeps the
     * original instant, so the wait is never restarted by looking at it again.
     */
    fun requestLeave(): Boolean = synchronized(lock) {
        val s = _state.value
        if (!s.on) return false
        if (s.leaveRequestedAtMs == null) commit(s.copy(leaveRequestedAtMs = clock()), history)
        true
    }

    /** "Stay in dumb mode": drops the pending leave. */
    fun stay() = synchronized(lock) {
        val s = _state.value
        if (s.on && s.leaveRequestedAtMs != null) commit(s.copy(leaveRequestedAtMs = null), history)
    }

    /** Milliseconds of wait left, or null when no leave is pending. */
    fun leaveRemainingMs(): Long? = synchronized(lock) {
        val s = _state.value
        val requested = s.leaveRequestedAtMs
        if (!s.on || requested == null) null else DumbLeaveRule.remainingMs(requested, clock())
    }

    /** Leaves when the wait is over AND [typed] matches the sentence; otherwise changes nothing. */
    fun leave(typed: String): LeaveResult = synchronized(lock) {
        val s = _state.value
        val requested = s.leaveRequestedAtMs
        when {
            !s.on -> LeaveResult.NotOn
            requested == null -> LeaveResult.NotRequested
            else -> {
                val remaining = DumbLeaveRule.remainingMs(requested, clock())
                when {
                    remaining > 0L -> LeaveResult.TooEarly(remaining)
                    !DumbLeaveRule.sentenceMatches(typed) -> LeaveResult.WrongSentence
                    else -> {
                        val end = clock()
                        val closed = DumbInterval(s.startedAtMs, maxOf(end, s.startedAtMs))
                        commit(DumbModeState(), prune(history + closed, end))
                        LeaveResult.Left
                    }
                }
            }
        }
    }

    private fun commit(state: DumbModeState, newHistory: List<DumbInterval>) {
        history = newHistory
        store.write(DumbModeSnapshot(state, newHistory))
        _state.value = state
    }

    private fun prune(all: List<DumbInterval>, nowMs: Long): List<DumbInterval> =
        all.filter { it.endMs >= nowMs - HISTORY_KEEP_MS }

    companion object {
        /** About 13 months: more than any streak the counter will show, bounded so prefs stay small. */
        const val HISTORY_KEEP_MS = 400L * 24 * 60 * 60 * 1000
    }
}

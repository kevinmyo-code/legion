package com.kevin.legion.clock

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The one clock controller (ADR 0079, ADR 0035): the Clock screen, the `clock` voice tool, the
 * notification buttons and the receivers all call it. Split by feature into [alarms], [timers],
 * [stopwatch] and [bedtime] over one shared [ClockCore], so no file carries all of it.
 *
 * Works offline and without Gemini: nothing here touches the network or the Live session.
 */
class ClockController(
    store: ClockStore,
    time: ClockTime,
    scheduler: ClockScheduler,
    notices: ClockNotices,
    ringer: Ringer,
    effects: BedtimeEffects,
) {
    private val core = ClockCore(store, time, scheduler, notices, ringer)

    val state: StateFlow<ClockState> get() = core.state
    val time: ClockTime get() = core.time

    val bedtime = BedtimeOps(core, effects)
    val alarms = AlarmOps(core, onDismissed = bedtime::afterAlarm)
    val timers = TimerOps(core)
    val stopwatch = StopwatchOps(core)

    /**
     * Idempotent re-arm of everything (ADR 0079): locked boot, boot, time set, zone change, app
     * update, the exact-alarm grant changing, and app start all call this and nothing else.
     */
    fun rearmAll() {
        alarms.rearm()
        timers.rearm()
        stopwatch.rebase()
        bedtime.rearm()
    }
}

/** State, store and side-effect ports shared by the feature ops. Every write goes through [edit]. */
class ClockCore(
    private val store: ClockStore,
    val time: ClockTime,
    val scheduler: ClockScheduler,
    val notices: ClockNotices,
    val ringer: Ringer,
) {
    private val lock = Any()
    private val mutable = MutableStateFlow(store.load())
    val state: StateFlow<ClockState> = mutable.asStateFlow()

    fun read(): ClockState = synchronized(lock) { store.load().also { mutable.value = it } }

    /** Reads, applies [block], saves, publishes. [block] returns the new state and its answer. */
    fun <T> edit(block: (ClockState) -> Pair<ClockState, T>): T = synchronized(lock) {
        val (next, answer) = block(store.load())
        store.save(next)
        mutable.value = next
        answer
    }

    fun newId(s: ClockState): Pair<ClockState, Int> = s.copy(nextId = s.nextId + 1) to s.nextId
}

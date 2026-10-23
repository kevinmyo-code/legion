package com.kevin.legion.dumb

import android.content.Context
import android.content.SharedPreferences

/**
 * [DumbModeStore] over SharedPreferences. Synchronous `commit()` writes: the mode flips from a
 * Quick Settings tile that may be torn down the moment the tap returns, and a leave request must
 * survive the process dying a second later. Intervals are two longs each in one string
 * ("start-end;start-end"); a malformed pair is dropped rather than failing the read, because a
 * corrupt history must never lock Kevin out of reading whether the mode is on.
 */
class PrefsDumbModeStore(private val prefs: SharedPreferences) : DumbModeStore {

    constructor(context: Context) : this(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE))

    override fun read(): DumbModeSnapshot {
        val requested = prefs.getLong(KEY_LEAVE_REQUESTED, NONE).takeIf { it != NONE }
        val state = DumbModeState(
            on = prefs.getBoolean(KEY_ON, false),
            startedAtMs = prefs.getLong(KEY_STARTED, 0L),
            leaveRequestedAtMs = requested,
        )
        return DumbModeSnapshot(state, parseHistory(prefs.getString(KEY_HISTORY, "").orEmpty()))
    }

    override fun write(snapshot: DumbModeSnapshot) {
        val s = snapshot.state
        prefs.edit()
            .putBoolean(KEY_ON, s.on)
            .putLong(KEY_STARTED, s.startedAtMs)
            .putLong(KEY_LEAVE_REQUESTED, s.leaveRequestedAtMs ?: NONE)
            .putString(KEY_HISTORY, formatHistory(snapshot.history))
            .commit()
    }

    internal companion object {
        const val PREFS = "dumb_mode"
        private const val KEY_ON = "on"
        private const val KEY_STARTED = "started_at"
        private const val KEY_LEAVE_REQUESTED = "leave_requested_at"
        private const val KEY_HISTORY = "history"
        private const val NONE = -1L

        fun formatHistory(history: List<DumbInterval>): String =
            history.joinToString(";") { "${it.startMs}-${it.endMs}" }

        fun parseHistory(raw: String): List<DumbInterval> = raw.split(';').mapNotNull(::parsePair)

        private fun parsePair(pair: String): DumbInterval? {
            val numbers = pair.split('-').mapNotNull { it.toLongOrNull() }
            val wellFormed = pair.count { it == '-' } == 1 && numbers.size == 2 && numbers[1] >= numbers[0]
            return if (wellFormed) DumbInterval(numbers[0], numbers[1]) else null
        }
    }
}
